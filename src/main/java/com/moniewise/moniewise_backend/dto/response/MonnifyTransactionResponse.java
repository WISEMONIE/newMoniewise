package com.moniewise.moniewise_backend.dto.response;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonnifyTransactionResponse {
    private boolean requestSuccessful;
    private String responseMessage;
    private ResponseBody responseBody;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseBody {
        private String transactionReference;
        private String status;
    }
}