package sm64;

import com.sun.jna.Memory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * No RuneLite, no rendering. Drops Mario onto a flat plane and confirms:
 *   1. the DLL loads
 *   2. ROM asset extraction works (dumps the texture atlas to a PNG)
 *   3. the struct layouts are right (Mario falls, lands, then runs)
 *
 * Usage: java -Djna.library.path=C:\path\to\dist sm64.Smoke C:\path\to\baserom.us.z64
 */
public class Smoke {

    /** SHA-1 of the US z64 ROM. Mismatch means wrong region or byte order. */
    private static final String EXPECTED_SHA1 =
        "9bef1128717f958171a4afac3ed78ee2bb4e86ce";

    // Keep a strong reference: JNA callbacks that get GC'd crash the JVM.
    private static final LibSM64.DebugPrintFunction DEBUG =
        msg -> System.out.println("[sm64] " + msg);

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: Smoke <path-to-baserom.us.z64>");
            System.exit(1);
        }

        byte[] rom = Files.readAllBytes(Path.of(args[0]));
        System.out.printf("ROM: %,d bytes%n", rom.length);
        System.out.println("SHA-1: " + sha1(rom));
        if (!sha1(rom).equalsIgnoreCase(EXPECTED_SHA1)) {
            System.out.println("  ^ does not match the expected US z64 hash. "
                + "Asset extraction will probably produce garbage.");
        }

        LibSM64 sm = LibSM64.INSTANCE;
        sm.sm64_register_debug_print_function(DEBUG);

        // --- 1. Global init: extracts Mario's textures into our buffer -------
        byte[] texture = new byte[LibSM64.TEXTURE_BYTES];
        sm.sm64_global_init(rom, texture);
        dumpTexture(texture, new File("mario-atlas.png"));
        System.out.println("Wrote mario-atlas.png -- open it. You should see "
            + "Mario's face, cap, hands and shoes. Noise means the ROM is wrong.");

        // --- 2. A flat floor ------------------------------------------------
        // 20000 units square, centred on the origin, at y = 0.
        // If Mario falls through, reverse the winding of both triangles.
        final int E = 10000;
        SurfaceBuffer floor = new SurfaceBuffer(2);
        floor.addTriangle(-E, 0, -E,  -E, 0,  E,   E, 0,  E);
        floor.addTriangle(-E, 0, -E,   E, 0,  E,   E, 0, -E);
        sm.sm64_static_surfaces_load(floor.pointer(), floor.count());

        float probe = sm.sm64_surface_find_floor_height(0, 1000, 0);
        System.out.println("Floor height under origin: " + probe
            + "  (expect 0.0; -11000 means the winding is backwards)");

        // --- 3. Spawn --------------------------------------------------------
        int mario = sm.sm64_mario_create(0, 1000, 0);
        if (mario < 0) {
            System.err.println("sm64_mario_create failed -- no floor beneath "
                + "the spawn point. Check winding order.");
            return;
        }
        System.out.println("Mario id = " + mario);

        // --- 4. Geometry buffers ---------------------------------------------
        final int N = LibSM64.SM64_GEO_MAX_TRIANGLES;
        Memory pos = new Memory(9L * N * Float.BYTES);
        Memory nrm = new Memory(9L * N * Float.BYTES);
        Memory col = new Memory(9L * N * Float.BYTES);
        Memory uv  = new Memory(6L * N * Float.BYTES);

        LibSM64.MarioGeometryBuffers geo = new LibSM64.MarioGeometryBuffers();
        geo.position = pos;
        geo.normal = nrm;
        geo.color = col;
        geo.uv = uv;

        LibSM64.MarioInputs in = new LibSM64.MarioInputs();
        LibSM64.MarioState st = new LibSM64.MarioState();

        // Camera looking down +Z, so stick +Y means "away from camera".
        in.camLookX = 0f;
        in.camLookZ = 1f;

        // --- 5. Tick ----------------------------------------------------------
        // libsm64 assumes 30 ticks per second.
        for (int tick = 0; tick < 180; tick++) {
            if (tick >= 60) {
                in.stickY = 1f;                 // run forward
                in.buttonA = (byte) (tick % 30 == 0 ? 1 : 0);  // jump periodically
            }

            sm.sm64_mario_tick(mario, in, st, geo);

            if (tick % 10 == 0) {
                System.out.printf(
                    "t=%3d  pos=(%8.1f, %8.1f, %8.1f)  vel=%6.1f  action=0x%08X  tris=%d%n",
                    tick, st.position[0], st.position[1], st.position[2],
                    st.forwardVelocity, st.action, geo.numTrianglesUsed);
            }
        }

        System.out.println();
        System.out.println("If Y fell from 1000 to ~0 and settled, and the "
            + "triangle count is nonzero and varies, the binding is correct.");

        sm.sm64_mario_delete(mario);
        sm.sm64_global_terminate();
    }

    private static void dumpTexture(byte[] rgba, File out) throws Exception {
        int w = LibSM64.SM64_TEXTURE_WIDTH, h = LibSM64.SM64_TEXTURE_HEIGHT;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int r = rgba[i]     & 0xFF;
                int g = rgba[i + 1] & 0xFF;
                int b = rgba[i + 2] & 0xFF;
                int a = rgba[i + 3] & 0xFF;
                img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        ImageIO.write(img, "png", out);
    }

    private static String sha1(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-1").digest(data);
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
