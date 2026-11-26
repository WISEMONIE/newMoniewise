package com.moniewise.moniewise_backend.receipt;

import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.service.TransactionPresentation;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds a receipt from the saved transfer. The file is not stored.
 * A missing transfer and someone else's transfer are both not found.
 */
@Service
public class ReceiptService {

    private static final ZoneOffset STORED_AS_UTC = ZoneOffset.UTC;
    private static final java.time.ZoneId LAGOS = java.time.ZoneId.of("Africa/Lagos");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH);
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{10,14}");

    private final TransactionLogRepository transactionLogRepository;
    private final TransactionPresentation presentation;

    public ReceiptService(
            TransactionLogRepository transactionLogRepository,
            TransactionPresentation presentation) {
        this.transactionLogRepository = transactionLogRepository;
        this.presentation = presentation;
    }

    public ReceiptFile render(Long transactionId, Long userId, boolean png) {
        TransactionLog log = transactionLogRepository.findById(transactionId)
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found"));
        if (log.getUserId() == null || userId == null || !log.getUserId().equals(userId)) {
            throw new EntityNotFoundException("Transaction not found");
        }

        try {
            ReceiptDocument.Slip slip = slip(log);
            byte[] pdf = ReceiptDocument.pdf(slip);
            byte[] bytes = png ? ReceiptDocument.png(pdf) : pdf;
            return new ReceiptFile(bytes, filename(slip.reference(), png));
        } catch (RuntimeException ex) {
            throw new ReceiptRenderException(ex);
        }
    }

    public static boolean pngFormat(String format) {
        if (format == null || format.isBlank()) {
            return false;
        }
        String value = format.trim();
        if (value.equalsIgnoreCase("pdf")) {
            return false;
        }
        if (value.equalsIgnoreCase("png")) {
            return true;
        }
        throw new IllegalArgumentException("Receipt format must be pdf or png.");
    }

    /**
     * A Nigerian account number is 10 digits. Anything shorter is not printed:
     * the old account column also held wallet ids.
     */
    static String maskAccount(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() != 10) {
            return null;
        }
        return digits.substring(0, 4) + "...." + digits.substring(digits.length() - 4);
    }

    /**
     * created_at is a timestamptz. This server reads it as UTC, which is the
     * zone of the Render image, and the slip shows Lagos.
     */
    static String formatWhen(LocalDateTime createdAt) {
        if (createdAt == null) {
            return "\u2014";
        }
        return WHEN.format(createdAt.atZone(STORED_AS_UTC).withZoneSameInstant(LAGOS));
    }

    static String filename(String reference, boolean png) {
        String token = reference == null ? "" : reference.trim();
        token = token.replaceAll("[^A-Za-z0-9_-]", "-");
        token = token.replaceAll("-{2,}", "-");
        token = token.replaceAll("^-|-$", "");
        if (token.isEmpty()) {
            token = "transfer";
        }
        if (token.length() > 48) {
            token = token.substring(0, 48).replaceAll("-$", "");
        }
        return "wisemonie-receipt-" + token + (png ? ".png" : ".pdf");
    }

    private ReceiptDocument.Slip slip(TransactionLog log) {
        TransactionPresentation.Display display = presentation.describe(
                log, TransactionPresentation.Names.RECEIPT);
        Status status = statusOf(log.getStatus());
        String account = maskAccount(display.accountNumber());
        String recipient = maskSharedText(display.recipient(), "Wisemonie");
        if (account != null && account.equals(recipient)) {
            recipient = "External Account";
        }
        BigDecimal fee = display.fee() != null && display.fee().compareTo(BigDecimal.ZERO) > 0
                ? display.fee() : null;
        BigDecimal stampDuty = display.stampDuty() != null && display.stampDuty().compareTo(BigDecimal.ZERO) > 0
                ? display.stampDuty() : null;
        return new ReceiptDocument.Slip(
                maskSharedText(display.sender(), "Wisemonie"),
                recipient,
                clean(display.bankName()),
                account,
                display.amount(),
                fee,
                stampDuty,
                status.label(),
                status.tone(),
                formatWhen(log.getCreatedAt()),
                display.reference(),
                display.heading());
    }

    /**
     * Hides an email and masks a phone or account number inside a name.
     * A short all-digit value is a wallet id, so it is left off the slip.
     */
    static String maskSharedText(String value, String fallback) {
        String trimmed = clean(value);
        if (trimmed == null || trimmed.contains("@")) {
            return fallback;
        }
        String digits = trimmed.replaceAll("\\D", "");
        boolean onlyDigits = trimmed.chars().allMatch(ch -> Character.isDigit(ch) || ch == ' ');
        if (onlyDigits && digits.length() < 10) {
            return fallback;
        }
        Matcher matcher = LONG_NUMBER.matcher(trimmed);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String found = matcher.group();
            String masked = found.substring(0, 4) + "...." + found.substring(found.length() - 4);
            matcher.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(out);
        String result = out.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private static Status statusOf(TransactionStatus status) {
        if (status == null) {
            return new Status("Pending", ReceiptDocument.Tone.PENDING);
        }
        return switch (status) {
            case SUCCESS, COMPLETED -> new Status("Successful", ReceiptDocument.Tone.SUCCESS);
            case FAILED, REVERSED -> new Status("Failed", ReceiptDocument.Tone.FAILED);
            case PENDING, PROCESSING -> new Status("Pending", ReceiptDocument.Tone.PENDING);
        };
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim().replaceAll("\\s+", " ");
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Status(String label, ReceiptDocument.Tone tone) {
    }
}
