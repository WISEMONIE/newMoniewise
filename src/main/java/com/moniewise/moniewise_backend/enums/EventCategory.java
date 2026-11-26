package com.moniewise.moniewise_backend.enums;

public enum EventCategory {
    WEDDING,
    BIRTHDAY,
    RENT,
    SCHOOL,
    OTHER;

    public static EventCategory parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EventCategory.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Choose Wedding, Birthday, Rent, School, or Other.");
        }
    }
}
