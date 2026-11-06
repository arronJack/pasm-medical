# -*- coding: utf-8 -*-
"""LLM 网关 —— 本地模型或云端 API，可插拔，且**无 LLM 也能跑**。

为什么必须可插拔
----------------
小志定的第 4 条：**LLM 可以用本地，也可以用 API**。互联网产品还有第三种现实：
**某些时候一个都用不了**（内网限制/欠费/超时）。所以设计成三档：

  1. ``ollama``   本地模型（内网、数据不出院、断网可用）
  2. ``openai``   任意 OpenAI 兼容 API（云端）
  3. ``null``     无 LLM —— 走**确定性模板**，系统功能完整但话术朴素

★ 第 3 档不是"凑合"，是**产品要求**：医疗系统在模型不可用时
**必须降级而不是报错**。患者问不出话，比话术生硬糟糕得多。

★★ LLM 在本项目里的**四个正当位置**（其余一律不许）
--------------------------------------------------
  ① 理解患者口语 → 映射到问诊树节点（失败则回退到规则分词，见 consult.classify）
  ② 把**规则决定的**问题转成自然话术（谁问什么由问诊树决定，不是模型决定）
  ③ 把结构化事实组织成《预问诊摘要》的文字
  ④ 把**已通过相关性闸门的资料原文**整理成回答文字（见 compose_answer_text）

④ 是 2026-11-06 新增的，加它之前先看三条约束（缺一条就不要加）：
  · **不是"检索"**：检索与"能不能答"由 service.ask 的闸门决定，模型拿到的
    只有已经判定够格的那几条资料；模型**没有机会**决定"要不要答"。
  · **不是"新增医学内容"**：prompt 明确只许改写给定原文，且输出必须带一行
    可核验的「依据：<资料标题>」；**引用到不存在/未命中的标题就整段作废**，
    回退到确定性原文摘录（`fallback_answer`）。这条是代码判据，不是提示词自觉。
  · **不是"必需"**：无 LLM / 模型报错 / 输出跑偏，一律回退到原文摘录 ——
    回答**永远非空**且**永远只含资料里出现过的话**。

**绝对禁止**：让 LLM 决定"该问什么"、生成诊断结论、解读影像、判读检验数值。
判读是规则（``safety`` / ``lab``），方向是问诊树（``consult``），
影像是归档（见 FEASIBILITY.md §4）。

零第三方依赖：只用标准库 ``urllib`` —— 与本项目其余部分保持一致，
也避免为了一个 HTTP 请求把 requests/httpx 拖进生产依赖。
"""
from __future__ import annotations

import json
import os
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Dict, List, Optional

__all__ = ["LLMConfig", "LLMProvider", "NullProvider", "OllamaProvider",
           "OpenAICompatProvider", "build_provider", "phrase_question",
           "compose_summary_text", "compose_answer", "compose_answer_text",
           "fallback_answer"]

#: 系统提示词里必须钉死的边界。**改这段之前先想清楚合规后果。**
SYSTEM_GUARD = (
    "你是一个医疗预问诊系统的语言模块。你只能：\n"
    "1) 用通俗、简短、口语化的中文把给定的问题转述给患者；\n"
    "2) 把给定的结构化病史整理成通顺的摘要文字。\n"
    "严禁：给出任何诊断、病名、用药建议、剂量，或对检查结果下结论。\n"
    "严禁：编造患者没有提供的信息。信息缺失就写“未提供”。\n"
    "你只是把既定内容说顺，不要增加医学判断。"
)


@dataclass
class LLMConfig:
    """LLM 配置。对应后台「大模型设置」页面。"""

    provider: str = "null"                  # ollama / openai / null
    model: str = ""
    base_url: str = ""                      # ollama: http://127.0.0.1:11434
    api_key: str = ""                       # openai 兼容
    timeout: int = 30
    temperature: float = 0.2                # 医疗场景要低温度，减少发挥

    @classmethod
    def from_env(cls) -> "LLMConfig":
        return cls(
            provider=os.environ.get("PASM_MEDICAL_LLM", "null").lower(),
            model=os.environ.get("PASM_MEDICAL_LLM_MODEL", ""),
            base_url=os.environ.get("PASM_MEDICAL_LLM_BASE_URL", ""),
            api_key=os.environ.get("PASM_MEDICAL_LLM_API_KEY", ""),
            timeout=int(os.environ.get("PASM_MEDICAL_LLM_TIMEOUT", "30")),
        )

    def to_dict(self) -> Dict[str, Any]:
        """给后台展示用 —— **api_key 必须打码**，别把密钥回显到页面。"""
        return {"provider": self.provider, "model": self.model,
                "base_url": self.base_url,
                "api_key": ("*" * 8 + self.api_key[-4:]) if self.api_key else "",
                "timeout": self.timeout, "temperature": self.temperature}


