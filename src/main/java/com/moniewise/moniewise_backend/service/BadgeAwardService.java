package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.BadgeAwardMessage;
import com.moniewise.moniewise_backend.dto.response.UserBadgeResponse;
import com.moniewise.moniewise_backend.entity.Badge;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserBadge;
import com.moniewise.moniewise_backend.enums.BadgeAwardSourceType;
import com.moniewise.moniewise_backend.repository.BadgeRepository;
import com.moniewise.moniewise_backend.repository.UserBadgeRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class BadgeAwardService {

    public static final String BUDGET_COMPLETED_BADGE = "BUDGET_COMPLETED";
    public static final String SAVINGS_MATURED_BADGE = "SAVINGS_MATURED";

    private static final Logger logger = LoggerFactory.getLogger(BadgeAwardService.class);

    private final BadgeRepository badgeRepository;
    private final UserBadgeRepository userBadgeRepository;
    private final UserRepository userRepository;
    private final TransactionTemplate requiresNewTransactionTemplate;
    private final SimpMessagingTemplate messagingTemplate;

    public BadgeAwardService(BadgeRepository badgeRepository,
                             UserBadgeRepository userBadgeRepository,
                             UserRepository userRepository,
                             PlatformTransactionManager transactionManager,
                             SimpMessagingTemplate messagingTemplate) {
        this.badgeRepository = badgeRepository;
        this.userBadgeRepository = userBadgeRepository;
        this.userRepository = userRepository;
        this.requiresNewTransactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.messagingTemplate = messagingTemplate;
    }

    public void awardBudgetCompletionBadgeAfterCommit(User user, Budget budget, BigDecimal unusedAmount) {
        if (user == null || budget == null || user.getId() == null || budget.getId() == null) {
            return;
        }

        BigDecimal unused = money(unusedAmount);
        AwardCommand command = new AwardCommand(
                user.getId(),
                user.getEmail(),
                BUDGET_COMPLETED_BADGE,
                BadgeAwardSourceType.BUDGET,
                budget.getId(),
                "Budget completed",
                String.format("You completed '%s'. You planned with %s and kept %s unused.",
                        safeName(budget.getName(), "your budget"),
                        formatMoney(budget.getTotalAmount()),
                        formatMoney(unused)),
                "I completed my Wisemonie budget",
                String.format("I completed '%s' on Wisemonie and kept %s unused.",
                        safeName(budget.getName(), "my budget"),
                        formatMoney(unused)),
                "/budgets/" + budget.getId() + "/completion"
        );
        awardAfterCommit(command);
    }

    public void awardSavingsMaturedBadgeAfterCommit(SavingsGoal goal) {
        if (goal == null || goal.getUser() == null || goal.getUser().getId() == null || goal.getId() == null) {
            return;
        }

        User user = goal.getUser();
        BigDecimal payout = money(goal.getCurrentBalance()).add(money(goal.getAccruedInterest()));
        AwardCommand command = new AwardCommand(
                user.getId(),
                user.getEmail(),
                SAVINGS_MATURED_BADGE,
                BadgeAwardSourceType.SAVINGS_GOAL,
                goal.getId(),
                "Savings goal matured",
                String.format("Your '%s' savings goal matured with %s ready to withdraw.",
                        safeName(goal.getName(), "savings goal"),
                        formatMoney(payout)),
                "I completed my Wisemonie savings goal",
                String.format("I completed '%s' on Wisemonie and saved %s.",
                        safeName(goal.getName(), "my savings goal"),
                        formatMoney(payout)),
                "/savings/" + goal.getId()
        );
        awardAfterCommit(command);
    }

    public List<UserBadgeResponse> getUserBadges(Long userId, boolean unseenOnly) {
        List<UserBadge> awards = unseenOnly
                ? userBadgeRepository.findUnseenByUserIdWithBadgeOrderByEarnedAtDesc(userId)
                : userBadgeRepository.findByUserIdWithBadgeOrderByEarnedAtDesc(userId);

        return awards.stream()
                .map(this::toResponse)
                .toList();
    }

    public UserBadgeResponse markSeen(Long userId, Long userBadgeId) {
        UserBadge award = userBadgeRepository.findByIdAndUserId(userBadgeId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Badge award not found"));
        if (award.getSeenAt() == null) {
            award.setSeenAt(Instant.now());
            award = userBadgeRepository.save(award);
        }
        return toResponse(award);
    }

    public String buildShareCardSvg(Long userId, Long userBadgeId) {
        UserBadge award = userBadgeRepository.findByIdAndUserId(userBadgeId, userId)
                .orElseThrow(() -> new IllegalArgumentException("Badge award not found"));
        UserBadgeResponse response = toResponse(award);

        String earnedDate = award.getEarnedAt() == null
                ? ""
                : DateTimeFormatter.ofPattern("d MMM yyyy")
                        .withZone(ZoneId.of("Africa/Lagos"))
                        .format(award.getEarnedAt());
        String headline = truncate(firstNonBlank(response.shareTitle(), response.title()), 44);
        String message = truncate(firstNonBlank(response.shareMessage(), response.message()), 92);
        String badgeName = truncate(response.badgeName(), 32);

        return """
                <svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 1080 1080">
                  <defs>
                    <linearGradient id="bg" x1="0" y1="0" x2="1" y2="1">
                      <stop offset="0" stop-color="#12343B"/>
                      <stop offset="0.58" stop-color="#2D6A4F"/>
                      <stop offset="1" stop-color="#F4A261"/>
                    </linearGradient>
                  </defs>
                  <rect width="1080" height="1080" rx="0" fill="url(#bg)"/>
                  <circle cx="540" cy="338" r="164" fill="#FFFFFF" fill-opacity="0.95"/>
                  <circle cx="540" cy="338" r="128" fill="#F4A261"/>
                  <path d="M480 346l42 42 91-116" fill="none" stroke="#12343B" stroke-width="34" stroke-linecap="round" stroke-linejoin="round"/>
                  <text x="540" y="590" text-anchor="middle" font-family="Arial, sans-serif" font-size="54" font-weight="700" fill="#FFFFFF">%s</text>
                  <text x="540" y="664" text-anchor="middle" font-family="Arial, sans-serif" font-size="36" font-weight="700" fill="#FFE8C2">%s</text>
                  <text x="540" y="738" text-anchor="middle" font-family="Arial, sans-serif" font-size="34" fill="#FFFFFF">%s</text>
                  <text x="540" y="840" text-anchor="middle" font-family="Arial, sans-serif" font-size="26" fill="#FFFFFF" fill-opacity="0.86">Earned on %s</text>
                  <text x="540" y="932" text-anchor="middle" font-family="Arial, sans-serif" font-size="34" font-weight="700" fill="#FFFFFF">Wisemonie</text>
                </svg>
                """.formatted(
                escapeXml(headline),
                escapeXml(badgeName),
                escapeXml(message),
                escapeXml(earnedDate)
        );
    }

    public UserBadgeResponse toResponse(UserBadge award) {
        Badge badge = award.getBadge();
        return new UserBadgeResponse(
                award.getId(),
                badge.getId(),
                badge.getCode(),
                badge.getName(),
                badge.getDescription(),
                badge.getCategory(),
                badge.getIconUrl(),
                award.getSourceType(),
                award.getSourceId(),
                firstNonBlank(award.getTitle(), badge.getName()),
                firstNonBlank(award.getMessage(), badge.getDescription()),
                firstNonBlank(award.getShareTitle(), badge.getShareTitle()),
                firstNonBlank(award.getShareMessage(), badge.getShareMessage()),
                "/badges/me/" + award.getId() + "/share-card.svg",
                deepLinkFor(award),
                award.getEarnedAt(),
                award.getSeenAt(),
                award.getSeenAt() != null,
                true
        );
    }

    private void awardAfterCommit(AwardCommand command) {
        Runnable award = () -> {
            UserBadgeResponse response = persistAward(command);
            if (response != null && command.userEmail() != null && !command.userEmail().isBlank()) {
                sendBadgeAward(command.userEmail(), response);
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    award.run();
                }
            });
        } else {
            award.run();
        }
    }

    private UserBadgeResponse persistAward(AwardCommand command) {
        try {
            return requiresNewTransactionTemplate.execute(status -> {
                if (userBadgeRepository.findAward(
                        command.userId(),
                        command.badgeCode(),
                        command.sourceType(),
                        command.sourceId()).isPresent()) {
                    return null;
                }

                Badge badge = getOrCreateBadge(command.badgeCode());
                UserBadge award = new UserBadge();
                award.setUser(userRepository.getReferenceById(command.userId()));
                award.setBadge(badge);
                award.setSourceType(command.sourceType());
                award.setSourceId(command.sourceId());
                award.setTitle(command.title());
                award.setMessage(command.message());
                award.setShareTitle(command.shareTitle());
                award.setShareMessage(command.shareMessage());
                award.setEarnedAt(Instant.now());
                return toResponse(userBadgeRepository.saveAndFlush(award));
            });
        } catch (DataIntegrityViolationException e) {
            logger.info("Badge {} already awarded to userId={} sourceType={} sourceId={}",
                    command.badgeCode(), command.userId(), command.sourceType(), command.sourceId());
            return null;
        } catch (Exception e) {
            logger.error("Failed to award badge {} to userId={} sourceType={} sourceId={}",
                    command.badgeCode(), command.userId(), command.sourceType(), command.sourceId(), e);
            return null;
        }
    }

    private Badge getOrCreateBadge(String code) {
        return badgeRepository.findByCode(code).orElseGet(() -> {
            Badge badge = new Badge();
            badge.setCode(code);
            badge.setThreshold(1);
            badge.setActive(true);
            if (BUDGET_COMPLETED_BADGE.equals(code)) {
                badge.setName("Budget Finisher");
                badge.setDescription("Completed a Wisemonie budget.");
                badge.setCategory("BUDGET");
                badge.setShareTitle("Budget completed");
                badge.setShareMessage("I completed a Wisemonie budget.");
            } else if (SAVINGS_MATURED_BADGE.equals(code)) {
                badge.setName("Savings Finisher");
                badge.setDescription("Completed a Wisemonie savings goal.");
                badge.setCategory("SAVINGS");
                badge.setShareTitle("Savings goal completed");
                badge.setShareMessage("I completed a Wisemonie savings goal.");
            } else {
                badge.setName(code);
                badge.setDescription("Wisemonie achievement earned.");
                badge.setCategory("GENERAL");
            }
            return badgeRepository.saveAndFlush(badge);
        });
    }

    private void sendBadgeAward(String userEmail, UserBadgeResponse response) {
        try {
            messagingTemplate.convertAndSendToUser(
                    userEmail,
                    "/queue/badges",
                    new BadgeAwardMessage("BADGE_AWARDED", response)
            );
            logger.info("Sent badge award websocket to {} for userBadgeId={}", userEmail, response.userBadgeId());
        } catch (Exception e) {
            logger.error("Failed to send badge award websocket to {}", userEmail, e);
        }
    }

    private String deepLinkFor(UserBadge award) {
        if (award.getSourceType() == BadgeAwardSourceType.BUDGET) {
            return "/budgets/" + award.getSourceId() + "/completion";
        }
        if (award.getSourceType() == BadgeAwardSourceType.SAVINGS_GOAL) {
            return "/savings/" + award.getSourceId();
        }
        return "/more/badges";
    }

    private String safeName(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String truncate(String value, int maxLength) {
        String safe = value == null ? "" : value.trim();
        if (safe.length() <= maxLength) {
            return safe;
        }
        return safe.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String escapeXml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : value.setScale(2, RoundingMode.HALF_UP);
    }

    private String formatMoney(BigDecimal value) {
        return "NGN " + money(value).toPlainString();
    }

    private record AwardCommand(
            Long userId,
            String userEmail,
            String badgeCode,
            BadgeAwardSourceType sourceType,
            Long sourceId,
            String title,
            String message,
            String shareTitle,
            String shareMessage,
            String deepLink
    ) {
    }
}
