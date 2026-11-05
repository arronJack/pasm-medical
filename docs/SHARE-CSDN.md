# 从「纸面方案」到「真跑通」：PASM 医疗辅助系统第一版之后，我们更新了什么

> 本文沿用本专栏固定四段式：**结论先行 → 方案与实现 → 验证与数据 → 复盘与下一步**。
> 数据截至 2026-11-05，仓库 HEAD `b363dec`（坐诊消息改 SSE 实时推送 + 补三处坐诊安全缺口；相对 `c6ce22a` 基线再 +5 提交 / +33 文件 / +2441 行实功能；Gitee / GitHub / GitCode 三端一致），MIT。
> 可核对的完整对比清单：[`docs/V1-VS-NOW.md`](V1-VS-NOW.md)；真实数据评测报告：[`docs/REAL-LEARNING-EVAL.md`](REAL-LEARNING-EVAL.md)。

---

## 一、结论先行：三个数字，一个反直觉结论

2026-09-21，这个项目提交了第一行代码（`944e295`，18 个文件 / 2471 行）。当时 README 的「现状与后续」写得很诚实：

| 部分 | 第一版状态 |
|---|---|
| Python 认知服务 | ✅ 已实现并验证 |
| Spring Boot 业务层 | ⚠️ **骨架 + 认知客户端，未编译验证** |
| Vue 前端 | ⚠️ **骨架，未构建验证** |
| 端到端验证 | 认知 e2e **17 项** + 反例对照 |

45 天、43 次提交之后（当前 `0.1.1`），三个数字先摆出来：

- **129 个文件变更 / +22672 行 / −295 行**（`git diff --stat 944e295 HEAD`）；Java 主类 53 个、Python 模块 13 个、Flyway 迁移 4 个、验证脚本 10 个。
- **验证从「17 项 + 反例」变成 14 项套件编排 + 实测 97 / 19 / 14 / 109 / 23 / 26 / 71 项资产**；`run_checks.py --web --java` 全绿，其中三端联调 **109 项**（较 `c6ce22a` 基线 +28：科室主数据、登录选科、转人工、坐诊队列、接诊、双向消息、**SSE 实时推送**等）、后端真 PostgreSQL **26 项**、真实就诊数据自学习评测 **71 项**。
- **原计划排在 P3 的自学习审核台提前落地**，并真的开了 `MEDICAL_LEARNING_AUTO` 跑完一轮真实就诊数据。

**反直觉的结论在这里：功能全部跑通之后，我把生产开关继续关着。**

原因不是 bug，是合规：预问诊摘要按设计**不含医师结论**，从真实就诊里抽出来的候选，正文实际是「患者自述 + 分诊建议」。这种内容不具备作为机构回答依据的资格。给候选加一道护栏、加一道人工审核闸门，都只是兜底；要让自学习真正在生产可用，必须先补「医师确认后的判断/处置」采集链路。

一句话：**第一版是「一份能自圆其说的方案 + 一个能跑的认知服务」；现在是「三端真跑通、四类角色、能对接 LIS、带审核台与版本回滚、并且被真实数据反复证伪过的 MVP」—— 而"被证伪过"比"全绿"更值钱。**

---

## 二、方案与实现：架构决策改了什么，模块补齐了什么

### 2.1 最重要的一个决策变更：不补 HTTP API，改「薄转发 + 复用门面」

第一版 `PLAN.md` §0.4 的结论是：框架的 HTTP 网关只有「客服形态」，记忆 / 情感 / 成长能力只在 MCP 上，**P0 第一件事应该是给框架补一组认知 HTTP API**。

实际落地时换了更省、更稳的路子：

```
Vue 3（只调 /api）
   ↓
Spring Boot：鉴权 + 参数校验 + 透传 + 留痕 + 落库   ← 不含任何医疗算法
   ↓（管理令牌，绝不下发浏览器）
Python 认知服务：问诊树 / 相关性闸门 / 检验单判读 / 记忆 / 受控学习
```

Java 层永远只做五件事，算法**只留在 Python 侧一份**。这不是洁癖：这个项目真的因为「同源两份代码」坏过一次 —— 相关性闸门只回植了 Java 这一边，独立包发布版答非所问。医疗场景里，两份实现悄悄分叉 ≈ 两份不同的医疗结论。

