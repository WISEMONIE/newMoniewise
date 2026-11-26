package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-transaction/tsq
 * (Transaction Status Query)
 *
 * <p><strong>Field name note:</strong> the official Rubies {@code TsqRequest}
 * schema marks {@code reference} as REQUIRED — NOT {@code transactionReference}
 * (an earlier dev-sandbox assumption). Sending the wrong key means the request
 * arrives without its required field and Rubies will most likely respond with
 * "transaction not found" / a failed lookup, making status polling useless.
 */
public class RubiesTsqRequest {

    @JsonProperty("reference")
    private String reference;

    public RubiesTsqRequest() {}

    public RubiesTsqRequest(String reference) {
        this.reference = reference;
    }

    public String getReference()           { return reference; }
    public void setReference(String v)     { this.reference = v; }
}
