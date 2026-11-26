package com.moniewise.moniewise_backend.psp.rubies.dto;

import lombok.Data;
import lombok.Getter;
import lombok.Setter;

/**
 * Response from POST /{stage}/baas-transaction/name-enquiry
 *
 * <p>On success:
 * <pre>
 * {
 *   "responseCode": "00",
 *   "responseMessage": "success",
 *   "data": {
 *     "accountName": "JOHN DOE",
 *     "accountNumber": "1234567890",
 *     "bankCode": "058",
 *     "bankName": "GTBank"
 *   }
 * }
 * </pre>
 */

@Data
@Setter
@Getter
public class RubiesNameEnquiryResponse {

    private String accountName;
    private String accountNumber;
    private String bankCode;
    private String bankName;
    private String bvn;
    private String kyc;
    private String responseCode;
    private String responseMessage;
    private String sessionId;
}
