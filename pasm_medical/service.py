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
from typing import Any, Dict, List, Optional, Sequence

from . import safety
from .consult import ConsultEngine, ConsultSession
from .domain import (Encounter, agent_id_for, allergy_memory, critical_memory,
                     scrub_identifiers)

try:                                    # 相关性闸门（pasm-skills >= 0.6.2）
    from pasm_skills.cognition import relevance
except Exception:                       # pragma: no cover
    relevance = None                    # type: ignore

__all__ = ["MedicalService", "build_service"]


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
            k: int = 5, *, use_gate: bool = True) -> Dict[str, Any]:
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
        got = self.recall(patient_ref, question, k=k)
        raw = got.get("hits") or []
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
        return {
            "tenant": self.tenant,
            "cognitive_available": caps is not None,
            "cognitive_error": err,
            "operations": (caps.operations() if caps is not None else []),
            "rules": safety.table_meta(),
            "note": ("requires_physician_confirmation 恒为 True；"
                     "本服务不产出处方，只做核对与依据呈现。"),
        }


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
    return MedicalService(app, tenant=tenant)


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
    svc.app.serve()
    return 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(main())
