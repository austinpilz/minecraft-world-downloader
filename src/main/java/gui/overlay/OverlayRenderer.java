package gui.overlay;

import config.Config;
import game.data.dimension.Dimension;
import gui.Bounds;
import java.util.ArrayList;
import java.util.List;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Draws the imported overlay shapes on top of the chunk map. Labels are only drawn once a shape is large enough on
 * screen to fit them, so a zoomed-out map doesn't turn into a wall of text. The hovered shape is always labelled.
 */
public class OverlayRenderer {
    private static final Font LABEL_FONT = Font.font(null, FontWeight.BOLD, 12);
    private static final double LABEL_CHAR_WIDTH = 7;
    private static final double LABEL_MIN_WIDTH = 40;
    private static final double POINT_LABEL_MAX_BLOCKS_PER_PIXEL = 2;
    private static final double POINT_SIZE = 6;
    private static final double CHUNK_GRID_MIN_PIXELS = 6;
    private static final Color HIGHLIGHT_COLOR = Color.WHITE;
    private static final Color CHUNK_GRID_COLOR = Color.rgb(255, 255, 255, .45);

    private final OverlayManager manager;
    private final List<OverlayShape> visible = new ArrayList<>();

    private Bounds bounds;
    private double blocksPerPixel;

    public OverlayRenderer(OverlayManager manager) {
        this.manager = manager;
    }

    public void draw(GraphicsContext graphics, Bounds bounds, double blocksPerPixel, Dimension dimension, OverlayShape hovered) {
        if (!Config.showOverlays()) {
            return;
        }
        this.bounds = bounds;
        this.blocksPerPixel = blocksPerPixel;

        visible.clear();
        manager.forEachVisible(dimension, shape -> {
            if (shape.intersects(bounds)) {
                visible.add(shape);
            }
        });
        if (hovered != null && !visible.contains(hovered)) {
            hovered = null;
        }

        graphics.save();
        graphics.setFillRule(FillRule.EVEN_ODD);
        graphics.setLineJoin(StrokeLineJoin.ROUND);

        for (OverlayShape shape : visible) {
            drawShape(graphics, shape, false);
        }

        if (hovered != null) {
            if (Config.overlayChunkGrid()) {
                drawChunkGrid(graphics, hovered);
            }
            drawShape(graphics, hovered, true);
        }

        graphics.setFont(LABEL_FONT);
        graphics.setTextAlign(TextAlignment.CENTER);
        graphics.setTextBaseline(VPos.CENTER);
        for (OverlayShape shape : visible) {
            if (shape != hovered && fitsLabel(shape)) {
                drawLabel(graphics, shape);
            }
        }
        if (hovered != null) {
            drawLabel(graphics, hovered);
        }

        graphics.restore();
    }

    private void drawShape(GraphicsContext graphics, OverlayShape shape, boolean highlight) {
        graphics.setLineWidth(shape.getLineWidth() + (highlight ? 1 : 0));
        graphics.setStroke(highlight ? HIGHLIGHT_COLOR : shape.getStroke());
        graphics.setFill(shape.getFill());

        switch (shape.getKind()) {
            case POLYGON -> {
                tracePolygon(graphics, shape);
                if (!highlight) {
                    graphics.fill();
                }
                graphics.stroke();
            }
            case POLYLINE -> {
                double[] x = shape.getX();
                double[] z = shape.getZ();
                double[] screenX = new double[x.length];
                double[] screenZ = new double[z.length];
                for (int i = 0; i < x.length; i++) {
                    screenX[i] = screenX(x[i]);
                    screenZ[i] = screenZ(z[i]);
                }
                graphics.strokePolyline(screenX, screenZ, x.length);
            }
            case CIRCLE -> {
                double radiusX = shape.getRadiusX() / blocksPerPixel;
                double radiusZ = shape.getRadiusZ() / blocksPerPixel;
                double left = screenX(shape.getX()[0]) - radiusX;
                double top = screenZ(shape.getZ()[0]) - radiusZ;
                if (!highlight) {
                    graphics.fillOval(left, top, radiusX * 2, radiusZ * 2);
                }
                graphics.strokeOval(left, top, radiusX * 2, radiusZ * 2);
            }
            case POINT -> {
                double size = highlight ? POINT_SIZE + 3 : POINT_SIZE;
                double left = screenX(shape.getX()[0]) - size / 2;
                double top = screenZ(shape.getZ()[0]) - size / 2;
                graphics.setFill(shape.getStroke());
                graphics.fillOval(left, top, size, size);
                graphics.setLineWidth(1);
                graphics.setStroke(highlight ? HIGHLIGHT_COLOR : Color.BLACK);
                graphics.strokeOval(left, top, size, size);
            }
        }
    }

