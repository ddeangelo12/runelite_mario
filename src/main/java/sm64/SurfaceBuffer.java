package sm64;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;

/**
 * Writes SM64Surface records straight into off-heap memory in the exact layout
 * libsm64 expects, avoiding JNA's Structure machinery. This matters: an OSRS
 * scene produces roughly 21k floor triangles plus walls, and building that many
 * JNA Structures per region load is measured in seconds.
 *
 * struct SM64Surface layout (x64):
 *   0  int16  type
 *   2  int16  force
 *   4  uint16 terrain
 *   6  -- 2 bytes of padding, because int32[3][3] forces 4-byte alignment --
 *   8  int32  vertices[3][3]   (36 bytes)
 *   44 total
 *
 * WINDING ORDER: surfaces are one-sided. The vertex order decides which way the
 * normal points, and a floor wound the wrong way is invisible to Mario -- he
 * falls straight through it. If that happens, swap v2 and v3 and try again
 * before assuming anything else is broken.
 */
public final class SurfaceBuffer {

    public static final int STRIDE = 44;

    private final Memory mem;
    private final int capacity;
    private int count;

    public SurfaceBuffer(int capacity) {
        this.capacity = capacity;
        this.mem = new Memory((long) capacity * STRIDE);
        this.mem.clear();
    }

    public void reset() {
        count = 0;
    }

    public int count() {
        return count;
    }

    public int capacity() {
        return capacity;
    }

    public Pointer pointer() {
        return mem;
    }

    /** Convenience for plain geometry with no special behaviour. */
    public void addTriangle(int x1, int y1, int z1,
                            int x2, int y2, int z2,
                            int x3, int y3, int z3) {
        addTriangle(LibSM64.SURFACE_DEFAULT, (short) 0, LibSM64.TERRAIN_GRASS,
                    x1, y1, z1, x2, y2, z2, x3, y3, z3);
    }

    public void addTriangle(short type, short force, short terrain,
                            int x1, int y1, int z1,
                            int x2, int y2, int z2,
                            int x3, int y3, int z3) {
        if (count >= capacity) {
            throw new IllegalStateException(
                "SurfaceBuffer full at " + capacity + " surfaces");
        }
        long o = (long) count * STRIDE;
        mem.setShort(o, type);
        mem.setShort(o + 2, force);
        mem.setShort(o + 4, terrain);
        mem.setInt(o + 8,  x1);
        mem.setInt(o + 12, y1);
        mem.setInt(o + 16, z1);
        mem.setInt(o + 20, x2);
        mem.setInt(o + 24, y2);
        mem.setInt(o + 28, z2);
        mem.setInt(o + 32, x3);
        mem.setInt(o + 36, y3);
        mem.setInt(o + 40, z3);
        count++;
    }

    /**
     * Adds an axis-aligned quad as two triangles. Vertices must be given in a
     * consistent loop order (either all clockwise or all counter-clockwise when
     * viewed from the side the surface should be solid on).
     */
    public void addQuad(short type, short force, short terrain,
                        int x1, int y1, int z1,
                        int x2, int y2, int z2,
                        int x3, int y3, int z3,
                        int x4, int y4, int z4) {
        addTriangle(type, force, terrain, x1, y1, z1, x2, y2, z2, x3, y3, z3);
        addTriangle(type, force, terrain, x1, y1, z1, x3, y3, z3, x4, y4, z4);
    }
}
