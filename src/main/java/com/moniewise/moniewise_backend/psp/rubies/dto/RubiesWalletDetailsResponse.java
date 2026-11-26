package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response from GET /{stage}/baas-wallet/wallet-details/{accountNumber}
 *
 * <p>Used for re-syncing wallet metadata (e.g. after a failed create-wallet
 * where the wallet was actually provisioned on Rubies' side).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesWalletDetailsResponse {

    @JsonProperty("responseCode")
    private String responseCode;

    @JsonProperty("responseMessage")
    private String responseMessage;

    @JsonProperty("data")
    private Data data;

    public boolean isSuccess() {
        return "00".equals(responseCode);
    }

    public String getResponseCode()            { return responseCode; }
    public void setResponseCode(String v)      { this.responseCode = v; }

    public String getResponseMessage()         { return responseMessage; }
    public void setResponseMessage(String v)   { this.responseMessage = v; }

    public Data getData()                      { return data; }
    public void setData(Data v)                { this.data = v; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Data {

        @JsonProperty("customerId")
        private String customerId;

        @JsonProperty("accountNumber")
        private String accountNumber;

        @JsonProperty("accountName")
        private String accountName;

        @JsonProperty("bankName")
        private String bankName;

        @JsonProperty("bankCode")
        private String bankCode;

        @JsonProperty("status")
        private String status;

        public String getCustomerId()            { return customerId; }
        public void setCustomerId(String v)      { this.customerId = v; }

        public String getAccountNumber()         { return accountNumber; }
        public void setAccountNumber(String v)   { this.accountNumber = v; }

        public String getAccountName()           { return accountName; }
        public void setAccountName(String v)     { this.accountName = v; }

        public String getBankName()              { return bankName; }
        public void setBankName(String v)        { this.bankName = v; }

        public String getBankCode()              { return bankCode; }
        public void setBankCode(String v)        { this.bankCode = v; }

        public String getStatus()                { return status; }
        public void setStatus(String v)          { this.status = v; }
    }
}
