package com.paytm.seats;

import java.util.Map;

/** A business outcome that maps to a 4xx response (never a 5xx). */
public class DomainException extends RuntimeException {
    private final int status;
    private final String code;
    private final Map<String, Object> extra;

    public DomainException(int status, String code, String message) {
        this(status, code, message, Map.of());
    }

    public DomainException(int status, String code, String message, Map<String, Object> extra) {
        super(message, null, false, false); // no stack trace: these are control flow under load
        this.status = status;
        this.code = code;
        this.extra = extra;
    }

    public int status() { return status; }
    public String code() { return code; }
    public Map<String, Object> extra() { return extra; }
}
