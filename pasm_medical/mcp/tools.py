# -*- coding: utf-8 -*-
"""pasm-medical 的 MCP 工具定义与实现。

每条工具都是对 :class:`pasm_medical.service.MedicalService` 某个方法的薄封装，
**不抢语言**：这里只负责"能不能答、凭哪几条答、要不要医师确认、配伍有没有反"，
自然语言结论由客户端的临床大模型结合返回的来源去生成。

★ 所有临床结果（除 ``med_health`` 运维探针外）统一追加
``requires_physician_confirmation=true`` —— 见 :func:`call_tool`。
"""
from __future__ import annotations

from typing import Any, Dict, List, Optional

from .. import domain
from .. import safety


def _s(args: Dict[str, Any], k: str, default: str = "") -> str:
    v = args.get(k)
    return str(v) if v is not None else default


def _i(args: Dict[str, Any], k: str, default: int = 0) -> int:
    try:
        return int(args.get(k))
    except (TypeError, ValueError):
        return default


def _b(args: Dict[str, Any], k: str, default: bool = False) -> bool:
    v = args.get(k, default)
    return bool(v)


def _lst(args: Dict[str, Any], k: str, default: Optional[List] = None) -> List:
    v = args.get(k)
    if isinstance(v, list):
        return v
    return list(default or [])


# ============================================================ 工具实现


