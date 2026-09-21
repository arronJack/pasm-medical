# -*- coding: utf-8 -*-
"""领域模型：患者 / 就诊 / 病历，以及**患者级隔离**的 agent_id 约定。

★ 为什么隔离这件事要单独写成模块
--------------------------------
PASM 的知识库与记忆默认落点是**全机共享**的（不给 ``kb_dir`` / ``persist_dir``
就跨实例串库）。在通用场景那只是"串味"，在医疗场景是**数据泄露** ——
A 患者的过敏史出现在 B 患者的召回里，是要出事的。

所以这里把"每个患者一个认知实例、且落点必须带患者维度"固化成函数，
并配上可执行的隔离测试（``tools/e2e_medical_service.py`` 里断言跨患者召回为 0 条）。
**"应该隔离了"不算数，要能证明。**

FHIR 取向
---------
字段命名向 FHIR 靠（Patient / Encounter / Condition / Observation /
MedicationRequest），是为了将来对接 HIS / EMR 时不用推倒重来。
本项目只放**最小可用子集**，不做完整 FHIR 实现。
"""
from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass, field, asdict
from typing import Any, Dict, List, Optional

#: agent_id 允许的字符（用于落盘目录名，必须防路径穿越）
_SAFE = re.compile(r"[^A-Za-z0-9_.-]")

#: 姓名等直接标识**不入库**：认知层只存去标识化后的引用。
#: 真实姓名 / 身份证 / 手机号留在业务库（Spring Boot 侧），认知层拿不到也存不下。
_SENSITIVE_PATTERNS = (
    re.compile(r"\b1[3-9]\d{9}\b"),                       # 手机号
    re.compile(r"\b\d{17}[\dXx]\b"),                      # 身份证
    re.compile(r"\b\d{15}\b"),
)


def agent_id_for(tenant: str, patient_ref: str) -> str:
    """把 (机构/科室, 患者引用) 映射成认知层的 agent_id。

    ``patient_ref`` 应当是**业务库里的患者主键或哈希**，不要传真实姓名 ——
    它会进入落盘目录名，而目录名会出现在日志与备份里。

    形如 ``h1_3f2a9c1b8e7d4a55``：前缀给租户，便于人工排查时一眼看出归属。
    """
    t = _SAFE.sub("_", (tenant or "default").strip()) or "default"
    p = _SAFE.sub("_", (patient_ref or "").strip())
    if not p:
        raise ValueError("patient_ref 不能为空 —— 没有患者就没有认知实例")
    return "%s_%s" % (t[:16], p[:48])


def pseudonymize(raw_id: str, *, salt: str = "pasm-medical") -> str:
    """把业务主键做**确定性**假名化（同一输入 → 同一输出，便于跨调用对齐）。

    用 sha256 截断而非随机：随机会让同一患者每次生成不同 id，记忆就散了。
    ⚠️ 这不是加密 —— 它防的是"误把真实标识写进认知层"，不防穷举。
    需要更强保护时，salt 必须换成**按机构密钥管理**的值。
    """
    h = hashlib.sha256(("%s|%s" % (salt, raw_id or "")).encode("utf-8"))
    return h.hexdigest()[:16]


def scrub_identifiers(text: str) -> str:
    """粗粒度去标识：把手机号 / 身份证替换成占位符。

    这是**兜底**，不是合规方案 —— 真正的去标识化在数据出业务库之前就该做完
    （见 PLAN.md §1.3）。放这一层是为了"就算上游漏了，认知层也不留痕"。
    """
    out = text or ""
    for pat in _SENSITIVE_PATTERNS:
        out = pat.sub("[已去标识]", out)
    return out


@dataclass
class Observation:
    """一条观测（症状 / 体征 / 检验）。对应 FHIR Observation 的最小投影。"""

    code: str = ""                       # 观测项（如 体温 / 血压）
    value: str = ""                      # 取值（如 38.9 / 152/94）
    unit: str = ""
    note: str = ""

    def to_fact(self) -> Dict[str, Any]:
        text = "%s %s%s" % (self.code, self.value, self.unit)
        if self.note:
            text += "（%s）" % self.note
        return {"observation": text.strip(), "code": self.code}


