package com.moniewise.moniewise_backend.repository;

import com.moniewise.moniewise_backend.entity.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, Long> {

    Optional<WebhookEvent> findByProviderNameAndIdempotencyKey(String providerName, String idempotencyKey);
}