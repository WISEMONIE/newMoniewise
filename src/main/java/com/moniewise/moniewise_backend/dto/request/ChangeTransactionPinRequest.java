package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;

@Data
public class ChangeTransactionPinRequest {
    private String currentPin;
    private String newPin;
    // confirmNewPin is a UI-only safety field — validated on the frontend before
    // the request is sent. The backend only needs currentPin + newPin.
}