class LLMProvider:
    """所有后端实现的接口。``complete`` 失败**必须抛异常**，由调用方降级。"""

    name = "base"

    def available(self) -> bool:
        return False

    def complete(self, prompt: str, *, system: str = SYSTEM_GUARD,
                 max_tokens: int = 400) -> str:
        raise NotImplementedError


class NullProvider(LLMProvider):
    """无 LLM —— 不报错，返回空串，由调用方走确定性模板。

    刻意**不**假装能生成：返回空串，让 :func:`phrase_question` 用模板兜底。
    """

    name = "null"

    def available(self) -> bool:
        return False

    def complete(self, prompt: str, *, system: str = SYSTEM_GUARD,
                 max_tokens: int = 400) -> str:
        return ""


class OllamaProvider(LLMProvider):
    """本地 Ollama（内网、数据不出院、断网可用）。"""

    name = "ollama"

    def __init__(self, cfg: LLMConfig) -> None:
        self.cfg = cfg
        self.url = (cfg.base_url or "http://127.0.0.1:11434").rstrip("/")
        self.model = cfg.model or "qwen2.5:7b"

    def available(self) -> bool:
        try:
            req = urllib.request.Request(self.url + "/api/tags", method="GET")
            with urllib.request.urlopen(req, timeout=3) as r:
                return r.status == 200
        except Exception:
            return False

    def complete(self, prompt: str, *, system: str = SYSTEM_GUARD,
                 max_tokens: int = 400) -> str:
        body = json.dumps({
            "model": self.model, "prompt": prompt, "system": system,
            "stream": False,
            "options": {"temperature": self.cfg.temperature,
                        "num_predict": max_tokens},
        }).encode("utf-8")
        req = urllib.request.Request(
            self.url + "/api/generate", data=body,
            headers={"Content-Type": "application/json"}, method="POST")
        with urllib.request.urlopen(req, timeout=self.cfg.timeout) as r:
            data = json.loads(r.read().decode("utf-8") or "{}")
        return str(data.get("response") or "").strip()


class OpenAICompatProvider(LLMProvider):
    """任意 OpenAI 兼容 API（DeepSeek / 通义 / vLLM / one-api 网关…）。

    ⚠️ 互联网产品用云端 API 时：**患者数据出网前必须去标识化**
    （见 ``domain.scrub_identifiers``）。这条不是建议，是合规要求。
    """

    name = "openai"

    def __init__(self, cfg: LLMConfig) -> None:
        self.cfg = cfg
        self.url = (cfg.base_url or "https://api.openai.com/v1").rstrip("/")
        self.model = cfg.model or "gpt-4o-mini"

    def available(self) -> bool:
        return bool(self.cfg.api_key and self.url)

    def complete(self, prompt: str, *, system: str = SYSTEM_GUARD,
                 max_tokens: int = 400) -> str:
        body = json.dumps({
            "model": self.model,
            "messages": [{"role": "system", "content": system},
                         {"role": "user", "content": prompt}],
            "temperature": self.cfg.temperature,
            "max_tokens": max_tokens,
        }).encode("utf-8")
        req = urllib.request.Request(
            self.url + "/chat/completions", data=body, method="POST",
            headers={"Content-Type": "application/json",
                     "Authorization": "Bearer " + self.cfg.api_key})
        with urllib.request.urlopen(req, timeout=self.cfg.timeout) as r:
            data = json.loads(r.read().decode("utf-8") or "{}")
        try:
            return str(data["choices"][0]["message"]["content"]).strip()
        except (KeyError, IndexError, TypeError) as ex:
            raise RuntimeError("LLM 返回结构异常：%s" % ex) from ex