    private void tracePolygon(GraphicsContext graphics, OverlayShape shape) {
        graphics.beginPath();
        traceRing(graphics, shape.getX(), shape.getZ());
        for (double[][] hole : shape.getHoles()) {
            traceRing(graphics, hole[0], hole[1]);
        }
    }

    private void traceRing(GraphicsContext graphics, double[] x, double[] z) {
        graphics.moveTo(screenX(x[0]), screenZ(z[0]));
        for (int i = 1; i < x.length; i++) {
            graphics.lineTo(screenX(x[i]), screenZ(z[i]));
        }
        graphics.closePath();
    }

    /**
     * Draw the chunk borders inside the given shape, to show exactly which chunks need to be loaded to cover it.
     */
    private void drawChunkGrid(GraphicsContext graphics, OverlayShape shape) {
        if (16 / blocksPerPixel < CHUNK_GRID_MIN_PIXELS) {
            return;
        }

        graphics.save();
        switch (shape.getKind()) {
            case POLYGON -> tracePolygon(graphics, shape);
            case CIRCLE -> {
                graphics.beginPath();
                graphics.arc(screenX(shape.getX()[0]), screenZ(shape.getZ()[0]),
                             shape.getRadiusX() / blocksPerPixel, shape.getRadiusZ() / blocksPerPixel, 0, 360);
                graphics.closePath();
            }
            default -> {
                graphics.restore();
                return;
            }
        }
        graphics.clip();

        double minX = Math.max(shape.getMinX(), bounds.getMinX());
        double maxX = Math.min(shape.getMaxX(), bounds.getMaxX());
        double minZ = Math.max(shape.getMinZ(), bounds.getMinZ());
        double maxZ = Math.min(shape.getMaxZ(), bounds.getMaxZ());

        graphics.setStroke(CHUNK_GRID_COLOR);
        graphics.setLineWidth(1);
        for (double x = Math.floor(minX / 16) * 16; x <= maxX; x += 16) {
            graphics.strokeLine(screenX(x), screenZ(minZ), screenX(x), screenZ(maxZ));
        }
        for (double z = Math.floor(minZ / 16) * 16; z <= maxZ; z += 16) {
            graphics.strokeLine(screenX(minX), screenZ(z), screenX(maxX), screenZ(z));
        }
        graphics.restore();
    }

    private boolean fitsLabel(OverlayShape shape) {
        if (shape.getLabel().isEmpty()) {
            return false;
        }
        if (shape.getKind() == OverlayShape.Kind.POINT) {
            return blocksPerPixel <= POINT_LABEL_MAX_BLOCKS_PER_PIXEL;
        }
        double width = (shape.getMaxX() - shape.getMinX()) / blocksPerPixel;
        return width > Math.max(LABEL_MIN_WIDTH, shape.getLabel().length() * LABEL_CHAR_WIDTH);
    }

    private void drawLabel(GraphicsContext graphics, OverlayShape shape) {
        if (shape.getLabel().isEmpty()) {
            return;
        }
        double x = screenX(shape.getLabelX());
        double y = screenZ(shape.getLabelZ());
        if (shape.getKind() == OverlayShape.Kind.POINT) {
            y -= POINT_SIZE + 4;
        }

        graphics.setLineWidth(3);
        graphics.setStroke(Color.BLACK);
        graphics.strokeText(shape.getLabel(), x, y);
        graphics.setFill(Color.WHITE);
        graphics.fillText(shape.getLabel(), x, y);
    }

    private double screenX(double worldX) {
        return toScreen(worldX, bounds.getMinX(), blocksPerPixel);
    }

    private double screenZ(double worldZ) {
        return toScreen(worldZ, bounds.getMinZ(), blocksPerPixel);
    }

    /**
     * World to canvas coordinate, the same transform used to draw the chunk images.
     */
    static double toScreen(double world, double boundsMin, double blocksPerPixel) {
        return (world - boundsMin) / blocksPerPixel;
    }
}
