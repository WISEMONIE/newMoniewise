package com.moniewise.moniewise_backend.tooling;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import com.moniewise.moniewise_backend.service.WalletService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class WalletRepairRunner implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(WalletRepairRunner.class);

    private final UserRepository userRepository;
    private final WalletRepository walletRepository;
    private final WalletService walletService;

    @Value("${moniewise.wallet-repair.enabled:false}")
    private boolean enabled;

    @Value("${moniewise.wallet-repair.dry-run:true}")
    private boolean dryRun;

    @Value("${moniewise.wallet-repair.target-email:}")
    private String targetEmail;

    @Value("${moniewise.wallet-repair.allow-bulk:false}")
    private boolean allowBulkRepair;

    @Value("${moniewise.revenue.wallet.user-id}")
    private Long revenueWalletUserId;

    public WalletRepairRunner(
            UserRepository userRepository,
            WalletRepository walletRepository,
            WalletService walletService
    ) {
        this.userRepository = userRepository;
        this.walletRepository = walletRepository;
        this.walletService = walletService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }

        if (!dryRun && isBlank(targetEmail) && !allowBulkRepair) {
            throw new IllegalStateException(
                    "Wallet repair live mode requires either moniewise.wallet-repair.target-email "
                            + "or moniewise.wallet-repair.allow-bulk=true");
        }

        List<User> usersToInspect = resolveUsersToInspect();
        RepairSummary summary = new RepairSummary();

        logger.warn(
                "Wallet repair started. mode={}, targetEmail={}, candidateCount={}",
                dryRun ? "DRY_RUN" : "LIVE",
                isBlank(targetEmail) ? "<all-users>" : targetEmail,
                usersToInspect.size()
        );

        for (User user : usersToInspect) {
            summary.inspected++;

            String skipReason = getSkipReason(user);
            if (skipReason != null) {
                summary.skipped++;
                logger.info("Wallet repair skipped user {}: {}", describeUser(user), skipReason);
                continue;
            }

            if (dryRun) {
                summary.eligible++;
                logger.info("Wallet repair dry-run: would create wallet for {}", describeUser(user));
                continue;
            }

            try {
                walletService.createWalletForUser(user);
                summary.repaired++;
                logger.info("Wallet repair created wallet for {}", describeUser(user));
            } catch (Exception e) {
                summary.failed++;
                logger.error("Wallet repair failed for {}: {}", describeUser(user), e.getMessage());
            }
        }

        logger.warn(
                "Wallet repair completed. inspected={}, eligible={}, repaired={}, skipped={}, failed={}",
                summary.inspected,
                summary.eligible,
                summary.repaired,
                summary.skipped,
                summary.failed
        );
    }

    private List<User> resolveUsersToInspect() {
        if (!isBlank(targetEmail)) {
            Optional<User> targetUser = userRepository.findFirstByEmailOrderByCreatedAtAsc(targetEmail.trim());
            return targetUser.map(List::of).orElseGet(List::of);
        }

        return userRepository.findAll();
    }

    private String getSkipReason(User user) {
        if (user == null) {
            return "user record is null";
        }
        if (user.getId() == null) {
            return "user has no id";
        }
        if (revenueWalletUserId != null && revenueWalletUserId.equals(user.getId())) {
            return "system revenue user";
        }
        if (user.isDeleted()) {
            return "soft-deleted user";
        }
        if (walletRepository.existsByUser(user)) {
            return "wallet already exists";
        }
        if (isBlank(user.getPhone())) {
            return "missing phone";
        }
        if (isBlank(user.getBvn())) {
            return "missing bvn";
        }

        Map<String, Object> profileData = user.getProfileData();
        if (isBlank(readProfileValue(profileData, "firstName"))) {
            return "missing firstName";
        }
        if (isBlank(readProfileValue(profileData, "lastName"))) {
            return "missing lastName";
        }

        return null;
    }

    private String readProfileValue(Map<String, Object> profileData, String key) {
        if (profileData == null) {
            return null;
        }

        Object value = profileData.get(key);
        return value != null ? value.toString() : null;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String describeUser(User user) {
        return String.format("id=%s,email=%s", user.getId(), user.getEmail());
    }

    private static class RepairSummary {
        private int inspected;
        private int eligible;
        private int repaired;
        private int skipped;
        private int failed;
    }
}
