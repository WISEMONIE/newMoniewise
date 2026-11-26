package com.moniewise.moniewise_backend.utils;

import com.moniewise.moniewise_backend.entity.User;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileCompletionTest {

    @Test
    void aPhoneAndLifestyleAreEnoughToEnterHome() {
        User user = person("08030000000");
        user.setProfileData(lifestyle());

        assertTrue(ProfileCompletion.isLifestyleComplete(user));
        assertFalse(ProfileCompletion.hasIdentity(user));
        assertFalse(ProfileCompletion.needsProfileUpdate(user));
    }

    @Test
    void anExistingIdentityDoesNotReturnToLifestyle() {
        User user = person("08030000000");
        user.setBvn("22435553718");
        Map<String, Object> profile = new HashMap<>();
        profile.put("firstName", "Ada");
        profile.put("lastName", "Okonkwo");
        user.setProfileData(profile);

        assertTrue(ProfileCompletion.hasIdentity(user));
        assertFalse(ProfileCompletion.needsProfileUpdate(user));
    }

    @Test
    void aMissingPhoneStillNeedsTheProfileStep() {
        User user = person("  ");
        user.setProfileData(lifestyle());

        assertTrue(ProfileCompletion.needsProfileUpdate(user));
    }

    @Test
    void aNewAccountWithoutLifestyleStillNeedsTheProfileStep() {
        User user = person("08030000000");
        user.setProfileData(new HashMap<>());

        assertTrue(ProfileCompletion.needsProfileUpdate(user));
    }

    @Test
    void aStringFlagCountsAndABlankNameDoesNot() {
        User user = person("08030000000");
        Map<String, Object> profile = new HashMap<>();
        profile.put(ProfileCompletion.LIFESTYLE_COMPLETE, "true");
        profile.put("firstName", "null");
        profile.put("lastName", "");
        user.setBvn("22435553718");
        user.setProfileData(profile);

        assertTrue(ProfileCompletion.isLifestyleComplete(user));
        assertFalse(ProfileCompletion.hasIdentity(user));
        assertFalse(ProfileCompletion.needsProfileUpdate(user));
    }

    private static User person(String phone) {
        User user = new User();
        user.setPhone(phone);
        return user;
    }

    private static Map<String, Object> lifestyle() {
        Map<String, Object> profile = new HashMap<>();
        profile.put(ProfileCompletion.LIFESTYLE_COMPLETE, Boolean.TRUE);
        profile.put("occupation", "Employee");
        profile.put("mainExpense", "Food");
        profile.put("savingsGoal", "Rent");
        return profile;
    }
}
