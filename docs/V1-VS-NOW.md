# 第一版 → 当前版：更新了哪些内容

> 生成日期：2026-11-05 · 仓库当前 HEAD：`c6ce22a` · 三端（Gitee / GitHub / GitCode）SHA 一致
> 本文是**可核对的对比清单**，配套成稿见 [`SHARE-CSDN.md`](SHARE-CSDN.md) 与 [`SHARE-WECHAT.md`](SHARE-WECHAT.md)。

## 0. 基线定义（先对齐口径，避免"第一版"各说各话）

| 项 | 定义 |
|---|---|
| **第一版** | 2026-09-21 首个提交 `944e295`（`feat: 医院智能诊疗辅助系统`），同日 `docs/PLAN.md` 标注 `v0.1`，PyPI 侧对应 `pasm-medical 0.1.0` |
| **第一版当时的自述** | README「现状与后续」原文：Spring Boot 业务层 = **骨架 + 认知客户端，未编译验证**；Vue 前端 = **骨架，未构建验证**；验证只有 **认知 e2e 17 项 + 反例对照**；中医知识库 / 多模态**未开始** |
| **当前版** | 2026-11-05 HEAD `c6ce22a`，`pyproject.toml` = **0.1.1**，共 **38 次提交**（9/21 共 9 次、9/22 共 14 次、9/23 共 6 次、11/04 共 5 次、11/05 共 4 次） |
| 代码体量变化 | 首版 **18 个文件 / 2471 行新增** → 相对首版 `git diff --stat 944e295 HEAD` = **112 个文件变更 / +19825 行 / −294 行** |
| 许可与托管 | 首版即 MIT，现仍是 MIT；Gitee `arronzheng/pasm-medical`、GitHub `arronJack/pasm-medical`、GitCode `arronzheng/pasm-medical` 三端公开 |

> 若首篇分享稿覆盖的是「方案设计 v0.1（`docs/PLAN.md`）」而不是仓库代码，对比仍然成立：那份 PLAN 的 P0/P1/P2/P3 分期正是本文第 2 节的对照轴。

---

## 1. 一页总览

| 维度 | 第一版（2026-09-21） | 当前版（2026-11-05） |
|---|---|---|
| 定位 | 医生的副驾驶，不是 AI 医生；三条红线 | **没变**（不下诊断、不自动出处方、无依据必拒答）；红线由代码保证 |
| 架构决策 | PLAN §0.4 计划「给 pasm-framework 补一组认知 HTTP API」 | **改成薄转发 + 复用门面**：Java 只做鉴权/校验/透传/留痕/落库，算法只留在 Python 侧一份；新增 MCP stdio sidecar（零端口污染） |
| Spring Boot | 骨架 + `PasmCognitionClient` + `AssistController`，**未编译验证** | 编译/启动/三端联调全过；**53 个 Java 主类**；JPA 持久化 + Flyway 3 个迁移 + PostgreSQL 真库；四角色 HS256 JWT；双层授权；科室维度统计；LIS/HL7/FHIR 与 FHIR R4 只读投影 |
| Python 认知服务 | `domain.py` / `safety.py` / `service.py` 三个模块 | **13 个 Python 模块**：新增问诊树 `consult.py`、检验单 `lab.py`、三档 LLM 网关 `llm.py`、会话存储 `store.py`、受控学习 `learning.py`、`mcp/` sidecar；**15 条医疗路由**挂到网关（`/healthz` 的 `custom_routes=15`） |
| Vue 前端 | 单个 `App.vue` 骨架，**未构建验证** | `vue-router` 三页（登录 / 三栏工作台 / 医护后台）+ `api.ts` + `identity.ts`；**结束问诊会真落 `Encounter`**；`vue-tsc` + `vite` 构建 0 错误；生产产物不含演示账号字样 |
| 安全与身份 | 患者隔离认知侧测试；无登录/角色体系 | 四角色（患者/医护/科室管理员/超管）+ URL 级与资源级**双层授权** + 归属校验挡 IDOR + 演示账号默认关闭 + 401/403 分离 + 读/写双向审计 + 资料库按科室授权 |
| 自学习 | 只在 PLAN §8 的纸面设计，排期 **P3** | **前移落地**：候选池 + 审核台 + 知识池版本回滚（`V3__kb_candidates.sql`）；指纹去重、冲突挂起、采纳才入 `knowledge_docs`、驳回不入库；**已开启 `MEDICAL_LEARNING_AUTO` 跑完一轮真实就诊数据评测（71 项全绿）**；**生产开关仍关闭**（原因见 §5） |
| 分发与工程化 | `pip install -e .`；无启动器；无迁移 | PyPI `pasm-medical` 0.1.0 → 0.1.1；`dist_smoke.py` 守住「装出来的包」；Windows 一键启动器 + `.gitattributes` 行尾契约；`tools/` 10 个验证脚本 |
| 验证体系 | 认知 e2e **17 项** + 反例对照 | `run_checks.py --web --java` **14 项套件全绿**；实测项数：模块自检 **97**、认知 e2e **19**、学习 e2e **14**、三端联调 **81**、MCP selftest **23**、MCP stdio e2e **15**、后端真 PG **26**、真实就诊评测 **71**、分发形态冒烟 **9**（发版资产，本轮未跑） |
| 文档 | README + PLAN（108 + 385 行） | README + **7 份 docs**（FEASIBILITY / GUIDE / DB-MIGRATION / INTEGRATION-LIS-FHIR / PLAN / PLAN-CASE-LEARNING / REAL-LEARNING-EVAL）+ 本文与两篇分享稿 |

