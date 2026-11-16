package com.moniewise.moniewise_backend.receipt;

/**
 * The slip could not be drawn. This is a server fault, not a bad request.
 */
public class ReceiptRenderException extends RuntimeException {

    public ReceiptRenderException(Throwable cause) {
        super("Could not create the receipt.", cause);
    }
}
