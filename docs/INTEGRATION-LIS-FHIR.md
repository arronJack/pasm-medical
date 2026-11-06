# LIS / HL7 / FHIR 对接

本文只写**已实现**的部分，以及明确的边界。2026-11-06 补上 **MLLP TCP 直连 +
HL7 ACK^R01** 后，仍未实现的是 LOINC/SNOMED 映射、ORU 之外的报文等，见 §7。

## 1. 四种能力

| 能力 | 端点 | 鉴权 | 说明 |
|---|---|---|---|
| HL7 v2 ORU 投递（HTTP） | `POST /api/integration/lis/oru` | `X-LIS-Token` | 接收 `ORU^R01`，落 `lab_results` |
| **HL7 v2 ORU 直连（MLLP/TCP）** | TCP `:2575`（默认关闭） | HL7 `ZAT` 段 + 共享密钥 | 同上 + 回 `ACK^R01`，见 §3.6 |
| FHIR Observation 投递 | `POST /api/integration/fhir/observations` | `X-LIS-Token` | 接收 `Observation` 或 `Bundle(collection)` |
| FHIR 只读投影 | `GET /api/fhir/**` | 用户 JWT | Patient / Encounter / Observation |

> ★ HTTP 与 MLLP **共用** `LisIngestService.ingest()`：机构配置校验、未知患者拒收、
> 幂等落库只有一份判定逻辑，不会出现「HTTP 拒了、TCP 却收了」的漂移。

投递与读取的鉴权**刻意分离**：LIS 只该有"写检验结果"的权限。给它一个医护 JWT，
等于让它能读全院病历。

## 2. 启用步骤

1. 生成共享密钥（≥32 字节）：
   ```bash
   openssl rand -base64 48
   ```
2. 注入环境变量并重启后端：
   ```bash
   export MEDICAL_LIS_TOKEN='<上一步的值>'
   ```
   **未配置 = 通道关闭**（返回 503）。不会以"不校验"的方式开放。
3. 在后台把 `lis` 设为 `hl7` 或 `fhir`（`/api/admin/config`）。
   - `off`：两条通道都关（409）
   - `hl7`：只开 HL7，FHIR 投递返回 409
   - `fhir`：只开 FHIR 投递，HL7 返回 409

   **不会同时开**：同一份结果从两条线进来，幂等键不同会变成双份数据。

## 3. HL7 投递

```bash
printf 'MSH|^~\\&|LIS|HOSP|EMR|HOSP|20261104120000+0800||ORU^R01|MSG-001|P|2.5.1\rPID|1||P001\rOBR|1|ORD1|SP1|CBC^血常规^L|||20261104115500\rOBX|1|NM|WBC^白细胞计数^L||6.2|10*9/L|3.5-9.5|N|||F\r' \
  | curl -sS -X POST 'http://127.0.0.1:8081/api/integration/lis/oru' \
      -H "X-LIS-Token: $MEDICAL_LIS_TOKEN" \
      -H 'Content-Type: text/plain' --data-binary @-
```

成功返回 `202`：

```json
{"accepted":true,"patientRef":"P001","messageControlId":"MSG-001",
 "panelCode":"CBC","received":1,"inserted":1,"skippedDuplicates":0}
```

支持的输入形态：裸 HL7 文本、MLLP 帧（`0x0B … 0x1C 0x0D`）、`\r` 或 `\n` 分段。
分隔符从 `MSH-1` / `MSH-2` 读取，不写死。

### 字段映射

| HL7 | 落库字段 | 说明 |
|---|---|---|
| `MSH-10` | `message_control_id` | 与 OBX 序号构成幂等键 |
| `PID-3.1` | `patient_ref` | 只允许 `[A-Za-z0-9._:-]{1,64}` |
| `OBR-4.1 / .2` | `panel_code / panel_name` | |
| `OBX-3.1 / .2` | `analyte_code / analyte_name` | |
| `OBX-5` | `value_text` / `value_num` | 非数字结果保留原文，不强转 |
| `OBX-6.1` | `unit` | |
| `OBX-7` | `ref_low / ref_high / ref_range_text` | `<5`、`>=1.0`、`3.5-9.5` 均可 |
| `OBX-8` | `abnormal_flag_raw` + `abnormal_flag` | H/L/N/A → high/low/normal/abnormal |
| `OBX-11` | `result_status` | F/P/C/X |
| `OBX-14` | `observed_at` | 缺省回落到 OBR-7，再回落到 MSH-7 |

