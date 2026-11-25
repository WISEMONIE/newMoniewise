package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.enums.WalletStatus;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What the team does with a hold: read the case, note the customer's call,
 * release it, escalate it to Rubies, or freeze the wallet (guardrails 4, 5
 * and 10, and the response playbook). Every action is recorded with who did
 * it and why.
 */
@Service
public class AmlCaseService {

    private static final Logger logger = LoggerFactory.getLogger(AmlCaseService.class);

    private final InflowHoldRepository holds;
    private final InflowReviewService inflowReview;
    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;
    private final AmlNotifier notifier;
    private final AmlSettings settings;
    private final Clock clock;

    public AmlCaseService(InflowHoldRepository holds,
                          InflowReviewService inflowReview,
                          UserRepository userRepository,
                          WalletRepository walletRepository,
                          @Lazy WalletService walletService,
                          AmlNotifier notifier,
                          AmlSettings settings,
                          Optional<Clock> clock) {
        this.holds = holds;
        this.inflowReview = inflowReview;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
        this.notifier = notifier;
        this.settings = settings;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    @Transactional(readOnly = true)
    public List<InflowHold> openHolds() {
        return holds.findByStatusInOrderByStartedAtAsc(InflowReviewService.HOLDING);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> caseFile(Long holdId) {
        InflowHold hold = find(holdId);
        User user = userRepository.findById(hold.getUserId()).orElse(null);
        Wallet wallet = walletRepository.findByUserId(hold.getUserId()).orElse(null);
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("hold", hold);
        file.put("credits", inflowReview.creditsOn(hold));
        file.put("customer", user == null ? Map.of() : Map.of(
                "userId", user.getId(),
                "email", nullToEmpty(user.getEmail()),
                "phone", nullToEmpty(user.getPhone()),
                "signedUpAt", String.valueOf(user.getCreatedAt())));
        file.put("wallet", wallet == null ? Map.of() : Map.of(
                "accountNumber", nullToEmpty(wallet.getAccountNumber()),
                "status", String.valueOf(wallet.getStatus()),
                "openedAt", String.valueOf(wallet.getOpenedAt()),
                "balance", wallet.getBalance()));
        file.put("otherHolds", holds.findByUserIdOrderByIdDesc(hold.getUserId()).stream()
                .filter(h -> !h.getId().equals(hold.getId())).toList());
        file.put("rubiesEscalation", escalationText(hold, user, wallet));
        return file;
    }

    /** The customer called or wrote in. Stops the 24-hour silence escalation. */
    @Transactional
    public InflowHold recordContact(Long holdId, String staff, String note) {
        InflowHold hold = find(holdId);
        LocalDateTime now = LocalDateTime.now(clock);
        if (hold.getCustomerContactedAt() == null) {
            hold.setCustomerContactedAt(now);
        }
        hold.addNote(staff, "Customer contact: " + required(note, "note"), now);
        return holds.save(hold);
    }

    @Transactional
    public InflowHold addNote(Long holdId, String staff, String note) {
        InflowHold hold = find(holdId);
        hold.addNote(staff, required(note, "note"), LocalDateTime.now(clock));
        return holds.save(hold);
    }

    /** Clears the review. Only release with evidence on file (guardrail 2). */
    @Transactional
    public InflowHold release(Long holdId, String staff, String reason) {
        String why = required(reason, "reason");
        InflowHold hold = find(holdId);
        if (!hold.getStatus().isHolding()) {
            throw new IllegalStateException("Hold " + holdId + " is already " + hold.getStatus());
        }
        LocalDateTime now = LocalDateTime.now(clock);
        hold.setStatus(InflowHold.Status.RELEASED);
        hold.setReleasedAt(now);
        hold.setReleasedBy(staff);
        hold.setReleaseReason(why);
        hold.addNote(staff, "Released: " + why, now);
        InflowHold saved = holds.save(hold);
        logger.warn("[AML] Hold {} for user={} released by {}", holdId, hold.getUserId(), staff);
        userRepository.findById(hold.getUserId()).ifPresent(notifier::customerHoldReleased);
        notifier.compliance("Hold " + holdId + " released by " + staff,
                "User id: " + hold.getUserId() + "\nReason: " + why);
        return saved;
    }

    /** Marks the case as sent to Rubies and returns the text to send (guardrail 5). */
    @Transactional
    public Map<String, Object> escalate(Long holdId, String staff, String note) {
        InflowHold hold = find(holdId);
        LocalDateTime now = LocalDateTime.now(clock);
        markEscalated(hold, staff, note == null || note.isBlank() ? "Escalated to Rubies" : note, now);
        User user = userRepository.findById(hold.getUserId()).orElse(null);
        Wallet wallet = walletRepository.findByUserId(hold.getUserId()).orElse(null);
        String text = escalationText(hold, user, wallet);
        notifier.compliance("Hold " + holdId + " escalated by " + staff, text);
        return Map.of("hold", hold, "sendToRubies", text, "rubiesContact", "support@rubies.ng");
    }

    /** Post-no-debit: nothing leaves until someone unfreezes it (playbook step 1). */
    @Transactional
    public void freeze(Long userId, String staff, String reason) {
        setWalletStatus(userId, WalletStatus.FROZEN, staff, required(reason, "reason"));
    }

    @Transactional
    public void unfreeze(Long userId, String staff, String reason) {
        setWalletStatus(userId, WalletStatus.ACTIVE, staff, required(reason, "reason"));
    }

    /**
     * Rubies' real case: a fraudster stays silent after a limit drops. No
     * contact within the silence window escalates the case. The hold stays.
     * Also closes first-day limits that have run their 24 hours.
     */
    @Scheduled(fixedDelayString = "${moniewise.aml.review-job.delay-ms:900000}",
            initialDelayString = "${moniewise.aml.review-job.initial-delay-ms:180000}")
    @Transactional
    public void reviewOpenHolds() {
        if (!settings.enabled()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        for (InflowHold hold : holds.findByReasonAndStatusAndEndsAtBefore(
                InflowHold.Reason.FIRST_DAY, InflowHold.Status.ACTIVE, now)) {
            hold.setStatus(InflowHold.Status.EXPIRED);
            holds.save(hold);
        }
        LocalDateTime before = now.minusHours(settings.silenceHours());
        for (InflowHold hold : holds.findByReasonAndStatusAndCustomerContactedAtIsNullAndStartedAtBefore(
                InflowHold.Reason.LARGE_INFLOW, InflowHold.Status.ACTIVE, before)) {
            markEscalated(hold, "system", "No contact from the customer within "
                    + settings.silenceHours() + "h of the hold. Hold kept.", now);
            User user = userRepository.findById(hold.getUserId()).orElse(null);
            Wallet wallet = walletRepository.findByUserId(hold.getUserId()).orElse(null);
            notifier.compliance("Silent customer on hold " + hold.getId() + " (user " + hold.getUserId()
                            + "): escalate to Rubies",
                    "Rubies' guide treats silence after a limit drop as a strong fraud signal.\n"
                            + "Send this to Rubies (support@rubies.ng):\n\n" + escalationText(hold, user, wallet));
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void markEscalated(InflowHold hold, String staff, String note, LocalDateTime now) {
        if (!hold.getStatus().isHolding()) {
            throw new IllegalStateException("Hold " + hold.getId() + " is already " + hold.getStatus());
        }
        hold.setStatus(InflowHold.Status.ESCALATED);
        if (hold.getEscalatedAt() == null) {
            hold.setEscalatedAt(now);
        }
        hold.addNote(staff, note, now);
        holds.save(hold);
    }

    private void setWalletStatus(Long userId, WalletStatus status, String staff, String reason) {
        Wallet wallet = walletRepository.findByUserId(userId)
                .orElseThrow(() -> new IllegalArgumentException("No wallet for user " + userId));
        wallet.setStatus(status);
        wallet.setUpdatedAt(LocalDateTime.now(clock));
        walletRepository.save(wallet);
        walletService.evictWalletCache(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        holds.findByUserIdAndStatusInOrderByIdDesc(userId, InflowReviewService.HOLDING).forEach(h -> {
            h.addNote(staff, "Wallet " + status + ": " + reason, now);
            holds.save(h);
        });
        logger.warn("[AML] Wallet of user={} set {} by {}", userId, status, staff);
        notifier.compliance("Wallet of user " + userId + " set " + status + " by " + staff, "Reason: " + reason);
    }

    /** The details Rubies asks for in guardrail 5, ready to paste. */
    String escalationText(InflowHold hold, User user, Wallet wallet) {
        StringBuilder text = new StringBuilder();
        String name = user != null && user.getProfileData() != null && user.getProfileData().get("name") != null
                ? user.getProfileData().get("name").toString() : "-";
        text.append("Customer: ").append(name).append("\n");
        text.append("Account number: ").append(wallet != null ? nullToEmpty(wallet.getAccountNumber()) : "-").append("\n");
        text.append("Account opened: ").append(wallet != null && wallet.getOpenedAt() != null
                ? wallet.getOpenedAt().toLocalDate() : user != null ? String.valueOf(user.getCreatedAt()) : "-").append("\n\n");
        text.append("Transactions:\n");
        for (InflowEvent credit : inflowReview.creditsOn(hold)) {
            text.append(" - ").append(AmlNotifier.naira(credit.getAmount()))
                    .append(" ref ").append(credit.getReference())
                    .append(" at ").append(credit.getReceivedAt().withNano(0))
                    .append(" from ").append(nullToDash(credit.getSenderName()))
                    .append(" / ").append(nullToDash(credit.getSenderBank()))
                    .append(" (name check ").append(credit.getNameCheck()).append(")\n");
        }
        text.append("\nWhy it was reviewed: ").append(AmlNotifier.naira(nullSafe(hold.getInflowTotal24h())))
                .append(" in 24h, threshold ").append(AmlNotifier.naira(nullSafe(hold.getThresholdUsed())))
                .append(", name check ").append(hold.getNameCheck()).append("\n");
        text.append("Status: outflow capped at ").append(AmlNotifier.naira(hold.getOutflowCap()))
                .append(", ").append(AmlNotifier.naira(hold.getOutflowUsed())).append(" already sent, ")
                .append(wallet != null ? "balance " + AmlNotifier.naira(wallet.getBalance()) : "")
                .append(", customer ")
                .append(hold.getCustomerContactedAt() != null ? "contacted us at " + hold.getCustomerContactedAt().withNano(0)
                        : "has not contacted us").append("\n");
        text.append("\nNotes and evidence:\n").append(hold.getNotes() == null ? "-" : hold.getNotes());
        return text.toString();
    }

    private InflowHold find(Long holdId) {
        return holds.findById(holdId)
                .orElseThrow(() -> new IllegalArgumentException("Hold " + holdId + " not found"));
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A " + field + " is required");
        }
        return value.trim();
    }

    private static java.math.BigDecimal nullSafe(java.math.BigDecimal value) {
        return value == null ? java.math.BigDecimal.ZERO : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
