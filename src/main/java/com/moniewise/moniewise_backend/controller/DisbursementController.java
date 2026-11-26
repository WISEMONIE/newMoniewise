package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.response.ClaimDisbursementResponse;
import com.moniewise.moniewise_backend.entity.Budget;
import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.PendingDisbursement;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.PendingDisbursementRepository;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/disbursements")
@RequiredArgsConstructor
@Slf4j
public class DisbursementController {

    private final EnvelopeService envelopeService;
    private final PendingDisbursementRepository pendingDisbursementRepository;
    private final EnvelopeRepository envelopeRepository;
    private final BudgetRepository budgetRepository;
    private final AbuseProtectionService abuseProtectionService;

    /**
     * POST /disbursements/{id}/claim
     * Rate-limited — prevents rapid double-claim attempts.
     */
    @PostMapping("/{id}/claim")
    public ResponseEntity<ClaimDisbursementResponse> claim(
            @PathVariable("id") Long pendingDisbursementId,
            HttpServletRequest httpRequest) {

        String email = SecurityContextHolder.getContext().getAuthentication().getName();
        log.info("User {} attempting to claim disbursement {}", email, pendingDisbursementId);

        String throttleKey = abuseProtectionService.buildKey(email, httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.DISBURSEMENT_CLAIM, throttleKey);

        envelopeService.claimDisbursement(pendingDisbursementId, email);

        PendingDisbursement pd = pendingDisbursementRepository.findById(pendingDisbursementId)
                .orElseThrow(() -> new IllegalStateException("Disbursement disappeared after claim"));

        Envelope envelope = envelopeRepository.findById(pd.getEnvelopeId())
                .orElseThrow(() -> new IllegalStateException("Envelope not found"));

        Budget budget = envelope.getBudget();

        // Claim succeeded — clear the failure window so transient errors don't compound
        abuseProtectionService.recordSuccess(AbuseProtectionService.DISBURSEMENT_CLAIM, throttleKey);

        ClaimDisbursementResponse resp = new ClaimDisbursementResponse(
                pd.getId(),
                pd.getEnvelopeName(),
                pd.getAmount(),
                pd.getProcessedAt(),
                envelope.getTotalRemainingAmount(),
                budget.getRemainingAmount(),
                "Disbursement claimed successfully"
        );

        return ResponseEntity.ok(resp);
    }
}
