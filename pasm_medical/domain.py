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
#:
#: ★★ 2026-11-20 修真 bug：原用 ``\b`` 做边界，但 Python 的 ``\b`` 把中文也算 ``\w``，
#: 于是「我的手机号是13812345678，…」里手机号前面是中文"是" → ``\b`` 不成立 →
#: **中文句子里的手机号整段漏网**。改用「前后不是数字」的断言：既能在中文语境命中，
#: 又不会把长数字串（如 18 位身份证）从中间截断误匹配。

#: 脱敏占位符。**所有替换都产出它**，且它本身**不得命中任何一条规则** ——
#: 否则 L4 硬闸（出网前再扫一遍）会把"已脱敏文本"误判成"残留标识"，
#: 云端 provider 将**永久拒发**所有请求。这条约束由 domain 自检的幂等断言守住。
REDACTION_PLACEHOLDER = "[已去标识]"

#: 裸值型规则：(正则, 替换模板) —— 命中即**整段**替换成占位符。
#:
#: ★★ 顺序有意义：**从长到短**排。否则短规则可能先吃掉长串的前缀、留下尾巴：
#:    15 位规则先跑会把 18 位身份证截成 "[已去标识]23X"，而残留的 "23X" 反而
#:    脱敏不掉了（它已经不是 18 位，contains_identifiers 再扫也检不出）。
#:    这种"部分残留"是脱敏里最隐蔽的失败方式 —— 表面看已脱敏，实际漏了。
_BARE_RULES = (
    (re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)"), REDACTION_PLACEHOLDER),  # 身份证 18 位
    (re.compile(r"(?<!\d)\d{15}(?!\d)"), REDACTION_PLACEHOLDER),        # 身份证 15 位
    (re.compile(r"(?<!\d)1[3-9]\d{9}(?!\d)"), REDACTION_PLACEHOLDER),  # 手机号
    # 座机：有横杠形态明确，无横杠形态（075588888888）也可能撞上长编号 —— 宁可多脱
    (re.compile(r"(?<![\d\-])0\d{2,3}-\d{7,8}(?![\d\-])"), REDACTION_PLACEHOLDER),
    (re.compile(r"(?<!\d)0\d{9,11}(?!\d)"), REDACTION_PLACEHOLDER),
    (re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}"),
     REDACTION_PLACEHOLDER),                                           # 邮箱
)

#: 标签锚定型：**只替换「标签：值」里的值，标签保留**。
#: 理由：模型仍能看出"这里本来有个姓名字段"，但拿不到具体值 —— 比整段消失更有用。
#:
#: 为什么用「标签锚定」而不是直接匹配裸中文姓名：中文姓名与普通词汇无法用正则区分
#: （"李医生"/"当归"都可能是词），裸匹配必然误伤临床内容。带标签的形态才是
#: 表单里真正会出现的、且能判定为标识的形态。
_LABEL_TAGS = (
    "紧急联系人姓名|监护人姓名|联系人姓名|紧急联系人|患者姓名|病人姓名|姓名|"
    "身份证件号码|身份证号|证件号码|证件号|身份证|"
    "病历编号|病历号|就诊卡号|门诊号|住院号|就诊号|挂号号|医保号|社保卡号|"
    "家庭住址|通讯地址|现住址|住址|地址|"
    "联系电话|联系方式|手机号码|手机号|电话|"
    "出生日期|出生年月|生日"
)

#: 标签后面的值：到分隔符/换行为止，**排除方括号** ——
#: 占位符以 "[" 开头，天然不会被自己二次匹配（这是幂等的关键）。
#:
#: ★★ 这里**不能设长度上限**（2026-11-20 复盘抓到的真缺陷）：
#:    原写作 ``{1,200}``，于是「住址：」后面超过 200 字符的值**只脱敏前 200 字**，
#:    尾巴原样残留出网 —— 而且残留已不是"标签：值"形态，
#:    ``contains_identifiers`` 检不出、L4 硬闸也拦不住，**静默泄漏**。
#:    这跟裸值型"短规则吃掉长串前缀留尾巴"是同一类失败：
#:    表面看已脱敏，实际漏了。
#:    值的长度本就由**分隔符**界定（遇到 ，。；、换行、空格即止），不需要额外上限；
#:    去掉上限后超长值被整段脱敏，边界反而更干净。
_LABEL_VALUE = r"[^\n，,。；;、 \[\]]+"

#: (正则, 替换模板)。``\1\2`` 保留「标签 + 分隔符」，只把值抹成占位符。
_LABELLED_RULES = (
    (re.compile(r"(%s)(\s*[：:]\s*)(%s)" % (_LABEL_TAGS, _LABEL_VALUE)),
     r"\1\2" + REDACTION_PLACEHOLDER),
)

