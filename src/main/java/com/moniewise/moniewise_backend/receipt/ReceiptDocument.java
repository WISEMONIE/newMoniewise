package com.moniewise.moniewise_backend.receipt;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One narrow slip. The PDF is the document. The PNG is that same page,
 * rendered at 3x, so the two files cannot drift.
 */
public final class ReceiptDocument {

    public enum Tone { SUCCESS, PENDING, FAILED }

    public record Slip(
            String sender,
            String recipient,
            String bank,
            String account,
            BigDecimal amount,
            BigDecimal fee,
            BigDecimal stampDuty,
            String statusLabel,
            Tone tone,
            String whenText,
            String reference,
            String heading) {
    }

    private static final float PAGE_WIDTH = 320f;
    private static final float MARGIN_X = 28f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - (MARGIN_X * 2f);
    private static final float VALUE_WIDTH = CONTENT_WIDTH - 112f;
    private static final float PNG_SCALE = 3f;

    private static final Color INK = new Color(0x11, 0x18, 0x27);
    private static final Color LABEL = new Color(0x9C, 0xA3, 0xAF);
    private static final Color DATE = new Color(0x6B, 0x72, 0x80);
    private static final Color RULE = new Color(0xE5, 0xE7, 0xEB);
    private static final Color SUCCESS_INK = new Color(0x04, 0x78, 0x57);
    private static final Color SUCCESS_FILL = new Color(0xEC, 0xFD, 0xF5);
    private static final Color PENDING_INK = new Color(0xB4, 0x53, 0x09);
    private static final Color PENDING_FILL = new Color(0xFF, 0xFB, 0xEB);
    private static final Color FAILED_INK = new Color(0xB9, 0x1C, 0x1C);
    private static final Color FAILED_FILL = new Color(0xFE, 0xF2, 0xF2);

    private static volatile Fonts fonts;

    private ReceiptDocument() {
    }

    public static byte[] pdf(Slip slip) {
        try {
            return draw(slip);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Could not draw the receipt.", ex);
        }
    }

