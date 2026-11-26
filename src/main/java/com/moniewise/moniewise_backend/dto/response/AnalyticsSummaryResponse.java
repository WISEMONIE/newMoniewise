package com.moniewise.moniewise_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AnalyticsSummaryResponse {
    private long activeUsers;
    private List<Map<String, Object>> topScreens;
    private List<Map<String, Object>> eventBreakdown;
    private List<Map<String, Object>> dailyActiveUsers;
    private int periodDays;
}
