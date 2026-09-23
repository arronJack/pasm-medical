# -*- coding: utf-8 -*-
"""医学动作后验 —— 自包含的 Beta-Bernoulli 学习器。

为什么在医疗侧自建、不调框架的 worldmodel
----------------------------------------
框架的 ``worldmodel.observe_op`` / ``predict_op`` **从未**接入医疗认知栈
（2026-09-22 实测确认），且「框架能力必须先发布到 PyPI 才能依赖」是硬约束。
为它新开接线要改 ``pasm-framework`` + ``pasm-skills`` 并重发，跨边界风险过高。
因此这里做一个**自包含、线程安全、可落盘**的 Beta-Bernoulli 后验，只服务于
「医生对某条医学建议的采纳 / 否决，如何改变同类建议的排序」这一个目标。

★ 核心铁律：绝不训练聊天动作池（那是原实现改错的对象）。
---------------------------------------------------------
本后验的键是 ``(医学动作, 处境)``，与聊天动作池
``'greet' / 'ask' / 'share' / 'teach' / 'listen'`` **完全隔离**。
原 ``/api/cog/feedback`` 把反馈写进了聊天动作池 → 医学判断分毫未变、纯属练错了对象。
本模块把信号改接到医学动作后验，并只影响「分诊候选排序」，不碰任何安全规则。

动作枚举（与 ``docs/PLAN-CASE-LEARNING.md`` §4 一致）：
    triage:<科室>   例如 triage:心内科 / 急诊内科
    ask:<问题key>   例如 ask:放射
    advise:<类别>   例如 advise:用药交代
处境键（固定模板，由前端/调用方拼好传进来）：
    complaint=咳嗽;age_band=成年;redflag=无
"""
from __future__ import annotations

import json
import os
import threading
from typing import Any, Dict, Optional

__all__ = ["MedicalPosterior", "ACTION_PREFIXES", "PRIOR_ALPHA", "PRIOR_BETA"]

#: 医学动作前缀（与 docs/PLAN-CASE-LEARNING.md §4 一致）。
ACTION_PREFIXES = ("triage:", "ask:", "advise:")

#: 先验 Beta(1, 1) = 均匀先验，未观测键的采纳概率 = 0.5。
PRIOR_ALPHA = 1.0
PRIOR_BETA = 1.0

#: 键里分隔动作与处境的分隔符（\u0001 不会出现在正常文本里）。
_SEP = "\u0001"


def _valid_action(action: str) -> bool:
    return isinstance(action, str) and action.startswith(ACTION_PREFIXES)


class MedicalPosterior:
    """Beta-Bernoulli 后验（键 = ``(动作, 处境)``）。

    观测语义（与 P0.5 验收一致）：
      - 成功（adopt 采纳）    → alpha += 1
      - 非成功（modify / reject 修改 / 否决） → beta += 1
    后验均值 = alpha / (alpha + beta)；未观测键返回先验均值 0.5。

    落盘为 JSON，所有读写加锁 —— 进程内多请求 / 多问诊并发安全。
    路径为空（测试）时只在内存里跑，不落盘。
    """

    def __init__(self, path: Optional[str] = None) -> None:
        self._path = path
        self._lock = threading.Lock()
        # _data: key -> {"alpha": float, "beta": float, "n": int}
        self._data: Dict[str, Dict[str, float]] = {}
        if path:
            self.load(path)

    # -------------------------------------------------- 键与持久化

    @staticmethod
    def _key(action: str, context: str) -> str:
        return "%s%s%s" % (action, _SEP, context or "")

    def load(self, path: str) -> None:
        """从 JSON 载入（不存在 / 损坏都不抛，退化为空）。"""
        try:
            with open(path, "r", encoding="utf-8") as f:
                obj = json.load(f)
            data = obj.get("data", {}) if isinstance(obj, dict) else {}
            self._data = {
                k: {"alpha": float(v.get("alpha", PRIOR_ALPHA)),
                    "beta": float(v.get("beta", PRIOR_BETA)),
                    "n": int(v.get("n", 0))}
                for k, v in (data or {}).items()
            }
        except FileNotFoundError:
            self._data = {}
        except Exception:                       # pragma: no cover
            self._data = {}

    def save(self) -> None:
        """原子落盘（先写 .tmp 再 rename）。无路径则跳过。"""
        if not self._path:
            return
        try:
            os.makedirs(os.path.dirname(self._path) or ".", exist_ok=True)
            tmp = self._path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump({"data": self._data}, f,
                          ensure_ascii=False, indent=2)
            os.replace(tmp, self._path)
        except Exception:                       # pragma: no cover
            pass

    # -------------------------------------------------- 核心接口

    def observe_op(self, action: str, context: str, success: bool) -> Dict[str, Any]:
        """记录一次观测（success=True 表示采纳）。

        返回更新后的该键统计；非法动作前缀返回 ok=False（不写入）。
        """
        if not _valid_action(action):
            return {"ok": False, "error": "illegal action prefix",
                    "valid_prefixes": list(ACTION_PREFIXES)}
        key = self._key(action, context)
        with self._lock:
            rec = self._data.get(key) or {
                "alpha": PRIOR_ALPHA, "beta": PRIOR_BETA, "n": 0}
            if success:
                rec["alpha"] += 1.0
            else:
                rec["beta"] += 1.0
            rec["n"] += 1
            self._data[key] = rec
            self.save()
            return {"ok": True, "action": action, "context": context,
                    "success": success, "alpha": rec["alpha"],
                    "beta": rec["beta"], "n": rec["n"],
                    "probability": self._mean(rec)}

    def predict_op(self, action: str, context: str) -> float:
        """返回该 ``(动作, 处境)`` 的采纳概率（后验均值）。未观测 = 先验 0.5。"""
        key = self._key(action, context)
        with self._lock:
            rec = self._data.get(key)
            return self._mean(rec)

    # -------------------------------------------------- 内部

    @staticmethod
    def _mean(rec: Optional[Dict[str, float]]) -> float:
        if not rec:
            return PRIOR_ALPHA / (PRIOR_ALPHA + PRIOR_BETA)
        return rec["alpha"] / (rec["alpha"] + rec["beta"])

    def stats(self) -> Dict[str, Any]:
        with self._lock:
            return {"ok": True, "keys": len(self._data),
                    "data": {k: dict(v) for k, v in self._data.items()}}


