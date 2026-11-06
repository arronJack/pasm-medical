# 真实链路就诊数据 × 自学习候选池 评测报告

- 生成时间：2026-11-06 18:19:04
- 仓库：`E:/AI/pasm/code/pasm-medical`
- 命令：`python tools/eval_real_learning.py`
- 后端开关：`MEDICAL_LEARNING_AUTO=true`（dev 档，H2 内存库，进程结束即销毁）
- JDK：`C:\Program Files\Java\jdk-18.0.1.1`

> **数据合规说明**：本轮 5 例主诉与回答全部为**合成文本**，不含任何真实患者信息；所称「真实就诊数据」指**经由生产同款 HTTP 链路 `/api/consult/start → answer → finish` 真实产生并落业务库的结构化就诊记录**，与 `DemoDataSeeder` 手工填齐字段的演示就诊严格区分。业务库只存结构化字段 + 去标识化摘要，不存原始问诊对话。

## 结论先行

- 核验 **74 项通过 / 0 项失败**；首轮评测曾以 3 项失败暴露真实数据缺口，修复后本轮全绿（详见「四、发现与处置」）。
- 开关语义：`MEDICAL_LEARNING_AUTO` 只控制**每日 02:00 的 `@Scheduled` 批处理**；审核台的手动 `POST /api/admin/kb/candidates/generate` 不受它限制（设计如此）。本轮用手动入口触发「那一轮」，同时用随报告回传的 `autoGenerateEnabled` 硬证开关注入值。
- 真实链路落库：主诉 / 科室 / 去标识化摘要均非空；`assessment`/`plan` 按合规为空。
- 候选生成：首轮 generated=5 / deduplicated=0 / conflict=0 / pendingTotal=7；指纹去重、冲突挂起、审核闸门、版本回滚均按设计工作。
- 配套回归：`tools/e2e_stack.py` 已补 **7 项真实链路落库断言**（74 → 81 项，防演示数据假绿）；全量 `run_checks.py --web --java` **14 项套件全绿**（含后端真库测试与前端构建）。
- **数据资格限制与结构性闸门（本轮最重要的结论）**：预问诊摘要按合规不含医师结论，本轮产出的候选正文是「患者自述 + 分诊建议」，**不具备作为机构回答依据的资格**。★ 2026-11-05 已把「医师确认后的判断/处置」链路补齐：坐诊台结束会话时由接诊医师填写判断/处置，`Encounter.source=PHYSICIAN_CONFIRMED`（Flyway V5），候选继承该判据；`approve()` 对未确认候选**硬拒 422**（`NOT_PHYSICIAN_CONFIRMED`），与文字护栏、与 `MEDICAL_LEARNING_AUTO` 开关都无关 —— 误开开关也只会生成候选，不会污染知识库。本轮已核验「预问诊候选一律被拒且库/版本分毫不动」。**生产仍保持关闭**：代码侧闸门已就位，但临床药师复核、器械分类界定、等保等机构流程尚未完成（见 `docs/COMPLIANCE-GATE.md`），这些不是代码能替代的。
- 表中回答为合成文本、按问诊树顺序填入，语义与要素名未必对应（如「体温：两天前开始的」）；本轮只验**数据通路与字段完整性**，不验临床语义正确性。

## 一、就诊落库（5 例真实链路）

