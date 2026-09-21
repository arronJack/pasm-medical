# pasm-medical 运作说明与功能说明

> 面向：部署者 / 开发 / 运维。读完这份应该能把它跑起来、知道每个功能在做什么、
> 以及**哪些地方是"能上生产"、哪些是"还没验证"**。

---

## 一、它是什么（一句话）

**面向患者的互联网智能预问诊系统**：患者登录后与 AI 对话、上传检验单，
系统按标准问诊要素把病史问全、把检验指标读明白、给出**就诊建议**，
并把结果整理成《预问诊摘要》交给医师。

**它不做什么**（这几条是合规与安全底线，写在代码里，不是提示词）：

| 不做 | 原因 |
|---|---|
| ❌ 不下诊断结论 | 面向患者的诊断属医疗器械（SaMD），且触碰「AI 不得替代医师本人提供诊疗服务」 |
| ❌ 不开处方 / 不给用药建议 | 同上；用药只做**规则核对**（配伍禁忌、剂量上限），不产出方剂 |
| ❌ 不解读影像（X光/CT） | 通用视觉大模型会**自信地编造影像所见**；真正能读片的模型须三类医疗器械注册证。影像**只归档 + 转交** |

---

## 二、系统组成

```
┌─────────────┐   ┌────────────────────┐   ┌──────────────────────┐
│  Vue 3 前端 │──►│  Spring Boot 业务层 │──►│  Python 认知服务(PASM)│
│  :5173      │   │  :8081             │   │  :8090               │
└─────────────┘   └────────────────────┘   └──────────────────────┘
  登录/三栏工作台      患者·病历·权限·审计        记忆/情绪/知识库
  医护后台            检验单·预问诊编排           相关性闸门/问诊树
```

**调用方向单向**：`前端 → 业务层 → 认知服务`。
**前端绝不直连认知服务** —— 认知接口的令牌是**管理令牌**（能写记忆、改人格），
一旦下发到浏览器就等于把后台公开。

| 组件 | 目录 | 技术栈 | 状态 |
|---|---|---|---|
| 前端 | `web/` | Vue 3 + TS + Vite | 骨架（登录/三栏/后台），**本机已构建验证** |
| 业务层 | `backend/` | Spring Boot 3 / Java 21 | 骨架 + 认知客户端，**未编译验证**（开发机无 JDK） |
| 认知服务 | `pasm_medical/` | Python ≥3.9，零第三方依赖 | 已实现并端到端验证 |

---

## 三、功能说明

### 3.1 患者端（三栏工作台）

```
┌──────────────┬────────────────────────┬──────────────────┐
│ 左栏          │ 中间                    │ 右栏              │
│ ·患者头像/信息 │ ·聊天（可传图片/文件）    │ ·病情分析          │
│ ·历史问询列表  │ ·AI 追问（逐题引导）      │  - 异常指标解读    │
│  （每次问询    │ ·上传检验单 → 回显确认    │  - 可能相关的情况  │
│   =一次对话）  │                        │  - 需要补充的信息  │
│              │                        │  - ⚠️ 危险信号     │
│              │                        │  - 建议就诊科室    │
└──────────────┴────────────────────────┴──────────────────┘
```

**智能预问诊（核心功能）** —— 规则驱动的问诊树：

1. 患者自由描述 → 识别**主诉**（识别不出就让选，**绝不硬猜**）
2. 按**现病史七要素**逐题追问：部位 / 性质 / 程度 / 诱因 / 持续时间 / 缓解加重 / 伴随症状
3. **红旗筛查**（硬规则，优先级最高）：胸痛+大汗+放射痛、霹雳样头痛、黑便呕血、
   高热伴意识改变、呼吸困难 → **立即中断问诊、直接建议急诊**
4. 既往史 / 用药史 / **过敏史**（问清后永久记住，复诊不再重问）
5. 产出《预问诊摘要》（**不含"诊断"字段**，只有分诊建议）

