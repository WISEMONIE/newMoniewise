package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;

@Data
public class ResetTransactionPinRequest {
    private String otp;
    private String newPin;
    private String confirmNewPin;
}
