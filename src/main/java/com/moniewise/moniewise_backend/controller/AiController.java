package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.AiBudgetAssistantTurnRequest;
import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAssistantTurnResponse;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAllocationResponse;
import com.moniewise.moniewise_backend.dto.response.AiDashboardNextActionResponse;
import com.moniewise.moniewise_backend.dto.response.AiStarterEnvelopeResponse;
import com.moniewise.moniewise_backend.service.AiBudgetService;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.AiInsightService;
import com.moniewise.moniewise_backend.service.GeminiService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiBudgetService aiBudgetService;
    private final AiInsightService aiInsightService;
    private final GeminiService geminiService;
    private final AbuseProtectionService abuseProtectionService;

    public AiController(AiBudgetService aiBudgetService,
                        AiInsightService aiInsightService,
                        GeminiService geminiService,
                        AbuseProtectionService abuseProtectionService) {
        this.aiBudgetService = aiBudgetService;
        this.aiInsightService = aiInsightService;
        this.geminiService = geminiService;
        this.abuseProtectionService = abuseProtectionService;
    }

    /**
     * POST /ai/starter-envelopes
     * Volume-limited — every call hits the Gemini API (costs money).
     * 20 AI requests per hour per user+IP across all AI endpoints.
     */
    @PostMapping("/starter-envelopes")
    public ResponseEntity<AiStarterEnvelopeResponse> generateStarterEnvelopes(
            @RequestBody AiStarterEnvelopeRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String throttleKey = abuseProtectionService.buildKey(
                authentication != null ? authentication.getName() : null,
                httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.AI_QUERY, throttleKey);
        AiStarterEnvelopeResponse response = aiBudgetService.generateStarterEnvelopes(request);
        abuseProtectionService.recordRequest(AbuseProtectionService.AI_QUERY, throttleKey);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /ai/budget-allocation
     * Volume-limited — Gemini API call.
     */
    @PostMapping("/budget-allocation")
    public ResponseEntity<AiBudgetAllocationResponse> generateBudgetAllocation(
            @RequestBody AiStarterEnvelopeRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String throttleKey = abuseProtectionService.buildKey(
                authentication != null ? authentication.getName() : null,
                httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.AI_QUERY, throttleKey);
        AiBudgetAllocationResponse response = aiBudgetService.generateBudgetAllocation(request);
        abuseProtectionService.recordRequest(AbuseProtectionService.AI_QUERY, throttleKey);
        return ResponseEntity.ok(response);
    }

    /**
     * POST /ai/budget-assistant/turn
     * Volume-limited — conversational Gemini API call.
     */
    @PostMapping("/budget-assistant/turn")
    public ResponseEntity<AiBudgetAssistantTurnResponse> processBudgetAssistantTurn(
            @RequestBody AiBudgetAssistantTurnRequest request,
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String throttleKey = abuseProtectionService.buildKey(
                authentication != null ? authentication.getName() : null,
                httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.AI_QUERY, throttleKey);
        AiBudgetAssistantTurnResponse response = aiBudgetService.processBudgetAssistantTurn(request);
        abuseProtectionService.recordRequest(AbuseProtectionService.AI_QUERY, throttleKey);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /ai/dashboard-next-action
     * Light rate-limited — the endpoint falls back to a server-ranked deterministic
     * recommendation when Gemini is unavailable, so it is cheap most of the time.
     * Uses the AI_DASHBOARD_ACTION bucket (120/hour) rather than the shared AI_QUERY
     * bucket (20/hour) so normal dashboard navigation never triggers a lockout.
     */
    @GetMapping("/dashboard-next-action")
    public ResponseEntity<AiDashboardNextActionResponse> getDashboardNextAction(
            Authentication authentication,
            HttpServletRequest httpRequest) {

        String throttleKey = abuseProtectionService.buildKey(
                authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.AI_DASHBOARD_ACTION, throttleKey);
        AiDashboardNextActionResponse response = aiInsightService.getDashboardNextAction(authentication.getName());
        abuseProtectionService.recordRequest(AbuseProtectionService.AI_DASHBOARD_ACTION, throttleKey);
        return ResponseEntity.ok(response);
    }

    /**
     * GET /ai/gemini-status
     * Status/diagnostic endpoint — no rate limiting needed.
     */
    @GetMapping("/gemini-status")
    public ResponseEntity<Map<String, Object>> getGeminiStatus() {
        return ResponseEntity.ok(geminiService.getConfigurationStatus());
    }
}
