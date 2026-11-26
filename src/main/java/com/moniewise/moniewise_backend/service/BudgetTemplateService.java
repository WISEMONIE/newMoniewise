package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.request.BudgetRequest;
import com.moniewise.moniewise_backend.dto.request.CreateFromTemplateRequest;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.SaveTemplateRequest;
import com.moniewise.moniewise_backend.dto.request.TemplateEnvelopeDefinition;
import com.moniewise.moniewise_backend.dto.response.BudgetResponse;
import com.moniewise.moniewise_backend.dto.response.BudgetTemplateResponse;
import com.moniewise.moniewise_backend.entity.BudgetTemplate;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.BudgetStatus;
import com.moniewise.moniewise_backend.repository.BudgetTemplateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BudgetTemplateService {

    private static final Logger logger = LoggerFactory.getLogger(BudgetTemplateService.class);

    @Autowired
    private BudgetTemplateRepository templateRepository;

    @Autowired
    private UserService userService;

    @Autowired
    private BudgetService budgetService;

    @Transactional
    public BudgetTemplateResponse saveTemplate(SaveTemplateRequest request, String email) {
        User user = userService.findByEmail(email);

        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Template name is required");
        }
        if (request.getEnvelopes() == null || request.getEnvelopes().isEmpty()) {
            throw new IllegalArgumentException("At least one envelope definition is required");
        }

        BigDecimal totalPercentage = request.getEnvelopes().stream()
                .map(TemplateEnvelopeDefinition::getPercentage)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
            throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
        }
        if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
        }

        List<Map<String, Object>> definitions = request.getEnvelopes().stream()
                .map(this::toDefinitionMap)
                .collect(Collectors.toList());

        BudgetTemplate template = new BudgetTemplate();
        template.setUser(user);
        template.setName(request.getName().trim());
        template.setEnvelopeDefinitions(definitions);
        template.setCreatedAt(LocalDateTime.now());
        template.setUpdatedAt(LocalDateTime.now());

        BudgetTemplate saved = templateRepository.save(template);
        logger.info("Template '{}' saved for user {}", saved.getName(), user.getId());
        return toResponse(saved);
    }

    public List<BudgetTemplateResponse> getTemplates(String email) {
        User user = userService.findByEmail(email);
        return templateRepository.findByUserIdOrderByCreatedAtDesc(user.getId())
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public BudgetTemplateResponse getTemplate(Long templateId, String email) {
        User user = userService.findByEmail(email);
        BudgetTemplate template = templateRepository.findByIdAndUserId(templateId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        return toResponse(template);
    }

    @Transactional
    public BudgetTemplateResponse updateTemplate(Long templateId, SaveTemplateRequest request, String email) {
        User user = userService.findByEmail(email);
        BudgetTemplate template = templateRepository.findByIdAndUserId(templateId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));

        if (request.getName() != null && !request.getName().trim().isEmpty()) {
            template.setName(request.getName().trim());
        }

        if (request.getEnvelopes() != null && !request.getEnvelopes().isEmpty()) {
            BigDecimal totalPercentage = request.getEnvelopes().stream()
                    .map(TemplateEnvelopeDefinition::getPercentage)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (totalPercentage.compareTo(new BigDecimal("50")) < 0) {
                throw new IllegalArgumentException("Envelope percentages must sum to at least 50%");
            }
            if (totalPercentage.compareTo(new BigDecimal("100")) > 0) {
                throw new IllegalArgumentException("Envelope percentages cannot exceed 100%");
            }

            List<Map<String, Object>> definitions = request.getEnvelopes().stream()
                    .map(this::toDefinitionMap)
                    .collect(Collectors.toList());
            template.setEnvelopeDefinitions(definitions);
        }

        template.setUpdatedAt(LocalDateTime.now());
        BudgetTemplate saved = templateRepository.save(template);
        logger.info("Template {} updated for user {}", templateId, user.getId());
        return toResponse(saved);
    }

    @Transactional
    public void deleteTemplate(Long templateId, String email) {
        User user = userService.findByEmail(email);
        BudgetTemplate template = templateRepository.findByIdAndUserId(templateId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));
        templateRepository.delete(template);
        logger.info("Template {} deleted for user {}", templateId, user.getId());
    }

    @Transactional
    public BudgetResponse createBudgetFromTemplate(Long templateId, CreateFromTemplateRequest request, String email) {
        User user = userService.findByEmail(email);
        BudgetTemplate template = templateRepository.findByIdAndUserId(templateId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Template not found"));

        if (request.getName() == null || request.getName().trim().isEmpty()) {
            throw new IllegalArgumentException("Budget name is required");
        }
        if (request.getTotalAmount() == null || request.getTotalAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Budget amount must be positive");
        }
        if (request.getEndDate() == null) {
            throw new IllegalArgumentException("End date is required");
        }
        LocalDate startDate = request.getStartDate() != null
                ? request.getStartDate()
                : LocalDate.now(ZoneId.of("Africa/Lagos"));

        long durationDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, request.getEndDate()) + 1;
        for (Map<String, Object> def : template.getEnvelopeDefinitions()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cond = (Map<String, Object>) def.get("conditions");
            if (cond == null) continue;
            String type = cond.get("type") != null ? cond.get("type").toString() : "";
            String envName = (String) def.get("name");
            switch (type) {
                case "monthly":
                    if (durationDays < 28) {
                        throw new IllegalArgumentException(
                                "Envelope \"" + envName + "\" is monthly but your budget is only "
                                        + durationDays + " days. Monthly envelopes need at least 28 days.");
                    }
                    break;
                case "quarterly":
                    if (durationDays < 85) {
                        throw new IllegalArgumentException(
                                "Envelope \"" + envName + "\" is quarterly but your budget is only "
                                        + durationDays + " days. Quarterly envelopes need at least 85 days.");
                    }
                    break;
                case "biannual":
                    if (durationDays < 170) {
                        throw new IllegalArgumentException(
                                "Envelope \"" + envName + "\" is biannual but your budget is only "
                                        + durationDays + " days. Biannual envelopes need at least 170 days.");
                    }
                    break;
            }
        }

        List<EnvelopeRequest> envelopeRequests = new ArrayList<>();
        for (Map<String, Object> def : template.getEnvelopeDefinitions()) {
            EnvelopeRequest env = new EnvelopeRequest();
            env.setName((String) def.get("name"));

            Object pctObj = def.get("percentage");
            BigDecimal percentage;
            if (pctObj instanceof BigDecimal) {
                percentage = (BigDecimal) pctObj;
            } else if (pctObj instanceof Number) {
                percentage = BigDecimal.valueOf(((Number) pctObj).doubleValue());
            } else {
                percentage = new BigDecimal(pctObj.toString());
            }
            env.setPercentage(percentage);

            BigDecimal exactAmount = request.getTotalAmount()
                    .multiply(percentage)
                    .divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP);
            env.setExactAmount(exactAmount);

            @SuppressWarnings("unchecked")
            Map<String, Object> conditions = (Map<String, Object>) def.get("conditions");
            Map<String, Object> fresh = conditions != null ? new LinkedHashMap<>(conditions) : new LinkedHashMap<>();

            // Reset time-bound fields so they're recalculated from the new budget's dates
            String type = fresh.get("type") != null ? fresh.get("type").toString() : "";
            if ("safe_lock".equals(type) || "strict_lock".equals(type)) {
                fresh.put("lockStartDate", startDate.toString());
            }

            env.setConditions(fresh);
            envelopeRequests.add(env);
        }

        BudgetRequest budgetRequest = new BudgetRequest();
        budgetRequest.setName(request.getName().trim());
        budgetRequest.setTotalAmount(request.getTotalAmount());
        budgetRequest.setStartDate(startDate);
        budgetRequest.setEndDate(request.getEndDate());
        budgetRequest.setDurationDays(request.getDurationDays());
        budgetRequest.setStatus(BudgetStatus.ACTIVE);
        budgetRequest.setEnvelopes(envelopeRequests);
        budgetRequest.setTermsAccepted(true);

        logger.info("Creating budget from template '{}' for user {}", template.getName(), user.getId());
        return budgetService.createBudget(budgetRequest, email);
    }

    private Map<String, Object> toDefinitionMap(TemplateEnvelopeDefinition def) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", def.getName());
        map.put("percentage", def.getPercentage());
        map.put("conditions", def.getConditions());
        return map;
    }

    private BudgetTemplateResponse toResponse(BudgetTemplate template) {
        return new BudgetTemplateResponse(
                template.getId(),
                template.getName(),
                template.getEnvelopeDefinitions(),
                template.getEnvelopeDefinitions().size(),
                template.getCreatedAt(),
                template.getUpdatedAt()
        );
    }
}