@dataclass
class Encounter:
    """一次就诊 —— 对应 FHIR Encounter，也是认知层的**一个 episode**。

    认知层把"一次就诊"当一个情景写入，左栏时间轴点开看到的就是它。
    """

    encounter_id: str = ""
    patient_ref: str = ""
    occurred_at: str = ""                # ISO8601
    department: str = ""
    chief_complaint: str = ""            # 主诉
    observations: List[Observation] = field(default_factory=list)
    assessment: str = ""                 # 医生判断（**金标准来源**）
    plan: str = ""                       # 处置计划

    def to_memory(self) -> Dict[str, Any]:
        """转成认知层的一条记忆（observe 入参）。

        ★ ``salience`` 的选择有讲究：
          · 主诉与判断 → 4（重要，复诊时要能想起来）
          · 观测 → 3
          · 处置 → 3
        不设 5：5 是"绝不能被闲聊挤掉"的级别，留给**过敏史 / 危急事件**这类
        （见 :func:`allergy_memory`）。全员 5 等于没有优先级。
        """
        brief = "；".join(
            filter(None, [self.chief_complaint, self.assessment, self.plan]))
        return {
            "title": "就诊·%s" % (self.department or "门诊"),
            "brief": scrub_identifiers(brief)[:500],
            "tags": [t for t in (self.department, "就诊") if t],
            "salience": 4,
        }

    def to_observation_memories(self) -> List[Dict[str, Any]]:
        return [{
            "title": "观测·%s" % (o.code or "未命名"),
            "brief": scrub_identifiers("%s%s%s" % (o.value, o.unit, o.note))[:200],
            "tags": [o.code] if o.code else [],
            "salience": 3,
        } for o in self.observations if o.code]

    def to_dict(self) -> Dict[str, Any]:
        return asdict(self)


def allergy_memory(allergen: str, reaction: str = "") -> Dict[str, Any]:
    """过敏史记忆 —— **salience=5**。

    过敏史是"绝不能被闲聊挤掉"的典型：一旦被挤出上下文，再次处方时可能致命。
    PASM 的高 salience 记忆不会被普通对话驱逐，这里正是它的用武之地。
    """
    return {
        "title": "过敏史·%s" % allergen,
        "brief": scrub_identifiers(reaction)[:200],
        "tags": [allergen, "过敏", "禁忌"],
        "salience": 5,
    }


def critical_memory(what: str, detail: str = "") -> Dict[str, Any]:
    """危急事件 / 重要既往史 —— 同样 salience=5。"""
    return {
        "title": "重要·%s" % what,
        "brief": scrub_identifiers(detail)[:200],
        "tags": [what, "重要"],
        "salience": 5,
    }


# ============================================================ 自检

def selftest() -> bool:
    import shutil
    import tempfile

    ok = fail = 0

    def check(name: str, cond: bool, detail: str = "") -> None:
        nonlocal ok, fail
        if cond:
            ok += 1
            print("  v %s" % name)
        else:
            fail += 1
            print("  x %s%s" % (name, ("  <- " + detail) if detail else ""))

    print("pasm_medical.domain 自检")
    print("-" * 60)

    # agent_id：不同患者必须不同（这是隔离的前提）
    a = agent_id_for("h1", pseudonymize("patient-001"))
    b = agent_id_for("h1", pseudonymize("patient-002"))
    check("不同患者的 agent_id 不同（隔离前提）", a != b, "%s vs %s" % (a, b))
    check("同一患者两次调用 agent_id 稳定", a == agent_id_for("h1", pseudonymize("patient-001")))
    check("agent_id 不含路径分隔符（防穿越）",
          "/" not in a and "\\" not in a and ".." not in a, a)
    try:
        agent_id_for("h1", "")
        check("空 patient_ref 抛 ValueError", False, "没抛")
    except ValueError:
        check("空 patient_ref 抛 ValueError", True)
    # 反例：中文姓名进不了目录名
    check("中文患者标识被安全化", "/" not in agent_id_for("科室一", "张三") ,
          agent_id_for("科室一", "张三"))

    # 假名化确定性
    check("假名化是确定性的（同输入同输出）",
          pseudonymize("p1") == pseudonymize("p1"))
    check("假名化区分不同输入", pseudonymize("p1") != pseudonymize("p2"))

    # 去标识
    s = scrub_identifiers("患者张三，手机 13812345678，身份证 11010119900307123X")
    check("手机号被去标识", "13812345678" not in s, s)
    check("身份证被去标识", "11010119900307123X" not in s, s)

    # Encounter → 记忆
    enc = Encounter(encounter_id="e1", patient_ref="p1", department="心内科",
                    chief_complaint="胸痛 2 小时",
                    observations=[Observation(code="血压", value="152/94", unit="mmHg")],
                    assessment="高血压 3 级", plan="随访")
    m = enc.to_memory()
    check("Encounter 可转记忆", m["title"].startswith("就诊·") and m["brief"], str(m))
    check("Encounter 记忆 salience=4（不占用 5）", m["salience"] == 4, str(m))
    check("观测可单独成记忆", len(enc.to_observation_memories()) == 1)
    check("过敏史 salience=5（关键事实）", allergy_memory("青霉素")["salience"] == 5)
    check("危急事件 salience=5", critical_memory("心梗史")["salience"] == 5)

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
