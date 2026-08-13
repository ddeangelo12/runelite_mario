package sm64;

import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Renders Mario as a RuneLiteObject so the client draws him inside the scene,
 * with real depth testing. Unlike the Java2D overlay, walls occlude him.
 *
 * THE PROBLEM THIS SOLVES AWKWARDLY
 * --------------------------------
 * There is no API to build a Model from raw vertex arrays. But Mesh exposes the
 * live backing arrays -- getVerticesX() returns the actual float[], not a copy --
 * so we borrow a cache model big enough to hold Mario and overwrite its contents
 * each frame.
 *
 * Consequences you cannot avoid on this path:
 *  - Face colours are Jagex HSL shorts, not per-vertex RGB. Mario is flat-shaded
 *    per triangle, and the palette quantises his colours.
 *  - No UVs, so no ROM textures. His cap logo, face and buttons are gone.
 *  - The scratch model must be at least as large as Mario's mesh. Unused faces
 *    are collapsed to degenerate triangles rather than removed.
 *
 * For textured Mario you need the GPU plugin route. This is the "correct
 * occlusion, worse looks" option.
 */
@Slf4j
@Singleton
public class MarioObjectRenderer {

    private static final int MAX_TRIS = LibSM64.SM64_GEO_MAX_TRIANGLES;

    /** Upper bound on merged copies. Beyond this the model id is simply wrong. */
    private static final int MAX_MERGE_COPIES = 512;

    private final Client client;
    private final MarioPlugin plugin;
    private final MarioConfig config;

    private final float[] positions = new float[MAX_TRIS * 9];
    private final float[] colors = new float[MAX_TRIS * 9];
    private final float[] normals = new float[MAX_TRIS * 9];

    private RuneLiteObject object;
    private Model scratch;
    private int scratchFaces;
    private int scratchVertices;
    private boolean unavailable;