def tool_health(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.health()


def tool_record_allergy(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.record_allergy(_s(args, "patient_ref"), _s(args, "allergen"),
                              _s(args, "reaction"))


def tool_record_critical(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.record_critical(_s(args, "patient_ref"), _s(args, "what"),
                               _s(args, "detail"))


def tool_record_rule_violation(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.record_rule_violation(_s(args, "patient_ref"), _s(args, "detail"),
                                     _lst(args, "involved"))


def tool_observe_note(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.observe_note(_s(args, "patient_ref"), _s(args, "title"),
                            _s(args, "brief"), _i(args, "salience", 3))


def tool_feel(args: Dict[str, Any], svc) -> Dict[str, Any]:
    try:
        valence = float(args.get("valence"))
    except (TypeError, ValueError):
        valence = 0.0
    return svc.feel(_s(args, "patient_ref"), _s(args, "event"), valence)


def tool_recall(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.recall(_s(args, "patient_ref"), _s(args, "query"),
                      _i(args, "k", 5))


def tool_ask(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.ask(_s(args, "patient_ref"), _s(args, "question"),
                   _i(args, "k", 5), use_gate=_b(args, "use_gate", True))


def tool_timeline(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.timeline(_s(args, "patient_ref"), _i(args, "k", 20))


def tool_record_encounter(args: Dict[str, Any], svc) -> Dict[str, Any]:
    obs = []
    for o in _lst(args, "observations"):
        if isinstance(o, dict):
            obs.append(domain.Observation(
                code=_s(o, "code"), value=_s(o, "value"),
                unit=_s(o, "unit"), note=_s(o, "note")))
    enc = domain.Encounter(
        encounter_id=_s(args, "encounter_id"),
        patient_ref=_s(args, "patient_ref"),
        occurred_at=_s(args, "occurred_at"),
        department=_s(args, "department"),
        chief_complaint=_s(args, "chief_complaint"),
        observations=obs,
        assessment=_s(args, "assessment"),
        plan=_s(args, "plan"),
    )
    return svc.record_encounter(enc)


def tool_parse_lab_rows(args: Dict[str, Any], svc) -> Dict[str, Any]:
    rows = _lst(args, "rows")
    norm: List[List[str]] = []
    for r in rows:
        if isinstance(r, list):
            norm.append([str(x) for x in r])
        elif isinstance(r, dict):
            norm.append([_s(r, "code"), _s(r, "value"), _s(r, "unit")])
    return svc.parse_lab_rows(_s(args, "patient_ref"), norm,
                              engine=_s(args, "engine", "manual"),
                              source=_s(args, "source"))


def tool_confirm_lab(args: Dict[str, Any], svc) -> Dict[str, Any]:
    corr = args.get("corrections")
    if not isinstance(corr, dict):
        corr = None
    return svc.confirm_lab(_s(args, "report_key"), corrections=corr,
                           all_items=_b(args, "all_items", False))


def tool_start_consult(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.start_consult(_s(args, "patient_ref"),
                             _s(args, "chief_complaint"),
                             session_key=_s(args, "session_key"))


def tool_answer_consult(args: Dict[str, Any], svc) -> Dict[str, Any]:
    session_key = _s(args, "session_key")
    value = _s(args, "value")
    by_key = _b(args, "by_key", False)
    qkey = _s(args, "question_key")
    patient_ref = _s(args, "patient_ref")
    if by_key:
        return svc.answer_consult(qkey, value, session_key, by_key=True,
                                  patient_ref=patient_ref)
    return svc.answer_consult(value, "", session_key, by_key=False,
                              patient_ref=patient_ref)


def tool_finish_consult(args: Dict[str, Any], svc) -> Dict[str, Any]:
    return svc.finish_consult(_s(args, "session_key"),
                              patient_ref=_s(args, "patient_ref"))


def tool_check_herbs(args: Dict[str, Any], svc) -> Dict[str, Any]:
    herbs = [str(x) for x in _lst(args, "herbs")]
    doses = args.get("doses")
    if not isinstance(doses, dict):
        doses = None
    return svc.check_herbs(herbs, pregnant=_b(args, "pregnant", False),
                           doses=doses)


# ============================================================ 工具清单


def _schema(required: List[str], props: Dict[str, Any]) -> Dict[str, Any]:
    return {"type": "object", "properties": props, "required": required}


_ST = {"type": "string"}
_I = {"type": "integer"}
_B = {"type": "boolean"}
_ARR = {"type": "array", "items": _ST}
_OBS = {"type": "array", "items": {
    "type": "object",
    "properties": {"code": _ST, "value": _ST, "unit": _ST, "note": _ST},
    "required": ["code", "value"]}}
_ROWS = {"type": "array", "items": {
    "type": "array", "items": _ST}}


TOOLS: List[Dict[str, Any]] = [
    {
        "name": "med_health",
        "description": "服务健康检查：报告认知层是否可用、支持的操作与规则表版本。运维探针，不返回临床结论。",
        "inputSchema": _schema([], {}),
        "fn": tool_health,
    },
    {
        "name": "med_record_allergy",
        "description": "登记患者过敏史（salience=5，绝不会被闲聊挤掉）。返回写入结果。",
        "inputSchema": _schema(["patient_ref", "allergen"], {
            "patient_ref": _ST, "allergen": _ST, "reaction": _ST}),
        "fn": tool_record_allergy,
    },
    {
        "name": "med_record_critical",
        "description": "登记重要既往史 / 危急事件（salience=5）。",
        "inputSchema": _schema(["patient_ref", "what"], {
            "patient_ref": _ST, "what": _ST, "detail": _ST}),
        "fn": tool_record_critical,
    },
    {
        "name": "med_record_rule_violation",
        "description": "记录一次用药核对违例（salience=4），供下次问诊召回。",
        "inputSchema": _schema(["patient_ref", "detail"], {
            "patient_ref": _ST, "detail": _ST, "involved": _ARR}),
        "fn": tool_record_rule_violation,
    },
    {
        "name": "med_observe_note",
        "description": "写一条结构化临床笔记（标题/摘要/显著度）。",
        "inputSchema": _schema(["patient_ref", "title", "brief"], {
            "patient_ref": _ST, "title": _ST, "brief": _ST, "salience": _I}),
        "fn": tool_observe_note,
    },
    {
        "name": "med_feel",
        "description": "记录患者情绪事件（驱动共情语气）。valence 取值 -1.0~1.0。",
        "inputSchema": _schema(["patient_ref", "event", "valence"], {
            "patient_ref": _ST, "event": _ST, "valence": {"type": "number"}}),
        "fn": tool_feel,
    },
    {
        "name": "med_recall",
        "description": "按患者检索记忆，返回带来源的记忆条目（供审计与展示依据）。",
        "inputSchema": _schema(["patient_ref", "query"], {
            "patient_ref": _ST, "query": _ST, "k": _I}),
        "fn": tool_recall,
    },
    {
        "name": "med_ask",
        "description": "带护栏问答：先检索、过相关性闸门、再判定有无依据。"
                       "有依据返回来源 + 强制医师确认；无依据如实拒答，绝不编造。",
        "inputSchema": _schema(["patient_ref", "question"], {
            "patient_ref": _ST, "question": _ST, "k": _I, "use_gate": _B}),
        "fn": tool_ask,
    },
    {
        "name": "med_timeline",
        "description": "就诊时间轴（前端左栏用）：检索该患者的历次就诊摘要。",
        "inputSchema": _schema(["patient_ref"], {
            "patient_ref": _ST, "k": _I}),
        "fn": tool_timeline,
    },
    {
        "name": "med_record_encounter",
        "description": "把一次就诊（FHIR 取向）写进该患者的认知记忆：就诊摘要 + 观测 + 规则违例。",
        "inputSchema": _schema(
            ["patient_ref", "encounter_id", "chief_complaint", "assessment", "plan"], {
                "patient_ref": _ST, "encounter_id": _ST, "occurred_at": _ST,
                "department": _ST, "chief_complaint": _ST,
                "observations": _OBS, "assessment": _ST, "plan": _ST}),
        "fn": tool_record_encounter,
    },
    {
        "name": "med_parse_lab_rows",
        "description": "吃结构化检验行（LIS 直连 或 人工录入，绕开 OCR），返回待确认的检验单。",
        "inputSchema": _schema(["patient_ref", "rows"], {
            "patient_ref": _ST, "rows": _ROWS, "engine": _ST, "source": _ST}),
        "fn": tool_parse_lab_rows,
    },
    {
        "name": "med_confirm_lab",
        "description": "回显确认检验单 —— 确认之后才允许入记忆（防数值误读成事故）。",
        "inputSchema": _schema(["report_key"], {
            "report_key": _ST, "corrections": {"type": "object"},
            "all_items": _B}),
        "fn": tool_confirm_lab,
    },
    {
        "name": "med_start_consult",
        "description": "开一次预问诊，返回首个问题（或红十字直接建议就医）。",
        "inputSchema": _schema(["patient_ref"], {
            "patient_ref": _ST, "chief_complaint": _ST, "session_key": _ST}),
        "fn": tool_start_consult,
    },
    {
        "name": "med_answer_consult",
        "description": "回答当前预问诊问题。必须带 patient_ref —— 会话按患者隔离，"
                       "缺了它就无法确定这是谁的问诊。by_key=true 时 value 为问题 key 的回答。",
        "inputSchema": _schema(["patient_ref", "session_key"], {
            "patient_ref": _ST, "session_key": _ST, "value": _ST,
            "by_key": _B, "question_key": _ST}),
        "fn": tool_answer_consult,
    },
    {
        "name": "med_finish_consult",
        "description": "结束问诊，产出《预问诊摘要》并写入认知记忆。必须带 patient_ref。",
        "inputSchema": _schema(["patient_ref", "session_key"], {
            "patient_ref": _ST, "session_key": _ST}),
        "fn": tool_finish_consult,
    },
    {
        "name": "med_check_herbs",
        "description": "中药处方核对（纯规则，绝不经过 LLM）。返回违例 / 是否阻断 / 规则表元信息。"
                       "空违例 ≠ 安全，note 字段必须原样带给医生。",
        "inputSchema": _schema(["herbs"], {
            "herbs": _ARR, "pregnant": _B, "doses": {"type": "object"}}),
        "fn": tool_check_herbs,
    },
]


def list_tools() -> List[Dict[str, Any]]:
    """对外清单：剥离内部 ``fn`` 字段。"""
    return [{k: v for k, v in t.items() if k != "fn"} for t in TOOLS]


def call_tool(name: str, args: Dict[str, Any], svc) -> Dict[str, Any]:
    """按名分发工具。未知工具抛 KeyError（由 server 转成协议错误）。"""
    for t in TOOLS:
        if t["name"] == name:
            result = t["fn"](args or {}, svc)
            # ★ 除运维探针外，所有临床结果强制医师确认。
            if name != "med_health":
                result["requires_physician_confirmation"] = True
            return result
    raise KeyError(name)