# ============================================================ 自检

def selftest() -> bool:
    ok = fail = 0

    def check(name: str, cond: bool, detail: str = "") -> None:
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  v %s" % name)
        else:
            fail += 1
            print("  x %s%s" % (name, ("  <- " + detail) if detail else ""))

    print("pasm_medical.learning 自检")
    print("-" * 60)

    p = MedicalPosterior()                       # 内存模式（不落盘）

    # ---- 先验：未观测键 = 0.5
    check("未观测键的采纳概率 = 0.5（均匀先验）",
          p.predict_op("triage:心内科 / 急诊内科", "complaint=胸痛") == 0.5)

    # ---- 采纳 → 概率上升
    r1 = p.observe_op("triage:心内科 / 急诊内科", "complaint=胸痛", True)
    check("采纳后概率上升（Beta(2,1)=0.667）",
          abs(r1["probability"] - 2 / 3) < 1e-9, str(r1))
    check("采纳后 n=1", r1["n"] == 1, str(r1))

    # ---- 否决 → 概率下降
    r2 = p.observe_op("triage:心内科 / 急诊内科", "complaint=胸痛", False)
    check("否决后概率下降（Beta(2,2)=0.5）",
          abs(r2["probability"] - 0.5) < 1e-9, str(r2))

    # ---- ★ 处境隔离：同一动作不同处境互不影响（这是学习"按处境"的硬要求）
    check("另一处境的概率不受本处境否决影响（仍是先验 0.5）",
          p.predict_op("triage:心内科 / 急诊内科", "complaint=咳嗽") == 0.5)

    # ---- ★ 动作隔离：医学动作后验与聊天动作池零耦合
    check("绝不接受聊天动作池前缀（'greet' 被拒）",
          p.observe_op("greet", "complaint=胸痛", True)["ok"] is False)
    check("拒绝后不写入（键数不变）", p.stats()["keys"] == 1,
          str(p.stats()))

    # ---- 反例：拒绝写进聊天池也不影响医学排序
    #   （这里用 observe_op 直接证明：即使有人误调，前缀校验也挡得住）

    # ---- 落盘 + 重载一致性
    import tempfile
    tmp = os.path.join(tempfile.mkdtemp(prefix="pasm-med-learn-"), "posterior.json")
    p2 = MedicalPosterior(tmp)
    p2.observe_op("ask:放射", "complaint=胸痛;age_band=老年", True)
    p3 = MedicalPosterior(tmp)                   # 重新载入
    check("落盘后重载概率一致（ask:放射 在老年胸痛下 ≈0.667）",
          abs(p3.predict_op("ask:放射", "complaint=胸痛;age_band=老年") - 2 / 3) < 1e-9,
          str(p3.predict_op("ask:放射", "complaint=胸痛;age_band=老年")))

    # ---- 多观测单调：连续否决应持续压低概率
    p4 = MedicalPosterior()
    for _ in range(3):
        p4.observe_op("advise:用药交代", "complaint=咳嗽", False)
    check("连续否决后概率 = 1/(1+4) = 0.2",
          abs(p4.predict_op("advise:用药交代", "complaint=咳嗽") - 0.2) < 1e-9,
          str(p4.predict_op("advise:用药交代", "complaint=咳嗽")))

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
