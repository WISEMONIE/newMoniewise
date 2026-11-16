package com.moniewise.moniewise_backend.receipt;

import com.moniewise.moniewise_backend.entity.Envelope;
import com.moniewise.moniewise_backend.entity.TransactionLog;
import com.moniewise.moniewise_backend.entity.User;
import com.moniewise.moniewise_backend.enums.TransactionStatus;
import com.moniewise.moniewise_backend.enums.TransactionType;
import com.moniewise.moniewise_backend.exception.EntityNotFoundException;
import com.moniewise.moniewise_backend.repository.BudgetRepository;
import com.moniewise.moniewise_backend.repository.EnvelopeRepository;
import com.moniewise.moniewise_backend.repository.TransactionLogRepository;
import com.moniewise.moniewise_backend.repository.UserRepository;
import com.moniewise.moniewise_backend.service.MarkupCalculatorService;
import com.moniewise.moniewise_backend.service.TransactionPresentation;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReceiptServiceTest {

    @Mock private TransactionLogRepository transactionLogRepository;
    @Mock private UserRepository userRepository;
    @Mock private EnvelopeRepository envelopeRepository;
    @Mock private BudgetRepository budgetRepository;
    @Mock private MarkupCalculatorService markupCalculatorService;

    private ReceiptService service;

    @BeforeEach
    void setUp() {
        service = new ReceiptService(
                transactionLogRepository,
                new TransactionPresentation(
                        userRepository, envelopeRepository, budgetRepository, markupCalculatorService));
    }

    @Test
    void accountNumbersAreMaskedAndShortIdsAreLeftOff() {
        assertEquals("0123....6789", ReceiptService.maskAccount("0123456789"));
        assertEquals("0123....6789", ReceiptService.maskAccount("0123 456789"));
        assertNull(ReceiptService.maskAccount("12345678"));
        assertNull(ReceiptService.maskAccount("99"));
        assertNull(ReceiptService.maskAccount(null));
        assertNull(ReceiptService.maskAccount("   "));
    }

    @Test
    void timeIsShownInLagosFromUtc() {
        LocalDateTime stored = LocalDateTime.of(2026, 3, 4, 14, 5);
        assertEquals("4 Mar 2026, 3:05 PM", ReceiptService.formatWhen(stored));
        assertEquals("\u2014", ReceiptService.formatWhen(null));
    }

    @Test
    void formatIsPdfOrPng() {
        assertFalse(ReceiptService.pngFormat(null));
        assertFalse(ReceiptService.pngFormat(" pdf "));
        assertTrue(ReceiptService.pngFormat("PNG"));
        IllegalArgumentException ex = assertThrows(
                IllegalArgumentException.class, () -> ReceiptService.pngFormat("jpg"));
        assertEquals("Receipt format must be pdf or png.", ex.getMessage());
    }

    @Test
    void filenameDropsPathCharacters() {
        assertEquals("wisemonie-receipt-TRF-ADA-1001.pdf", ReceiptService.filename("TRF-ADA-1001", false));
        assertEquals("wisemonie-receipt-transfer.png", ReceiptService.filename(null, true));
        String sneaky = ReceiptService.filename("../secret", true);
        assertFalse(sneaky.contains(".."));
        assertFalse(sneaky.contains("/"));
        assertTrue(sneaky.endsWith(".png"));
    }

    @Test
    void aMissingTransferIsNotFound() {
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.empty());

        EntityNotFoundException ex = assertThrows(
                EntityNotFoundException.class, () -> service.render(44L, 7L, false));

        assertEquals("Transaction not found", ex.getMessage());
        verifyNoInteractions(userRepository, envelopeRepository);
    }

    @Test
    void anotherUsersTransferIsNotFound() {
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(transfer()));

        EntityNotFoundException ex = assertThrows(
                EntityNotFoundException.class, () -> service.render(44L, 8L, false));

        assertEquals("Transaction not found", ex.getMessage());
        verifyNoInteractions(userRepository, envelopeRepository);
    }

    @Test
    void anOwnedTransferMatchesTheScreenAndMasksTheAccount() throws Exception {
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(transfer()));
        salary();

        ReceiptFile file = service.render(44L, 7L, false);
        String text = text(file.bytes());

        assertTrue(file.filename().equals("wisemonie-receipt-TRF-ADA-1001.pdf"));
        assertTrue(new String(file.bytes(), 0, 5).startsWith("%PDF"));
        assertTrue(text.contains("Transfer receipt"), text);
        assertTrue(text.indexOf("Salary") < text.indexOf("Chinedu Adeyemi"), text);
        assertTrue(text.contains("Opay"), text);
        assertTrue(text.contains("0123....6789"), text);
        assertFalse(text.contains("0123456789"), text);
        assertTrue(text.contains("26.00"), text);
        assertTrue(text.contains("7.50"), text);
        assertTrue(text.contains("Successful"), text);
        assertTrue(text.contains("4 Mar 2026, 3:05 PM"), text);
        verifyNoInteractions(userRepository);
    }

    @Test
    void aRubiesBankTransferAddsTheNipChargeTheScreenShows() throws Exception {
        TransactionLog log = transfer();
        log.setProviderName("RUBIES");
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(log));
        when(markupCalculatorService.calculateNipFee(any())).thenReturn(new BigDecimal("10.75"));
        salary();

        String text = text(service.render(44L, 7L, false).bytes());

        assertTrue(text.contains("36.75"), text);
        assertFalse(text.contains("26.00"), text);
        assertTrue(text.contains("7.50"), text);
    }

    @Test
    void pngIsTheSamePage() {
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(transfer()));
        salary();

        ReceiptFile file = service.render(44L, 7L, true);

        assertTrue(file.filename().endsWith(".png"));
        assertEquals((byte) 0x89, file.bytes()[0]);
        assertEquals((byte) 0x50, file.bytes()[1]);
        assertEquals((byte) 0x4E, file.bytes()[2]);
        assertEquals((byte) 0x47, file.bytes()[3]);
    }

    @Test
    void aReversedTransferSwapsThePartiesAndOmitsTheFee() throws Exception {
        TransactionLog log = transfer();
        log.setStatus(TransactionStatus.REVERSED);
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(log));
        salary();

        String text = text(service.render(44L, 7L, false).bytes());

        assertTrue(text.contains("Failed"), text);
        assertTrue(text.indexOf("Chinedu Adeyemi") < text.indexOf("Salary"), text);
        assertFalse(text.contains("26.00"), text);
        assertFalse(text.contains("7.50"), text);
    }

    @Test
    void moneyReceivedFromAnotherUserNamesTheSender() throws Exception {
        TransactionLog log = transfer();
        log.setTransactionType(TransactionType.USER_TO_ENVELOPE);
        log.setSourceEnvelopeId(null);
        log.setExternalAccountName(null);
        log.setExternalAccountNumber(null);
        log.setExternalBankName(null);
        log.setCounterpartyUserId(9L);
        log.setTargetEnvelopeId(4L);
        User friend = new User();
        friend.setProfileData(Map.of("name", "Chinedu Adeyemi"));
        Envelope rent = new Envelope();
        rent.setName("Rent");
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(log));
        when(userRepository.findById(9L)).thenReturn(Optional.of(friend));
        when(envelopeRepository.findById(4L)).thenReturn(Optional.of(rent));

        String text = text(service.render(44L, 7L, false).bytes());

        assertTrue(text.indexOf("Chinedu Adeyemi") < text.indexOf("Rent"), text);
        assertFalse(text.contains("Ada Okonkwo"), text);
    }

    @Test
    void aCounterpartyWithoutAProfileNameIsNotTheirEmail() throws Exception {
        TransactionLog log = transfer();
        log.setTransactionType(TransactionType.ENVELOPE_TO_USER);
        log.setExternalAccountName(null);
        log.setExternalAccountNumber(null);
        log.setExternalBankName(null);
        log.setCounterpartyUserId(9L);
        User friend = new User();
        friend.setEmail("tunde@example.com");
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(log));
        when(userRepository.findById(9L)).thenReturn(Optional.of(friend));
        salary();

        String text = text(service.render(44L, 7L, false).bytes());

        assertTrue(text.contains("Wisemonie User"), text);
        assertFalse(text.contains("tunde@example.com"), text);
        assertFalse(text.contains("tunde"), text);
    }

    @Test
    void anEnvelopeMoveNamesBothEnvelopes() throws Exception {
        TransactionLog log = transfer();
        log.setTransactionType(TransactionType.ENVELOPE_TO_ENVELOPE);
        log.setExternalAccountName(null);
        log.setExternalAccountNumber(null);
        log.setExternalBankName(null);
        log.setTargetEnvelopeId(4L);
        Envelope rent = new Envelope();
        rent.setName("Rent");
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(log));
        when(envelopeRepository.findById(4L)).thenReturn(Optional.of(rent));
        salary();

        String text = text(service.render(44L, 7L, false).bytes());

        assertTrue(text.indexOf("Salary") < text.indexOf("Rent"), text);
        verify(userRepository, never()).findById(9L);
    }

    @Test
    void aLegacyAccountNumberIsMaskedAndAWalletIdIsNotPrinted() throws Exception {
        TransactionLog legacy = transfer();
        legacy.setExternalAccountNumber(null);
        legacy.setExternalAccountId("0123456789");
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(legacy));
        salary();
        String legacyText = text(service.render(44L, 7L, false).bytes());
        assertTrue(legacyText.contains("0123....6789"), legacyText);
        assertFalse(legacyText.contains("0123456789"), legacyText);

        TransactionLog walletId = transfer();
        walletId.setExternalAccountNumber(null);
        walletId.setExternalAccountId("99");
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(walletId));
        String hidden = text(service.render(44L, 7L, false).bytes());
        assertFalse(hidden.contains("99"), hidden);
    }

    @Test
    void fundingAndAirtimeAreNotLabeledAsTransfers() throws Exception {
        TransactionLog funding = transfer();
        funding.setTransactionType(TransactionType.WALLET_DEPOSIT);
        funding.setSourceEnvelopeId(null);
        funding.setExternalAccountName(null);
        funding.setExternalAccountNumber(null);
        funding.setExternalBankName(null);
        funding.setFee(BigDecimal.ZERO);
        funding.setStampDuty(null);
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(funding));
        String funded = text(service.render(44L, 7L, false).bytes());
        assertTrue(funded.contains("Funding receipt"), funded);
        assertTrue(funded.contains("External Transfer / Card"), funded);
        assertTrue(funded.contains("Main Wallet"), funded);
        assertFalse(funded.contains("Transfer receipt"), funded);

        TransactionLog airtime = transfer();
        airtime.setTransactionType(TransactionType.VAS_PURCHASE);
        airtime.setExternalAccountName(null);
        airtime.setExternalAccountNumber(null);
        airtime.setExternalBankName(null);
        airtime.setFee(BigDecimal.ZERO);
        airtime.setStampDuty(null);
        airtime.setDescription("MTN airtime - NGN 100.00 to 08012345678");
        when(transactionLogRepository.findById(44L)).thenReturn(Optional.of(airtime));
        salary();
        String purchase = text(service.render(44L, 7L, false).bytes());
        assertTrue(purchase.contains("Purchase receipt"), purchase);
        assertTrue(purchase.contains("MTN 0801....5678"), purchase);
        assertFalse(purchase.contains("08012345678"), purchase);
    }

    private static TransactionLog transfer() {
        TransactionLog log = new TransactionLog();
        log.setId(44L);
        log.setUserId(7L);
        log.setAmount(new BigDecimal("15000.50"));
        log.setFee(new BigDecimal("26.00"));
        log.setStampDuty(new BigDecimal("7.50"));
        log.setStatus(TransactionStatus.SUCCESS);
        log.setTransactionType(TransactionType.ENVELOPE_TO_EXTERNAL);
        log.setSourceEnvelopeId(3L);
        log.setProviderName("SECUREWAVE");
        log.setCreatedAt(LocalDateTime.of(2026, 3, 4, 14, 5));
        log.setReference("TRF-ADA-1001");
        log.setExternalBankName("Opay");
        log.setExternalAccountNumber("0123456789");
        log.setExternalAccountName("Chinedu Adeyemi");
        return log;
    }

    private void salary() {
        Envelope envelope = new Envelope();
        envelope.setName("Salary");
        when(envelopeRepository.findById(3L)).thenReturn(Optional.of(envelope));
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