配套还多了 **MCP stdio sidecar**（`pasm_medical/mcp/`）：复用同一个 `MedicalService` 门面，不启 HTTP，可以挂到 WorkBuddy / Claude Desktop / Cursor，零端口污染。认知服务对外共 **15 条医疗路由**，`/healthz` 的 `custom_routes=15` 是「接口真的挂上了」的唯一可观察信号。

### 2.2 分期路线图：当时画的饼，现在做到哪了

| 首版 PLAN §11 | 原计划 | 2026-11-05 现状 |
|---|---|---|
| P0 打通链路 | 认知 HTTP API + 双端骨架 | ✅ 完成，路径改为薄转发 |
| P1 医生助手 MVP | 患者/就诊 CRUD + 聊天 + 资料库 + 审计 | ✅ 完成：JPA 持久化、资料库真正影响回答、读+写双向审计且每条带操作者；**2026-11-05 补齐真实「在线坐诊」闭环——患者一键转人工（红旗中断自动转接）、医生接诊、双向消息（SSE 实时推送 + 断线重连）、结束落 `Encounter`** |
| P2 认知能力 | 记忆分层 + 患者隔离 + 情绪共情 + 反馈闭环 | ✅ 主体完成（情绪共情仍只是认知层能力）；采纳/否决只影响**分诊候选后验**，不训练聊天动作池 |
| P3 自学习 | 每日批处理 + 候选池 + 审核台 + 版本回滚 | ✅ **前移落地**并完成真实数据评测；⚠️ 生产批处理仍关闭 |
| P4 / P5 影像 / 中医知识库 | 目标态 | ❌ 未开始；影像刻意只归档 + 转交，**不做分析** |
| 合规界定 | 分类界定 / 等保 / 临床顾问 / 回顾性研究 | ❌ 仍未启动 |

首版压根没排期、后来补上的能力：**LIS/HL7/FHIR 对接**、**PostgreSQL + Flyway 版本化迁移**、**HS256 JWT + 会话存储可插拔**、**科室维度统计**、**FHIR R4 只读投影**、**Windows 一键启动器**、**科室主数据 + 人工坐诊全链路 + SSE 实时推送**（把「AI 转人工后接不上真人」这块真实缺口补上）。

### 2.3 模块级更新清单

