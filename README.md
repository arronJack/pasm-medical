# pasm-medical · 医院智能诊疗辅助系统

> **定位：医生的副驾驶，不是 AI 医生。**
> 本系统输出「线索 + 依据 + 建议核对方向」，**不下诊断结论、不自动生成处方**，
> 全部输出带 `requiresPhysicianConfirmation=true`，最终判断权与责任在医师。

---

## 这是什么

一个 **Spring Boot + Vue** 的诊疗辅助系统，认知能力（长期记忆、共情沟通、受控自学习）
由独立的 **Python 认知服务**（基于 [PASM](https://gitee.com/arronzheng/pasm-skills)）提供。

```
Vue 3 前端  ──►  Spring Boot 业务层  ──►  Python 认知服务（PASM）
（左栏时间轴/    （患者·就诊·病历·         （记忆分层 / 情绪 / 知识库
  对话区）        处方·权限·审计）           / 相关性闸门 / 受控学习）
```

**调用方向单向**：前端绝不直连认知服务 —— 认知接口的令牌是**管理令牌**
（能写记忆、改人格、触发巩固），一旦下发到浏览器就等于把后台公开。

## 仓库结构

```
pasm-medical/
├── backend/                # Spring Boot 3 / Java 17+ —— 业务主干
│   └── src/main/java/com/pasm/medical/
│       ├── MedicalApplication.java
│       ├── CognitionProperties.java
│       ├── cognition/PasmCognitionClient.java   # ★ 认知服务客户端（唯一入口）
│       ├── domain/                              # 患者 / 就诊 / 审计 / 对接设置 实体
│       ├── repository/ service/                 # JPA 仓储 + 服务（含白名单校验）
│       └── web/                                 # AuthController / AssistController
│                                                # MedicalProxyController（薄转发）
│                                                # AdminController（后台，限 ROLE_STAFF）
├── web/                    # Vue 3 + TS —— 诊疗工作台
│   └── src/views/          # LoginView / ConsultView（三栏）/ AdminView（后台）
├── pasm_medical/           # Python 认知服务侧（独立包，可单独 pip 安装）
│   ├── domain.py           # 患者/就诊领域模型 + 患者级隔离规则
│   ├── safety.py           # ★ 医疗安全护栏（红线写成代码）
│   ├── llm.py              # 三档 LLM 网关（ollama / openai / null）
│   ├── mcp/                # MCP stdio sidecar（复用同一门面，零端口污染）
│   └── service.py          # 服务装配（多租户 + 患者隔离）
├── tools/                  # 端到端验证与反例对照
├── docs/
│   ├── PLAN.md             # 完整开发方案（分层/合规/路线图/验收）
│   ├── FEASIBILITY.md      # ★ 可行性评估：哪些能做、哪些要设边界、哪些不该自研
│   └── GUIDE.md            # ★ 运作说明与功能说明（启动 / 部署 / 配置 / 排障）
├── pyproject.toml          # 打包 pasm_medical（可 pip 安装）
└── LICENSE                 # MIT
```

> 知识源（西医 / 中医指南、规则表、术语字典）**不放在仓库里**：它们随机构不同，
> 且规则表必须经临床药师复核后随院内变更单发布。运行时用 `--dir` 指定知识库落点
> （见 `docs/GUIDE.md` §4.2）。后台「资料库」页展示的是这些知识源的**版本与复核人**。

## 三条不能碰的红线

这三条**由代码保证**，不依赖模型自觉（见 `pasm_medical/safety.py`）：

| 红线 | 落点 |
|---|---|
| 不得自动生成处方 / 医嘱 | 只做**规则核对**（配伍禁忌 / 妊娠 / 剂量上限），返回违例清单 |
| 无依据不得作答 | **相关性闸门**：命中的词必须落在资料的标题或标签上；否则**拒答**，不用"可能/或许"软化 |
| 确定性计算不交给 LLM | 十八反 / 十九畏 / 妊娠禁忌 / 毒性剂量上限全部是**规则表比对** |

> ⚠️ 规则表是药典与教材的**经典内容**，用于初版落地，**未经临床药师复核**。
> 投产前必须逐条复核并与院内前置审核系统对齐。`safety.table_meta()` 会把
> 版本与复核人暴露出来，避免"谁改的、依据哪版"无人知晓。

## 患者隔离（用测试证明，不是"应该隔离了"）

PASM 的知识库与记忆**默认落点是全机共享**的（不给目录就跨实例串库）。
在通用场景那只是串味，在医疗场景是**数据泄露**。所以：

- 知识库按**租户（机构/科室）**分片；
- 认知实例按**患者**分片（`agent_id_for(tenant, patient_ref)`）；
- 真实标识不入认知层：姓名/手机号/身份证在业务库，认知层只存**假名化后的引用**；
- `tools/e2e_medical_service.py` 里断言：造两个患者、各写一条互不相同的记忆，
  **彼此一条都看不到**，且噪声灌入 30 轮后关键事实仍可召回。

## 快速开始

```bash
# 1) Python 认知服务（先装依赖）
#    ★ pasm-framework 必须 >=0.5.3：0.5.2 没有 register_route，装上会「启动即拒」（见 docs/GUIDE.md §4.2）
pip install "pasm-skills>=0.6.2" "pasm-framework>=0.5.3"
pip install -e .
PASM_MEDICAL_TOKEN=<管理令牌> python -m pasm_medical.service --port 8090

# 2) Spring Boot 后端（dev 档 = H2 内存库 + 演示账号，无需外部数据库）
#    ★ 需 JDK 17+：先 `mvn -version` 确认打出来的是 17.x。JAVA_HOME 未设时 Maven 会
#      静默用本机 JDK 8，报一堆看着像源码坏了的语法错（见 docs/GUIDE.md §4.1）
cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev   # http://127.0.0.1:8081
#    演示账号（仅 dev）：staff / 123456（后台）、patient / 123456
#    注意：非 dev 档演示账号默认关闭，且需要 PostgreSQL

# 3) Vue 前端
cd web && npm install && npm run dev       # http://127.0.0.1:5173
```

> 🖱️ **Windows 一键启动**（不想逐条敲上面的命令时）：双击仓库里的
> `tools\demo\start-demo.bat`。它会替你自检 Python（能 `import pasm_medical`）、
> **JDK 17+（太旧会直接拦下并说清怎么改）**、Node、前端依赖，跳过已在监听的端口，
> 拉起三个服务并打开工作台；停止双击 `tools\demo\stop-demo.bat`。
> 详见 [`docs/GUIDE.md`](docs/GUIDE.md) §4.6。

> 生产档不接受写死的演示账号 —— 必须接入医院统一身份（OIDC/OAuth2）或签名 JWT。
> 这是刻意的默认值，见 `docs/GUIDE.md` §5.2。

## 测试账号（仅 `dev` 档，开箱即用）

`dev` 档 = H2 内存库 + 演示数据播种，**不用注册、不用装数据库**，登录即可看到真实接口的数据：

| 角色 | 账号 | 密码 | 登录后落地页 | 看得到什么 |
|---|---|---|---|---|
| **医护人员** | `staff` | `123456` | `/#/admin` 医护后台 | 患者列表、就诊时间轴、统计、AI 审计、对接设置（改大模型 / OCR 指向） |
| **患者** | `patient` | `123456` | `/#/consult` 诊疗工作台 | 示例患者档案（女 46 岁 · 青霉素过敏 · 高血压 3 级）与 2 条历史就诊 |

入口 `http://127.0.0.1:5173`（跑法见上面「快速开始」第 2、3 步）。

- 两个账号密码相同、**权限不同**：患者令牌打 `/api/admin/**` 一律 **403**（`tools/e2e_stack.py` 有这条断言）。
- 开发模式（`npm run dev`）下登录页**直接提示**这两个账号，点一下即自动填入；
  `npm run build` 的产物里**不含**该提示（也没有 `123456` 字样，可用 grep 产物验证）。
- `dev` 之外**默认关闭**（`medical.auth.demo-login-enabled` 默认 `false`）——
  不是"上线前记得关"，而是本来就关着。

自检与端到端：

```bash
python -m pasm_medical.domain            # 领域模型（隔离规则 / 去标识）
python -m pasm_medical.safety            # 护栏（红线）
python -m pasm_medical.mcp.server --selftest   # MCP sidecar，24 项
python tools/e2e_medical_service.py      # 真起 HTTP 服务，17 项
python tools/e2e_stack.py                # 三端联调，39 项（需先 mvn package）
python tools/falsify_medical_service.py  # 反例对照：故意改坏必须被抓到
python tools/check_demo_launchers.py     # 启动器脚本的编码/行尾契约 + 真跑 --check
```

## 现状与后续

| 部分 | 状态 |
|---|---|
| **智能问诊树（`consult.py`）** | ✅ 已实现并验证（23 项）：主诉识别、现病史七要素、**红旗中断**、覆盖率、摘要、分诊 |
| **检验单解析（`lab.py`）** | ✅ 已实现并验证（24 项）：OCR→结构化→规则判读→**回显确认**；未确认不得入记忆 |
| **LLM 网关（`llm.py`）** | ✅ 已实现并验证（11 项）：本地 Ollama / OpenAI 兼容 API / **无 LLM 降级**三档 |
| 领域与安全（`domain.py` / `safety.py`） | ✅ 已实现并验证（14 + 16 项） |
| 认知服务与患者隔离 | ✅ 已实现并验证（端到端 17 项 + 反例对照 5 项） |
| **前端（`web/`）** | ✅ **构建通过**（vue-tsc 类型检查 + vite）；登录页 / 三栏工作台 / 医护后台，全部接通真实后端 |
| **Spring Boot 业务层** | ✅ **编译 + 启动 + 三端联调通过**（JDK 18 / Maven 3.8.6）；登录鉴权（默认关闭演示账号）/ 薄转发 / 认知客户端；**JPA 持久化**（患者 / 就诊 / 审计 / 对接设置）+ 管理接口 `/api/admin/*`、`/api/patient/*` |
| **授权与越权防护** | ✅ 后台限 `ROLE_STAFF`（患者令牌 403）；就诊详情做**归属校验**（挡 IDOR）；对接设置**服务端白名单校验** |
| **审计与统计** | ✅ 审计 append-only，**读 + 写双向事件且每条都带操作者**；患者问题原文随 `input_snapshot` 落库并可在后台查看；统计口径明确（口径定义随接口返回，避免"看着合理的假数字"），口径与埋点都有"改回旧实现即转红"的断言盯着 |
| **资料库（回答依据）** | ✅ 后台可**新增 / 编辑 / 上下架 / 删除**；业务库是权威、认知侧只放"当前生效资料"的检索副本（全量同步）；**下架的资料不再作为任何回答的依据**。带依据问答走医疗侧的**相关性闸门**（命中词必须落在标题或标签上），没依据就拒答 —— 闸门与资料库都有正反断言盯着 |
| 三端联调 | ✅ `tools/e2e_stack.py` **39 项全过**（含 7 项越权/非法输入反例；**8 项判据已做反向验证**：3 个安全判据 + 3 个统计口径/审计埋点 + 2 个资料库链路） |
| 影像归档 | 未开始（P5，**只做归档与转交，不做分析**） |
| 中医知识库 | 未开始（见 `docs/PLAN.md` §6） |

**已验证的规模**：模块自检 **88 项** + MCP selftest **24 项** + 端到端 **17 项** + 反例对照 **5 项** + 三端联调 **39 项**（全部通过）。

> **启动、部署、配置、排障**见 [`docs/GUIDE.md`](docs/GUIDE.md)；
> **功能说明**（每个功能在做什么、边界在哪）也在同一份文档里。

**完整的开发方案**（合规红线、数据模型、自学习闭环、验收标准、分期路线图）
见 [`docs/PLAN.md`](docs/PLAN.md)。

**"这些功能到底能不能做出来"** —— 逐项可行性判定（能做 / 要设边界 / 不建议自研）、
检验单解析的安全设计、智能追问引擎的做法、完整站点结构（登录 / 患者端三栏 / 医护后台）
见 [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md)。

> 一句话结论：**能，而且不用赌任何未成熟技术。**
> 唯一要放弃的是"AI 自己读片下结论"——换成"归档 + 接已有资质厂商 + 结构化转交"，
> 临床价值几乎不损失，风险降一个量级。

## 开源与许可

本项目为**开源公开仓库**（MIT）：

- Gitee：https://gitee.com/arronzheng/pasm-medical
- GitHub：https://github.com/arronJack/pasm-medical

许可见 [LICENSE](LICENSE)（MIT）。

> ⚠️ **许可范围提示**：MIT 覆盖的是**代码**。但把这个系统用于实际诊疗场景，
> 涉及的是**医疗器械合规**（见 [`docs/FEASIBILITY.md`](docs/FEASIBILITY.md) §2）——
> 代码开源不等于可以合法用于临床诊断。尤其是**影像自动诊断**那一项，
> 本仓库**没有**也不打算实现（不建议自研，需三类医疗器械注册证）。
>
> 仓库内 `pasm_medical/safety.py` 的规则表（十八反 / 十九畏 / 妊娠禁忌 / 毒性剂量）
> 是药典与教材的经典内容，用于**演示与初版落地**，**未经临床药师复核**。
> 投产前必须逐条复核并与本机构前置审核规则对齐。
