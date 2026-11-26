package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.ExternalTransferQuoteRequest;
import com.moniewise.moniewise_backend.dto.ExternalTransferQuoteResponse;
import com.moniewise.moniewise_backend.dto.request.EnvelopeRequest;
import com.moniewise.moniewise_backend.dto.request.ExternalTransferRequest;
import com.moniewise.moniewise_backend.dto.request.P2PTransferRequest;
import com.moniewise.moniewise_backend.dto.response.EnvelopeResponse;
import com.moniewise.moniewise_backend.dto.response.ExternalTransferResponse;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.service.EnvelopeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.Map;

@RestController
@RequestMapping("/envelopes")
public class EnvelopeController {

    @Autowired
    private BudgetRepository budgetRepository;

    @Autowired
    private EnvelopeService envelopeService;

    private static final Logger logger = LoggerFactory.getLogger(BudgetController.class);

    @PostMapping("/{id}/move")
    public ResponseEntity<?> moveMoney(@PathVariable Long id, @RequestBody Map<String, Object> requestBody, Authentication authentication) {
        System.out.println("POST /envelopes/" + id + "/move called");
        try {
            String email = authentication.getName();
            Long targetId = Long.valueOf(requestBody.get("target_envelope_id").toString());
            Double amount = Double.valueOf(requestBody.get("amount").toString());
            String withdrawalReason = (String) requestBody.getOrDefault("withdrawalReason", null);

            envelopeService.moveMoney(id, targetId, amount, email, withdrawalReason);
            return ResponseEntity.ok("Money moved successfully");
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error moving money: " + e.getMessage());
        }
    }

