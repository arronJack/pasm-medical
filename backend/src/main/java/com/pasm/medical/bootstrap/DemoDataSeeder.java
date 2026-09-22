package com.pasm.medical.bootstrap;

import com.pasm.medical.domain.Encounter;
import com.pasm.medical.domain.Patient;
import com.pasm.medical.repository.EncounterRepository;
import com.pasm.medical.repository.PatientRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Instant;

/**
 * 开发档演示数据播种器。
 *
 * <p>dev 档用 H2 内存库，每次重启清空 —— 所以启动时灌一批演示数据，
 * 让前端"真实接口"（患者档案 / 历史就诊 / 后台统计）有内容可看，
 * 而不是一片空白让人误以为接口没通。
 *
 * <p>⚠️ 只在 {@code dev} profile 生效：生产（PostgreSQL）绝不自动灌数。
 * 且只在患者表为空时才灌，避免重复。
 */
@Configuration
@Profile("dev")
public class DemoDataSeeder {

    @Bean
    public CommandLineRunner seed(PatientRepository patients, EncounterRepository encounters) {
        return args -> {
            if (patients.count() > 0) {
                return;
            }
            Patient p = new Patient();
            p.setRef("demo-patient-001");
            p.setName("示例患者");
            p.setSex("女");
            p.setAge(46);
            p.setAllergy("青霉素");
            p.setChronic("高血压 3 级");
            patients.save(p);

            Encounter e1 = new Encounter();
            e1.setPatientRef("demo-patient-001");
            e1.setOccurredAt(Instant.parse("2026-09-21T10:30:00Z"));
            e1.setDepartment("心内科 / 急诊内科");
            e1.setChiefComplaint("胸痛 2 小时");
            e1.setAssessment("可疑心源性胸痛，已建议急诊");
            e1.setPlan("转诊心内科，完善心电图与肌钙蛋白");
            e1.setTriageUrgency("emergency");
            e1.setSummaryText("主诉：胸痛 2 小时；判断：可疑心源性胸痛，已建议急诊；处置：转诊心内科，完善心电图与肌钙蛋白");
            encounters.save(e1);

            Encounter e2 = new Encounter();
            e2.setPatientRef("demo-patient-001");
            e2.setOccurredAt(Instant.parse("2026-08-12T15:02:00Z"));
            e2.setDepartment("发热门诊");
            e2.setChiefComplaint("发热 3 天");
            e2.setAssessment("上呼吸道感染");
            e2.setPlan("对症处理，观察");
            e2.setTriageUrgency("routine");
            e2.setSummaryText("主诉：发热 3 天；判断：上呼吸道感染；处置：对症处理，观察");
            encounters.save(e2);

            // ★ 第二位演示患者是**刻意的**：他的就诊科室与前一位没有交集，
            //   这样"医护只看本科室"这条数据范围规则在界面上**看得见效果**
            //   （发热门诊的医护只应看到 demo-patient-001，看不到这一位）。
            //   只有一位患者时，科室过滤与全院不过滤的结果一样，测不出区别 —— 那等于没测。
            Patient p2 = new Patient();
            p2.setRef("demo-patient-002");
            p2.setName("示例患者乙");
            p2.setSex("男");
            p2.setAge(58);
            p2.setAllergy("");
            p2.setChronic("2 型糖尿病");
            patients.save(p2);

            Encounter e3 = new Encounter();
            e3.setPatientRef("demo-patient-002");
            e3.setOccurredAt(Instant.parse("2026-09-15T09:10:00Z"));
            e3.setDepartment("骨科");
            e3.setChiefComplaint("右膝疼痛 1 周");
            e3.setAssessment("考虑退行性改变，建议进一步检查");
            e3.setPlan("骨科门诊复诊，必要时影像检查");
            e3.setTriageUrgency("routine");
            e3.setSummaryText("主诉：右膝疼痛 1 周；判断：考虑退行性改变，建议进一步检查；处置：骨科门诊复诊");
            encounters.save(e3);
        };
    }
}
