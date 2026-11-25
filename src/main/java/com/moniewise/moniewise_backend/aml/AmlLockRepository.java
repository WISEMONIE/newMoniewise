package com.moniewise.moniewise_backend.aml;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** A Postgres lock held until the current transaction ends. It never touches table rows. */
public interface AmlLockRepository extends Repository<InflowHold, Long> {

    @Query(value = "SELECT 1 FROM pg_advisory_xact_lock(:key)", nativeQuery = true)
    Integer lock(@Param("key") long key);
}