---

## 2. 分期路线图：当时画的饼，现在做到哪了

| 首版 PLAN §11 分期 | 原计划 | 当前状态 |
|---|---|---|
| **P0** 打通链路 | 认知 HTTP API + Spring Boot 骨架 + Vue 骨架 + Java SDK 联调 | ✅ 完成，且路径改为「薄转发 + 复用门面」，避免同源两份实现 |
| **P1** 医生助手 MVP | 患者/就诊/病历 CRUD + 聊天 + 知识库检索 + 审计 | ✅ 完成：JPA 持久化、资料库真正影响回答、读+写审计且带操作者、统计口径可核对 |
| **P2** 认知能力 | 记忆分层 + 患者隔离 + 情绪共情 + 采纳反馈闭环 | ✅ 主体完成（情绪共情仍只作认知层能力，未做业务层单独编排）；采纳/否决改接医学动作后验，**不训练聊天动作池** |
| **P3** 自学习 | 每日批处理 + 候选池 + 审核台 + 版本回滚 | ✅ **前移落地**（2026-11-04），2026-11-05 用真实数据评测；⚠️ 每日批处理生产**仍关闭**（合规原因，见 §5） |
| **P4 / P5** 影像 / 中医知识库 | 目标态 | ❌ 未开始；影像只做归档+转交，**不做分析**（合规正确） |
| 合规 | 分类界定 / 等保三级 / 临床顾问 / 回顾性研究 | ❌ 仍未做，见 §5 |

顺带新增了首版没排期的能力：**LIS/HL7/FHIR 对接**（`8d79072`）、**PostgreSQL + Flyway 版本化迁移**（`3492a75`）、**JWT 鉴权与会话存储可插拔**（`91b679e`）、**科室维度统计**（`7806b77`）、**FHIR R4 只读投影**、**Windows 一键启动器**。

---

## 3. 逐模块更新明细（按提交可追溯）

| 模块 | 关键提交 | 更新内容 |
|---|---|---|
| 能力层 | `068dd1d` / `e6b1696` | 智能问诊树（主诉识别、现病史七要素、红旗中断、覆盖率、摘要、分诊）；检验单 OCR→结构化→规则判读→**回显确认**（未确认不入病历）；LLM 三档（ollama / OpenAI 兼容 / null），**无 LLM 也能跑** |
| 业务层 | `bb61d81` / `71713ea` / `c57511e` | JPA 持久化；`/api/admin/**` 限医护侧、演示账号默认关闭；资料库 CRUD + 全量同步到认知侧 + 带依据问答真的走相关性闸门 |
| 身份与授权 | `5822238` / `ab686dc` / `92ee718` / `91b679e` | 身份→业务实体绑定（patientRef/科室/工号）；四角色分区；P1-2 科室维度；HS256 JWT（`jti` + 撤销表）+ 401/403 分离；`SessionStore` 默认 file 存储 |
| 审计与统计 | `485be27` / `f1c9905` / `7806b77` | 读事件留痕 + 每条带操作者 + 问题原文下发后台；统计改 DB 带时间窗计数（去掉「最近 200 条」截断与重复计数）；科室范围分流 |
| 反馈与自学习 | `b68315c` / `a431107` / `c5f0fdf` | 医学动作后验（Beta-Bernoulli，只影响分诊候选排序）；候选池/审核台/版本回滚；真实就诊数据评测与三处真缺陷修复 |
| 集成与数据 | `8d79072` / `3492a75` / `9e9ce8d` | HL7 v2 `ORU^R01` + FHIR `Observation`/`Bundle` 落 `lab_results`（幂等）；Flyway `V1/V2/V3`；依赖 `pasm-skills>=0.6.4` 修认知层患者隔离 |
| 分发 / 演示 | `afeb28b` / `ad29b59` / `ddf324e` | `register_medical_routes` 注册失败改**硬抛错** + `/healthz custom_routes` 可观察 + `dist_smoke.py`；Windows `.bat` 启动器（GBK/CRLF 契约、无控制台探测分支、端口钉命令行） |

