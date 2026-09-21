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

★★ LLM 在本项目里的**三个正当位置**（其余一律不许）
--------------------------------------------------
  ① 理解患者口语 → 映射到问诊树节点（失败则回退到规则分词，见 consult.classify）
  ② 把**规则决定的**问题转成自然话术（谁问什么由问诊树决定，不是模型决定）
  ③ 把结构化事实组织成《预问诊摘要》的文字

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
           "compose_summary_text"]

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

    print("-" * 60)
    print("结果：%d 项通过，%d 项失败" % (ok, fail))
    return fail == 0


if __name__ == "__main__":                                  # pragma: no cover
    import sys
    sys.exit(0 if selftest() else 1)