| ref | 输入主诉 | 落库主诉 | 科室 | 紧急度 | 判断/处置 | 摘要（截断） |
|---|---|---|---|---|---|---|
| eval-fever-01 | 发烧两天了 | 发热 | 发热门诊 | routine | （空·合规） | 主诉：发热；体温：两天前开始的；热型：三十八度五；部位：有点咳嗽；性质：没有；程度：无；分诊建议：发热门诊 / 感染科； |
| eval-cough-02 | 咳嗽三天 | 咳嗽 | 呼吸内科 | routine | （空·合规） | 主诉：咳嗽；痰：三天前；部位：干咳为主；性质：晚上更明显；程度：没有；诱因：无；分诊建议：呼吸内科； |
| eval-abdomen-03 | 肚子疼 | 腹痛 | 消化内科 | routine | （空·合规） | 主诉：腹痛；转移：肚脐周围；排便：一阵一阵的；部位：昨天开始的；性质：没有；程度：无；分诊建议：消化内科 / 普外科； |
| eval-dizzy-04 | 头晕 | 头晕 | 神经内科 | routine | （空·合规） | 主诉：头晕；性质：站起来的时候；部位：天旋地转；程度：今天早上；诱因：没有；持续时间：无；分诊建议：神经内科 / 耳鼻喉科； |
| eval-chest-05 | 胸口有点闷 | 胸痛 | （空） | emergency | （空·合规） | 主诉：胸痛；放射：往左肩和后背串； |

## 二、候选池产出

| 标题 | 科室 | 状态 | confidence | 来源 | 内容（截断） |
|---|---|---|---|---|---|
| 胸痛 | （空） | PENDING | 0.5 | daily-batch | 【预问诊采集·非医师结论，不得作为诊疗依据】主诉：胸痛；放射：往左肩和后背串； |
| 头晕 | 神经内科 | PENDING | 0.5 | daily-batch | 【预问诊采集·非医师结论，不得作为诊疗依据】主诉：头晕；性质：站起来的时候；部位：天旋地转；程度：今天早上；诱因：没有；持续时间：无；分诊建议：神经内科 / 耳鼻喉科； |
| 腹痛 | 消化内科 | PENDING | 0.5 | daily-batch | 【预问诊采集·非医师结论，不得作为诊疗依据】主诉：腹痛；转移：肚脐周围；排便：一阵一阵的；部位：昨天开始的；性质：没有；程度：无；分诊建议：消化内科 / 普外科； |
| 咳嗽 | 呼吸内科 | PENDING | 0.5 | daily-batch | 【预问诊采集·非医师结论，不得作为诊疗依据】主诉：咳嗽；痰：三天前；部位：干咳为主；性质：晚上更明显；程度：没有；诱因：无；分诊建议：呼吸内科； |
| 发热 | 发热门诊 | PENDING | 0.5 | daily-batch | 【预问诊采集·非医师结论，不得作为诊疗依据】主诉：发热；体温：两天前开始的；热型：三十八度五；部位：有点咳嗽；性质：没有；程度：无；分诊建议：发热门诊 / 感染科； |

## 三、逐项核验

