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
| 前端 | `web/` | Vue 3 + TS + Vite | ✅ 三栏工作台 + 医护后台，**全部接通真实后端**（构建通过） |
| 业务层 | `backend/` | Spring Boot 3 / Java 17 | ✅ 已编译、已启动、三端联调全过；JPA 持久化（患者/就诊/审计）+ 真实管理接口 |
| 认知服务 | `pasm_medical/` | Python ≥3.9，零第三方依赖 | ✅ 已实现并端到端验证 |
| MCP sidecar | `pasm_medical/mcp/` | Python 零依赖 stdio | ✅ 已建并验证（selftest 19 项 + e2e 15 项） |

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

| 模块 | 现在的实际状态 | 待补 |
|---|---|---|
| 患者情况 | ✅ 列表：ref / 姓名 / 过敏 / 慢病 / 就诊次数 / 最近就诊的科室与分诊（真实接口） | 患者**详情下钻**（摘要全文、历次就诊）；患者档案的新增与编辑 |
| 资料库 | ✅ 真实的**增 / 改 / 上下架 / 删**（`/api/admin/kb`）；业务库权威 + 认知侧检索副本全量同步；带依据问答真的会引用它，下架即不再被引用。详见 §3.5 | 按科室过滤资料（`department` 字段已留）；外部资料批量导入 |
| 统计 | ✅ 已接真实数据，口径见下 | 分诊准确率（要"医生最终科室 vs 系统推荐"的对照数据，未开始） |
| 审计 | ✅ **写事件 + 读事件**，**每条都带操作者**；问题原文随 `input_snapshot` 落库并**已下发到面板**（超 300 字截断，`inputTruncated` 标明） | 登出与登录失败；审计查询本身留痕；防篡改（哈希链 / 只读库）与导出 |
| 对接设置 | ✅ 真读写业务库，并与认知服务实际生效值对账（`drift`） | LIS / HIS / EMR 的真实适配层 |

> 后台整体要求 **`ROLE_STAFF`**：它能读出全院患者的过敏史，还能改「大模型指向何处」
> （决定患者数据会不会出网）。患者令牌访问 `/api/admin/**` 一律 403。

**统计口径**（`/api/admin/stats`，2026-09-22 修正）：

| 指标 | 口径 |
|---|---|
| 一次问诊 | = 一条 **`consult-start`** 审计事件。不是 `ask`（一次问诊要问十几轮，按问题数算会虚高几倍），也不是 `consult-finish`（红旗中断或中途关页面就没有 finish，按它算会漏） |
| 今日窗口 | 本机时区当天 00:00 起；窗口起止随响应返回（`windowFrom` / `windowTo` / `zone`），便于核对 |
| 拒答率 | 今日窗口内：无依据被拒的问题数 ÷ 今日全部问题数 |
| 采纳率 | **累计** 采纳 ÷（采纳 + 否决）—— 单日医生反馈样本太小，天天在 0% / 100% 之间跳没有意义 |
| 红旗命中 | `consult-redflag` 事件数（今日与累计都给） |

> ★ 修正前的实现是「取最近 200 条审计 → 在内存里筛今天，并且把 `ask` 与 `consult-finish` 都算一次」，
> 有两个错：**审计超过 200 条就静默封顶**、**一次问诊被重复计数** —— 都不报错，只给出一个看着合理的假数字。
> 现在 `tools/e2e_stack.py` 有专门的反例断言盯着：**先灌 250 条审计，再起一次问诊，要求计数精确 +1**
> （把实现改回旧逻辑时该断言**精确转红**：`before=0 after=0`，其余 31 项保持绿）。

**审计事件表**（`ai_audit`，append-only）：

