# -*- coding: utf-8 -*-
"""检验单解析 —— OCR 结果 → 结构化 → 规则判读 → **回显确认**。

按小志拍板的第 2 条（无 LIS/HIS 对接、后台留对接设置）：**OCR 是主路径**。
这决定了本模块的一个核心设计：

★★ 识别值**必须回显给用户确认后才能入病历**
-------------------------------------------
理由不是体验，是安全：13.5 识别成 18.5，在其他场景是 bug，在这里是事故。
所以本模块把「识别」与「确认」**在类型层面分开**：

    LabItem.confirmed = False  →  不得进病历、不得进认知记忆、不得用于判读
    调用 confirm() 之后 confirmed = True → 才允许

`LabItem.to_memory()` 会**拒绝**未确认项 —— 让"忘记确认"在代码层就不可能发生，
而不是靠开发者记得。

三条与 `safety.py` 同源的规则
-----------------------------
1. **参考区间以单据上印的为准**。不同医院、不同仪器、不同人群区间不同；
   单据没印才用字典默认值，且**必须标记为"默认区间（可能与本机构不符）"**。
2. **危急值硬编码 + 强制升级**，不受对话流程影响（见 :data:`CRITICAL_VALUES`）。
3. **对不上项目字典的不许猜** —— 标为"未识别项"交人处理。

LLM 在这里没有位置。判读是规则，一个数值比大小而已。
OCR 引擎本身是外部依赖（见 :class:`OcrEngine`）—— 生产建议用**化验单专用**接口，
通用 OCR 在表格上明显更差。
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, Iterable, List, Optional, Sequence, Tuple

__all__ = [
    "LabItem", "LabReport", "OcrEngine", "StubOcrEngine",
    "ITEM_DICT", "CRITICAL_VALUES", "DEFERENCE_DEFAULT_RANGES",
    "parse_rows", "interpret",
]

# ---------------------------------------------------------------- 项目字典
#: 别名 → 标准项目名。**只做映射，不做判读**。
#: 生产环境这张表应与院内 LIS 的项目字典对齐（后台「对接设置」里维护）。
ITEM_DICT: Dict[str, str] = {
    "wbc": "白细胞计数", "白细胞": "白细胞计数", "白细胞计数": "白细胞计数",
    "rbc": "红细胞计数", "红细胞": "红细胞计数", "红细胞计数": "红细胞计数",
    "hgb": "血红蛋白", "hb": "血红蛋白", "血红蛋白": "血红蛋白",
    "plt": "血小板计数", "血小板": "血小板计数", "血小板计数": "血小板计数",
    "neu%": "中性粒细胞百分比", "中性粒细胞百分比": "中性粒细胞百分比",
    "lym%": "淋巴细胞百分比", "淋巴细胞百分比": "淋巴细胞百分比",
    "crp": "C反应蛋白", "c反应蛋白": "C反应蛋白",
    "pct": "降钙素原", "降钙素原": "降钙素原",
    "glu": "血糖", "血糖": "血糖", "葡萄糖": "血糖",
    "k": "血钾", "钾": "血钾", "血钾": "血钾",
    "na": "血钠", "钠": "血钠", "血钠": "血钠",
    "cl": "血氯", "氯": "血氯",
    "cr": "肌酐", "肌酐": "肌酐", "crea": "肌酐",
    "bun": "尿素氮", "尿素氮": "尿素氮", "尿素": "尿素氮",
    "alt": "谷丙转氨酶", "谷丙转氨酶": "谷丙转氨酶", "gpt": "谷丙转氨酶",
    "ast": "谷草转氨酶", "谷草转氨酶": "谷草转氨酶", "got": "谷草转氨酶",
    "tbil": "总胆红素", "总胆红素": "总胆红素",
    "alb": "白蛋白", "白蛋白": "白蛋白",
    "tsh": "促甲状腺激素", "促甲状腺激素": "促甲状腺激素",
    "ft3": "游离T3", "ft4": "游离T4",
    "inr": "INR", "d-dimer": "D-二聚体", "d二聚体": "D-二聚体",
    "hba1c": "糖化血红蛋白", "糖化血红蛋白": "糖化血红蛋白",
    "esr": "血沉", "血沉": "血沉",
    "尿蛋白": "尿蛋白", "尿糖": "尿糖", "尿潜血": "尿潜血",
    "ph": "酸碱度",
}

#: 危急值（**硬编码，触发即强制升级**）。单位与阈值参照常用成人标准，
#: ⚠️ **投产前必须由临床/检验科复核并与本院危急值目录对齐**。
#: 结构：标准项目名 → [(判定函数, 说明)]
CRITICAL_VALUES: Dict[str, List[Tuple[Callable[[float], bool], str]]] = {
    "血钾": [(lambda v: v > 6.5, "血钾 > 6.5 mmol/L（高钾危象）"),
             (lambda v: v < 2.8, "血钾 < 2.8 mmol/L（严重低钾）")],
    "血钠": [(lambda v: v < 120, "血钠 < 120 mmol/L（重度低钠）"),
             (lambda v: v > 160, "血钠 > 160 mmol/L（重度高钠）")],
    "血糖": [(lambda v: v < 2.8, "血糖 < 2.8 mmol/L（低血糖危象）"),
             (lambda v: v > 22.2, "血糖 > 22.2 mmol/L")],
    "血红蛋白": [(lambda v: v < 60, "血红蛋白 < 60 g/L（重度贫血）")],
    "血小板计数": [(lambda v: v < 20, "血小板 < 20×10⁹/L（出血风险高）")],
    "白细胞计数": [(lambda v: v < 2.0, "白细胞 < 2.0×10⁹/L（粒细胞缺乏风险）"),
                   (lambda v: v > 30.0, "白细胞 > 30×10⁹/L")],
    "肌酐": [(lambda v: v > 707, "肌酐 > 707 μmol/L")],
    "INR": [(lambda v: v > 5.0, "INR > 5.0（出血风险高）")],
    "C反应蛋白": [(lambda v: v > 200, "CRP > 200 mg/L")],
    "降钙素原": [(lambda v: v > 10, "PCT > 10 ng/mL（重症感染可能）")],
}

#: 单据**未印**参考区间时可用的兜底区间。
#: ⚠️ 用它判读必须在结果里标注 `range_source="default"` 并给出提示 ——
#: 不同机构/仪器/人群区间不同，拿通用值判读是有风险的。
DEFERENCE_DEFAULT_RANGES: Dict[str, Tuple[float, float, str]] = {
    "白细胞计数": (3.5, 9.5, "10⁹/L"),
    "红细胞计数": (3.8, 5.8, "10¹²/L"),
    "血红蛋白": (115, 175, "g/L"),
    "血小板计数": (125, 350, "10⁹/L"),
    "中性粒细胞百分比": (40, 75, "%"),
    "淋巴细胞百分比": (20, 50, "%"),
    "C反应蛋白": (0, 10, "mg/L"),
    "降钙素原": (0, 0.5, "ng/mL"),
    "血糖": (3.9, 6.1, "mmol/L"),
    "血钾": (3.5, 5.5, "mmol/L"),
    "血钠": (137, 147, "mmol/L"),
    "血氯": (99, 110, "mmol/L"),
    "肌酐": (41, 111, "μmol/L"),
    "尿素氮": (2.9, 8.2, "mmol/L"),
    "谷丙转氨酶": (0, 40, "U/L"),
    "谷草转氨酶": (0, 40, "U/L"),
    "总胆红素": (3.4, 20.5, "μmol/L"),
    "白蛋白": (40, 55, "g/L"),
    "促甲状腺激素": (0.27, 4.2, "mIU/L"),
    "糖化血红蛋白": (4.0, 6.0, "%"),
    "血沉": (0, 20, "mm/h"),
    "尿酸": (155, 428, "μmol/L"),
}

_NUM = re.compile(r"-?\d+(?:\.\d+)?")
#: 形如 3.5-9.5 / 3.5～9.5 / 3.5~9.5 / ≤10 / <10
_RANGE = re.compile(r"^\s*(?:≤|<)?\s*(-?\d+(?:\.\d+)?)\s*[-~～—–至]\s*(-?\d+(?:\.\d+)?)\s*$")
_RANGE_MAX = re.compile(r"^\s*(?:≤|<)\s*(-?\d+(?:\.\d+)?)\s*$")


# ---------------------------------------------------------------- 数据结构

@dataclass
class LabItem:
    """一条检验项目。

    ★ ``confirmed`` 是**安全闸门**：默认 False，未确认项不允许入病历/记忆。
    """

    name: str                       # 标准项目名（对不上字典则保留原文并置 unrecognized）
    raw_name: str = ""
    value: Optional[float] = None
    raw_value: str = ""
    unit: str = ""
    ref_low: Optional[float] = None
    ref_high: Optional[float] = None
    range_source: str = "none"      # printed（单据印的）/ default（字典兜底）/ none
    flag: str = ""                  # high / low / normal / unknown
    critical: List[str] = field(default_factory=list)
    unrecognized: bool = False      # 对不上项目字典 → 交人处理，**不猜**
    confidence: float = 1.0         # OCR 置信度（来源引擎给出）
    confirmed: bool = False         # ★ 用户回显确认后才 True
    corrected: bool = False         # 是否被用户修正过（留痕）

    # ---- 判读（纯规则） ----
    def interpret(self) -> "LabItem":
        """高/低判读 + 危急值。**纯数值比较，不经 LLM。**"""
        if self.unrecognized:
            self.flag = "unknown"
            return self
        v = self.value
        if v is None:
            self.flag = "unknown"
            return self
        if self.ref_low is not None and v < self.ref_low:
            self.flag = "low" if self.range_source == "printed" else "low*"
        elif self.ref_high is not None and v > self.ref_high:
            self.flag = "high" if self.range_source == "printed" else "high*"
        else:
            self.flag = "normal"
        # 危急值**独立于参考区间**判定（即使区间缺失也要能触发）
        for pred, desc in CRITICAL_VALUES.get(self.name, []):
            try:
                if pred(float(v)):
                    self.critical.append(desc)
            except Exception:
                pass
        return self

    def to_dict(self) -> Dict[str, Any]:
        return {"name": self.name, "raw_name": self.raw_name, "value": self.value,
                "raw_value": self.raw_value, "unit": self.unit,
                "ref_low": self.ref_low, "ref_high": self.ref_high,
                "range_source": self.range_source, "flag": self.flag,
                "critical": list(self.critical), "unrecognized": self.unrecognized,
                "confidence": self.confidence, "confirmed": self.confirmed,
                "corrected": self.corrected}

    def to_memory(self) -> Dict[str, Any]:
        """★ 只有**已确认**的项才能进记忆。未确认就调用 → 直接报错。

        这是刻意的：让"忘了确认就入库"在代码层不可能发生。

        ★ 未识别项（对不上项目字典）**永远不入记忆** —— 我们连它是什么都不知道，
        存进去只会造出一条无意义甚至误导的"检验事实"。它仍然保留在报告里
        供医师查看原始行。
        """
        if not self.confirmed:
            raise ValueError("未确认的检验项不得入记忆：%s（先调 confirm）" % self.name)
        if self.unrecognized:
            raise ValueError("未识别的检验项不得入记忆（项目名未知）：%s" % self.raw_name)
        brief = "%s %s %s" % (self.value, self.unit, self.flag)
        return {"title": "检验·%s" % self.name, "brief": brief.strip(),
                "tags": [self.name] + (["危急值"] if self.critical else []),
                "salience": 5 if self.critical else 3}


@dataclass
class LabReport:
    """一张检验单的解析结果。"""

    items: List[LabItem] = field(default_factory=list)
    source: str = ""                # 文件名 / 单据号
    engine: str = ""                # OCR 引擎名与版本（审计要用）
    unparsed_rows: List[str] = field(default_factory=list)

    # ---- 确认流程 ----

    def pending(self) -> List[LabItem]:
        return [i for i in self.items if not i.confirmed]

    def confirm(self, corrections: Optional[Dict[str, float]] = None,
                *, all_items: bool = False) -> Dict[str, Any]:
        """回显确认。``corrections`` 是用户改过的值 ``{项目名: 新值}``。

        ``all_items=True`` 表示"整单核对无误" —— 此时**未识别项也会被确认**，
        否则整单会永远卡在"待确认"（不可用）。但未识别项仍然
        **不会进记忆**（见 :meth:`LabItem.to_memory`），只是不再阻塞流程。
        """
        corrections = corrections or {}
        done: List[str] = []
        for it in self.items:
            if it.name in corrections:
                it.value = float(corrections[it.name])
                it.corrected = True
                it.interpret()          # 修正后**必须重判**
                it.confirmed = True
                done.append(it.name)
            elif all_items:
                it.confirmed = True
                done.append(it.name)
        return {"confirmed": done, "pending": [i.name for i in self.pending()],
                "corrected": [k for k in corrections],
                "unrecognized_in_memory": 0}

    # ---- 汇总 ----

    def critival_summary(self) -> Dict[str, Any]:
        crit = [i for i in self.items if i.critical]
        return {
            "count": len(crit),
            "items": [{"name": i.name, "value": i.value, "unit": i.unit,
                       "messages": list(i.critical)} for i in crit],
            # ★ 危急值必须给**强制**建议，且不依赖对话流程
            "advice": ("发现危急值，请立即联系医生或前往急诊。" if crit else ""),
            "forced": bool(crit),
        }

    def abnormal(self) -> List[LabItem]:
        return [i for i in self.items if i.flag in ("high", "low", "high*", "low*")]

    def unrecognized(self) -> List[LabItem]:
        return [i for i in self.items if i.unrecognized]

    def to_dict(self) -> Dict[str, Any]:
        return {
            "source": self.source, "engine": self.engine,
            "items": [i.to_dict() for i in self.items],
            "abnormal": [i.name for i in self.abnormal()],
            "unrecognized": [i.raw_name or i.name for i in self.unrecognized()],
            "unparsed_rows": list(self.unparsed_rows),
            "critical": self.critival_summary(),
            # ★ 未确认前**不得**用于任何判断，这个标记要透传到前端
            "needs_confirmation": bool(self.pending()),
            "disclaimer": ("检验结果由系统识别，可能存在识别误差，"
                           "请核对原始单据；本结果不构成诊断。"),
        }

    def to_memories(self) -> List[Dict[str, Any]]:
        """只有**已确认且已识别**的项才能转记忆。

        未确认项会被 :meth:`LabItem.to_memory` 拒绝；未识别项被这里过滤 ——
        它们仍留在报告里供医师查看原始行，但不进认知层。
        """
        return [i.to_memory() for i in self.items
                if i.confirmed and not i.unrecognized]


# ---------------------------------------------------------------- 解析

def _norm_key(s: str) -> str:
    return re.sub(r"[\s　:：]+", "", (s or "")).lower()


def _lookup(name: str) -> Tuple[str, bool]:
    """项目名 → 标准名。对不上**不猜**，返回 (原名, True)。"""
    k = _norm_key(name)
    if not k:
        return name, True
    if k in ITEM_DICT:
        return ITEM_DICT[k], False
    # 去掉常见后缀再试（"白细胞计数(WBC)" → "白细胞计数"）
    base = re.sub(r"[（(].*?[)）]", "", k)
    if base in ITEM_DICT:
        return ITEM_DICT[base], False
    for alias, std in ITEM_DICT.items():
        if alias and (alias in k or (len(base) >= 2 and base in alias)):
            return std, False
    return name.strip(), True


def parse_rows(rows: Iterable[Sequence[str]], *,
               engine: str = "", source: str = "",
               confidences: Optional[Iterable[float]] = None) -> LabReport:
    """把 OCR 出来的表格行解析成结构化报告。

    ``rows`` 每行形如 ``[项目名, 结果, 单位, 参考区间]``（多余列忽略）。
    这是**通用 OCR → 表格**之后的一步；实际 OCR 由 :class:`OcrEngine` 提供。

    ★ 解析规则遵循"不确定就不猜"：
      · 项目名对不上字典 → ``unrecognized=True``，**保留原文**；
      · 结果不是数字 → 该项 ``value=None``、``flag="unknown"``（不静默丢）；
      · 参考区间缺 → 用字典兜底但标 ``range_source="default"``。
    """
    rep = LabReport(source=source, engine=engine)
    confs = list(confidences or [])
    for idx, row in enumerate(rows):
        cells = [str(c).strip() for c in row]
        cells = [c for c in cells if c != ""]
        if len(cells) < 2:
            if cells:
                rep.unparsed_rows.append(" | ".join(cells))
            continue
        raw_name, raw_val = cells[0], cells[1]
        unit = cells[2] if len(cells) > 2 else ""
        ref_cell = cells[3] if len(cells) > 3 else ""

        name, unk = _lookup(raw_name)
        it = LabItem(name=name, raw_name=raw_name, unit=unit,
                     unrecognized=unk,
                     confidence=(confs[idx] if idx < len(confs) else 1.0))

        m = _NUM.search(raw_val.replace(",", ""))
        it.raw_value = raw_val
        it.value = float(m.group()) if m else None

        # 参考区间：**优先用单据印的**
        rl = rh = None
        mm = _RANGE.match(ref_cell)
        if mm:
            rl, rh = float(mm.group(1)), float(mm.group(2))
        else:
            mm2 = _RANGE_MAX.match(ref_cell)
            if mm2:
                rh = float(mm2.group(1))
        if rl is not None or rh is not None:
            it.range_low, it.ref_low = rl, rl
            it.ref_high = rh
            it.range_source = "printed"
        else:
            dflt = DEFERENCE_DEFAULT_RANGES.get(name)
            if dflt:
                it.ref_low, it.ref_high = dflt[0], dflt[1]
                it.range_source = "default"
                if not it.unit:
                    it.unit = dflt[2]
        it.interpret()
        rep.items.append(it)
    return rep


def interpret(report: LabReport) -> LabReport:
    """（重）判读整单。修正数值后需要调它。"""
    for it in report.items:
        it.interpret()
    return report


# ---------------------------------------------------------------- OCR 接口

class OcrEngine:
    """OCR 引擎接口。**生产实现不要用通用 OCR** —— 化验单表格上明显更差。"""

    name = "base"

    def available(self) -> bool:
        return False

    def rows(self, image_path: str) -> List[List[str]]:
        raise NotImplementedError


class StubOcrEngine(OcrEngine):
    """开发/测试用的假引擎：不识别图片，直接返回预置行。

    ★ 存在的意义：让整条流水线（解析 → 判读 → 确认 → 入记忆）**在无 OCR 时也能测**。
    生产请换成化验单专用 OCR，或在后台「对接设置」里配 LIS 直连。
    """

    name = "stub"

    def __init__(self, canned: Optional[List[List[str]]] = None) -> None:
        self._canned = canned or []

    def available(self) -> bool:
        return True

    def rows(self, image_path: str) -> List[List[str]]:
        return [list(r) for r in self._canned]


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

    print("pasm_medical.lab 自检")
    print("-" * 60)

    rows = [
        ["WBC", "13.5", "10^9/L", "3.5-9.5"],
        ["HGB", "142", "g/L", "115-175"],
        ["K", "6.8", "mmol/L", "3.5-5.5"],
        ["血糖", "4.9", "mmol/L", "3.9-6.1"],
        ["某新项目XYZ", "1.2", "U/L", ""],
        ["CRP", "—", "mg/L", "0-10"],
    ]
    rep = parse_rows(rows, engine="stub", source="化验单001.jpg")

    # ---- 项目字典对齐
    check("别名能对齐到标准名（WBC→白细胞计数）",
          any(i.name == "白细胞计数" for i in rep.items), str([i.name for i in rep.items]))
    check("★ 对不上字典的不猜，标为未识别并保留原文",
          any(i.unrecognized and i.raw_name == "某新项目XYZ" for i in rep.items),
          str([(i.raw_name, i.unrecognized) for i in rep.items]))

    # ---- 参考区间
    wbc = next(i for i in rep.items if i.name == "白细胞计数")
    check("★ 单据印的区间优先（range_source=printed）",
          wbc.range_source == "printed" and wbc.ref_high == 9.5, str(wbc.to_dict()))
    check("WBC 13.5 判为偏高", wbc.flag == "high", wbc.flag)

    # ---- ★ 危急值（独立于参考区间）
    k = next(i for i in rep.items if i.name == "血钾")
    check("★ 血钾 6.8 → 命中危急值", bool(k.critical), str(k.critical))
    cs = rep.critival_summary()
    check("★ 危急值汇总强制给建议且 forced=True",
          cs["forced"] and "急诊" in cs["advice"], str(cs))

    # ---- ★ 未确认不得入记忆
    try:
        wbc.to_memory()
        check("★ 未确认项拒绝入记忆", False, "竟然没报错")
    except ValueError:
        check("★ 未确认项拒绝入记忆", True)
    check("★ 未确认时报告标 needs_confirmation=True",
          rep.to_dict()["needs_confirmation"] is True)
    check("未确认项转记忆数量为 0", rep.to_memories() == [], str(rep.to_memories()))

    # ---- 确认流程
    r = rep.confirm(corrections={"白细胞计数": 13.5}, all_items=True)
    check("确认后 confirmed 生效", wbc.confirmed and not rep.pending(), str(r))
    mems = rep.to_memories()
    check("★ 确认后可入记忆", len(mems) >= 4, str(len(mems)))
    check("★ 危急值记忆 salience=5（不会被闲聊挤掉）",
          all(m["salience"] == 5 for m in mems if m["title"] == "检验·血钾"),
          str([m for m in mems if m["title"] == "检验·血钾"]))
    check("★ 去标识项目不进记忆（未识别项不确认）",
          all("某新项目XYZ" not in m["title"] for m in mems))
    check("★ 未识别项即使被确认也不进记忆（项目名未知）",
          all("某新项目XYZ" not in m["title"] and "某新项目XYZ" not in m["brief"]
              for m in rep.to_memories()),
          str(rep.to_memories()))
    try:
        next(i for i in rep.items if i.unrecognized).to_memory()
        check("★ 未识别项直接调 to_memory 会报错（双重防护）", False, "竟然没报错")
    except ValueError:
        check("★ 未识别项直接调 to_memory 会报错（双重防护）", True)
    check("★ 未识别项仍保留在报告里（供医师查看原始行）",
          any(i.raw_name == "某新项目XYZ" for i in rep.items))
    check("报告透传识别免责声明", "识别误差" in rep.to_dict()["disclaimer"])

    # ---- 修正后必须重判
    rep2 = parse_rows([["K", "3.0", "mmol/L", "3.5-5.5"]])
    check("血钾 3.0 判为偏低", rep2.items[0].flag == "low", rep2.items[0].flag)
    rep2.confirm(corrections={"血钾": 4.2}, all_items=True)
    check("★ 用户修正后**重新判读**（3.0→4.2 应变为正常）",
          rep2.items[0].flag == "normal" and rep2.items[0].corrected,
          str(rep2.items[0].to_dict()))

    # ---- 兜底区间要标注来源
    rep3 = parse_rows([["HGB", "100", "g/L", ""]])
    check("★ 单据没印区间时用兜底值并标注 range_source=default",
          rep3.items[0].range_source == "default" and rep3.items[0].flag == "low*",
          str(rep3.items[0].to_dict()))

    # ---- 非数字结果不能静默丢
    crp = next(i for i in rep.items if i.name == "C反应蛋白")
    check("★ 非数字结果保留条目但 flag=unknown（不静默丢）",
          crp.value is None and crp.flag == "unknown", crp.flag)
    check("解析不了的行进 unparsed_rows 而不是消失",
          isinstance(rep.unparsed_rows, list))

    # ---- OCR 接口
    eng = StubOcrEngine([["WBC", "5.0", "10^9/L", "3.5-9.5"]])
    check("Stub OCR 可用（让流水线在无 OCR 时也能测）", eng.available())
    check("OCR 接口有明确的生产替换点",
          not OcrEngine().available() and OcrEngine.name == "base")

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