> 问的是**规则决定的**，不是模型决定的。可评测（覆盖率）、可审计（每个问题带 `why`）、
> 可迭代（漏了症状加节点即可，不用重训模型）。LLM 只负责把问题说得自然些。

**检验单识别** —— OCR 是主路径（按决定②：无 LIS/HIS 对接）：

```
上传图片 → 质控 → OCR → 表格还原 → 项目字典对齐
        → 参考区间判读（纯规则）→ 危急值判定
        → ★ 回显给用户确认 → 才写入病历
```

三条安全设计：
- **未确认不得入记忆**：`confirmed` 默认 `False`，`to_memory()` 直接抛错 —— 让"忘了确认就入库"在代码层不可能
- **参考区间以单据印的为准**，用字典兜底时标 `range_source=default`（区间因机构/仪器/人群而异）
- **危急值硬编码**，独立于参考区间判定，触发即强制建议就医（不受对话流程影响）

### 3.2 医护后台

| 模块 | 内容 |
|---|---|
| 患者情况 | 按患者查看《预问诊摘要》+ 对话记录 + 检验单 + 依从性 |
| 资料库 | 知识源 / 规则表 / 术语字典，**带版本与复核人** |
| 统计 | 问诊量 / 分诊准确率 / 拒答率 / 红旗命中 / AI 建议采纳率 |
| 审计 | 每次 AI 输出的输入快照 + 召回依据 + 模型版本 + 操作者，**只增不改** |
| 对接设置 | OCR 引擎 / LIS-HIS（预留）/ 大模型（本地或 API） |

### 3.3 「无依据必须拒答」

语义检索对**任何**问题都会返回结果（只是分数不同）。直接拿弱命中当依据，
就会答非所问。所以有一道**相关性闸门**：命中的词必须落在资料的**标题或标签**上，
否则**拒答**（如实说"查不到"，不用"可能/或许"软化）。

> 为什么不用分数阈值：打分带时间衰减，阈值会让资料库**越老越失忆**；
> 且真命中（6.00）与假命中（2.29）区间重叠，调不出稳的阈值。

### 3.4 大模型可插拔

`ollama`（本地，数据不出院）/ `openai 兼容 API`（云端）/ **`null`（无 LLM）**。

**无 LLM 也能跑**——不是凑合：模型不可用时必须**降级而非报错**。
问题原样呈现、摘要退化为结构化文本，**功能完整、话术朴素**。
模型抛异常或输出跑偏时回退到原问题，绝不把跑偏内容发给患者。

---

## 四、启动（本地开发）

### 4.1 前置

| 依赖 | 版本 | 本机实测 |
|---|---|---|
| Python | ≥ 3.9 | ✅ 3.13.12 |
| Node.js | ≥ 18 | ✅ 22.22.2 + npm 10.9.7 |
| **JDK** | **17+**（Spring Boot 3 硬要求） | ✅ **JDK 18**（`C:\Program Files\Java\jdk-18.0.1.1`） |
| **Maven** | 3.8+ | ✅ **3.8.6**（`D:\Program Files\apache-maven-3.8.6`） |

> ⚠️ **JDK 8 跑不了本项目**。Spring Boot 3.x 的最低要求是 Java 17；
> 本机另有 JDK 8 / JRE 8，但它们只能跑旧项目，不能编译本后端。
> `pom.xml` 里 `java.version` 定为 **17**（不是 21）就是为了匹配本机实际可用的 JDK。

### 4.1.1 Maven 的两个坑（本机实测）

