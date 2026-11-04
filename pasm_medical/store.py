# -*- coding: utf-8 -*-
"""会话存储 —— 把「进程内状态」抽成**可替换**的存储接口。

为什么需要这层
--------------
认知服务有两块**跨请求**状态：

* 进行中的预问诊会话（``MedicalService._consults``）
* 待人工确认的检验单（``MedicalService._lab_reports``）

第一版把它们放在进程内字典里。单实例够用，但有两个硬伤：

1. **重启即丢** —— 患者填到一半刷新/服务升级，会话就没了；
2. **多实例不共享** —— 两个 worker 各自持有一份，请求被负载均衡打到另一台上就
   会得到"没有进行中的问诊"（看起来像 bug，其实是状态不在这里）。

本模块提供两个实现，调用方只依赖 :class:`SessionStore` 协议：

* :class:`MemorySessionStore` —— 默认，零依赖，适合测试与单实例；
* :class:`FileSessionStore`   —— JSON 落盘 + **原子替换**，适合单机多 worker / 重启不丢。

⚠️ 多**主机**部署请换成 Redis / DB 实现（同一个协议，改 ``build_store`` 一行即可）。
   ``FileSessionStore`` 在多实例共享同一目录时是 **last-write-wins**：它保证了
   "不会读到写了一半的文件"，但没有分布式锁，同一会话被两台机器同时写仍会互相覆盖。
"""
from __future__ import annotations

import hashlib
import json
import os
import tempfile
import time
from pathlib import Path
from typing import Any, Optional, Protocol, runtime_checkable

#: 会话默认存活时间（秒）。6 小时足够覆盖一次问诊 + 中途离开再回来。
DEFAULT_TTL = 6 * 3600


@runtime_checkable
class SessionStore(Protocol):
    """跨请求状态的最小契约。值必须是 **JSON 可序列化** 的。"""

    def get(self, key: str) -> Optional[Any]: ...

    def put(self, key: str, value: Any, ttl: int = DEFAULT_TTL) -> None: ...

    def pop(self, key: str) -> Optional[Any]: ...

    def stats(self) -> dict: ...


class MemorySessionStore:
    """进程内实现（默认）。零依赖，但**重启即丢、不跨实例**。"""

    def __init__(self) -> None:
        self._data: dict[str, tuple[float, Any]] = {}

    def get(self, key: str) -> Optional[Any]:
        item = self._data.get(key)
        if item is None:
            return None
        exp, value = item
        if exp and exp < time.time():
            self._data.pop(key, None)
            return None
        return value

    def put(self, key: str, value: Any, ttl: int = DEFAULT_TTL) -> None:
        self._data[key] = ((time.time() + ttl) if ttl else 0.0, value)

    def pop(self, key: str) -> Optional[Any]:
        item = self._data.pop(key, None)
        if item is None:
            return None
        exp, value = item
        return None if (exp and exp < time.time()) else value

    def stats(self) -> dict:
        return {"kind": "memory", "entries": len(self._data)}


class FileSessionStore:
    """JSON 文件实现：原子写（临时文件 + ``os.replace``），重启不丢。

    ⚠️ 同一会话被多个进程同时写时是 last-write-wins（没有分布式锁）。
    对"一个患者同一时刻只在一个实例上问诊"的用法是够的；高并发多主机请换 Redis/DB。
    """

    def __init__(self, root: str | os.PathLike[str]) -> None:
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)

    # 键里可能含 ``:`` / 中文 / 很长 —— 用哈希做文件名，避免非法字符与长度问题
    def _path(self, key: str) -> Path:
        h = hashlib.sha256(key.encode("utf-8")).hexdigest()[:32]
        return self.root / (h + ".json")

    def get(self, key: str) -> Optional[Any]:
        p = self._path(key)
        try:
            payload = json.loads(p.read_text(encoding="utf-8"))
        except Exception:                                  # 不存在 / 半截文件 / 非法 JSON
            return None
        exp = float(payload.get("exp") or 0.0)
        if exp and exp < time.time():
            try:
                p.unlink()
            except OSError:
                pass
            return None
        return payload.get("v")

    def put(self, key: str, value: Any, ttl: int = DEFAULT_TTL) -> None:
        p = self._path(key)
        payload = {"k": key, "exp": (time.time() + ttl) if ttl else 0.0, "v": value}
        fd, tmp = tempfile.mkstemp(dir=str(self.root), suffix=".tmp")
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(payload, f, ensure_ascii=False)
            # ★ 原子替换：读方要么看到旧文件、要么看到完整的新文件，绝不会看到写了一半的
            os.replace(tmp, p)
        finally:
            try:
                if os.path.exists(tmp):
                    os.unlink(tmp)
            except OSError:
                pass

    def pop(self, key: str) -> Optional[Any]:
        value = self.get(key)
        try:
            self._path(key).unlink()
        except OSError:
            pass
        return value

    def stats(self) -> dict:
        try:
            n = sum(1 for _ in self.root.glob("*.json"))
        except OSError:
            n = 0
        return {"kind": "file", "dir": str(self.root), "entries": n}


