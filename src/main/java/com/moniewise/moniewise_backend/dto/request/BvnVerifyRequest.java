package com.moniewise.moniewise_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

@Getter
@Setter
public class BvnVerifyRequest {

    @NotBlank(message = "BVN is required")
    @Pattern(regexp = "\\d{11}", message = "BVN must be exactly 11 digits")
    private String bvn;

    /**
     * Sent only after SecureWave is unavailable. Rubies checks these against
     * the BVN. A normal SecureWave success ignores them.
     */
    private String firstName;
    private String lastName;
    private String dateOfBirth;
}
