package com.moniewise.moniewise_backend.psp.rubies;

import com.moniewise.moniewise_backend.psp.rubies.dto.RubiesCreateWalletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RubiesAccountPrefixTest {

    @Test
    void anIndividualWalletKeepsTheBvnNameAndAsksRubiesToPrefixIt() {
        RubiesCreateWalletRequest request = RubiesCreateWalletRequest.forIndividualWallet(
                "12345678901",
                "Amaka",
                "Okon",
                "amaka@example.com",
                "08030000000",
                "1994-04-12"
        );

        assertEquals("Amaka", request.getFirstName());
        assertEquals("Okon", request.getLastName());
        assertTrue(request.getAccountPrefix());
    }

    @Test
    void theRevenueWalletStaysUnprefixed() {
        RubiesCreateWalletRequest request = new RubiesCreateWalletRequest(
                "12345678901",
                "Moniewise",
                "Technologies",
                "revenue@example.com",
                "08030000000",
                "1990-01-01"
        );

        assertFalse(request.getAccountPrefix());
    }
}
