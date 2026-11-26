package com.moniewise.moniewise_backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KycProfileRequestDto {

    @NotBlank
    @Pattern(regexp = "\\d{11}", message = "BVN must be 11 digits")
    private String bvn;

    @NotBlank
    private String sourceOfFunds;

    private String sourceOfWealth;
}