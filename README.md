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
├── backend/                # Spring Boot 3 / Java 21 —— 业务主干
│   └── src/main/java/com/pasm/medical/
│       ├── MedicalApplication.java
│       ├── CognitionProperties.java
│       ├── cognition/PasmCognitionClient.java   # ★ 认知服务客户端（唯一入口）
│       └── web/AssistController.java            # ★ 给前端的接口
├── web/                    # Vue 3 + TS —— 诊疗工作台
│   └── src/App.vue         # ★ 左栏就诊时间轴 + 右侧对话区
├── pasm_medical/           # Python 认知服务侧（独立包，可单独 pip 安装）
│   ├── domain.py           # 患者/就诊领域模型 + 患者级隔离规则
│   ├── safety.py           # ★ 医疗安全护栏（红线写成代码）
│   └── service.py          # 服务装配（多租户 + 患者隔离）
├── tools/                  # 端到端验证与反例对照
├── docs/
│   ├── PLAN.md             # 完整开发方案（分层/合规/路线图/验收）
│   └── FEASIBILITY.md      # ★ 可行性评估：哪些能做、哪些要设边界、哪些不该自研
└── knowledge/              # 知识源（西医 / 中医），带版本清单
```

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
pip install "pasm-skills>=0.6.2" "pasm-framework>=0.5.2"
pip install -e .
PASM_MEDICAL_TOKEN=<管理令牌> python -m pasm_medical.service --port 8090

# 2) Spring Boot 后端
cd backend && mvn spring-boot:run          # http://127.0.0.1:8081

# 3) Vue 前端
cd web && npm install && npm run dev
```

自检与端到端：

```bash
python -m pasm_medical.domain            # 领域模型（隔离规则 / 去标识）
python -m pasm_medical.safety            # 护栏（红线）
python tools/e2e_medical_service.py      # 真起 HTTP 服务，17 项
python tools/falsify_medical_service.py  # 反例对照：故意改坏必须被抓到
```

## 现状与后续

| 部分 | 状态 |
|---|---|
| **智能问诊树（`consult.py`）** | ✅ 已实现并验证（23 项）：主诉识别、现病史七要素、**红旗中断**、覆盖率、摘要、分诊 |
| **LLM 网关（`llm.py`）** | ✅ 已实现并验证（11 项）：本地 Ollama / OpenAI 兼容 API / **无 LLM 降级**三档 |
| 领域与安全（`domain.py` / `safety.py`） | ✅ 已实现并验证（14 + 16 项） |
| 认知服务与患者隔离 | ✅ 已实现并验证（端到端 17 项 + 反例对照 4 项） |
| Spring Boot 业务层 | ⚠️ **骨架 + 认知客户端**，未编译验证（开发机无 JDK）；登录鉴权 / 领域实体 / 审计 / 每日学习任务待补 |
| Vue 前端 | ⚠️ 骨架（左栏时间轴 + 对话区 + 采纳/修改/否决），未构建验证；登录页 / 右侧分析栏 / 医护后台待补 |
| 检验单 OCR + 回显确认 | 未开始（P2） |
| 影像归档 | 未开始（P5，**只做归档与转交，不做分析**） |
| 中医知识库 | 未开始（见 `docs/PLAN.md` §6） |

**已验证的规模**：模块自检 **64 项** + 端到端 **17 项** + 反例对照 **4 项**（全部通过）。

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
