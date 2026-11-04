package com.pasm.medical.service;

import com.pasm.medical.domain.LabResult;
import com.pasm.medical.integration.LisDisabledException;
import com.pasm.medical.integration.UnknownPatientException;
import com.pasm.medical.integration.hl7.Hl7OruMapper;
import com.pasm.medical.integration.hl7.Hl7Parser;
import com.pasm.medical.integration.hl7.OruReport;
import com.pasm.medical.repository.LabResultRepository;
import com.pasm.medical.repository.PatientRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LIS 检验结果接入：解析（HL7 ORU）→ 校验 → 幂等落库。
 *
 * <p>★ <b>为什么必须校验"机构配置"</b>：{@code system_config.lis=off} 的机构
 * 不应该有可用通道。只在界面上下拉框限制没用 —— 接口能被直接调用。
 * 关闭时必须<b>明确报错</b>（409），而不是"接受但丢弃"：后者会让 LIS 厂商
 * 以为对接成功，实际一条都没进来。
 *
 * <p>★ <b>未知患者一律拒绝</b>（422）：不自动建患者。自动建会让一条写错 ref
 * 的消息凭空造出一个"患者"，而空档患者会被统计与随访当成真实病例。
 * 由 LIS 侧核对主索引后重发，是更安全的失败方式。
 *
 * <p>★ <b>幂等</b>：{@code (message_control_id, observation_seq)} 唯一。
 * 重发时跳过而非报错 —— 报错会让 LIS 无限重试把队列堵死。
 */
@Service
public class LisIngestService {

    private final LabResultRepository results;
    private final PatientRepository patients;
    private final SystemConfigService config;

    public LisIngestService(LabResultRepository results,
                            PatientRepository patients,
                            SystemConfigService config) {
        this.results = results;
        this.patients = patients;
        this.config = config;
    }

    /** 一次接入的结果摘要（回给 LIS 做对账）。 */
    public record IngestResult(String patientRef,
                               String messageControlId,
                               String panelCode,
                               int received,
                               int inserted,
                               int skippedDuplicates) {
    }

    @Transactional
    public IngestResult ingest(String rawMessage) {
        String mode = config.read().lis();
        if ("off".equals(mode)) {
            throw new LisDisabledException("机构未启用 LIS 对接（system_config.lis=off）");
        }
        if (!"hl7".equals(mode)) {
            throw new LisDisabledException(
                    "当前 LIS 对接模式为 " + mode + "，HL7 通道未启用（改为 hl7 后重试）");
        }

        // 解析失败会抛 Hl7ParseException —— 整个事务回滚，不会落半条。
        OruReport report = Hl7OruMapper.map(Hl7Parser.parse(rawMessage));

        if (!patients.existsById(report.patientRef())) {
            throw new UnknownPatientException(
                    "未知患者 ref：" + report.patientRef() + "（请先在业务侧建档）");
        }

        int inserted = 0;
        int skipped = 0;
        for (OruReport.Observation o : report.observations()) {
            if (results.existsByMessageControlIdAndObservationSeq(
                    report.messageControlId(), o.seq())) {
                skipped++;
                continue;
            }
            results.save(toEntity(report, o));
            inserted++;
        }

        return new IngestResult(report.patientRef(), report.messageControlId(),
                report.panelCode(), report.observations().size(), inserted, skipped);
    }

    private static LabResult toEntity(OruReport report, OruReport.Observation o) {
        LabResult r = new LabResult();
        r.setPatientRef(report.patientRef());
        r.setMessageControlId(report.messageControlId());
        r.setObservationSeq(o.seq());
        r.setPanelCode(blankToNull(report.panelCode()));
        r.setPanelName(blankToNull(report.panelName()));
        r.setAnalyteCode(o.analyteCode());
        r.setAnalyteName(blankToNull(o.analyteName()));
        r.setValueText(o.valueText());
        r.setValueNum(o.valueNum());
        r.setUnit(blankToNull(o.unit()));
        r.setRefLow(o.refLow());
        r.setRefHigh(o.refHigh());
        r.setRefRangeText(blankToNull(o.refRangeText()));
        r.setAbnormalFlag(o.abnormalFlag());
        r.setAbnormalFlagRaw(blankToNull(o.abnormalFlagRaw()));
        r.setResultStatus(o.status());
        r.setObservedAt(o.observedAt() != null ? o.observedAt() : report.observedAt());
        r.setSource("hl7");
        return r;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
