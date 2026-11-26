package com.moniewise.moniewise_backend.config;

import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.Role;
import com.moniewise.moniewise_backend.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Seeds (idempotently) the single account an app-store reviewer logs in with.
 *
 * <p>It is a REAL user — same login endpoint, same bcrypt password check. It is
 * only pre-set to the state a reviewer can't reach on their own: email-verified,
 * profile complete (so no "finish your profile" wall), and flagged
 * {@code test_account = true} so login skips the first-login-per-device OTP a
 * reviewer can't receive. Nothing here bypasses password validation.
 *
 * <p>Credentials come from environment variables so they never live in source or
 * git history. If either is unset the seeder does nothing — so no default review
 * account can ever exist in an environment that didn't explicitly ask for one.
 * Set in Render (or delete/disable after review):
 * <pre>
 *   REVIEW_ACCOUNT_EMAIL=play-reviewer@wisemonie.com
 *   REVIEW_ACCOUNT_PASSWORD=&lt;a strong password&gt;
 * </pre>
 */
@Component
public class ReviewAccountSeeder implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(ReviewAccountSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${review.account.email:${REVIEW_ACCOUNT_EMAIL:}}")
    private String reviewEmail;

    @Value("${review.account.password:${REVIEW_ACCOUNT_PASSWORD:}}")
    private String reviewPassword;

    @Value("${review.account.phone:${REVIEW_ACCOUNT_PHONE:+2348000000000}}")
    private String reviewPhone;

    public ReviewAccountSeeder(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (reviewEmail == null || reviewEmail.isBlank()
                || reviewPassword == null || reviewPassword.isBlank()) {
            logger.info("[REVIEW-ACCOUNT] REVIEW_ACCOUNT_EMAIL/PASSWORD not set — skipping seed.");
            return;
        }

        String email = reviewEmail.trim().toLowerCase();
        try {
            User user = userRepository.findByEmail(email).orElseGet(User::new);
            boolean isNew = user.getId() == null;

            user.setEmail(email);
            user.setPassword(passwordEncoder.encode(reviewPassword));
            user.setVerified(true);        // email already verified
            user.setTestAccount(true);     // skip the per-device login OTP
            user.setDeleted(false);
            user.setRole(Role.USER);
            user.setTncAccepted(true);
            if (user.getPhone() == null || user.getPhone().isBlank()) {
                user.setPhone(reviewPhone.trim());
            }
            // A placeholder BVN so the profile-completion gate (phone+bvn+name)
            // is satisfied. This is a fake, review-only value — it is never sent
            // to the bank; the account performs no real KYC or money movement.
            if (user.getBvn() == null || user.getBvn().isBlank()) {
                user.setBvn("00000000000");
            }
            Map<String, Object> profile =
                    user.getProfileData() != null ? user.getProfileData() : new HashMap<>();
            profile.putIfAbsent("firstName", "Play");
            profile.putIfAbsent("lastName", "Reviewer");
            profile.putIfAbsent("name", "Play Reviewer");
            user.setProfileData(profile);

            userRepository.save(user);
            logger.info("[REVIEW-ACCOUNT] {} review account {}", isNew ? "Created" : "Refreshed", email);
        } catch (Exception e) {
            // Never let seeding break startup.
            logger.error("[REVIEW-ACCOUNT] Failed to seed review account", e);
        }
    }
}