| 动作 | 写入方 | `input_snapshot` 里是什么 |
|---|---|---|
| `login` | 登录 | `username=…;role=…` |
| `read-patient` / `read-encounters` / `read-encounter` / `read-timeline` | 查看患者档案 / 历史就诊 / 单次就诊详情 / 认知时间轴 | `ref=…`（就诊详情还带 `encounterId=…`） |
| `read-admin-patients` | 后台拉**全院患者名单** | `scope=all` |
| `ask` | 带依据问答 | **`question=…`（患者问题原文，⚠️ 含患者自述内容）** |
| `consult-start` / `consult-answer` / `consult-finish` / `consult-redflag` | 预问诊链路 | `path=/api/consult/…` |
| `lab-parse` / `lab-confirm` | 检验单识别 / 确认 | `path=/api/lab/…` |
| `critical-fact` / `feedback-*` | 关键事实登记 / 医生对 AI 输出的处置 | `title=…` / `kind=…;action=…` |
| `config-update` | 改对接设置 | `ocr=…;lis=…;llm=…;model=…;baseUrl=…` |

> ★ **读事件也必须带操作者**：不带 actor 的读审计等于没记 —— 面板上"操作者"列永远是空的，
> 出了泄露根本回答不了"是谁看的"。实现上由 `AuditService.currentActor()` 统一取
> （优先用传入的认证对象，否则从 `SecurityContextHolder` 取），避免新增接口时漏传。
> 读审计是**先记再看**：即使随后返回 404（ref 不存在 / 不属于该患者），这次"试图查看"也已留痕
> —— IDOR 探测恰恰靠这个发现。

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

**★ 期望值 ≠ 生效值，界面必须说清楚。** 后台「对接设置」存的是**机构期望**
（"我们想用 ollama + qwen2.5:7b"），而**真正跑模型的在 Python 侧**，由
`PASM_MEDICAL_LLM*` 环境变量决定。两边不一致时，界面会一直显示"已配置"，
而患者实际拿到的是模板话术 —— 这种假象比没有配置项更有害。所以：

- 认知服务暴露只读的 `GET /api/config`（**不探活**，只报进程真实配置）；
- 业务层 `GET /api/admin/config` 把它取回，与期望值比对，不一致就 `drift=true` 并在界面顶格提示；
- 认知服务不可达时 `drift=null`（**未知**）——不做"看起来没事"的乐观假设。

---

### 3.5 资料库（回答依据从哪来）

「带依据问答」的依据**只可能来自两处**，而且必须能说清是哪一处：

| 来源 | 存在哪 | 谁能改 | 返回时怎么标 |
|---|---|---|---|
| **机构资料库**（指南 / 规则 / 术语 / 科室手册） | 业务库 `knowledge_docs`（权威）+ 认知侧检索副本 | 后台「资料库」页（`/api/admin/kb`，限 `ROLE_STAFF`） | `sources[].kind = "doc"` |
| **患者档案与记忆**（过敏史 / 就诊记录 / 关键事实） | 认知层**按患者分片**的实例 | 随访 / 检验单确认 / 关键事实登记 | `sources[].kind = "memory"` |

**判定规则（相关性闸门）**：问题词元必须落在资料的**标题或标签**上，这条资料才算依据。
**正文不参与判定** —— 正文里的偶然词重合正是"答非所问"的来源（问"术后康复"，只因为某篇资料正文里提过
"康复"就把它当依据）。返回空依据是**有意义的结论**：本次拒答（`refused=true`），不用"可能/或许"软化。

> ★ 所以**标签是语义字段，不是装饰**：想让某类提问能命中一份资料，就得把那个词写进标题或标签。

**为什么权威库在业务库，而不是直接用框架的 `knowledge_base` 插件**：
那个插件只有 `ingest`（追加），**没有删除和编辑**；而资料必须能下架 / 改版 ——
「下架的资料还被当成依据引用」在医疗场景是事故，不能靠"再追加一版"绕过（旧版永远是依据）。
所以业务库是权威（带状态 / 版本 / 复核人 / 审计），认知侧只留一份"当前生效资料"的检索副本；
**同步是全量替换**：每次写操作后推当前生效的全集，认知侧先清掉本项目写入的条目再重灌。

