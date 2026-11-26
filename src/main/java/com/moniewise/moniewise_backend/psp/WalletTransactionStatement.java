package com.moniewise.moniewise_backend.psp;

import java.util.List;
import java.util.Map;

/**
 * One paged read of a wallet statement. {@code truncated} means the provider
 * still had pages, or a later page failed, so the list is not the full window.
 */
public record WalletTransactionStatement(List<Map<String, Object>> lines, boolean truncated) {
}
