package com.pasm.medical.db;

import com.pasm.medical.domain.Patient;
import com.pasm.medical.repository.PatientRepository;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * **整应用 + 真 PostgreSQL** 的启动验证（嵌入式 PG，不依赖 Docker）。
 *
 * <p>{@link FlywayPostgresTest} 只验"迁移脚本能在 PG 上跑"，本测试进一步验
 * "整个 Spring 上下文能在真 PG 上起来且实体与表结构对得上"：
 * <ol>
 *   <li>Flyway 迁移先执行，JPA 以 {@code ddl-auto=validate} 校验实体 ↔ 表一致
 *       —— 任何字段类型/可空性/命名漂移都会让上下文启动失败；</li>
 *   <li>真实 Repository 读写可用（不是拿 H2 的近似行为糊过去）；</li>
 *   <li>{@code flyway_schema_history} 里 V1 标记 success。</li>
 * </ol>
 *
 * <p>为什么不用 {@code tools/pg_smoke.py}：那条路径要 Docker 引擎。
 * 本测试覆盖同样的关键断言（迁移 + validate + 读写），CI 与无 Docker 机器都能跑；
 * Docker 可用时再跑 pg_smoke 补"打好的 jar + prod 配置"这一层。
 */
// MOCK：不绑端口，但保留 Servlet Web 上下文 —— SecurityConfig 依赖 HttpSecurity，
// 用 NONE 会因为不是 Web 应用而找不到该 Bean，导致上下文起不来（与服务本身无关）。
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class FullAppPostgresTest {

    private static final EmbeddedPostgres PG;

    static {
        try {
            PG = EmbeddedPostgres.builder().start();
        } catch (Exception e) {
            throw new IllegalStateException("嵌入式 PostgreSQL 启动失败", e);
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> PG.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    @Autowired
    private PatientRepository patients;

    @Autowired
    private DataSource dataSource;

    @Test
    void contextStartsWithFlywayAndJpaValidateOnRealPostgres() throws Exception {
        // 1) 上下文能起来 = Flyway 迁移成功 + JPA validate 通过（否则本测试根本跑不到这里）
        assertNotNull(patients, "Repository 应注入成功");

        // 2) 真实读写：PG 上自增主键与实体映射都能用
        Patient p = new Patient();
        p.setRef("pg-fullapp-p1");
        p.setName("真库验证患者");
        p.setSex("男");
        p.setAge(40);
        p.setAllergy("");
        p.setChronic("");
        Patient saved = patients.save(p);
        assertEquals("pg-fullapp-p1", saved.getRef(), "主键应回写");
        assertNotNull(saved.getCreatedAt(), "created_at 应有值（数据库/实体默认生效）");

        assertEquals("真库验证患者",
                patients.findById("pg-fullapp-p1").orElseThrow().getName(),
                "按 ref 查询应读回刚写入的记录");

        // 3) 迁移历史：V1 成功落账
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM flyway_schema_history WHERE version='1' AND success=true")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1), "flyway_schema_history 应记录 V1 success");
        }

        patients.delete(saved);
    }
}
