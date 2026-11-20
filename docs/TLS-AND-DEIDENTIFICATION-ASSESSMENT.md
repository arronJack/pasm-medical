# TLS 与去标识化出网 · 改造评估

> 版本：v0.2（G2 已实施 / G1 待部署） · 生成日期：2026-11-20 · 适用对象：`pasm-medical` 0.1.1
> 目标：补齐数据安全协议中的两个硬缺口 —— G1 传输明文、G2 出网未去标识。
>
> ✅ **G2 已于 2026-11-20 实施**（L1 模板方法单点脱敏 + L4 云端硬闸 + L5 风险回显，
> 并修复中文语境正则漏网 bug）；验证：llm 自检 25 项、domain 自检 19 项、
> 端到端反例对照捕获真实出站 body 无标识泄漏、全套回归 11/11 绿。
> ⚠️ **G1 暂缓**：尚未部署到服务器，方案保留待部署时执行。
> L2/L3 增强（姓名/地址 NER、字段白名单）未做，见 §G2 边界。

---

## G1 · 传输加密（TLS/HTTPS）

### 现状（代码可证）
- `application.yml:6-7`：`server.port: 8081`，无 `server.ssl.*` 任何配置。
- `docker-compose.yml`：无证书卷、无 443 映射，只有后端与 DB 端口。
- `ProductionGate.java` 自检项（:96-117）覆盖 demo 账号/JWT/学习开关/MLLP，**不含 TLS**。
- 结论：全链路明文 HTTP。院内若直连，患者健康数据在网络上裸奔。

### 三种方案对比（按推荐度排序）

| 方案 | 做法 | 改动量 | 优点 | 缺点 | 推荐 |
|---|---|---|---|---|---|
| **A. 反向代理终止 TLS** | Nginx/Caddy 前置，对外 443 HTTPS，内部仍 8081 HTTP | 小（配置级） | 不动 Java 代码；证书轮换方便；业内标准做法 | 需部署代理、代理到后端仍是内网明文 | ✅ **首选** |
| B. Spring Boot 内置 SSL | `application.yml` 加 `server.ssl.key-store*`，放 jks/p12 | 中 | 单进程直接 HTTPS | 证书进应用包、轮换需重启；MLLP 是独立 TCP 端口不受此保护 | 🟡 备选 |
| C. 服务网格 / 网关 | Istio/Linkerd 或院内 API 网关 | 大 | 统一 mTLS、可审计 | 对单体项目过重 | ❌ 暂不 |

### 推荐落地步骤（方案 A）
1. 生成/申请证书：内网 CA 签发的域名证书（生产）；自签证书仅测试，且需客户端信任。
2. Nginx 配置：`listen 443 ssl` + `ssl_certificate` / `ssl_certificate_key` + TLS 1.2/1.3 + 禁用弱套件；HTTP 80 → 443 重定向。
3. CORS 收紧：`SecurityConfig` 只允许 `https://<院内域名>` 来源，去掉任何 `*`。
4. HSTS：加 `Strict-Transport-Security` 响应头（确认域名全站 HTTPS 后再开）。
5. 健康检查 `/actuator/health`：仅内网可达，不暴露公网。
6. **同步补 ProductionGate 自检**：新增一项 `tls_enabled`，生产档检测到无 HTTPS 入口就给 BLOCK/WARN，避免"没做看起来像已验证"。
7. MLLP 端口（默认 2575）是独立 TCP，TLS 方案 A 不覆盖——若跨机传输检验数据，需院方专网/白名单（当前 `ProductionGate.java:112-117` 已对非回环绑定给 WARN）。

### 验收标准
- `curl -I https://<host>/actuator/health` 返回 200，HTTP 访问 301/302 到 HTTPS。
- `openssl s_client -connect <host>:443` 显示 TLS 1.2+、证书链有效。
- 浏览器无混合内容告警；CORS 跨域仅允许白名单域名。

### 工作量：小（配置级，1 天内可完成，不含证书申请等待）

---

## G2 · 去标识化出网

### 现状（代码可证）——这是真正的漏洞
> 📌 本节是 **2026-11-20 改动前的评估快照**，引用的旧行号用于说明"当时漏洞在哪"；
> 改动后的现状见本节末「实施记录」与文首状态框。

- `domain.py:65-74` 有 `scrub_identifiers()`（手机号/身份证占位符），但**只在记忆写入侧调用**（`service.py:290`）。
- LLM 出网的三条路径**都没有去标识**：
  - `llm.py:358` `compose_answer()`：把 `question`（患者原始问题）原样拼进 prompt。
  - `llm.py:217` `phrase_question()`：把 `known`（患者已提供信息）原样拼进 prompt。
  - `llm.py:249` `compose_summary_text()`：把结构化病史原文（含主诉、现病史）原样拼进 prompt。
- `llm.py:159` 注释写着"患者数据出网前**必须**去标识化……这条不是建议，是合规要求"，但代码没做——**文档/注释与实现不一致**。
- `OpenAICompatProvider`（云端 API，DeepSeek/通义等）正是走这些 `complete()` 调用（`llm.py:156-194`）。

### 风险
一旦启用云端 provider，患者原始问题、病史、可能含有的姓名/手机号/身份证会直接发给第三方 API。违反最小必要与个人信息保护要求。

### 改造方案（分层，按推荐度排序）

