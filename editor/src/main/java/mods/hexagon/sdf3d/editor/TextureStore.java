package mods.hexagon.sdf3d.editor;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * A mutable RGBA texture buffer. Loads/saves PNGs, samples with wrap-around UV, and supports
 * brush stamps for on-model painting. Pixels are stored as 0xAARRGGBB (matches JavaFX).
 */
public final class TextureStore {
    private final int width;
    private final int height;
    private final int[] pixels;

    public TextureStore(int width, int height) {
        this.width = width;
        this.height = height;
        this.pixels = new int[width * height];
        clear(0xFF808080);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int[] pixels() {
        return pixels;
    }

    public void clear(int argb) {
        for (int i = 0; i < pixels.length; i++) pixels[i] = argb;
    }

    /** Samples with wrapped UV, returning the 0xAARRGGBB pixel. */
    public int sample(float u, float v) {
        int x = Math.floorMod((int) Math.floor(u * width), width);
        int y = Math.floorMod((int) Math.floor(v * height), height);
        return pixels[y * width + x];
    }

    public int getPixel(int x, int y) {
        return pixels[clamp(y, 0, height - 1) * width + clamp(x, 0, width - 1)];
    }

    public void setPixel(int x, int y, int argb) {
        if (x < 0 || y < 0 || x >= width || y >= height) return;
        pixels[y * width + x] = argb;
    }

    /** Stamps a filled circle of {@code argb}. */
    public void stamp(int cx, int cy, int radius, int argb) {
        int r = Math.max(1, radius);
        for (int y = cy - r; y <= cy + r; y++) {
            for (int x = cx - r; x <= cx + r; x++) {
                float dx = x - cx, dy = y - cy;
                if (dx * dx + dy * dy <= (float) r * r) {
                    setPixel(x, y, argb);
                }
            }
        }
    }

    public static TextureStore load(File file) throws IOException {
        BufferedImage img = ImageIO.read(file);
        if (img == null) throw new IOException("Unrecognized image: " + file);
        TextureStore store = new TextureStore(img.getWidth(), img.getHeight());
        img.getRGB(0, 0, img.getWidth(), img.getHeight(), store.pixels, 0, img.getWidth());
        return store;
    }

    public void save(File file) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, width, height, pixels, 0, width);
        ImageIO.write(img, "png", file);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