**两道保险**（都实测过，见 §6 的断言）：

1. **认知侧清理**（`pasm_medical.service._purge_medical_docs`）：只清 `source` 前缀为本项目的 `doc` 条目，
   不碰自学习沉淀的 QA。⚠️ 它读了插件的内部字段（框架没有公开的 remove API），缺字段时返回 `-1`
   并把 `purgeSupported=false` 回报给界面 —— **不静默当成清理成功**。
   正解是给框架加公开的 `remove_source()`（TODO）。
2. **业务库"生效集合"过滤**：问答时把当前 active 的资料键传给认知侧，检索结果再按它过滤。
   于是**即使清理失败、旧资料还留在检索库里，下架的资料也不会被引用**。
   实测：资料仍在库中、生效集合为空 → 拒答。

> 界面会**如实显示**同步漂移：`sync.synced=false` 时提示"已存库，但同步检索副本失败：<原因>"。
> 「库里改了、检索侧没生效」这种漂移必须当场可见，否则会以为改了资料就有用。

---

## 四、启动（本地开发）

### 4.1 前置

| 依赖 | 版本 | 本机实测 |
|---|---|---|
| Python | ≥ 3.9 | ✅ 3.13.12 |
| Node.js | ≥ 18 | ✅ 22.22.2 + npm 10.9.7 |
| **JDK** | **17+**（Spring Boot 3 硬要求） | ✅ **JDK 17**（`D:\Program Files\Java\jdk-17`，编后端 / 跑服务）<br>✅ **JDK 18**（`C:\Program Files\Java\jdk-18.0.1.1`，跑 `tools/e2e_stack.py`） |
| **Maven** | 3.8+ | ✅ **3.8.6**（`D:\Program Files\apache-maven-3.8.6`） |

> ⚠️ **JDK 8 跑不了本项目**。Spring Boot 3.x 的最低要求是 Java 17；
> 本机另有 JDK 8 / JRE 8 / JDK 12，但它们只能跑旧项目，不能编译本后端。
> `pom.xml` 里 `java.version` 定为 **17**（不是 21）就是为了匹配本机实际可用的 JDK。

#### ★★ 不设 `JAVA_HOME` 会「静默」用 JDK 8（2026-09-22 实测踩到，先看这段）

`JAVA_HOME` 为空时 Maven 回落到 PATH 上第一个 `java`；本机那是 Oracle 的转发目录
`C:\Program Files (x86)\Common Files\Oracle\Java\javapath\java.exe` → 实为 **`jre1.8.0_221`**。
而 maven-compiler-plugin 会**静默忽略** `release 17`（**连一条 WARNING 都没有**），
javac 8 就按默认源级 8 解析，**只在用了新语言特性的那几行报语法错** —— 看起来像源码写坏了：

```
[ERROR] .../service/SystemConfigService.java:[54,9]  非法的表达式开始   ← public record Settings(…) 被当成"返回 record 类型的方法"
[ERROR] .../service/SystemConfigService.java:[54,41] 需要';'
[ERROR] .../web/AdminController.java:[183,47]        需要')'            ← x instanceof Map<?,?> mm 模式匹配（Java 16+）不识别
[ERROR] .../web/AdminController.java:[183,48]        不是语句
[ERROR] .../web/AdminController.java:[183,50]        需要';'
```

**判据**：报错集中在 `record` / `instanceof <类型> <变量>` 这类**新语言特性**上时，
**先跑 `mvn -version` 看 `Java version`**，**不要去改源码**（改了才是真坏）。
同一份源码、同一个 pom，只换 Maven 所用的 JDK：

| Maven 运行在 | 结果 |
|---|---|
| JDK 8（`jre1.8.0_221`） | 上述 5 条语法错 + `BUILD FAILURE` |
| JDK 12（本机 `D:\Program Files\Java\jdk-12.0.2`） | 57 条语法错（同样不行） |
| **JDK 17** | **`BUILD SUCCESS`**，产物字节码 `major=61` |

