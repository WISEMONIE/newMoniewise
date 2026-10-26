package com.moniewise.moniewise_backend.dto.request;

import com.moniewise.moniewise_backend.enums.Gender;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.Past;
import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
// ProfileRequest (new DTO)
public class ProfileRequest {
    private String firstName; // Split this
    private String lastName;  // Split this
    private String phone;
    private String bvn;       // 👈 CRITICAL ADDITION
    private Gender gender;
    private BigDecimal monthlyIncome;
    private String mainExpense;
    private String savingsGoal;
    private String occupation;
    private String referralSource;
    private String referralCode;

    @Past
    private LocalDate dob;

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    // Getters, setters
}