def build_store(kind: str = "", root: str = "") -> SessionStore:
    """按配置构造存储。

    ``kind`` 为空时读环境变量 ``PASM_MEDICAL_SESSION_STORE``（``memory`` / ``file``），
    默认 ``memory`` —— **测试与单实例不需要任何额外配置**。
    ``root`` 为空时读 ``PASM_MEDICAL_SESSION_DIR``。

    选 ``file`` 却没给目录时**如实报错**，不要悄悄退回 memory：
    后者会让人以为"已经多实例可用了"，实际仍在丢状态。
    """
    k = (kind or os.environ.get("PASM_MEDICAL_SESSION_STORE") or "memory").strip().lower()
    if k in ("file", "disk", "json"):
        d = root or os.environ.get("PASM_MEDICAL_SESSION_DIR") or ""
        if not d:
            raise ValueError(
                "PASM_MEDICAL_SESSION_STORE=file 时必须指定会话目录"
                "（参数 root 或环境变量 PASM_MEDICAL_SESSION_DIR）")
        return FileSessionStore(d)
    if k not in ("memory", "mem", "inmemory", "in-memory"):
        # 未知取值不静默降级：写错配置却"看起来能跑"是最难查的一类问题
        raise ValueError("未知的会话存储类型：%r（支持 memory / file）" % k)
    return MemorySessionStore()


# ============================================================ 自检


def selftest() -> bool:
    """存储层自检（``python -m pasm_medical.store``）。

    ★ 重点不是"put 完能 get"，而是两条**真正决定能不能多实例**的性质：
      ① file 存储跨实例可见（两个对象 = 两个 worker）；
      ② 过期项读不到且会被清理（否则会话永久堆积）。
    """
    import shutil

    ok = fail = 0

    def check(name: str, cond: bool, detail: str = "") -> None:
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  v %s" % name)
        else:
            fail += 1
            print("  x %s  <- %s" % (name, detail))

    print("会话存储自检")
    print("-" * 60)

    # ---- memory
    m = MemorySessionStore()
    m.put("a:b", {"x": 1})
    check("memory：put/get 往返", m.get("a:b") == {"x": 1}, str(m.get("a:b")))
    check("memory：pop 后取不到", m.pop("a:b") == {"x": 1} and m.get("a:b") is None)
    m._data["old"] = (time.time() - 1, {"z": 3})
    check("memory：过期项读不到（并顺手清掉）",
          m.get("old") is None and "old" not in m._data)

    # ---- file（两个对象 = 两个 worker 共享同一目录）
    d = tempfile.mkdtemp(prefix="pasm-store-")
    try:
        s1 = FileSessionStore(d)
        s2 = FileSessionStore(d)
        s1.put("p:1", {"facts": {"主诉": "胸痛"}})
        check("file：跨实例可见（多 worker 共享的关键）",
              s2.get("p:1") == {"facts": {"主诉": "胸痛"}}, str(s2.get("p:1")))
        check("file：pop 后另一实例也取不到",
              s2.pop("p:1") is not None and s1.get("p:1") is None)

        s1.put("p:2", {"a": 1})
        files = list(Path(d).glob("*.json"))
        parsed = json.loads(files[0].read_text(encoding="utf-8")) if files else {}
        check("file：落盘是完整 JSON（原子写不留半截）",
              len(files) == 1 and parsed.get("v") == {"a": 1},
              "files=%d payload=%s" % (len(files), str(parsed)[:80]))

        s1.put("p:3", {"b": 2}, ttl=1)
        p3 = s1._path("p:3")
        payload = json.loads(p3.read_text(encoding="utf-8"))
        payload["exp"] = time.time() - 1          # 手工置为已过期
        p3.write_text(json.dumps(payload), encoding="utf-8")
        check("file：过期项读不到且文件被清理",
              s2.get("p:3") is None and not p3.exists())
    finally:
        shutil.rmtree(d, ignore_errors=True)

    # ---- 配置错误要如实报错
    for kind, kw in (("file", {}), ("redis? typo", {"root": "x"})):
        raised = False
        try:
            build_store(kind, **kw)
        except ValueError:
            raised = True
        check("build_store：%r 如实报错（不静默降级）" % kind, raised)

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                      # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
