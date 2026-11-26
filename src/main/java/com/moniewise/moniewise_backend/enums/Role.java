package com.moniewise.moniewise_backend.enums;

import org.springframework.security.core.GrantedAuthority;

public enum Role implements GrantedAuthority {
    USER, ADMIN, SYSTEM;

    @Override
    public String getAuthority() {
        // Spring's hasRole('X') checks for the authority "ROLE_X", so the
        // GrantedAuthority contract requires the "ROLE_" prefix here.
        return "ROLE_" + name(); // e.g. "ROLE_USER", "ROLE_ADMIN", "ROLE_SYSTEM"
    }
}