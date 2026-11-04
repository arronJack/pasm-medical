package com.pasm.medical.integration;

/** LIS 对接未启用 / 模式不匹配（配置为 off 或 fhir 时走 HL7 通道）。 */
public class LisDisabledException extends RuntimeException {

    public LisDisabledException(String message) {
        super(message);
    }
}
