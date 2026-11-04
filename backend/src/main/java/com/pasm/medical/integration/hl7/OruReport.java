package com.pasm.medical.integration.hl7;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 一条 ORU^R01（检验结果）消息的业务投影 —— 已脱离 HL7 结构，可直接落库/映射 FHIR。
 *
 * <p>{@code abnormalFlag} 为<b>归一化</b>值（high/low/normal/abnormal/unknown），
 * 原始标记另存 {@code abnormalFlagRaw}：判读用归一化，对账用原始值。
 */
public record OruReport(
        String patientRef,
        String messageControlId,
        String sendingApp,
        String panelCode,
        String panelName,
        Instant observedAt,
        List<Observation> observations) {

    /** 单个检验项（对应一个 OBX）。 */
    public record Observation(
            int seq,
            String analyteCode,
            String analyteName,
            String valueText,
            BigDecimal valueNum,
            String unit,
            BigDecimal refLow,
            BigDecimal refHigh,
            String refRangeText,
            String abnormalFlag,
            String abnormalFlagRaw,
            String status,
            Instant observedAt) {
    }
}
