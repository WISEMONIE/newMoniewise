package com.moniewise.moniewise_backend.enums;

public enum Status {
    PENDING,    // Awaiting user claim
    CLAIMED,    // User successfully withdrew
    EXPIRED,    // Grace period passed, returned silently
    REVERTED    // System reverted due to timeout
}