| 层 | 做法 | 改动点 | 说明 |
|---|---|---|---|
| **L1 出网前强制脱敏（必做）** | 在 `complete()` 的统一出口对所有 prompt 调 `scrub_identifiers()` | `llm.py` 的 `OllamaProvider.complete` / `OpenAICompatProvider.complete` | 单点拦截，三条路径全覆盖；不依赖调用方记得调 |
| L2 扩展识别模式 | 增加姓名、地址、病历号、生日等模式；或接专业 NER | `domain.py:32-36` `_SENSITIVE_PATTERNS` | 正则兜底有限，姓名/地址难穷举 |
| L3 结构化字段白名单 | 出网只发"必要字段"，而不是整段原文 | `compose_answer` / `compose_summary_text` 拼装处 | 数据最小化；配合 L1 |
| L4 云端开关硬闸 | 云端 provider 启用时，若出网文本仍命中敏感模式则拒绝发送并告警 | `OpenAICompatProvider.complete` | 双向保险；fail closed |
| L5 本地优先 | 默认 `PASM_MEDICAL_LLM=null` 或 `ollama`（本地），云端需显式开启且二次确认 | `llm.py:78` 配置默认 | 本地模型数据不出网 |

### 推荐落地步骤（L1 + L4 + L5 最小闭环）
1. **L1**：在 `LLMProvider` 抽象层加一个 `_sanitize(prompt)`，两个 `complete()` 实现在发请求前统一调用 `scrub_identifiers()`。这样无论哪条路径、哪个 provider，出网文本都过一遍脱敏。
2. **L4**：`OpenAICompatProvider.complete()` 里，对脱敏后文本再做一次敏感模式扫描，若仍命中（说明 L2 模式不全）→ 抛异常/拒发，不静默放行。
3. **L5**：确认默认 provider 为本地/null（`llm.py:78` 已是 `null` 默认✅）；云端启用需环境变量显式设置，并在 `llm_status()` 回显中高亮"出网风险：云端已启用"。
4. **补测试**：
   - 给定含手机号/身份证的问题，断言 `complete()` 收到的 prompt 里不含原始标识（可 monkeypatch provider 捕获）。
   - 负对照：故意关掉 L1，测试必须失败（证明断言非恒绿）。
   - 三条路径（compose_answer / phrase_question / compose_summary_text）各一条用例。
5. **同步注释**：`llm.py:159` 的"必须去标识化"从"合规要求（未做）"改为"已由 L1 统一拦截"，避免文档与实现继续不符。

### 验收标准
- 单测：三条出网路径 + 两种 provider，敏感标识 100% 被替换，无遗漏。
- 集成：启用云端 provider 打一次真实问答，抓包/日志确认出网 body 无原始标识。
- 负对照：移除 L1 拦截后，测试立即 FAIL（证明防护真实有效）。

### 工作量：中（L1+L4+L5+测试，约半天到 1 天）

### ✅ 实施记录（2026-11-20，已落地）
| 项 | 落地情况 |
|---|---|
| L1 出网强制脱敏 | **已做，且加固**：没按原方案"两个 complete 各自调 scrub"（漏一个就出事），改为**基类 `LLMProvider.complete()` 模板方法**——唯一出口先脱敏再交子类 `_send()`；4 个子类（Null/Ollama/OpenAI/_Fake）全部改为 `_send`，无法绕过。三条路径全覆盖。 |
| L4 云端失败闸 | **已做**：`OpenAICompatProvider._send()` 脱敏后再扫 `contains_identifiers()`，仍命中即 `RuntimeError` 拒发、零出站。 |
| L5 本地优先 + 风险提示 | **已做**：默认 `null`（`llm.py:78`）不变；`llm_status()` 新增 `egress.cloud` / `egress.notice`，云端启用时回显出网风险。 |
| 补测试 | **已做**：llm 自检 +9（三路径脱敏/绕过模板负对照/L4 硬闸/已脱敏不误拦），domain 自检 +5；独立端到端脚本 monkeypatch `urlopen` 捕获真实出站 body，逐条确认三条 prompt 无原始标识。 |
| 注释同步 | **已做**：`llm.py` 原 159 行"合规要求（未做）"注释改为"已由模板方法统一拦截 + L4 硬闸"。 |
| ★ 额外抓到的真 bug | 原 `scrub_identifiers` 正则用 `\b` 边界，中文句子里的手机号/身份证**整段漏网**（Python `\b` 把中文当 `\w`）。已改 `(?<!\d)…(?!\d)` 并加 2 条回归。**这个 bug 若只按原方案加脱敏调用、不测中文语境，会假绿。** |

**未做（诚实边界）**：L2 姓名/地址/病历号 NER、L3 字段白名单。当前正则只拦手机号/身份证。云端 provider 投产前须由机构评估是否补 NER——协议侧已知晓，不假称"全部 PII 已覆盖"。

---

## 优先级与依赖

```
P0  G2-L1 出网强制脱敏   ← 最高：一旦启云端就立即产生合规风险
P0  G2-L4 云端失败闸     ← 与 L1 配套，防正则漏网
P1  G1-A  反向代理 TLS   ← 部署级，需证书与运维配合
P1  G1-6  ProductionGate 加 tls 自检 ← 防止"没做看起来像做了"
P2  G2-L2 扩展识别模式 / NER
P2  G2-L3 字段白名单
P3  G5 审计独立库、G3 统一身份（属 COMPLIANCE-GATE 更大改造）
```

**为什么 G2 优先级高于 G1**：G1 明文传输的暴露面在院内内网，且可先用网络隔离兜底；G2 一旦启用云端 LLM，数据直接出到第三方，且当前代码**完全没有拦截**，风险更即时、更隐蔽。

## 相关文档
- `docs/DATA-SECURITY-POLICY.md` —— 缺口 G1/G2 的定义
- `docs/CAPABILITY-BOUNDARY.md` —— 能力边界
- `docs/COMPLIANCE-GATE.md` —— 投产签核（合规性不由本文替代）
