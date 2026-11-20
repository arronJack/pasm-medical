# 后续安全改造方案草案（待评审）

> 版本：v0.2 · 更新：2026-11-20 · 状态：**方案已评审；L6 透传断点已实施（见下），其余按结论推进**
> 来源：2026-11-20 收尾复盘，四项待办中的 C（自由文本 PII 路线）与 B（G1 TLS 部署）。
> 落地记录：① egress 透传断点（§1.1 三步）**已于同日实施并推送三端**；
> ② 结构化表单路线已采纳为方向，待前端改造排期；③ G1 TLS 待小志提供部署参数（§2.3）；
> ④ pasm-agents 依赖下限已随 0.6.1 发版生效（§三）。

---

## 〇、结论先行

| 项 | 结论 | 是否需你拍板 | 我能否先做 |
|---|---|---|---|
| 自由文本 PII | **先补 egress 透传断点**（零风险），再走"结构化表单"根治；**否决 NER 改写** | 路线需拍板；透传断点可直接做 | 是 |
| G1 TLS | 方案 A（反向代理终止 TLS）文档已完备；**服务器/域名/证书需你提供** | 部署参数需你给 | 仅能先补 `ProductionGate.tls_enabled` 自检（需授权动已推送代码） |

---

## 一、自由文本 PII：结构化 vs NER

### 1.1 先说一个复盘抓到的真实断点（最高优先级）

L6「身份声明可见性」只做了一半 —— **Python 有检测，界面看不到提示**。逐层证据：

| 层 | 位置 | 现状 |
|---|---|---|
| 检测逻辑 | `pasm_medical/domain.py:195` `detect_identity_declaration()` | ✅ 已实现，只返回类型+次数不含原文 |
| 组装提示 | `pasm_medical/service.py:115` `_egress_notice()` | ✅ 已实现 |
| Python 返回 | `service.py:379-380` 及拒答分支 `:395` 两条 return 都带 `egress` | ✅ 已带 |
| Java 透传 | `backend/.../web/AssistController.java:57-105` 的 `out` map | ❌ **没读 `r.path("egress")`，没放进去** |
| 前端契约 | `web/src/api.ts:208-220` `ask()` 返回类型 | ❌ **没有 `egress` 字段** |
| 前端展示 | `web/src/views/ConsultView.vue` | ❌ 无提示位 |

即：患者自报了姓名住址，后端算出了提示，但**前端接不到、也不显示**。L6 的实际用户可见价值 = 0。

**修法（零风险，建议无条件先做）**：
1. `AssistController.ask` 的 `out` map 加 `out.put("egress", cognition.toPlain(r.path("egress")))`；
2. `api.ts` 的 `ask()` 返回类型加 `egress?: { identity_hints: {kind:string;count:number}[]; notice: string }`；
3. `ConsultView.vue` 在发送结果处按 `notice` 显示一条轻量提示（不阻断、不改写原话）。
三步都不碰脱敏核心，纯透传+展示，可用现有自检 + 一次端到端验证。

### 1.2 两条根治路线对比

| 维度 | 路线 A：提问前结构化（推荐） | 路线 B：中文 NER 改写 |
|---|---|---|
| 做法 | 前端把"姓名/住址/电话"做成独立字段或不采集，自由文本只留症状；身份由已存在的 `patientRef` 承担 | Python 层加实体识别，命中后脱敏再出网 |
| 落点 | `ConsultView.vue:141` textarea + `api.ts:208` 契约 + 后端校验 | `domain.py` scrub 前置一层 NER |
| 根治程度 | **根治**：PII 根本不作为自由文本进链路 | 缓解：仍进链路，靠识别兜底 |
| 医疗误伤风险 | **低**：不动症状描述 | **高**：实测「我叫不上来哪里疼」被改成「我叫[姓名]里疼」，破坏临床语义 |
| 依据 | 身份已由 `patientRef` 定、记忆按患者隔离，自报姓名住址对作答**零贡献**（冗余） | 需模型/词典；中文姓名与普通词无边界（"李医生"/"当归"） |
| 成本 | 中（前端+契约，一次性） | 高（模型维护 + 误伤调优，长期） |

