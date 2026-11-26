package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.request.AnalyticsBatchRequest;
import com.moniewise.moniewise_backend.dto.request.AnalyticsEventRequest;
import com.moniewise.moniewise_backend.dto.response.AnalyticsSummaryResponse;
import com.moniewise.moniewise_backend.entity.UserEvent;
import com.moniewise.moniewise_backend.repository.UserEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Date;
import java.time.LocalDateTime;
import java.util.*;

@Service
public class AnalyticsService {

    private static final Logger logger = LoggerFactory.getLogger(AnalyticsService.class);
    private static final int MAX_BATCH_SIZE = 50;

    @Autowired
    private UserEventRepository userEventRepository;

    @Transactional
    public int ingestBatch(Long userId, AnalyticsBatchRequest request) {
        if (request.getEvents() == null || request.getEvents().isEmpty()) {
            return 0;
        }

        List<AnalyticsEventRequest> events = request.getEvents();
        if (events.size() > MAX_BATCH_SIZE) {
            events = events.subList(0, MAX_BATCH_SIZE);
        }

        List<UserEvent> entities = new ArrayList<>(events.size());
        for (AnalyticsEventRequest event : events) {
            if (event.getEventName() == null || event.getEventName().isBlank()) {
                continue;
            }

            UserEvent entity = new UserEvent();
            entity.setUserId(userId);
            entity.setEventName(event.getEventName().trim());
            entity.setScreenName(event.getScreenName() != null ? event.getScreenName().trim() : null);
            entity.setMetadata(event.getMetadata());
            entity.setDevicePlatform(event.getDevicePlatform());
            entity.setAppVersion(event.getAppVersion());
            entity.setSessionId(event.getSessionId());
            entity.setCreatedAt(LocalDateTime.now());
            entities.add(entity);
        }

        userEventRepository.saveAll(entities);
        logger.info("Ingested {} analytics events for user {}", entities.size(), userId);
        return entities.size();
    }

    public AnalyticsSummaryResponse getSummary(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);

        long activeUsers = userEventRepository.countDistinctUsersSince(since);

        List<Map<String, Object>> topScreens = new ArrayList<>();
        for (Object[] row : userEventRepository.topScreensSince(since, 20)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("screenName", row[0]);
            entry.put("views", ((Number) row[1]).longValue());
            topScreens.add(entry);
        }

        List<Map<String, Object>> eventBreakdown = new ArrayList<>();
        for (Object[] row : userEventRepository.countByEventNameSince(since, 30)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("eventName", row[0]);
            entry.put("count", ((Number) row[1]).longValue());
            eventBreakdown.add(entry);
        }

        List<Map<String, Object>> dau = new ArrayList<>();
        for (Object[] row : userEventRepository.dailyActiveUsersSince(since, days)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("date", row[0] instanceof Date ? row[0].toString() : String.valueOf(row[0]));
            entry.put("users", ((Number) row[1]).longValue());
            dau.add(entry);
        }

        AnalyticsSummaryResponse response = new AnalyticsSummaryResponse();
        response.setActiveUsers(activeUsers);
        response.setTopScreens(topScreens);
        response.setEventBreakdown(eventBreakdown);
        response.setDailyActiveUsers(dau);
        response.setPeriodDays(days);
        return response;
    }
}
