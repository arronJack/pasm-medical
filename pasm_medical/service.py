# -*- coding: utf-8 -*-
"""服务装配 —— 把 PASM 认知能力装成一个**多租户、按患者隔离**的医疗认知服务。

分层的落点
----------
- 认知（记忆 / 情绪 / 倾向）→ 由 PASM 提供，经 HTTP ``/api/cog/*`` 暴露；
- 医疗约束（护栏 / 规则 / 领域模型）→ 由本包提供（:mod:`.safety` / :mod:`.domain`）；
- 业务事务（患者、就诊、处方、审计）→ 由 Spring Boot 侧负责（见 ``backend/``）。

★ 两条不可省的隔离
------------------
1. **知识库**：PASM 的 ``knowledge_base`` 默认落点是**全机共享**的（不给 ``kb_dir``
   就跨实例串库）。这里按租户（机构 / 科室）分片。
2. **认知实例**：``AgentRegistry`` 按 ``agent_id`` 分实例，医疗侧 agent_id 必须
   **带患者维度**（:func:`pasm_medical.domain.agent_id_for`）。

两者都要**用测试证明**，不能"应该隔离了" ——
见 ``tools/e2e_medical_service.py`` 里"造两个患者、断言召回为 0 条"的用例。
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Sequence, Set

from . import safety
from . import lab
from .consult import ConsultEngine, ConsultSession
from .lab import LabReport, OcrEngine
from .domain import (Encounter, agent_id_for, allergy_memory, critical_memory,
                     scrub_identifiers)

try:                                    # 相关性闸门（pasm-skills >= 0.6.2）
    from pasm_skills.cognition import relevance
except Exception:                       # pragma: no cover
    relevance = None                    # type: ignore

__all__ = ["MedicalService", "build_service"]

#: 机构资料库条目在知识库里的 source 前缀。写进 source 是为了能**只清自己写的**条目，
#: 不碰知识库自学习沉淀的 QA（那些是运行时自动攒的，清掉等于删学习成果）。
DOC_SOURCE = "pasm-medical"

#: 清理资料条目需要的插件内部字段（见 _purge_medical_docs 的说明）。
_PURGE_ATTRS = ("_entries", "_save", "_lock", "_index_dirty")


def _doc_key_of(entry: Dict[str, Any]) -> str:
    """从知识库条目里取回业务库的资料 key；不是本项目写入的返回空串。

    ★ 注意 recall 的返回值会把来源**再装饰一层**（``knowledge_base:<原始 source>``，
    见插件的 recall 实现），所以必须先剥外层 —— 少了这一步，所有资料都会被
    自己的前缀判断过滤掉，表现为"资料明明同步进去了，问它却一直拒答"。
    这正是 2026-09-22 第一次跑通时踩到的：断言红了才发现**过滤条件把全部资料滤没了**。
    """
    src = str(entry.get("source") or "")
    if src.startswith("knowledge_base:"):
        src = src.split(":", 1)[1]
    return src.split(":", 1)[1].strip() if src.startswith(DOC_SOURCE + ":") else ""


def _purge_medical_docs(kb: Any) -> int:
    """清掉本项目写入的资料条目，返回清除条数；**不支持时返回 -1**。

    ⚠️ 这里读了 knowledge_base 插件的内部状态（`_entries` / `_save` / `_lock` / `_index_dirty`），
    因为该插件只提供 ``ingest``（追加），**没有 remove / update**；而"资料下架后还能被检索到"
    在医疗场景是事故 —— 不能靠"再追加一版"绕过，否则旧版本永远是依据。

    三重保护，别把它们删掉：
      ① 缺任一私有字段就返回 **-1**，调用方据此回报 ``purgeSupported=false``
         —— **不静默当成清理成功**（那正是这个项目吃过亏的那类假象）；
      ② 只删 ``kind=doc`` 且 source 前缀匹配的条目，不碰自学习沉淀的 QA；
      ③ 清理失败也**不影响正确性**：``recall_docs`` 还会拿业务库的生效集合再过滤一次。

    正解是给框架加一个公开的 ``remove_source()``（已记为待办，见 docs/GUIDE.md §8）。
    """
    if not all(hasattr(kb, a) for a in _PURGE_ATTRS):
        return -1
    try:
        with kb._lock:
            before = len(kb._entries)
            kb._entries = [e for e in kb._entries
                           if not (isinstance(e, dict)
                                   and e.get("kind") == "doc"
                                   and str(e.get("source") or "").startswith(DOC_SOURCE + ":"))]
            removed = before - len(kb._entries)
            if removed:
                kb._save()
                kb._index_dirty = True
        return removed
    except Exception:                       # pragma: no cover
        return -1


class MedicalService:
    """一个租户（机构 / 科室）的认知服务。

    每个租户一个实例；租户内**每个患者一个认知 agent**。
    """

    def __init__(self, app: Any, *, tenant: str,
                 consult_engine: Optional[ConsultEngine] = None) -> None:
        self.app = app
        self.tenant = tenant
        self.engine = consult_engine or ConsultEngine()
        #: ★ 进行中的问诊（进程内）。生产须落 Redis/DB —— 多实例下进程内状态会丢。
        self._consults: Dict[str, ConsultSession] = {}
        #: 待确认的检验单：report_key → (patient_ref, LabReport)
        self._lab_reports: Dict[str, Any] = {}
        #: OCR 引擎（不配置时检验单功能不可用，但服务照常启动）
        self.ocr: Optional[Any] = None

    # ---------------------------------------------------------- 内部

    def _agent(self, patient_ref: str) -> str:
        return agent_id_for(self.tenant, patient_ref)

    def _caps(self) -> Any:
        """取认知能力门面（pasm-framework 的 web_gateway 持有）。

        ★ 走 ``gateway.cognitive.capabilities``，**不要自己 new 一个**：
        两个 ``AgentRegistry`` 会让「HTTP 写入」「进程内读取」互相看不见，
        而且不报错 —— 属于最难查的那种 bug。

        拿不到就返回 None —— 由调用方决定降级还是报错，**不要在这里抛**：
        服务要能起来，缺能力时如实申报（见 :meth:`health`）。
        """
        pm = getattr(self.app, "plugins", None)
        gw = pm.get("web_gateway") if pm is not None else None
        api = getattr(gw, "cognitive", None) if gw is not None else None
        return getattr(api, "capabilities", None) if api is not None else None

    # ---------------------------------------------------------- 写入

    def record_allergy(self, patient_ref: str, allergen: str,
                       reaction: str = "") -> Dict[str, Any]:
        """登记过敏史（**salience=5，不会被闲聊挤掉**）。"""
        caps = self._caps()
        if caps is None:
            return {"ok": False, "error": "认知层不可用"}
        mem = allergy_memory(allergen, reaction)
        return caps.observe(self._agent(patient_ref), **mem)

    def record_critical(self, patient_ref: str, what: str,
                        detail: str = "") -> Dict[str, Any]:
        """登记重要既往史 / 危急事件（salience=5）。"""
        caps = self._caps()
        if caps is None:
            return {"ok": False, "error": "认知层不可用"}
        return caps.observe(self._agent(patient_ref), **critical_memory(what, detail))

    def record_encounter(self, enc: Encounter) -> Dict[str, Any]:
        """把一次就诊写进该患者的认知记忆（一次就诊 = 一个 episode）。

        写三类：就诊摘要（salience 4）、观测（3）、以及**规则违例**（若有 → 另记一条）。
        规则违例单独记是因为它必须能被下次问诊召回 —— 医生需要知道"上次开过有配伍
        风险的方子"。
        """
        caps = self._caps()
        if caps is None:
            return {"ok": False, "error": "认知层不可用"}
        aid = self._agent(enc.patient_ref)
        n = 0
        caps.observe(aid, **enc.to_memory())
        n += 1
        for mem in enc.to_observation_memories():
            caps.observe(aid, **mem)
            n += 1
        return {"ok": True, "agent_id": aid, "written": n,
                "encounter_id": enc.encounter_id}

    def record_rule_violation(self, patient_ref: str, detail: str,
                              involved: Sequence[str]) -> Dict[str, Any]:
        """把一次规则违例写进记忆（salience=4）。"""
        caps = self._caps()
        if caps is None:
            return {"ok": False, "error": "认知层不可用"}
        return caps.observe(self._agent(patient_ref),
                            title="用药核对·发现违例",
                            brief=scrub_identifiers(detail)[:300],
                            tags=list(involved)[:5] + ["用药安全"],
                            salience=4)

    def feel(self, patient_ref: str, event: str, valence: float) -> Dict[str, Any]:
        """记录患者情绪事件（驱动共情语气）。"""
        caps = self._caps()
        if caps is None:
            return {"ok": False, "error": "认知层不可用"}
        return caps.feel(self._agent(patient_ref), event=event, valence=valence)

    # ---------------------------------------------------------- 读取

    def recall(self, patient_ref: str, query: str, k: int = 5) -> Dict[str, Any]:
        """按患者检索记忆。**返回带来源的记忆条目**，供审计与展示依据。"""
        caps = self._caps()
        if caps is None:
            return {"ok": False, "error": "认知层不可用", "hits": []}
        return caps.recall(self._agent(patient_ref), query=query, k=k)

    def ask(self, patient_ref: str, question: str,
            k: int = 5, *, use_gate: bool = True,
            doc_keys: Optional[Set[str]] = None) -> Dict[str, Any]:
        """带护栏的问答。

        ★ 行为：**先检索，再过相关性闸门，再判断有无依据**。
          · 有够格的依据 → 带出 ``sources``，并强制
            ``requires_physician_confirmation=True``；
          · 没有 → **拒答**（返回 :data:`safety.REFUSAL_TEXT`），
            **不编造**、也不用"可能/或许"软化。

        ★ 为什么必须有闸门（2026-09-21 实测教训）
        ----------------------------------------
        语义检索对**任何**问题都会返回若干条结果（只是分数不同）。不设闸门时：

            问「髋关节置换术后康复方案」→ 命中「过敏史·磺胺」(0.47) 与「初次相遇」
            → 系统据此作答，**"无依据必须拒答"形同虚设**

        而分数阈值又不可靠（打分带时间衰减，资料库越老越"失忆"）。
        所以闸门用的是结构判据：命中的词必须落在资料的**标题或标签**上。
        实现见 ``pasm_skills.cognition.relevance``（共享实现，非本项目私有）。

        注意本方法**不生成自然语言结论** —— 那是 Spring Boot 侧接大模型之后的事。
        这里只负责把"能不能答、凭哪几条答"判定清楚。
        """
        # ★ 两路召回：**患者私有记忆**（按患者分片）+ **机构资料库**（全院共享）。
        #   以前只召回记忆 —— 于是"资料库"对问答毫无影响，那它就只是个摆设。
        #   两路的来源必须标出来（sources[].kind）：医生要能分清"这条是患者档案里的"
        #   还是"这条是机构指南里的"，两者的可信度与责任不同。
        got = self.recall(patient_ref, question, k=k)
        raw = list(got.get("hits") or []) + self.recall_docs(
            question, k=k, active_keys=doc_keys)
        if use_gate and relevance is None:
            # ★ 装了闸门却用不了 → **宁可拒答也不放行**。
            #   这里与"校验类逻辑无法判定就放行"的规则不同：那条规则防的是**误拦正确内容**；
            #   而这里是要**主动断言**一件事，没有判据就不该断言。
            d = safety.guard_answer("", [], min_sources=1).to_dict()
            d.update({"ok": True, "agent_id": self._agent(patient_ref),
                      "hits": [], "count": 0, "raw_count": len(raw),
                      "gate": False,
                      "refusal_reason": "相关性闸门不可用（pasm-skills 版本过低）"})
            return d
        if use_gate:
            keep = relevance.select_evidence(question, raw, limit=k)
        else:
            keep = list(raw)                    # 仅调试用：跳过闸门以观察原始命中
        sources = [{"title": h.get("title", ""), "brief": h.get("brief", ""),
                    "tags": list(h.get("tags", [])),
                    "salience": h.get("salience", 1),
                    # memory=患者档案/记忆；doc=机构资料库。医生需要知道依据来自哪一侧
                    "kind": h.get("kind", "memory"),
                    **({"docKey": h["docKey"]} if h.get("docKey") else {}),
                    **({"score": h["score"]} if "score" in h else {})}
                   for h in keep]
        guarded = safety.guard_answer("", sources, min_sources=1)
        d = guarded.to_dict()
        d.update({"ok": True, "agent_id": self._agent(patient_ref),
                  "hits": sources, "count": len(sources),
                  "raw_count": len(raw), "gate": bool(use_gate)})
        return d

    def timeline(self, patient_ref: str, k: int = 20) -> List[Dict[str, Any]]:
        """就诊时间轴（给前端左栏用）。

        实现上是"用'就诊'去检索记忆" —— 走的是同一套认知检索，
        不另建存储，避免业务库与认知层两份数据打架。
        """
        got = self.recall(patient_ref, "就诊 主诉 判断 处置", k=k)
        return [h for h in (got.get("hits") or []) if "就诊" in (h.get("title") or "")]

    # ---------------------------------------------------------- 资料库（机构级，**不按患者分片**）

    def _kb(self) -> Any:
        """取框架的知识库插件。取不到返回 None（调用方好判断，别抛）。"""
        pm = getattr(self.app, "plugins", None)
        return pm.get("knowledge_base") if pm is not None else None

    def replace_docs(self, docs: Sequence[Dict[str, Any]]) -> Dict[str, Any]:
        """**全量替换**机构资料库（业务库是权威，认知侧只是检索副本）。

        为什么是全量替换，而不是逐条增删改：
        权威数据在业务库（Spring Boot 的 `knowledge_docs` 表），带状态 / 版本 / 复核人；
        认知侧只需要一份"当前生效资料的检索副本"。逐条改删要在两侧同步 id 与版本，
        多一处不一致就多一处"下架了却还能被引用"。

        ★ 业务层每次变更后推**当前生效的全集**，这里先清掉上一批再重灌。
        """
        kb = self._kb()
        if kb is None:
            return {"ok": False, "error": "knowledge_base 插件未启用，资料库无法落库"}
        purged = _purge_medical_docs(kb)
        items: List[Dict[str, Any]] = []
        for d in docs or []:
            key = str(d.get("key") or "").strip()
            title = str(d.get("title") or "").strip()
            if not key or not title:
                continue
            content = str(d.get("content") or "").strip() or title
            items.append({
                "title": title,
                "content": content,
                "tags": [str(t) for t in (d.get("tags") or [])],
                # source 同时承担两个作用：① 标明"这条是本项目写入的"（供清理）；
                # ② 承载业务库的 key（供 ask 用生效集合过滤）。别挪进 tags —— tags 是
                # 相关性闸门的命中面，塞 key 进去会让闸门多出噪声命中。
                "source": "%s:%s" % (DOC_SOURCE, key),
            })
        added = int(kb.ingest(items)) if items else 0
        return {"ok": True, "purged": purged, "purgeSupported": purged >= 0,
                "added": added, "expected": len(items)}

    def recall_docs(self, query: str, k: int = 5,
                    active_keys: Optional[Set[str]] = None) -> List[Dict[str, Any]]:
        """检索机构资料（**共享**，与患者无关）。

        `active_keys` 是业务库里"当前生效"的资料 key 集合。传了就只返回生效资料 ——
        这是**正确性的第二道保险**：即使认知侧的清理失败（历史版本还留在库里），
        已下架的资料也不会被当作依据引用。
        """
        kb = self._kb()
        if kb is None or not query:
            return []
        try:
            hits = kb.recall(query, k=max(k * 3, 12))
        except Exception:
            return []
        out: List[Dict[str, Any]] = []
        for h in hits or []:
            if not isinstance(h, dict) or h.get("kind") != "doc":
                continue                      # 自学习沉淀的 QA 不算"机构资料"
            key = _doc_key_of(h)
            if not key:
                continue                      # 非本项目写入的资料，不掺进医疗依据
            if active_keys is not None and key not in active_keys:
                continue
            out.append({
                "title": h.get("title") or "",
                "brief": str(h.get("content") or "")[:200],
                "tags": list(h.get("tags") or []),
                "salience": 3,                # 机构资料比患者记忆更该被优先引用
                "kind": "doc",
                "docKey": key,
                "score": h.get("score"),
            })
            if len(out) >= k:
                break
        return out

    def doc_stats(self) -> Dict[str, Any]:
        """资料库现状（给运维/验证用）：总条数 / 资料条数 / 自学习 QA 条数。"""
        kb = self._kb()
        if kb is None:
            return {"ok": False, "error": "knowledge_base 插件未启用"}
        s: Dict[str, Any] = {}
        if hasattr(kb, "stats"):
            try:
                s.update(dict(kb.stats()))
            except Exception:
                pass
        s["ok"] = True
        s["purgeSupported"] = all(hasattr(kb, a) for a in _PURGE_ATTRS)
        return s

    # ---------------------------------------------------------- 检验单（OCR → 确认 → 入记忆）

    def parse_lab_image(self, patient_ref: str, image_path: str, *,
                        ocr: Optional["OcrEngine"] = None,
                        source: str = "") -> Dict[str, Any]:
        """识别检验单并返回**待确认**的结构化结果。

        ★ 关键：本方法**只识别、不入库**。返回值里 ``needs_confirmation=True``，
        必须由前端回显给患者/护士核对后调 :meth:`confirm_lab` 才写进病历。
        理由：13.5 识别成 18.5，在别的场景是 bug，在这里是事故。
        """
        eng = ocr or self.ocr
        if eng is None or not eng.available():
            return {"ok": False, "error": "OCR 未配置",
                    "hint": "在后台「对接设置」里配置化验单 OCR，或改为 LIS 直连"}
        rows = eng.rows(image_path)
        rep = lab.parse_rows(rows, engine=eng.name, source=source or image_path)
        key = "%s:lab" % patient_ref
        self._lab_reports[key] = (patient_ref, rep)
        d = rep.to_dict()
        d.update({"ok": True, "report_key": key})
        return d

    def parse_lab_rows(self, patient_ref: str, rows: List[List[str]], *,
                       engine: str = "manual", source: str = "") -> Dict[str, Any]:
        """直接吃结构化行（LIS 直连 或 人工录入 走这条，绕开 OCR）。"""
        rep = lab.parse_rows(rows, engine=engine, source=source)
        key = "%s:lab" % patient_ref
        self._lab_reports[key] = (patient_ref, rep)
        d = rep.to_dict()
        d.update({"ok": True, "report_key": key})
        return d

    def confirm_lab(self, report_key: str, *,
                    corrections: Optional[Dict[str, float]] = None,
                    all_items: bool = False) -> Dict[str, Any]:
        """回显确认 —— **确认之后才允许入记忆**。"""
        got = self._lab_reports.get(report_key)
        if got is None:
            return {"ok": False, "error": "找不到该检验单，请先识别"}
        patient_ref, rep = got
        r = rep.confirm(corrections, all_items=all_items)
        mems = rep.to_memories()
        written = 0
        for m in mems:
            if self.observe_note(patient_ref, m["title"], m["brief"],
                                 m["salience"]).get("ok"):
                written += 1
        return {"ok": True, "confirm": r, "written": written,
                "critical": rep.critival_summary(),
                "report": rep.to_dict()}

    # ---------------------------------------------------------- 预问诊（问诊树）

    def start_consult(self, patient_ref: str, chief_complaint: str = "",
                      session_key: str = "") -> Dict[str, Any]:
        """开一次预问诊。返回首个问题（或红十字直接建议就医）。

        ★ 问诊状态**进程内保存**（``self._consults``）—— 这是 P1 的第一版。
        生产必须落到 Redis/DB：多实例部署下进程内状态会丢，患者刷新就断线。
        """
        s = ConsultSession(self.engine, patient_ref=patient_ref)
        key = session_key or patient_ref
        if chief_complaint:
            r = s.set_chief_complaint(chief_complaint)
            if not r.get("ok"):
                return r                       # 识别不出 → 让前端出主诉选择
        self._consults[key] = s
        return self._consult_state(s)

    def answer_consult(self, text_or_key: str, value: str = "",
                       session_key: str = "", *, by_key: bool = False) -> Dict[str, Any]:
        """回答当前问题。``by_key=True`` 时第一个参数是问题 key。"""
        key = session_key or text_or_key if by_key else session_key
        s = self._consults.get(session_key)
        if s is None:
            return {"ok": False, "error": "没有进行中的问诊，请先调用 start_consult"}
        if by_key:
            r = s.answer(text_or_key, value)
        else:
            r = s.answer_current(text_or_key)
        out = self._consult_state(s)
        out["answer_result"] = r
        # ★ 主诉与红旗事件写入认知记忆：复诊时不必重问（这就是记忆的价值）
        if r.get("halted"):
            self.record_rule_violation(patient_ref=s.patient_ref,
                                       detail="预问诊命中警示信号：" +
                                              "；".join(x["label"] for x in r["red_flags"]),
                                       involved=[x["id"] for x in r["red_flags"]])
        return out

    def finish_consult(self, session_key: str) -> Dict[str, Any]:
        """结束问诊，产出《预问诊摘要》并写入认知记忆。"""
        s = self._consults.get(session_key)
        if s is None:
            return {"ok": False, "error": "没有进行中的问诊"}
        sm = s.summary()
        # 摘要入认知层：主诉 + 关键事实（过敏史 salience=5，绝不能被闲聊挤掉）
        if s.facts.get("过敏史"):
            self.record_allergy(s.patient_ref, str(s.facts["过敏史"]), "预问诊采集")
        if s.chief_complaint:
            self.observe_note(s.patient_ref, "预问诊·%s" % s.chief_complaint,
                              "；".join("%s：%s" % (k, v)
                                        for k, v in list(s.facts.items())[:8]), 4)
        self._consults.pop(session_key, None)
        return {"ok": True, **sm}

    def _consult_state(self, s: "ConsultSession") -> Dict[str, Any]:
        q = s.next_question()
        return {"ok": True, "halted": s.halted, "red_flags": s.red_flags_hit,
                "coverage": s.coverage(),
                "question": (q.to_dict() if q else None),
                "triage": s.triage() if s.halted else None}

    def observe_note(self, patient_ref: str, title: str, brief: str,
                     salience: int = 3) -> Dict[str, Any]:
        caps = self._caps()
        if caps is None:
            return {"ok": False, "error": "认知层不可用"}
        return caps.observe(self._agent(patient_ref), title=title, brief=brief,
                            tags=[], salience=salience)

    # ---------------------------------------------------------- 确定性规则

    def check_herbs(self, herbs: Sequence[str], *, pregnant: bool = False,
                    doses: Optional[Dict[str, float]] = None) -> Dict[str, Any]:
        """中药处方核对 —— **纯规则，绝不经过 LLM**。

        返回值里的 ``note`` 必须原样带给医生：**空违例 ≠ 安全**。
        """
        vio = safety.check_herbs(herbs, pregnant=pregnant, doses=doses)
        return {
            "violations": [v.to_dict() for v in vio],
            "blocking": safety.has_blocking(vio),
            "rules": safety.table_meta(),
            "note": ("空违例只表示「本规则表未发现违例」，不表示安全。"
                     "最终处方权与审核责任在医师 / 药师。"),
        }

    # ---------------------------------------------------------- 运维

    def llm_status(self) -> Dict[str, Any]:
        """本服务**实际生效**的 LLM 配置（只读，供业务层做「期望 vs 实际」对账）。

        ★ 为什么要有这个接口
        --------------------
        后台「对接设置」由业务层持久化（机构想用什么模型），但**真正跑模型的是这一层**。
        两边一旦不一致（业务层写着 ollama，进程却是 ``PASM_MEDICAL_LLM=null``），
        界面会一直显示"已配置"，而患者其实拿到的是模板话术 —— 这种"配置了但其实没生效"
        比配置项本身不存在更危险。所以这里把**进程真实值**如实报出来，由业务层比对。

        ★ 刻意**不做**可用性探测
        ------------------------
        :meth:`pasm_medical.llm.OllamaProvider.available` 会发一次 HTTP（3s 超时）。
        配置查询是只读且高频的，不该在这里挂 3 秒；要探活请用 ``probe`` 参数显式要求。

        api_key 一律打码（见 :meth:`pasm_medical.llm.LLMConfig.to_dict`）。
        """
        from .llm import LLMConfig, build_provider

        cfg = LLMConfig.from_env()
        out = cfg.to_dict()
        out["source"] = "env:PASM_MEDICAL_LLM*"
        # provider 是否被识别。判据取自 build_provider 本身（不另抄一份别名表，
        # 否则别名表一改这里就开始说谎）：识别得出非 Null 实现即算已知；
        # 显式写 "null" 也算已知（那是刻意的降级档，不是拼错）。
        requested = (cfg.provider or "null").lower()
        out["provider_known"] = (build_provider(cfg).name != "null") or (requested == "null")
        return out

    def health(self) -> Dict[str, Any]:
        caps = self._caps()
        pm = getattr(self.app, "plugins", None)
        gw = pm.get("web_gateway") if pm is not None else None
        api = getattr(gw, "cognitive", None) if gw is not None else None
        err = None
        if api is None:
            err = "网关未挂载认知接口（web_gateway 未启用，或 cognitive=false）"
        elif caps is None:
            err = getattr(api, "error", None) and api.error() or "认知层未就绪"
        _ri = getattr(gw, "routes_info", None)
        return {
            "tenant": self.tenant,
            # ★ 应用侧注册的自定义路由（= 医疗接口）。**0 条就是"没挂上"的信号** ——
            #   与 pasm-framework 侧 /healthz 的 custom_routes 是同一个数，
            #   故意两处都暴露：业务层只跟本服务说话，未必会去看网关的 /healthz。
            "custom_routes": (_ri() if callable(_ri) else None),
            "cognitive_available": caps is not None,
            "cognitive_error": err,
            "operations": (caps.operations() if caps is not None else []),
            "rules": safety.table_meta(),
            "note": ("requires_physician_confirmation 恒为 True；"
                     "本服务不产出处方，只做核对与依据呈现。"),
        }


def register_medical_routes(svc: "MedicalService") -> int:
    """把医疗接口挂到网关的自定义路由上，返回注册条数。

    ★ 路径与 Spring Boot 的前端契约一致 —— 这样业务层是**薄薄一层转发 + 鉴权**，
    不用把问诊树/检验单的逻辑在 Java 里再实现一遍（那就成了同源两份代码）。

    ★ 网关会把这些路由一律按**管理作用域**鉴权（见 web_gateway.match_route），
    所以 Spring Boot 必须带管理令牌来调。
    """
    pm = getattr(svc.app, "plugins", None)
    gw = pm.get("web_gateway") if pm is not None else None
    # ★★ 这里**必须硬失败**，不能静默 return 0。
    #   历史事故：`register_route` 曾经只存在于 pasm-framework 的**未提交工作区**，
    #   于是 pip 装出来的包"医疗接口全 404"，而服务照报 healthy、
    #   管理台照常能聊 —— **零提示**。返回 0 会让这种包一路绿灯发出去。
    if gw is None:
        raise RuntimeError(
            "web_gateway 插件未启用，医疗接口无法挂载"
            "（backend_config 里需要 web_gateway.enabled=true，见 build_service）")
    if not hasattr(gw, "register_route"):
        raise RuntimeError(
            "当前 pasm-framework 不支持 register_route —— 装的版本 < 0.5.3。"
            "这类包\"装得上但医疗接口全 404\"，且网关照报 healthy、管理台照常能聊，"
            "零提示，所以这里直接失败而不是继续跑。"
            "修复：pip install -U \"pasm-framework>=0.5.3\"")

    def _start(q, b):
        return 200, svc.start_consult(
            str(b.get("patientRef") or ""),
            str(b.get("chiefComplaint") or ""),
            session_key=str(b.get("sessionKey") or "current"))

    def _answer(q, b):
        return 200, svc.answer_consult(
            str(b.get("value") or ""),
            session_key=str(b.get("sessionKey") or "current"),
            by_key=bool(b.get("byKey")))

    def _finish(q, b):
        return 200, svc.finish_consult(str(b.get("sessionKey") or "current"))

    def _lab_parse(q, b):
        return 200, svc.parse_lab_image(
            str(b.get("patientRef") or ""), str(b.get("imageUrl") or ""),
            source=str(b.get("source") or ""))

    def _lab_rows(q, b):
        rows = b.get("rows") or []
        return 200, svc.parse_lab_rows(str(b.get("patientRef") or ""), rows,
                                       engine=str(b.get("engine") or "manual"))

    def _lab_confirm(q, b):
        corrections = b.get("corrections") if isinstance(b.get("corrections"), dict) else {}
        return 200, svc.confirm_lab(
            str(b.get("reportKey") or ""), corrections=corrections,
            all_items=bool(b.get("allItems")))

    def _encounters(q, b):
        ref = str(q.get("patientRef") or b.get("patientRef") or "")
        return 200, {"encounters": svc.timeline(ref, k=30)}

    def _critical(q, b):
        return 200, svc.record_allergy(str(b.get("patientRef") or ""),
                                       str(b.get("title") or ""),
                                       str(b.get("detail") or ""))

    def _config(q, b):
        """只读：本进程**实际生效**的 LLM 配置。业务层用它跟"机构期望值"对账。"""
        return 200, svc.llm_status()

    # ★ 资料库用 /api/library/* 而不是 /api/kb/*：后者是框架自带的资料库前缀
    #   （`/api/kb/stats` 就在 `_RESERVED_PATHS` 里），混在一起会让"这条路由归谁管"
    #   变得说不清 —— 而且真撞上时 register_route 会直接抛错（这次就被它挡了一次）。
    def _kb_sync(q, b):
        """全量替换机构资料库（业务库是权威，这里是检索副本）。"""
        return 200, svc.replace_docs(b.get("docs") or [])

    def _kb_stats(q, b):
        return 200, svc.doc_stats()

    def _ask(q, b):
        """**带闸门**的问答：患者记忆 + 机构资料两路召回，没依据就拒答。

        ★ 为什么不直接让业务层调框架的 ``/api/cog/recall``：那条路只召回患者记忆，
        既不含机构资料、也不经过相关性闸门 —— 于是"资料库"对问答毫无影响，
        "无依据必须拒答"在界面上也形同虚设。判据归医疗侧，业务层只做转发。
        """
        keys = b.get("docKeys")
        return 200, svc.ask(
            str(b.get("patientRef") or ""), str(b.get("question") or ""),
            k=int(b.get("k") or 5),
            doc_keys={str(x) for x in keys} if isinstance(keys, list) else None)

    routes = [
        ("POST", "/api/consult/start", _start),
        ("POST", "/api/consult/answer", _answer),
        ("POST", "/api/consult/finish", _finish),
        ("POST", "/api/lab/parse", _lab_parse),
        ("POST", "/api/lab/rows", _lab_rows),
        ("POST", "/api/lab/confirm", _lab_confirm),
        ("GET", "/api/encounters", _encounters),
        ("POST", "/api/critical-fact", _critical),
        ("GET", "/api/config", _config),
        ("POST", "/api/library/sync", _kb_sync),
        ("GET", "/api/library/stats", _kb_stats),
        ("POST", "/api/answer", _ask),
    ]
    for method, path, fn in routes:
        gw.register_route(method, path, fn)
    # ★ 注册完立刻回读一次：确认每条都**真能被匹配到**，而不是"注册了个寂寞"。
    #   这条与 framework 侧 /healthz 的 custom_routes 互补 ——
    #   那边证明"注册了几条"，这边证明"注册的确实生效"。
    unbound = ["%s %s" % (m, p) for m, p, _ in routes
               if gw.match_route(m, p) is None]
    if unbound:
        raise RuntimeError("医疗接口注册后无法匹配（框架行为异常）：%s" % unbound)
    return len(routes)


def build_service(*, tenant: str, kb_dir: str, persist_dir: str,
                  host: str = "127.0.0.1", port: int = 0,
                  token: str = "", workspace: Optional[str] = None) -> MedicalService:
    """装配一个租户的医疗认知服务并绑好 HTTP 网关。

    ``token`` 是**管理令牌** —— 认知接口（写记忆、改人格）与资料库管理都要它。
    不要把它发到浏览器；前端只跟 Spring Boot 说话。
    """
    from pasm_framework import SimpleApplication

    ws = workspace or persist_dir
    Path(kb_dir).mkdir(parents=True, exist_ok=True)
    Path(persist_dir).mkdir(parents=True, exist_ok=True)

    app = SimpleApplication(
        agent_id="medical:%s" % tenant,
        persona={"name": "诊疗助手", "role": "临床辅助", "tone": "简洁、克制",
                 "temper": 0.3, "energy": 0.4, "play": 0.0},
        persist_dir=os.path.join(ws, "app"),
        backend_config={
            "knowledge_base": {"enabled": True, "config": {"kb_dir": kb_dir}},
            "web_gateway": {"enabled": True, "config": {
                "host": host, "port": port, "token": token,
                # ★ 认知实例落点按租户分片；患者维度由 agent_id 再分一层
                "cognitive_persist_dir": os.path.join(ws, "cog"),
                "cognitive_persona": {"name": "诊疗助手", "role": "临床辅助",
                                      "tone": "简洁、克制", "play": 0.0},
            }},
        },
    )
    svc = MedicalService(app, tenant=tenant)
    register_medical_routes(svc)          # ★ 把医疗接口挂到网关
    return svc


def main() -> int:                                          # pragma: no cover
    """命令行入口：起一个本地医疗认知服务，便于联调。"""
    import argparse
    import json

    ap = argparse.ArgumentParser(prog="pasm-medical")
    ap.add_argument("--tenant", default="demo-hospital")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8090)
    ap.add_argument("--token", default=os.environ.get("PASM_MEDICAL_TOKEN", ""))
    ap.add_argument("--dir", default=os.path.join(os.path.expanduser("~"),
                                                  ".pasm-medical"))
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        import sys
        from . import domain
        return 0 if (domain.selftest() and safety.selftest()) else 1

    svc = build_service(tenant=a.tenant, kb_dir=os.path.join(a.dir, "kb"),
                        persist_dir=os.path.join(a.dir, "persist"),
                        host=a.host, port=a.port, token=a.token)
    print(json.dumps(svc.health(), ensure_ascii=False, indent=2))
    print("\n认知接口前缀：/api/cog/  （详见 pasm-framework 的 web_gateway 文档）")
    print("医疗接口：/api/consult/*、/api/lab/*、/api/encounters、/api/config（需管理令牌）")
    svc.app.serve()

    # ★ `serve()` 是**非阻塞**的：它在守护线程里起 HTTP 服务器后立刻返回。
    #   如果这里不挡住主线程，进程会直接退出 —— 表现是"打印了健康信息但端口从没开过"，
    #   而且退出码是 0（看起来像正常结束），极难判断。
    print("\n服务已启动，Ctrl+C 停止。")
    import signal
    import threading
    stop = threading.Event()
    try:
        signal.signal(signal.SIGINT, lambda *_: stop.set())
        signal.signal(signal.SIGTERM, lambda *_: stop.set())
    except Exception:                                   # noqa: BLE001
        pass
    try:
        while not stop.is_set():
            stop.wait(1.0)
    except KeyboardInterrupt:                           # pragma: no cover
        pass
    finally:
        print("正在关闭…")
        try:
            svc.app.close()
        except Exception:                               # noqa: BLE001
            pass
    return 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(main())