def build_provider(cfg: Optional[LLMConfig] = None) -> LLMProvider:
    """按配置构造后端。**未知 provider 一律退回 NullProvider**（不抛错）。"""
    cfg = cfg or LLMConfig.from_env()
    p = (cfg.provider or "null").lower()
    if p == "ollama":
        return OllamaProvider(cfg)
    if p in ("openai", "api", "deepseek", "azure"):
        return OpenAICompatProvider(cfg)
    return NullProvider()


# ---------------------------------------------------------------- 用途 ② / ③

def phrase_question(provider: LLMProvider, question_text: str,
                    facts: Dict[str, Any], *, fallback_prefix: str = "") -> str:
    """把**问诊树决定的问题**转成自然话术。

    ★ 注意方向：**问题本身来自规则**（``consult``），这里只是"怎么说"。
    模型失败了就原样返回 —— 生硬但正确，永远不因为模型抖动改变问诊内容。
    """
    if not provider.available():
        return (fallback_prefix + question_text).strip()
    known = "；".join("%s：%s" % (k, v) for k, v in list(facts.items())[:6]) or "无"
    prompt = ("患者已提供的信息：%s\n\n"
              "请把下面这个问题用更口语、更简短的问法问出来，"
              "只输出这一句问话，不要任何解释或额外内容：\n%s"
              % (known, question_text))
    try:
        got = provider.complete(prompt, max_tokens=120)
    except Exception:
        got = ""
    got = (got or "").strip().strip('"').splitlines()[0].strip() if got else ""
    # 长度异常（模型跑偏）就不用它 —— 宁可生硬
    if not got or len(got) > 200:
        return (fallback_prefix + question_text).strip()
    return got


def compose_summary_text(provider: LLMProvider,
                         summary: Dict[str, Any]) -> str:
    """把结构化摘要组织成通顺文字。**内容完全来自 summary**，模型只做措辞。"""
    chief = summary.get("chief_complaint") or "未明确"
    pi = summary.get("present_illness") or {}
    lines = ["主诉：%s" % chief]
    for k, v in pi.items():
        lines.append("%s：%s" % (k, v))
    if summary.get("missing"):
        lines.append("未采集到：" + "、".join(summary["missing"]))
    rf = summary.get("red_flags") or []
    if rf:
        lines.append("警示信号：" + "；".join(r["label"] for r in rf))
    text = "\n".join(lines)

    if not provider.available():
        return text                       # 无 LLM：直接给结构化文本，照样能用
    prompt = ("请把下面的结构化病史整理成一段通顺的中文摘要，"
              "不得新增任何未提供的信息，不得给出诊断：\n\n" + text)
    try:
        got = provider.complete(prompt, max_tokens=400)
    except Exception:
        got = ""
    return (got or "").strip() or text


#: 「依据资料作答」的系统提示词。与 SYSTEM_GUARD 分开：那边是"转述问题/写摘要"，
#: 这边允许改写**已给定的资料原文**，但同样不许新增任何医学判断。
ANSWER_SYSTEM = (
    "你是医疗机构的资料整理助手。你的唯一任务：把【已检索到的资料原文】整理成"
    "通顺、易懂的中文回答。\n"
    "硬性规则（违反任何一条即视为回答无效）：\n"
    "1) 只能使用资料原文中出现过的信息；不得补充资料之外的知识、经验或推测。\n"
    "2) 不得给出诊断、病名判断、处方、或调整药物剂量的建议；"
    "资料里若有具体用药说明，只能原样转述并注明“以医师/药师意见为准”。\n"
    "3) 资料没有提到的内容，明确写“资料未提及”，不要猜。\n"
    "4) 最后必须单独一行写：依据：<资料标题1>；<资料标题2>"
    "（只许写上面列出的资料标题，不得编造标题）。\n"
    "5) 不要写任何寒暄、不要写“作为 AI”。"
)

#: 引用行前缀。写成常量是为了「生成」与「核验」用同一份定义 ——
#: 两处各写一遍，改一处就会静默放行（本项目踩过同类问题）。
_CITE_PREFIXES = ("依据：", "依据:", "来源：", "来源:")


