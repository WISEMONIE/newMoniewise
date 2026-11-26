package com.moniewise.moniewise_backend.dto.response;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MonnifyLoginResponse {
    private boolean requestSuccessful;
    private ResponseBody responseBody;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ResponseBody {
        private String accessToken;
        private int expiresIn;
    }
}