#: 全部敏感规则（供 contains_identifiers 逐条扫描）。**单一来源**，
#: 不要在别处再抄一份 —— 两处各写一遍、改一处就会静默放行（本项目踩过同类问题）。
_SENSITIVE_PATTERNS = tuple(pat for pat, _ in _LABELLED_RULES + _BARE_RULES)

#: 只匹配标签名（不要求冒号与值）—— 用于判断"这个**字段名**本身是不是标识字段"。
_FIELD_LABEL_RE = re.compile(r"(%s)" % _LABEL_TAGS)


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
    """粗粒度去标识：把手机号 / 身份证 / 姓名 / 地址等替换成占位符。

    这是**兜底**，不是合规方案 —— 真正的去标识化在数据出业务库之前就该做完
    （见 PLAN.md §1.3）。放这一层是为了"就算上游漏了，认知层也不留痕"。

    ★★ 两类规则，替换方式不同（2026-11-20 G2-L2 扩展）：

    1. **裸值型**（手机号 / 身份证 / 座机 / 邮箱）→ 整段替换成 ``[已去标识]``。
       规则按**长到短**排列，否则短规则会先吃掉长串的前缀、留下尾巴
       （15 位规则先跑会把 18 位身份证截成 ``[已去标识]23X``，尾巴反而漏了）。
    2. **标签锚定型**（姓名：/ 住址：/ 病历号：…）→ **只替换值、保留标签**，
       这样模型仍看得出"这里本来有个姓名字段"，只是拿不到值。

    ★★ 幂等性是硬约束：``scrub(scrub(x)) == scrub(x)``，且脱敏后的文本
    **不得再命中任何规则**。否则出网侧的 L4 硬闸会把"已脱敏文本"误判成
    "残留标识"，云端 provider 将永久拒发所有请求。
    占位符以 "[" 开头且标签型的值部分排除方括号 —— 这是幂等的实现基础。
    """
    out = text or ""
    for pat, repl in _LABELLED_RULES:
        out = pat.sub(repl, out)
    for pat, repl in _BARE_RULES:
        out = pat.sub(repl, out)
    return out


def contains_identifiers(text: str) -> bool:
    """文本里**是否仍含**直接标识（手机号 / 身份证 / 座机 / 邮箱 / 标识字段值）。

    与 :func:`scrub_identifiers` 共用同一组规则（单一来源），用于出网侧的
    L4 硬闸：脱敏之后再扫一遍，若仍命中说明脱敏这一步被绕过或改坏，fail closed 拒发。

    ⚠️ 诚实边界：它只能发现规则覆盖得到的形态。**中文姓名若不带标签
    （如"张三说头痛"）依然检不出** —— 中文姓名与普通词汇无法用正则区分，
    裸匹配必然误伤临床内容。要真正覆盖需要专业 NER 或"数据入库前结构化"。
    """
    out = text or ""
    return any(pat.search(out) for pat in _SENSITIVE_PATTERNS)


def is_identifier_field(name: str) -> bool:
    """这个**字段名**本身是不是标识字段（姓名 / 电话 / 地址 / 病历号…）。

    给 L3 出网字段白名单用：即使某字段的值不含可识别标识（如"姓名"值为空），
    只要字段名是标识字段，就**不整条出网** —— 数据最小化优先于"看起来没泄漏"。
    """
    return bool(_FIELD_LABEL_RE.search(name or ""))


#: 疑似**身份声明**的锚点（"我叫…"/"我住在…"）。**只用于检测与告警，绝不用于改写文本。**
#:
#: ★★ 为什么只检测、不改写（2026-11-20 实测数据）
#: --------------------------------------------------
#: 「我叫不上来哪里疼」是患者描述症状的极常见说法。若按"自称前缀 + 值"去改写，
#: 会变成「我叫[姓名]里疼」——**把临床症状描述破坏掉**。
#: 实测 6 条反例里 4 条被误伤（"我叫不上来"、"疼得我叫唤"、"我住在医院附近方便复查"）。
#:
#: 医疗场景里，**误伤临床语义比漏掉一个地址更危险**：泄漏是合规风险，
#: 而破坏症状描述直接影响诊疗判断。所以这条路只做"看见"，不做"抹掉"。
_IDENTITY_DECLARATIONS = (
    ("name", re.compile(r"(我叫作|我叫|本人叫|我的名字是|患者叫|病人叫)")),
    ("address", re.compile(r"(我住在|我家住在|我家在|本人住|现居住于|本人现住)")),
    ("contact", re.compile(r"(我的电话是|我的手机号是|我的联系方式是|电话联系我)")),
)


