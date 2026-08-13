package sm64;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.util.Arrays;

/**
 * Step 5a: crude software rendering of Mario's mesh with Java2D.
 *
 * Flat-shaded, painter's-algorithm triangles. No texture, no per-pixel depth,
 * so Mario draws on top of everything -- walk behind a wall and he stays
 * visible. That is expected at this stage. The point here is to validate the
 * projection and coordinate mapping, not to look good.
 *
 * MIRRORING: if Mario comes out mirrored (cap logo reversed, animations running
 * backwards), flip the sign on SceneCollision.toSm64Z AND on the face angle
 * together. Flipping only one gives a Mario who moves correctly but faces wrong.
 */
public class MarioRenderer extends Overlay {

    /** Floats per triangle: 3 vertices * 3 components. */
    private static final int STRIDE = 9;
    private static final int MAX_TRIS = LibSM64.SM64_GEO_MAX_TRIANGLES;

    /** Light direction, roughly over the player's shoulder. */
    private static final float LX = 0.35f, LY = 0.8f, LZ = 0.5f;

    private final Client client;
    private final MarioPlugin plugin;
    private final MarioConfig config;
    private final Projector projector;

    // All reused every frame -- this runs at frame rate, so no per-frame allocation.
    private final float[] positions = new float[MAX_TRIS * STRIDE];
    private final float[] colors = new float[MAX_TRIS * STRIDE];
    private final float[] normals = new float[MAX_TRIS * STRIDE];

    private final int[] screenX = new int[MAX_TRIS * 3];
    private final int[] screenY = new int[MAX_TRIS * 3];

    /**
     * Sort keys: depth in the high 32 bits, triangle index in the low 32.
     * Sorting a primitive long[] avoids boxing ~750 Integers per frame.
     * Safe because all depths here are positive floats, and the IEEE-754 bit
     * pattern of a positive float sorts in the same order as the float.
     */
    private final long[] sortKeys = new long[MAX_TRIS];

    private final int[] projected = new int[2];
    private final Polygon poly = new Polygon(new int[3], new int[3], 3);

    @Inject
    private MarioRenderer(Client client, MarioPlugin plugin, MarioConfig config,
                          Projector projector) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        this.projector = projector;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!plugin.isSimulating() || !config.overlayRenderer()) {
            return null;
        }

        // Calibration markers, shown with wireframe on. Both should land on
        // your character's feet. If the magenta circle is right and the green
        // cross is not, the hand-rolled transform below is wrong. If both are
        // wrong, suspect the environment -- the GPU plugin in stretched mode
        // rescales the canvas so overlay coords stop matching viewport coords.
        if (config.wireframe()) {
            drawCalibrationMarkers(g);
        }

        int triCount = plugin.getTriangleCount();
        if (triCount <= 0 || triCount > MAX_TRIS) {
            return null;
        }

        plugin.readGeometry(positions, normals, colors, triCount);

        SceneCollision collision = plugin.getCollision();
        float scale = (float) config.marioScale();
        int originX = collision.originLocalX();
        int originY = collision.originLocalY();

        // --- Project ------------------------------------------------------
        int visibleCount = 0;
        for (int t = 0; t < triCount; t++) {
            boolean ok = true;
            float depthSum = 0f;

            for (int v = 0; v < 3; v++) {
                int pi = t * STRIDE + v * 3;
                float sx = positions[pi];
                float sy = positions[pi + 1];
                float sz = positions[pi + 2];

                // SM64 space -> OSRS local space (inverse of SceneCollision).
                float localX = originX + sx * scale;
                float localY = originY - sz * scale;
                float height = -sy * scale;

                if (!projector.project(localX, localY, height, projected)) {
                    ok = false;
                    break;
                }
                screenX[t * 3 + v] = projected[0];
                screenY[t * 3 + v] = projected[1];
                depthSum += projector.lastDepth();
            }

            if (ok) {
                float depth = depthSum / 3f;
                sortKeys[visibleCount++] =
                        ((long) Float.floatToRawIntBits(depth) << 32) | (t & 0xFFFFFFFFL);
            }
        }

        if (visibleCount == 0) {
            return null;
        }

        // Ascending by depth == near to far, so we walk it backwards.
        Arrays.sort(sortKeys, 0, visibleCount);

        Object oldAA = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_OFF);

        for (int i = visibleCount - 1; i >= 0; i--) {
            drawTriangle(g, (int) (sortKeys[i] & 0xFFFFFFFFL));
        }

        if (oldAA != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldAA);
        }
        return null;
    }

    private void drawTriangle(Graphics2D g, int t) {
        // Flat shade from the first vertex normal.
        int ni = t * STRIDE;
        float lambert = normals[ni] * LX + normals[ni + 1] * LY + normals[ni + 2] * LZ;
        float shade = 0.55f + 0.45f * Math.max(0f, lambert);

        // Average the three vertex colours.
        int ci = t * STRIDE;
        float r = (colors[ci]     + colors[ci + 3] + colors[ci + 6]) / 3f;
        float gg = (colors[ci + 1] + colors[ci + 4] + colors[ci + 7]) / 3f;
        float b = (colors[ci + 2] + colors[ci + 5] + colors[ci + 8]) / 3f;

        g.setColor(new Color(clamp(r * shade), clamp(gg * shade), clamp(b * shade)));

        int base = t * 3;
        poly.xpoints[0] = screenX[base];
        poly.xpoints[1] = screenX[base + 1];
        poly.xpoints[2] = screenX[base + 2];
        poly.ypoints[0] = screenY[base];
        poly.ypoints[1] = screenY[base + 1];
        poly.ypoints[2] = screenY[base + 2];
        poly.invalidate();

        g.fill(poly);

        if (config.wireframe()) {
            g.setColor(Color.BLACK);
            g.draw(poly);
        }
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Draws my projection (green cross) and RuneLite's own (magenta circle) at
     * the player's feet. Any disagreement between them isolates the bug to this
     * class; agreement that is still in the wrong place points outward.
     */
    private void drawCalibrationMarkers(Graphics2D g) {
        Player p = client.getLocalPlayer();
        LocalPoint lp = p != null ? p.getLocalLocation() : null;
        if (lp == null || !lp.isInScene()) {
            return;
        }

        int h = Perspective.getTileHeight(client, lp, client.getPlane());

        if (projector.project(lp.getX(), lp.getY(), h, projected)) {
            g.setColor(Color.GREEN);
            g.drawLine(projected[0] - 12, projected[1], projected[0] + 12, projected[1]);
            g.drawLine(projected[0], projected[1] - 12, projected[0], projected[1] + 12);
        }

        Point rl = Perspective.localToCanvas(client, lp.getX(), lp.getY(), h);
        if (rl != null) {
            g.setColor(Color.MAGENTA);
            g.drawOval(rl.getX() - 10, rl.getY() - 10, 20, 20);
        }
    }

}