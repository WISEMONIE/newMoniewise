package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.config.GenericNotificationEvent;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.NotificationType;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;
import java.util.Optional;

@Service
public class MonnieCacheInvalidationService {

    private static final Logger logger = LoggerFactory.getLogger(MonnieCacheInvalidationService.class);

    private final ObjectProvider<AiInsightService> aiInsightServiceProvider;
    private final UserRepository userRepository;

    public MonnieCacheInvalidationService(
            ObjectProvider<AiInsightService> aiInsightServiceProvider,
            UserRepository userRepository
    ) {
        this.aiInsightServiceProvider = aiInsightServiceProvider;
        this.userRepository = userRepository;
    }

    public void evictUserAfterCommit(Long userId) {
        if (userId == null) return;
        runAfterCommit(() -> evictUserNow(userId));
    }

    public void evictUsersAfterCommit(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) return;
        runAfterCommit(() -> userIds.stream()
                .filter(id -> id != null)
                .distinct()
                .forEach(this::evictUserNow));
    }

    public void evictUserIdentifierAfterCommit(String identifier) {
        if (identifier == null || identifier.isBlank()) return;
        runAfterCommit(() -> evictUserIdentifierNow(identifier));
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void evictAfterRelevantNotification(GenericNotificationEvent event) {
        if (event == null || !isMonnieRelevant(event.getType())) {
            return;
        }
        evictUserIdentifierNow(event.getUserId());
    }

    private void evictUserIdentifierNow(String identifier) {
        if (identifier == null || identifier.isBlank()) return;

        String trimmed = identifier.trim();
        if (trimmed.matches("\\d+")) {
            evictUserNow(Long.valueOf(trimmed));
            return;
        }

        Optional<User> user = userRepository.findFirstByEmailOrderByCreatedAtAsc(trimmed);
        if (user.isPresent()) {
            evictEmail(user.get().getEmail());
        } else {
            evictEmail(trimmed);
        }
    }

    private void evictUserNow(Long userId) {
        userRepository.findById(userId)
                .map(User::getEmail)
                .ifPresentOrElse(
                        this::evictEmail,
                        () -> logger.debug("[Monnie] Cache eviction skipped; user {} was not found", userId)
                );
    }

    private void evictEmail(String email) {
        if (email == null || email.isBlank()) return;
        AiInsightService aiInsightService = aiInsightServiceProvider.getIfAvailable();
        if (aiInsightService == null) {
            logger.debug("[Monnie] Cache eviction skipped for {}; AiInsightService is not available yet", email);
            return;
        }
        aiInsightService.evictMonnieCache(email);
    }

    private void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private boolean isMonnieRelevant(NotificationType type) {
        if (type == null) return false;

        return switch (type) {
            case WALLET_FUNDED,
                 WALLET_DEPOSIT,
                 WITHDRAWAL,
                 EXTERNAL_TRANSFER,
                 ENVELOPE_TRANSFER,
                 BUDGET_CREATION_FEE,
                 BUDGET_CREATION,
                 BUDGET_CREATION_SUCCESS,
                 BUDGET_UPDATED,
                 BUDGET_COMPLETED,
                 BUDGET_END,
                 BUDGET_EXPIRED,
                 ENVELOPE_CREATED,
                 ENVELOPE_UPDATED,
                 ENVELOPE_DELETED,
                 ENVELOPE_LOCKED,
                 ENVELOPE_UNLOCKED,
                 DISBURSEMENT,
                 DISBURSEMENT_SUCCESS,
                 DISBURSEMENT_FAILED,
                 DISBURSEMENT_READY,
                 EXPIRED_DISBURSEMENT,
                 DISBURSEMENT_REFUNDED,
                 BUDGET_UNALLOCATED_REFUNDED,
                 LIMIT_REACHED,
                 LOW_BALANCE_WARNING,
                 ENVELOPE_LOW_BALANCE,
                 INSUFFICIENT_BALANCE,
                 EMERGENCY_USED,
                 REFUND_ISSUED -> true;
            default -> false;
        };
    }
}
