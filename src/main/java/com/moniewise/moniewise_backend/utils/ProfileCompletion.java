package com.moniewise.moniewise_backend.utils;

import com.moniewise.moniewise_backend.entity.User;

import java.util.Map;

/**
 * Signup is finished once the phone is on the account and either the legal
 * identity is already stored or the lifestyle step has been saved.
 * A wallet is not part of that check. It is opened later, on the first
 * action that needs one.
 */
public final class ProfileCompletion {

    public static final String LIFESTYLE_COMPLETE = "lifestyleComplete";

    private ProfileCompletion() {
    }

    public static boolean needsProfileUpdate(User user) {
        if (user == null || isBlank(user.getPhone())) {
            return true;
        }
        if (hasIdentity(user)) {
            return false;
        }
        return !isLifestyleComplete(user);
    }

    public static boolean hasIdentity(User user) {
        if (user == null || isBlank(user.getBvn())) {
            return false;
        }
        Map<String, Object> profileData = user.getProfileData();
        return !isBlank(read(profileData, "firstName"))
                && !isBlank(read(profileData, "lastName"));
    }

    public static boolean isLifestyleComplete(User user) {
        if (user == null) {
            return false;
        }
        Map<String, Object> profileData = user.getProfileData();
        if (profileData == null) {
            return false;
        }
        Object flag = profileData.get(LIFESTYLE_COMPLETE);
        if (flag instanceof Boolean) {
            return (Boolean) flag;
        }
        return flag != null && "true".equalsIgnoreCase(flag.toString().trim());
    }

    private static String read(Map<String, Object> profileData, String key) {
        if (profileData == null) {
            return null;
        }
        Object value = profileData.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        if (text.isEmpty() || "null".equalsIgnoreCase(text)) {
            return null;
        }
        return text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