时间：TS 自带偏移时按自带；没有则**沿用 MSH-7 的偏移**（不按服务器时区，也不硬按 UTC，
否则整条结果会偏 8 小时）。

### 拒收规则（整条消息退回，不落半条）

| 情况 | 状态码 | error |
|---|---|---|
| 非 `ORU^R01` | 400 | `hl7_parse_error` |
| 缺 `PID-3` / `PID-3` 含非法字符 | 400 | `hl7_parse_error` |
| 缺 `OBX` / `OBX-3` / `OBX-5` | 400 | `hl7_parse_error` |
| 患者 ref 不存在 | 422 | `unknown_patient` |
| 机构配置为 off / 模式不匹配 | 409 | `lis_disabled` |
| 令牌缺失 / 错误 | 401 | `missing_token` / `invalid_token` |
| 服务端未配置令牌 | 503 | `lis_token_not_configured` |

## 3.6 MLLP 直连与 HL7 ACK^R01（2026-11-06 新增）

当对接方要求 LIS **直连 2575 端口**、且强制要 HL7 ACK 报文时，打开 MLLP 通道：

```bash
MEDICAL_LIS_MLLP_ENABLED=true \
MEDICAL_LIS_TOKEN=<共享密钥> \
MEDICAL_LIS_MLLP_PORT=2575 \
java -jar target/pasm-medical-backend-0.1.1.jar
```

**默认完全关闭**（不创建监听 Bean）。三个 fail-closed 约束：

1. 只绑 `127.0.0.1`；跨机部署必须由院方在网络层做白名单/专网隔离，
   本项目不代替院方做网络准入（投产门禁会把非回环绑定记为 BLOCK）。
2. `MEDICAL_LIS_TOKEN` 未配置时通道仍监听，但**每条消息回 `AR` 拒绝**，
   让 LIS 拿到明确结论而不是面对一个连不上/没响应的黑盒。
3. 令牌与 HTTP 端点共用 `IntegrationTokenGuard` 的常量时间比较。

**令牌怎么传**：TCP 没有 HTTP 头，用 HL7 自定义 `ZAT` 段（`ZAT|<token>`，HL7 v2
允许的 Z 段扩展）。服务端提取后**在交给业务层之前剥掉该段**，保证共享密钥不落进
解析结果、异常文本、日志或库表；消息控制号仍取 MSH-10，幂等键不受影响。

**帧与 ACK**：入站 `0x0B <HL7> 0x1C 0x0D`，出站同格式封装 `ACK^R01`。
MSA-2 回显原 MSH-10（LIS 靠它对账，丢了会无限重发）。MSA-1 三态：

| 码 | 含义 | 触发 | LIS 该做什么 |
|---|---|---|---|
| `AA` | 接受 | 落库成功（含幂等重发） | 丢弃本地副本；MSA-3 带 `inserted=N;skippedDuplicates=M` |
| `AE` | 应用错误 | 帧损坏、HL7 解析失败、未知患者 | **修正后**可重发；未知患者先核对主索引 |
| `AR` | 拒绝 | 令牌缺失/错误/未配置、机构未启用 LIS | **停止重试**并联系管理员（重发不会变好） |

这与 HTTP 的 `202 / 400 / 422 / 401 / 409` 是同一套判定，只是翻译成 HL7 标准写法。
ACK 文本只放**稳定错误码**，不放异常原文（可能含患者 ref 或内部路径）。

> ⚠ 一连接可连续发多帧，每帧立即回 ACK；空闲超时或 EOF 断开。
> 单帧上限 1 MiB，与 HTTP 端点一致。

## 4. FHIR 投递

