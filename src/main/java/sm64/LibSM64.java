package sm64;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

/**
 * JNA binding for libsm64 (https://github.com/libsm64/libsm64).
 *
 * Struct layouts mirror src/libsm64.h exactly. Field ORDER matters and field
 * TYPES matter -- JNA computes offsets and padding from the declared types, so
 * a short where the header says int32 silently shifts every following field.
 */
public interface LibSM64 extends Library {

    /** Loads sm64.dll / libsm64.so / libsm64.dylib from jna.library.path. */
    LibSM64 INSTANCE = Native.load("sm64", LibSM64.class);

    int SM64_TEXTURE_WIDTH = 64 * 11;   // 704
    int SM64_TEXTURE_HEIGHT = 64;
    int SM64_GEO_MAX_TRIANGLES = 1024;

    /** Bytes in the RGBA texture atlas libsm64 fills during global init. */
    int TEXTURE_BYTES = SM64_TEXTURE_WIDTH * SM64_TEXTURE_HEIGHT * 4;

    // --- A few surface types from the decomp. Full list is in surface_terrains.h.
    short SURFACE_DEFAULT        = 0x0000;
    short SURFACE_BURNING        = 0x0001;
    short SURFACE_HANGABLE       = 0x0005;
    short SURFACE_SLOW           = 0x0009;
    short SURFACE_DEATH_PLANE    = 0x000A;
    short SURFACE_WATER          = 0x000D;
    short SURFACE_NOT_SLIPPERY   = 0x0013;
    short SURFACE_VERY_SLIPPERY  = 0x0015;
    short SURFACE_NO_CAM_COLLISION = 0x0056;
    short SURFACE_VANISH_CAP_WALLS = 0x007A;

    // --- Terrain types.
    short TERRAIN_GRASS  = 0x0000;
    short TERRAIN_STONE  = 0x0001;
    short TERRAIN_SNOW   = 0x0002;
    short TERRAIN_SAND   = 0x0003;
    short TERRAIN_SPOOKY = 0x0004;
    short TERRAIN_WATER  = 0x0005;
    short TERRAIN_SLIDE  = 0x0006;

    /**
     * struct SM64MarioInputs -- 20 bytes (16 float + 3 byte + 1 pad).
     * stickX/stickY are roughly -1..1. camLookX/camLookZ is the camera's
     * horizontal forward vector; stick input is interpreted relative to it, so
     * if you leave it at zero Mario's controls will not match the camera.
     */
    @Structure.FieldOrder({ "camLookX", "camLookZ", "stickX", "stickY",
                            "buttonA", "buttonB", "buttonZ" })
    class MarioInputs extends Structure {
        public float camLookX;
        public float camLookZ;
        public float stickX;
        public float stickY;
        public byte buttonA;
        public byte buttonB;
        public byte buttonZ;
    }

    /**
     * struct SM64MarioState -- note forwardVelocity, animID and animFrame,
     * which older example code often omits. Layout with x64 alignment:
     *   position   0    velocity  12    faceAngle 24    forwardVelocity 28
     *   health    32   (pad 34)   action 36    animID 40    animFrame 44
     *   (pad 46)  flags 48    particleFlags 52    invincTimer 56   size 60
     */
    @Structure.FieldOrder({ "position", "velocity", "faceAngle", "forwardVelocity",
                            "health", "action", "animID", "animFrame",
                            "flags", "particleFlags", "invincTimer" })
    class MarioState extends Structure {
        public float[] position = new float[3];
        public float[] velocity = new float[3];
        public float faceAngle;          // radians, Y axis
        public float forwardVelocity;
        public short health;             // 0x880 == full (8 wedges * 0x100)
        public int action;               // ACT_* constants from the decomp
        public int animID;
        public short animFrame;
        public int flags;
        public int particleFlags;
        public short invincTimer;
    }

    /**
     * struct SM64MarioGeometryBuffers. You own these buffers. Sizes per tick,
     * with N = SM64_GEO_MAX_TRIANGLES:
     *   position  9N floats (3 verts * xyz)
     *   normal    9N floats
     *   color     9N floats (0..1 RGB per vertex)
     *   uv        6N floats (3 verts * uv)
     * Only the first numTrianglesUsed triangles are valid after a tick, and
     * that count CHANGES between frames.
     */
    @Structure.FieldOrder({ "position", "normal", "color", "uv", "numTrianglesUsed" })
    class MarioGeometryBuffers extends Structure {
        public Pointer position;
        public Pointer normal;
        public Pointer color;
        public Pointer uv;
        public short numTrianglesUsed;
    }

    interface DebugPrintFunction extends Callback {
        void invoke(String message);
    }

    interface PlaySoundFunction extends Callback {
        void invoke(int soundBits, Pointer pos);
    }

    // --- Lifecycle ---------------------------------------------------------

    /** rom must be a full US .z64 image. outTexture must be TEXTURE_BYTES long. */
    void sm64_global_init(byte[] rom, byte[] outTexture);
    void sm64_global_terminate();

    void sm64_register_debug_print_function(DebugPrintFunction fn);
    void sm64_register_play_sound_function(PlaySoundFunction fn);

    // --- Audio (optional; skip until the visuals work) ---------------------

    void sm64_audio_init(byte[] rom);
    int sm64_audio_tick(int numQueuedSamples, int numDesiredSamples, short[] audioBuffer);

    // --- Collision ---------------------------------------------------------

    /**
     * Replaces the entire static collision set. Pass a raw pointer to a packed
     * array of SM64Surface (see SurfaceBuffer) rather than a JNA Structure
     * array -- Structure.toArray on 20k+ elements takes seconds.
     */
    void sm64_static_surfaces_load(Pointer surfaceArray, int numSurfaces);

    float sm64_surface_find_floor_height(float x, float y, float z);
    float sm64_surface_find_water_level(float x, float z);

    // --- Mario -------------------------------------------------------------

    /** Returns the mario id, or -1 if there is no floor beneath the spawn point. */
    int sm64_mario_create(float x, float y, float z);

    void sm64_mario_tick(int marioId, MarioInputs inputs,
                         MarioState outState, MarioGeometryBuffers outBuffers);

    void sm64_mario_delete(int marioId);

    void sm64_set_mario_position(int marioId, float x, float y, float z);
    void sm64_set_mario_faceangle(int marioId, float y);
    void sm64_set_mario_velocity(int marioId, float x, float y, float z);
    void sm64_set_mario_forward_velocity(int marioId, float vel);
    void sm64_set_mario_action(int marioId, int action);
    void sm64_set_mario_health(int marioId, short health);
    void sm64_set_mario_invincibility(int marioId, short timer);
    void sm64_set_mario_water_level(int marioId, int level);
    void sm64_mario_heal(int marioId, byte healCounter);
    void sm64_mario_kill(int marioId);
}
