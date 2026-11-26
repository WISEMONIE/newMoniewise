package com.moniewise.moniewise_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PinStatusResponse {
    private boolean hasTransactionPin;
}