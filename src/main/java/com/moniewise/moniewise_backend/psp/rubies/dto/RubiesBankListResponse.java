package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response from POST /{stage}/baas-transaction/bank-list
 *
 * <p>On success:
 * <pre>
 * {
 *   "responseCode": "00",
 *   "responseMessage": "success",
 *   "data": [
 *     { "bankName": "GTBank", "bankCode": "058" },
 *     ...
 *   ]
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesBankListResponse {

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    @JsonProperty("data")
    private List<BankEntry> data;

    public boolean isSuccess() {
        return "00".equals(responseCode);
    }

    public String getResponseCode()            { return responseCode; }
    public void setResponseCode(String v)      { this.responseCode = v; }

    public String getResponseMessage()         { return responseMessage; }
    public void setResponseMessage(String v)   { this.responseMessage = v; }

    public List<BankEntry> getData()           { return data; }
    public void setData(List<BankEntry> v)     { this.data = v; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BankEntry {

        @JsonProperty("bankName")
        private String bankName;

        @JsonProperty("bankCode")
        private String bankCode;

        public String getBankName()            { return bankName; }
        public void setBankName(String v)      { this.bankName = v; }

        public String getBankCode()            { return bankCode; }
        public void setBankCode(String v)      { this.bankCode = v; }
    }
}
