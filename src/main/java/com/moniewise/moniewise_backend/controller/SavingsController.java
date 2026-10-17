package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.CreateSavingsGoalRequest;
import com.moniewise.moniewise_backend.dto.request.UpdateSavingsGoalRequest;
import com.moniewise.moniewise_backend.dto.request.FundSavingsRequest;
import com.moniewise.moniewise_backend.dto.request.SavingsP2PTransferRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawSavingsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.entity.SavingsGoal;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.security.AuthenticatedUserHolder;
import com.moniewise.moniewise_backend.service.MarkupCalculatorService;
import com.moniewise.moniewise_backend.service.SavingsService;
import com.moniewise.moniewise_backend.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/savings") // Adjust your API version path if needed
public class SavingsController {

    private static final Logger logger = LoggerFactory.getLogger(SavingsController.class);

    private final SavingsService savingsService;
    private final UserService userService;
    private final MarkupCalculatorService markupCalculatorService;
    private final AuthenticatedUserHolder userHolder;

    public SavingsController(SavingsService savingsService, UserService userService,
                             MarkupCalculatorService markupCalculatorService,
                             AuthenticatedUserHolder userHolder) {
        this.savingsService = savingsService;
        this.userService = userService;
        this.markupCalculatorService = markupCalculatorService;
        this.userHolder = userHolder;
    }

    private User currentUser(String email) {
        return userHolder.isPresent() ? userHolder.getUser() : userService.findByEmail(email);
    }

    /**
     * POST: Create a new Savings Goal (Phase 1)
     */
    @PostMapping
    public ResponseEntity<?> createSavingsGoal(
            @Valid @RequestBody CreateSavingsGoalRequest request,
            Principal principal) {
        try {
            // 1. Get the securely authenticated user
            User user = currentUser(principal.getName());

            // 2. Call the Wealth Engine
            SavingsGoal createdGoal = savingsService.createSavingsGoal(user.getId(), request);

            return new ResponseEntity<>(createdGoal, HttpStatus.CREATED);

        } catch (IllegalArgumentException | IllegalStateException e) {
            // Catches validation errors like "Duplicate Name" or "Insufficient Funds"
            logger.warn("Savings creation failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error creating savings goal", e);
            return ResponseEntity.internalServerError().body("An error occurred while creating your savings goal.");
        }
    }

    /**
     * PATCH: Rename, change the amount, move the day later, or change reminders.
     * A live event cannot be cancelled here.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<?> updateEvent(
            @PathVariable("id") Long savingsGoalId,
            @RequestBody UpdateSavingsGoalRequest request,
            Principal principal) {
        try {
            User user = currentUser(principal.getName());
            SavingsGoal updated = savingsService.updateEvent(user.getId(), savingsGoalId, request);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException | IllegalStateException e) {
            logger.warn("Event update failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error updating event", e);
            return ResponseEntity.internalServerError().body("An error occurred while updating this savings.");
        }
    }

    /**
     * GET: Fetch all savings goals (all statuses) for the savings screen
     */
    @GetMapping
    public ResponseEntity<?> getAllSavings(Principal principal) {
        try {
            User user = currentUser(principal.getName());
            return ResponseEntity.ok(savingsService.getAllSavingsForUser(user.getId()));
        } catch (Exception e) {
            logger.error("Failed to fetch savings for {}", principal.getName(), e);
            return ResponseEntity.internalServerError().body("Failed to load savings goals.");
        }
    }

    /**
     * GET: Fetch only active savings pots
     */
    @GetMapping("/active")
    public ResponseEntity<?> getActiveSavings(Principal principal) {
        try {
            User user = currentUser(principal.getName());
            return ResponseEntity.ok(savingsService.getActiveSavingsForUser(user.getId()));
        } catch (Exception e) {
            logger.error("Failed to fetch active savings for {}", principal.getName(), e);
            return ResponseEntity.internalServerError().body("Failed to load savings goals.");
        }
    }