**设置（按终端选一段；`JAVA_HOME` 必须置于 PATH 最前，否则 javapath 仍抢先）**

```bash
# ① Git Bash
export JAVA_HOME="D:/Program Files/Java/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
mvn -version          # ★ 必须显示 Java version: 17.x
```

```powershell
# ② Windows PowerShell
$env:JAVA_HOME = "D:\Program Files\Java\jdk-17"
$env:PATH = "$env:JAVA_HOME\bin;" + $env:PATH
mvn -version          # ★ 必须显示 Java version: 17.x
```

```bat
:: ③ Windows CMD
set JAVA_HOME=D:\Program Files\Java\jdk-17
set PATH=%JAVA_HOME%\bin;%PATH%
mvn -version          :: ★ 必须显示 Java version: 17.x
```

### 4.1.1 Maven 的两个坑（本机实测）

1. **`mvn` 启动脚本处理不了带空格的安装路径**（`D:\Program Files\...`）。
   直接 `mvn` 会报 `找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`。
   **首选解法**：改用同目录的 **`mvn.cmd`**（绕开那个处理不了空格的 shell 脚本），并显式给 `JAVA_HOME`：

   ```bash
   # Git Bash（★ 先跑 mvn -version，确认 Java version 是 17.x）
   export JAVA_HOME="D:/Program Files/Java/jdk-17"
   export PATH="$JAVA_HOME/bin:/d/Program Files/apache-maven-3.8.6/bin:$PATH"
   "/d/Program Files/apache-maven-3.8.6/bin/mvn.cmd" -DskipTests package
   ```
   > 2026-09-22 实测：**同一条 `mvn.cmd` 命令**，`JAVA_HOME` 指 JDK 17 → `BUILD SUCCESS`；
   > 指 JDK 8 → §4.1 那 5 条语法错。

   **兜底解法**：绕过脚本，直接让 Java 加载 Maven 的 launcher（等价且稳定）：

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
# ★ pasm-framework 必须 >=0.5.3 —— 0.5.2 缺 register_route，装上会「启动即拒」
pip install "pasm-skills>=0.6.2" "pasm-framework>=0.5.3"
pip install -e .
```

**★ 先选终端**：`export` / `$(...)` 是 **bash** 语法。在 Windows PowerShell 里照抄会报
`无法将"export"项识别为 cmdlet、函数、脚本文件或可运行程序的名称` —— 那不是装错了，是**终端不对**。
下面三段是同一件事的三套写法，**按你的终端选一段**：

**① Git Bash / WSL / macOS / Linux**
```bash
export PASM_MEDICAL_TOKEN="$(python -c 'import secrets;print(secrets.token_urlsafe(24))')"
python -m pasm_medical.service --tenant demo --port 8090
```

**② Windows PowerShell**（5.1 / 7 均可；注意 5.1 不支持 `&&`）
```powershell
$env:PASM_MEDICAL_TOKEN = (python -c "import secrets;print(secrets.token_urlsafe(24))")
python -m pasm_medical.service --tenant demo --port 8090
```

**③ Windows CMD**
```bat
for /f "delims=" %T in ('python -c "import secrets;print(secrets.token_urlsafe(24))"') do set PASM_MEDICAL_TOKEN=%T
python -m pasm_medical.service --tenant demo --port 8090
```

> 嫌麻烦也可以直接给 `--token` 传字面值（仅本机联调；生产走密钥管理注入，不入库不入镜像）：
> `python -m pasm_medical.service --tenant demo --port 8090 --token dev-only-token`

**探活**
```bash
curl -s http://127.0.0.1:8090/healthz
curl -s -H "Authorization: Bearer $PASM_MEDICAL_TOKEN" \
     http://127.0.0.1:8090/api/cog/capabilities
