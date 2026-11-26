package com.moniewise.moniewise_backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moniewise.moniewise_backend.config.GeminiProperties;
import com.moniewise.moniewise_backend.dto.request.AiBudgetAssistantTurnRequest;
import com.moniewise.moniewise_backend.dto.request.AiStarterEnvelopeRequest;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAllocationResponse;
import com.moniewise.moniewise_backend.dto.response.AiBudgetAssistantTurnResponse;
import com.moniewise.moniewise_backend.dto.response.AiEnvelopeSuggestion;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the deterministic fallback paths of AiBudgetService.
 * GeminiProperties has no API key, so GeminiService.generateText throws
 * before any network call — every request lands in the fallback code.
 */
class AiBudgetServiceFallbackTest {

    private AiBudgetService service;

    @BeforeEach
    void setUp() {
        GeminiProperties keyless = new GeminiProperties(); // no apiKey
        service = new AiBudgetService(
            new GeminiService(keyless),
            new AiPromptService(),
            new ObjectMapper()
        );
    }

    private AiStarterEnvelopeRequest writeItOutRequest(String userPlan, double budget) {
        AiStarterEnvelopeRequest request = new AiStarterEnvelopeRequest();
        request.setTotalBudget(budget);
        request.setDurationDays(30);
        request.setCurrency("NGN");
        request.setInterpretUserPlan(true);
        // Mirrors the frontend's _buildWriteItOutPrompt shape
        request.setGoal("""
            The user has written their own budget plan below. Your job is to INTERPRET and
            STRUCTURE it into envelopes — do NOT suggest a new plan or replace theirs.

            Budget: "June Budget" — 30,000.00 NGN over 1 month.

            What the user wrote:
            "%s"

            Rules for interpreting the user's input — handle every case gracefully:
            1. NAMES WITH NUMBERS ...
            """.formatted(userPlan));
        return request;
    }

    private double total(List<AiEnvelopeSuggestion> envelopes) {
        return envelopes.stream()
            .mapToDouble(e -> e.getPercentage() == null ? 0 : e.getPercentage())
            .sum();
    }

    private AiEnvelopeSuggestion byName(List<AiEnvelopeSuggestion> envelopes, String name) {
        return envelopes.stream()
            .filter(e -> name.equalsIgnoreCase(e.getName()))
            .findFirst()
            .orElse(null);
    }

    @Test
    void writeItOut_percentages_areHonoured_withSavingsRemainder() {
        AiBudgetAllocationResponse response =
            service.generateBudgetAllocation(writeItOutRequest("food 30, church 20", 30_000));

        assertEquals("fallback", response.getSource());
        AiEnvelopeSuggestion food = byName(response.getEnvelopes(), "Food");
        AiEnvelopeSuggestion church = byName(response.getEnvelopes(), "Church");
        assertNotNull(food, "user's Food envelope must exist");
        assertNotNull(church, "user's Church envelope must exist");
        assertEquals(30.0, food.getPercentage(), 0.11);
        assertEquals(20.0, church.getPercentage(), 0.11);
        // Remainder should be captured (Savings rule), totalling ~100
        assertEquals(100.0, total(response.getEnvelopes()), 0.5);
    }

    @Test
    void writeItOut_namesOnly_splitEqually() {
        AiBudgetAllocationResponse response =
            service.generateBudgetAllocation(writeItOutRequest("food, transport, savings", 30_000));

        assertEquals(3, response.getEnvelopes().size(), "no envelopes invented beyond the user's three");
        assertNotNull(byName(response.getEnvelopes(), "Food"));
        assertNotNull(byName(response.getEnvelopes(), "Transport"));
        assertNotNull(byName(response.getEnvelopes(), "Savings"));
        assertEquals(100.0, total(response.getEnvelopes()), 0.5);
    }

    @Test
    void writeItOut_amounts_convertToPercentages() {
        AiBudgetAllocationResponse response =
            service.generateBudgetAllocation(writeItOutRequest("food 21000, transport 6000", 30_000));

        AiEnvelopeSuggestion food = byName(response.getEnvelopes(), "Food");
        AiEnvelopeSuggestion transport = byName(response.getEnvelopes(), "Transport");
        assertNotNull(food);
        assertNotNull(transport);
        assertEquals(70.0, food.getPercentage(), 0.2);      // 21000/30000
        assertEquals(20.0, transport.getPercentage(), 0.2); // 6000/30000
    }