def _norm_cite(text: str) -> str:
    """比较标题用的归一化：去掉空白与常见标点，只留可比较的字符。"""
    drop = set(" \t\r\n　·、，,。.；;：:（）()【】[]《》<>\"'“”‘’-—_/\\|")
    return "".join(ch for ch in (text or "") if ch not in drop).lower()


def fallback_answer(sources: Optional[List[Dict[str, Any]]] = None,
                    max_items: int = 3) -> str:
    """**确定性**回答：直接摘录已过闸门的资料原文。

    ★ 这是"无 LLM / 模型跑偏"时的**唯一兜底**，所以它本身必须满足：
      ① 永远不编造 —— 每一句都来自 ``sources[].brief`` 原文；
      ② 永远不为空（只要有来源）—— 空回答会让界面显示一个空气泡，
         患者以为"系统坏了"，比话术生硬糟糕得多。
    """
    lines: List[str] = []
    for s in (sources or [])[:max_items]:
        if not isinstance(s, dict):
            continue
        title = str(s.get("title") or "").strip()
        brief = str(s.get("brief") or "").strip()
        if not title and not brief:
            continue
        lines.append("· " + (("%s：" % title) if title else "") + brief)
    if not lines:
        return ""
    return "根据院内资料：\n" + "\n".join(lines) + "\n（以上为资料原文摘录，请以主管医师判断为准。）"


def _parse_citations(text: str) -> tuple:
    """拆出「依据：A；B」这一行 → (正文, [引用标题])。

    找不到引用行返回 ``(text, [])`` —— 调用方据此判定"未锚定"。
    """
    lines = [ln.strip() for ln in (text or "").splitlines()]
    for idx in range(len(lines) - 1, -1, -1):
        ln = lines[idx]
        for p in _CITE_PREFIXES:
            if ln.startswith(p):
                raw = ln[len(p):]
                cited = [x.strip() for x in raw.replace("；", ";").replace("、", ";")
                         .replace(",", ";").replace("，", ";").split(";") if x.strip()]
                body = "\n".join(lines[:idx]).strip()
                return body, cited
    return (text or "").strip(), []


def compose_answer(provider: LLMProvider, question: str,
                   sources: Optional[List[Dict[str, Any]]] = None,
                   *, max_chars: int = 600) -> Dict[str, str]:
    """同 :func:`compose_answer_text`，但额外回报**这段文字是谁产出的**。

    返回 ``{"text": ..., "source": ...}``，``source`` 取值：
      · ``"refused"``         没有依据 → 不该有正文（调用方本应拒答）
      · ``"fallback"``        确定性原文摘录（无 LLM / 模型失败 / 输出跑偏）
      · ``"llm:<model>"``     真模型生成，且已通过引用核验

    ★ 为什么要回报这个：审计表有 ``model_version`` 列，但以前一律写死成
      ``cognition-gated-ask`` —— 出了事查不出"这句话是模型说的还是模板拼的"。
      医疗系统里这两者的责任完全不同，必须可区分。
    """
    src = [s for s in (sources or []) if isinstance(s, dict)]
    if not src:
        return {"text": "", "source": "refused"}
    fallback = fallback_answer(src)

    if not provider.available():
        return {"text": fallback, "source": "fallback"}

    blocks: List[str] = []
    for i, s in enumerate(src, 1):
        title = str(s.get("title") or "").strip()
        brief = str(s.get("brief") or "").strip()
        kind = "机构资料" if s.get("kind") == "doc" else "患者既往记录"
        blocks.append("[%d]（%s）%s\n%s" % (i, kind, title or "（无标题）", brief or "（无正文）"))
    material = "\n\n".join(blocks)

    prompt = ("患者的问题：%s\n\n"
              "可用资料（**只有这些**）：\n%s\n\n"
              "请依据上述资料作答，并在最后一行列出你实际用到的资料标题。"
              % (question, material))
    try:
        got = provider.complete(prompt, system=ANSWER_SYSTEM, max_tokens=500)
    except Exception:
        got = ""
    got = (got or "").strip()
    if not got:
        return {"text": fallback, "source": "fallback"}

    body, cited = _parse_citations(got)
    if len(body) > max_chars:
        return {"text": fallback, "source": "fallback"}
    if not cited:
        return {"text": fallback, "source": "fallback"}   # 未标注依据 = 未锚定，不敢发出去

    known = [_norm_cite(str(s.get("title") or "")) for s in src]
    known = [k for k in known if k]
    for c in cited:
        nc = _norm_cite(c)
        if not nc:
            continue
        # 允许模型把标题写短/写长一点，但不允许指向一个不存在的标题
        if not any(k in nc or nc in k for k in known):
            return {"text": fallback, "source": "fallback"}
    if not body:
        return {"text": fallback, "source": "fallback"}
    model = getattr(provider, "model", "") or getattr(provider, "name", "") or "llm"
    return {"text": body, "source": "llm:%s" % model}