| 结果 | 核验项 | 失败详情 |
|---|---|---|
| PASS | 找到按 mtime 最新的后端 fat jar（>10MB） |  |
| PASS | 认知服务 /healthz 且 custom_routes=15 |  |
| PASS | 主后端启动（MEDICAL_LEARNING_AUTO=true） |  |
| PASS | 对照后端启动（未设 MEDICAL_LEARNING_AUTO） |  |
| PASS | 主后端 /actuator/health = UP |  |
| PASS | 超管 staff 登录成功 |  |
| PASS | 科室管理员 deptadmin 登录成功 |  |
| PASS | 反例对照：未设 MEDICAL_LEARNING_AUTO 时 autoGenerateEnabled=false |  |
| PASS | start 成功并拿到问诊树首问：发烧两天了 |  |
| PASS | finish 产出《预问诊摘要》（chief_complaint 非空）：发烧两天了 |  |
| PASS | 业务库落库：列表 title 非空（不是兜底「就诊记录」）：发烧两天了 |  |
| PASS | 业务库落库：列表 summary 非空：发烧两天了 |  |
| PASS | 结构化详情 chiefComplaint 非空：发烧两天了 |  |
| PASS | 合规：assessment / plan 为空（预问诊不出诊断结论）：发烧两天了 |  |
| PASS | 摘要不含「判断：/处置：」等伪造结论：发烧两天了 |  |
| PASS | 摘要是去标识化结构化事实（含「主诉：」）：发烧两天了 |  |
| PASS | 常规路径 department 来自 triage.suggested_department：发烧两天了 |  |
| PASS | start 成功并拿到问诊树首问：咳嗽三天 |  |
| PASS | finish 产出《预问诊摘要》（chief_complaint 非空）：咳嗽三天 |  |
| PASS | 业务库落库：列表 title 非空（不是兜底「就诊记录」）：咳嗽三天 |  |
| PASS | 业务库落库：列表 summary 非空：咳嗽三天 |  |
| PASS | 结构化详情 chiefComplaint 非空：咳嗽三天 |  |
| PASS | 合规：assessment / plan 为空（预问诊不出诊断结论）：咳嗽三天 |  |
| PASS | 摘要不含「判断：/处置：」等伪造结论：咳嗽三天 |  |
| PASS | 摘要是去标识化结构化事实（含「主诉：」）：咳嗽三天 |  |
| PASS | 常规路径 department 来自 triage.suggested_department：咳嗽三天 |  |
| PASS | start 成功并拿到问诊树首问：肚子疼 |  |
| PASS | finish 产出《预问诊摘要》（chief_complaint 非空）：肚子疼 |  |
| PASS | 业务库落库：列表 title 非空（不是兜底「就诊记录」）：肚子疼 |  |
| PASS | 业务库落库：列表 summary 非空：肚子疼 |  |
| PASS | 结构化详情 chiefComplaint 非空：肚子疼 |  |
| PASS | 合规：assessment / plan 为空（预问诊不出诊断结论）：肚子疼 |  |
| PASS | 摘要不含「判断：/处置：」等伪造结论：肚子疼 |  |
| PASS | 摘要是去标识化结构化事实（含「主诉：」）：肚子疼 |  |
| PASS | 常规路径 department 来自 triage.suggested_department：肚子疼 |  |
| PASS | start 成功并拿到问诊树首问：头晕 |  |
| PASS | finish 产出《预问诊摘要》（chief_complaint 非空）：头晕 |  |
| PASS | 业务库落库：列表 title 非空（不是兜底「就诊记录」）：头晕 |  |
| PASS | 业务库落库：列表 summary 非空：头晕 |  |
| PASS | 结构化详情 chiefComplaint 非空：头晕 |  |
| PASS | 合规：assessment / plan 为空（预问诊不出诊断结论）：头晕 |  |
| PASS | 摘要不含「判断：/处置：」等伪造结论：头晕 |  |
| PASS | 摘要是去标识化结构化事实（含「主诉：」）：头晕 |  |
| PASS | 常规路径 department 来自 triage.suggested_department：头晕 |  |
| PASS | start 成功并拿到问诊树首问：胸口有点闷 |  |
| PASS | finish 产出《预问诊摘要》（chief_complaint 非空）：胸口有点闷 |  |
| PASS | 业务库落库：列表 title 非空（不是兜底「就诊记录」）：胸口有点闷 |  |
| PASS | 业务库落库：列表 summary 非空：胸口有点闷 |  |
| PASS | 结构化详情 chiefComplaint 非空：胸口有点闷 |  |
| PASS | 合规：assessment / plan 为空（预问诊不出诊断结论）：胸口有点闷 |  |
| PASS | 摘要不含「判断：/处置：」等伪造结论：胸口有点闷 |  |
| PASS | 摘要是去标识化结构化事实（含「主诉：」）：胸口有点闷 |  |
| PASS | 开关生效：主后端 autoGenerateEnabled=true |  |
| PASS | ★ 真实就诊产出候选（generated > 0）—— 修复前恒为 0 |  |
| PASS | 新增候选条数 == generate 报告的 generated |  |
| PASS | 新增候选全部来自 daily-batch（不是演示数据） |  |
| PASS | 候选标题 = 真实就诊主诉 |  |
| PASS | 合规：候选内容不含患者 ref / 姓名 / 伪造的判断处置 |  |
| PASS | 无医师结论时 confidence = 0.5（启发式下限，不作临床可信度） |  |
| PASS | ★ 合规护栏：无医师结论候选自带「非医师结论」警示 |  |
| PASS | ★ 生成候选绝不自动写 knowledge_docs |  |
| PASS | 生成候选不改知识池版本 |  |
| PASS | 二次生成 generated = 0（幂等） |  |
| PASS | 二次生成 deduplicated = 首轮 generated（指纹去重生效） |  |
| PASS | ★ 真实预问诊候选全部标记为非医师确认 |  |
| PASS | ★ generate 报告如实区分可采纳 / 不可采纳条数 |  |
| PASS | 至少 2 条 PENDING 供闸门/驳回对照 |  |
| PASS | ★ 合规闸门：预问诊候选采纳被 422 拒绝（不是 409/403） |  |
| PASS | ★ 被拒后患者自述绝不进 knowledge_docs |  |
| PASS | ★ 被拒后知识池版本不变（闸门没偷偷 bump） |  |
| PASS | 驳回成功且绝不入库 |  |
| PASS | ★ 科室管理员（发热门诊）能看到本科室真实候选 |  |
| PASS | ★ 同标题异结论候选被挂起（conflict = 1） |  |
| PASS | 冲突候选带 conflictWith 指向现库资料，不覆盖现库 |  |

