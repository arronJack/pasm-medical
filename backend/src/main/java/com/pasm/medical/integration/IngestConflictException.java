package com.pasm.medical.integration;

/**
 * 同一资源 id 已存在但归属不同患者 —— 拒绝覆盖。
 *
 * <p>静默覆盖会把 A 的检验结果挂到 B 身上；静默跳过则让 B 以为数据已入库。
 * 两种都不行，只能报冲突让人核对主索引。
 */
public class IngestConflictException extends RuntimeException {

    public IngestConflictException(String message) {
        super(message);
    }
}