```bash
curl -sS -X POST 'http://127.0.0.1:8081/api/integration/fhir/observations' \
  -H "X-LIS-Token: $MEDICAL_LIS_TOKEN" -H 'Content-Type: application/json' -d '{
    "resourceType": "Observation",
    "id": "obs-20261104-001",
    "status": "final",
    "code": {"coding": [{"system": "urn:pasm:medical:analyte", "code": "GLU", "display": "血糖"}]},
    "subject": {"reference": "Patient/P001"},
    "effectiveDateTime": "2026-11-04T02:00:00Z",
    "valueQuantity": {"value": 7.2, "unit": "mmol/L"},
    "interpretation": [{"coding": [{"code": "H"}]}],
    "referenceRange": [{"low": {"value": 3.9}, "high": {"value": 6.1}}]
  }'
```

- **`id` 必填**：它是幂等键。同一 `id` 重发 → `skippedDuplicates` +1；
  同一 `id` 换了患者 → 422 `patient_conflict`（不覆盖、不静默跳过）。
- 没有 LOINC 映射时，`code.coding[0].code` 原样作为院内码存，**不猜** LOINC。
- 同一 Bundle 混入多个患者 → 整包拒收。

## 5. FHIR 只读

| 请求 | 返回 |
|---|---|
| `GET /api/fhir/metadata` | CapabilityStatement（明确只支持哪些交互） |
| `GET /api/fhir/Patient/{ref}` | Patient（含 identifier；只有年龄，**不伪造 birthDate**） |
| `GET /api/fhir/Encounter?patient={ref}` | Bundle(searchset) |
| `GET /api/fhir/Observation?patient={ref}` | Bundle(searchset) |

- 鉴权与后台一致：患者令牌只能读自己，医护按科室范围收窄（越权 403，不存在 404）。
- 检验值：数值走 `valueQuantity`，文本（"阴性"）走 `valueString`。
- 异常标记映射到 `v3-ObservationInterpretation`（H/L/N/A）。

## 6. 数据落点

`lab_results`（V2 迁移）：

- 项目级粒度（一个 OBX / 一个 Observation 一行），不是就诊摘要。
- 唯一约束 `(message_control_id, observation_seq)` 保证重发幂等。
- 索引：`(patient_ref, observed_at DESC)`、`(patient_ref, panel_code)`、
  `(patient_ref, abnormal_flag)`。
- **不写认知记忆**：结果更正（`result_status=C`）、复检、单位差异都会让旧值失效；
  进记忆仍走 `pasm_medical.lab` 的医师确认闸门。

## 7. 尚未实现（不要当成已有）

| 项 | 现状 |
|---|---|
| ORU 之外的报文 | 未实现（ADT/ORM/RDE 一律拒绝；MLLP 回 `AE`、HTTP 回 400） |
| LOINC / SNOMED 映射 | 未实现。用院内码 + 文本，避免伪码 |
| FHIR 写入非检验资源 | 未实现（只收 Observation） |
| FHIR 搜索参数 | 只支持 `patient`；无 `date` / `code` / 分页 |
| 跨机 MLLP 的网络准入 | 由院方网络层负责，代码只做绑定地址告警 |
| 长期连接池 / TLS | 未实现。当前是明文 TCP + 回环绑定，跨机需院方加隧道 |

## 8. 验证

```bash
cd backend
export JAVA_HOME="C:/Program Files/Java/jdk-18.0.1.1"

# 纯解析单测（含反例）
mvn -Dtest=Hl7ParserTest test

# MLLP 帧 / ACK / ZAT 剥离的纯单测（不启 Spring、不开端口）
mvn -Dtest=MllpProtocolTest test

# 真 PostgreSQL 集成（落库/幂等/HTTP 鉴权/FHIR 投递）
mvn -Dtest=LisIntegrationPostgresTest test

# 端到端：真开两个 TCP 端口，发帧验 AA/AE/AR、幂等、fail closed
python tools/e2e_stack.py     # 末段 15 项 MLLP 断言
```

真库测试用嵌入式 PostgreSQL，不依赖 Docker。`--full` 一键验证会全部串起来。
