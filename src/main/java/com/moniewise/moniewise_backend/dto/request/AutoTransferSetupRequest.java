package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AutoTransferSetupRequest {
    private String bankCode;
    private String bankName;
    private String accountNumber;
    private String accountName;
    private String transactionPin;
}
