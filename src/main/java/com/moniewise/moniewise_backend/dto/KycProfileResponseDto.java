package com.moniewise.moniewise_backend.dto;

import com.moniewise.moniewise_backend.entity.KycProfile;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KycProfileResponseDto {

    private Long id;
    private String bvn;
    private boolean bvnVerified;
    private String sourceOfFunds;
    private String sourceOfWealth;
    private KycProfile.KycStatus kycStatus;
    private KycProfile.RiskLevel riskLevel;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}