def compose_answer_text(provider: LLMProvider, question: str,
                        sources: Optional[List[Dict[str, Any]]] = None,
                        *, max_chars: int = 600) -> str:
    """把**已过闸门的资料**整理成回答文字（只要文字，不要来源标记）。

    ★ 方向很重要：**"能不能答、凭哪几条答"已经在调用方（service.ask 的闸门）定死**，
      本函数只负责"把这些资料说顺"。所以：

      · ``sources`` 为空 → 返回 ``""``（调用方本就应该拒答，不该走到这里）；
      · 无可用模型 → 回退 :func:`fallback_answer`（原文摘录，绝不空手而归）；
      · 模型报错 / 超长 / **引用到未命中的标题** → 同样回退。

    ★ 为什么用「引用可核验」而不是靠提示词自觉
    ------------------------------------------
    提示词说"不得编造"，但**没有判据**。这里要求模型在末行列出它用了哪几条资料，
    然后**逐条比对**：只要出现一个不在 ``sources`` 里的标题，就认定它在编造来源，
    整段作废、回退原文摘录。这是本项目一贯做法（判据要能证伪，不能只写在文档里）。

    ⚠️ 诚实边界：本函数能挡住"编造来源"，但**挡不住"引用正确、内容却说错了"**
       （语义蕴含不是字符串可判定的）。真正的最后防线仍是
       ``requires_physician_confirmation`` 与医师复核。
    """
    return compose_answer(provider, question, sources, max_chars=max_chars)["text"]


# ============================================================ 自检

