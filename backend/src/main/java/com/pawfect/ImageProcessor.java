package com.pawfect;

import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;

/**
 * Validates an uploaded picture and turns it into a clean JPEG: the content must really be a JPEG or PNG
 * (checked by its bytes, not its name or declared type), its size is checked before it is decoded, it is
 * scaled down to at most 1024 px on the long side and re-encoded, which also drops any metadata.
 */
final class ImageProcessor {
    static final int MAX_SIDE = 1024, MIN_SIDE = 8, MAX_DIMENSION = 10_000;
    static final long MAX_PIXELS = 24_000_000L;
    static final float QUALITY = 0.85f;

    static { System.setProperty("java.awt.headless", "true"); }

    private ImageProcessor() {}

    static byte[] toJpeg(byte[] raw) {
        String format = detect(raw);
        if (format == null) throw new ValidationException(415, "UNSUPPORTED_IMAGE", "Only JPEG and PNG pictures are supported.");
        BufferedImage image = decode(raw, format);
        return encode(scale(flatten(image)));
    }

    /** "jpeg" or "png" from the leading bytes, otherwise null. */
    private static String detect(byte[] b) {
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpeg";
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length > 8) {
            for (int i = 0; i < 8; i++) if (b[i] != png[i]) return null;
            return "png";
        }
        return null;
    }

    private static BufferedImage decode(byte[] raw, String format) {
        ImageReader reader = null;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(raw))) {
            Iterator<ImageReader> it = ImageIO.getImageReadersByFormatName(format);
            if (in == null || !it.hasNext()) throw bad();
            reader = it.next();
            reader.setInput(in, true, true);
            int w = reader.getWidth(0), h = reader.getHeight(0);          // read from the header, before decoding any pixels
            if (w < MIN_SIDE || h < MIN_SIDE) throw new ValidationException("IMAGE_TOO_SMALL", "That picture is too small (at least " + MIN_SIDE + " px each way).");
            if (w > MAX_DIMENSION || h > MAX_DIMENSION || (long) w * h > MAX_PIXELS) {
                throw new ValidationException("IMAGE_TOO_LARGE", "That picture is too large. Pick one under 24 megapixels.");
            }
            return reader.read(0);
        } catch (ValidationException e) {
            throw e;
        } catch (Exception e) {
            throw bad();
        } finally {
            if (reader != null) reader.dispose();
        }
    }

    private static ValidationException bad() {
        return new ValidationException("INVALID_IMAGE", "That file is not a readable JPEG or PNG picture.");
    }

    /** Transparent areas become white; the result is plain RGB, which the JPEG writer needs. */
    private static BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Halves repeatedly, then a final smooth step, so large photos shrink without jagged edges. */
    private static BufferedImage scale(BufferedImage img) {
        int longSide = Math.max(img.getWidth(), img.getHeight());
        if (longSide <= MAX_SIDE) return img;
        double ratio = (double) MAX_SIDE / longSide;
        int tw = Math.max(1, (int) Math.round(img.getWidth() * ratio)), th = Math.max(1, (int) Math.round(img.getHeight() * ratio));
        BufferedImage cur = img;
        while (cur.getWidth() / 2 >= tw && cur.getHeight() / 2 >= th) cur = resize(cur, cur.getWidth() / 2, cur.getHeight() / 2);
        return resize(cur, tw, th);
    }

    private static BufferedImage resize(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] encode(BufferedImage img) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
            ios.flush();
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not encode the picture", e);
        } finally {
            writer.dispose();
        }
    }
}
