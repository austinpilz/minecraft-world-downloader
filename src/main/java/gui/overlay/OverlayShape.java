package gui.overlay;

import gui.Bounds;
import java.util.List;
import javafx.scene.paint.Color;

/**
 * A single shape imported from an overlay file, in world (block) coordinates. Polygons and lines store their
 * vertices, circles and points store their centre as the only vertex.
 */
public class OverlayShape {
    public enum Kind { POLYGON, POLYLINE, CIRCLE, POINT }

    static final Color DEFAULT_STROKE = Color.web("#ffcc00");
    static final Color DEFAULT_FILL = Color.web("#ffcc00", 0.15);

    private final Kind kind;
    private final double[] x;
    private final double[] z;
    private final List<double[][]> holes;
    private final double radiusX;
    private final double radiusZ;

    private String label = "";
    private Color stroke = DEFAULT_STROKE;
    private Color fill = DEFAULT_FILL;
    private double lineWidth = 2;

    private double labelX;
    private double labelZ;

    private double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE;
    private double minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

    private OverlayShape(Kind kind, double[] x, double[] z, List<double[][]> holes, double radiusX, double radiusZ) {
        if (x.length != z.length) {
            throw new IllegalArgumentException("x and z have different lengths");
        }
        for (int i = 0; i < x.length; i++) {
            if (!Double.isFinite(x[i]) || !Double.isFinite(z[i])) {
                throw new IllegalArgumentException("non-numeric coordinate");
            }
        }

        this.kind = kind;
        this.x = x;
        this.z = z;
        this.holes = holes;
        this.radiusX = radiusX;
        this.radiusZ = radiusZ;

        for (int i = 0; i < x.length; i++) {
            minX = Math.min(minX, x[i] - radiusX);
            maxX = Math.max(maxX, x[i] + radiusX);
            minZ = Math.min(minZ, z[i] - radiusZ);
            maxZ = Math.max(maxZ, z[i] + radiusZ);
        }
        this.labelX = (minX + maxX) / 2;
        this.labelZ = (minZ + maxZ) / 2;
    }

    /**
     * @param holes each hole is a {x[], z[]} pair, may be empty
     */
    public static OverlayShape polygon(double[] x, double[] z, List<double[][]> holes) {
        if (x.length < 3) {
            throw new IllegalArgumentException("polygon needs at least 3 points");
        }
        for (double[][] hole : holes) {
            if (hole[0].length < 3 || hole[0].length != hole[1].length) {
                throw new IllegalArgumentException("invalid polygon hole");
            }
        }
        return new OverlayShape(Kind.POLYGON, x, z, holes, 0, 0);
    }

    public static OverlayShape rectangle(double x1, double z1, double x2, double z2) {
        return polygon(new double[]{x1, x2, x2, x1}, new double[]{z1, z1, z2, z2}, List.of());
    }

    public static OverlayShape polyline(double[] x, double[] z) {
        if (x.length < 2) {
            throw new IllegalArgumentException("line needs at least 2 points");
        }
        return new OverlayShape(Kind.POLYLINE, x, z, List.of(), 0, 0);
    }

    public static OverlayShape circle(double x, double z, double radiusX, double radiusZ) {
        if (!(radiusX > 0) || !(radiusZ > 0)) {
            throw new IllegalArgumentException("circle needs a positive radius");
        }
        return new OverlayShape(Kind.CIRCLE, new double[]{x}, new double[]{z}, List.of(), radiusX, radiusZ);
    }

    public static OverlayShape point(double x, double z) {
        return new OverlayShape(Kind.POINT, new double[]{x}, new double[]{z}, List.of(), 0, 0);
    }

    OverlayShape withLabel(String label) {
        this.label = label == null ? "" : label;
        return this;
    }

    OverlayShape withStyle(Color stroke, Color fill, double lineWidth) {
        this.stroke = stroke;
        this.fill = fill;
        this.lineWidth = Math.max(1, Math.min(lineWidth, 8));
        return this;
    }

    OverlayShape withLabelPosition(double x, double z) {
        if (Double.isFinite(x) && Double.isFinite(z)) {
            this.labelX = x;
            this.labelZ = z;
        }
        return this;
    }

    public boolean intersects(Bounds bounds) {
        return maxX >= bounds.getMinX() && minX <= bounds.getMaxX()
            && maxZ >= bounds.getMinZ() && minZ <= bounds.getMaxZ();
    }

    /**
     * Whether the given world position is on this shape. Lines and points have no area, so for those the position
     * only has to be within the given tolerance (in blocks).
     */
    public boolean contains(double px, double pz, double tolerance) {
        if (px < minX - tolerance || px > maxX + tolerance || pz < minZ - tolerance || pz > maxZ + tolerance) {
            return false;
        }

        return switch (kind) {
            case POLYGON -> {
                boolean inside = ringContains(x, z, px, pz);
                for (double[][] hole : holes) {
                    if (ringContains(hole[0], hole[1], px, pz)) {
                        inside = !inside;
                    }
                }
                yield inside;
            }
            case CIRCLE -> {
                double dx = (px - x[0]) / radiusX;
                double dz = (pz - z[0]) / radiusZ;
                yield dx * dx + dz * dz <= 1;
            }
            case POLYLINE -> {
                for (int i = 1; i < x.length; i++) {
                    if (segmentDistance(px, pz, x[i - 1], z[i - 1], x[i], z[i]) <= tolerance) {
                        yield true;
                    }
                }
                yield false;
            }
            case POINT -> Math.hypot(px - x[0], pz - z[0]) <= tolerance;
        };
    }

    /**
     * Ray casting point-in-polygon test.
     */
    private static boolean ringContains(double[] x, double[] z, double px, double pz) {
        boolean inside = false;
        for (int i = 0, j = x.length - 1; i < x.length; j = i++) {
            if ((z[i] > pz) != (z[j] > pz) && px < (x[j] - x[i]) * (pz - z[i]) / (z[j] - z[i]) + x[i]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static double segmentDistance(double px, double pz, double x1, double z1, double x2, double z2) {
        double dx = x2 - x1;
        double dz = z2 - z1;
        double lengthSquared = dx * dx + dz * dz;
        double t = lengthSquared == 0 ? 0 : ((px - x1) * dx + (pz - z1) * dz) / lengthSquared;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (x1 + t * dx), pz - (z1 + t * dz));
    }

    /**
     * Area of the bounding box, used to prefer the smallest shape when several overlap the cursor.
     */
    double boundingArea() {
        return (maxX - minX) * (maxZ - minZ);
    }

    public Kind getKind() {
        return kind;
    }

    public double[] getX() {
        return x;
    }

    public double[] getZ() {
        return z;
    }

    public List<double[][]> getHoles() {
        return holes;
    }

    public double getRadiusX() {
        return radiusX;
    }

    public double getRadiusZ() {
        return radiusZ;
    }

    public String getLabel() {
        return label;
    }

    public Color getStroke() {
        return stroke;
    }

    public Color getFill() {
        return fill;
    }

    public double getLineWidth() {
        return lineWidth;
    }

    public double getLabelX() {
        return labelX;
    }

    public double getLabelZ() {
        return labelZ;
    }

    public double getMinX() {
        return minX;
    }

    public double getMaxX() {
        return maxX;
    }

    public double getMinZ() {
        return minZ;
    }

    public double getMaxZ() {
        return maxZ;
    }
}