class _FakeProvider(LLMProvider):
    """假的 LLM —— 自检**不发网络请求**，但仍走完整代码路径。"""

    name = "fake"

    def __init__(self, reply: str = "这是模型改写的问法。", boom: bool = False):
        self.reply = reply
        self.boom = boom
        self.calls = 0

    def available(self) -> bool:
        return True

    def complete(self, prompt: str, *, system: str = SYSTEM_GUARD,
                 max_tokens: int = 400) -> str:
        self.calls += 1
        if self.boom:
            raise RuntimeError("模拟模型不可用")
        return self.reply


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

    print("pasm_medical.llm 自检")
    print("-" * 60)

    # ---- 三档都能构造
    check("三档 provider 均可构造",
          all(build_provider(LLMConfig(provider=p)).name == p
              for p in ("null", "ollama", "openai")),
          str([build_provider(LLMConfig(provider=p)).name
               for p in ("null", "ollama", "openai")]))
    check("未知 provider 退回 null（不抛错）",
          build_provider(LLMConfig(provider="火星模型")).name == "null")

    # ---- ★ 无 LLM 时功能完整（这是产品要求，不是凑合）
    null = build_provider(LLMConfig(provider="null"))
    q = phrase_question(null, "有没有药物过敏？", {})
    check("★ 无 LLM 时问题原样返回（问诊不中断）", q == "有没有药物过敏？", q)
    sm = {"chief_complaint": "胸痛", "present_illness": {"性质": "压榨样"},
          "missing": ["过敏史"], "red_flags": []}
    t = compose_summary_text(null, sm)
    check("★ 无 LLM 时摘要仍有内容（结构化文本）",
          "主诉：胸痛" in t and "未采集到" in t, t)

    # ---- 有 LLM 时用它
    fake = _FakeProvider("有没有对什么药过敏呀？")
    q2 = phrase_question(fake, "有没有药物过敏？", {"主诉": "胸痛"})
    check("有 LLM 时采用模型话术", q2 == "有没有对什么药过敏呀？", q2)
    check("确实调用了模型", fake.calls == 1, str(fake.calls))

    # ---- ★ 模型跑偏 / 崩溃时必须回退，不能把错误话术发出去
    boom = _FakeProvider(boom=True)
    q3 = phrase_question(boom, "有没有药物过敏？", {})
    check("★ 模型抛异常时回退到原问题", q3 == "有没有药物过敏？", q3)
    long_reply = _FakeProvider("啊" * 500)
    q4 = phrase_question(long_reply, "有没有药物过敏？", {})
    check("★ 模型输出异常长时回退（避免把跑偏内容发给患者）",
          q4 == "有没有药物过敏？", q4[:60])
    t2 = compose_summary_text(boom, sm)
    check("★ 摘要生成失败时回退到结构化文本", "主诉：胸痛" in t2, t2[:60])

    # ---- 配置打码
    d = LLMConfig(provider="openai", api_key="sk-abcdef123456").to_dict()
    check("★ 后台展示时 api_key 打码（不回显密钥）",
          d["api_key"] and "sk-abcdef123456" not in d["api_key"], str(d))

    check("系统提示词钉死了三条禁止", "严禁" in SYSTEM_GUARD
          and "诊断" in SYSTEM_GUARD and "编造" in SYSTEM_GUARD)

    # ---- ★ 依据资料作答（2026-11-06 新增）。这一组是"能不能把话说出去"的判据，
    #      每一条都对应一种真实失败：空回答 / 编造来源 / 超长跑偏 / 拒答路径误调模型。
    src = [{"title": "成人发热的家庭处理与就医指征", "brief": "体温≥38.5℃可按说明书退热处理。",
            "kind": "doc"},
           {"title": "头痛的常见类型与就医时机", "brief": "突发最剧烈头痛须急诊。", "kind": "doc"}]

    check("★ 无来源时不生成任何文字（拒答路径不该有正文）",
          compose_answer_text(null, "发烧怎么办", []) == "")

    fb = compose_answer_text(null, "发烧怎么办", src)
    check("★ 无 LLM 时仍有正文（原文摘录，绝不空手）",
          bool(fb.strip()) and "体温≥38.5℃" in fb, fb[:80])
    check("★ 兜底正文只含资料原文与标题（不新增结论）",
          "成人发热的家庭处理与就医指征" in fallback_answer(src))

    ok_reply = _FakeProvider("发热时可先按说明书退热，出现呼吸困难须就医。\n"
                             "依据：成人发热的家庭处理与就医指征")
    got = compose_answer_text(ok_reply, "发烧怎么办", src)
    check("★ 模型回答带合法依据时被采用",
          "按说明书退热" in got and "依据" not in got, got[:80])

    # ★★ 这一条是整组里最重要的：模型引用了一个**不存在**的资料标题。
    #    靠提示词是挡不住的（模型会"看起来很自信"地编一个标题），必须由代码判退。
    lie = _FakeProvider("可服用布洛芬，每日三次。\n依据：中华医学会发热诊疗指南（2019）")
    got2 = compose_answer_text(lie, "发烧怎么办", src)
    check("★ 引用不存在的资料 → 整段作废、回退原文摘录",
          "中华医学会发热诊疗指南" not in got2 and "体温≥38.5℃" in got2, got2[:90])

    no_cite = _FakeProvider("多喝水、注意休息就好。")
    got3 = compose_answer_text(no_cite, "发烧怎么办", src)
    check("★ 没写依据（未锚定）→ 回退原文摘录",
          "多喝水" not in got3 and "体温≥38.5℃" in got3, got3[:90])

    huge = _FakeProvider("啊" * 900 + "\n依据：成人发热的家庭处理与就医指征")
    got4 = compose_answer_text(huge, "发烧怎么办", src)
    check("★ 输出超长（跑偏）→ 回退原文摘录",
          "啊" * 100 not in got4 and "体温≥38.5℃" in got4, got4[:60])

    long_ok = _FakeProvider("发热先物理降温。\n依据：头痛的常见类型与就医时机")
    got5 = compose_answer_text(long_ok, "发烧怎么办", src)
    check("★ 引用另一条**存在**的资料也算合法（不是只认第一条）",
          "物理降温" in got5, got5[:60])

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