```

> ★ **PowerShell 里 `curl` 是 `Invoke-WebRequest` 的别名**，`-H` / `-d` / `-s` 语义完全不同
> （常见症状：`-H` 报「找不到与参数名称"H"匹配的参数」）。PowerShell 里**必须写 `curl.exe`**：
> ```powershell
> curl.exe -s http://127.0.0.1:8090/healthz
> curl.exe -s -H "Authorization: Bearer $env:PASM_MEDICAL_TOKEN" http://127.0.0.1:8090/api/cog/capabilities
> ```

★ **`/healthz` 看 `custom_routes`**：必须 **> 0**（正常 12 条医疗路由）。`healthy` 只是插件级状态，
**路由挂没挂它看不出来** —— 医疗接口全 404 时它照样报 `healthy`（2026-09-22 踩过，见 §10 分发形态）。

### 4.3 启动前端（**已验证可构建**）

```bash
cd web
npm install
npm run dev          # http://127.0.0.1:5173
npm run build        # 产物在 web/dist/
```

`vite.config.ts` 已把 `/api` 代理到 `http://127.0.0.1:8081`（业务层）。

### 4.4 启动业务层（**已验证可编译、可启动、可联调**）

> ★ 编译/运行需 **JDK 17+**：先 `mvn -version` 确认（见 §4.1）；打包用 `mvn -DskipTests package`。

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

#### 4.4.1 测试账号（**仅开发档**）

`application-dev.yml` 显式打开 `demo-login-enabled: true` 时可用：

| 角色 | 账号 | 密码 | 登录后落地页 | 用途 |
|---|---|---|---|---|
| 医护人员 | `staff` | `123456` | `/#/admin` | 患者列表 / 就诊时间轴 / 统计 / AI 审计 / 对接设置（改大模型、OCR 指向） |
| 患者 | `patient` | `123456` | `/#/consult` | 预问诊、检验单回显确认、历史就诊；数据由 `bootstrap/DemoDataSeeder` 灌入 |

登录页 `http://127.0.0.1:5173/#/login`。**`npm run dev` 时页面上直接显示这两个账号，点一下自动填入**
（实现见 `web/src/views/LoginView.vue` 的 `DEMO_HINT` 门控）；`npm run build` 的生产产物里
**既不含该提示、也不含 `123456` 字样**。

> ★ **坑：模板里的 `v-if` 只挡「渲染」，挡不住字面量。** 最初写成
> `<p v-if="isDev">医护人员 staff / 123456</p>`，运行时确实不显示，但
> `grep -r 123456 web/dist` → **3 处命中**：字符串照样进 render 函数（`v-if` 编译成运行时三元）。
> 正确写法是把**标题与条目一起**放进脚本层常量：`import.meta.env.DEV ? {...} : { title:'', items:[] }`
> —— `vite build` 会把条件折叠成 `false`，整段字面量被摇掉。
>
> **验证（正反都要）**：
> ```bash
> grep -r 123456 web/dist | wc -l                              # 生产：必须 0
> NODE_ENV=development <vite> build --outDir dist-devcheck     # 正例对照：必须 >0（3 处）
> ```
> 注意 `vite build --mode development` **不能**让 `DEV=true`（build 时 `NODE_ENV` 仍是 production），
> 必须显式 `NODE_ENV=development` —— 否则正例对照恒为 0，看着"通过了"其实只是没测到。

两条**反例断言**（在 `tools/e2e_stack.py` 里，改鉴权时别让它们变绿）：

- 患者令牌访问 `/api/admin/**` 必须 **403**；
- `--medical.auth.demo-login-enabled=false` 时，这两个账号必须**登不进去**。

生产必须接医院统一身份（OIDC/OAuth2），见 `config/TokenService.java` 的注释与 §5.2。

### 4.5 三端联调（**一键验证整条链路**）

```bash
# 前置：后端已打包（★ 需 JDK 17+，先 mvn -version 自检；见 §4.1）
cd backend && mvn -DskipTests package

# 真起两个服务、走真实 HTTP、跑完自动清理
python tools/e2e_stack.py
```

