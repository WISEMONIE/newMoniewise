package com.moniewise.moniewise_backend.repository;

import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WalletRepositoryQueryTest {

    @Test
    void openingFeeCountDoesNotUsePostgresCastColons() throws Exception {
        Query query = WalletRepository.class
                .getMethod("countRubiesWalletsMissingOpeningFee")
                .getAnnotation(Query.class);

        assertTrue(query.nativeQuery());
        assertTrue(query.value().contains("CAST(w.user_id AS text)"));
        assertFalse(query.value().contains("::"),
                "Hibernate treats :: as an escaped colon and Postgres then rejects the leftover colon");
    }
}
