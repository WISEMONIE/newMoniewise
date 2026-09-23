package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.request.UpdateBankDetailsRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalQuoteRequest;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.FeeReserveResponse;
import com.moniewise.moniewise_backend.dto.response.WithdrawalQuoteResponse;
import com.moniewise.moniewise_backend.dto.response.WalletResponse;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.entity.Wallet;
import com.moniewise.moniewise_backend.entity.Withdrawal;
import com.moniewise.moniewise_backend.security.AuthenticatedUserHolder;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.FeeReserveService;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

import java.math.BigDecimal;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;
    private final UserService userService;
    private final AbuseProtectionService abuseProtectionService;
    private final AuthenticatedUserHolder userHolder;
    private final FeeReserveService feeReserveService;

    public WalletController(WalletService walletService,
                            UserService userService,
                            AbuseProtectionService abuseProtectionService,
                            AuthenticatedUserHolder userHolder,
                            FeeReserveService feeReserveService) {
        this.walletService = walletService;
        this.userService = userService;
        this.abuseProtectionService = abuseProtectionService;
        this.userHolder = userHolder;
        this.feeReserveService = feeReserveService;
    }

    /** Returns the current user from the request-scoped holder (populated by the JWT filter).
     *  Falls back to a DB lookup for edge cases (e.g. tests, public endpoints with auth). */
    private User currentUser(String email) {
        return userHolder.isPresent() ? userHolder.getUser() : userService.findByEmail(email);
    }

    @GetMapping
    public ResponseEntity<?> getMyWallet(Authentication authentication) {
        User user = currentUser(authentication.getName());
        Long userId = user.getId();

        Wallet wallet = walletService.getWalletByUserId(userId);
        BigDecimal totalHoldings = walletService.getTotalHoldings(userId);

        return ResponseEntity.ok(new WalletResponse(
                wallet.getBalance(),
                wallet.getCurrency(),
                wallet.getAccountNumber(),
                wallet.getBankName(),
                walletService.resolveFundingAccountName(wallet, user),
                wallet.getStatus().name(),
                wallet.getUpdatedAt(),
                wallet.getProviderName(),
                totalHoldings
        ));
    }

    @GetMapping("/bank-info")
    public ResponseEntity<?> getLinkedBankInfo(Principal principal) {
        try {
            User user = currentUser(principal.getName());
            Map<String, Object> linkedBankInfo = walletService.getLinkedBankInfo(user.getId(), user.getEmail());

            if (linkedBankInfo == null || linkedBankInfo.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                        "status", false,
                        "message", "No linked bank account found"
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", "Linked bank account fetched successfully",
                    "data", linkedBankInfo
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", false,
                    "message", e.getMessage()
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
                    "status", false,
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET: Fetch all supported banks for withdrawals
     */
    @GetMapping("/banks")
    public ResponseEntity<?> getSupportedBanks(Authentication authentication) {
        try {
            Long userId = currentUser(authentication.getName()).getId();
            List<Map<String, Object>> banks = walletService.getSupportedBanks(userId);

            if (banks.isEmpty()) {
                // Safety-net: WalletService now has a three-layer fallback
                // (upstream → stale cache → static Nigerian list) so this branch
                // should never be reached in practice.  Keep it here as a last-
                // resort guard; always return JSON so the Flutter client doesn't
                // crash on a plain-text body.
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("message", "Bank list is temporarily unavailable. Please try again shortly."));
            }

            return ResponseEntity.ok(banks);
        } catch (Exception e) {
            log.error("getSupportedBanks error: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "An error occurred while fetching banks"));
        }
    }

    /**
     * GET: "Transferred before" auto-suggest list for the Transfer to Bank screen.
     *
     * <p>Returns up to {@code limit} distinct destination accounts the
     * authenticated user has successfully sent money to before, most-recent-
     * first — derived live from their own COMPLETED withdrawal history (see
     * {@link WalletService#getRecentRecipients}). Backs both the quick top-3
     * dropdown on the Transfer to Bank screen and the "see more" full-list
     * screen — same data, just a different limit.
     *
     * <p>No rate limiting — this purely reads the user's own transaction
     * history (same trust level as {@code GET /wallets/withdrawals}), no
     * external calls or enumeration risk like {@code resolve-account} has.
     */
    @GetMapping("/recent-recipients")
    public ResponseEntity<?> getRecentRecipients(
            Authentication authentication,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            Long userId = currentUser(authentication.getName()).getId();
            int clamped = Math.max(1, Math.min(limit, 50));
            return ResponseEntity.ok(walletService.getRecentRecipients(userId, clamped));
        } catch (Exception e) {
            log.error("getRecentRecipients error: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "An error occurred while fetching recent recipients"));
        }
    }

    /**
     * POST: Resolve Account Name (KYC Check).
     * Rate-limited per user+IP — 30 lookups per 5 minutes to prevent scraping.
     */
    @PostMapping("/resolve-account")
    public ResponseEntity<?> resolveBankAccount(Authentication authentication,
                                                @RequestBody Map<String, String> payload,
                                                HttpServletRequest httpRequest) {
        String bankCode      = payload.get("bankCode");
        String accountNumber = payload.get("accountNumber");

        log.error("\n\n🚨🚨🚨 ALARM: WALLET CONTROLLER HIT (POST)! 🚨🚨🚨\nBank: {}, Account: {}\n\n",
                bankCode, accountNumber);

        if (bankCode == null || accountNumber == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "bankCode and accountNumber are required."));
        }

        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.WALLET_RESOLVE_ACCOUNT, throttleKey);

        try {
            Long userId = currentUser(authentication.getName()).getId();
            String accountName = walletService.resolveBankAccount(userId, bankCode, accountNumber);
            // Count every lookup (success or failure) — prevents bulk account enumeration
            abuseProtectionService.recordRequest(AbuseProtectionService.WALLET_RESOLVE_ACCOUNT, throttleKey);
            return ResponseEntity.ok(Map.of("accountName", accountName));
        } catch (RuntimeException e) {
            abuseProtectionService.recordRequest(AbuseProtectionService.WALLET_RESOLVE_ACCOUNT, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST: OPay-style bank auto-detect. Given just an account number, probe name-enquiry across a
     * curated set of popular banks and return the ones that resolve — so the app can suggest the
     * destination bank before the user picks one. Falls back to manual selection when empty.
     */
    @PostMapping("/detect-banks")
    public ResponseEntity<?> detectBanks(Authentication authentication,
                                         @RequestBody Map<String, String> payload,
                                         HttpServletRequest httpRequest) {
        String accountNumber = payload.get("accountNumber");
        if (accountNumber == null || accountNumber.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "accountNumber is required."));
        }

        String throttleKey = abuseProtectionService.buildKey(authentication.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.WALLET_DETECT_BANKS, throttleKey);

        try {
            Long userId = currentUser(authentication.getName()).getId();
            List<Map<String, Object>> matches = walletService.detectBanksForAccount(userId, accountNumber);
            // One request per detect call (not per bank probed) — prevents enumeration abuse.
            abuseProtectionService.recordRequest(AbuseProtectionService.WALLET_DETECT_BANKS, throttleKey);
            return ResponseEntity.ok(Map.of("matches", matches));
        } catch (IllegalArgumentException e) {
            abuseProtectionService.recordRequest(AbuseProtectionService.WALLET_DETECT_BANKS, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (RuntimeException e) {
            abuseProtectionService.recordRequest(AbuseProtectionService.WALLET_DETECT_BANKS, throttleKey);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST: Save the verified Settlement Account details to the Wallet.
     * Rate-limited — prevents rapid bank account cycling.
     */
    @PostMapping("/bank-info")
    public ResponseEntity<?> updateWithdrawalBank(@Valid @RequestBody UpdateBankDetailsRequest request,
                                                  Principal principal,
                                                  HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(principal.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.WALLET_BANK_INFO, throttleKey);
        try {
            User user = currentUser(principal.getName());
            Wallet updatedWallet = walletService.updateSettlementAccount(user.getId(), request);
            abuseProtectionService.recordSuccess(AbuseProtectionService.WALLET_BANK_INFO, throttleKey);
            return ResponseEntity.ok(Map.of(
                    "message", "Bank details updated successfully",
                    "accountName", updatedWallet.getSettlementAccountName(),
                    "accountNumber", updatedWallet.getSettlementAccountNumber(),
                    "bankName", updatedWallet.getSettlementBankName()
            ));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.WALLET_BANK_INFO, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * POST: Initiate a Withdrawal to the Settlement Account.
     * Rate-limited — 10 withdrawal attempts per hour per user+IP.
     */
    /**
     * POST: Preview transfer fee before the user confirms.
     *
     * <p>Rubies wallets get the markup-tier fee (₦50/₦75/₦120, waived for premium).
     * Legacy wallets get the flat withdrawal fee.
     *
     * <p>Frontend must call this and display the result on the pre-confirmation screen
     * before the user hits "Confirm Transfer".
     */
    @PostMapping("/withdraw/quote")
    public ResponseEntity<?> quoteWithdrawal(@Valid @RequestBody WithdrawalQuoteRequest request,
                                             Principal principal) {
        User user = currentUser(principal.getName());
        WithdrawalQuoteResponse quote =
                walletService.quoteWithdrawal(request.getAmount(), user.getId(), request.isClosure());
        return ResponseEntity.ok(Map.of(
                "status", true,
                "message", quote.getMessage(),
                "data", quote
        ));
    }

    /**
     * GET: Fee preview — lightweight version the frontend can call while the user
     * is still typing the amount (no request body needed).
     *
     * <p>Example: {@code GET /wallets/transfer/fee-preview?amount=10000}
     */
    @GetMapping("/transfer/fee-preview")
    public ResponseEntity<?> transferFeePreview(@RequestParam java.math.BigDecimal amount,
                                                Principal principal) {
        User user = currentUser(principal.getName());
        WithdrawalQuoteResponse quote = walletService.quoteWithdrawal(amount, user.getId());
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("transferAmount",   quote.getWithdrawalAmount());
        data.put("bankCharge",       quote.getBankCharge());
        data.put("fee",              quote.getFee());
        data.put("stampDuty",        quote.getStampDuty());
        data.put("totalDebit",       quote.getTotalDebit());
        data.put("feePolicy",        quote.getFeePolicy());
        data.put("feeWaived",        quote.getFee().compareTo(java.math.BigDecimal.ZERO) == 0);
        data.put("displayText",      quote.getMessage());
        return ResponseEntity.ok(Map.of("status", true, "data", data));
    }

    @PostMapping("/withdraw")
    public ResponseEntity<?> withdrawFunds(@Valid @RequestBody WithdrawalRequest request,
                                           Principal principal,
                                           HttpServletRequest httpRequest) {
        String throttleKey = abuseProtectionService.buildKey(principal.getName(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
        try {
            User user = currentUser(principal.getName());
            Withdrawal withdrawal = walletService.processWithdrawal(user.getId(), request);
            abuseProtectionService.recordSuccess(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
            Map<String, Object> withdrawalData = new LinkedHashMap<>();
            BigDecimal bankCharge = withdrawal.getTotalDebit()
                    .subtract(withdrawal.getAmount())
                    .subtract(withdrawal.getFeeAmount());
            BigDecimal remainingBalance = walletService.getWalletByUserId(user.getId()).getBalance();

            withdrawalData.put("withdrawalId",     withdrawal.getId());
            withdrawalData.put("clientReference",  withdrawal.getClientReference());
            withdrawalData.put("reference",        withdrawal.getProviderReference());
            withdrawalData.put("amount",           withdrawal.getAmount());
            withdrawalData.put("fee",              withdrawal.getFeeAmount());
            withdrawalData.put("bankCharge",       bankCharge);
            withdrawalData.put("totalDebit",       withdrawal.getTotalDebit());
            withdrawalData.put("recipientReceives", withdrawal.getRecipientReceives());
            withdrawalData.put("remainingBalance", remainingBalance);
            withdrawalData.put("narration",        withdrawal.getNarration());
            withdrawalData.put("status",           withdrawal.getStatus().name());
            return ResponseEntity.ok(Map.of(
                    "status", true,
                    "message", "Transfer initiated. You will be notified once confirmed.",
                    "data", withdrawalData
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
            return ResponseEntity.badRequest().body(Map.of("status", false, "error", e.getMessage()));
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.WALLET_WITHDRAW, throttleKey);
            return ResponseEntity.internalServerError().body(Map.of("status", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/fee-reserve")
    public ResponseEntity<?> getFeeReserve(Authentication authentication) {
        User user = currentUser(authentication.getName());
        var reserve = feeReserveService.getReserve(user.getId());
        var estimates = feeReserveService.getActiveEstimates(user.getId());
        return ResponseEntity.ok(FeeReserveResponse.from(reserve, estimates));
    }
}