它会验证：认知服务健康 → 业务层健康 → **未登录 401** → 登录 → **薄转发到问诊树
（胸痛首问必须是「放射」）** → **红旗中断** → 检验单待确认 → 时间轴 →
**业务层持久化**（患者档案 / 历史就诊 / 后台患者列表 / 统计 / 审计）→
**就诊结构化详情 + 归属校验** → **对接设置读写 / 对账 / 白名单校验** → **角色越权 403**。

**39 项全过**代表三端真的接上了。其中 7 项是**反例**（故意构造的越权/非法输入）：
就诊归属不符必须 404（挡 IDOR）、非法枚举值必须 400、患者令牌访问后台必须 403、
`demo-login-enabled=false` 时写死的账号必须登不进去。

另有 3 项是**"改回旧实现就必须转红"的断言**（它们盯的是"不报错但结果错"的缺陷）：

| 断言 | 改回旧实现会怎样 |
|---|---|
| 灌 250 条审计后，今日问诊量仍按 `consult-start` 精确 +1 | 旧实现（最近 200 条内存筛）掉到 **0** → 转红 |
| 查看患者档案留痕，且 `actor` 正确 | 去掉读审计埋点 → **命中 0 条** → 转红 |
| `ask` 的 `inputSnapshot` 含问题原文并下发到后台 | 不下发该字段 → **命中 0 条** → 转红 |

> ★ 这 3 条的断言都写成"**None 安全 + 命中计数**"：字段缺失或没记上时给出一条**清晰的失败**，
> 而不是在断言表达式里抛 `TypeError`/`KeyError` 把整个套件带崩 —— 崩溃只会让人以为
> "脚本坏了"，看不出是"口径/埋点错了"。

> 脚本会**并发**多起一个后端进程（演示账号关闭）来跑最后那条反例 ——
> 只看代码不看行为，正是"默认开着、生产忘了关"这类事故的成因。

### 4.6 一键启动脚本（Windows，`tools/demo/`）

把 §4.2~§4.4 的三条命令封装起来，免去手工设令牌、手工设 `JAVA_HOME`、对齐三个终端：

```bat
tools\demo\start-demo.bat              :: 起三个服务；已在监听的端口自动跳过
tools\demo\start-demo.bat --check      :: 只做前置检查，不启动任何进程
tools\demo\start-demo.bat --no-open    :: 不自动打开浏览器
tools\demo\stop-demo.bat               :: 按端口停掉三个服务
tools\demo\stop-demo.bat --check       :: 只看端口状态，不杀进程
```

它替你做的检查，每一条都对应一个"手工做容易踩"的点：

| 检查 | 为什么需要 |
|---|---|
| Python 能 `import pasm_medical` | 该检查**从仓库外的目录执行**：`python -c` 会把当前目录放进 `sys.path`，在仓库根跑会"通过"而其实根本没装（实测踩到的假绿） |
| **JDK ≥ 17** | Spring Boot 3 的硬要求。读 `java -version` 比对主版本，JDK 8 / 12 会被**明确拦下**并说清怎么改，不会拿着旧 JDK 硬跑出一堆莫名错误 |
| Node + `web/node_modules/vite` | 缺了前端会白屏 |
| 端口占用 | 已在监听的端口**跳过**，所以可以反复双击，不会起两份 |
| 令牌一致 | 认知服务与业务层必须同一个令牌；不设 `PASM_MEDICAL_TOKEN` 时用内置 dev 默认值（**仅本机演示**，生产走密钥管理） |
| 健康锚点 | 启动后打印 `:8090/healthz` 的 **`custom_routes`**（必须 > 0）——只看"服务活着"看不出医疗接口挂没挂 |