## 四、发现与处置（真实数据暴露，演示数据掩盖不了）

1. **真实问诊落库是空壳（已修复）**：认知侧《预问诊摘要》返回 `chief_complaint` / `present_illness` / `triage.suggested_department`，且按合规**不含** `assessment` / `plan`；旧 Java 落库读驼峰 `chiefComplaint` 等 → 全为 null，候选池 `title.isEmpty() → continue` → 修复前真实数据恒产出 **0 条**候选。`DemoDataSeeder` 手工填齐字段，既有 74 项联调因此一直全绿。   修法：`MedicalProxyController.consultFinish` 按真实响应对齐字段名、补 `department`，`summaryText` 由 `summaryOf` 构造去标识化结构化摘要（不编造结论、不存原始对话）。
2. **科室范围错配（已修复）**：`triage.suggested_department` 是复合值（`发热门诊 / 感染科`），而 `Identity.department` 是单科室（`发热门诊`）；`KbCandidateService.list` 用精确 `equals` 收窄 → 科室管理员对本轮真实候选可见 **0 条**。   修法：业务层落库取主科室（第一段）用于授权归类，完整建议仍原样保留在摘要里；分诊规则本身仍在认知侧，Java 不重写（薄转发铁律）。
3. **红旗中断路径没有 `suggested_department`（如实标注，未改）**：`ConsultSession.triage()` 命中红旗时只返回 `urgency/advice/reason`，故该就诊 `department` 为空、其候选按设计仅超管可见。这是认知侧既有结构，不是落库丢字段。
4. **候选数据资格不足（★ 已结构性根治，不再是「待办」）**：旧的文字护栏【预问诊采集·非医师结论】只是给人看的，超管点一下「采纳」就能绕过 —— 这是比不设防更危险的假校验。修法分三层：① 就诊显式标 `source`（Flyway V5），演示数据填齐判断/处置也只标 `DEMO`；② 候选继承`physicianConfirmed`；③ `approve()` 作为唯一生效入口硬拒未确认候选（422）。本轮已核验：预问诊候选采纳被拒、库与知识池版本分毫不动；医师确认候选可采纳（后端真库正例）。生产开关仍关闭是**机构流程**未完，不是代码缺口。

### 运行时观察

- 红旗中断路径：triage 只给 urgency/advice/reason，不含 suggested_department，落库 department 为空（本次实际值=''）。该就诊产生的候选科室为空，按审核台规则仅超管可见 —— 这是认知侧既有结构，不是落库丢字段。

## 五、复现

```bash
cd E:/AI/pasm/code/pasm-medical/backend
JAVA_HOME='C:\Program Files\Java\jdk-18.0.1.1' 'D:\Program Files\apache-maven-3.8.6\bin\mvn.cmd' -q -DskipTests package
cd ..
python tools/eval_real_learning.py --out docs/REAL-LEARNING-EVAL.md
```
