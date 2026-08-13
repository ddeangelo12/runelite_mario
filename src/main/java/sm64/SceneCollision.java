package sm64;

import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.LocalPoint;

/**
 * Converts the OSRS scene into SM64 collision surfaces.
 *
 * COORDINATE MAPPING
 * ------------------
 * OSRS local space: X east, Y north, and a separate height value that gets
 * MORE NEGATIVE as you go UP. One tile is 128 local units.
 *
 * SM64 space: Y is up, right-handed.
 *
 * To keep the handedness consistent (a mirrored mapping makes Mario's
 * animations play backwards and his cap logo read in reverse):
 *
 *     sm64X =  (localX - originX)
 *     sm64Y = -(height)
 *     sm64Z = -(localY - originY)
 *
 * The Z negation is what preserves right-handedness. If Mario turns out
 * mirrored once rendering exists, flip the sign on Z and on faceAngle together.
 *
 * LEVEL BOUNDARY
 * --------------
 * SM64 drops surfaces outside +/-8192 units from the origin, so we cannot load
 * a whole 104x104 scene (13312 units across). Instead we emit a window of
 * WINDOW_RADIUS tiles around the player and rebuild it when the player leaves
 * the middle of that window.
 */
public final class SceneCollision {

    public static final int LOCAL_TILE_SIZE = 128;

    /** Half-width of the collision window, in tiles. 24 => 48x48 => +/-3072 units. */
    public static final int WINDOW_RADIUS = 24;

    /** Rebuild once the player has moved this many tiles from the window centre. */
    public static final int REBUILD_THRESHOLD = 8;

    /** How tall to make wall quads, in local units. ~3 tiles. */
    public static final int WALL_HEIGHT = 400;

    private static final int MAX_SURFACES =
            (2 * WINDOW_RADIUS) * (2 * WINDOW_RADIUS) * 2   // floor triangles
                    + (2 * WINDOW_RADIUS) * (2 * WINDOW_RADIUS) * 8 // up to 4 walls per tile
                    + 16;

    private final SurfaceBuffer buffer = new SurfaceBuffer(MAX_SURFACES);

    /** Scene-tile coords of the window centre, used to decide when to rebuild. */
    private int centreSceneX = Integer.MIN_VALUE;
    private int centreSceneY = Integer.MIN_VALUE;

    /** Local-space origin the SM64 world is expressed relative to. */
    private int originLocalX;
    private int originLocalY;

    private boolean flipWinding;

    public void setFlipWinding(boolean flip) {
        this.flipWinding = flip;
    }

    public SurfaceBuffer buffer() {
        return buffer;
    }

    public int originLocalX() {
        return originLocalX;
    }

    public int originLocalY() {
        return originLocalY;
    }

    /** True if the player has wandered far enough that the window should move. */
    public boolean needsRebuild(int sceneX, int sceneY) {
        return centreSceneX == Integer.MIN_VALUE
                || Math.abs(sceneX - centreSceneX) >= REBUILD_THRESHOLD
                || Math.abs(sceneY - centreSceneY) >= REBUILD_THRESHOLD;
    }

    // --- Coordinate conversion -------------------------------------------

    public float toSm64X(int localX) {
        return localX - originLocalX;
    }

    public float toSm64Z(int localY) {
        return -(localY - originLocalY);
    }

    public float toSm64Y(int height) {
        return -height;
    }

    public int fromSm64XToLocalX(float sm64X) {
        return Math.round(sm64X) + originLocalX;
    }

    public int fromSm64ZToLocalY(float sm64Z) {
        return Math.round(-sm64Z) + originLocalY;
    }

    public int fromSm64YToHeight(float sm64Y) {
        return Math.round(-sm64Y);
    }

    // --- Build -------------------------------------------------------------

