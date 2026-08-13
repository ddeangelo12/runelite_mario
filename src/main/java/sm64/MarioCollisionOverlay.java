package sm64;

import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import javax.inject.Inject;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;

/**
 * Draws the SM64 collision mesh over the OSRS world as wireframe.
 *
 * This reads the surfaces back out of the SurfaceBuffer -- the same bytes handed
 * to sm64_static_surfaces_load -- and projects them through the OSRS camera. So
 * it shows what libsm64 actually received, not what SceneCollision intended to
 * send. If Mario walks through a wall that appears here, the bug is in libsm64's
 * handling; if the wall is missing here, the bug is in SceneCollision.
 *
 * Floors are drawn teal, walls amber. Classification is by geometry rather than
 * surface type, since the two share a type when non-slippery floors are off.
 */
public class MarioCollisionOverlay extends Overlay {

    private static final Color FLOOR = new Color(60, 200, 170, 90);
    private static final Color WALL = new Color(240, 170, 40, 130);

    private final Client client;
    private final MarioPlugin plugin;
    private final MarioConfig config;
    private final Projector projector;

    private final int[] tri = new int[9];
    private final int[] p0 = new int[2];
    private final int[] p1 = new int[2];
    private final int[] p2 = new int[2];

    @Inject
    private MarioCollisionOverlay(Client client, MarioPlugin plugin,
                                  MarioConfig config, Projector projector) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
        this.projector = projector;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g) {
        if (!config.debugCollision() || !plugin.isInitialised()) {
            return null;
        }

        SceneCollision collision = plugin.getCollision();
        SurfaceBuffer buffer = collision.buffer();
        int count = buffer.count();
        if (count <= 0) {
            return null;
        }

        // Cull around the player. Projecting ~9700 triangles per frame in Java2D
        // is not viable, and distant wireframe is unreadable anyway.
        Player local = client.getLocalPlayer();
        LocalPoint lp = local != null ? local.getLocalLocation() : null;
        if (lp == null || !lp.isInScene()) {
            return null;
        }
        int radius = config.debugCollisionRadius() * SceneCollision.LOCAL_TILE_SIZE;
        int radiusSq = radius * radius;

        int originX = collision.originLocalX();
        int originY = collision.originLocalY();

        Stroke oldStroke = g.getStroke();
        g.setStroke(new BasicStroke(1f));

        int drawn = 0;
        for (int i = 0; i < count; i++) {
            buffer.readTriangle(i, tri);

            // Cheap reject on the first vertex before doing any projection.
            float lx0 = originX + tri[0];
            float ly0 = originY - tri[2];
            float dx = lx0 - lp.getX();
            float dy = ly0 - lp.getY();
            if (dx * dx + dy * dy > radiusSq) {
                continue;
            }

            if (!projectVertex(originX, originY, tri, 0, p0)
                || !projectVertex(originX, originY, tri, 1, p1)
                || !projectVertex(originX, originY, tri, 2, p2)) {
                continue;
            }

            // Flat in Y means a floor; anything else is treated as a wall.
            boolean flat = tri[1] == tri[4] && tri[4] == tri[7];
            g.setColor(flat ? FLOOR : WALL);

            g.drawLine(p0[0], p0[1], p1[0], p1[1]);
            g.drawLine(p1[0], p1[1], p2[0], p2[1]);
            g.drawLine(p2[0], p2[1], p0[0], p0[1]);
            drawn++;
        }

        g.setStroke(oldStroke);

        if (config.debugCollisionStats()) {
            g.setColor(Color.WHITE);
            g.drawString(drawn + " / " + count + " surfaces  origin ("
                       + originX + ", " + originY + ")", 12, 40);
        }
        return null;
    }

    private boolean projectVertex(int originX, int originY, int[] t, int v, int[] out) {
        int sx = t[v * 3];
        int sy = t[v * 3 + 1];
        int sz = t[v * 3 + 2];

        // Inverse of SceneCollision's mapping.
        float localX = originX + sx;
        float localY = originY - sz;
        float height = -sy;

        return projector.project(localX, localY, height, out);
    }
}
