# -*- coding: utf-8 -*-
"""智能预问诊 —— **规则驱动的问诊树**（本项目最核心的一块）。

为什么这块必须用规则而不是"让大模型自由发挥"
--------------------------------------------
三个理由，每一个都足以定生死：

1. **完整性可评测**。问诊有没有漏（现病史七要素齐不齐），可以算出来、可以写进验收。
   让模型自由聊，你无法回答"它问全了吗"。
2. **决策可审计**。医生问"你为什么问这句"，能答出"因为主诉是胸痛，缺'放射部位'"。
3. **可迭代**。临床顾问说"漏了'夜间加重'"，加一个节点即可，**不用重训模型**。

LLM 在这里只做三件事：**理解患者口语** / **把规则转成自然话术** / **生成摘要**。
它**不决定该问什么** —— 这条边界是这块设计的地基。

定位（互联网场景）
------------------
面向患者的互联网产品**不做诊断**。本引擎产出的是《预问诊摘要》——
一份结构化病史，交给医师判断。红旗症状命中时**立即中断问诊、直接建议就医**，
不继续走完流程 —— 安全优先级高于流程完整性。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional, Sequence

try:                                   # 复用基座的中文分词（与相关性闸门同一套）
    from pasm_skills.cognition.relevance import tokens as _tokens
    _TOKENIZER = "pasm_skills.cognition.relevance"
except Exception:                      # pragma: no cover - 兜底
    import re as _re

    _CJK = _re.compile(r"[\u4e00-\u9fff]+")
    _STOP = set("的了是我你他她它们在有和与就都也很不之其这那么呢吗啊呀哦嗯")

    def _tokens(s: str) -> set:
        """兜底分词：**必须与基座同款切法（中文二元词）**。

        ★ 这里踩过一个坑：最初的兜底写成"按单字切"，于是当 `pasm_skills`
        没装上时（自检脚本忘了设 PYTHONPATH 就会这样），主诉识别会**静默退化** ——
        「我就是不太舒服」被误判成腹痛（单字"不"重合）。而且**不报任何错**。

        教训：**降级路径必须与主路径语义一致**，否则"降级"就是悄悄换了一套判据。
        """
        out = set()
        for run in _CJK.findall(s or ""):
            n = len(run)
            if n == 1:
                out.add(run)
                continue
            for i in range(n - 1):
                bg = run[i:i + 2]
                if bg[0] in _STOP and bg[1] in _STOP:
                    continue
                out.add(bg)
            if n <= 4:
                out.update(c for c in run if c not in _STOP)
        for w in _re.findall(r"[A-Za-z0-9]+", s or ""):
            if len(w) > 1:
                out.add(w.lower())
        return out

    _TOKENIZER = "内置兜底（二字切分）"

__all__ = [
    "Question", "RedFlag", "ConsultSession", "ConsultEngine",
    "CHIEF_COMPLAINTS", "RED_FLAGS", "HISTORY_ELEMENTS",
]

# ---------------------------------------------------------------- 现病史七要素
#: 通用的现病史骨架。每个主诉都会先走一遍这些（已有值就跳过）。
HISTORY_ELEMENTS: List[Dict[str, Any]] = [
    {"key": "部位", "text": "主要不舒服的位置在哪里？是一处还是多处？",
     "kind": "text", "why": "定位是鉴别诊断的第一步"},
    {"key": "性质", "text": "这种感觉是什么样的？（比如胀痛、刺痛、压榨样、烧灼感）",
     "kind": "text", "why": "性质直接决定危险分层的方向"},
    {"key": "程度", "text": "如果 0 分是完全没感觉、10 分是难以忍受，现在大概几分？",
     "kind": "number", "why": "量化程度，便于复诊对比"},
    {"key": "诱因", "text": "出现前在做什么？有没有明显的诱因（活动、进食、情绪、受凉）？",
     "kind": "text", "why": "诱因常指向特定病因"},
    {"key": "持续时间", "text": "从第一次出现到现在多久了？是一直这样还是一阵一阵？",
     "kind": "text", "why": "急性/慢性决定就诊紧急度"},
    {"key": "缓解加重", "text": "做什么会让它加重、做什么会让它减轻？",
     "kind": "text", "why": "缓解因素是有价值的鉴别线索"},
    {"key": "伴随症状", "text": "同时还有别的难受吗？（发热、出汗、恶心、气促、头晕等）",
     "kind": "text", "why": "伴随症状是红旗筛查的主要输入"},
]

#: 主诉库：别名 → 标准主诉 + 该主诉的专属追问节点。
CHIEF_COMPLAINTS: Dict[str, Dict[str, Any]] = {
    "胸痛": {
        "aliases": ["胸口闷", "胸口疼", "胸闷", "胸疼", "心口疼", "心口痛", "胸前区痛"],
        "extra": [
            {"key": "放射", "text": "疼痛会不会往别的地方串？（左肩、后背、下巴、上腹）",
             "kind": "text", "why": "放射痛是心源性胸痛的重要提示"},
            {"key": "活动关系", "text": "是活动时加重、休息能缓解吗？",
             "kind": "text", "why": "劳力相关性是心绞痛的关键特征"},
        ],
    },
    "发热": {
        "aliases": ["发烧", "烧起来了", "体温高", "低烧", "高烧"],
        "extra": [
            {"key": "体温", "text": "最高量到多少度？用什么量的（腋温/口温/耳温）？",
             "kind": "text", "why": "体温峰值与测量方式影响判断"},
            {"key": "热型", "text": "是一直高还是忽高忽低？退烧药能退下来吗？",
             "kind": "text", "why": "热型有助于区分感染类型"},
        ],
    },
    "咳嗽": {
        "aliases": ["咳嗽", "咳痰", "干咳", "老咳"],
        "extra": [
            {"key": "痰", "text": "有痰吗？什么颜色、多少、好咳出来吗？",
             "kind": "text", "why": "痰的性状提示感染部位与性质"},
        ],
    },
    "腹痛": {
        "aliases": ["肚子疼", "肚子痛", "胃疼", "胃痛", "腹部不适"],
        "extra": [
            {"key": "转移", "text": "一开始疼在哪，现在还在原来的地方吗？",
             "kind": "text", "why": "转移性疼痛有定位价值"},
            {"key": "排便", "text": "大便正常吗？有没有腹泻、便秘、黑便、便血？",
             "kind": "text", "why": "消化道症状与急腹症相关"},
        ],
    },
    "头痛": {
        "aliases": ["头疼", "脑袋疼", "偏头痛"],
        "extra": [
            {"key": "起病方式", "text": "是突然一下就痛起来，还是慢慢加重的？",
             "kind": "text", "why": "霹雳样头痛提示需紧急排查"},
        ],
    },
    "头晕": {
        "aliases": ["眩晕", "晕", "天旋地转"],
        "extra": [
            {"key": "性质", "text": "是感觉天旋地转，还是头重脚轻、要晕倒的感觉？",
             "kind": "text", "why": "眩晕与晕厥前状态指向不同系统"},
        ],
    },
}

#: 固定收尾节点（任何主诉都问）。
FOLLOWUP_NODES: List[Dict[str, Any]] = [
    {"key": "过敏史", "text": "有没有药物或食物过敏？具体是什么、当时什么反应？",
     "kind": "text", "why": "★ 用药安全的前提，问清后永久记住"},
    {"key": "用药史", "text": "最近在吃什么药吗？（包括中药、保健品）",
     "kind": "text", "why": "药物相互作用与掩盖症状"},
    {"key": "既往史", "text": "以前得过什么病吗？做过手术吗？",
     "kind": "text", "why": "基础病决定危险分层"},
    {"key": "就诊意愿", "text": "你希望这次主要是想搞清楚什么？",
     "kind": "text", "why": "对齐预期，也给医师提供主诉重点"},
]


# ---------------------------------------------------------------- 红旗筛查

@dataclass
class RedFlag:
    """一条红旗规则。

    ``when`` 收到的是「已收集到的问诊事实」字典（键为节点 key）。
    ``because`` 返回命中的依据 —— **必须能答出"凭什么判红旗"**，否则不可审计。
    """

    id: str
    label: str
    urgency: str                    # emergency / urgent
    advice: str
    when: Callable[[Dict[str, Any]], bool]
    because: Callable[[Dict[str, Any]], str]

    def hit(self, facts: Dict[str, Any]) -> bool:
        try:
            return bool(self.when(facts))
        except Exception:
            return False            # 规则本身出错不该把问诊打断

    def to_dict(self, facts: Dict[str, Any]) -> Dict[str, Any]:
        return {"id": self.id, "label": self.label, "urgency": self.urgency,
                "advice": self.advice, "because": self.because(facts)}


def _has(facts: Dict[str, Any], key: str, *needles: str) -> bool:
    v = str(facts.get(key) or "")
    return any(n in v for n in needles)


def _join(facts: Dict[str, Any], *keys: str) -> str:
    return "；".join("%s：%s" % (k, facts.get(k)) for k in keys if facts.get(k))


#: ★ 红旗规则表。**这是安全底线** —— 命中即中断问诊、直接建议就医。
#: 每条都必须带 because（可审计），且不依赖任何模型输出。
RED_FLAGS: List[RedFlag] = [
    RedFlag(
        id="chest_pain_acs", label="胸痛伴高危特征", urgency="emergency",
        advice="请立即拨打 120 或前往最近医院急诊，不要自行驾车。",
        when=lambda f: f.get("主诉") == "胸痛" and (
            _has(f, "性质", "压榨", "闷痛", "紧缩") or _has(f, "伴随症状", "大汗", "出冷汗", "濒死")
            or _has(f, "放射", "左肩", "后背", "下巴", "上腹")),
        because=lambda f: _join(f, "性质", "放射", "伴随症状"),
    ),
    RedFlag(
        id="sudden_headache", label="突发剧烈头痛", urgency="emergency",
        advice="请立即就医（急诊），突发剧烈头痛需要排除颅内出血等情况。",
        when=lambda f: f.get("主诉") == "头痛" and (
            _has(f, "起病方式", "突然", "一下", "爆炸", "炸裂") or _has(f, "伴随症状", "呕吐", "意识")),
        because=lambda f: _join(f, "起病方式", "伴随症状"),
    ),
    RedFlag(
        id="gi_bleed", label="消化道出血征象", urgency="emergency",
        advice="请尽快就医（急诊），黑便/呕血需要及时处理。",
        when=lambda f: f.get("主诉") == "腹痛" and _has(f, "排便", "黑便", "便血", "呕血"),
        because=lambda f: _join(f, "排便"),
    ),
    RedFlag(
        id="high_fever_neuro", label="高热伴神经系统症状", urgency="emergency",
        advice="请立即就医（急诊）。",
        when=lambda f: f.get("主诉") == "发热" and _has(f, "伴随症状", "意识", "抽搐", "颈硬"),
        because=lambda f: _join(f, "伴随症状"),
    ),
    RedFlag(
        id="dyspnea", label="呼吸困难", urgency="emergency",
        advice="请立即就医（急诊）。",
        when=lambda f: _has(f, "伴随症状", "呼吸困难", "喘不上", "气促", "憋气"),
        because=lambda f: _join(f, "伴随症状"),
    ),
]


# ---------------------------------------------------------------- 引擎

@dataclass
class Question:
    """一个待回答的问题。``why`` 是可审计性的关键 —— 界面上也能展示。"""

    key: str
    text: str
    kind: str = "text"          # text / number
    why: str = ""
    from_tree: str = ""

    def to_dict(self) -> Dict[str, Any]:
        return {"key": self.key, "text": self.text, "kind": self.kind,
                "why": self.why, "from_tree": self.from_tree}


class ConsultEngine:
    """问诊树引擎。**无状态** —— 状态在 :class:`ConsultSession` 里。"""

    def __init__(self, chief_complaints: Optional[Dict[str, Any]] = None,
                 history_elements: Optional[List[Dict[str, Any]]] = None,
                 red_flags: Optional[Sequence[RedFlag]] = None,
                 followups: Optional[List[Dict[str, Any]]] = None) -> None:
        self.chief_complaints = chief_complaints or CHIEF_COMPLAINTS
        self.history_elements = history_elements or HISTORY_ELEMENTS
        self.red_flags = list(red_flags if red_flags is not None else RED_FLAGS)
        self.followups = followups or FOLLOWUP_NODES

    # -------------------------------------------------- 主诉识别

    def classify(self, text: str) -> Optional[str]:
        """把患者口语映射到标准主诉。

        ★ 用**词元重叠**而不是模型：这一步错了，后面整棵树的追问方向就错了。
        所以既要可解释（返回命中的词），也要**宁可判不出**——
        识别不出就走通用流程 / 让用户从列表里选，**绝不硬猜**。

        ★★ 为什么只认**两字及以上的词元**（2026-09-21 实测教训）
        ------------------------------------------------------
        最初写成"任意词元重合即命中"，结果：
            「我就是不太舒服」→ 被判成 **腹痛**（因"不适"）
            「说不上来」      → 被判成 **发热**
        原因是单字/偶合的重合极易发生（"上""来""不"这类字随处可见）。
        单字重合**不构成证据** —— 这条与相关性闸门是同一个道理：
        判据要落在**有信息量的**特征上。

        代价：只输入单个字（如"晕"）时将识别不出。这是**刻意的取舍** ——
        让用户从主诉列表里选一个，比系统猜错方向后一路问偏要好得多。
        """
        t = _tokens(text or "")
        if not t:
            return None
        # 只看两字及以上的词元；单字不构成证据
        strong = {x for x in t if len(x) >= 2}
        if not strong:
            return None
        best, best_n, best_hits = None, 0, set()
        for name, spec in self.chief_complaints.items():
            cand = _tokens(name) | _tokens(" ".join(spec.get("aliases", [])))
            hits = strong & {x for x in cand if len(x) >= 2}
            if len(hits) > best_n:
                best, best_n, best_hits = name, len(hits), hits
        return best if best_n >= 1 else None

    def match_chief_complaint(self, text: str) -> Optional[str]:
        """兼容别名。"""
        return self.classify(text)

    # -------------------------------------------------- 红旗

    def scan_red_flags(self, facts: Dict[str, Any]) -> List[RedFlag]:
        return [r for r in self.red_flags if r.hit(facts)]


class ConsultSession:
    """一次问诊会话的状态机。

    流程：主诉 → **每答一题就扫一次红旗** → 现病史七要素 → 主诉专属节点
          → 固定收尾（过敏/用药/既往）→ 摘要。

    ``answered`` 的顺序被刻意保留：这就是可审计的"问诊轨迹"。
    """

    def __init__(self, engine: ConsultEngine, *, chief_complaint: Optional[str] = None,
                 patient_ref: str = "") -> None:
        self.engine = engine
        self.patient_ref = patient_ref
        self.chief_complaint = chief_complaint
        self.facts: Dict[str, Any] = {}
        self.answered: List[Dict[str, Any]] = []
        self._pending: List[Question] = []
        self.halted = False
        self.red_flags_hit: List[Dict[str, Any]] = []
        self._build()

    # -------------------------------------------------- 内部

    def _build(self) -> None:
        eng = self.engine
        plan: List[Question] = []
        if self.chief_complaint:
            spec = eng.chief_complaints.get(self.chief_complaint, {})
            for q in spec.get("extra", []):
                plan.append(Question(q["key"], q["text"], q.get("kind", "text"),
                                     q.get("why", ""), self.chief_complaint))
        for q in eng.history_elements:
            plan.append(Question(q["key"], q["text"], q.get("kind", "text"),
                                 q.get("why", ""), "现病史"))
        for q in eng.followups:
            plan.append(Question(q["key"], q["text"], q.get("kind", "text"),
                                 q.get("why", ""), "既往与用药"))
        self._pending = [q for q in plan if q.key not in self.facts]

    def _rescan(self) -> None:
        """每次作答后重扫红旗。命中即**中断问诊** —— 安全优先于流程完整性。"""
        hits = self.engine.scan_red_flags(self.facts)
        self.red_flags_hit = [r.to_dict(self.facts) for r in hits]
        if hits:
            self.halted = True
            self._pending = []          # 不再追问，直接给就医建议

    # -------------------------------------------------- 对外

    def next_question(self) -> Optional[Question]:
        """下一个该问的问题；``None`` 表示问完了或已中断。"""
        if self.halted or not self._pending:
            return None
        return self._pending[0]

    def answer(self, key: str, value: Any) -> Dict[str, Any]:
        """记录一题答案。返回**当时的**红旗状态（前端可立即弹提示）。

        ★ 空答案必须被拒。否则「患者点了跳过」会被记成"已采集"，
        计入覆盖率、写进摘要 —— 表现为"问全了"但其实是空的。
        医疗场景里"假装问过"比"没问"更危险。
        """
        if value is None or (isinstance(value, str) and not value.strip()):
            return {"ok": False, "error": "答案不能为空（可用“不清楚”明确表示不知道）",
                    "key": key, "halted": self.halted,
                    "red_flags": self.red_flags_hit,
                    "next": (self.next_question().to_dict()
                             if self.next_question() else None)}
        self.facts[key] = value
        self.answered.append({"key": key, "value": value})
        self._rescan()
        return {"ok": True, "halted": self.halted,
                "red_flags": self.red_flags_hit,
                "next": (self.next_question().to_dict()
                         if self.next_question() else None)}

    def answer_current(self, value: Any) -> Dict[str, Any]:
        q = self.next_question()
        if q is None:
            return {"ok": False, "error": "当前没有待答问题（已问完或已中断）"}
        return self.answer(q.key, value)

    def set_chief_complaint(self, text_or_name: str) -> Dict[str, Any]:
        """设定主诉（可直接给标准名，也可给患者原话，由引擎识别）。"""
        name = text_or_name if text_or_name in self.engine.chief_complaints \
            else self.engine.classify(text_or_name)
        if not name:
            return {"ok": False, "error": "无法识别主诉，请选择或描述得更具体",
                    "known": list(self.engine.chief_complaints.keys())}
        self.chief_complaint = name
        self.facts["主诉"] = name
        self.answered.append({"key": "主诉", "value": name})
        self._rescan()
        self._build()
        return {"ok": True, "chief_complaint": name, "halted": self.halted,
                "red_flags": self.red_flags_hit}

    # -------------------------------------------------- 覆盖率 / 摘要

    def coverage(self) -> Dict[str, Any]:
        """问诊完整性 —— **这是可评测指标**，不是感觉。

        分母是"本次主诉下总共该问几题"，分子是已答几题。
        红旗中断时 coverage 会偏低，这是**正确行为**（安全优先），
        所以 :meth:`summary` 里会单独标注 halted。
        """
        total = len(self.answered) + len(self._pending)
        return {"answered": len(self.answered), "pending": len(self._pending),
                "total": total,
                "ratio": round(len(self.answered) / total, 3) if total else 1.0,
                "halted": self.halted}

    def summary(self) -> Dict[str, Any]:
        """《预问诊摘要》—— 交给医师的结构化病史。**不含任何诊断结论。**"""
        chief = self.facts.get("主诉") or self.chief_complaint or "未明确"
        present = {k: v for k, v in self.facts.items() if k != "主诉"}
        missing = [q.key for q in self._pending]
        return {
            "patient_ref": self.patient_ref,
            "chief_complaint": chief,
            "present_illness": present,
            "missing": missing,
            "coverage": self.coverage(),
            "red_flags": self.red_flags_hit,
            "halted": self.halted,
            "triage": self.triage(),
            "trace": list(self.answered),        # 问诊轨迹（可审计）
            "disclaimer": ("本摘要由预问诊系统按标准问诊要素采集生成，"
                           "**不是诊断**，请由医师结合查体与检查判断。"),
        }

    def triage(self) -> Dict[str, Any]:
        """分诊建议 —— 只给"紧急度 + 建议科室"，**不给诊断**。"""
        if self.red_flags_hit:
            first = self.red_flags_hit[0]
            return {"urgency": first["urgency"], "advice": first["advice"],
                    "reason": first["label"]}
        dept = {
            "胸痛": "心内科 / 急诊内科", "头痛": "神经内科", "腹痛": "消化内科 / 普外科",
            "发热": "发热门诊 / 感染科", "咳嗽": "呼吸内科", "头晕": "神经内科 / 耳鼻喉科",
        }.get(self.chief_complaint or "", "全科门诊")
        return {"urgency": "routine",
                "advice": "建议预约门诊就诊，由医师面诊判断。",
                "reason": "未发现需要立即就医的警示信号" if self.facts else "信息尚不完整",
                "suggested_department": dept}


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

    print("pasm_medical.consult 自检")
    print("  分词器：%s" % _TOKENIZER)
    print("-" * 60)
    eng = ConsultEngine()

    # ---- 主诉识别
    check("口语可识别到主诉：'胸口闷得慌' → 胸痛",
          eng.classify("胸口闷得慌") == "胸痛", str(eng.classify("胸口闷得慌")))
    check("'发烧两天了' → 发热", eng.classify("发烧两天了") == "发热")
    # ★ 反例：不能因为个别字重合就硬猜出主诉（判错方向比判不出更糟）
    check("★ 识别不出时返回 None（不硬猜）：'我就是不太舒服'",
          eng.classify("我就是不太舒服") is None,
          str(eng.classify("我就是不太舒服")))
    check("★ 识别不出时返回 None：'说不上来'",
          eng.classify("说不上来") is None, str(eng.classify("说不上来")))
    check("★ 单字不构成证据（'晕' 单独输入不硬判）",
          eng.classify("晕") is None, str(eng.classify("晕")))

    # ---- 正常流程
    s = ConsultSession(eng, patient_ref="p1")
    r = s.set_chief_complaint("胸口有点闷")
    check("设定主诉成功且未中断", r["ok"] and not r["halted"], str(r))
    check("首问来自主诉专属节点（胸痛 → 放射/活动关系）",
          s.next_question() is not None and s.next_question().from_tree == "胸痛",
          str(s.next_question()))
    s.answer("放射", "不串")
    s.answer("活动关系", "没什么关系")
    r2 = s.answer("性质", "隐隐的胀痛")
    check("普通答案不触发红旗", not r2["halted"], str(r2))
    check("每个问题都带 why（可审计）",
          all(q.why for q in [s.next_question()] if q))

    # ---- ★ 红旗：命中即中断
    s2 = ConsultSession(eng)
    s2.set_chief_complaint("胸痛")
    s2.answer("放射", "往左肩和后背串")
    check("★ 胸痛+放射痛 → 判红旗", s2.halted and s2.red_flags_hit, str(s2.red_flags_hit))
    check("★ 红旗后不再追问（next_question 为 None）", s2.next_question() is None)
    check("★ 红旗给出紧急建议且带依据",
          "120" in s2.red_flags_hit[0]["advice"] and s2.red_flags_hit[0]["because"],
          str(s2.red_flags_hit[0]))
    check("★ 红旗后 triage 升级为 emergency",
          s2.triage()["urgency"] == "emergency", str(s2.triage()))

    s3 = ConsultSession(eng)
    s3.set_chief_complaint("头痛")
    s3.answer("起病方式", "突然一下，炸裂一样")
    check("★ 霹雳样头痛 → 红旗", s3.halted, str(s3.red_flags_hit))

    # ---- 反例：不该误报的不能误报
    s4 = ConsultSession(eng)
    s4.set_chief_complaint("腹痛")
    s4.answer("转移", "一直在上腹")
    s4.answer("排便", "正常")
    check("★ 普通腹痛不误报红旗（误拦比漏拦同样有害）",
          not s4.halted, str(s4.red_flags_hit))

    # ---- 覆盖率可评测
    c = s4.coverage()
    check("覆盖率给出分子分母", {"answered", "pending", "ratio"} <= set(c), str(c))
    check("未中断时覆盖率在 0~1 之间", 0 < c["ratio"] <= 1, str(c))

    # ---- 摘要
    sm = s4.summary()
    check("摘要含主诉/现病史/缺失项/轨迹",
          sm["chief_complaint"] == "腹痛" and sm["trace"] and isinstance(sm["missing"], list),
          str(list(sm)))
    check("★ 摘要明确声明不是诊断", "不是诊断" in sm["disclaimer"])
    check("摘要里没有'诊断'字段（只有 triage 建议）",
          "diagnosis" not in sm and "诊断" not in sm, str(list(sm)))

    # ---- 兜底：识别不出主诉时不崩
    s5 = ConsultSession(eng)
    rr = s5.set_chief_complaint("说不上来")
    check("识别不出的主诉给出可选列表而不是抛错",
          rr["ok"] is False and rr.get("known"), str(rr))

    # ---- ★ 空答案必须被拒（否则"跳过"会被记成"已采集"）
    s6 = ConsultSession(eng)
    s6.set_chief_complaint("发热")
    before = s6.coverage()["answered"]
    bad = s6.answer_current("   ")
    check("★ 空答案被拒且不推进问诊",
          bad["ok"] is False and s6.coverage()["answered"] == before, str(bad))
    ok_ans = s6.answer_current("不清楚")
    check("★ 明确说'不清楚'则算已采集（与跳过区分开）", ok_ans["ok"] is True, str(ok_ans))

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