---

## 4. 真实数据与反例对照抓出来的「静默错误」（分享时最值得讲的部分）

这些 bug **都不报错**，全绿测试也看不见，只有端到端断言 / 反例对照 / 真实数据能抓到：

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1 | `pip install` 出来的包装上后，所有医疗接口 404，但 `/healthz` 仍报 healthy | `register_route` 只存在于未提交工作区；注册失败还静默 `return 0` | 框架能力发 0.5.3；注册失败硬抛错；`custom_routes` 可观察；`dist_smoke.py` 唯一跑「装出来的包」 |
| 2 | 患者 B 回答问诊，改到患者 A 的进行中会话 | `session_key` 默认恒为 `"current"`，全租户只有一条会话 | `_session_key(patient_ref, session_key)`；answer/finish 必填 `patient_ref` |
| 3 | 审计里查不出谁登录，且访问令牌明文落库 | `login()` 返回的第 0 位是 token，被当成 actor | 改 `LoginResult(token, Identity)` 具名对象 |
| 4 | 「今日问诊量」永远不超过 200，且一次问诊算两次 | 取最近 200 条在内存里筛 + 把 `ask`/`finish` 都算一次 | 全部改 DB 带时间窗计数，口径定义随响应返回 |
| 5 | 资料库新增成功，提问却一直拒答 | `kb.recall` 把 `source` 又装饰一层前缀，自己的 key 过滤把资料全滤掉 | 端到端断言「新增资料后必须命中 doc 来源」 |
| 6 | 同秒两次登录签出的 JWT 逐字节相同，登出一个把另一个也踢掉；前端把 403 当成登录失效 | payload `iat` 只到秒；401/403 都塌成 403 | 加 `jti`；服务端分开 401（未认证）/403（角色不足） |
| 7 | **真实问诊落库的 `Encounter` 是空壳**，候选池恒产 0 条 | Python `summary()` 给 `chief_complaint`/`present_illness`/`triage.suggested_department`，且按合规**不含** `assessment`/`plan`；Java 读驼峰字段全 null。演示数据手工填齐字段，74 项联调一直**假绿** | `consultFinish` 对齐真实字段、补 `department`、`summaryOf` 生成去标识化摘要；`e2e_stack.py` 补 **7 项真实链路落库回归**（74 → **81 项**） |
| 8 | 科室管理员对真实候选可见 0 条 | 分诊建议是复合值「发热门诊 / 感染科」，授权按单科室精确 `equals` | 落库取主科室用于授权归类，完整建议原样留在摘要里（**分诊规则仍在认知侧，Java 不重写**） |
| 9 | 双击启动器满屏乱码 / 无控制台时卡死 / 端口莫名被占用 | `.bat` 用了 UTF-8 + LF + 跨行括号块；`DETACHED_PROCESS` 下 `java -version`/`netstat` 输出 0 字节；环境变量 `SERVER__PORT` 被 Spring 宽松绑定成 `server.port` | GBK + CRLF + 单行 `goto` 结构；探测改读文件/只看退出码；`--server.port=8081` 钉在命令行；`.gitattributes` 根因修复 |
| 10 | 认知层患者 A 召回里混进 B 的过敏史 | `pasm-skills` 的 `PasmV1CoreBackend` 持有模块单例，`set_data_dir()` 改的是全局目录 | `pasm-skills` 加 `data_dir` 作用域 + RLock；medical 依赖升到 ≥0.6.4 |

> 方法论：**全绿 ≠ 断言有效**。反例对照（故意改坏 → 确认被抓到 → 还原）与「真实链路 + 真实数据」是这个项目两道最值钱的闸门。

---

## 5. 还有哪些问题（如实标注，别假装修好）

### 5.1 本轮复盘发现并**已修**的小问题
- 文档验证项数口径漂移：`README.md` / `docs/GUIDE.md` / `docs/PLAN.md` / 配套 skill 里仍写着 `67 项` / `17 项` / `88 项` / `24 项` / `selftest 19 项` / `--java 21 项` 等旧数字，且同一文件自相矛盾（README 一处 67、一处 81）。已按 2026-11-05 实测统一为：**97 / 19 / 14 / 81 / 23 / 15 / 26 / 71**，`--java` 26 项含 `CandidatePostgresTest` 5 项。
- 配套 skill 中三条「未做」记录已过时（前端结束问诊入口 `91b679e`、科室统计 `7806b77`、医护与科室管理员能力差异 `92ee718`），已改为带 commit 证据的修复状态表。

