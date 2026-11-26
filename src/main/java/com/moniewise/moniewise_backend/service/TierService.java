package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.TierProfile;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.UserTierAssignment;
import com.moniewise.moniewise_backend.repository.TierProfileRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.repository.UserTierAssignmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class TierService {

    private final TierProfileRepository tierProfileRepository;
    private final UserTierAssignmentRepository userTierAssignmentRepository;
    private final UserRepository userRepository;
    private final KycService kycService;

    public TierService(TierProfileRepository tierProfileRepository,
                       UserTierAssignmentRepository userTierAssignmentRepository,
                       UserRepository userRepository,
                       KycService kycService) {
        this.tierProfileRepository = tierProfileRepository;
        this.userTierAssignmentRepository = userTierAssignmentRepository;
        this.userRepository = userRepository;
        this.kycService = kycService;
    }

    public Optional<TierProfile> getActiveTierForUser(Long userId) {
        return userTierAssignmentRepository.findByUserIdAndStatus(userId, UserTierAssignment.Status.ACTIVE)
                .map(UserTierAssignment::getTierProfile);
    }

    @Transactional
    public void assignTier(Long userId, String tierCode) {
        User user = userRepository.findById(userId).orElseThrow(() -> new IllegalArgumentException("User not found"));
        Optional<TierProfile> tierOpt = tierProfileRepository.findByTierCode(tierCode);
        if (tierOpt.isEmpty()) {
            throw new IllegalArgumentException("Tier not found");
        }
        // Deactivate existing
        userTierAssignmentRepository.findByUserIdAndStatus(userId, UserTierAssignment.Status.ACTIVE)
                .ifPresent(assignment -> {
                    assignment.setStatus(UserTierAssignment.Status.INACTIVE);
                    userTierAssignmentRepository.save(assignment);
                });
        // Assign new
        UserTierAssignment assignment = new UserTierAssignment();
        assignment.setUser(user);
        assignment.setTierProfile(tierOpt.get());
        assignment.setStatus(UserTierAssignment.Status.ACTIVE);
        userTierAssignmentRepository.save(assignment);
    }

    public boolean validateTransaction(Long userId, BigDecimal amount) {
        Optional<TierProfile> tierOpt = getActiveTierForUser(userId);
        if (tierOpt.isPresent()) {
            TierProfile tier = tierOpt.get();
            return amount.compareTo(tier.getMaxTransactionAmount()) <= 0;
        }
        return false;
    }

    @Transactional
    public void upgradeTierBasedOnKyc(Long userId) {
        if (kycService.isUserVerified(userId)) {
            assignTier(userId, "TIER_2"); // Example
        }
    }
}