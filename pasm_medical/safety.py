# -*- coding: utf-8 -*-
"""医疗安全护栏 —— 把合规约束写成**代码**，不写成提示词。

三条红线（对应 PLAN.md §1.2）
-----------------------------
1. **不得自动生成处方 / 医嘱** —— 本模块只做「核对」，不产出方剂。
   所有输出强制带 ``requires_physician_confirmation=True``。
2. **无依据不得作答** —— 没有可追溯来源（source）就不给结论，明确说"查不到"。
   这比"给个像样的答案"重要得多：医疗场景里**编造**是最严重的失败。
3. **确定性计算不交给 LLM** —— 配伍禁忌、妊娠禁忌、毒性剂量上限全部是规则表比对。
   大模型可以做语言理解与解释，**不能**做这几件事。

⚠️ 关于知识表的严肃声明
-----------------------
下列表格是中国药典 / 教材里的**经典内容**，用于演示与初版落地，**不是**可以直接
上临床的完整规则库。投入使用前必须：
  · 由**中药师 / 临床药师**逐条复核并签署；
  · 与**本院处方前置审核系统**的规则对齐（两边规则不一致会造成"系统说没事、
    前置审核拦下"的混乱）；
  · 建立**变更流程**（药典改版、院内目录调整都要能追溯）。
本模块提供 :func:`table_meta` 用于把这些信息暴露给运维页，避免"谁改的、依据哪版"
无人知晓。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Dict, Iterable, List, Optional, Sequence

__all__ = [
    "GuardViolation", "GuardedAnswer", "check_herbs", "guard_answer",
    "table_meta", "REQUIRES_PHYSICIAN", "REFUSAL_TEXT",
]

#: 所有 AI 输出都必须带这个标记 —— 它是"辅助"定位的技术表达。
REQUIRES_PHYSICIAN = True

#: 无依据时的标准话术。**不要**在这里写"我猜"、"可能"之类的软话术 ——
#: 医疗场景要的是明确拒答，不是模糊安慰。
REFUSAL_TEXT = "抱歉，我暂时没有查到与这个问题相关的可靠资料，无法给出结论。请以主管医师判断为准。"

#: 规则表版本（改了表必须一起改这里 + 记录复核人）。
_TABLE_VERSION = "2026.09-starter"
_TABLE_REVIEWER = "（待中药师复核）"


@dataclass
class GuardViolation:
    """一条规则命中。``severity`` 决定调用方该拦还是该提示。"""

    kind: str                    # incompatibility / pregnancy / overdose
    severity: str                # block（必须拦）/ warn（提示）
    detail: str = ""
    involved: List[str] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {"kind": self.kind, "severity": self.severity,
                "detail": self.detail, "involved": list(self.involved)}


@dataclass
class GuardedAnswer:
    """经过护栏处理后的输出。**结构本身就是约束** —— 缺字段就没法构造。"""

    text: str
    sources: List[Dict[str, Any]] = field(default_factory=list)
    requires_physician_confirmation: bool = REQUIRES_PHYSICIAN
    refused: bool = False
    refusal_reason: str = ""
    violations: List[Dict[str, Any]] = field(default_factory=list)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "text": self.text,
            "sources": list(self.sources),
            "requires_physician_confirmation": self.requires_physician_confirmation,
            "refused": self.refused,
            "refusal_reason": self.refusal_reason,
            "violations": list(self.violations),
        }


# ============================================================ 规则表
# 结构：{药材A: [(药材B, "说明"), ...]}  双向都会命中（查两次）。

#: 十八反（本草明言十八反：半蒌贝蔹及攻乌 / 藻戟遂芫俱战草 / 诸参辛芍叛藜芦）
_INCOMPATIBLE: Dict[str, List[str]] = {
    "乌头": ["半夏", "瓜蒌", "贝母", "白蔹", "白及"],
    "川乌": ["半夏", "瓜蒌", "贝母", "白蔹", "白及"],
    "草乌": ["半夏", "瓜蒌", "贝母", "白蔹", "白及"],
    "附子": ["半夏", "瓜蒌", "贝母", "白蔹", "白及"],
    "甘草": ["海藻", "京大戟", "红大戟", "甘遂", "芫花"],
    "藜芦": ["人参", "沙参", "丹参", "玄参", "苦参", "细辛", "白芍", "赤芍"],
}

#: 十九畏（相畏，配伍需谨慎）
_MUTUAL_FEAR: Dict[str, List[str]] = {
    "硫黄": ["朴硝", "芒硝"],
    "水银": ["砒霜"],
    "狼毒": ["密陀僧"],
    "巴豆": ["牵牛子", "牵牛"],
    "丁香": ["郁金"],
    "牙硝": ["三棱"],
    "官桂": ["赤石脂", "石脂"],
    "人参": ["五灵脂"],
}

#: 妊娠禁忌（禁用）
_PREGNANCY_FORBIDDEN: Sequence[str] = (
    "巴豆", "牵牛子", "牵牛", "大戟", "京大戟", "红大戟", "甘遂", "芫花",
    "商陆", "麝香", "水蛭", "虻虫", "莪术", "三棱", "斑蝥", "水银", "砒霜",
    "雄黄", "轻粉", "蟾酥", "马钱子", "川乌", "草乌", "附子", "藜芦",
    "干漆", "土鳖虫", "蜈蚣", "全蝎",
)

#: 妊娠慎用（提示即可，不拦）
_PREGNANCY_CAUTION: Sequence[str] = (
    "桃仁", "红花", "牛膝", "川芎", "枳实", "枳壳", "大黄", "芒硝",
    "肉桂", "桂枝", "半夏", "天南星", "薏苡仁", "通草", "瞿麦",
)

#: 毒性 / 峻烈药材的**单日剂量上限**（克）。超限一律 block。
#: 数据取自中国药典 2020 年版的常用煎服剂量范围上限。
_TOXIC_MAX_DOSE: Dict[str, Dict[str, Any]] = {
    "附子": {"max_g": 15.0, "note": "先煎、久煎；超量易致心律失常"},
    "川乌": {"max_g": 3.0, "note": "先煎、久煎；生品禁用"},
    "草乌": {"max_g": 3.0, "note": "先煎、久煎；生品禁用"},
    "细辛": {"max_g": 3.0, "note": "超量有肾毒性报道"},
    "马钱子": {"max_g": 0.6, "note": "治疗窗极窄"},
    "雄黄": {"max_g": 0.1, "note": "含砷，不宜久服"},
    "蟾酥": {"max_g": 0.03, "note": "强心苷类，极易中毒"},
    "斑蝥": {"max_g": 0.06, "note": "剧毒"},
    "水蛭": {"max_g": 3.0, "note": "破血力猛"},
}


def _norm(x: str) -> str:
    return (x or "").strip().replace(" ", "")


def _pairs(herbs: Iterable[str], table: Dict[str, List[str]],
           kind: str, severity: str, label: str) -> List[GuardViolation]:
    got = [_norm(h) for h in herbs if _norm(h)]
    out: List[GuardViolation] = []
    for i, a in enumerate(got):
        for b in got[i + 1:]:
            hit = (b in table.get(a, [])) or (a in table.get(b, []))
            if hit:
                out.append(GuardViolation(
                    kind=kind, severity=severity,
                    detail="%s：%s 与 %s 同方（%s）" % (label, a, b, kind),
                    involved=[a, b]))
    return out


def check_herbs(herbs: Sequence[str], *,
                pregnant: bool = False,
                doses: Optional[Dict[str, float]] = None) -> List[GuardViolation]:
    """对一张中药处方做**确定性**规则核对。

    ``doses`` 传入 ``{药材: 克数}`` 时会额外查剂量上限。

    返回违例列表（**可能是空列表 —— 空表示"本表未发现违例"，不表示"安全"**）。
    调用方必须把这句话带给医生，别让"系统没报错"变成"医生放心了"。
    """
    out: List[GuardViolation] = []
    out += _pairs(herbs, _INCOMPATIBLE, "incompatibility", "block", "十八反")
    out += _pairs(herbs, _MUTUAL_FEAR, "mutual_fear", "warn", "十九畏")

    normed = {_norm(h) for h in herbs if _norm(h)}
    if pregnant:
        for h in sorted(normed & set(_PREGNANCY_FORBIDDEN)):
            out.append(GuardViolation(kind="pregnancy", severity="block",
                                      detail="妊娠禁用：%s" % h, involved=[h]))
        for h in sorted(normed & set(_PREGNANCY_CAUTION)):
            out.append(GuardViolation(kind="pregnancy", severity="warn",
                                      detail="妊娠慎用：%s" % h, involved=[h]))

    for name, g in (doses or {}).items():
        cap = _TOXIC_MAX_DOSE.get(_norm(name))
        try:
            v = float(g)
        except (TypeError, ValueError):
            continue
        if cap and v > float(cap["max_g"]):
            out.append(GuardViolation(
                kind="overdose", severity="block",
                detail="%s 单日 %.1fg 超过上限 %.1fg（%s）"
                       % (name, v, cap["max_g"], cap["note"]),
                involved=[name]))
    return out


def guard_answer(text: str, sources: Optional[List[Dict[str, Any]]] = None, *,
                 violations: Optional[List[GuardViolation]] = None,
                 min_sources: int = 1) -> GuardedAnswer:
    """给一条待输出内容套上护栏。

    **核心行为：没有来源就拒答。** 这是代码级约束，不依赖模型自觉 ——
    医疗场景里"答得漂亮但没依据"比"答不上来"危险得多。
    """
    src = list(sources or [])
    vio = [v.to_dict() for v in (violations or [])]
    if len(src) < min_sources:
        return GuardedAnswer(
            text=REFUSAL_TEXT, sources=[], refused=True,
            refusal_reason="无可追溯来源（sources < %d）" % min_sources,
            violations=vio)
    return GuardedAnswer(text=text, sources=src, violations=vio)


def has_blocking(violations: Iterable[GuardViolation]) -> bool:
    """是否存在必须拦截的违例（调用方据此**不要**把内容发出去）。"""
    return any(v.severity == "block" for v in violations)


def table_meta() -> Dict[str, Any]:
    """规则表的元信息 —— 暴露给运维页，避免"谁改的、依据哪版"无人知晓。"""
    return {
        "version": _TABLE_VERSION,
        "reviewer": _TABLE_REVIEWER,
        "incompatible_pairs": sum(len(v) for v in _INCOMPATIBLE.values()),
        "mutual_fear_pairs": sum(len(v) for v in _MUTUAL_FEAR.values()),
        "pregnancy_forbidden": len(_PREGNANCY_FORBIDDEN),
        "pregnancy_caution": len(_PREGNANCY_CAUTION),
        "toxic_herbs_with_cap": len(_TOXIC_MAX_DOSE),
        "warning": ("本表为演示/初版落地用的经典内容，**未**经临床药师复核；"
                    "投产前必须逐条复核并与院内前置审核规则对齐。"
                    "空结果只表示'本表未发现违例'，不表示'安全'。"),
    }


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

    print("pasm_medical.safety 自检")
    print("-" * 60)

    # ---- 无依据必须拒答（最重要的一条）
    g = guard_answer("根据经验，建议服用某药。", sources=[])
    check("★ 无来源 → 拒答（不输出原文）", g.refused and "建议服用" not in g.text, str(g))
    check("★ 拒答时 sources 为空且给出原因", not g.sources and g.refusal_reason, str(g))
    g2 = guard_answer("依据：某某指南第 3 版。", sources=[{"title": "指南"}])
    check("有来源 → 正常输出且强制带医师确认标记",
          (not g2.refused) and g2.requires_physician_confirmation is True, str(g2))

    # ---- 十八反（双向都要命中）
    v = check_herbs(["附子", "半夏"])
    check("★ 十八反：附子 + 半夏 命中且为 block",
          bool(v) and v[0].severity == "block", str([x.to_dict() for x in v]))
    v2 = check_herbs(["半夏", "附子"])          # 顺序颠倒也必须命中
    check("★ 十八反：顺序颠倒同样命中（不能只查单向）", bool(v2), str(v2))
    check("十八反：甘草 + 甘遂 命中", bool(check_herbs(["甘草", "甘遂"])))
    check("十八反：藜芦 + 丹参 命中", bool(check_herbs(["藜芦", "丹参"])))

    # 反例：正常配伍不得误报
    check("正常配伍不误报", not check_herbs(["黄芪", "当归", "白术"]),
          str([x.to_dict() for x in check_herbs(["黄芪", "当归", "白术"])]))

    # ---- 妊娠
    v3 = check_herbs(["麝香"], pregnant=True)
    check("妊娠禁用命中", bool(v3) and v3[0].severity == "block", str(v3))
    v4 = check_herbs(["桃仁"], pregnant=True)
    check("妊娠慎用为 warn（不拦）", bool(v4) and v4[0].severity == "warn", str(v4))
    check("非妊娠时不报妊娠违例", not check_herbs(["麝香"], pregnant=False))

    # ---- 剂量
    v5 = check_herbs(["附子"], doses={"附子": 30})
    check("★ 附子 30g 超上限 → block", bool(v5) and v5[0].kind == "overdose", str(v5))
    check("附子 10g 不报超量", not check_herbs(["附子"], doses={"附子": 10}))
    v6 = check_herbs(["细辛"], doses={"细辛": 5})
    check("细辛 5g 超上限 → block", bool(v6), str(v6))

    # ---- 元信息
    m = table_meta()
    check("规则表元信息含版本与复核人", m["version"] and m["reviewer"], str(m))
    check("元信息带未复核警告", "未" in m["warning"])

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
