package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;
import java.math.BigDecimal;

/**
 * Optional body for POST /savings/{id}/withdraw.
 * {@code amount} null (or no body at all — backward compatible) = withdraw the
 * full payout; otherwise a partial withdrawal of exactly {@code amount}.
 */
@Data
public class WithdrawSavingsRequest {
    private BigDecimal amount;
}
