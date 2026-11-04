package com.pasm.medical.integration;

/** LIS 消息里的患者 ref 在业务库不存在。 */
public class UnknownPatientException extends RuntimeException {

    public UnknownPatientException(String message) {
        super(message);
    }
}
