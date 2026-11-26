package com.moniewise.moniewise_backend.psp.rubies.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Response from POST /{stage}/baas-wallet/balance-enquiry
 *
 * <p>On success:
 * <pre>
 * {
 *   "responseCode": "00",
 *   "responseMessage": "success",
 *   "data": {
 *     "accountNumber": "...",
 *     "availableBalance": "15000.00",
 *     "ledgerBalance": "15000.00",
 *     "currency": "NGN"
 *   }
 * }
 * </pre>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RubiesBalanceEnquiryResponse {

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

        @JsonProperty("accountNumber")
        private String accountNumber;

        @JsonProperty("availableBalance")
        private String availableBalance;

        @JsonProperty("ledgerBalance")
        private String ledgerBalance;

        @JsonProperty("currency")
        private String currency;

        public String getAccountNumber()           { return accountNumber; }
        public void setAccountNumber(String v)     { this.accountNumber = v; }

        public String getAvailableBalance()        { return availableBalance; }
        public void setAvailableBalance(String v)  { this.availableBalance = v; }

        public String getLedgerBalance()           { return ledgerBalance; }
        public void setLedgerBalance(String v)     { this.ledgerBalance = v; }

        public String getCurrency()                { return currency; }
        public void setCurrency(String v)          { this.currency = v; }

        /** Convenience: parse availableBalance as BigDecimal. Returns ZERO on parse failure. */
        public BigDecimal availableBalanceDecimal() {
            try {
                return availableBalance != null ? new BigDecimal(availableBalance.trim()) : BigDecimal.ZERO;
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
    }
}