可用的环境变量覆盖（都不设也能跑，路径按 `PASM_MEDICAL_*` → `.venv` → `venv` → `PATH` 的顺序探测）：
`PASM_MEDICAL_PYTHON`、`PASM_MEDICAL_JAVA`、`PASM_MEDICAL_NODE`、`PASM_MEDICAL_TOKEN`。
运行时数据落在 `<repo>\.demo\`（已进 `.gitignore`）。

> ★★ **改这两个 .bat 之前必读：格式契约**
> 文件必须保持 **纯 ASCII + CRLF 行尾**，并且**不要出现跨行 `( ... )` 块**（用 `goto` 标签代替）。
> 原因：cmd.exe 按**控制台代码页**解析 .bat，且对行尾很敏感。2026-09-22 实测踩到过 ——
> 文件存成 UTF-8 且是 LF 行尾时，中文注释按 GBK 解码成乱码、半截中文被当成命令执行，
> **后续所有行连环错位**，双击后满屏 `'xxx' is not recognized as an internal or external command`。
> 消息刻意写成英文：这是公开仓库，控制台代码页随地区变（936 / 950 / 932 / 1252 / 65001）。
>
> 三道保险：① 本节的书写约定；② `.gitattributes` 的 `*.bat text eol=crlf`（保证检出即 CRLF，
> 不依赖各人本地的 `core.autocrlf`）；③ 校验脚本（**含 4 个反例对照**，证明检查项不是空的）：
> ```bash
> python tools/check_demo_launchers.py           # 静态契约 + 反例对照 + 真跑 --check
> python tools/check_demo_launchers.py --fix     # 顺手把孤立 LF 规范成 CRLF
> ```

---

## 五、部署（生产）

### 5.1 关键配置

| 变量 | 说明 |
|---|---|
| `PASM_MEDICAL_TOKEN` | **管理令牌**。认知接口能写记忆/改人格，泄露=后台被接管。从密钥管理注入，**不入库、不入镜像** |
| `PASM_COGNITION_URL` | 业务层访问认知服务的地址 |
| `PASM_MEDICAL_LLM` | `ollama` / `openai` / `null` |
| `PASM_MEDICAL_LLM_BASE_URL` / `_MODEL` / `_API_KEY` | 对应后端参数 |
| `MEDICAL_AUTH_DEMO_LOGIN` | 写死的演示账号开关，**默认 false**。只有 dev 档显式打开；生产接 OIDC/JWT 后才有人能登录 |
| `MEDICAL_DB_URL` / `_USER` / `_PASSWORD` | PostgreSQL（需要 JSONB + pgvector） |

### 5.2 三条部署红线

1. **认知服务不要直接暴露公网**。它只在业务层之后；管理令牌不下发浏览器。
   若必须暴露，前面必须放网关做鉴权 + 限流（默认网关是标准库 `http.server`，
   生产建议放 Nginx 之后或换 uvicorn）。
2. **数据落地分区**：认知实例按「租户 + 患者」分片（`pasm_medical/domain.py`）。
   不分片会跨患者串库 —— 在医疗场景是**数据泄露**。
3. **审计库独立且 append-only**（只授予 INSERT），与业务库物理隔离。

另有一条**默认就是安全**的设计：演示账号默认关闭（`MEDICAL_AUTH_DEMO_LOGIN=false`）。
为什么强调"默认"：写死的 `staff/123456` 一旦跟着生产部署，任何人都能以医护身份
读走全院患者数据。这类事故的共同成因都是"默认开着、忘了关"。默认关闭意味着
**忘记配置的后果是登不进去**（可发现、可恢复），而不是数据泄露（不可发现、不可恢复）。

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
RUN pip install --no-cache-dir "pasm-skills>=0.6.2" "pasm-framework>=0.5.3" -e .
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

# MCP sidecar 自检（24 项：协议 / 工具 / 患者隔离 / LLM 配置对账）
python -m pasm_medical.mcp.server --selftest

# 认知服务端到端（真起 HTTP，17 项）
python tools/e2e_medical_service.py

# ★ 三端联调（认知服务 + Spring Boot + 前端契约，39 项；含 7 项越权/非法输入反例）
#   前置：cd backend && mvn -DskipTests package
python tools/e2e_stack.py

# ★ 反例对照（5 项）—— 故意改坏必须被抓到
python tools/falsify_medical_service.py

# 前端：类型检查 + 构建
cd web && npm run build
```