1. **`mvn` 启动脚本处理不了带空格的安装路径**（`D:\Program Files\...`）。
   直接 `mvn` 会报 `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。
   **解法**：绕过脚本，直接让 Java 加载 Maven 的 launcher（等价且稳定）：

   ```bash
   export JAVA_HOME='C:\Program Files\Java\jdk-18.0.1.1'
   M='C:\Users\xiaozhi\AppData\Local\Temp\maven386'   # 见下面第 2 条
   java -classpath "$M\boot\plexus-classworlds-2.6.0.jar" \
        "-Dclassworlds.conf=$M\bin\m2.conf" "-Dmaven.home=$M" \
        "-Dmaven.multiModuleProjectDirectory=<项目路径>" \
        org.codehaus.plexus.classworlds.launcher.Launcher -DskipTests package
   ```

2. **给 java 的路径要用 Windows 形式**（`C:\...`）。在 Git Bash 里用 `/c/...`
   会被 MSYS 做路径转换，classpath 直接失效。
   （若不想每次写这一长串，可把 Maven 复制到无空格目录，例如
   `C:\Users\xiaozhi\AppData\Local\Temp\maven386`。）

### 4.2 启动认知服务（**已验证可跑**）

```bash
# 安装依赖（基座 + 应用框架）
pip install "pasm-skills>=0.6.2" "pasm-framework>=0.5.2"
pip install -e .

# 起服务（管理令牌务必设置，认知接口全靠它）
export PASM_MEDICAL_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(24))')"
python -m pasm_medical.service --tenant demo --port 8090

# 探活
curl -s http://127.0.0.1:8090/healthz
curl -s -H "Authorization: Bearer $PASM_MEDICAL_TOKEN" \
     http://127.0.0.1:8090/api/cog/capabilities
```

### 4.3 启动前端（**已验证可构建**）

```bash
cd web
npm install
npm run dev          # http://127.0.0.1:5173
npm run build        # 产物在 web/dist/
```

`vite.config.ts` 已把 `/api` 代理到 `http://127.0.0.1:8081`（业务层）。

### 4.4 启动业务层（**已验证可编译、可启动、可联调**）

```bash
cd backend

# 开发档：用 H2 内存库，**不需要任何外部数据库**就能跑起来
# （打包后用 java -jar 也行，见 tools/e2e_stack.py 的做法）
java -jar target/pasm-medical-backend-0.1.0.jar \
  --spring.profiles.active=dev \
  --server.port=8081 \
  --pasm.cognition.base-url=http://127.0.0.1:8090 \
  --pasm.cognition.token=<与认知服务同一个管理令牌>
```

演示账号（**仅开发档**）：`patient / 123456`、`staff / 123456`。
生产必须接医院统一身份（OIDC/OAuth2），见 `config/TokenService.java` 的注释。

### 4.5 三端联调（**一键验证整条链路**）

```bash
# 前置：后端已打包
cd backend && mvn -DskipTests package

# 真起两个服务、走真实 HTTP、跑完自动清理
python tools/e2e_stack.py
```

它会验证：认知服务健康 → 业务层健康 → **未登录 401** → 登录 → **薄转发到问诊树
（胸痛首问必须是「放射」）** → **红旗中断** → 检验单待确认 → 时间轴。
**10 项全过**代表三端真的接上了。

---

## 五、部署（生产）

### 5.1 关键配置

| 变量 | 说明 |
|---|---|
| `PASM_MEDICAL_TOKEN` | **管理令牌**。认知接口能写记忆/改人格，泄露=后台被接管。从密钥管理注入，**不入库、不入镜像** |
| `PASM_COGNITION_URL` | 业务层访问认知服务的地址 |
| `PASM_MEDICAL_LLM` | `ollama` / `openai` / `null` |
| `PASM_MEDICAL_LLM_BASE_URL` / `_MODEL` / `_API_KEY` | 对应后端参数 |
| `MEDICAL_DB_URL` / `_USER` / `_PASSWORD` | PostgreSQL（需要 JSONB + pgvector） |

### 5.2 三条部署红线

1. **认知服务不要直接暴露公网**。它只在业务层之后；管理令牌不下发浏览器。
   若必须暴露，前面必须放网关做鉴权 + 限流（默认网关是标准库 `http.server`，
   生产建议放 Nginx 之后或换 uvicorn）。
2. **数据落地分区**：认知实例按「租户 + 患者」分片（`pasm_medical/domain.py`）。
   不分片会跨患者串库 —— 在医疗场景是**数据泄露**。