    @Inject
    MarioObjectRenderer(Client client, MarioPlugin plugin, MarioConfig config) {
        this.client = client;
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * Drops the RuneLiteObject without discarding the scratch model.
     *
     * A scene reload rebases the world view and unregisters any RuneLiteObject
     * attached to it. Ours keeps calling setActive(true) on an object the client
     * no longer tracks, which silently does nothing and Mario vanishes. The
     * scratch model is scene-independent, so it survives.
     */
    public void onSceneChanged() {
        if (object != null) {
            object.setActive(false);
            object = null;
        }
    }

    public void shutDown() {
        if (object != null) {
            object.setActive(false);
            object = null;
        }
        scratch = null;
        unavailable = false;
    }

    /** Called from the client thread each simulation tick. */
    public void update() {
        // EVERY early return must hide the object. Returning while it is still
        // active leaves a stale RuneLiteObject registered in the scene at its
        // last position -- a frozen Mario that no config toggle can clear,
        // because the next update() takes the same early return again.
        if (!config.objectRenderer() || !plugin.isSimulating() || unavailable) {
            hide();
            return;
        }

        int triCount = plugin.getTriangleCount();
        if (triCount <= 0 || triCount > MAX_TRIS) {
            hide();
            return;
        }

        if (scratch == null && !buildScratchModel()) {
            hide();
            return;
        }
        if (triCount > scratchFaces || triCount * 3 > scratchVertices) {
            log.warn("Scratch model too small: {} faces / {} verts, need {} / {}",
                    scratchFaces, scratchVertices, triCount, triCount * 3);
            unavailable = true;
            hide();
            return;
        }

        LocalPoint anchor = marioAnchor();
        if (anchor == null || !anchor.isInScene()) {
            hide();
            return;
        }

        plugin.readGeometry(positions, normals, colors, triCount);
        writeMesh(triCount, anchor);

        if (object == null) {
            object = client.createRuneLiteObject();
            object.setModel(scratch);
        }
        object.setLocation(anchor, client.getPlane());
        object.setActive(true);
    }

    /** Deactivates without discarding, so the next good frame can reuse it. */
    private void hide() {
        if (object != null) {
            object.setActive(false);
        }
    }

    /**
     * Clears the sticky failure state so a config toggle can retry. Without this,
     * one bad frame disables the renderer for the rest of the session.
     */
    public void reset() {
        hide();
        object = null;
        scratch = null;
        unavailable = false;
    }

    /**
     * Mario's position snapped to a tile. Model vertices are expressed relative
     * to this anchor, so it needs to move with him or the vertex offsets grow
     * large enough to lose precision.
     */
    private LocalPoint marioAnchor() {
        SceneCollision collision = plugin.getCollision();
        float[] state = plugin.getMarioPosition();
        if (state == null) {
            return null;
        }
        int lx = collision.fromSm64XToLocalX(state[0]);
        int ly = collision.fromSm64ZToLocalY(state[2]);

        // Out-of-scene coordinates would index past the tile height array.
        int maxLocal = 104 * SceneCollision.LOCAL_TILE_SIZE;
        if (lx < 0 || ly < 0 || lx >= maxLocal || ly >= maxLocal) {
            return null;
        }

        // LocalPoint gained a WorldView parameter in recent versions. If this
        // does not resolve, fall back to new LocalPoint(lx, ly).
        return new LocalPoint(lx, ly, client.getTopLevelWorldView());
    }

    /**
     * Merges copies of a small cache model until the result has room for Mario.
     * The geometry does not matter -- only the array sizes -- because every
     * vertex and face gets overwritten before it is ever drawn.
     */
    /**
     * Sized for SM64_GEO_MAX_TRIANGLES, never for the current frame's count.
     *
     * Mario's triangle count is NOT constant. It sits at 752 for common states
     * but changes with hand pose, cap state and draw layer -- a slide pushes it
     * to 763. Sizing to whatever happened to be live when this first ran means
     * the model is one animation away from being too small.
     */
    private boolean buildScratchModel() {
        try {
            int baseId = config.scratchModelId();
            ModelData base = client.loadModelData(baseId);
            if (base == null) {
                log.error("Could not load scratch model id {}", baseId);
                unavailable = true;
                return false;
            }

            int baseFaces = base.getFaceCount();
            int baseVerts = base.getVerticesCount();
            log.info("Scratch base id {}: {} faces, {} vertices",
                    baseId, baseFaces, baseVerts);

            if (baseFaces <= 0 || baseVerts <= 0) {
                log.error("Scratch model id {} is empty -- pick a different id", baseId);
                unavailable = true;
                return false;
            }

            // Vertices are usually the binding constraint, not faces: Mario needs
            // 3 unshared vertices per triangle. Compute the copy count directly
            // rather than growing one at a time.
            int needFaces = MAX_TRIS;
            int needVerts = MAX_TRIS * 3;
            int copies = Math.max(ceilDiv(needFaces, baseFaces),
                    ceilDiv(needVerts, baseVerts));

            // Retry with more copies rather than trusting the arithmetic.
            // mergeModels collapses coincident vertices, so the yield per copy
            // is not guaranteed even with the copies spaced apart -- the first
            // attempt here came up 130 vertices short of 27 x 118.
            ModelData merged = null;
            for (int attempt = 0; attempt < 6; attempt++) {
                if (copies > MAX_MERGE_COPIES) {
                    log.error("Scratch model {} would need more than {} copies to "
                                    + "reach {} faces / {} vertices. Pick a larger id.",
                            baseId, MAX_MERGE_COPIES, needFaces, needVerts);
                    unavailable = true;
                    return false;
                }

                merged = mergeCopies(baseId, copies);
                if (merged == null) {
                    unavailable = true;
                    return false;
                }
                if (merged.getFaceCount() >= needFaces
                        && merged.getVerticesCount() >= needVerts) {
                    break;
                }

                log.info("Merge of {} copies gave {} faces / {} vertices, short of "
                                + "{} / {} -- retrying with more",
                        copies, merged.getFaceCount(), merged.getVerticesCount(),
                        needFaces, needVerts);
                // Scale up by the observed shortfall plus headroom.
                int have = Math.max(1, merged.getVerticesCount());
                copies = Math.max(copies + 2,
                        (int) Math.ceil(copies * (needVerts / (double) have)) + 2);
                merged = null;
            }

            if (merged == null) {
                log.error("Could not build a large enough scratch model from id {}",
                        baseId);
                unavailable = true;
                return false;
            }

            merged.cloneVertices();
            merged.cloneColors();

            scratch = merged.light();
            scratchFaces = scratch.getFaceCount();
            scratchVertices = scratch.getVerticesCount();
            log.info("Scratch model: {} copies -> {} faces, {} vertices "
                            + "(need {} / {})",
                    copies, scratchFaces, scratchVertices, needFaces, needVerts);

            if (scratchFaces < needFaces || scratchVertices < needVerts) {
                log.error("light() shrank the model below what merge produced: "
                        + "{} faces / {} vertices", scratchFaces, scratchVertices);
                unavailable = true;
                return false;
            }
            return true;
        } catch (Exception e) {
            log.error("Failed to build scratch model", e);
            unavailable = true;
            return false;
        }
    }

    /**
     * Merges n copies of a model, spacing them widely enough that no two
     * vertices coincide. Coincident vertices get deduplicated by mergeModels,
     * which is why the naive approach yields fewer vertices than copies x size.
     * The spacing is in 1/128ths of a tile, so 256 is two tiles per copy --
     * far wider than any base model. The layout is irrelevant since every
     * vertex is overwritten before the model is drawn.
     */
    private ModelData mergeCopies(int baseId, int n) {
        ModelData[] parts = new ModelData[n];
        for (int i = 0; i < n; i++) {
            ModelData part = client.loadModelData(baseId);
            if (part == null) {
                log.error("loadModelData({}) returned null on copy {}", baseId, i);
                return null;
            }
            // cloneVertices first: loadModelData hands back shared arrays.
            part.cloneVertices();
            part.translate(i * 256, 0, 0);
            parts[i] = part;
        }
        return client.mergeModels(parts);
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    private void writeMesh(int triCount, LocalPoint anchor) {
        float[] vx = scratch.getVerticesX();
        float[] vy = scratch.getVerticesY();
        float[] vz = scratch.getVerticesZ();
        int[] f1 = scratch.getFaceIndices1();
        int[] f2 = scratch.getFaceIndices2();
        int[] f3 = scratch.getFaceIndices3();

        SceneCollision collision = plugin.getCollision();
        float scale = (float) config.marioScale();
        int originX = collision.originLocalX();
        int originY = collision.originLocalY();

        int anchorX = anchor.getX();
        int anchorY = anchor.getY();
        int anchorH = Perspective.getTileHeight(client, anchor, client.getPlane());

        for (int t = 0; t < triCount; t++) {
            for (int v = 0; v < 3; v++) {
                int pi = t * 9 + v * 3;
                int vi = t * 3 + v;

                float sx = positions[pi];
                float sy = positions[pi + 1];
                float sz = positions[pi + 2];

                // SM64 -> OSRS local, then relative to the anchor tile.
                // Model space: X east, Y height (negative is up), Z north.
                vx[vi] = (originX + sx * scale) - anchorX;
                vy[vi] = (-sy * scale) - anchorH;
                vz[vi] = (originY - sz * scale) - anchorY;
            }
            f1[t] = t * 3;
            f2[t] = t * 3 + 1;
            f3[t] = t * 3 + 2;
        }

        // Collapse unused faces so leftover cache geometry does not render.
        for (int t = triCount; t < scratchFaces; t++) {
            f1[t] = 0;
            f2[t] = 0;
            f3[t] = 0;
        }

        writeColors(triCount);
    }

    /**
     * Converts libsm64's per-vertex RGB into Jagex HSL face colours.
     *
     * The packing is hue(6 bits) << 10 | saturation(3 bits) << 7 | luminance(7).
     * Lossy: 6 bits of hue over 3 of saturation, so Mario's reds and blues
     * flatten noticeably compared to the overlay renderer.
     *
     * Model exposes gouraud colours as three arrays, one per triangle corner.
     * Writing the same value to all three gives flat shading per face, which is
     * what we want here -- we are doing our own lambert term below, because the
     * client will not light a mesh whose normals it did not compute.
     */
    private void writeColors(int triCount) {
        int[] c1 = scratch.getFaceColors1();
        int[] c2 = scratch.getFaceColors2();
        int[] c3 = scratch.getFaceColors3();
        if (c1 == null || c2 == null || c3 == null) {
            return;
        }
        int limit = Math.min(triCount, Math.min(c1.length, Math.min(c2.length, c3.length)));

        for (int t = 0; t < limit; t++) {
            int ci = t * 9;
            float r = (colors[ci]     + colors[ci + 3] + colors[ci + 6]) / 3f;
            float g = (colors[ci + 1] + colors[ci + 4] + colors[ci + 7]) / 3f;
            float b = (colors[ci + 2] + colors[ci + 5] + colors[ci + 8]) / 3f;

            int ni = t * 9;
            float lambert = normals[ni] * 0.35f + normals[ni + 1] * 0.8f
                    + normals[ni + 2] * 0.5f;
            float shade = 0.6f + 0.4f * Math.max(0f, lambert);

            int hsl = rgbToJagexHsl(r * shade, g * shade, b * shade) & 0xFFFF;
            c1[t] = hsl;
            c2[t] = hsl;
            c3[t] = hsl;
        }
    }

    static short rgbToJagexHsl(float rf, float gf, float bf) {
        float r = clamp(rf), g = clamp(gf), b = clamp(bf);

        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        float l = (max + min) / 2f;

        float h = 0f, s = 0f;
        float d = max - min;
        if (d > 0.0001f) {
            s = l > 0.5f ? d / (2f - max - min) : d / (max + min);
            if (max == r) {
                h = (g - b) / d + (g < b ? 6f : 0f);
            } else if (max == g) {
                h = (b - r) / d + 2f;
            } else {
                h = (r - g) / d + 4f;
            }
            h /= 6f;
        }

        int hue = (int) (h * 63f) & 0x3F;
        int sat = (int) (s * 7f) & 0x07;
        int lum = (int) (l * 127f) & 0x7F;
        return (short) ((hue << 10) | (sat << 7) | lum);
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}