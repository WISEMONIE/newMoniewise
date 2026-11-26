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
    private final AmlLockRepository locks;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @Lazy
    private com.moniewise.moniewise_backend.service.WalletOpeningService walletOpeningService;

    /** Only one server runs the review job at a time. */
    static final long REVIEW_JOB_LOCK = (0x414D4CL << 32) | 0x52455649L;

    public AmlCaseService(InflowHoldRepository holds,
                          InflowReviewService inflowReview,
                          UserRepository userRepository,
                          WalletRepository walletRepository,
                          @Lazy WalletService walletService,
                          AmlNotifier notifier,
                          AmlSettings settings,
                          AmlLockRepository locks,
                          Optional<Clock> clock) {
        this.holds = holds;
        this.inflowReview = inflowReview;
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
        this.notifier = notifier;
        this.settings = settings;
        this.locks = locks;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    /**
     * Rows for the review console. Open: large-inflow holds still holding,
     * oldest first. Closed: the 200 most recent releases. First-day limits are
     * left out because they end on their own and need no review.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> holdSummaries(boolean open) {
        List<InflowHold> rows = open
                ? holds.findByReasonAndStatusInOrderByStartedAtAsc(InflowHold.Reason.LARGE_INFLOW,
                        InflowReviewService.HOLDING)
                : holds.findTop200ByReasonAndStatusOrderByReleasedAtDesc(InflowHold.Reason.LARGE_INFLOW,
                        InflowHold.Status.RELEASED);
        LocalDateTime now = LocalDateTime.now(clock);
        return rows.stream().map(hold -> summary(hold, now)).toList();
    }

    private Map<String, Object> summary(InflowHold hold, LocalDateTime now) {
        User user = userRepository.findById(hold.getUserId()).orElse(null);
        Wallet wallet = walletRepository.findByUserId(hold.getUserId()).orElse(null);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", hold.getId());
        row.put("userId", hold.getUserId());
        row.put("customerName", displayName(user));
        row.put("email", user == null ? "" : nullToEmpty(user.getEmail()));
        row.put("accountNumber", wallet == null ? "" : nullToEmpty(wallet.getAccountNumber()));
        row.put("walletStatus", wallet == null ? "" : String.valueOf(wallet.getStatus()));
        row.put("status", hold.getStatus());
        row.put("nameCheck", hold.getNameCheck());
        row.put("triggerAmount", hold.getTriggerAmount());
        row.put("inflowTotal24h", hold.getInflowTotal24h());
        row.put("outflowUsed", hold.getOutflowUsed());
        row.put("outflowCap", hold.getOutflowCap());
        row.put("startedAt", hold.getStartedAt());
        row.put("hoursOpen", java.time.Duration.between(hold.getStartedAt(), now).toHours());
        row.put("customerContactedAt", hold.getCustomerContactedAt());
        row.put("escalatedAt", hold.getEscalatedAt());
        row.put("rubiesNotifiedAt", hold.getRubiesNotifiedAt());
        row.put("releasedAt", hold.getReleasedAt());
        row.put("releasedBy", hold.getReleasedBy());
        return row;
    }

    private static String displayName(User user) {
        if (user == null || user.getProfileData() == null || user.getProfileData().get("name") == null) {
            return "";
        }
        return user.getProfileData().get("name").toString();
    }

    /**
     * The admin dashboard: what needs doing, split by who has to act.
     * <ul>
     *   <li>informRubies: escalated and Rubies not told yet</li>
     *   <li>reviewNow: the customer got in touch, so a decision is due</li>
     *   <li>waitingOnCustomer: no word yet, with hours left before it escalates</li>
     *   <li>walletsToFix: verified customers whose wallet keeps failing to open</li>
     *   <li>frozenWallets: wallets on post-no-debit</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public Map<String, Object> todos() {
        LocalDateTime now = LocalDateTime.now(clock);
        List<Map<String, Object>> informRubies = new java.util.ArrayList<>();
        List<Map<String, Object>> reviewNow = new java.util.ArrayList<>();
        List<Map<String, Object>> waiting = new java.util.ArrayList<>();
        for (InflowHold hold : holds.findByReasonAndStatusInOrderByStartedAtAsc(
                InflowHold.Reason.LARGE_INFLOW, InflowReviewService.HOLDING)) {
            Map<String, Object> row = summary(hold, now);
            if (hold.getStatus() == InflowHold.Status.ESCALATED && hold.getRubiesNotifiedAt() == null) {
                informRubies.add(row);
            } else if (hold.getStatus() == InflowHold.Status.ACTIVE && hold.getCustomerContactedAt() != null) {
                reviewNow.add(row);
            } else if (hold.getStatus() == InflowHold.Status.ACTIVE) {
                long left = settings.silenceHours()
                        - java.time.Duration.between(hold.getStartedAt(), now).toHours();
                row.put("hoursUntilEscalation", Math.max(0, left));
                waiting.add(row);
            }
        }
        List<Map<String, Object>> walletsToFix = walletOpeningService == null
                ? List.of() : walletOpeningService.failedOpenings(50);
        List<Map<String, Object>> frozen = walletRepository
                .findTop100ByStatusOrderByUpdatedAtDesc(WalletStatus.FROZEN).stream()
                .filter(w -> w.getUser() != null)
                .map(w -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", w.getUser().getId());
                    row.put("customerName", displayName(w.getUser()));
                    row.put("accountNumber", nullToEmpty(w.getAccountNumber()));
                    row.put("balance", w.getBalance());
                    row.put("since", w.getUpdatedAt());
                    return row;
                }).toList();

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("informRubies", informRubies.size());
        counts.put("reviewNow", reviewNow.size());
        counts.put("waitingOnCustomer", waiting.size());
        counts.put("walletsToFix", walletsToFix.size());
        counts.put("frozenWallets", frozen.size());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("counts", counts);
        result.put("informRubies", informRubies);
        result.put("reviewNow", reviewNow);
        result.put("waitingOnCustomer", waiting);
        result.put("walletsToFix", walletsToFix);
        result.put("frozenWallets", frozen);
        return result;
    }

    /** Someone told Rubies. Takes the case off the "Inform Rubies" list; the hold stays. */
    @Transactional
    public InflowHold markRubiesInformed(Long holdId, String staff, String note) {
        InflowHold hold = find(holdId);
        LocalDateTime now = LocalDateTime.now(clock);
        hold.setRubiesNotifiedAt(now);
        hold.addNote(staff, "Rubies informed" + (note == null || note.isBlank() ? "" : ": " + note.trim()), now);
        return holds.save(hold);
    }

    /** Tries to open a failed wallet now, ignoring the wait after the last failure. */
    public Map<String, Object> retryWalletOpening(Long userId, String staff) {
        if (walletOpeningService == null) {
            throw new IllegalStateException("Wallet opening is not available");
        }
        logger.warn("[AML] Wallet opening retried by {} for user={}", staff, userId);
        Wallet wallet = walletOpeningService.openFromVerifiedKyc(userId);
        return Map.of("userId", userId, "accountNumber", nullToEmpty(wallet.getAccountNumber()),
                "bankName", nullToEmpty(wallet.getBankName()));
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
                "name", displayName(user),
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
        if (!Boolean.TRUE.equals(locks.tryLock(REVIEW_JOB_LOCK))) {
            logger.debug("[AML] Review job already running on another server");
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
            String subject = "Silent customer on hold " + hold.getId() + " (user " + hold.getUserId()
                    + "): escalate to Rubies";
            String body = "Rubies' guide treats silence after a limit drop as a strong fraud signal.\n"
                    + "Send this to Rubies (support@rubies.ng):\n\n" + escalationText(hold, user, wallet);
            afterCommit(() -> notifier.compliance(subject, body));
            afterCommit(notifier::adminsTellRubies);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    /**
     * Sends only once the change is saved, so a rolled-back run never alerts.
     * Anything the send writes (the push outbox) gets its own transaction:
     * after a commit, writes in the old one would be silently dropped.
     */
    private void afterCommit(Runnable send) {
        Runnable inOwnTransaction = () -> {
            if (transactionManager == null) {
                send.run();
                return;
            }
            org.springframework.transaction.support.TransactionTemplate tx =
                    new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            tx.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            tx.executeWithoutResult(status -> send.run());
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                inOwnTransaction.run();
                            } catch (RuntimeException e) {
                                logger.warn("[AML] Alert after commit failed: {}", e.getMessage());
                            }
                        }
                    });
        } else {
            inOwnTransaction.run();
        }
    }

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
        String name = displayName(user).isEmpty() ? "-" : displayName(user);
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
