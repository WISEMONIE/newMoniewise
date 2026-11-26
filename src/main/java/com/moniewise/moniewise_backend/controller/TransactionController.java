package com.moniewise.moniewise_backend.controller;

import com.moniewise.moniewise_backend.dto.TransactionDecisionResponseDto;
import com.moniewise.moniewise_backend.dto.WithdrawalInitiationResponseDto;
import com.moniewise.moniewise_backend.dto.request.WithdrawalRequest;
import com.moniewise.moniewise_backend.dto.response.TransactionDetailResponse;
import com.moniewise.moniewise_backend.dto.response.TransactionListResponse;
import com.moniewise.moniewise_backend.receipt.ReceiptFile;
import com.moniewise.moniewise_backend.receipt.ReceiptService;
import com.moniewise.moniewise_backend.service.AbuseProtectionService;
import com.moniewise.moniewise_backend.service.TransactionService;
import com.moniewise.moniewise_backend.service.UserService;
import com.moniewise.moniewise_backend.service.WalletService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Map;

@RestController
@RequestMapping("/transactions")
public class TransactionController {

    @Autowired private TransactionService transactionService;
    @Autowired private UserService userService;
    @Autowired private WalletService walletService;
    @Autowired private AbuseProtectionService abuseProtectionService;
    @Autowired private ReceiptService receiptService;

    @GetMapping
    public ResponseEntity<Page<TransactionListResponse>> getUserTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            @RequestParam(required = false) Long envelopeId,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = getUserId(userDetails);
        if (envelopeId != null) {
            return ResponseEntity.ok(transactionService.getTransactionsForEnvelope(userId, envelopeId, page, size));
        }
        return ResponseEntity.ok(transactionService.getTransactionsForUser(userId, page, size));
    }

    @GetMapping("/month-totals")
    public ResponseEntity<Map<String, BigDecimal>> getMonthTotals(
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = getUserId(userDetails);
        YearMonth targetMonth = (year != null && month != null)
                ? YearMonth.of(year, month)
                : YearMonth.now();

        return ResponseEntity.ok(transactionService.getMonthTotalsForUser(userId, targetMonth));
    }

    @GetMapping("/provider")
    public ResponseEntity<Map<String, Object>> getProviderTransactions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = getUserId(userDetails);
        return ResponseEntity.ok(walletService.getProviderTransactions(userId, page, perPage));
    }

    @GetMapping("/provider/{transactionReference}")
    public ResponseEntity<Map<String, Object>> getProviderTransactionDetails(
            @PathVariable String transactionReference,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = getUserId(userDetails);
        return ResponseEntity.ok(walletService.getProviderTransactionDetails(userId, transactionReference));
    }

    @GetMapping("/{trans_id}")
    public TransactionDetailResponse getTransactionDetail(
            @PathVariable Long trans_id,
            @AuthenticationPrincipal UserDetails userDetails) {

        Long userId = getUserId(userDetails);
        return transactionService.getTransactionDetail(trans_id, userId);
    }

    /**
     * The PDF is the slip. PNG is that same page. A missing transfer and a
     * transfer owned by someone else are both not found.
     */
    @GetMapping("/{trans_id}/receipt")
    public ResponseEntity<byte[]> downloadReceipt(
            @PathVariable("trans_id") Long transId,
            @RequestParam(name = "format", defaultValue = "pdf") String format,
            @AuthenticationPrincipal UserDetails userDetails) {
        Long userId = getUserId(userDetails);
        ReceiptFile file = receiptService.render(transId, userId, ReceiptService.pngFormat(format));
        MediaType mediaType = file.filename().endsWith(".png")
                ? MediaType.IMAGE_PNG
                : MediaType.APPLICATION_PDF;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(file.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(file.bytes());
    }

    /**
     * POST /transactions/withdraw
     * Rate-limited — 10 withdrawal attempts per hour per user+IP.
     */
    @PostMapping("/withdraw")
    public ResponseEntity<WithdrawalInitiationResponseDto> initiateWithdrawal(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestBody WithdrawalRequest request,
            HttpServletRequest httpRequest) {

        String throttleKey = abuseProtectionService.buildKey(userDetails.getUsername(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.TXN_WITHDRAW, throttleKey);
        try {
            Long userId = getUserId(userDetails);
            WithdrawalInitiationResponseDto response = transactionService.initiateWithdrawal(userId, request);
            abuseProtectionService.recordSuccess(AbuseProtectionService.TXN_WITHDRAW, throttleKey);
            return ResponseEntity.ok(response);
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.TXN_WITHDRAW, throttleKey);
            throw e;
        }
    }

    /**
     * POST /transactions/transfer
     * Rate-limited — 20 transfers per hour per user+IP.
     */
    @PostMapping("/transfer")
    public ResponseEntity<Void> initiateTransfer(
            @AuthenticationPrincipal UserDetails userDetails,
            @RequestParam BigDecimal amount,
            @RequestParam Long sourceEnvelopeId,
            @RequestParam String destinationReference,
            HttpServletRequest httpRequest) {

        String throttleKey = abuseProtectionService.buildKey(userDetails.getUsername(), httpRequest.getRemoteAddr());
        abuseProtectionService.checkAllowed(AbuseProtectionService.TXN_TRANSFER, throttleKey);
        try {
            Long userId = getUserId(userDetails);
            transactionService.initiateTransfer(userId, amount, sourceEnvelopeId, destinationReference);
            abuseProtectionService.recordSuccess(AbuseProtectionService.TXN_TRANSFER, throttleKey);
            return ResponseEntity.ok().build();
        } catch (RuntimeException e) {
            abuseProtectionService.recordFailure(AbuseProtectionService.TXN_TRANSFER, throttleKey);
            throw e;
        }
    }

    @GetMapping("/decision/{transactionRequestId}")
    public ResponseEntity<TransactionDecisionResponseDto> getDecision(
            @PathVariable Long transactionRequestId,
            @AuthenticationPrincipal UserDetails userDetails) {
        // Scope the lookup to the caller — transactionRequestId is a
        // sequential id, so without the owner check any authenticated user
        // could enumerate and read other users' transaction risk decisions.
        Long userId = getUserId(userDetails);
        return ResponseEntity.ok(transactionService.getDecision(transactionRequestId, userId));
    }

    private Long getUserId(UserDetails userDetails) {
        return userService.getRequiredUserIdByEmail(userDetails.getUsername());
    }
}