    /**
     * Rebuilds the surface buffer around the given scene tile. Does not upload;
     * the caller passes buffer() to sm64_static_surfaces_load.
     */
    public void rebuild(Client client, int plane, int sceneX, int sceneY) {
        buffer.reset();
        centreSceneX = sceneX;
        centreSceneY = sceneY;
        originLocalX = sceneX * LOCAL_TILE_SIZE;
        originLocalY = sceneY * LOCAL_TILE_SIZE;

        int[][][] heights = client.getTileHeights();
        CollisionData[] collisionMaps = client.getCollisionMaps();
        int[][] flags = (collisionMaps != null && plane < collisionMaps.length
                && collisionMaps[plane] != null)
                ? collisionMaps[plane].getFlags()
                : null;

        int minX = Math.max(0, sceneX - WINDOW_RADIUS);
        int maxX = Math.min(103, sceneX + WINDOW_RADIUS);
        int minY = Math.max(0, sceneY - WINDOW_RADIUS);
        int maxY = Math.min(103, sceneY + WINDOW_RADIUS);

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                emitFloor(heights, plane, x, y);
                if (flags != null) {
                    emitWalls(heights, flags, plane, x, y);
                }
            }
        }
    }

    private void emitFloor(int[][][] heights, int plane, int x, int y) {
        // Corner heights. tileHeights is 105x105 -- one more than the tile grid.
        int h00 = heights[plane][x][y];
        int h10 = heights[plane][x + 1][y];
        int h01 = heights[plane][x][y + 1];
        int h11 = heights[plane][x + 1][y + 1];

        int lx0 = x * LOCAL_TILE_SIZE;
        int lx1 = (x + 1) * LOCAL_TILE_SIZE;
        int ly0 = y * LOCAL_TILE_SIZE;
        int ly1 = (y + 1) * LOCAL_TILE_SIZE;

        int ax = (int) toSm64X(lx0), az = (int) toSm64Z(ly0), ay = (int) toSm64Y(h00);
        int bx = (int) toSm64X(lx1), bz = (int) toSm64Z(ly0), by = (int) toSm64Y(h10);
        int cx = (int) toSm64X(lx0), cz = (int) toSm64Z(ly1), cy = (int) toSm64Y(h01);
        int dx = (int) toSm64X(lx1), dz = (int) toSm64Z(ly1), dy = (int) toSm64Y(h11);

        if (!flipWinding) {
            tri(ax, ay, az, cx, cy, cz, dx, dy, dz);
            tri(ax, ay, az, dx, dy, dz, bx, by, bz);
        } else {
            tri(ax, ay, az, dx, dy, dz, cx, cy, cz);
            tri(ax, ay, az, bx, by, bz, dx, dy, dz);
        }
    }

    private void emitWalls(int[][][] heights, int[][] flags, int plane, int x, int y) {
        int f = flags[x][y];

        boolean full = (f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0
                || (f & CollisionDataFlag.BLOCK_MOVEMENT_OBJECT) != 0;

        if (full || (f & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0) {
            wall(heights, plane, x, y + 1, x + 1, y + 1);
        }
        if (full || (f & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0) {
            wall(heights, plane, x + 1, y, x, y);
        }
        if (full || (f & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0) {
            wall(heights, plane, x + 1, y + 1, x + 1, y);
        }
        if (full || (f & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0) {
            wall(heights, plane, x, y, x, y + 1);
        }
    }

    /** A vertical quad spanning two tile corners, rising WALL_HEIGHT above them. */
    private void wall(int[][][] heights, int plane, int cx1, int cy1, int cx2, int cy2) {
        int h1 = heights[plane][cx1][cy1];
        int h2 = heights[plane][cx2][cy2];

        int x1 = (int) toSm64X(cx1 * LOCAL_TILE_SIZE);
        int z1 = (int) toSm64Z(cy1 * LOCAL_TILE_SIZE);
        int x2 = (int) toSm64X(cx2 * LOCAL_TILE_SIZE);
        int z2 = (int) toSm64Z(cy2 * LOCAL_TILE_SIZE);

        int yb1 = (int) toSm64Y(h1);
        int yb2 = (int) toSm64Y(h2);
        int yt1 = yb1 + WALL_HEIGHT;
        int yt2 = yb2 + WALL_HEIGHT;

        if (!flipWinding) {
            tri(x1, yb1, z1, x2, yb2, z2, x2, yt2, z2);
            tri(x1, yb1, z1, x2, yt2, z2, x1, yt1, z1);
        } else {
            tri(x1, yb1, z1, x2, yt2, z2, x2, yb2, z2);
            tri(x1, yb1, z1, x1, yt1, z1, x2, yt2, z2);
        }
    }

    private void tri(int x1, int y1, int z1, int x2, int y2, int z2, int x3, int y3, int z3) {
        if (buffer.count() >= buffer.capacity()) {
            return; // silently drop rather than throw mid-frame
        }
        buffer.addTriangle(x1, y1, z1, x2, y2, z2, x3, y3, z3);
    }

    /** Convenience: current scene tile of a local point. */
    public static int sceneTile(int local) {
        return local / LOCAL_TILE_SIZE;
    }

    public static int sceneX(LocalPoint p) {
        return p.getX() / LOCAL_TILE_SIZE;
    }

    public static int sceneY(LocalPoint p) {
        return p.getY() / LOCAL_TILE_SIZE;
    }
}
