package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.dto.request.AiBudgetAssistantMessage;
import com.moniewise.moniewise_backend.dto.request.AiBudgetAssistantTurnRequest;
import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAssistantTurnResponse;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAllocationResponse;
import com.moniewise.moniewise_backend.dto.response.AiEnvelopeSuggestion;
import com.moniewise.moniewise_backend.dto.response.AiStarterEnvelopeResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AiBudgetService {

    private static final Logger logger = LoggerFactory.getLogger(AiBudgetService.class);

    private final GeminiService geminiService;
    private final AiPromptService aiPromptService;
    private final ObjectMapper objectMapper;

    public AiBudgetService(
        GeminiService geminiService,
        AiPromptService aiPromptService,
        ObjectMapper objectMapper
    ) {
        this.geminiService = geminiService;
        this.aiPromptService = aiPromptService;
        this.objectMapper = objectMapper;
    }

    public AiStarterEnvelopeResponse generateStarterEnvelopes(AiStarterEnvelopeRequest request) {
        validateRequest(request);

        try {
            String prompt = aiPromptService.buildStarterEnvelopePrompt(request);
            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);

            AiStarterEnvelopeResponse response =
                objectMapper.readValue(cleanedText, AiStarterEnvelopeResponse.class);

            normalizeStarterResponse(response, request);
            validateStarterResponse(response);
            response.setSource("gemini");
            return response;
        } catch (Exception e) {
            logger.warn("Falling back to heuristic starter plan for goal='{}': {}", request.getGoal(), e.getMessage());
            return buildFallbackStarterPlan(request);
        }
    }

    public AiBudgetAllocationResponse generateBudgetAllocation(AiStarterEnvelopeRequest request) {
        validateRequest(request);

        try {
            String prompt = aiPromptService.buildBudgetAllocationPrompt(request);
            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);

            AiBudgetAllocationResponse response =
                objectMapper.readValue(cleanedText, AiBudgetAllocationResponse.class);

            normalizeAllocationResponse(response, request);
            validateAllocationResponse(response);
            response.setSource("gemini");
            return response;
        } catch (Exception e) {
            logger.warn("Falling back to heuristic allocation plan for goal='{}': {}", request.getGoal(), e.getMessage());
            return buildFallbackAllocationPlan(request);
        }
    }

    public AiBudgetAssistantTurnResponse processBudgetAssistantTurn(
        AiBudgetAssistantTurnRequest request
    ) {
        validateAssistantTurnRequest(request);

        try {
            List<AiEnvelopeSuggestion> currentItems = request.getEnvelopes() == null
                ? Collections.emptyList()
                : request.getEnvelopes();
            List<AiEnvelopeSuggestion> normalizedCurrent = normalizeItems(
                currentItems, 100.0, request.getDurationDays());
            double allocatedPercentage = sumPercentages(normalizedCurrent);
            double remainingAmount = computeRemainingAmount(request.getTotalBudget(), allocatedPercentage);

            String currentEnvelopesJson = objectMapper.writeValueAsString(normalizedCurrent);
            String conversationJson = objectMapper.writeValueAsString(trimConversation(request.getMessages()));
            String prompt = aiPromptService.buildBudgetAssistantTurnPrompt(
                safeBudgetName(request.getBudgetName()),
                request.getTotalBudget(),
                request.getDurationDays(),
                safeGoal(request.getGoal()),
                safeCurrency(request.getCurrency()),
                request.getLatestUserMessage().trim(),
                currentEnvelopesJson,
                conversationJson,
                allocatedPercentage,
                remainingAmount
            );

            String rawText = geminiService.generateText(prompt);
            String cleanedText = cleanJson(rawText);

            AiBudgetAssistantTurnResponse response =
                objectMapper.readValue(cleanedText, AiBudgetAssistantTurnResponse.class);

            normalizeAssistantResponse(response, request);
            validateAssistantResponse(response);
            response.setSource("gemini");
            return response;
        } catch (Exception e) {
            logger.warn(
                "Falling back to conversational assistant plan for latestUserMessage='{}': {}",
                request.getLatestUserMessage(),
                e.getMessage()
            );
            return buildFallbackAssistantResponse(request, e);
        }
    }

    private void validateRequest(AiStarterEnvelopeRequest request) {
        if (request.getTotalBudget() == null || request.getTotalBudget() <= 0) {
            throw new IllegalArgumentException("totalBudget must be greater than 0");
        }

        if (request.getDurationDays() == null || request.getDurationDays() <= 0) {
            throw new IllegalArgumentException("durationDays must be greater than 0");
        }

        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }
    }

    private void validateAssistantTurnRequest(AiBudgetAssistantTurnRequest request) {
        if (request.getTotalBudget() == null || request.getTotalBudget() <= 0) {
            throw new IllegalArgumentException("totalBudget must be greater than 0");
        }

        if (request.getDurationDays() == null || request.getDurationDays() <= 0) {
            throw new IllegalArgumentException("durationDays must be greater than 0");
        }

        if (request.getCurrency() == null || request.getCurrency().isBlank()) {
            throw new IllegalArgumentException("currency is required");
        }

        if (request.getLatestUserMessage() == null || request.getLatestUserMessage().isBlank()) {
            throw new IllegalArgumentException("latestUserMessage is required");
        }
    }

    private void validateStarterResponse(AiStarterEnvelopeResponse response) {
        if (response == null || response.getEnvelopes() == null || response.getEnvelopes().isEmpty()) {
            throw new IllegalArgumentException("Invalid AI response");
        }

        double total = validateSuggestionItems(response.getEnvelopes());
        if (total > 100.0) {
            throw new IllegalArgumentException("Total percentage exceeds 100");
        }
    }

    private void validateAllocationResponse(AiBudgetAllocationResponse response) {
        if (response == null || response.getEnvelopes() == null || response.getEnvelopes().isEmpty()) {
            throw new IllegalArgumentException("Invalid AI response");
        }

        double total = validateSuggestionItems(response.getEnvelopes());
        if (total > 100.0) {
            throw new IllegalArgumentException("Total allocation percentage exceeds 100");
        }

        response.setTotalAllocatedPercentage(total);
    }

    private void validateAssistantResponse(AiBudgetAssistantTurnResponse response) {
        if (response == null || response.getEnvelopes() == null) {
            throw new IllegalArgumentException("Invalid assistant response");
        }

        double total = validateSuggestionItems(response.getEnvelopes());
        if (total > 100.0) {
            throw new IllegalArgumentException("Total percentage exceeds 100");
        }
    }

    private double validateSuggestionItems(List<AiEnvelopeSuggestion> items) {
        double total = 0.0;

        for (AiEnvelopeSuggestion item : items) {
            if (item.getName() == null || item.getName().isBlank()) {
                throw new IllegalArgumentException("Envelope name is missing");
            }

            if (item.getPercentage() == null || item.getPercentage() <= 0) {
                throw new IllegalArgumentException("Invalid percentage for envelope: " + item.getName());
            }

            total += item.getPercentage();
        }

        return total;
    }

    private void normalizeStarterResponse(AiStarterEnvelopeResponse response, AiStarterEnvelopeRequest request) {
        if (response == null) {
            return;
        }
        if (response.getTitle() == null || response.getTitle().isBlank()) {
            response.setTitle(buildHeuristicTitle(request, false));
        }
        if (response.getReasoning() == null || response.getReasoning().isBlank()) {
            response.setReasoning(buildHeuristicReasoning(request, false));
        }
        response.setEnvelopes(normalizeItems(response.getEnvelopes(), 100.0, request.getDurationDays()));
    }

    private void normalizeAllocationResponse(AiBudgetAllocationResponse response, AiStarterEnvelopeRequest request) {
        if (response == null) {
            return;
        }
        if (response.getTitle() == null || response.getTitle().isBlank()) {
            response.setTitle(buildHeuristicTitle(request, true));
        }
        if (response.getReasoning() == null || response.getReasoning().isBlank()) {
            response.setReasoning(buildHeuristicReasoning(request, true));
        }
        // Fully allocate to 100% — NOT 95%. Capping at 95 scaled a plan that
        // already summed to 100% (e.g. a "write it out" of 4000 food + 2000
        // offering on a 6000 budget) down to 95%, leaving a permanent ~5% gap
        // the app surfaced as "Left: ₦300". normalizeToFullAllocation closes it.
        List<AiEnvelopeSuggestion> normalized = normalizeToFullAllocation(
            response.getEnvelopes(), request.getDurationDays());
        response.setEnvelopes(normalized);
        response.setTotalAllocatedPercentage(sumPercentages(normalized));
    }

    /**
     * Scales an envelope set to sum to exactly 100% (up or down), so the budget
     * is fully allocated with no residual gap. Reuses {@link #normalizeItems}
     * for dedupe/clamp, then proportionally scales the remainder to 100 and
     * fixes rounding drift on the first item.
     */
    private List<AiEnvelopeSuggestion> normalizeToFullAllocation(
            List<AiEnvelopeSuggestion> items,
            Integer durationDays
    ) {
        List<AiEnvelopeSuggestion> normalized = normalizeItems(items, 100.0, durationDays);
        if (normalized.isEmpty()) {
            return normalized;
        }
        double total = sumPercentages(normalized);
        if (total > 0 && Math.abs(total - 100.0) >= 0.1) {
            double ratio = 100.0 / total;
            double running = 0.0;
            for (AiEnvelopeSuggestion item : normalized) {
                double adjusted = round1(item.getPercentage() * ratio);
                item.setPercentage(Math.max(1.0, adjusted));
                running += item.getPercentage();
            }
            rebalanceRounding(normalized, 100.0, running);
        }
        return normalized;
    }

    private void normalizeAssistantResponse(
        AiBudgetAssistantTurnResponse response,
        AiBudgetAssistantTurnRequest request
    ) {
        if (response == null) {
            return;
        }

        List<AiEnvelopeSuggestion> normalized =
            normalizeItems(response.getEnvelopes(), 100.0, request.getDurationDays());
        double total = sumPercentages(normalized);
        double remainingAmount = computeRemainingAmount(request.getTotalBudget(), total);

        response.setEnvelopes(normalized);
        response.setTotalAllocatedPercentage(round1(total));
        response.setRemainingAmount(round2(remainingAmount));

        if (response.getAssistantMessage() == null || response.getAssistantMessage().isBlank()) {
            response.setAssistantMessage(buildAssistantFollowUpMessage(request, normalized, remainingAmount));
        }

        if (response.getReasoning() == null || response.getReasoning().isBlank()) {
            response.setReasoning(buildAssistantReasoning(request, normalized, remainingAmount));
        }

        boolean ready = Boolean.TRUE.equals(response.getReadyToFinalize())
            && total >= 90.0
            && remainingAmount <= request.getTotalBudget() * 0.1;
        response.setReadyToFinalize(ready);
    }

    private List<AiEnvelopeSuggestion> normalizeItems(List<AiEnvelopeSuggestion> items, double targetCap) {
        return normalizeItems(items, targetCap, null);
    }

    private List<AiEnvelopeSuggestion> normalizeItems(
            List<AiEnvelopeSuggestion> items,
            double targetCap,
            Integer durationDays
    ) {
        if (items == null || items.isEmpty()) {
            return items;
        }

        List<AiEnvelopeSuggestion> normalized = new ArrayList<>();
        double total = 0.0;
        for (AiEnvelopeSuggestion item : items) {
            if (item == null) {
                continue;
            }
            String category = normalizeCategory(item.getCategory());
            String conditionType = normalizeConditionType(item.getConditionType(), durationDays);
            String name = item.getName() == null ? "Envelope" : item.getName().trim();
            if (name.isBlank()) {
                name = defaultNameForCategory(category);
            }

            double percentage = item.getPercentage() == null ? 0.0 : item.getPercentage();
            if (percentage <= 0) {
                continue;
            }

            AiEnvelopeSuggestion normalizedItem = new AiEnvelopeSuggestion();
            normalizedItem.setName(name);
            normalizedItem.setCategory(category);
            normalizedItem.setConditionType(conditionType);
            normalizedItem.setPercentage(percentage);
            normalized.add(normalizedItem);
            total += percentage;
        }

        if (normalized.isEmpty()) {
            return normalized;
        }

        if (total > targetCap && total > 0) {
            double ratio = targetCap / total;
            double adjustedTotal = 0.0;
            for (AiEnvelopeSuggestion item : normalized) {
                double adjusted = round1(item.getPercentage() * ratio);
                item.setPercentage(Math.max(1.0, adjusted));
                adjustedTotal += item.getPercentage();
            }
            rebalanceRounding(normalized, targetCap, adjustedTotal);
        }

        return normalized;
    }

    private void rebalanceRounding(List<AiEnvelopeSuggestion> items, double targetCap, double currentTotal) {
        if (items.isEmpty()) {
            return;
        }
        double diff = round1(targetCap - currentTotal);
        if (Math.abs(diff) < 0.1) {
            return;
        }

        AiEnvelopeSuggestion first = items.get(0);
        first.setPercentage(Math.max(1.0, round1(first.getPercentage() + diff)));
    }

    private String cleanJson(String rawText) {
        if (rawText == null) {
            return "{}";
        }

        String cleaned = rawText.trim();
        if (cleaned.startsWith("```") && cleaned.endsWith("```")) {
            cleaned = cleaned.replace("```json", "")
                .replace("```JSON", "")
                .replace("```", "")
                .trim();
        }

        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        return cleaned;
    }

    private boolean isAllowedConditionType(String value, Integer durationDays) {
        if (value == null) {
            return false;
        }
        if ("interval".equals(value)) {
            return durationDays != null && durationDays >= IntervalReleaseRules.MINIMUM_BUDGET_DAYS;
        }
        return switch (value) {
            case "daily", "weekly", "dynamic", "emergency" -> true;
            default -> false;
        };
    }

    private boolean isAllowedCategory(String value) {
        return value != null && switch (value) {
            case "savings", "security", "food", "car", "home", "education",
                 "flight", "tools", "gift", "work", "internet", "faith",
                 "groceries", "lunch", "more" -> true;
            default -> false;
        };
    }

    private String normalizeConditionType(String value, Integer durationDays) {
        if (isAllowedConditionType(value, durationDays)) {
            return value;
        }
        if ("interval".equals(value)) {
            return "daily";
        }
        return "weekly";
    }

    private String normalizeCategory(String value) {
        if (isAllowedCategory(value)) {
            return value;
        }
        return "more";
    }

    private AiBudgetAssistantTurnResponse buildFallbackAssistantResponse(
        AiBudgetAssistantTurnRequest request,
        Exception fallbackCause
    ) {
        AiBudgetAssistantTurnResponse response = new AiBudgetAssistantTurnResponse();
        List<AiEnvelopeSuggestion> currentItems = request.getEnvelopes() == null
            ? Collections.emptyList()
            : request.getEnvelopes();
        List<AiEnvelopeSuggestion> normalizedCurrent = normalizeItems(
            currentItems, 100.0, request.getDurationDays());

        List<AiEnvelopeSuggestion> envelopes = buildAssistantFallbackSuggestions(
            request,
            normalizedCurrent
        );
        double total = sumPercentages(envelopes);
        double remaining = computeRemainingAmount(request.getTotalBudget(), total);
        boolean userConfirmed = isAffirmingCompletion(request.getLatestUserMessage());

        response.setEnvelopes(envelopes);
        response.setAssistantMessage(buildAssistantFollowUpMessage(request, envelopes, remaining));
        response.setReasoning(buildAssistantReasoning(request, envelopes, remaining));
        response.setReadyToFinalize(
            userConfirmed && total >= 90.0 && remaining <= request.getTotalBudget() * 0.1
        );
        response.setTotalAllocatedPercentage(round1(total));
        response.setRemainingAmount(round2(remaining));
        response.setSource("fallback");
        response.setSourceDetail(buildFallbackSourceDetail(fallbackCause));
        response.setAction(inferFallbackAction(request.getLatestUserMessage(), currentItems, envelopes));
        return response;
    }

    /**
     * Client-safe fallback reason. Raw exception messages can reveal server
     * configuration state (e.g. "GEMINI_API_KEY is missing or blank"), so only
     * a coarse category is sent to the client — the full detail is already in
     * the server logs from the catch block's logger.warn.
     */
    private String buildFallbackSourceDetail(Exception fallbackCause) {
        if (fallbackCause == null) {
            return "Gemini was not used for this turn.";
        }

        Throwable root = fallbackCause;
        while (root.getCause() != null) {
            root = root.getCause();
        }

        String message = root.getMessage() == null ? "" : root.getMessage();
        String rootType = root.getClass().getSimpleName();

        String category;
        if (message.contains("quota") || message.contains("429")) {
            category = "quota";
        } else if (message.contains("timed out") || message.contains("timeout")
            || rootType.contains("Timeout")) {
            category = "timeout";
        } else if (message.contains("missing or blank")) {
            category = "configuration";
        } else if (rootType.contains("Json") || message.contains("Unrecognized")
            || message.contains("Invalid")) {
            category = "invalid_response";
        } else {
            category = "upstream_error";
        }

        return "Gemini unavailable (" + category + "); see server logs for detail.";
    }

    private String inferFallbackAction(
        String latestMessage,
        List<AiEnvelopeSuggestion> before,
        List<AiEnvelopeSuggestion> after
    ) {
        String normalized = latestMessage == null ? "" : latestMessage.toLowerCase(Locale.ROOT);
        // Treat as a query if the message is a question or purely informational
        boolean isQuestion = normalized.endsWith("?")
            || containsAny(normalized, "how much", "what is", "how many", "can you", "tell me", "explain", "why", "show me");
        if (isQuestion && before != null && !before.isEmpty()
            && after != null && after.size() == before.size()) {
            return "QUERY";
        }
        // No prior envelopes → we added them
        if (before == null || before.isEmpty()) {
            return after != null && !after.isEmpty() ? "ADD_ENVELOPE" : "NONE";
        }
        if (after == null || after.isEmpty()) {
            return "REMOVE_ENVELOPE";
        }
        // Detect specific intents
        if (isRenameEnvelopeIntent(normalized)) {
            return "UPDATE_ENVELOPE";
        }
        if (containsAny(normalized, "move") && containsAny(normalized, " from ", " to ")) {
            return "UPDATE_ENVELOPE";
        }
        if (containsAny(normalized, "reduce", "cut", "lower", "increase", "add ")) {
            return after.size() > before.size() ? "ADD_ENVELOPE" : "UPDATE_ENVELOPE";
        }
        if (after.size() > before.size()) {
            return "ADD_ENVELOPE";
        }
        if (after.size() < before.size()) {
            return "REMOVE_ENVELOPE";
        }
        return "REBALANCE";
    }

    private AiStarterEnvelopeResponse buildFallbackStarterPlan(AiStarterEnvelopeRequest request) {
        AiStarterEnvelopeResponse response = new AiStarterEnvelopeResponse();
        response.setSource("fallback");
        response.setTitle(buildHeuristicTitle(request, false));
        response.setReasoning(buildHeuristicReasoning(request, false));
        response.setEnvelopes(buildHeuristicSuggestions(request, 100.0));
        return response;
    }

    private AiBudgetAllocationResponse buildFallbackAllocationPlan(AiStarterEnvelopeRequest request) {
        AiBudgetAllocationResponse response = new AiBudgetAllocationResponse();
        response.setSource("fallback");

        if (request.isInterpretUserPlan()) {
            List<AiEnvelopeSuggestion> parsed = parseUserPlanText(request);
            if (!parsed.isEmpty()) {
                response.setTitle("Your plan — structured");
                response.setReasoning("We structured your plan into envelopes based on what you wrote.");
                response.setEnvelopes(parsed);
                response.setTotalAllocatedPercentage(sumPercentages(parsed));
                return response;
            }
        }

        // The AI Draft brief contains guidance text full of category keywords
        // ("School", "Wedding", "Travel", "rent", "savings"...). Keyword-matching
        // the whole brief would trigger every heuristic branch and produce the
        // same bloated plan every time — match only the actual budget name.
        AiStarterEnvelopeRequest heuristicRequest = withHeuristicGoal(request);
        response.setTitle(buildHeuristicTitle(heuristicRequest, true));
        response.setReasoning(buildHeuristicReasoning(heuristicRequest, true));
        List<AiEnvelopeSuggestion> suggestions = buildHeuristicSuggestions(heuristicRequest, 95.0);
        response.setEnvelopes(suggestions);
        response.setTotalAllocatedPercentage(sumPercentages(suggestions));
        return response;
    }

    /**
     * When the goal field carries a structured prompt brief rather than a raw
     * user goal, reduce it to the budget name so the keyword heuristics react
     * to what the user actually named their budget — not to the brief's own
     * guidance examples.
     */
    private AiStarterEnvelopeRequest withHeuristicGoal(AiStarterEnvelopeRequest request) {
        String goal = request.getGoal() == null ? "" : request.getGoal();
        Matcher nameMatcher = BUDGET_NAME_IN_BRIEF.matcher(goal);
        if (!nameMatcher.find()) {
            return request;
        }

        AiStarterEnvelopeRequest reduced = new AiStarterEnvelopeRequest();
        reduced.setTotalBudget(request.getTotalBudget());
        reduced.setDurationDays(request.getDurationDays());
        reduced.setCurrency(request.getCurrency());
        reduced.setInterpretUserPlan(request.isInterpretUserPlan());
        reduced.setGoal(nameMatcher.group(1));
        return reduced;
    }

    private static final Pattern BUDGET_NAME_IN_BRIEF =
        Pattern.compile("Budget(?:\\s+name)?\\s*:\\s*\"([^\"]+)\"");

    private List<AiEnvelopeSuggestion> parseUserPlanText(AiStarterEnvelopeRequest request) {
        String goal = request.getGoal() == null ? "" : request.getGoal().trim();
        if (goal.isEmpty()) {
            return Collections.emptyList();
        }

        // The goal field for "Write it out" contains the full interpretation prompt.
        // Extract the user's actual text from between the quotes after "What the user wrote:"
        String userText = goal;
        int markerIdx = goal.indexOf("What the user wrote:");
        if (markerIdx >= 0) {
            String afterMarker = goal.substring(markerIdx + "What the user wrote:".length()).trim();
            // Strip surrounding quotes
            if (afterMarker.startsWith("\"")) {
                int closeQuote = afterMarker.indexOf("\"", 1);
                if (closeQuote > 1) {
                    userText = afterMarker.substring(1, closeQuote).trim();
                } else {
                    userText = afterMarker.substring(1).trim();
                }
            } else {
                // Take everything up to the next blank line or "Rules" section
                int rulesIdx = afterMarker.indexOf("Rules for interpreting");
                if (rulesIdx > 0) {
                    userText = afterMarker.substring(0, rulesIdx).trim();
                } else {
                    userText = afterMarker;
                }
            }
        }

        if (userText.isEmpty()) {
            return Collections.emptyList();
        }

        double totalBudget = request.getTotalBudget() == null ? 0 : request.getTotalBudget();

        // Split on commas, newlines, semicolons, or " and "
        String[] segments = userText.split("[,;\\n]+|\\band\\b");
        List<AiEnvelopeSuggestion> results = new ArrayList<>();

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty()) {
                continue;
            }

            // Try to extract a name and a number from each segment
            // Patterns: "food 30", "30 food", "food 30%", "food 21000", "food ₦21,000", "food 20k"
            Matcher nameNumber = Pattern.compile(
                "([a-zA-Z][a-zA-Z\\s'-]*)\\s+[₦#N]?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmM]?)\\s*%?"
            ).matcher(trimmed);
            Matcher numberName = Pattern.compile(
                "[₦#N]?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmM]?)\\s*%?\\s+([a-zA-Z][a-zA-Z\\s'-]*)"
            ).matcher(trimmed);
            Matcher nameOnly = Pattern.compile(
                "^\\s*([a-zA-Z][a-zA-Z\\s'-]*)\\s*$"
            ).matcher(trimmed);

            String envelopeName = null;
            double rawNumber = -1;
            boolean hasAmountSuffix = false;

            if (nameNumber.find()) {
                envelopeName = nameNumber.group(1).trim();
                rawNumber = parseRawNumber(nameNumber.group(2));
                hasAmountSuffix = !nameNumber.group(3).isEmpty();
                if (hasAmountSuffix) {
                    rawNumber *= nameNumber.group(3).equalsIgnoreCase("k") ? 1_000 : 1_000_000;
                }
            } else if (numberName.find()) {
                rawNumber = parseRawNumber(numberName.group(1));
                hasAmountSuffix = !numberName.group(2).isEmpty();
                if (hasAmountSuffix) {
                    rawNumber *= numberName.group(2).equalsIgnoreCase("k") ? 1_000 : 1_000_000;
                }
                envelopeName = numberName.group(3).trim();
            } else if (nameOnly.matches()) {
                envelopeName = nameOnly.group(1).trim();
            }

            if (envelopeName == null || envelopeName.isEmpty()) {
                continue;
            }

            // Title-case the name
            envelopeName = titleCaseEnvelopeName(envelopeName);
            if (envelopeName.isEmpty()) {
                continue;
            }

            AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
            item.setName(envelopeName);

            if (rawNumber > 0) {
                boolean explicitAmount = hasAmountSuffix
                    || trimmed.contains("₦")
                    || trimmed.contains("#");
                if (explicitAmount && totalBudget > 0) {
                    // "20k" / "₦5,000" — definitely an amount, convert to %
                    item.setPercentage(round1(Math.min(100.0, (rawNumber / totalBudget) * 100.0)));
                } else if (rawNumber <= 100) {
                    // Bare number ≤ 100 — treat as percentage
                    item.setPercentage(rawNumber);
                } else if (totalBudget > 0) {
                    // Bare number > 100 — treat as amount, convert to %
                    item.setPercentage(round1(Math.min(100.0, (rawNumber / totalBudget) * 100.0)));
                } else {
                    item.setPercentage(0.0);
                }
            } else {
                item.setPercentage(0.0);
            }

            // Guess category and condition type from the envelope name
            String lowerName = envelopeName.toLowerCase(Locale.ROOT);
            item.setCategory(guessCategoryFromName(lowerName));
            item.setConditionType(guessConditionTypeFromName(lowerName, request));

            // Merge duplicates ("food 30, food 20" → one Food at 50%)
            AiEnvelopeSuggestion existing = findSuggestionByLabel(results, envelopeName);
            if (existing != null) {
                double combined = (existing.getPercentage() == null ? 0.0 : existing.getPercentage())
                    + (item.getPercentage() == null ? 0.0 : item.getPercentage());
                existing.setPercentage(round1(combined));
                continue;
            }

            results.add(item);
        }

        // Numbers-only input ("30000, 20000, 10000" or "40, 30, 30") — label
        // generically in the order given, mirroring rule 3 of the AI prompt.
        if (results.isEmpty()) {
            List<Double> bareNumbers = new ArrayList<>();
            Matcher numMatcher = Pattern
                .compile("[₦#]?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmM]?)")
                .matcher(userText);
            while (numMatcher.find()) {
                double value = parseRawNumber(numMatcher.group(1));
                if (value <= 0) continue;
                if (!numMatcher.group(2).isEmpty()) {
                    value *= numMatcher.group(2).equalsIgnoreCase("k") ? 1_000 : 1_000_000;
                }
                bareNumbers.add(value);
            }

            boolean allPercentages = !bareNumbers.isEmpty()
                && bareNumbers.stream().allMatch(v -> v <= 100);
            for (int i = 0; i < bareNumbers.size(); i++) {
                double value = bareNumbers.get(i);
                double pct = allPercentages
                    ? value
                    : (totalBudget > 0 ? round1(Math.min(100.0, (value / totalBudget) * 100.0)) : 0.0);
                if (pct <= 0) continue;
                AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
                item.setName("Envelope " + (i + 1));
                item.setCategory("more");
                item.setConditionType("dynamic");
                item.setPercentage(pct);
                results.add(item);
            }
        }

        if (results.isEmpty()) {
            return Collections.emptyList();
        }

        // Handle zero-percentage items: split equally
        long zeroCount = results.stream().filter(i -> i.getPercentage() == null || i.getPercentage() <= 0).count();
        double allocatedTotal = results.stream().mapToDouble(i -> i.getPercentage() == null ? 0 : i.getPercentage()).sum();

        if (zeroCount == results.size()) {
            // All names, no numbers — split equally
            double equalShare = round1(100.0 / results.size());
            double running = 0;
            for (int i = 0; i < results.size(); i++) {
                double pct = i == results.size() - 1 ? round1(100.0 - running) : equalShare;
                results.get(i).setPercentage(Math.max(4.0, pct));
                running += results.get(i).getPercentage();
            }
        } else if (zeroCount > 0) {
            // Mixed: some have numbers, some don't — distribute remaining
            double remaining = Math.max(0, 100.0 - allocatedTotal);
            double shared = zeroCount > 0 ? round1(remaining / zeroCount) : 0;
            double running = allocatedTotal;
            for (int i = 0; i < results.size(); i++) {
                AiEnvelopeSuggestion item = results.get(i);
                if (item.getPercentage() != null && item.getPercentage() > 0) continue;
                double pct = Math.max(4.0, shared);
                item.setPercentage(pct);
                running += pct;
            }
        }

        // Normalize to 100%
        results = normalizeItems(results, 100.0, request.getDurationDays());

        // If total < 95%, add Savings for the remainder
        double total = sumPercentages(results);
        if (total < 95.0) {
            AiEnvelopeSuggestion savings = new AiEnvelopeSuggestion();
            savings.setName("Savings");
            savings.setCategory("savings");
            savings.setConditionType("dynamic");
            savings.setPercentage(round1(100.0 - total));
            results.add(savings);
        }

        return results;
    }

    private double parseRawNumber(String raw) {
        if (raw == null || raw.isBlank()) return -1;
        try {
            return Double.parseDouble(raw.replace(",", ""));
        } catch (Exception e) {
            return -1;
        }
    }

    private String guessCategoryFromName(String lowerName) {
        if (containsAny(lowerName, "save", "savings", "buffer")) return "savings";
        if (containsAny(lowerName, "emergency")) return "security";
        if (containsAny(lowerName, "food", "feeding", "meal")) return "food";
        if (containsAny(lowerName, "groceries", "market")) return "groceries";
        if (containsAny(lowerName, "lunch", "breakfast", "dinner")) return "lunch";
        if (containsAny(lowerName, "transport", "fuel", "cab", "uber", "bolt")) return "car";
        if (containsAny(lowerName, "rent", "house", "home", "bill", "utility", "light", "electric", "nepa")) return "home";
        if (containsAny(lowerName, "school", "tuition", "education", "book")) return "education";
        if (containsAny(lowerName, "flight", "travel", "trip", "vacation")) return "flight";
        if (containsAny(lowerName, "tool", "equipment")) return "tools";
        if (containsAny(lowerName, "gift", "giving", "present")) return "gift";
        if (containsAny(lowerName, "work", "business", "client", "hustle")) return "work";
        if (containsAny(lowerName, "data", "internet", "wifi", "airtime")) return "internet";
        if (containsAny(lowerName, "tithe", "offering", "church", "faith", "mosque")) return "faith";
        return "more";
    }

    private String guessConditionTypeFromName(String lowerName, AiStarterEnvelopeRequest request) {
        if (containsAny(lowerName, "emergency")) return "emergency";
        if (containsAny(lowerName, "lunch", "breakfast", "dinner", "feeding")) return "daily";
        int days = request.getDurationDays() == null ? 30 : request.getDurationDays();
        if (days <= 8) return "daily";
        if (containsAny(lowerName, "tithe", "offering", "church")) return "weekly";
        if (containsAny(lowerName, "food", "groceries", "transport", "data")) return "weekly";
        return "dynamic";
    }

    private List<AiEnvelopeSuggestion> buildHeuristicSuggestions(AiStarterEnvelopeRequest request, double targetTotal) {
        String goal = request.getGoal() == null ? "" : request.getGoal().toLowerCase(Locale.ROOT);
        boolean savingsHeavy = containsAny(goal, "save", "savings", "buffer", "emergency", "discipline");
        boolean essentialsHeavy = containsAny(goal, "essential", "essentials", "discipline", "control", "tight", "lean", "rent", "bills");
        boolean travelHeavy = containsAny(goal, "travel", "flight", "trip", "vacation");
        boolean workHeavy = containsAny(goal, "work", "business", "client", "tools", "data", "internet");
        boolean educationHeavy = containsAny(goal, "school", "education", "tuition", "books");
        boolean faithHeavy = containsAny(goal, "faith", "tithe", "offering", "church");
        boolean foodHeavy = containsAny(goal, "food", "groceries", "feeding", "meal", "lunch");

        LinkedHashMap<String, SuggestionSeed> seeds = new LinkedHashMap<>();
        addSeed(seeds, foodHeavy ? "Groceries" : "Food", foodHeavy ? "groceries" : "food", chooseRecurringType(request, "food"), 22);
        addSeed(seeds, essentialsHeavy ? "Bills" : "Home", "home", chooseRecurringType(request, "home"), essentialsHeavy ? 24 : 18);
        addSeed(seeds, "Transport", "car", chooseRecurringType(request, "car"), travelHeavy ? 18 : 12);

        if (savingsHeavy) {
            addSeed(seeds, goal.contains("emergency") ? "Emergency Buffer" : "Savings", goal.contains("emergency") ? "security" : "savings", goal.contains("emergency") ? "emergency" : "dynamic", goal.contains("emergency") ? 22 : 20);
        } else {
            addSeed(seeds, "Savings", "savings", "dynamic", 12);
        }

        if (educationHeavy) {
            addSeed(seeds, "Education", "education", chooseRecurringType(request, "education"), 15);
        }
        if (workHeavy) {
            addSeed(seeds, goal.contains("internet") || goal.contains("data") ? "Data" : "Work Tools", goal.contains("internet") || goal.contains("data") ? "internet" : "tools", chooseRecurringType(request, "internet"), 12);
        }
        if (travelHeavy) {
            addSeed(seeds, "Travel", "flight", "dynamic", 16);
        }
        if (faithHeavy) {
            addSeed(seeds, goal.contains("offering") ? "Offering" : "Tithe", "faith", "weekly", 8);
        }

        addSeed(seeds, "Misc", "more", "daily", essentialsHeavy ? 6 : 10);

        List<SuggestionSeed> selected = new ArrayList<>(seeds.values());
        if (selected.size() < 4) {
            addSeed(seeds, "Lunch", "lunch", "daily", 8);
            selected = new ArrayList<>(seeds.values());
        }

        if (selected.size() > 6) {
            selected = selected.subList(0, 6);
        }

        double totalWeight = selected.stream().mapToDouble(seed -> seed.weight).sum();
        List<AiEnvelopeSuggestion> result = new ArrayList<>();
        double runningTotal = 0.0;

        for (int i = 0; i < selected.size(); i++) {
            SuggestionSeed seed = selected.get(i);
            double percentage = round1((seed.weight / totalWeight) * targetTotal);
            if (i == selected.size() - 1) {
                percentage = round1(targetTotal - runningTotal);
            }
            percentage = Math.max(4.0, percentage);
            runningTotal += percentage;
            result.add(buildItem(seed.name, percentage, seed.conditionType, seed.category));
        }

        return result;
    }

    private List<AiEnvelopeSuggestion> buildAssistantFallbackSuggestions(
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> normalizedCurrent
    ) {
        String latestMessage = request.getLatestUserMessage() == null
            ? ""
            : request.getLatestUserMessage().trim();
        List<AiEnvelopeSuggestion> requestedFromMessage = parseRequestedEnvelopes(
            latestMessage,
            request
        );

        if (normalizedCurrent.isEmpty() && requestedFromMessage.isEmpty()) {
            return buildHeuristicSuggestions(toStarterRequest(request), 100.0);
        }

        LinkedHashMap<String, AiEnvelopeSuggestion> merged = new LinkedHashMap<>();
        for (AiEnvelopeSuggestion item : normalizedCurrent) {
            merged.put(item.getName().trim().toLowerCase(Locale.ROOT), cloneSuggestion(item));
        }

        for (AiEnvelopeSuggestion requested : requestedFromMessage) {
            String key = requested.getName().trim().toLowerCase(Locale.ROOT);
            AiEnvelopeSuggestion existing = merged.get(key);
            if (existing == null) {
                merged.put(key, requested);
                continue;
            }

            existing.setCategory(normalizeCategory(requested.getCategory()));
            existing.setConditionType(normalizeConditionType(
                requested.getConditionType(), request.getDurationDays()));
            if (requested.getPercentage() != null && requested.getPercentage() > 0) {
                existing.setPercentage(requested.getPercentage());
            }
        }

        List<AiEnvelopeSuggestion> mergedItems = new ArrayList<>(merged.values());
        boolean hasExplicitPercentages = requestedFromMessage.stream()
            .anyMatch(item -> item.getPercentage() != null && item.getPercentage() > 0);

        if (!hasExplicitPercentages) {
            rebalanceAssistantSuggestions(
                mergedItems,
                normalizedCurrent,
                request.getTotalBudget() == null ? 100.0 : 100.0,
                request.getDurationDays()
            );
        } else {
            mergedItems = normalizeItems(mergedItems, 100.0, request.getDurationDays());
        }

        applyRenameEnvelopeIntent(latestMessage, request, mergedItems);
        applyMoveAmountIntent(latestMessage, request, mergedItems);
        applyReduceEnvelopeIntent(latestMessage, request, mergedItems);
        applyUnallocatedAmountIntent(latestMessage, request, mergedItems);
        applySplitRemainingIntent(latestMessage, request, mergedItems);
        pruneZeroPercentSuggestions(mergedItems);
        return mergedItems;
    }

    private void rebalanceAssistantSuggestions(
        List<AiEnvelopeSuggestion> mergedItems,
        List<AiEnvelopeSuggestion> normalizedCurrent,
        double targetTotal,
        Integer durationDays
    ) {
        if (mergedItems.isEmpty()) {
            return;
        }

        double currentTotal = sumPercentages(mergedItems);
        if (currentTotal <= 0) {
            double equalShare = round1(targetTotal / mergedItems.size());
            double running = 0.0;
            for (int i = 0; i < mergedItems.size(); i++) {
                double pct = i == mergedItems.size() - 1
                    ? round1(targetTotal - running)
                    : equalShare;
                mergedItems.get(i).setPercentage(Math.max(4.0, pct));
                running += mergedItems.get(i).getPercentage();
            }
            return;
        }

        if (!normalizedCurrent.isEmpty() && currentTotal <= targetTotal) {
            return;
        }

        List<AiEnvelopeSuggestion> normalized = normalizeItems(mergedItems, targetTotal, durationDays);
        mergedItems.clear();
        mergedItems.addAll(normalized);
    }

    private List<AiEnvelopeSuggestion> parseRequestedEnvelopes(
        String latestMessage,
        AiBudgetAssistantTurnRequest request
    ) {
        if (latestMessage.isBlank()) {
            return Collections.emptyList();
        }

        List<EnvelopeKeyword> keywords = buildEnvelopeKeywords(request);

        String normalizedMessage = latestMessage.toLowerCase(Locale.ROOT);
        List<AiEnvelopeSuggestion> suggestions = new ArrayList<>();

        for (EnvelopeKeyword keyword : keywords) {
            if (!normalizedMessage.contains(keyword.phrase)) {
                continue;
            }
            Double explicitPercentage = extractRequestedPercentage(normalizedMessage, keyword.phrase);
            AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
            item.setName(keyword.label);
            item.setCategory(keyword.category);
            item.setConditionType(keyword.conditionType);
            item.setPercentage(explicitPercentage != null ? explicitPercentage : 0.0);
            suggestions.add(item);
        }

        // If no keywords matched, try extracting free-form envelope names from
        // patterns like "add X", "create X", "I want X", "something for X"
        if (suggestions.isEmpty()) {
            suggestions = extractFreeFormEnvelopeNames(normalizedMessage, request);
        }

        if (suggestions.isEmpty()) {
            return Collections.emptyList();
        }

        long zeroCount = suggestions.stream()
            .filter(item -> item.getPercentage() == null || item.getPercentage() <= 0)
            .count();
        double allocatedExplicit = suggestions.stream()
            .mapToDouble(item -> item.getPercentage() == null ? 0.0 : item.getPercentage())
            .sum();
        double remaining = Math.max(0.0, 100.0 - allocatedExplicit);
        double shared = zeroCount > 0 ? round1(remaining / zeroCount) : 0.0;

        double running = allocatedExplicit;
        for (int i = 0; i < suggestions.size(); i++) {
            AiEnvelopeSuggestion item = suggestions.get(i);
            if (item.getPercentage() != null && item.getPercentage() > 0) {
                continue;
            }
            double pct = i == suggestions.size() - 1
                ? round1(Math.max(0.0, 100.0 - running))
                : shared;
            item.setPercentage(Math.max(4.0, pct));
            running += item.getPercentage();
        }

        return normalizeItems(suggestions, 100.0, request.getDurationDays());
    }

    private List<AiEnvelopeSuggestion> extractFreeFormEnvelopeNames(
        String normalizedMessage,
        AiBudgetAssistantTurnRequest request
    ) {
        List<AiEnvelopeSuggestion> results = new ArrayList<>();
        List<Pattern> patterns = List.of(
            Pattern.compile("(?:add|create|include|want|need)\\s+(?:a\\s+|an\\s+)?(?:envelope\\s+(?:for|called)\\s+)?([a-z][a-z\\s'-]{1,25})"),
            Pattern.compile("(?:something|one)\\s+for\\s+([a-z][a-z\\s'-]{1,25})"),
            Pattern.compile("([a-z][a-z\\s'-]{1,25})\\s+envelope")
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(normalizedMessage);
            while (matcher.find()) {
                String rawName = matcher.group(1).trim()
                    .replaceAll("\\b(?:the|my|an?|for|please|envelope|with)\\b", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
                if (rawName.length() < 2 || rawName.length() > 30) continue;

                String name = titleCaseEnvelopeName(rawName);
                if (name.isEmpty()) continue;

                boolean alreadyAdded = results.stream()
                    .anyMatch(item -> item.getName().equalsIgnoreCase(name));
                if (alreadyAdded) continue;

                AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
                item.setName(name);
                String lower = name.toLowerCase(Locale.ROOT);
                item.setCategory(guessCategoryFromName(lower));
                item.setConditionType(guessConditionTypeFromName(lower, toStarterRequest(request)));
                item.setPercentage(0.0);
                results.add(item);
            }
        }

        return results;
    }

    private Double extractRequestedPercentage(String message, String phrase) {
        Pattern before = Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*%\\s+(?:for\\s+)?"
            + Pattern.quote(phrase));
        Matcher beforeMatcher = before.matcher(message);
        if (beforeMatcher.find()) {
            return parsePercentage(beforeMatcher.group(1));
        }

        Pattern after = Pattern.compile(Pattern.quote(phrase)
            + "(?:\\s+at|\\s+for|\\s*=|\\s*:)??\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*%");
        Matcher afterMatcher = after.matcher(message);
        if (afterMatcher.find()) {
            return parsePercentage(afterMatcher.group(1));
        }

        return null;
    }

    private List<EnvelopeKeyword> buildEnvelopeKeywords(AiBudgetAssistantTurnRequest request) {
        AiStarterEnvelopeRequest starter = toStarterRequest(request);
        return List.of(
            new EnvelopeKeyword("emergency buffer", "Emergency Buffer", "security", "emergency"),
            new EnvelopeKeyword("emergency fund", "Emergency Fund", "security", "emergency"),
            new EnvelopeKeyword("school fee", "School Fee", "education", chooseRecurringType(starter, "education")),
            new EnvelopeKeyword("school fees", "School Fees", "education", chooseRecurringType(starter, "education")),
            new EnvelopeKeyword("school runs", "School Runs", "education", chooseRecurringType(starter, "education")),
            new EnvelopeKeyword("tuition", "Tuition", "education", chooseRecurringType(starter, "education")),
            new EnvelopeKeyword("work tools", "Work Tools", "tools", chooseRecurringType(starter, "tools")),
            new EnvelopeKeyword("client transport", "Client Transport", "car", chooseRecurringType(starter, "car")),
            new EnvelopeKeyword("fuel", "Fuel", "car", chooseRecurringType(starter, "car")),
            new EnvelopeKeyword("uber", "Transport", "car", chooseRecurringType(starter, "car")),
            new EnvelopeKeyword("bolt", "Transport", "car", chooseRecurringType(starter, "car")),
            new EnvelopeKeyword("cab", "Transport", "car", chooseRecurringType(starter, "car")),
            new EnvelopeKeyword("rent", "Rent", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("bills", "Bills", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("utility", "Utilities", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("utilities", "Utilities", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("light bill", "Light Bill", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("nepa", "Light Bill", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("electric", "Electricity", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("groceries", "Groceries", "groceries", chooseRecurringType(starter, "groceries")),
            new EnvelopeKeyword("market", "Market", "groceries", chooseRecurringType(starter, "groceries")),
            new EnvelopeKeyword("food", "Food", "food", chooseRecurringType(starter, "food")),
            new EnvelopeKeyword("feeding", "Feeding", "food", chooseRecurringType(starter, "food")),
            new EnvelopeKeyword("meal", "Meals", "food", chooseRecurringType(starter, "food")),
            new EnvelopeKeyword("transport", "Transport", "car", chooseRecurringType(starter, "car")),
            new EnvelopeKeyword("savings", "Savings", "savings", "dynamic"),
            new EnvelopeKeyword("save", "Savings", "savings", "dynamic"),
            new EnvelopeKeyword("data", "Data", "internet", chooseRecurringType(starter, "internet")),
            new EnvelopeKeyword("airtime", "Airtime", "internet", chooseRecurringType(starter, "internet")),
            new EnvelopeKeyword("internet", "Internet", "internet", chooseRecurringType(starter, "internet")),
            new EnvelopeKeyword("wifi", "Internet", "internet", chooseRecurringType(starter, "internet")),
            new EnvelopeKeyword("tithe", "Tithe", "faith", "weekly"),
            new EnvelopeKeyword("offering", "Offering", "faith", "weekly"),
            new EnvelopeKeyword("church", "Church", "faith", "weekly"),
            new EnvelopeKeyword("mosque", "Mosque", "faith", "weekly"),
            new EnvelopeKeyword("travel", "Travel", "flight", "dynamic"),
            new EnvelopeKeyword("trip", "Trip", "flight", "dynamic"),
            new EnvelopeKeyword("vacation", "Vacation", "flight", "dynamic"),
            new EnvelopeKeyword("housing", "Housing", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("education", "Education", "education", chooseRecurringType(starter, "education")),
            new EnvelopeKeyword("books", "Books", "education", chooseRecurringType(starter, "education")),
            new EnvelopeKeyword("lunch", "Lunch", "lunch", "daily"),
            new EnvelopeKeyword("breakfast", "Breakfast", "lunch", "daily"),
            new EnvelopeKeyword("dinner", "Dinner", "lunch", "daily"),
            new EnvelopeKeyword("snacks", "Snacks", "lunch", "daily"),
            new EnvelopeKeyword("home", "Home", "home", chooseRecurringType(starter, "home")),
            new EnvelopeKeyword("clothing", "Clothing", "more", "dynamic"),
            new EnvelopeKeyword("clothes", "Clothing", "more", "dynamic"),
            new EnvelopeKeyword("health", "Health", "more", "dynamic"),
            new EnvelopeKeyword("medical", "Medical", "more", "dynamic"),
            new EnvelopeKeyword("gym", "Gym", "more", "dynamic"),
            new EnvelopeKeyword("entertainment", "Entertainment", "more", "dynamic"),
            new EnvelopeKeyword("fun", "Fun Money", "more", "dynamic"),
            new EnvelopeKeyword("gift", "Gifts", "gift", "dynamic"),
            new EnvelopeKeyword("hustle", "Hustle", "work", "dynamic"),
            new EnvelopeKeyword("business", "Business", "work", "dynamic"),
            new EnvelopeKeyword("misc", "Misc", "more", "daily"),
            new EnvelopeKeyword("miscellaneous", "Misc", "more", "daily"),
            new EnvelopeKeyword("other", "Other", "more", "daily"),
            new EnvelopeKeyword("flex", "Flex", "more", "dynamic")
        );
    }

    private void applyRenameEnvelopeIntent(
        String latestMessage,
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> mergedItems
    ) {
        RenameIntent intent = parseRenameIntent(latestMessage);
        if (intent == null || mergedItems.isEmpty()) {
            return;
        }

        AiEnvelopeSuggestion item = findSuggestionByNameOrKeyword(
            mergedItems,
            intent.sourceName,
            buildEnvelopeKeywords(request)
        );
        if (item == null) {
            return;
        }

        String newName = titleCaseEnvelopeName(intent.targetName);
        if (newName.isBlank()) {
            return;
        }

        item.setName(newName);
        EnvelopeKeyword targetKeyword = matchEnvelopeKeyword(intent.targetName, buildEnvelopeKeywords(request));
        if (targetKeyword != null) {
            item.setCategory(targetKeyword.category);
            item.setConditionType(targetKeyword.conditionType);
        }
    }

    private RenameIntent parseRenameIntent(String latestMessage) {
        if (!isRenameEnvelopeIntent(latestMessage)) {
            return null;
        }

        String normalized = latestMessage.toLowerCase(Locale.ROOT).trim();
        List<Pattern> patterns = List.of(
            Pattern.compile("(?:rename|change)\\s+(?:the\\s+)?(?:envelope\\s+)?(?:name\\s+)?from\\s+(.+?)\\s+to\\s+(.+)$"),
            Pattern.compile("(?:rename|change)\\s+(?:the\\s+)?(.+?)\\s+(?:envelope\\s+)?(?:name\\s+)?to\\s+(.+)$")
        );

        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(normalized);
            if (!matcher.find()) {
                continue;
            }

            String source = cleanRenameSegment(matcher.group(1));
            String target = cleanRenameSegment(matcher.group(2));
            if (!source.isBlank() && !target.isBlank()) {
                return new RenameIntent(source, target);
            }
        }

        return null;
    }

    private boolean isRenameEnvelopeIntent(String message) {
        if (message == null || message.isBlank()) {
            return false;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        return containsAny(normalized, "rename", "change")
            && containsAny(normalized, " to ")
            && (
                containsAny(normalized, "envelope", "name", " from ")
                || Pattern.compile("(?:rename|change)\\s+\\w+\\s+to\\s+\\w+").matcher(normalized).find()
            );
    }

    private String cleanRenameSegment(String value) {
        if (value == null) {
            return "";
        }

        return value
            .replaceAll("\\b(?:the|my|envelope|name|from|to|please|i\\s+want\\s+to|i\\s+want|i\\s+would\\s+like\\s+to)\\b", " ")
            .replaceAll("[^a-z0-9\\s-]", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private AiEnvelopeSuggestion findSuggestionByNameOrKeyword(
        List<AiEnvelopeSuggestion> items,
        String sourceName,
        List<EnvelopeKeyword> keywords
    ) {
        if (sourceName == null || sourceName.isBlank()) {
            return null;
        }

        for (AiEnvelopeSuggestion item : items) {
            if (item.getName() != null && item.getName().equalsIgnoreCase(sourceName.trim())) {
                return item;
            }
        }

        EnvelopeKeyword keyword = matchEnvelopeKeyword(sourceName, keywords);
        return keyword == null ? null : findSuggestionByLabel(items, keyword.label);
    }

    private String titleCaseEnvelopeName(String value) {
        String cleaned = cleanRenameSegment(value);
        if (cleaned.isBlank()) {
            return "";
        }

        String[] parts = cleaned.split("\\s+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(Locale.ROOT));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.toString();
    }

    private void applyMoveAmountIntent(
        String latestMessage,
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> mergedItems
    ) {
        if (latestMessage == null || latestMessage.isBlank() || mergedItems.isEmpty()) {
            return;
        }

        String normalized = latestMessage.toLowerCase(Locale.ROOT);
        if (!normalized.contains("move") || !normalized.contains(" from ") || !normalized.contains(" to ")) {
            return;
        }

        Pattern pattern = Pattern.compile(
            "move\\s+(?:about\\s+)?(?:ngn|naira|₦)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmM]?)\\s+from\\s+(.+?)\\s+to\\s+(.+)"
        );
        Matcher matcher = pattern.matcher(normalized);
        if (!matcher.find()) {
            return;
        }

        Double amount = parseCurrencyAmount(matcher.group(1), matcher.group(2));
        Double totalBudget = request.getTotalBudget();
        if (amount == null || amount <= 0 || totalBudget == null || totalBudget <= 0) {
            return;
        }

        List<EnvelopeKeyword> keywords = buildEnvelopeKeywords(request);
        EnvelopeKeyword sourceKeyword = matchEnvelopeKeyword(matcher.group(3), keywords);
        EnvelopeKeyword targetKeyword = matchEnvelopeKeyword(matcher.group(4), keywords);
        if (sourceKeyword == null || targetKeyword == null) {
            return;
        }

        AiEnvelopeSuggestion source = findSuggestionByLabel(mergedItems, sourceKeyword.label);
        if (source == null || source.getPercentage() == null || source.getPercentage() <= 0) {
            return;
        }

        AiEnvelopeSuggestion target = findSuggestionByLabel(mergedItems, targetKeyword.label);
        if (target == null) {
            target = buildItem(targetKeyword.label, 0.0, targetKeyword.conditionType, targetKeyword.category);
            mergedItems.add(target);
        }

        double percentageToMove = Math.min(source.getPercentage(), (amount / totalBudget) * 100.0);
        if (percentageToMove <= 0) {
            return;
        }

        source.setPercentage(round1(Math.max(0.0, source.getPercentage() - percentageToMove)));
        target.setPercentage(round1((target.getPercentage() == null ? 0.0 : target.getPercentage()) + percentageToMove));
    }

    private void applyReduceEnvelopeIntent(
        String latestMessage,
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> mergedItems
    ) {
        if (latestMessage == null || latestMessage.isBlank() || mergedItems.isEmpty()) {
            return;
        }

        String normalized = latestMessage.toLowerCase(Locale.ROOT);
        if (!containsAny(normalized, "reduce", "cut", "lower")) {
            return;
        }

        List<EnvelopeKeyword> keywords = buildEnvelopeKeywords(request);
        Pattern percentPattern = Pattern.compile("(?:reduce|cut|lower)\\s+(.+?)\\s+by\\s+(\\d{1,3}(?:\\.\\d+)?)\\s*%");
        Matcher percentMatcher = percentPattern.matcher(normalized);
        if (percentMatcher.find()) {
            EnvelopeKeyword keyword = matchEnvelopeKeyword(percentMatcher.group(1), keywords);
            Double reduction = parsePercentage(percentMatcher.group(2));
            if (keyword == null || reduction == null || reduction <= 0) {
                return;
            }

            AiEnvelopeSuggestion item = findSuggestionByLabel(mergedItems, keyword.label);
            if (item == null || item.getPercentage() == null) {
                return;
            }

            item.setPercentage(round1(Math.max(0.0, item.getPercentage() - reduction)));
            return;
        }

        Pattern amountPattern = Pattern.compile(
            "(?:reduce|cut|lower)\\s+(.+?)\\s+by\\s+(?:ngn|naira|₦)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmM]?)"
        );
        Matcher amountMatcher = amountPattern.matcher(normalized);
        if (!amountMatcher.find()) {
            return;
        }

        EnvelopeKeyword keyword = matchEnvelopeKeyword(amountMatcher.group(1), keywords);
        Double amount = parseCurrencyAmount(amountMatcher.group(2), amountMatcher.group(3));
        Double totalBudget = request.getTotalBudget();
        if (keyword == null || amount == null || totalBudget == null || totalBudget <= 0) {
            return;
        }

        AiEnvelopeSuggestion item = findSuggestionByLabel(mergedItems, keyword.label);
        if (item == null || item.getPercentage() == null) {
            return;
        }

        double reduction = (amount / totalBudget) * 100.0;
        item.setPercentage(round1(Math.max(0.0, item.getPercentage() - reduction)));
    }

    private void applySplitRemainingIntent(
        String latestMessage,
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> mergedItems
    ) {
        if (latestMessage == null || latestMessage.isBlank()) {
            return;
        }

        String normalized = latestMessage.toLowerCase(Locale.ROOT);
        if (!containsAny(normalized, "split the rest", "split the remaining", "share the rest", "share the remaining")) {
            return;
        }

        double remainingPercentage = Math.max(0.0, 100.0 - sumPercentages(mergedItems));
        if (remainingPercentage <= 0.05) {
            return;
        }

        String targetSegment = normalized;
        int betweenIndex = normalized.indexOf("between ");
        if (betweenIndex >= 0) {
            targetSegment = normalized.substring(betweenIndex + "between ".length());
        } else {
            int intoIndex = normalized.indexOf("into ");
            if (intoIndex >= 0) {
                targetSegment = normalized.substring(intoIndex + "into ".length());
            }
        }

        List<EnvelopeKeyword> targets = findMentionedKeywords(targetSegment, buildEnvelopeKeywords(request));
        if (targets.isEmpty()) {
            return;
        }

        double shared = round1(remainingPercentage / targets.size());
        double assigned = 0.0;
        for (int i = 0; i < targets.size(); i++) {
            EnvelopeKeyword keyword = targets.get(i);
            AiEnvelopeSuggestion item = findSuggestionByLabel(mergedItems, keyword.label);
            if (item == null) {
                item = buildItem(keyword.label, 0.0, keyword.conditionType, keyword.category);
                mergedItems.add(item);
            }

            double addition = i == targets.size() - 1
                ? round1(Math.max(0.0, remainingPercentage - assigned))
                : shared;
            item.setPercentage(round1((item.getPercentage() == null ? 0.0 : item.getPercentage()) + addition));
            assigned += addition;
        }
    }

    private void applyUnallocatedAmountIntent(
        String latestMessage,
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> mergedItems
    ) {
        if (mergedItems.isEmpty()) {
            return;
        }

        Double amountToFree = extractRequestedUnallocatedAmount(latestMessage);
        if (amountToFree == null || amountToFree <= 0) {
            return;
        }

        Double totalBudget = request.getTotalBudget();
        if (totalBudget == null || totalBudget <= 0) {
            return;
        }

        double currentTotal = sumPercentages(mergedItems);
        if (currentTotal <= 0) {
            return;
        }

        double percentageToFree = Math.min(100.0, (amountToFree / totalBudget) * 100.0);
        double targetAllocated = Math.max(0.0, currentTotal - percentageToFree);

        if (targetAllocated >= currentTotal - 0.05) {
            return;
        }

        List<AiEnvelopeSuggestion> reduced = normalizeItems(
            mergedItems, targetAllocated, request.getDurationDays());
        mergedItems.clear();
        mergedItems.addAll(reduced);
    }

    private Double extractRequestedUnallocatedAmount(String message) {
        if (message == null || message.isBlank()) {
            return null;
        }

        String normalized = message.toLowerCase(Locale.ROOT);
        if (!containsAny(normalized, "remove", "free up", "take out", "repurpose", "hold back", "leave")) {
            return null;
        }

        Pattern amountPattern = Pattern.compile(
            "(?:remove|free\\s+up|take\\s+out|repurpose|hold\\s+back|leave)\\s+"
                + "(?:about\\s+)?(?:ngn|naira|₦)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmM]?)\\b"
        );
        Matcher matcher = amountPattern.matcher(normalized);
        if (matcher.find()) {
            return parseCurrencyAmount(matcher.group(1), matcher.group(2));
        }

        Pattern trailingIntentPattern = Pattern.compile(
            "(?:keep|leave)\\s+(?:about\\s+)?(?:ngn|naira|₦)?\\s*([\\d,]+(?:\\.\\d+)?)\\s*([kKmM]?)\\s+"
                + "(?:left|remaining|unallocated|aside)"
        );
        Matcher trailingMatcher = trailingIntentPattern.matcher(normalized);
        if (trailingMatcher.find()) {
            return parseCurrencyAmount(trailingMatcher.group(1), trailingMatcher.group(2));
        }

        return null;
    }

    private Double parseCurrencyAmount(String rawNumber, String suffix) {
        if (rawNumber == null || rawNumber.isBlank()) {
            return null;
        }

        try {
            double value = Double.parseDouble(rawNumber.replace(",", ""));
            if (value <= 0) {
                return null;
            }

            if (suffix != null && !suffix.isBlank()) {
                String normalizedSuffix = suffix.toLowerCase(Locale.ROOT);
                if ("k".equals(normalizedSuffix)) {
                    value *= 1_000;
                } else if ("m".equals(normalizedSuffix)) {
                    value *= 1_000_000;
                }
            }

            return round2(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private EnvelopeKeyword matchEnvelopeKeyword(String text, List<EnvelopeKeyword> keywords) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        EnvelopeKeyword matched = null;
        for (EnvelopeKeyword keyword : keywords) {
            if (!normalized.contains(keyword.phrase)) {
                continue;
            }
            if (matched == null || keyword.phrase.length() > matched.phrase.length()) {
                matched = keyword;
            }
        }
        return matched;
    }

    private List<EnvelopeKeyword> findMentionedKeywords(String text, List<EnvelopeKeyword> keywords) {
        if (text == null || text.isBlank()) {
            return Collections.emptyList();
        }

        String normalized = text.toLowerCase(Locale.ROOT);
        List<EnvelopeKeyword> matches = new ArrayList<>();
        for (EnvelopeKeyword keyword : keywords) {
            if (!normalized.contains(keyword.phrase)) {
                continue;
            }

            boolean exists = matches.stream()
                .anyMatch(item -> item.label.equalsIgnoreCase(keyword.label));
            if (!exists) {
                matches.add(keyword);
            }
        }

        return matches;
    }

    private AiEnvelopeSuggestion findSuggestionByLabel(List<AiEnvelopeSuggestion> items, String label) {
        for (AiEnvelopeSuggestion item : items) {
            if (item.getName() != null && item.getName().equalsIgnoreCase(label)) {
                return item;
            }
        }
        return null;
    }

    private void pruneZeroPercentSuggestions(List<AiEnvelopeSuggestion> items) {
        items.removeIf(item -> item.getPercentage() == null || item.getPercentage() < 0.1);
    }

    private Double parsePercentage(String raw) {
        try {
            double value = Double.parseDouble(raw);
            if (value <= 0) {
                return null;
            }
            return Math.min(100.0, round1(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private AiEnvelopeSuggestion cloneSuggestion(AiEnvelopeSuggestion item) {
        AiEnvelopeSuggestion clone = new AiEnvelopeSuggestion();
        clone.setName(item.getName());
        clone.setCategory(item.getCategory());
        clone.setConditionType(item.getConditionType());
        clone.setPercentage(item.getPercentage());
        return clone;
    }

    private AiStarterEnvelopeRequest toStarterRequest(AiBudgetAssistantTurnRequest request) {
        AiStarterEnvelopeRequest starterRequest = new AiStarterEnvelopeRequest();
        starterRequest.setTotalBudget(request.getTotalBudget());
        starterRequest.setDurationDays(request.getDurationDays());
        starterRequest.setGoal(request.getGoal());
        starterRequest.setCurrency(request.getCurrency());
        return starterRequest;
    }

    private List<AiBudgetAssistantMessage> trimConversation(List<AiBudgetAssistantMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        int start = Math.max(0, messages.size() - 8);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    private String buildHeuristicTitle(AiStarterEnvelopeRequest request, boolean allocation) {
        String goal = request.getGoal() == null ? "" : request.getGoal().toLowerCase(Locale.ROOT);
        if (containsAny(goal, "save", "savings", "buffer")) {
            return allocation ? "Savings-led allocation" : "Savings-first starter plan";
        }
        if (containsAny(goal, "essential", "discipline", "control", "lean")) {
            return allocation ? "Essentials-led allocation" : "Essentials-first starter plan";
        }
        if (containsAny(goal, "travel", "flight", "trip")) {
            return allocation ? "Travel-ready allocation" : "Travel-focused starter plan";
        }
        if (containsAny(goal, "school", "education", "tuition")) {
            return allocation ? "Education-led allocation" : "Education-focused starter plan";
        }
        return allocation ? "Personalized allocation mix" : "Personalized starter plan";
    }

    private String buildHeuristicReasoning(AiStarterEnvelopeRequest request, boolean allocation) {
        String goal = request.getGoal() == null ? "" : request.getGoal().toLowerCase(Locale.ROOT);
        if (containsAny(goal, "save", "savings", "buffer", "emergency")) {
            return "This mix protects core spending first while keeping stronger room for savings and financial buffer.";
        }
        if (containsAny(goal, "essential", "discipline", "control", "lean")) {
            return "This mix leans toward essentials and repeat spending so the budget stays tighter and easier to manage.";
        }
        if (containsAny(goal, "travel", "flight", "trip")) {
            return "This mix gives stronger room to movement and travel-related needs without ignoring daily spending.";
        }
        if (containsAny(goal, "work", "business", "tools", "internet", "data")) {
            return "This mix gives more room to productive spend while keeping household and recurring needs covered.";
        }
        return allocation
            ? "This draft balances essentials, flexibility, and savings around the budget amount and stated goal."
            : "This starter draft adapts the envelope mix to the stated goal instead of relying on one default structure.";
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value)) {
                return true;
            }
        }
        return false;
    }

    private String chooseRecurringType(AiStarterEnvelopeRequest request, String category) {
        int days = request.getDurationDays() == null ? 30 : request.getDurationDays();
        if ("security".equals(category)) {
            return "emergency";
        }
        if (days <= 8) {
            return "daily";
        }
        if (days <= 21) {
            return "weekly";
        }
        return switch (category) {
            case "food", "groceries", "lunch", "internet", "car" -> "weekly";
            default -> "dynamic";
        };
    }

    private void addSeed(Map<String, SuggestionSeed> seeds, String name, String category, String conditionType, double weight) {
        seeds.putIfAbsent(name.toLowerCase(Locale.ROOT), new SuggestionSeed(name, category, conditionType, weight));
    }

    private double sumPercentages(List<AiEnvelopeSuggestion> items) {
        return items.stream().mapToDouble(item -> item.getPercentage() == null ? 0.0 : item.getPercentage()).sum();
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private double computeRemainingAmount(Double totalBudget, double allocatedPercentage) {
        if (totalBudget == null || totalBudget <= 0) {
            return 0.0;
        }
        double remainingRatio = Math.max(0.0, 100.0 - allocatedPercentage) / 100.0;
        return totalBudget * remainingRatio;
    }

    private String safeBudgetName(String budgetName) {
        return budgetName == null || budgetName.isBlank() ? "This budget" : budgetName.trim();
    }

    private String safeGoal(String goal) {
        return goal == null ? "" : goal.trim();
    }

    private String safeCurrency(String currency) {
        return currency == null || currency.isBlank() ? "NGN" : currency.trim();
    }

    private String buildAssistantFollowUpMessage(
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> envelopes,
        double remainingAmount
    ) {
        String latestMessage = request.getLatestUserMessage() == null
            ? ""
            : request.getLatestUserMessage().toLowerCase(Locale.ROOT);

        if (envelopes.isEmpty()) {
            return "Let's start by naming the first few envelopes you want this budget to cover, and I will help split the money realistically.";
        }

        Double amountToFree = extractRequestedUnallocatedAmount(latestMessage);
        if (amountToFree != null && amountToFree > 0 && remainingAmount > 0.01) {
            return String.format(
                Locale.ROOT,
                "I have freed up %s %.2f from the current plan, so you now have %s %.2f left to repurpose. Tell me whether you want to keep that balance unassigned or move it into a new envelope.",
                safeCurrency(request.getCurrency()),
                round2(Math.min(amountToFree, request.getTotalBudget() == null ? amountToFree : request.getTotalBudget())),
                safeCurrency(request.getCurrency()),
                round2(remainingAmount)
            );
        }

        if (containsAny(latestMessage, "move") && containsAny(latestMessage, " from ", " to ")) {
            if (remainingAmount <= 0.01) {
                return "I have moved that amount within the budget and kept the full plan allocated. If you want, we can still rebalance another envelope before you finalize.";
            }
            return String.format(
                Locale.ROOT,
                "I have moved that amount and you still have %s %.2f left unassigned. Tell me where you want the rest to go or say you are done if this balance should stay free for now.",
                safeCurrency(request.getCurrency()),
                round2(remainingAmount)
            );
        }

        if (containsAny(latestMessage, "reduce", "cut", "lower")) {
            return String.format(
                Locale.ROOT,
                "I have reduced that envelope and the plan now has %s %.2f left to work with. You can keep that balance free, move it into another envelope, or ask me to split the rest.",
                safeCurrency(request.getCurrency()),
                round2(remainingAmount)
            );
        }

        if (containsAny(latestMessage, "split the rest", "split the remaining", "share the rest", "share the remaining")) {
            if (remainingAmount <= 0.01) {
                return "I have shared the remaining balance across those envelopes, so the plan is now fully allocated. If it looks right to you, say you are done and I will treat it as ready to finalize.";
            }
            return String.format(
                Locale.ROOT,
                "I have split part of the remaining balance across those envelopes, and you still have %s %.2f left to place.",
                safeCurrency(request.getCurrency()),
                round2(remainingAmount)
            );
        }

        if (isRenameEnvelopeIntent(latestMessage)) {
            return "I have renamed that envelope and kept the budget fully allocated. You can adjust the amount next or say you are done.";
        }

        if (containsAny(latestMessage, "adjust", "change", "reduce", "increase", "move", "shift", "repurpose")) {
            return "I can help rebalance this, but I need one clearer instruction. Tell me which envelope to reduce, increase, or move money into, and I will recalculate what remains right away.";
        }

        if (remainingAmount <= 0.01) {
            if (containsAny(latestMessage, "why", "left", "remaining", "0.00", "zero")) {
                return "The plan is fully allocated, so there is no money left to assign. The continue button is still waiting for your final confirmation before I mark this budget as ready.";
            }
            return "This plan is fully allocated now, so there is no balance left to assign. If it looks right to you, say you are done and I will treat it as ready to finalize.";
        }

        if (remainingAmount > request.getTotalBudget() * 0.1) {
            String nextName = pickNextPromptCategory(envelopes);
            return String.format(
                Locale.ROOT,
                "I have mapped %.1f%% so far. You still have %s %.2f left to plan. We can add or rebalance %s next if that fits what you want.",
                sumPercentages(envelopes),
                safeCurrency(request.getCurrency()),
                round2(remainingAmount),
                nextName
            );
        }

        return "This plan is almost complete, with only a small balance left. If these envelopes feel right to you, say you are done and I will treat it as ready to finalize.";
    }

    private String buildAssistantReasoning(
        AiBudgetAssistantTurnRequest request,
        List<AiEnvelopeSuggestion> envelopes,
        double remainingAmount
    ) {
        Double amountToFree = extractRequestedUnallocatedAmount(request.getLatestUserMessage());
        if (amountToFree != null && amountToFree > 0 && remainingAmount > 0.01) {
            return "This update reduces the current envelopes proportionally so part of the budget can stay free for repurposing without discarding the rest of the plan.";
        }
        String latestMessage = request.getLatestUserMessage() == null
            ? ""
            : request.getLatestUserMessage().toLowerCase(Locale.ROOT);
        if (containsAny(latestMessage, "move") && containsAny(latestMessage, " from ", " to ")) {
            return "This update shifts money between existing envelopes while preserving the rest of the budget structure.";
        }
        if (isRenameEnvelopeIntent(latestMessage)) {
            return "This update changes the envelope name while preserving its existing allocation.";
        }
        if (containsAny(latestMessage, "reduce", "cut", "lower")) {
            return "This update frees some budget from the selected envelope so the remaining balance can be reassigned more intentionally.";
        }
        if (containsAny(latestMessage, "split the rest", "split the remaining", "share the rest", "share the remaining")) {
            return "This update spreads the remaining budget across the named envelopes instead of rebuilding the entire plan.";
        }
        if (remainingAmount > request.getTotalBudget() * 0.1) {
            return "This draft keeps the current envelope choices intact while showing what remains so the next decision can stay realistic.";
        }
        return "This allocation now covers the main envelopes with only a small balance left, so it is close to a finalized budget plan.";
    }

    private boolean isAffirmingCompletion(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return containsAny(
            normalized,
            "we're done",
            "we are done",
            "i am done",
            "i'm done",
            "looks good",
            "this is fine",
            "finalize",
            "confirm",
            "done"
        );
    }

    private String pickNextPromptCategory(List<AiEnvelopeSuggestion> envelopes) {
        for (AiEnvelopeSuggestion item : envelopes) {
            if ("more".equals(item.getCategory())) {
                return "your remaining flexible spend";
            }
        }
        return "one more priority envelope";
    }

    private String defaultNameForCategory(String category) {
        return switch (category) {
            case "savings" -> "Savings";
            case "security" -> "Emergency Buffer";
            case "food" -> "Food";
            case "car" -> "Transport";
            case "home" -> "Home";
            case "education" -> "Education";
            case "flight" -> "Travel";
            case "tools" -> "Tools";
            case "gift" -> "Giving";
            case "work" -> "Work";
            case "internet" -> "Internet";
            case "faith" -> "Faith";
            case "groceries" -> "Groceries";
            case "lunch" -> "Lunch";
            default -> "Misc";
        };
    }

    private AiEnvelopeSuggestion buildItem(String name, Double pct, String type, String category) {
        AiEnvelopeSuggestion item = new AiEnvelopeSuggestion();
        item.setName(name);
        item.setPercentage(pct);
        item.setConditionType(type);
        item.setCategory(category);
        return item;
    }

    private static class EnvelopeKeyword {
        private final String phrase;
        private final String label;
        private final String category;
        private final String conditionType;

        private EnvelopeKeyword(String phrase, String label, String category, String conditionType) {
            this.phrase = phrase;
            this.label = label;
            this.category = category;
            this.conditionType = conditionType;
        }
    }

    private static class RenameIntent {
        private final String sourceName;
        private final String targetName;

        private RenameIntent(String sourceName, String targetName) {
            this.sourceName = sourceName;
            this.targetName = targetName;
        }
    }

    private static class SuggestionSeed {
        private final String name;
        private final String category;
        private final String conditionType;
        private final double weight;

        private SuggestionSeed(String name, String category, String conditionType, double weight) {
            this.name = name;
            this.category = category;
            this.conditionType = conditionType;
            this.weight = weight;
        }
    }
}
