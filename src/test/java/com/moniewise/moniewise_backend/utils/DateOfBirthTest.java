package com.moniewise.moniewise_backend.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DateOfBirthTest {

    @Test
    void turnsProviderShapesIntoIsoDates() {
        assertEquals("1998-03-19", DateOfBirth.toIso("Mar 19, 1998"));
        assertEquals("1998-03-19", DateOfBirth.toIso("March 19, 1998"));
        assertEquals("1998-03-19", DateOfBirth.toIso("19-Mar-1998"));
        assertEquals("1998-03-19", DateOfBirth.toIso("19-MAR-1998"));
        assertEquals("1998-03-19", DateOfBirth.toIso("19/03/1998"));
        assertEquals("1998-03-19", DateOfBirth.toIso("1998-03-19"));
        assertEquals("1998-03-19", DateOfBirth.toIso(" 1998-03-19T00:00:00 "));
    }

    @Test
    void leavesUnknownTextAlone() {
        assertNull(DateOfBirth.toIso(null));
        assertEquals("", DateOfBirth.toIso("  "));
        assertEquals("sometime", DateOfBirth.toIso("sometime"));
    }
}
