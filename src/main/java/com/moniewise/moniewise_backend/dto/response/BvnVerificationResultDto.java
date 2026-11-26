package com.moniewise.moniewise_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.moniewise.moniewise_backend.entity.KycProfile;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The response returned to the mobile client after a successful BVN verification
 * via SecureWave.  The raw base64 image from the SecureWave payload is intentionally
 * excluded — it is large and not needed by the client.
 */
@Getter
@Setter
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BvnVerificationResultDto {

    /**
     * Set by the BVN check endpoint: whether the wallet opened, and if not,
     * a customer-safe reason and whether trying again can help.
     */
    private java.util.Map<String, Object> wallet;

    // ── Top-level SecureWave fields ───────────────────────────────────────────
    private String bvnNumber;
    private String nameOnCard;
    private String enrolmentBank;
    private String enrolmentBranch;
    private String formattedRegistrationDate;
    private String levelOfAccount;
    private String nin;
    private String watchlisted;
    private String verificationStatus;

    // ── personal_info (flattened, image field omitted) ────────────────────────
    private String firstName;
    private String middleName;
    private String lastName;
    private String fullName;
    private String gender;
    private String dateOfBirth;
    private String stateOfOrigin;
    private String lgaOfOrigin;
    private String nationality;
    private String maritalStatus;

    // ── residential_info (flattened) ─────────────────────────────────────────
    private String stateOfResidence;
    private String lgaOfResidence;
    private String residentialAddress;

    // ── Internal KYC status (set after persisting) ────────────────────────────
    private KycProfile.KycStatus kycStatus;
    private boolean bvnVerified;
}
