package com.moniewise.moniewise_backend.enums;

public enum NotificationPriority {
    HIGH,   // Push + Save (Money Movement, Security)
    MEDIUM, // Save Only (Budget warnings, Tips)
    LOW     // Ignore/Log Only (Created envelope, Edited profile)
}