**为什么一定要跑反例对照**：模块自检全绿**不等于**断言有效 —— 断言可能写空了。
本项目的反例脚本会故意改坏 5 处（隔离失效 / 闸门失效 / 红旗规则失效 /
确认闸门被拆 / 主诉硬猜），每一处都必须被抓到，跑完自动还原。

业务层的安全判据同样做过**反向验证**：把就诊归属校验、后台角色限制、
配置漂移判定分别故意改坏 → `tools/e2e_stack.py` 的对应 3 项**确实转红**（其余 28 项仍绿），
还原后回到 31/31。只报"全绿"而不报"改坏能被抓到"，等于没验证。

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

**Q：后台「对接设置」保存了，但模型还是没生效？**
先看表单下面那块「认知服务实际生效」。后台存的是**机构期望值**，真正跑模型的在
Python 进程里，由 `PASM_MEDICAL_LLM*` 环境变量决定。两者不一致时界面会显示
`drift = true` 并顶格提示 —— 按提示改环境变量并**重启认知服务**才会生效。
把「期望」当「已生效」是这块最容易骗到人的地方，所以专门做了对账。

**Q：非 dev 档登录报"账号或密码不正确"？**
演示账号（`staff`/`patient`，密码 `123456`）默认**关闭**，只有 dev 档打开
（`medical.auth.demo-login-enabled`）。这是刻意的：写死的口令不能跟着生产上线。
生产接 OIDC/OAuth2 或签名 JWT 后才会有人能登录。

---

## 八、当前边界（务必知道）

| 项 | 状态 |
|---|---|
| Python 认知服务（问诊树 / 检验单 / 护栏 / 隔离） | ✅ 已实现并验证（88 + 17 + 5 项） |
| MCP stdio sidecar（`pasm_medical/mcp/`） | ✅ 已建并验证（selftest 24 项 + 三端 e2e 39 项）；复用 `MedicalService` 门面，不启 HTTP 零端口污染 |
| Spring Boot 业务层 | ✅ 已编译、已启动、三端联调全过；JPA 持久化（患者 / 就诊 / 审计 / **对接设置**）+ 真实管理接口（`/api/admin/*`、`/api/patient`、`/api/patient/encounters`、`/api/patient/encounter/{id}`、`/api/admin/config`）；**后台限 `ROLE_STAFF`** |
| 前端 | ✅ 三栏工作台 + 医护后台，**全部接通真实后端**（登录 / 问诊 / 检验单 / 反馈 / 患者档案 / 历史就诊详情 / 后台患者·统计·审计·对接设置），构建通过、零 TS 错误 |
| 影像 | ❌ 不做分析，只归档 + 转交 |
| 合规 | ❌ 未做医疗器械分类界定；规则表**未经临床药师复核** |

> ⚠️ **尚未做到的一条（如实标注，不假装已解决）**：`/api/patient/**` 目前只校验
> 「已认证」，患者令牌理论上可以换 `ref` 去读别人的就诊记录。要修必须先有
> 「令牌 → 该患者 ref」的映射，而当前演示账号没有这个映射
> （用户名 `patient` 与 ref `demo-patient-001` 对不上）。生产接 OIDC 时应把 ref
> 绑定进令牌声明（claim），届时在过滤器里强制覆盖请求参数即可。
> 就诊详情接口（`/api/patient/encounter/{id}`）**已经**做了归属校验 —— 因为它的 id 是自增整数，
> 不校验就等于把全院病历开放出去，属于必须当场堵住的越权。

> **代码开源 ≠ 可合法用于临床诊断。** 见 [LICENSE](../LICENSE) 与
> [FEASIBILITY.md](FEASIBILITY.md) §2。
