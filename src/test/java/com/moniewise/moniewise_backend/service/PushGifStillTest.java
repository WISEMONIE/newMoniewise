package com.moniewise.moniewise_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** GIF push pictures: recognised by their bytes, and their first frame becomes a PNG for Android. */
class PushGifStillTest {

    /** A real two-frame GIF: red, then blue. */
    private static byte[] twoFrameGif(int w, int h) throws Exception {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.prepareWriteSequence(null);
            for (Color c : new Color[]{Color.RED, Color.BLUE}) {
                BufferedImage frame = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = frame.createGraphics();
                g.setColor(c);
                g.fillRect(0, 0, w, h);
                g.dispose();
                writer.writeToSequence(new IIOImage(frame, null, null), null);
            }
            writer.endWriteSequence();
        }
        return out.toByteArray();
    }

    @Test
    void aGifIsRecognisedByItsBytes() throws Exception {
        byte[] gif = twoFrameGif(20, 10);
        assertEquals("image/gif", BlogService.imageTypeFromBytes(new MockMultipartFile("f", "a.gif", "image/gif", gif)));
        assertNull(BlogService.imageTypeFromBytes(new MockMultipartFile("f", "a.gif", "image/gif",
                "GIF90a-not-real".getBytes())));
    }

    @Test
    void theStillIsTheFirstFrameAsAPng() throws Exception {
        byte[] png = BlogService.firstFramePng(twoFrameGif(20, 10));

        assertEquals("image/png", BlogService.imageTypeFromBytes(new MockMultipartFile("f", "s.png", "image/png", png)));
        BufferedImage still = ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(20, still.getWidth());
        assertEquals(10, still.getHeight());
        Color first = new Color(still.getRGB(5, 5));
        assertTrue(first.getRed() > 200 && first.getBlue() < 50, "the first (red) frame, not the second: " + first);
    }

    @Test
    void aBrokenGifIsRefused() {
        assertThrows(java.io.IOException.class, () -> BlogService.firstFramePng("GIF89a broken".getBytes()));
    }

    @Test
    void gifIsAnAllowedPushType() {
        assertTrue(BlogService.PUSH_IMAGE_TYPES.contains("image/gif"));
        assertFalse(BlogService.PUSH_IMAGE_TYPES.contains("image/webp"));
    }
}
