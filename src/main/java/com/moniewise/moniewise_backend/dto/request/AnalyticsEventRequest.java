package com.moniewise.moniewise_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsEventRequest {
    private String eventName;
    private String screenName;
    private Map<String, Object> metadata;
    private String devicePlatform;
    private String appVersion;
    private String sessionId;
    private String clientTimestamp;
}
