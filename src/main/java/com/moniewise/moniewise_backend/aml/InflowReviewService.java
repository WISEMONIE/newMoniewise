package com.moniewise.moniewise_backend.aml;

import com.moniewise.moniewise_backend.entity.KycProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.KycProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Records every credit and starts a hold when money in reaches the
 * threshold (Rubies guide, guardrails 1 to 3).
 */
@Service
public class InflowReviewService {

    private static final Logger logger = LoggerFactory.getLogger(InflowReviewService.class);

    static final EnumSet<InflowHold.Status> HOLDING =
            EnumSet.of(InflowHold.Status.ACTIVE, InflowHold.Status.ESCALATED);

    private final InflowEventRepository inflowEvents;
    private final InflowHoldRepository holds;
    private final UserRepository userRepository;
    private final KycProfileRepository kycProfileRepository;
    private final AmlSettings settings;
    private final AmlNotifier notifier;
    private final Clock clock;

    public InflowReviewService(InflowEventRepository inflowEvents,
                               InflowHoldRepository holds,
                               UserRepository userRepository,
                               KycProfileRepository kycProfileRepository,
                               AmlSettings settings,
                               AmlNotifier notifier,
                               Optional<Clock> clock) {
        this.inflowEvents = inflowEvents;
        this.holds = holds;
        this.userRepository = userRepository;
        this.kycProfileRepository = kycProfileRepository;
        this.settings = settings;
        this.notifier = notifier;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    /** What the webhook knows about one credit. */
    public record Inflow(Long userId, BigDecimal amount, String reference,
                         String senderName, String senderBank, String senderAccount, boolean internal) {
    }

    /**
     * Call after the wallet has been credited. Runs in its own transaction,
     * so a problem here never undoes the credit. Safe to call twice for the
     * same reference.
     *
     * @return the hold the credit landed on, if any
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<InflowHold> recordInflow(Inflow inflow) {
        if (!settings.enabled() || inflow.userId() == null || inflow.amount() == null
                || inflow.amount().signum() <= 0 || isBlank(inflow.reference())) {
            return Optional.empty();
        }
        if (inflowEvents.existsByUserIdAndReference(inflow.userId(), inflow.reference())) {
            return Optional.empty();
        }
        User user = userRepository.findById(inflow.userId()).orElse(null);
        if (user == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);

        InflowEvent event = new InflowEvent();
        event.setUserId(user.getId());
        event.setReference(inflow.reference().trim());
        event.setAmount(inflow.amount());
        event.setSenderName(trim(inflow.senderName(), 200));
        event.setSenderBank(trim(inflow.senderBank(), 120));
        event.setSenderAccount(trim(inflow.senderAccount(), 20));
        event.setInternal(inflow.internal());
        event.setNameCheck(nameCheck(user, inflow.senderName()));
        event.setReceivedAt(now);
        try {
            event = inflowEvents.saveAndFlush(event);
        } catch (DataIntegrityViolationException duplicate) {
            return Optional.empty();
        }

        BigDecimal total24h = inflowEvents.sumSince(user.getId(), now.minusHours(24));
        BigDecimal threshold = thresholdFor(user, now);
        boolean large = inflow.amount().compareTo(threshold) >= 0 || total24h.compareTo(threshold) >= 0;
        if (!large) {
            return Optional.empty();
        }

        Optional<InflowHold> open = holds.findFirstByUserIdAndReasonAndStatusInOrderByIdDesc(
                user.getId(), InflowHold.Reason.LARGE_INFLOW, HOLDING);
        if (open.isPresent()) {
            InflowHold hold = open.get();
            hold.setInflowTotal24h(total24h);
            hold.setNameCheck(worse(hold.getNameCheck(), event.getNameCheck()));
            hold.addNote("system", "Another large credit " + AmlNotifier.naira(inflow.amount())
                    + " ref " + event.getReference() + " (name check " + event.getNameCheck() + ")", now);
            holds.save(hold);
            event.setHoldId(hold.getId());
            inflowEvents.save(event);
            notifier.compliance("Further large credit on held user " + user.getId()
                            + ": " + AmlNotifier.naira(inflow.amount()),
                    describeCredit(user, event, hold));
            return Optional.of(hold);
        }

        InflowHold hold = new InflowHold();
        hold.setUserId(user.getId());
        hold.setReason(InflowHold.Reason.LARGE_INFLOW);
        hold.setStatus(InflowHold.Status.ACTIVE);
        hold.setOutflowCap(settings.holdOutflowCap());
        hold.setOutflowUsed(BigDecimal.ZERO);
        hold.setTriggerReference(event.getReference());
        hold.setTriggerAmount(inflow.amount());
        hold.setInflowTotal24h(total24h);
        hold.setThresholdUsed(threshold);
        hold.setNameCheck(event.getNameCheck());
        hold.setStartedAt(now);
        hold.addNote("system", "Hold started: " + AmlNotifier.naira(inflow.amount()) + " credit, "
                + AmlNotifier.naira(total24h) + " in 24h, threshold " + AmlNotifier.naira(threshold)
                + ", name check " + event.getNameCheck(), now);
        hold = holds.saveAndFlush(hold);

        // The credits that added up to the threshold belong to this case too.
        for (InflowEvent recent : inflowEvents
                .findByUserIdAndReceivedAtGreaterThanEqualOrderByReceivedAtAsc(user.getId(), now.minusHours(24))) {
            if (recent.getHoldId() == null) {
                recent.setHoldId(hold.getId());
                inflowEvents.save(recent);
            }
        }

        logger.warn("[AML] Hold {} started for user={} credit={} total24h={} nameCheck={}",
                hold.getId(), user.getId(), inflow.amount(), total24h, event.getNameCheck());
        notifier.customerHoldStarted(user, hold.getOutflowCap());
        notifier.adminsHoldStarted(inflow.amount());
        notifier.compliance("Hold " + hold.getId() + " started for user " + user.getId()
                        + ": " + AmlNotifier.naira(inflow.amount()) + " (name check " + event.getNameCheck() + ")",
                describeCredit(user, event, hold));
        return Optional.of(hold);
    }

    /**
     * Accounts under the young-account age use the lower threshold, the rest
     * the normal one.
     *
     * <p>Deposits in a wallet's first 24 hours do not start a review on their
     * own: new customers should be able to fund and budget freely. The CBN
     * first-day limit applies to money out only (see OutflowGuard).
     */
    BigDecimal thresholdFor(User user, LocalDateTime now) {
        LocalDateTime since = user.getCreatedAt();
        if (since != null && now.isBefore(since.plusDays(settings.youngAccountDays()))) {
            return settings.youngAccountInflowThreshold();
        }
        return settings.inflowThreshold();
    }

    SenderNameMatcher.Result nameCheck(User user, String senderName) {
        Optional<KycProfile> kyc = kycProfileRepository.findFirstByUserIdOrderByCreatedAtAsc(user.getId());
        Map<String, Object> profile = user.getProfileData() != null ? user.getProfileData() : Map.of();
        String first = firstText(kyc.map(KycProfile::getFirstName).orElse(null), profile.get("bvnFirstName"),
                profile.get("firstName"));
        String middle = kyc.map(KycProfile::getMiddleName).orElse(null);
        String last = firstText(kyc.map(KycProfile::getLastName).orElse(null), profile.get("bvnLastName"),
                profile.get("lastName"));
        return SenderNameMatcher.match(senderName, first, middle, last);
    }

    /** Higher is riskier. */
    static int risk(SenderNameMatcher.Result result) {
        if (result == null) {
            return -1;
        }
        return switch (result) {
            case MATCH -> 0;
            case PARTIAL -> 1;
            case UNKNOWN -> 2;
            case MISMATCH -> 3;
            case COMPANY -> 4;
        };
    }

    static SenderNameMatcher.Result worse(SenderNameMatcher.Result a, SenderNameMatcher.Result b) {
        return risk(b) > risk(a) ? b : a;
    }

    private String describeCredit(User user, InflowEvent event, InflowHold hold) {
        return "User id: " + user.getId() + "\n"
                + "Credit: " + AmlNotifier.naira(event.getAmount()) + " ref " + event.getReference()
                + (event.isInternal() ? " (from another Wisemonie customer)" : "") + "\n"
                + "Sender: " + nullToDash(event.getSenderName()) + " / " + nullToDash(event.getSenderBank())
                + " / " + nullToDash(event.getSenderAccount()) + "\n"
                + "Name check: " + event.getNameCheck() + "\n"
                + "Total in 24h: " + AmlNotifier.naira(hold.getInflowTotal24h())
                + " (threshold " + AmlNotifier.naira(hold.getThresholdUsed()) + ")\n"
                + "Outflow cap while held: " + AmlNotifier.naira(hold.getOutflowCap()) + "\n\n"
                + "Review it at GET /admin/aml/holds/" + hold.getId();
    }

    public List<InflowEvent> creditsOn(InflowHold hold) {
        return inflowEvents.findByHoldIdOrderByReceivedAtAsc(hold.getId());
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
