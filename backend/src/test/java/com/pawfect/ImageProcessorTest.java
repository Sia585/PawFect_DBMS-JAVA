package com.pawfect;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static com.pawfect.TestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class ImageProcessorTest {
    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    @Test
    void pngBecomesAJpegWithTransparencyFlattenedToWhite() throws Exception {
        byte[] out = ImageProcessor.toJpeg(image(100, 80, "png", true));
        assertTrue(isJpeg(out));
        BufferedImage img = decode(out);
        assertEquals(100, img.getWidth());
        assertEquals(80, img.getHeight());
        int px = img.getRGB(5, 40) & 0xFFFFFF;                      // left half was fully transparent
        assertTrue((px >> 16 & 0xFF) > 240 && (px >> 8 & 0xFF) > 240 && (px & 0xFF) > 240, "expected white, got " + Integer.toHexString(px));
    }

    @Test
    void largePicturesAreScaledToThe1024LongEdgeKeepingTheAspectRatio() throws Exception {
        BufferedImage img = decode(ImageProcessor.toJpeg(image(3000, 1500, "jpg", false)));
        assertEquals(1024, img.getWidth());
        assertEquals(512, img.getHeight());
        BufferedImage tall = decode(ImageProcessor.toJpeg(image(1200, 2400, "png", false)));
        assertEquals(512, tall.getWidth());
        assertEquals(1024, tall.getHeight());
    }

    @Test
    void smallPicturesAreNotEnlarged() throws Exception {
        BufferedImage img = decode(ImageProcessor.toJpeg(image(300, 200, "jpg", false)));
        assertEquals(300, img.getWidth());
        assertEquals(200, img.getHeight());
    }

    @Test
    void contentIsCheckedByItsBytesNotItsName() {
        ValidationException e = assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg("<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes()));
        assertEquals("UNSUPPORTED_IMAGE", e.code());
        assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg(new byte[0]));
        assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg("GIF89a......".getBytes()));
        assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg("#!/bin/sh\nrm -rf /\n".getBytes()));
    }

    @Test
    void truncatedOrCorruptImagesAreRejectedCleanly() throws Exception {
        byte[] good = image(200, 200, "png", false);
        byte[] cut = java.util.Arrays.copyOf(good, good.length / 3);
        assertEquals("INVALID_IMAGE", assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg(cut)).code());
        byte[] jpegHeaderOnly = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};
        assertEquals("INVALID_IMAGE", assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg(jpegHeaderOnly)).code());
    }

    @Test
    void tinyAndOversizedDimensionsAreRejectedBeforeDecoding() throws Exception {
        assertEquals("IMAGE_TOO_SMALL", assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg(image(4, 4, "png", false))).code());
        // 6000 x 6000 = 36 MP: a highly compressible PNG that must be refused from its header alone
        assertEquals("IMAGE_TOO_LARGE", assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg(blankPng(6000, 6000))).code());
        assertEquals("IMAGE_TOO_LARGE", assertThrows(ValidationException.class, () -> ImageProcessor.toJpeg(blankPng(12000, 20))).code());
    }

    private static byte[] blankPng(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void metadataIsDroppedByReEncoding() throws Exception {
        byte[] out = ImageProcessor.toJpeg(image(64, 64, "jpg", false));
        String ascii = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(ascii.contains("Exif"));
    }
}
