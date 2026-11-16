package gui;

import game.data.WorldBorderTracker.Border;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.paint.ImagePattern;
import javafx.scene.shape.FillRule;

/**
 * Draws the world border the server sent as a band of diagonal blue stripes just outside the border, like the force
 * field texture of the border in game.
 */
public class WorldBorderRenderer {
    private static final Color STRIPE_COLOR = Color.rgb(32, 160, 255, .75);
    private static final Color GAP_COLOR = Color.rgb(32, 160, 255, .15);
    private static final Color LINE_COLOR = Color.rgb(32, 160, 255, .9);
    // sizes in pixels: the stripe pattern repeats every TILE pixels, of which STRIPE are the stripe itself
    private static final int TILE = 16;
    private static final int STRIPE = 6;
    private static final double BAND_WIDTH = 12;

    private final Image stripes = createStripes();

    private static Image createStripes() {
        WritableImage image = new WritableImage(TILE, TILE);
        PixelWriter writer = image.getPixelWriter();
        for (int x = 0; x < TILE; x++) {
            for (int y = 0; y < TILE; y++) {
                writer.setColor(x, y, (x + y) % TILE < STRIPE ? STRIPE_COLOR : GAP_COLOR);
            }
        }
        return image;
    }

    public void draw(GraphicsContext graphics, Bounds bounds, double blocksPerPixel, Border border) {
        if (border == null || bounds == null || blocksPerPixel <= 0) {
            return;
        }

        double width = (bounds.getMaxX() - bounds.getMinX()) / blocksPerPixel;
        double height = (bounds.getMaxZ() - bounds.getMinZ()) / blocksPerPixel;

        // the border can be millions of blocks away, keep everything just around the visible area
        double left = clamp((border.minX() - bounds.getMinX()) / blocksPerPixel, width);
        double right = clamp((border.maxX() - bounds.getMinX()) / blocksPerPixel, width);
        double top = clamp((border.minZ() - bounds.getMinZ()) / blocksPerPixel, height);
        double bottom = clamp((border.maxZ() - bounds.getMinZ()) / blocksPerPixel, height);
        double outerLeft = clamp(left - BAND_WIDTH, width);
        double outerRight = clamp(right + BAND_WIDTH, width);
        double outerTop = clamp(top - BAND_WIDTH, height);
        double outerBottom = clamp(bottom + BAND_WIDTH, height);

        // anchor the stripes to the world rather than the window, so they move along when dragging the map
        double anchorX = Math.floorMod(Math.round(-bounds.getMinX() / blocksPerPixel), TILE);
        double anchorZ = Math.floorMod(Math.round(-bounds.getMinZ() / blocksPerPixel), TILE);

        graphics.save();
        graphics.setFillRule(FillRule.EVEN_ODD);
        graphics.setFill(new ImagePattern(stripes, anchorX, anchorZ, TILE, TILE, false));
        graphics.beginPath();
        graphics.rect(outerLeft, outerTop, outerRight - outerLeft, outerBottom - outerTop);
        graphics.rect(left, top, right - left, bottom - top);
        graphics.fill();

        graphics.setStroke(LINE_COLOR);
        graphics.setLineWidth(2);
        graphics.strokeRect(left, top, right - left, bottom - top);
        graphics.restore();
    }

    /**
     * Limit a screen coordinate to a little beyond the canvas, edges that are further away aren't visible anyway.
     */
    private static double clamp(double value, double size) {
        double margin = BAND_WIDTH * 2;
        return Math.max(-margin, Math.min(value, size + margin));
    }
}
