package com.moniewise.moniewise_backend.dto.response;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private long expiresAt;
    private long idleTimeoutSeconds;
    private long warningLeadSeconds;
}
