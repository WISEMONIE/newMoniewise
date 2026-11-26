package com.moniewise.moniewise_backend.receipt;

/**
 * A receipt built for one response. It is not stored.
 */
public record ReceiptFile(byte[] bytes, String filename) {
}