**推荐：A 为主，B 否决作为当前路线**。理由不是 NER 做不好，而是医疗场景里"误伤临床语义"的代价 > "漏掉一段冗余 PII"的代价 —— 这与 11-20 已否决"自称前缀改写"的判断一致（那次实测误伤 4/6）。

### 1.3 路线 A 的最小落地形态

不必做重型表单。基于"身份已由 patientRef 承担"这一事实：
1. **提示前移**：把 1.1 的 egress 提示做成发送前即时提醒（"无需填写姓名/住址，身份已按就诊卡识别"），而不是事后提示；
2. **字段剥离**：若确需采集，前端用独立结构化字段（与 `patient` profile `:243-245` 合并），question 只发症状文本；
3. **后端兜底**：`AssistController.ask` 对结构化字段走白名单，自由文本仍过现有 L1-L6（不重复造轮子）。

---

## 二、G1 TLS 部署

### 2.1 现状（代码可证，见 `docs/TLS-AND-DEIDENTIFICATION-ASSESSMENT.md:18-48`）
- `application.yml:6-7`：`server.port: 8081`，无 `server.ssl.*`；
- `docker-compose.yml`：无证书卷、无 443 映射；
- `ProductionGate.java:96-117` 覆盖 demo 账号/JWT/学习开关/MLLP，**不含 TLS**；
- 结论：全链路明文 HTTP。

### 2.2 方案（已评估，方案 A 反向代理终止 TLS 为首选）
文档 `:34-41` 的七步已完备：证书 → Nginx `443 ssl` → CORS 收紧 → HSTS → 健康检查仅内网 → `ProductionGate` 加 `tls_enabled` → MLLP 独立端口说明。验收标准见 `:43-46`。**不需要重新论证。**

### 2.3 待你提供的信息（这些我无法代劳）

| # | 需要你给 | 为什么 |
|---|---|---|
| 1 | 部署形态：docker-compose 还是裸机/虚机 | 决定 Nginx 是加容器还是装本机 |
| 2 | 域名（或确认内网 IP 直连） | 证书绑定 + CORS 白名单需要 |
| 3 | 证书来源：内网 CA / 公网 Let's Encrypt / 自签（仅测试） | 决定 `ssl_certificate` 配置与信任链 |
| 4 | 是否已有 Nginx/Caddy 或院内 API 网关 | 复用现有代理，避免重复建 |
| 5 | MLLP 端口（2575）是否跨机传检验数据 | 跨机则 TLS 不覆盖，需院方专网/白名单 |

### 2.4 我可以先做（需你授权动已推送代码）
`ProductionGate.java` 加 `tls_enabled` 自检项（文档 `:40` 列为 G1-6，防"没做看起来像做了"）。这项是**本地代码改动**，会动已推送的 backend —— 需你点头才做。

---

## 三、跨仓/发布类（复盘附带，另行拍板）

- **pasm-agents 依赖下限**：源码已改 `>=0.6.7`（本地，patch 备份在仓库外 `E:/AI/pasm-agents-dep-bump-20261120.patch`）。但 **PyPI 0.6.0 wheel 仍写 `>=0.6.4`（已查证）** —— 只改源码不发新版，老用户风险没消除。要真正生效需发 pasm-agents 0.6.1（三端+PyPI），**是否发版待拍板**。
- **pasm-agents 三个未提交文档改动**：核实对应真实代码（`agents/safety.py` 的 `NonResistanceGate`/`startup_report`/`guard_reply` 均在），是给已发布 0.6.0 功能补文档，内容真实；但属并发会话的活，按纪律未碰。请确认由谁收尾。