3. **审计库独立且 append-only**（只授予 INSERT），与业务库物理隔离。

### 5.3 云端 LLM 的合规前提

用云端 API 时，**患者数据出网前必须去标识化**（`domain.scrub_identifiers`）。
这不是建议，是合规要求。更稳的做法是用本地模型（`ollama`）。

### 5.4 容器化（参考）

```dockerfile
# 认知服务（无外部依赖，镜像很小）
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml README.md ./
COPY pasm_medical ./pasm_medical
RUN pip install --no-cache-dir "pasm-skills>=0.6.2" "pasm-framework>=0.5.2" -e .
ENV PASM_MEDICAL_LLM=null
EXPOSE 8090
CMD ["python", "-m", "pasm_medical.service", "--host", "0.0.0.0", "--port", "8090"]
```

> ⚠️ 上面这个 Dockerfile 只覆盖认知服务。业务层与前端的镜像**尚未编写**，
> 因为业务层还没编译验证过。

---

## 六、自检与验证

```bash
# 模块自检（88 项）—— 零依赖，随时可跑
python -m pasm_medical.domain      # 14 项：患者隔离规则、去标识
python -m pasm_medical.safety      # 16 项：三条红线
python -m pasm_medical.lab         # 24 项：检验单解析 + 确认闸门
python -m pasm_medical.consult     # 23 项：问诊树 + 红旗中断
python -m pasm_medical.llm         # 11 项：三档 LLM 网关

# 端到端（真起 HTTP 服务，17 项）
python tools/e2e_medical_service.py

# ★ 反例对照（5 项）—— 故意改坏必须被抓到
python tools/falsify_medical_service.py
```

**为什么一定要跑反例对照**：模块自检全绿**不等于**断言有效 —— 断言可能写空了。
本项目的反例脚本会故意改坏 5 处（隔离失效 / 闸门失效 / 红旗规则失效 /
确认闸门被拆 / 主诉硬猜），每一处都必须被抓到，跑完自动还原。

前端：

```bash
cd web && npm run build      # 类型检查 + 构建，必须无错误
```

---

## 七、常见问题

**Q：认知服务起来了，但 `/api/cog/*` 返回 503？**
认知层没装上。`pip install -U "pasm-skills>=0.6.2"`。`/api/cog/capabilities` 会给出具体原因。

**Q：前端调接口一直 401？**
业务层没带管理令牌。认知接口**全部落在管理作用域**（默认行为），
业务层需在 `PasmCognitionClient` 里配 `pasm.cognition.token`。

**Q：患者 A 的检验单出现在 B 的记录里？**
不该发生 —— 隔离由 `agent_id_for(tenant, patient_ref)` 保证，并有端到端断言。
真出现了请立刻上报：说明某个入口绕过了 `MedicalService` 的 agent_id 封装。

**Q：为什么系统说"查不到"而不给个答案？**
相关性闸门判定没有够格的依据。这是**设计行为** —— 医疗场景里，
"答得漂亮但没依据"比"答不上来"危险得多。

**Q：危急值提示能关掉吗？**
不能。硬编码规则 + 强制升级，不受对话流程影响。

---

## 八、当前边界（务必知道）

| 项 | 状态 |
|---|---|
| Python 认知服务（问诊树 / 检验单 / 护栏 / 隔离） | ✅ 已实现并验证（88 + 17 + 5 项） |
| 前端 | ⚠️ 骨架，构建通过；**功能尚未与后端联调** |
| Spring Boot 业务层 | ⚠️ **未编译验证**（开发机无 JDK）；登录鉴权 / 领域实体 / 审计 / 定时任务待补 |
| 影像 | ❌ 不做分析，只归档 + 转交 |
| 合规 | ❌ 未做医疗器械分类界定；规则表**未经临床药师复核** |

> **代码开源 ≠ 可合法用于临床诊断。** 见 [LICENSE](../LICENSE) 与
> [FEASIBILITY.md](FEASIBILITY.md) §2。