    /** The image is the PDF page. There is no second layout. */
    public static byte[] png(byte[] pdf) {
        System.setProperty("java.awt.headless", "true");
        try (PDDocument document = PDDocument.load(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            var image = renderer.renderImage(0, PNG_SCALE, ImageType.RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "PNG", out)) {
                throw new IllegalStateException("Could not write the receipt image.");
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not write the receipt image.", ex);
        }
    }

    private static byte[] draw(Slip slip) throws Exception {
        Fonts loaded = fonts();
        List<Line> rows = lines(slip);
        boolean knownAmount = slip.amount() != null;
        String digits = knownAmount ? moneyDigits(slip.amount()) : "\u2014";
        float amountSize = knownAmount
                ? fitAmount(loaded.black, loaded.naira, digits)
                : 26f;
        float height = 214f + amountSize + (rows.size() * 22f);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(new Rectangle(PAGE_WIDTH, height), 0, 0, 0, 0);
        PdfWriter writer = PdfWriter.getInstance(document, out);
        document.open();
        try {
            document.addTitle(heading(slip));
            document.addAuthor("Wisemonie");
            document.addCreator("Wisemonie");
            paint(writer.getDirectContent(), loaded, slip, rows, digits, amountSize, height);
        } finally {
            document.close();
        }
        return out.toByteArray();
    }

    private static void paint(
            PdfContentByte canvas,
            Fonts loaded,
            Slip slip,
            List<Line> rows,
            String digits,
            float amountSize,
            float pageHeight) throws Exception {
        canvas.saveState();
        canvas.setRGBColorFill(255, 255, 255);
        canvas.rectangle(0, 0, PAGE_WIDTH, pageHeight);
        canvas.fill();
        canvas.restoreState();

        float y = pageHeight - 28f;

        com.lowagie.text.Image logo = com.lowagie.text.Image.getInstance(loaded.logo);
        float logoHeight = 24f;
        float logoWidth = logoHeight * logo.getWidth() / Math.max(1f, logo.getHeight());
        logo.scaleAbsolute(logoWidth, logoHeight);
        logo.setAbsolutePosition(MARGIN_X, y - logoHeight);
        canvas.addImage(logo);

        float textX = MARGIN_X + logoWidth + 8f;
        text(canvas, loaded.bold, 14f, INK, "Wisemonie", textX, y - 12f, Element.ALIGN_LEFT, 0f);
        text(canvas, loaded.regular, 9f, DATE, heading(slip), textX, y - 25f, Element.ALIGN_LEFT, 0f);
        y -= logoHeight + 22f;

        float amountBaseline = y - (amountSize * 0.78f);
        if (slip.amount() != null) {
            Naira mark = Naira.of(loaded.naira, loaded.black, amountSize);
            mark.paint(canvas, MARGIN_X, amountBaseline, INK);
            text(canvas, loaded.black, amountSize, INK, digits,
                    MARGIN_X + mark.advance(), amountBaseline, Element.ALIGN_LEFT, -0.4f);
        } else {
            text(canvas, loaded.black, amountSize, INK, digits,
                    MARGIN_X, amountBaseline, Element.ALIGN_LEFT, 0f);
        }
        y -= amountSize + 12f;

        String status = blank(slip.statusLabel()) ? "Pending" : slip.statusLabel().trim();
        Tone tone = slip.tone() == null ? Tone.PENDING : slip.tone();
        Color fill = switch (tone) {
            case SUCCESS -> SUCCESS_FILL;
            case FAILED -> FAILED_FILL;
            case PENDING -> PENDING_FILL;
        };
        Color pillInk = switch (tone) {
            case SUCCESS -> SUCCESS_INK;
            case FAILED -> FAILED_INK;
            case PENDING -> PENDING_INK;
        };
        float pillFont = 8.5f;
        float pillH = 18f;
        float pillW = loaded.medium.getWidthPoint(status, pillFont) + 18f;
        float pillBottom = y - pillH;
        canvas.saveState();
        canvas.setRGBColorFill(fill.getRed(), fill.getGreen(), fill.getBlue());
        canvas.roundRectangle(MARGIN_X, pillBottom, pillW, pillH, pillH / 2f);
        canvas.fill();
        canvas.restoreState();
        float ascent = loaded.medium.getAscentPoint(status, pillFont);
        float descent = loaded.medium.getDescentPoint(status, pillFont);
        float pillBaseline = pillBottom + (pillH / 2f) - ((ascent + descent) / 2f);
        text(canvas, loaded.medium, pillFont, pillInk, status, MARGIN_X + 9f, pillBaseline, Element.ALIGN_LEFT, 0.15f);
        y -= pillH + 8f;

        String when = blank(slip.whenText()) ? "\u2014" : slip.whenText();
        text(canvas, loaded.regular, 9f, DATE, when, MARGIN_X, y - 9f, Element.ALIGN_LEFT, 0f);
        y -= 26f;

        rule(canvas, y);
        y -= 16f;

        float right = PAGE_WIDTH - MARGIN_X;
        for (Line row : rows) {
            float valueSize = fitValue(loaded.semibold, loaded.naira, row.value(), row.money());
            float baseline = y - 13f;
            text(canvas, loaded.regular, 8f, LABEL, row.label(), MARGIN_X, baseline, Element.ALIGN_LEFT, 0.55f);
            if (row.money()) {
                Naira small = Naira.of(loaded.naira, loaded.semibold, valueSize);
                float textWidth = loaded.semibold.getWidthPoint(row.value(), valueSize);
                float start = right - textWidth - small.advance();
                small.paint(canvas, start, baseline, INK);
                text(canvas, loaded.semibold, valueSize, INK, row.value(),
                        start + small.advance(), baseline, Element.ALIGN_LEFT, 0f);
            } else {
                text(canvas, loaded.semibold, valueSize, INK, row.value(), right, baseline, Element.ALIGN_RIGHT, 0f);
            }
            y -= 22f;
        }

        y -= 4f;
        rule(canvas, y);
        y -= 20f;
        text(canvas, loaded.regular, 8f, LABEL, "Secured by Wisemonie",
                PAGE_WIDTH / 2f, y - 2f, Element.ALIGN_CENTER, 0.2f);
    }

    private static List<Line> lines(Slip slip) {
        List<Line> rows = new ArrayList<>();
        addText(rows, "Sender", slip.sender());
        addText(rows, "Recipient", slip.recipient());
        addText(rows, "Bank", slip.bank());
        addText(rows, "Account", slip.account());
        addMoney(rows, "Fee", slip.fee());
        addMoney(rows, "Stamp duty", slip.stampDuty());
        String reference = clip(slip.reference());
        rows.add(new Line("Reference", reference == null ? "\u2014" : reference, false));
        return rows;
    }

    private static void addText(List<Line> rows, String label, String value) {
        String shown = clip(value);
        if (shown != null) {
            rows.add(new Line(label, shown, false));
        }
    }

    private static void addMoney(List<Line> rows, String label, BigDecimal amount) {
        if (amount != null && amount.compareTo(BigDecimal.ZERO) > 0) {
            rows.add(new Line(label, moneyDigits(amount), true));
        }
    }

    private static String moneyDigits(BigDecimal amount) {
        DecimalFormat format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
        BigDecimal value = amount == null ? BigDecimal.ZERO : amount.abs();
        return format.format(value);
    }

    private static String clip(String value) {
        if (value == null) {
            return null;
        }
        String line = value.replace('\r', ' ').replace('\n', ' ').trim().replaceAll("\\s+", " ");
        if (line.isEmpty()) {
            return null;
        }
        if (line.length() <= 64) {
            return line;
        }
        return line.substring(0, 63) + "\u2026";
    }

    private static String heading(Slip slip) {
        String line = slip.heading() == null ? "" : slip.heading().trim();
        return line.isEmpty() ? "Transaction receipt" : line;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static float fitAmount(BaseFont digits, BaseFont naira, String text) {
        float size = 26f;
        while (size > 16f) {
            float used = Naira.advance(naira, digits, size) + digits.getWidthPoint(text, size);
            if (used <= CONTENT_WIDTH) {
                return size;
            }
            size -= 0.5f;
        }
        return 16f;
    }

    private static float fitValue(BaseFont digits, BaseFont naira, String value, boolean money) {
        float size = 10f;
        while (size > 7.5f) {
            float extra = money ? Naira.advance(naira, digits, size) : 0f;
            if (extra + digits.getWidthPoint(value, size) <= VALUE_WIDTH) {
                return size;
            }
            size -= 0.25f;
        }
        return 7.5f;
    }

    private static void rule(PdfContentByte canvas, float y) {
        canvas.saveState();
        canvas.setLineWidth(0.7f);
        canvas.setLineDash(1.15f, 2.4f, 0f);
        canvas.setRGBColorStroke(RULE.getRed(), RULE.getGreen(), RULE.getBlue());
        canvas.moveTo(MARGIN_X, y);
        canvas.lineTo(PAGE_WIDTH - MARGIN_X, y);
        canvas.stroke();
        canvas.restoreState();
    }

    private static void text(
            PdfContentByte canvas,
            BaseFont font,
            float size,
            Color color,
            String value,
            float x,
            float y,
            int align,
            float tracking) {
        if (value == null || value.isEmpty()) {
            return;
        }
        float width = font.getWidthPoint(value, size) + (tracking * Math.max(0, value.length() - 1));
        float start = x;
        if (align == Element.ALIGN_RIGHT) {
            start = x - width;
        } else if (align == Element.ALIGN_CENTER) {
            start = x - (width / 2f);
        }
        canvas.saveState();
        canvas.setRGBColorFill(color.getRed(), color.getGreen(), color.getBlue());
        canvas.beginText();
        canvas.setFontAndSize(font, size);
        canvas.setCharacterSpacing(tracking);
        canvas.setTextMatrix(start, y);
        canvas.showText(value);
        canvas.setCharacterSpacing(0);
        canvas.endText();
        canvas.restoreState();
    }

    /**
     * The DM Sans files in the app have no naira. Roboto Medium does.
     * It is set to the same cap-height as the digits beside it.
     */
    private static final class Naira {
        private final BaseFont font;
        private final float size;
        private final float gap;

        private Naira(BaseFont font, float size, float gap) {
            this.font = font;
            this.size = size;
            this.gap = gap;
        }

        static Naira of(BaseFont nairaFont, BaseFont digitFont, float digitSize) {
            float digitCap = digitFont.getFontDescriptor(BaseFont.CAPHEIGHT, digitSize);
            float unitCap = nairaFont.getFontDescriptor(BaseFont.CAPHEIGHT, 1f);
            float size = unitCap <= 0f || digitCap <= 0f ? digitSize : digitCap / unitCap;
            return new Naira(nairaFont, size, Math.max(1.6f, digitSize * 0.06f));
        }

        static float advance(BaseFont nairaFont, BaseFont digitFont, float digitSize) {
            return of(nairaFont, digitFont, digitSize).advance();
        }

        float advance() {
            return font.getWidthPoint("\u20A6", size) + gap;
        }

        void paint(PdfContentByte canvas, float x, float baseline, Color color) {
            text(canvas, font, size, color, "\u20A6", x, baseline, Element.ALIGN_LEFT, 0f);
        }
    }

    private static Fonts fonts() {
        Fonts loaded = fonts;
        if (loaded == null) {
            synchronized (ReceiptDocument.class) {
                if (fonts == null) {
                    fonts = new Fonts(
                            font("DMSans-Regular.ttf", "/fonts/DMSans-Regular.ttf"),
                            font("DMSans-Medium.ttf", "/fonts/DMSans-Medium.ttf"),
                            font("DMSans-SemiBold.ttf", "/fonts/DMSans-SemiBold.ttf"),
                            font("DMSans-Bold.ttf", "/fonts/DMSans-Bold.ttf"),
                            font("DMSans-Black.ttf", "/fonts/DMSans-Black.ttf"),
                            font("Roboto-Medium.ttf", "/fonts/Roboto-Medium.ttf"),
                            read("/receipts/logo.png"));
                }
                loaded = fonts;
            }
        }
        return loaded;
    }

    private static BaseFont font(String name, String path) {
        try {
            return BaseFont.createFont(name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, read(path), null);
        } catch (Exception ex) {
            throw new IllegalStateException("Receipt font could not be embedded: " + name, ex);
        }
    }

    private static byte[] read(String path) {
        try (InputStream in = ReceiptDocument.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Receipt resource is missing: " + path);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new IllegalStateException("Receipt resource could not be read: " + path, ex);
        }
    }

    private record Line(String label, String value, boolean money) {
    }

    private record Fonts(
            BaseFont regular,
            BaseFont medium,
            BaseFont semibold,
            BaseFont bold,
            BaseFont black,
            BaseFont naira,
            byte[] logo) {
    }
}
