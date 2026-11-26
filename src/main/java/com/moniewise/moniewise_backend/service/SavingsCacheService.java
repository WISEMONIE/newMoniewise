package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.enums.SavingsStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class SavingsCacheService {

    private static final Logger logger = LoggerFactory.getLogger(SavingsCacheService.class);
    private static final TypeReference<List<SavingsGoal>> SAVINGS_GOAL_LIST =
            new TypeReference<>() {};
    private static final ZoneId LAGOS_ZONE = ZoneId.of("Africa/Lagos");
    private static final String ALL_SAVINGS_CACHE_PREFIX = "savings:all:";
    private static final String ACTIVE_SAVINGS_CACHE_PREFIX = "savings:active:";
    private static final long SAVINGS_LIST_CACHE_TTL_SECONDS = 60;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public SavingsCacheService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<List<SavingsGoal>> getAllSavings(Long userId) {
        return readList(allSavingsKey(userId), userId);
    }

    public Optional<List<SavingsGoal>> getActiveSavings(Long userId) {
        return readList(activeSavingsKey(userId), userId);
    }

    public void putAllSavings(Long userId, List<SavingsGoal> goals) {
        writeList(allSavingsKey(userId), goals);
    }

    public void putActiveSavings(Long userId, List<SavingsGoal> goals) {
        writeList(activeSavingsKey(userId), goals);
    }

    public void evictUserSavingsCachesAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }
        runAfterCommit(() -> evictUserSavingsCachesNow(userId));
    }

    public void evictUsersSavingsCachesAfterCommit(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return;
        }
        runAfterCommit(() -> userIds.stream()
                .filter(id -> id != null)
                .distinct()
                .forEach(this::evictUserSavingsCachesNow));
    }

    private Optional<List<SavingsGoal>> readList(String key, Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        try {
            String cached = redisTemplate.opsForValue().get(key);
            if (cached == null || cached.isBlank()) {
                return Optional.empty();
            }

            List<SavingsGoal> goals = objectMapper.readValue(cached, SAVINGS_GOAL_LIST);
            if (containsMaturedActiveGoal(goals)) {
                evictUserSavingsCachesNow(userId);
                return Optional.empty();
            }
            return Optional.of(goals);
        } catch (Exception e) {
            logger.debug("[SavingsCache] Read failed for key={}: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    private void writeList(String key, List<SavingsGoal> goals) {
        try {
            redisTemplate.opsForValue().set(
                    key,
                    objectMapper.writeValueAsString(goals),
                    SAVINGS_LIST_CACHE_TTL_SECONDS,
                    TimeUnit.SECONDS
            );
        } catch (Exception e) {
            logger.debug("[SavingsCache] Write failed for key={}: {}", key, e.getMessage());
        }
    }

    private boolean containsMaturedActiveGoal(List<SavingsGoal> goals) {
        if (goals == null || goals.isEmpty()) {
            return false;
        }
        LocalDate today = LocalDate.now(LAGOS_ZONE);
        return goals.stream().anyMatch(goal ->
                goal != null
                        && goal.getStatus() == SavingsStatus.ACTIVE
                        && goal.getMaturityDate() != null
                        && !today.isBefore(goal.getMaturityDate()));
    }

    private void evictUserSavingsCachesNow(Long userId) {
        try {
            redisTemplate.delete(List.of(allSavingsKey(userId), activeSavingsKey(userId)));
        } catch (Exception e) {
            logger.debug("[SavingsCache] Evict failed for userId={}: {}", userId, e.getMessage());
        }
    }

    private String allSavingsKey(Long userId) {
        return ALL_SAVINGS_CACHE_PREFIX + userId;
    }

    private String activeSavingsKey(Long userId) {
        return ACTIVE_SAVINGS_CACHE_PREFIX + userId;
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
}
