package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.dto.response.UserSummaryResponse;
import com.moniewise.moniewise_backend.entity.Beneficiary;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.repository.BeneficiaryRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final UserService userService;
    private final UserRepository userRepository;

    // =========================================================================
    // 1. ADD BENEFICIARY (Write Operation - Safety First)
    // =========================================================================
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void addBeneficiary(Long currentUserId, String targetEmail, String alias) {
        // 1. Validation
        User target = userService.findByEmail(targetEmail); // Loads full user (Acceptable for single write)
        User currentUser = userRepository.findById(currentUserId)
                .orElseThrow(() -> new EntityNotFoundException("Current user not found"));

        if (currentUser.getId().equals(target.getId())) {
            throw new IllegalArgumentException("You cannot save yourself as a beneficiary.");
        }

        // 2. Duplicate Check
        if (beneficiaryRepository.existsByUserIdAndBeneficiaryUserId(currentUser.getId(), target.getId())) {
            throw new IllegalStateException("User is already in your beneficiary list.");
        }

        // 3. Resolve Alias
        String finalAlias = (alias != null && !alias.trim().isEmpty())
                ? alias.trim()
                : getSafeName(target);

        // 4. Save
        Beneficiary beneficiary = Beneficiary.builder()
                .user(currentUser)
                .beneficiaryUser(target)
                .alias(finalAlias)
                .build();

        beneficiaryRepository.save(beneficiary);
    }

    // =========================================================================
    // 2. GET BENEFICIARIES (Read Operation - Speed Optimized)
    // =========================================================================
    @Transactional(readOnly = true) // ✅ Important for performance
    public List<UserSummaryResponse> getMyBeneficiaries(Long currentUserId) {

        // 🛑 OLD: beneficiaryRepository.findByUserId(currentUserId) -> N+1 Queries
        // ✅ NEW: Fetches everything in 1 query using JOIN FETCH
        List<Beneficiary> beneficiaries = beneficiaryRepository.findByUserIdJoined(currentUserId);

        return beneficiaries.stream()
                .map(b -> {
                    User targetUser = b.getBeneficiaryUser();

                    // 1. Handle Generation
                    String handle = (targetUser.getEmail() != null)
                            ? "@" + targetUser.getEmail().split("@")[0]
                            : "Unknown";

                    // 2. Display Name Logic (Alias > Profile Name > Handle)
                    String displayName = (b.getAlias() != null && !b.getAlias().isEmpty())
                            ? b.getAlias()
                            : getSafeName(targetUser);

                    return new UserSummaryResponse(
                            displayName,
                            handle,
                            targetUser.getProfileImageUrl(),
                            targetUser.getEmail()
                    );
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 3. HELPER (Consistent Name Logic)
    // =========================================================================
    private String getSafeName(User user) {
        // 1. Try Profile Data
        if (user.getProfileData() != null) {
            Object fullName = user.getProfileData().get("fullName");
            Object name = user.getProfileData().get("name");

            if (fullName != null && !fullName.toString().isBlank()) return fullName.toString();
            if (name != null && !name.toString().isBlank()) return name.toString();
        }

        // 2. Fallback to Email Handle
        if (user.getEmail() != null && user.getEmail().contains("@")) {
            String handle = user.getEmail().split("@")[0];
            return handle.substring(0, 1).toUpperCase() + handle.substring(1);
        }

        return "Unknown User";
    }
}