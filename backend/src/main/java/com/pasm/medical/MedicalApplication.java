package com.pasm.medical;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 医院智能诊疗辅助系统 · 业务层入口。
 *
 * <p><b>本服务不做诊断、不出处方</b>：它负责患者 / 就诊 / 病历 / 处方的事务与审计，
 * 并把"记忆、情绪、依据"这类认知能力委托给 Python 认知服务（PASM）。
 * 所有面向医生的输出都带 {@code requires_physician_confirmation=true}。
 */
@SpringBootApplication
@EnableConfigurationProperties(CognitionProperties.class)
public class MedicalApplication {

    public static void main(String[] args) {
        SpringApplication.run(MedicalApplication.class, args);
    }
}