### 5.2 仍未解决（按严重度排序，写进文章不能省）
| 级别 | 问题 | 现状 / 下一步 |
|---|---|---|
| **P0 合规** | 预问诊摘要按合规**不含医师结论**，候选正文实际是「患者自述 + 分诊建议」，**不具备作为机构回答依据的资格** | 已加正文护栏 `【预问诊采集·非医师结论，不得作为诊疗依据】`（不放进 tags，避免给相关性闸门新增命中词）；`MEDICAL_LEARNING_AUTO` **生产保持关闭**；根治须补「医师确认后的判断/处置」采集链路（P3） |
| **P0 合规** | 规则表未经临床药师复核；未做医疗器械分类界定 / NMPA 注册路径；等保三级、单独同意、导出水印审批、临床顾问与回顾性研究均未启动 | MIT 只覆盖代码，**代码开源 ≠ 可合法用于临床诊断**；投产前必须补齐 |
| P1 功能 | 影像归档、中医知识库**未开始**；pgvector / MinIO / Redis / SSE 流式仍是设计目标 | 影像刻意只归档+转交、不做分析（无三类证） |
| P1 集成 | LIS 的 **TCP MLLP 监听、HL7 ACK 报文、LOINC/SNOMED 映射**未实现 | 边界以 `docs/INTEGRATION-LIS-FHIR.md` §7 为准 |
| P1 部署 | 多主机水平扩容需把 `SessionStore` 接 Redis/DB（协议已就位，当前默认 file 只覆盖单机多 worker）；生产需注入 `MEDICAL_AUTH_JWT_SECRET`、接医院统一身份（OIDC/OAuth2） | 单实例/单机可用，多主机未验证 |
| P2 已知结构 | 红旗中断路径 `triage()` 不给 `suggested_department` → `department` 为空、候选按设计仅超管可见；科室角色看不到无 `patientRef` 的登录/登出/改配置审计事件；`stop-demo.bat` 在真正无控制台父进程下取 PID 仍用 `netstat \| findstr` | 均已有明确边界说明，**未改** |
| P2 验证编排 | MCP stdio e2e **15 项**、真实就诊评测 **71 项**、分发形态冒烟 **9 项**是独立资产，**未编进 `run_checks.py`**；`dist_smoke.py` / `pg_smoke.py`（9 项，需 Docker）本轮**未跑** | 改后端落库逻辑或发 PyPI 前需手动补跑 |
| P3 工程 | 多会话并发提交会互相抢先（2026-11-05 出现过一次 `80d3c20` 被另一会话抢先提交，已核验 diff 一致无损失） | 推送坚持 fast-forward + 逐端 `git ls-remote` 核验 |

---

## 6. 复现与核对命令

```bash
# 全量验证（JDK 18；脚本在配套 skill 里，不在仓库里）
JAVA_HOME='C:\Program Files\Java\jdk-18.0.1.1' \
  "C:/Python312/python.exe" "C:/Users/<你>/.workbuddy/skills/pasm-medical/scripts/run_checks.py" \
  --web --java --repo "E:/AI/pasm/code/pasm-medical"
# 期望：14 项套件通过 / 0 项失败（三端联调 81 项、后端真库 BUILD SUCCESS）
# 注：run_checks.py 是维护者本机的编排器（放在配套 skill 目录，不在仓库里）。
# 外部读者 clone 后按 README.md「自检与端到端」逐条跑仓库内脚本即可，结论等价。

# 真实就诊数据自学习评测（真起 3 个服务：带开关的主后端 + 不带环境变量的对照后端 + 认知服务）
JAVA_HOME='C:\Program Files\Java\jdk-18.0.1.1' \
  "C:/Python312/python.exe" tools/eval_real_learning.py --out docs/REAL-LEARNING-EVAL.md
# 期望：71 项通过 / 0 项失败，报告落 docs/REAL-LEARNING-EVAL.md

# 首版基线核对
git show --stat 944e295 && git diff --stat 944e295 HEAD
```

## 7. 一句话结论

**第一版是「一份能自圆其说的方案 + 一个能跑的认知服务」；当前版是「三端真跑通、四类角色、能对接 LIS、带审核台与版本回滚、并且用真实数据和反例对照反复证伪过的 MVP」——但正因为把真实数据跑通了，才确认自学习在生产上还不能开，这个开关我选择继续关着。**