    @Test
    void writeItOut_kSuffix_isAnAmountNotAPercentage() {
        AiBudgetAllocationResponse response =
            service.generateBudgetAllocation(writeItOutRequest("food 20k", 30_000));

        AiEnvelopeSuggestion food = byName(response.getEnvelopes(), "Food");
        assertNotNull(food);
        assertEquals(66.7, food.getPercentage(), 0.2); // 20000/30000, NOT 20%
    }

    @Test
    void writeItOut_duplicateNames_areMerged() {
        AiBudgetAllocationResponse response =
            service.generateBudgetAllocation(writeItOutRequest("food 30, food 20", 30_000));

        long foodCount = response.getEnvelopes().stream()
            .filter(e -> "Food".equalsIgnoreCase(e.getName()))
            .count();
        assertEquals(1, foodCount, "duplicate names must merge into one envelope");
        assertEquals(50.0, byName(response.getEnvelopes(), "Food").getPercentage(), 0.11);
    }

    @Test
    void writeItOut_numbersOnly_getGenericLabels() {
        AiBudgetAllocationResponse response =
            service.generateBudgetAllocation(writeItOutRequest("40, 30, 30", 30_000));

        assertNotNull(byName(response.getEnvelopes(), "Envelope 1"));
        assertNotNull(byName(response.getEnvelopes(), "Envelope 2"));
        assertNotNull(byName(response.getEnvelopes(), "Envelope 3"));
        assertEquals(40.0, byName(response.getEnvelopes(), "Envelope 1").getPercentage(), 0.11);
    }

    @Test
    void aiDraft_fallback_reactsToBudgetName_notGuidanceKeywords() {
        AiStarterEnvelopeRequest request = new AiStarterEnvelopeRequest();
        request.setTotalBudget(30_000.0);
        request.setDurationDays(30);
        request.setCurrency("NGN");
        request.setInterpretUserPlan(false);
        // Mirrors the frontend's new AI Draft brief: budget name plus
        // guidance text that mentions travel/wedding/hustle as EXAMPLES.
        request.setGoal("""
            Create a tailored budget allocation for a Nigerian user.

            Budget name: "School Term"
            Budget amount: 30,000.00 NGN
            Duration: 1 month

            - A name like "Trip" or "Travel" → transport, accommodation, feeding
            - A name like "Wedding" or "Party" → venue, food, outfit
            - A name like "Hustle" or "Business" → data, transport, tools
            """);

        AiBudgetAllocationResponse response = service.generateBudgetAllocation(request);

        assertEquals("fallback", response.getSource());
        assertNotNull(byName(response.getEnvelopes(), "Education"),
            "budget name 'School Term' should produce an Education envelope");
        assertNull(byName(response.getEnvelopes(), "Travel"),
            "guidance example keywords must not contaminate the heuristics");
    }

    @Test
    void assistantFallback_doesNotLeakServerConfigDetails() {
        AiBudgetAssistantTurnRequest request = new AiBudgetAssistantTurnRequest();
        request.setBudgetName("June");
        request.setTotalBudget(30_000.0);
        request.setDurationDays(30);
        request.setCurrency("NGN");
        request.setGoal("monthly upkeep");
        request.setLatestUserMessage("add food and savings");
        request.setEnvelopes(Collections.emptyList());
        request.setMessages(Collections.emptyList());

        AiBudgetAssistantTurnResponse response = service.processBudgetAssistantTurn(request);

        assertEquals("fallback", response.getSource());
        assertNotNull(response.getSourceDetail());
        assertFalse(response.getSourceDetail().contains("GEMINI_API_KEY"),
            "raw config/exception text must not reach the client");
    }

    @Test
    void everyFallbackEnvelope_passesTheAllowlists() {
        AiBudgetAllocationResponse response =
            service.generateBudgetAllocation(writeItOutRequest("rice 25, drinks 15, gym 10", 30_000));

        List<String> conditionTypes = List.of("daily", "weekly", "dynamic", "emergency");
        List<String> categories = List.of("savings", "security", "food", "car", "home",
            "education", "flight", "tools", "gift", "work", "internet", "faith",
            "groceries", "lunch", "more");

        for (AiEnvelopeSuggestion item : response.getEnvelopes()) {
            assertTrue(conditionTypes.contains(item.getConditionType()),
                item.getName() + " has invalid conditionType " + item.getConditionType());
            assertTrue(categories.contains(item.getCategory()),
                item.getName() + " has invalid category " + item.getCategory());
            assertTrue(item.getPercentage() > 0,
                item.getName() + " has non-positive percentage");
        }
        assertNotNull(byName(response.getEnvelopes(), "Rice"));
        assertNotNull(byName(response.getEnvelopes(), "Drinks"));
        assertNotNull(byName(response.getEnvelopes(), "Gym"));
    }
}
