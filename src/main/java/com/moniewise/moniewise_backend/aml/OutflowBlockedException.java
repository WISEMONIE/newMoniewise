package com.moniewise.moniewise_backend.aml;

import org.springframework.http.HttpStatus;

import java.math.BigDecimal;

/**
 * A transfer stopped by a freeze or a review hold. The message is safe to
 * show the customer.
 *
 * <p>It is an {@link IllegalArgumentException} because every transfer
 * controller already turns that into a 400 with the message, so the current
 * app shows the right text without an update.
 */
public class OutflowBlockedException extends IllegalArgumentException {

    public static final String WALLET_FROZEN = "WALLET_FROZEN";
    public static final String OUTFLOW_HELD = "OUTFLOW_HELD";
    public static final String NEW_ACCOUNT_LIMIT = "NEW_ACCOUNT_LIMIT";

    private final String code;
    private final BigDecimal remaining;

    public OutflowBlockedException(String code, String message, BigDecimal remaining) {
        super(message);
        this.code = code;
        this.remaining = remaining;
    }

    public String getCode() {
        return code;
    }

    /** What the customer can still send today, or null when nothing can go out. */
    public BigDecimal getRemaining() {
        return remaining;
    }

    /** 400, the same status these refusals had before, so older app versions behave the same. */
    public HttpStatus getStatus() {
        return HttpStatus.BAD_REQUEST;
    }
}
