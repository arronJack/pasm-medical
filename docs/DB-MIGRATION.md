# 数据库迁移（Flyway）

> 一句话：**表结构的唯一真相是 `backend/src/main/resources/db/migration/V*.sql`**，
> JPA 的 `ddl-auto` 只做校验（`validate`），不再自动改表。

---

## 1. 为什么不用 `ddl-auto: update`

医疗系统的表结构变更要走**评审与变更单**。`ddl-auto` 的问题不是"会不会建表"，而是：

- 改了什么、谁改的、为什么改 —— 没有可 diff 的产物；
- 生产环境让 ORM 自动改表，等于把 DDL 权限交给应用；
- 多实例启动时并发改表，行为不可预期。

Flyway 把每次变更变成**一个带版本号的文件**，可评审、可回放、可追溯。

---

## 2. 目录与命名

```
backend/src/main/resources/db/migration/
  V1__init.sql            # 基础表结构（已应用，不要再改）
  V2__xxx.sql             # 下一个变更
```

规则（硬约束）：

1. **已应用的迁移永不修改**。Flyway 用校验和检测，改了会导致启动失败
   （`Validate failed: Migration checksum mismatch`）。要改就加新版本。
2. 版本号单调递增，**不要跳号复用**。
3. 一个迁移只做一件事；DDL 与大批量数据修数**分开**，便于定位与回滚。
4. 生产脚本里**不要**写 `DROP TABLE/DROP COLUMN` 除非有明确的数据迁移方案与备份。
5. 加索引优先 `CREATE INDEX`（不锁写）；大表需要并发建索引时用
   `CREATE INDEX CONCURRENTLY`，并注意它不能在事务块里执行。

---

## 3. 本地起 PostgreSQL

```bash
docker compose up -d          # postgres:16-alpine，端口 5432
export MEDICAL_DB_URL=jdbc:postgresql://127.0.0.1:5432/pasm_medical
export MEDICAL_DB_USER=pasm_medical
export MEDICAL_DB_PASSWORD=pasm_medical_dev
export MEDICAL_AUTH_JWT_SECRET="$(openssl rand -base64 48)"
java -jar backend/target/pasm-medical-backend-*.jar
```

启动日志里应出现：

```
Flyway ... Migrating schema "public" to version "1 - init"
Flyway ... Successfully applied 1 migration
```

`/actuator/health` 为 `UP` 说明：迁移成功 **且** JPA `validate` 通过
（实体与真实表一致）。

---

## 4. 三层验证（缺一层都不够）

| 层 | 命令 | 验证什么 | 依赖 |
|---|---|---|---|
| ① dev/e2e | `run_checks.py --web` | H2 上同一份迁移 + `validate`（每次 e2e 启动都跑） | 无 |
| ② 真 PG（测试） | `cd backend && mvn -Dtest=FlywayPostgresTest test` | **真实 PostgreSQL** 上建表、索引、自增、幂等 | 无（嵌入式 PG） |
| ③ 整应用（冒烟） | `python tools/pg_smoke.py` | 打好的 jar + prod 配置 + Flyway + validate + 重启幂等 | Docker |

> ② 用 `io.zonky.test:embedded-postgres`，首次运行会下载一次 PG 二进制（约几十 MB），
> 之后走本地缓存；CI 无 Docker 也能跑。

---

## 5. 老库首次接入（baseline）

`application.yml` 设了：

```yaml
spring.flyway.baseline-on-migrate: true
spring.flyway.baseline-version: 1
```

含义：如果库里**已有表但没有 `flyway_schema_history`**，Flyway 会把当前状态标记为
"V1 已应用"，然后从 V2 开始执行。

⚠️ 这只解决"能启动"，**不解决"结构是否真的对"**。老库接入前必须：

1. 用 `pg_dump --schema-only` 导出结构；
2. 与 `V1__init.sql` 人工比对（列、类型、可空、索引、约束）；
3. 有差异的，先写一个 `V2__align_legacy.sql` 修平，再上线。

---

## 6. 上线清单

- [ ] 变更单 / 评审通过，脚本已 review；
- [ ] 在生产同版本 PG 上预演过（② 或 ③）；
- [ ] 备份（`pg_dump` 或快照）已完成，并验证可恢复；
- [ ] 迁移耗时评估：大表加索引/改类型要考虑锁与停机窗口；
- [ ] 回滚方案明确：Flyway 社区版**没有 undo**，回滚 = 备份恢复或写一个前向修复脚本；
- [ ] 多实例部署：迁移只在启动时跑一次，建议先滚动一台观察，再放全量。

---

## 7. 当前 schema 概览

| 表 | 用途 | 关键约束 |
|---|---|---|
| `patients` | 患者主档（去标识化） | PK `ref` |
| `encounters` | 就诊 / 预问诊（FHIR Encounter 最小投影） | PK `id` 自增；不存原始对话 |
| `ai_audit` | AI 审计（只增不改） | PK `id`；`occurred_at` 非空 |
| `knowledge_docs` | 机构资料库 | PK `id`；`doc_key` 唯一 |
| `system_config` | 对接设置（期望值） | PK `config_key` |

索引按真实查询路径建：审计分页、统计（action/refused）、科室范围、时间轴、资料库科室视图。
