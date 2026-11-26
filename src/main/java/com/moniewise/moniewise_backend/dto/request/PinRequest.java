package com.moniewise.moniewise_backend.dto.request;

import lombok.Data;

@Data
public class PinRequest {
    private String pin;
    private String confirmPin; // Only needed for creation
    private String password;   // Optional: Require login password to set a PIN (High Security)
}