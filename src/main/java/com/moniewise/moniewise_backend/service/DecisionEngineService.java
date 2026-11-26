package com.moniewise.moniewise_backend.service;

import com.moniewise.moniewise_backend.entity.*;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.WalletRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class DecisionEngineService {

    private final KycService kycService;
    private final TierService tierService;
    private final WalletRepository walletRepository;
    private final EnvelopeRepository envelopeRepository;

    public DecisionEngineService(KycService kycService, TierService tierService,
                                 WalletRepository walletRepository, EnvelopeRepository envelopeRepository) {
        this.kycService = kycService;
        this.tierService = tierService;
        this.walletRepository = walletRepository;
        this.envelopeRepository = envelopeRepository;
    }

    public TransactionDecision.Decision evaluateWithdrawal(Long userId, BigDecimal amount) {
        return evaluateRequest(new TransactionRequest(null, null, null, amount, TransactionRequest.TransactionType.WITHDRAWAL, null, null, null, null, null, null));
    }

    public TransactionDecision.Decision evaluateTransfer(Long userId, BigDecimal amount, Long sourceEnvelopeId) {
        TransactionRequest request = new TransactionRequest();
        User user = new User();
        user.setId(userId);
        request.setUser(user);
        request.setAmount(amount);
        request.setTransactionType(TransactionRequest.TransactionType.TRANSFER);
        request.setSourceEnvelopeId(sourceEnvelopeId);
        return evaluateRequest(request);
    }

    public TransactionDecision.Decision evaluateRequest(TransactionRequest request) {
        if (request.getUser() == null) {
            return TransactionDecision.Decision.BLOCK;
        }
        Long userId = request.getUser().getId();
        BigDecimal amount = request.getAmount();

        // KYC check
        if (!kycService.isUserVerified(userId)) {
            return TransactionDecision.Decision.BLOCK;
        }

        // Tier check
        Optional<TierProfile> tierOpt = tierService.getActiveTierForUser(userId);
        if (tierOpt.isEmpty()) {
            return TransactionDecision.Decision.BLOCK;
        }
        TierProfile tier = tierOpt.get();
        if (amount.compareTo(tier.getMaxTransactionAmount()) > 0) {
            return TransactionDecision.Decision.BLOCK;
        }

        // Wallet check
        Optional<Wallet> walletOpt = walletRepository.findByUserId(userId);
        if (walletOpt.isEmpty()) {
            return TransactionDecision.Decision.BLOCK;
        }
        Wallet wallet = walletOpt.get();
        if (wallet.getBalance().compareTo(amount) < 0) {
            return TransactionDecision.Decision.BLOCK;
        }

        // Envelope check if sourceEnvelopeId present
        if (request.getSourceEnvelopeId() != null) {
            Optional<Envelope> envelopeOpt = envelopeRepository.findById(request.getSourceEnvelopeId());
            if (envelopeOpt.isEmpty()
                    || envelopeOpt.get().getBudget() == null
                    || envelopeOpt.get().getBudget().getUser() == null
                    || !envelopeOpt.get().getBudget().getUser().getId().equals(userId)) {
                return TransactionDecision.Decision.BLOCK;
            }
            Envelope envelope = envelopeOpt.get();
            if (envelope.getRemainingAmount().compareTo(amount) < 0) {
                return TransactionDecision.Decision.BLOCK;
            }
        }

        return TransactionDecision.Decision.ALLOW;
    }
}
