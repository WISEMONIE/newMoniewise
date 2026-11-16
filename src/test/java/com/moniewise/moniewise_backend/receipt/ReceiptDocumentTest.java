package com.moniewise.moniewise_backend.receipt;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptDocumentTest {

    @Test
    void pdfAndPngAreTheSameSlip() throws Exception {
        ReceiptDocument.Slip slip = new ReceiptDocument.Slip(
                "Ada Okonkwo",
                "Chinedu Adeyemi",
                "Opay",
                "0123....6789",
                new BigDecimal("15000.50"),
                new BigDecimal("26.00"),
                new BigDecimal("7.50"),
                "Successful",
                ReceiptDocument.Tone.SUCCESS,
                "4 Mar 2026, 3:05 PM",
                "TRF-ADA-1001",
                "Transfer receipt");

        byte[] pdf = ReceiptDocument.pdf(slip);
        assertTrue(new String(pdf, 0, 5, StandardCharsets.US_ASCII).startsWith("%PDF"));

        String text = text(pdf);
        assertTrue(text.contains("Wisemonie"), text);
        assertTrue(text.contains("Transfer receipt"), text);
        assertTrue(text.contains("15,000.50"), text);
        assertTrue(text.contains("Successful"), text);
        assertTrue(text.contains("4 Mar 2026, 3:05 PM"), text);
        assertTrue(text.contains("Ada Okonkwo"), text);
        assertTrue(text.contains("Chinedu Adeyemi"), text);
        assertTrue(text.contains("Opay"), text);
        assertTrue(text.contains("0123....6789"), text);
        assertTrue(text.contains("26.00"), text);
        assertTrue(text.contains("7.50"), text);
        assertTrue(text.contains("TRF-ADA-1001"), text);
        assertTrue(text.contains("Secured by Wisemonie"), text);
        assertFalse(text.contains("0123456789"), text);

        byte[] png = ReceiptDocument.png(pdf);
        assertEquals((byte) 0x89, png[0]);
        assertEquals((byte) 0x50, png[1]);
        assertEquals((byte) 0x4E, png[2]);
        assertEquals((byte) 0x47, png[3]);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertNotNull(image);
        assertEquals(960, image.getWidth());
        assertEquals(0xFFFFFF, image.getRGB(2, 2) & 0xFFFFFF);
    }

    @Test
    void zeroFeeAndStampDutyAreLeftOffTheSlip() throws Exception {
        ReceiptDocument.Slip slip = new ReceiptDocument.Slip(
                "Ada Okonkwo",
                "Rent",
                null,
                null,
                new BigDecimal("500"),
                BigDecimal.ZERO,
                null,
                "Failed",
                ReceiptDocument.Tone.FAILED,
                "4 Mar 2026, 3:05 PM",
                null,
                "Transfer receipt");

        String text = text(ReceiptDocument.pdf(slip));
        assertTrue(text.contains("Failed"), text);
        assertTrue(text.contains("500.00"), text);
        assertFalse(text.contains("Fee"), text);
        assertFalse(text.contains("Stamp duty"), text);
        assertTrue(text.contains("\u2014") || text.contains("-"), text);
    }

    private static String text(byte[] pdf) throws Exception {
        try (PDDocument document = PDDocument.load(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }
}