    /**
     * POST: Withdraw from a matured savings goal to the wallet.
     * Optional body {"amount": ...} = partial withdrawal; no body = full payout.
     */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<?> withdrawSavings(
            @PathVariable("id") Long savingsGoalId,
            @RequestBody(required = false) WithdrawSavingsRequest body,
            Principal principal) {
        try {
            User user = currentUser(principal.getName());
            SavingsGoal result = savingsService.withdrawSavings(
                    user.getId(), savingsGoalId, body != null ? body.getAmount() : null);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Withdrawal failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during savings withdrawal", e);
            return ResponseEntity.internalServerError().body("An error occurred while processing your withdrawal.");
        }
    }

    /**
     * POST: Send money from a MATURED savings pot to an external bank account.
     * Body is the same WithdrawalRequest the wallet endpoint uses (amount +
     * inline destination bank + PIN). Reuses the tested wallet withdrawal.
     */
    @PostMapping("/{id}/transfer/bank")
    public ResponseEntity<?> transferSavingsToBank(
            @PathVariable("id") Long savingsGoalId,
            @RequestBody WithdrawalRequest request,
            Principal principal) {
        try {
            User user = currentUser(principal.getName());
            SavingsGoal result = savingsService.transferSavingsToBank(user.getId(), savingsGoalId, request);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Savings bank transfer failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during savings bank transfer", e);
            return ResponseEntity.internalServerError().body("An error occurred while sending your savings to bank.");
        }
    }

    /**
     * POST: Send money from a MATURED savings pot to another Wisemonie user (P2P).
     */
    @PostMapping("/{id}/transfer/p2p")
    public ResponseEntity<?> transferSavingsToUser(
            @PathVariable("id") Long savingsGoalId,
            @RequestBody SavingsP2PTransferRequest request,
            Principal principal) {
        try {
            User user = currentUser(principal.getName());
            SavingsGoal result = savingsService.transferSavingsToUser(user.getId(), savingsGoalId, request);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Savings P2P failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during savings P2P transfer", e);
            return ResponseEntity.internalServerError().body("An error occurred while sending your savings.");
        }
    }

    /**
     * POST: Sweep a savings-linked envelope's balance into its savings goal
     */
    @PostMapping("/sweep-envelope/{envelopeId}")
    public ResponseEntity<?> sweepEnvelopeToSavings(
            @PathVariable("envelopeId") Long envelopeId,
            Principal principal) {
        try {
            SavingsGoal result = savingsService.triggerEnvelopeSweep(principal.getName(), envelopeId);
            return ResponseEntity.ok(result);
        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Envelope sweep failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during envelope sweep", e);
            return ResponseEntity.internalServerError().body("An error occurred while sweeping funds.");
        }
    }

    /**
     * POST: Manually Top-Up an existing Savings Goal from Wallet
     */
    @PostMapping("/{id}/fund")
    public ResponseEntity<?> fundSavingsGoal(
            @PathVariable("id") Long savingsGoalId,
            @Valid @RequestBody FundSavingsRequest request,
            Principal principal) {
        try {
            // 1. Authenticate user
            User user = currentUser(principal.getName());

            // 2. Execute manual top-up
            SavingsGoal updatedGoal = savingsService.manualTopUp(
                    user.getId(),
                    savingsGoalId,
                    request.getAmount()
            );

            return ResponseEntity.ok(updatedGoal);

        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
            logger.warn("Manual top-up failed for {}: {}", principal.getName(), e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("System error during manual top-up", e);
            return ResponseEntity.internalServerError().body("An error occurred while topping up your savings.");
        }
    }

    /**
     * GET: Fee quote for a savings→bank transfer.
     * Flat fee: ₦100 (< ₦50k) or ₦200 (≥ ₦50k). Fee comes from the pot.
     */
    @GetMapping("/quote-bank-transfer")
    public ResponseEntity<?> quoteBankTransfer(@RequestParam BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            return ResponseEntity.badRequest().body("Amount must be positive");
        }
        BigDecimal flatFee = SavingsService.savingsBankTransferFee(amount);
        BigDecimal nipFee  = markupCalculatorService.calculateNipFee(amount);
        BigDecimal totalDebit = amount.add(flatFee);
        return ResponseEntity.ok(Map.of(
                "transferAmount", amount,
                "fee", flatFee,
                "bankCharge", nipFee,
                "totalDebit", totalDebit,
                "recipientReceives", amount,
                "message", String.format("Send ₦%,.2f · Fee ₦%,.2f · Total from pot ₦%,.2f",
                        amount, flatFee, totalDebit)
        ));
    }
}
