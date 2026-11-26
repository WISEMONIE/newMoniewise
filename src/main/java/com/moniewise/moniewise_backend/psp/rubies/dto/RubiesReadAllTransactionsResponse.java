package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Response from POST /{stage}/baas-wallet/read-all-wallet-transactions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesReadAllTransactionsResponse {

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    @JsonProperty("data")
    private List<Map<String, Object>> data;

    @JsonProperty("hasNext")
    private boolean hasNext;

    public boolean isSuccess() {
        return "00".equals(responseCode);
    }

    public String getResponseCode()                  { return responseCode; }
    public void setResponseCode(String v)            { this.responseCode = v; }

    public String getResponseMessage()               { return responseMessage; }
    public void setResponseMessage(String v)         { this.responseMessage = v; }

    public List<Map<String, Object>> getData()       { return data; }
    public void setData(List<Map<String, Object>> v) { this.data = v; }

    public boolean isHasNext()                       { return hasNext; }
    public void setHasNext(boolean v)                { this.hasNext = v; }
}
