package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body for POST /{stage}/baas-transaction/bank-list
 *
 * <p>Pass {@code readAll="YES"} to retrieve all supported banks.
 */
public class RubiesBankListRequest {

    @JsonProperty("readAll")
    private String readAll = "YES";

    public RubiesBankListRequest() {}

    public String getReadAll()           { return readAll; }
    public void setReadAll(String v)     { this.readAll = v; }
}