def detect_identity_declaration(text: str) -> List[Dict[str, Any]]:
    """检测自由文本里的**疑似**身份声明，返回 ``[{"kind":..., "count":...}]``。

    ★★ 两条刻意的设计约束：

    1. **不返回命中的原文片段**。审计/告警若存了原文，审计表本身就变成 PII 库 ——
       等于把泄漏从"出网"搬到"落库"。只记**类型 + 次数**足够驱动提示与统计，
       且不新增任何敏感数据。
    2. **不修改文本**。本函数是纯检测，调用方拿它做提示/留痕，
       **绝不能**拿它去替换内容（原因见 ``_IDENTITY_DECLARATIONS`` 的实测注释）。

    ⚠️ 诚实边界：这是**启发式**，必然有误报（"我叫不上来"会命中 name），
    也必然有漏报（"张三，男，45岁"没有自称前缀）。
    但它不承担拦截职责 —— 它的作用是让"患者自报身份"这件事**从静默变可见**，
    从而驱动产品侧引导（"无需填写姓名/住址"）与后续评估是否要上 NER。
    """
    out = text or ""
    hits: List[Dict[str, Any]] = []
    for kind, pat in _IDENTITY_DECLARATIONS:
        n = len(pat.findall(out))
        if n:
            hits.append({"kind": kind, "count": n})
    return hits


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
    # ★★ 回归：中文句子里的标识（原 \b 边界漏网 bug，2026-11-20）
    zh = scrub_identifiers("我的手机号是13812345678，证件11010119900307123X，请看病")
    check("★ 中文语境手机号被去标识（\\b 漏网 bug 回归）", "13812345678" not in zh, zh)
    check("★ 中文语境身份证被去标识（\\b 漏网 bug 回归）", "11010119900307123X" not in zh, zh)
    # contains_identifiers：L4 硬闸的判据，必须正反都灵
    check("contains_identifiers 检出手机号/身份证",
          contains_identifiers("联系 13812345678") and
          contains_identifiers("证件 11010119900307123X"))
    check("contains_identifiers 对脱敏后文本返回 False（不误报）",
          not contains_identifiers(scrub_identifiers("手机 13812345678")))
    check("contains_identifiers 对普通病史不误报",
          not contains_identifiers("主诉胸痛，血压152/94，无药物过敏"))

    # ---- ★★ G2-L2 扩展识别模式（2026-11-20）。标签锚定型：只换值、保留标签。
    lab = scrub_identifiers("姓名：张三，男，45岁")
    check("★ L2 姓名标签：值被去标识", "张三" not in lab, lab)
    check("★ L2 姓名标签：标签保留（模型仍看得出字段存在）",
          "姓名：" in lab, lab)
    addr = scrub_identifiers("住址：广东省深圳市南山区科技园路1号")
    check("★ L2 地址标签：值被去标识", "南山区" not in addr, addr)
    mrn = scrub_identifiers("病历号：MR20240315001")
    check("★ L2 病历号标签：值被去标识", "MR20240315001" not in mrn, mrn)
    tel = scrub_identifiers("联系电话：0755-88888888")
    check("★ L2 座机（带横杠）被去标识", "0755-88888888" not in tel, tel)
    mail = scrub_identifiers("邮箱 zhangsan@example.com")
    check("★ L2 邮箱被去标识", "zhangsan@example.com" not in mail, mail)

    # ★★★ 回归：标签型值**超长时不得留尾巴**（2026-11-20 复盘抓到的真缺陷）。
    #   原写作 ``{1,200}`` → 超长值只脱敏前 200 字，尾巴原样出网；
    #   且残留已不是"标签：值"形态 → contains_identifiers 检不出、L4 硬闸拦不住，
    #   **静默泄漏**（跟裸值型"短规则吃长串前缀留尾巴"是同一类失败）。
    _long_val = "广东省深圳市南山区阳光工业区1号" + "A" * 260
    _long_scrub = scrub_identifiers("住址：" + _long_val)
    check("★★ L2 超长值整段脱敏（不留尾巴）",
          _long_val[200:230] not in _long_scrub, _long_scrub[:60])
    check("★★ L2 超长值脱敏后无残留（硬闸能兜住）",
          not contains_identifiers(_long_scrub), _long_scrub[:60])
    # 负对照：证明上面这条断言非恒绿 —— 若把上限改回 {1,200}，尾巴必然回来
    _cut = re.sub(r"住址：[^，,。；;、]{1,200}", "住址：" + _long_val[:200],
                  "住址：" + _long_val)
    check("★★ 负对照：截断到 200 字必然留尾巴（断言非恒绿）",
          _long_val[200:230] in _cut)

    # 负例：临床内容不得被误伤（这是脱敏能不能上线的底线）
    for safe in ("主诉：胸痛，血压152/94，无药物过敏",
                 "既往史：青霉素过敏，2019年行阑尾切除术",
                 "现病史：患者自述胸闷2天，无放射痛",
                 "出生年月不详，发育正常",
                 "医嘱：低盐低脂饮食，规律作息，电话随访"):
        check("★ L2 临床文本零误伤：%s" % safe[:14],
              scrub_identifiers(safe) == safe, scrub_identifiers(safe))

    # ---- ★★ 幂等性是硬约束：scrub(scrub(x)) == scrub(x)，且脱敏后不再命中任何规则。
    #      若不成立 → L4 硬闸会把"已脱敏文本"误判成残留标识 → 云端 provider 永久拒发。
    _idem_cases = [
        "患者张三，手机 13812345678，身份证 11010119900307123X",
        "我的手机号是13812345678，证件11010119900307123X，请看病",
        "姓名：张三，男，45岁",
        "住址：广东省深圳市南山区科技园路1号",
        "病历号：MR20240315001，联系电话：0755-88888888",
        "出生日期：1980-05-12，邮箱 zhangsan@example.com",
        "紧急联系人：李四，电话13812345678",
        "主诉：胸痛，血压152/94，无药物过敏",
    ]
    _idem_bad = [c for c in _idem_cases
                 if scrub_identifiers(scrub_identifiers(c)) != scrub_identifiers(c)]
    check("★★ 脱敏幂等（scrub∘scrub == scrub，全部用例）",
          not _idem_bad, str(_idem_bad[:1]))
    _res_bad = [c for c in _idem_cases
                if contains_identifiers(scrub_identifiers(c))]
    check("★★ 脱敏后不再命中任何规则（L4 硬闸不会误拒发）",
          not _res_bad, str(_res_bad[:1]))

    # ---- G2-L3：字段名白名单判定（即使值为空，标识类字段也不该出网）
    check("★ L3 标识字段名被识别（姓名/电话/住址/病历号）",
          all(is_identifier_field(x) for x in ("姓名", "联系电话", "住址", "病历号")))
    check("★ L3 临床字段名不被误判（主诉/过敏史/用药史）",
          not any(is_identifier_field(x) for x in ("主诉", "过敏史", "用药史", "既往史")))

    # ---- ★★ 疑似身份声明检测（2026-11-20）：只检测、不改写。
    #      它的职责是让"患者自报身份"从静默变可见，从而驱动产品侧引导。
    _hit_name = detect_identity_declaration("我叫张三，最近胸闷")
    check("★ 检测：'我叫X' 被识别为姓名声明",
          any(h["kind"] == "name" for h in _hit_name), str(_hit_name))
    _hit_addr = detect_identity_declaration("我住在广东省深圳市南山区科技园1号")
    check("★ 检测：'我住在X' 被识别为地址声明",
          any(h["kind"] == "address" for h in _hit_addr), str(_hit_addr))
    check("★ 检测：普通提问不产生告警（不误报临床问题）",
          detect_identity_declaration("发烧三天了怎么办") == [])
    check("★ 检测：临床描述不被误报（主诉/现病史）",
          detect_identity_declaration("主诉：胸闷气短，活动后加重") == [])
    # ★★★ 最关键的一条：检测结果**不得包含原文** ——
    #     否则审计表本身就成了 PII 库，等于把泄漏从"出网"搬到"落库"。
    _leak = detect_identity_declaration("我叫张三，住址是阳光工业区1号，电话13812345678")
    check("★★ 检测结果不含原文（审计不会变成 PII 库）",
          "张三" not in str(_leak) and "阳光工业区" not in str(_leak)
          and "13812345678" not in str(_leak), str(_leak))
    check("★ 检测：多条声明分别计数",
          {h["kind"] for h in detect_identity_declaration(
              "我叫张三，我住在南山区，我的电话是13812345678")} ==
          {"name", "address", "contact"})
    # 诚实边界：它**会**误命中（"我叫不上来"）——这正是只做检测、不做改写的根本原因
    check("★ 已知误报被如实暴露（'我叫不上来'命中 name → 只告警不改写）",
          any(h["kind"] == "name" for h in detect_identity_declaration("我叫不上来哪里疼")))

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