| 层 | 第一版 | 当前版 |
|---|---|---|
| 业务层 | 3 个类、未编译 | 53 个 Java 主类；患者/就诊/审计/配置/**科室**/资料文档/知识候选/检验结果/**人工会话**落库；Flyway `V1/V2/V3/V4`；四角色 JWT + URL 级/资源级双层授权；`/api/admin/**` 限医护侧、改配置**对接设置仅超管** |
| 认知层 | `domain` / `safety` / `service` | 新增问诊树 `consult.py`（红旗中断、覆盖率、分诊）、检验单 `lab.py`（OCR→结构化→**回显确认**，未确认不入病历）、三档 LLM 网关（ollama / OpenAI 兼容 / **null 也能跑**）、`store.py` 会话存储、`learning.py` 医学动作后验、`mcp/` sidecar |
| 前端 | 单个 `App.vue` 骨架 | `vue-router` 三页 + `api.ts` + `identity.ts`；身份唯一真相源 `GET /api/auth/me`；**「结束问诊」按钮真的会落一条 `Encounter`**；**医护后台新增「坐诊」台（队列 + 双向消息 + SSE 实时推送 + 结束）与超管专属「科室管理」**；登录页临床角色须选坐诊科室；`vue-tsc` + `vite` 构建 0 错误；生产产物 `grep 123456` = 0 |
| 自学习 | PLAN §8 纸面四条铁律 | 候选池 → 指纹去重 → 冲突挂起 → **审核台采纳才写 `knowledge_docs` 并 +1 版本** → 驳回不入库 → 知识池可回滚；生成/回滚仅超管，审核按科室收窄 |
| 集成 | 无 | HL7 v2 `ORU^R01` 与 FHIR `Observation`/`Bundle` 落 `lab_results`，`(message_control_id, observation_seq)` 幂等；机器通道用独立共享密钥 `MEDICAL_LIS_TOKEN`；`/api/fhir/**` R4 只读投影 |
| 分发/工程 | `pip install -e .` | PyPI 0.1.0 → 0.1.1；`dist_smoke.py` 唯一验证「装出来的包」；`.bat` 启动器（GBK + CRLF 契约）+ `.gitattributes` 根因修复 |

### 2.4 一段值得贴出来的代码：真实链路的字段对齐

这是我这轮改动中最能说明「演示数据会骗人」的一段（`MedicalProxyController.consultFinish`，节选）：

```java
// 认知侧 summary() 给的是 snake_case，且按合规不含 assessment / plan
e.setChiefComplaint(firstText(r, "chief_complaint", "chiefComplaint"));
// ★ 绝不凭空造「判断/处置」：预问诊不是诊断，编造结论是医疗合规红线
e.setAssessment(firstText(r, "assessment"));   // 上游没给就留 null
e.setPlan(firstText(r, "plan"));
JsonNode triage = r.path("triage");
if (triage.isObject()) {
    e.setTriageUrgency(firstText(triage, "urgency"));
    String dept = firstText(triage, "suggested_department");
    // 复合建议「发热门诊 / 感染科」→ 取主科室用于按科室授权；完整文本仍原样留在摘要
    String primary = dept.split("\\s*/\\s*")[0].trim();
    e.setDepartment(primary.isEmpty() ? null : primary);
}
e.setSummaryText(summaryOf(r, e));   // 去标识化摘要，不存原始问诊对话
```

三条约束写在注释里，比写在文档里可靠：**不编造结论、不把分诊规则重写进 Java、不存原始对话**。

---

## 三、验证与数据：全绿 ≠ 断言有效

这一节是我最想分享的部分。项目里绝大多数真 bug **都不报错**：服务 healthy、测试全绿、后台数字看着合理，但结果是错的。两道闸门把它们抓了出来。

### 3.1 反例对照：故意改坏，确认被抓到，再还原

`falsify_medical_service.py`（5 处）+ `falsify_medical_learning.py`（2 处）故意破坏隔离失效、闸门失效、红旗规则失效、确认闸门被拆、主诉硬猜等判据，每处都必须**精确转红**，其余项保持绿，跑完自动还原。`e2e_stack.py` 里另有 12 条「改回旧实现就必须转红」的断言。

几个代表性案例：

| 症状（都不报错） | 根因 |
|---|---|
| `pip install` 出来的包，医疗接口全 404，但 `/healthz` 仍报 healthy | `register_route` 只存在于未提交工作区；注册失败还静默 `return 0`。修法：框架发 0.5.3 + 注册失败硬抛错 + `custom_routes` 可观察 + `dist_smoke.py` |
| 患者 B 回答问诊，改到了患者 A 的进行中会话 | `session_key` 默认恒为 `"current"`，全租户只有一条会话 |
| 审计里查不出谁登录，且**访问令牌明文落进审计表** | `login()` 返回的第 0 位是 token，被当成 actor |
| 「今日问诊量」永远不超过 200，一次问诊算两次 | 取最近 200 条在内存里筛 + 把 `ask`/`finish` 都算一次 |
| 资料库新增成功，提问却一直拒答 | `kb.recall` 把 `source` 又装饰一层前缀，自己的 key 过滤把资料全滤光 |
| 同秒两次登录签出的 JWT 逐字节相同，登出一个把另一个也踢掉 | payload `iat` 只到秒；401/403 都塌成 403 |

> 通用教训：**统计类代码只用「字段存在/非空」做断言等于没断言**；判据必须能分辨「多算 / 少算 / 截断」三种错。凡是「取最近 N 条再在内存里聚合」的写法，先假定它是错的。

### 3.2 真实数据评测：74 项联调一直「假绿」

开启 `MEDICAL_LEARNING_AUTO` 之前，我先写了一个评测脚本 `tools/eval_real_learning.py`：真起 3 个服务（认知服务 + **带开关**的主后端 + **不带环境变量**的对照后端），5 例合成主诉（无真实患者信息）全程走 `start → answer → finish`，与演示数据严格隔离；生成报告回传 `autoGenerateEnabled` 做开关正反硬断言。

**首轮 67 通过 / 3 失败**，三个失败全是真实数据才暴露的：

1. **真实 finish 落库是空壳** → 候选池恒 0 条。Python `summary()` 返回 `chief_complaint` / `present_illness` / `triage.suggested_department`，且按合规不含 `assessment` / `plan`；旧 Java 读驼峰 `chiefComplaint` 等 → 全 null → `title.isEmpty() → continue`。而 `DemoDataSeeder` 手工把字段填齐了，所以 **74 项联调一直假绿**。
2. **科室管理员对真实候选可见 0 条**。分诊建议是复合值「发热门诊 / 感染科」，授权按单科室精确 `equals`。
3. **无医师结论的候选需要合规护栏**。正文前置 `【预问诊采集·非医师结论，不得作为诊疗依据】`，但**不放进 tags** —— 否则等于给相关性闸门新增一个可命中词，反而更容易被检索出来。

修完复跑：**71 项通过 / 0 项失败**；同时给 `e2e_stack.py` 补了 **7 项真实链路落库回归**（74 → **81 项**），以后再退化会被当场抓到。

**又过了一轮需求复核**，发现一个更大的「全绿但接不上」的洞：三端联调全绿，但医护后台只有查看态、没有坐诊功能，患者点「转人工」无处可去，也没有在线即时沟通。于是 2026-11-05 补齐了真实坐诊闭环——科室主数据、登录选科、患者一键转人工（红旗自动转接）、坐诊队列按科室隔离、接诊、双向消息、结束落 `Encounter`——并把实时消息从轮询换成 **SSE 推送 + 断线重连**。联调项数随之从 81 涨到 **109**（新增 28 项，全部是这条链路的端到端断言）。

### 3.3 当前验证口径（2026-11-05 实测，别把「套件」和「资产」混成一个数）

| 类型 | 项数 | 说明 |
|---|---|---|
| `run_checks.py --web --java` | **14 项套件全绿** | 编排的是「环节」，不是断言条数 |
| 模块自检 | **97** | domain 14 + safety 16 + lab 24 + consult 23 + llm 11 + store 9 |
| 认知 e2e / 学习 e2e | **19 / 14** | 真起 HTTP / 医学动作后验 |
| 反例对照 / 学习反例 | **5 处 / 2 处** | 故意改坏 → 精确转红 → 还原 |
| 三端联调 | **109** | 四角色全覆盖，含 7 项真实链路落库回归 + 28 项坐诊/科室/SSE 端到端断言 |
| MCP selftest / MCP stdio e2e | **23 / 15** | 后者是独立资产，未编进 run_checks |
| 后端真 PostgreSQL | **26** | 迁移 1 + 整应用 1 + 候选审核台 5 + LIS 9 + HL7 10（嵌入式 PG，免 Docker） |
| 真实就诊数据评测 | **71** | 独立资产，需手动跑 |
| 分发形态冒烟 / PG jar 冒烟 | **9 / 9** | 前者发 PyPI 前必跑、后者需 Docker；**本轮未跑**，如实标注 |

> 提醒自己也提醒读者：**「本机能跑」≠「装上能用」**。所有常规验证都跑在源码工作树上，对 `pip install` 出来的包是盲区 —— 第 3.1 节那个 404 就是这么漏过来的。

---

## 四、复盘与下一步：还有哪些问题

### 4.1 本轮复盘修掉的两个「卫生问题」

- **文档数字口径漂移**：README / GUIDE / PLAN / 配套 skill 里还留着 `67 项` / `17 项` / `88 项` / `24 项` / `selftest 19 项` / `--java 21 项` 等旧数字，甚至同一份 README 一处写 67、一处写 81。已按实测统一；`--java` 的 26 项里补上 `CandidatePostgresTest` 5 项。
- **源码里嵌了裸 NUL 字节**：`KbCandidateService.fingerprintOf` 用 `"<NUL>"` 当指纹分隔符，导致 `grep` / 部分编辑器把这个 `.java` 当二进制文件。已改成源码八进制转义 `"\0"`，**运行行为完全等价**，重新编译 + 全量验证复验。

顺带把配套技能文档里三条已过时的「未做」记录改成带 commit 证据的修复状态表 —— 文档也会骗人，尤其是写得很好的文档。

### 4.2 仍未解决（按严重度，写在这里不是为了好看）

| 级别 | 问题 | 下一步 |
|---|---|---|
| **P0 合规** | 预问诊候选不含医师结论，`MEDICAL_LEARNING_AUTO` 生产保持关闭 | 补「医师确认后的判断/处置」采集链路（P3）；在此之前人工审核台是最后一道闸门 |
| **P0 合规** | 规则表未经临床药师复核；未做医疗器械分类界定 / NMPA 路径；等保三级、单独同意、导出水印审批、临床顾问与回顾性研究均未启动 | **MIT 只覆盖代码，代码开源 ≠ 可合法用于临床诊断** |
| P1 功能 | 影像归档、中医知识库未开始；pgvector / MinIO / Redis 仍是设计目标 | 影像刻意不做分析（无三类证）；**SSE 实时推送已于 2026-11-05 落地**（坐诊双向消息用 `SseEmitter` + 前端 `fetch`/`ReadableStream`，替代轮询），Redis 仅在多主机水平扩容时才是必需 |
| P1 集成 | LIS 的 TCP MLLP 监听、HL7 ACK、LOINC/SNOMED 映射未实现 | 边界见 `docs/INTEGRATION-LIS-FHIR.md` §7 |
| P1 部署 | 多主机需把 `SessionStore` 接 Redis/DB（协议已就位，当前 file 只覆盖单机多 worker）；生产需注入 `MEDICAL_AUTH_JWT_SECRET`、接 OIDC/OAuth2 | 单实例/单机可用，多主机未验证 |
| P2 已知结构 | 红旗中断路径不给 `suggested_department`（`department` 为空，候选仅超管可见）；科室角色看不到无 `patientRef` 的登录/改配置审计事件；`stop-demo.bat` 真正无控制台时取 PID 仍用 `netstat \| findstr` | 已有明确边界说明，**未改** |
| P2 验证编排 | MCP stdio e2e 15 项、真实评测 71 项、分发冒烟 9 项未编进 `run_checks.py`；`dist_smoke` / `pg_smoke` 本轮未跑 | 改后端落库逻辑 / 发 PyPI 前手动补跑 |

### 4.3 自己复现

```bash
# 以下脚本都在仓库内，clone 后人人可跑（需 JDK 17+，跑三端联调需 JDK 18）
python -m pasm_medical.domain             # 14 项：隔离规则 / 去标识
python -m pasm_medical.safety             # 16 项：三条红线
python tools/e2e_medical_service.py       # 19 项：真起认知服务 HTTP
python tools/falsify_medical_service.py   # 5 处反例：故意改坏必须被抓到
cd backend && mvn -DskipTests package && cd ..
JAVA_HOME='C:\Program Files\Java\jdk-18.0.1.1' python tools/e2e_stack.py   # 109 项三端联调
cd web && npm run build                   # vue-tsc + vite，0 错误
cd backend && mvn -Dtest='FlywayPostgresTest,FullAppPostgresTest,CandidatePostgresTest,Hl7ParserTest,LisIntegrationPostgresTest' test  # 真库 26 项

# 真实就诊数据自学习评测（真起 3 个服务，约 1 分钟，自动产 Markdown 报告）
JAVA_HOME='C:\Program Files\Java\jdk-18.0.1.1' \
  python tools/eval_real_learning.py --out docs/REAL-LEARNING-EVAL.md

# 首版基线
git show --stat 944e295 && git diff --stat 944e295 HEAD
```

> 维护者本机另有一个一键编排脚本 `run_checks.py`（放在配套 skill 目录，不在仓库里），把上面这些编排成 **14 项套件**并统一判退出码；它只是编排器，不构成外部复现的前置条件。

开源地址：Gitee `arronzheng/pasm-medical` · GitHub `arronJack/pasm-medical` · GitCode `arronzheng/pasm-medical`（MIT）。

### 4.4 最后一句

做医疗 AI 最容易犯的错，不是功能没做完，而是**把"测试全绿"当成"结论可信"**。这个版本里，我们最花时间的不是新增 22672 行代码，而是不断地问：这条断言如果写空了，会不会照样绿？这份数据如果是演示数据填的，会不会把真缺陷盖住？这个开关打开了，出来的东西有没有资格进知识库？

**能回答这三个问题，比多写三个功能更接近"可用"。**
