# -*- coding: utf-8 -*-
"""pasm-medical MCP 端到端验证 —— 真起一个 stdio 子进程，跑完整 JSON-RPC 握手 + 核心工具。

为什么必须走真进程
------------------
MCP 的协议正确性（stdout 只能有 JSON-RPC、每行一条、写完 flush、Windows 下 buffer）
只有真起子进程、真读 stdout 才能证明。在进程内直接调函数验不出这些。

核心断言
--------
1. 版本协商回显；
2. 工具清单不含内部字段；
3. **患者隔离**：造两个患者，断言彼此的过敏史在召回里一条都看不到；
4. 无依据问答必须拒答（不编造）；
5. 附子+半夏 → blocking；
6. ★ **stdout 每行都是合法 JSON-RPC 2.0** —— 这是 MCP 协议正确性的底线。

用法::

    python tools/e2e_medical_mcp.py
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
PY = sys.executable

# 把 pasm 三件套加进子进程的 PYTHONPATH（安装态可省略，本地开发需要）
_EXTRA = [str(REPO), str(REPO.parent / "pasm-skills"),
          str(REPO.parent / "pasm-framework")]


def _env() -> dict:
    env = dict(os.environ)
    cur = env.get("PYTHONPATH", "")
    parts = [p for p in _EXTRA if os.path.isdir(p)]
    env["PYTHONPATH"] = os.pathsep.join([*parts, cur]) if cur else os.pathsep.join(parts)
    env["PASM_MEDICAL_MCP_QUIET"] = "1"   # 压住子进程 stderr 日志，避免干扰
    return env


OK = FAIL = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global OK, FAIL
    if cond:
        OK += 1
        print("  v %s" % name)
    else:
        FAIL += 1
        print("  x %s%s" % (name, ("  <- " + detail) if detail else ""))


def main() -> int:
    print("pasm-medical MCP 端到端验证（stdio 子进程）")
    print("-" * 64)

    tmp = tempfile.mkdtemp(prefix="pasm-med-mcp-e2e-")
    try:
        proc = subprocess.Popen(
            [PY, "-m", "pasm_medical.mcp.server", "--dir", tmp],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.PIPE, env=_env(), text=True, bufsize=1)

        def send(method: str, params=None, req_id=None):
            msg = {"jsonrpc": "2.0", "method": method}
            if req_id is not None:
                msg["id"] = req_id
            if params is not None:
                msg["params"] = params
            proc.stdin.write(json.dumps(msg, ensure_ascii=False) + "\n")
            proc.stdin.flush()

        def recv():
            line = proc.stdout.readline()
            if not line:
                return None
            return json.loads(line)

        # ---------- 1. initialize（版本协商回显）----------
        send("initialize", {"protocolVersion": "2024-11-05"}, 1)
        r = recv()
        check("initialize 回 jsonrpc=2.0", r.get("jsonrpc") == "2.0", str(r)[:120])
        check("版本协商回显客户端版本 2024-11-05",
              r.get("result", {}).get("protocolVersion") == "2024-11-05", str(r)[:120])
        # 通知类：无 id，不回复
        proc.stdin.write(json.dumps({"jsonrpc": "2.0",
                                     "method": "notifications/initialized"}) + "\n")
        proc.stdin.flush()

        # ---------- 2. tools/list ----------
        send("tools/list", {}, 2)
        r = recv()
        tools = (r.get("result") or {}).get("tools", [])
        names = [t["name"] for t in tools]
        check("tools/list 返回 >= 10 个工具", len(names) >= 10, "共 %d" % len(names))
        check("对外清单不含内部 fn 字段", "fn" not in (tools[0] if tools else {}))
        check("含核心工具 med_ask / med_check_herbs",
              "med_ask" in names and "med_check_herbs" in names)

        # ---------- 3. 患者级隔离 ----------
        pa, pb = "patient-A", "patient-B"
        send("tools/call", {"name": "med_record_allergy",
                            "arguments": {"patient_ref": pa, "allergen": "青霉素",
                                          "reaction": "皮疹"}}, 3)
        recv()
        send("tools/call", {"name": "med_record_allergy",
                            "arguments": {"patient_ref": pb, "allergen": "磺胺",
                                          "reaction": "发热"}}, 4)
        recv()

        send("tools/call", {"name": "med_recall",
                            "arguments": {"patient_ref": pa, "query": "过敏"}}, 5)
        r = recv()
        ta = [x.get("title", "") for x in
              (r.get("result", {}).get("structuredContent") or {}).get("hits", [])]
        check("★ 患者 A 能看到自己的过敏史（青霉素）", any("青霉素" in t for t in ta), str(ta))
        check("★ 患者 A **看不到**患者 B 的过敏史（磺胺）",
              not any("磺胺" in t for t in ta), str(ta))
        check("召回结果带 requires_physician_confirmation",
              (r.get("result", {}).get("structuredContent") or {})
              .get("requires_physician_confirmation") is True)

        send("tools/call", {"name": "med_recall",
                            "arguments": {"patient_ref": pb, "query": "过敏"}}, 6)
        r = recv()
        tb = [x.get("title", "") for x in
              (r.get("result", {}).get("structuredContent") or {}).get("hits", [])]
        check("★ 患者 B 能看到自己的、看不到 A 的",
              any("磺胺" in t for t in tb) and not any("青霉素" in t for t in tb), str(tb))

        # ---------- 4. 带护栏问答：无依据拒答 ----------
        send("tools/call", {"name": "med_ask",
                            "arguments": {"patient_ref": pb,
                                          "question": "髋关节置换术后康复方案"}}, 7)
        r = recv()
        g = r.get("result", {}).get("structuredContent") or {}
        check("★ 无依据时必须拒答（不编造）",
              g.get("refused") is True and "没有查到" in (g.get("text") or ""),
              str(g)[:200])

        # ---------- 5. 确定性规则 ----------
        send("tools/call", {"name": "med_check_herbs",
                            "arguments": {"herbs": ["附子", "半夏"]}}, 8)
        r = recv()
        c = r.get("result", {}).get("structuredContent") or {}
        check("★ 十八反·附子+半夏 判为 blocking", c.get("blocking") is True, str(c)[:200])
        check("规则结果带'空违例≠安全'说明", "不表示安全" in (c.get("note") or ""))

        # ---------- 6. health ----------
        send("tools/call", {"name": "med_health", "arguments": {}}, 9)
        r = recv()
        hc = r.get("result", {}).get("structuredContent") or {}
        check("med_health 报告认知层可用", hc.get("cognitive_available") is True, str(hc)[:160])

        # ---------- 7. 关掉 stdin，读余下 stdout 做协议正确性断言 ----------
        proc.stdin.close()
        bad_lines = 0
        remaining = 0
        for line in proc.stdout:
            line = line.strip()
            if not line:
                continue
            remaining += 1
            try:
                obj = json.loads(line)
                if obj.get("jsonrpc") != "2.0" or "id" not in obj and "method" not in obj:
                    bad_lines += 1
            except Exception:
                bad_lines += 1
        check("★ 退出前 stdout 全部是合法 JSON-RPC 2.0", bad_lines == 0,
              "坏行 %d / 共 %d" % (bad_lines, remaining))

        proc.stdout.close()
        proc.wait(timeout=15)
        check("子进程退出码为 0", proc.returncode == 0, "rc=%s" % proc.returncode)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)

    print("-" * 64)
    print("结果：%d 项通过，%d 项失败" % (OK, FAIL))
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