    // 08/06/20225 -new fix for transfer to external
    @PostMapping("/{id}/transfer-external")
    public ResponseEntity<?> transferToExternal(
            @PathVariable Long id,
            @RequestBody BudgetController.TransferExternalRequest request,
            Authentication authentication) {

        Logger logger = LoggerFactory.getLogger(BudgetController.class);
        logger.info("POST /envelopes/{}/transfer-external called with payload: {}", id, request);

        try {
            String email = authentication.getName();
            // CORRECT:
            validateTransferRequest(request);

            ExternalTransferResponse transfer = envelopeService.transferToExternal(
                    id,
                    request.getExternalAccount(),
                    request.getAmount(),
                    email,
                    request.getWithdrawalReason(),
                    request.getNarration(),
                    request.getTransactionPin()
            );
            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", "Transfer successful",
                    "data", Map.of(
                            "status", true,
                            "message", "Transfer request has been received and is being processed",
                            "data", Map.of(
                                    "clientReference", transfer.getClientReference(),
                                    "providerReference", transfer.getProviderReference(),
                                    "amount", transfer.getAmount(),
                                    "recipientName", transfer.getRecipientName(),
                                    "bankName", transfer.getBankName(),
                                    "status", transfer.getStatus().name()
                            )
                    )
            ));

        } catch (com.moniewise.moniewise_backend.aml.OutflowBlockedException blocked) {
            // Freeze, review hold or first-day limit: the global handler adds the code the app reads.
            throw blocked;
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid transfer request for envelope {}: {}", id, e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (SecurityException e) {
            logger.warn("Unauthorized transfer attempt for envelope {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            logger.error("Error initiating transfer for envelope {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("message", "Error initiating transfer: " + (e.getMessage() != null ? e.getMessage() : "Unknown error")));
        }
    }

    private void validateTransferRequest(BudgetController.TransferExternalRequest request) {
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        if (request.getExternalAccount() == null) {
            throw new IllegalArgumentException("External account details are required");
        }
        if (request.getTransactionPin() == null || request.getTransactionPin().isBlank()) {
            throw new IllegalArgumentException("Transaction PIN is required");
        }
        BudgetController.ExternalAccount account = request.getExternalAccount();
        if (account.getAccountNumber() == null || account.getAccountNumber().isBlank()) {
            throw new IllegalArgumentException("Account number is required");
        }
        if (account.getRecipientName() == null || account.getRecipientName().isBlank()) {
            throw new IllegalArgumentException("Recipient name is required");
        }
        if (!account.getAccountNumber().matches("\\d{10}")) {
            throw new IllegalArgumentException("Account number must be a 10-digit NUBAN");
        }
    }

           // Create an envelope
           // Create Envelope
   @PostMapping
   public ResponseEntity<EnvelopeResponse> createEnvelope(
           @RequestBody EnvelopeRequest request,
           Authentication authentication
   ) {
       String email = authentication.getName();
       EnvelopeResponse response = envelopeService.createEnvelope(request, email, false);
       return new ResponseEntity<>(response, HttpStatus.CREATED);
   }

    // Get Envelope
    @GetMapping("/{envelopeId}")
    public ResponseEntity<EnvelopeResponse> getEnvelope(
            @PathVariable Long envelopeId,
            Authentication authentication
    ) {
        String email = authentication.getName();

        // Ã°Å¸â€ºâ€˜ FORCE RECALCULATION ON VIEW Ã°Å¸â€ºâ€˜
        // This ensures the user sees the "Vault Cap" corrected balance immediately.
        envelopeService.getRemainingLimit(envelopeId, email);

        EnvelopeResponse response = envelopeService.getEnvelopeById(envelopeId, email);
        return ResponseEntity.ok(response);
    }

    // Update Envelope (only conditions allowed)
    @PutMapping("/{envelopeId}")
    public ResponseEntity<EnvelopeResponse> updateEnvelope(
            @PathVariable Long envelopeId,
            @RequestBody EnvelopeRequest request,
            Authentication authentication
    ) {
        String email = authentication.getName();
        EnvelopeResponse response = envelopeService.updateEnvelopeConditions(envelopeId, request, email);
        return ResponseEntity.ok(response);
    }

    // Delete Envelope (only if parent budget is not ACTIVE)
    @DeleteMapping("/{envelopeId}")
    public ResponseEntity<?> deleteEnvelope(
            @PathVariable Long envelopeId,
            Authentication authentication
    ) {
        String email = authentication.getName();
        envelopeService.deleteEnvelope(envelopeId, email);
        return ResponseEntity.noContent().build();
    }

    // ================== PERSON TO EXTERNAL TRANSFER =====================
    // NOTE: This is a convenience alias for POST /envelopes/{id}/transfer-external.
    // It accepts a sourceEnvelopeId in the body instead of the path.
    @PostMapping("/transfer/external")
    public ResponseEntity<?> transferToExternalBank(
            @RequestBody ExternalTransferRequest request,
            Authentication authentication
    ) {
        // Validate the same rules as the path-variable endpoint —
        // PIN, account number format, recipient name are all required here too.
        if (request.getAmount() == null || request.getAmount().doubleValue() <= 0) {
            return ResponseEntity.badRequest().body(Map.of("message", "Amount must be positive"));
        }
        if (request.getTransactionPin() == null || request.getTransactionPin().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Transaction PIN is required"));
        }
        if (request.getAccountNumber() == null || request.getAccountNumber().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Account number is required"));
        }
        if (!request.getAccountNumber().matches("\\d{10}")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Account number must be a 10-digit NUBAN"));
        }
        if (request.getRecipientName() == null || request.getRecipientName().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Recipient name is required"));
        }
        if (request.getSourceEnvelopeId() == null) {
            return ResponseEntity.badRequest().body(Map.of("message", "sourceEnvelopeId is required"));
        }

        String email = authentication.getName();
        // 1. Map the DTO to the Inner Class your Service expects
        BudgetController.ExternalAccount beneficiary = new BudgetController.ExternalAccount();
        beneficiary.setAccountNumber(request.getAccountNumber());
        beneficiary.setBankCode(request.getBankCode());
        beneficiary.setBankName(request.getBankName());
        beneficiary.setRecipientName(request.getRecipientName());

        // 2. Call the Service
        ExternalTransferResponse transfer = envelopeService.transferToExternal(
                request.getSourceEnvelopeId(),
                beneficiary,
                request.getAmount().doubleValue(),
                email,
                request.getWithdrawalReason(),
                request.getNarration(),
                request.getTransactionPin()
        );

        return ResponseEntity.ok(Map.of(
                "status", true,
                "message", "Transfer successful",
                "data", Map.of(
                        "status", true,
                        "message", "Transfer request has been received and is being processed",
                        "data", Map.of(
                                "clientReference", transfer.getClientReference(),
                                "providerReference", transfer.getProviderReference(),
                                "amount", transfer.getAmount(),
                                "recipientName", transfer.getRecipientName(),
                                "bankName", transfer.getBankName(),
                                "status", transfer.getStatus().name()
                        )
                )
        ));
    }


   // ================== PERSON TO PERSON TRANSFER =====================
    @PostMapping("/transfer/p2p")
    public ResponseEntity<?> p2pTransfer(
            @RequestBody P2PTransferRequest request,
            Authentication authentication // Or however you get current user email
    ) {
        String email = authentication.getName(); // <--- Get email safely here
        envelopeService.transferToMonieWiseUser(request, email);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Transfer successful"
        ));
    }

    // ================== DISBURSEMENT (THE CLAIM FLOW) =====================

    // 1. Check if there is money to claim (Frontend calls this when page loads)
    @GetMapping("/{id}/disbursement/pending")
    public ResponseEntity<?> getPendingDisbursement(
            @PathVariable Long id,
            Authentication authentication
    ) {
        // You will need to add this simple lookup method to EnvelopeService
        // It returns the PendingDisbursement object if one exists and isn't expired
        String email = authentication.getName();
        var pending = envelopeService.findPendingDisbursementByEnvelopeId(id, email);

        if (pending == null) {
            return ResponseEntity.noContent().build(); // No button needed
        }
        return ResponseEntity.ok(pending); // Return ID, Amount, ExpiresAt
    }

    // 2. The Trigger: User clicks "Claim"
    @PostMapping("/disbursements/{disbursementId}/claim")
    public ResponseEntity<?> claimDisbursement(
            @PathVariable Long disbursementId,
            Authentication authentication
    ) {
        String email = authentication.getName();
        envelopeService.claimDisbursement(disbursementId, email);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Funds unlocked! You can now spend."
        ));
    }

    @PostMapping("/{envelopeId}/transfer-external/quote")
    public ResponseEntity<?> quoteExternalTransfer(
            @PathVariable Long envelopeId,
            @Valid @RequestBody ExternalTransferQuoteRequest request,
            @AuthenticationPrincipal UserDetails userDetails
    ) {
        ExternalTransferQuoteResponse quote =
                envelopeService.quoteExternalTransfer(
                        envelopeId,
                        request.getAmount(),
                        request.getNote(),
                        userDetails.getUsername()
                );

        return ResponseEntity.ok(Map.of(
                "status", true,
                "message", "External transfer quote generated successfully",
                "data", quote
        ));
    }

    }

