package sm64;

import com.google.inject.Provides;
import com.sun.jna.Memory;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

import javax.inject.Inject;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Steps 4-5: SM64 physics driven by the OSRS scene, drawn with a Java2D
 * software renderer.
 *
 * Mario has no input yet -- he stands where he spawned. Input arrives in step 6.
 */
@Slf4j
@PluginDescriptor(
        name = "Mario",
        description = "SM64 physics running against the OSRS scene",
        tags = { "mario", "sm64" }
)
public class MarioPlugin extends Plugin {

    private static final long TICK_NANOS = 1_000_000_000L / 30L;

    /**
     * SM64's level boundary is +/-8192; stay well inside it so the leash trips
     * before the native spatial partition does.
     */
    private static final float SM64_SAFE_RADIUS = 7000f;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private MarioConfig config;
    @Inject private OverlayManager overlayManager;
    @Inject private MarioRenderer renderer;
    @Inject private KeyManager keyManager;
    @Inject private MarioInput input;
    @Inject private MarioObjectRenderer objectRenderer;

    // Strong references required -- a GC'd JNA callback crashes the JVM.
    private final LibSM64.DebugPrintFunction debugPrint =
            msg -> log.info("[sm64] {}", msg);

    /**
     * No-op sink for SM64's sound events.
     *
     * This is not optional. libsm64 calls the registered play-sound function
     * whenever Mario makes a noise, and the first noise he makes is a footstep
     * when he starts walking -- which is why standing idle was always safe and
     * the first WASD press was not. With nothing registered, that is a call
     * through a null function pointer inside native code.
     */
    private final LibSM64.PlaySoundFunction playSound =
            (soundBits, pos) -> { };

    private final SceneCollision collision = new SceneCollision();

    private LibSM64 sm;
    private boolean globalInitDone;
    private int marioId = -1;

    /** Mario's texture atlas, RGBA, SM64_TEXTURE_WIDTH x SM64_TEXTURE_HEIGHT. */
    private byte[] marioTexture;

    private Memory geoPos, geoNrm, geoCol, geoUv;
    private LibSM64.MarioGeometryBuffers geo;
    private LibSM64.MarioInputs inputs;
    private LibSM64.MarioState state;

    /** Set when the scene is rebuilt: the same tile now means a different place. */
    private boolean forceRebuild;

    private long tickAccumulator;
    private long lastNanos;
    private int logCounter;

    @Provides
    MarioConfig provideConfig(ConfigManager configManager) {
        return configManager.getConfig(MarioConfig.class);
    }

    @Override
    protected void startUp() {
        log.info("Mario plugin starting");
        lastNanos = System.nanoTime();
        collision.setFlipWinding(config.flipWinding());
        collision.setFlattenTiles(config.flattenTiles());
        collision.setNotSlippery(config.notSlippery());
        overlayManager.add(renderer);
        keyManager.registerKeyListener(input);
        clientThread.invokeLater(this::tryInit);
    }

    @Override
    protected void shutDown() {
        overlayManager.remove(renderer);
        keyManager.unregisterKeyListener(input);
        clientThread.invoke(objectRenderer::shutDown);
        input.clear();
        clientThread.invoke(() -> {
            if (marioId >= 0 && sm != null) {
                sm.sm64_mario_delete(marioId);
                marioId = -1;
            }
            if (globalInitDone && sm != null) {
                sm.sm64_global_terminate();
                globalInitDone = false;
            }
        });
        log.info("Mario plugin stopped");
    }

    // --- Accessors used by the renderer ------------------------------------

    public boolean isSimulating() {
        return globalInitDone && marioId >= 0 && config.enabled();
    }

    /**
     * numTrianglesUsed is a uint16 in C. Java's short is signed, so mask it --
     * this cannot overflow at the ~750 triangles Mario actually uses, but it is
     * the kind of thing that silently breaks later.
     */
    public int getTriangleCount() {
        return geo == null ? 0 : (geo.numTrianglesUsed & 0xFFFF);
    }

    /** Mario's SM64-space position, or null if he does not exist yet. */
    public float[] getMarioPosition() {
        return (state == null || marioId < 0) ? null : state.position;
    }

    public SceneCollision getCollision() {
        return collision;
    }

    public byte[] getMarioTexture() {
        return marioTexture;
    }

    /** Copies this tick's geometry out of native memory into reusable arrays. */
    public void readGeometry(float[] pos, float[] nrm, float[] col, int triCount) {
        if (geoPos == null || triCount <= 0) {
            return;
        }
        int floats = triCount * 9;
        geoPos.read(0, pos, 0, floats);
        geoNrm.read(0, nrm, 0, floats);
        geoCol.read(0, col, 0, floats);
    }

    // --- Initialisation ----------------------------------------------------

    private void tryInit() {
        if (globalInitDone) {
            return;
        }
        String romPath = config.romPath();
        if (romPath == null || romPath.isEmpty()) {
            log.warn("No ROM path configured -- set it in the Mario plugin config");
            return;
        }

        try {
            byte[] rom = Files.readAllBytes(Path.of(romPath));
            sm = LibSM64.INSTANCE;
            sm.sm64_register_debug_print_function(debugPrint);
            sm.sm64_register_play_sound_function(playSound);

            marioTexture = new byte[LibSM64.TEXTURE_BYTES];
            sm.sm64_global_init(rom, marioTexture);

            allocateGeometry();
            globalInitDone = true;
            log.info("libsm64 initialised, texture atlas retained ({} bytes)",
                    marioTexture.length);
        } catch (UnsatisfiedLinkError e) {
            log.error("Could not load sm64 native library. Is jna.library.path set?", e);
        } catch (Exception e) {
            log.error("libsm64 init failed", e);
        }
    }

    private void allocateGeometry() {
        int n = LibSM64.SM64_GEO_MAX_TRIANGLES;
        geoPos = new Memory(9L * n * Float.BYTES);
        geoNrm = new Memory(9L * n * Float.BYTES);
        geoCol = new Memory(9L * n * Float.BYTES);
        geoUv = new Memory(6L * n * Float.BYTES);

        geo = new LibSM64.MarioGeometryBuffers();
        geo.position = geoPos;
        geo.normal = geoNrm;
        geo.color = geoCol;
        geo.uv = geoUv;

        inputs = new LibSM64.MarioInputs();
        state = new LibSM64.MarioState();
    }

    // --- Events ------------------------------------------------------------

    @Subscribe
    public void onGameStateChanged(GameStateChanged e) {
        GameState gs = e.getGameState();
        if (gs == GameState.LOGGED_IN) {
            tryInit();
            // Scene has changed; force a collision rebuild next frame.
            despawnMario();
            objectRenderer.onSceneChanged();
            // Tile heights and collision flags all changed underneath us, even
            // if the scene-tile coordinates look the same.
            forceRebuild = true;
        } else if (gs == GameState.LOADING) {
            // The scene is being rebuilt. Anything anchored to the old world view
            // is about to become stale.
            objectRenderer.onSceneChanged();
        } else if (gs == GameState.LOGIN_SCREEN || gs == GameState.HOPPING) {
            despawnMario();
            objectRenderer.onSceneChanged();
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged e) {
        if (!"mario".equals(e.getGroup())) {
            return;
        }
        if ("flipWinding".equals(e.getKey())
                || "flattenTiles".equals(e.getKey())
                || "notSlippery".equals(e.getKey())) {
            collision.setFlipWinding(config.flipWinding());
            collision.setFlattenTiles(config.flattenTiles());
            collision.setNotSlippery(config.notSlippery());
            clientThread.invoke(() -> {
                despawnMario();
                rebuildCollisionIfNeeded(true);
            });
        } else if ("romPath".equals(e.getKey())) {
            clientThread.invoke(this::tryInit);
        } else if ("objectRenderer".equals(e.getKey())
                || "scratchModelId".equals(e.getKey())) {
            // Let a toggle clear any sticky failure state.
            clientThread.invoke(objectRenderer::reset);
        }
    }

    /**
     * ClientTick fires once per rendered frame, which is faster than 30Hz, so
     * we accumulate real time and step the simulation at a fixed rate.
     */
    @Subscribe
    public void onClientTick(net.runelite.api.events.ClientTick e) {
        long now = System.nanoTime();
        long delta = now - lastNanos;
        lastNanos = now;

        if (!config.enabled() || !globalInitDone
                || client.getGameState() != GameState.LOGGED_IN) {
            tickAccumulator = 0;
            return;
        }

        rebuildCollisionIfNeeded(forceRebuild);
        forceRebuild = false;
        if (marioId < 0) {
            spawnMario();
            if (marioId < 0) {
                return;
            }
        }

        tickAccumulator += delta;
        // Cap so a long stall does not produce a burst of catch-up ticks.
        if (tickAccumulator > 8 * TICK_NANOS) {
            tickAccumulator = 8 * TICK_NANOS;
        }
        while (tickAccumulator >= TICK_NANOS) {
            tickAccumulator -= TICK_NANOS;
            stepMario();
        }
    }

    // --- Simulation --------------------------------------------------------

    private void rebuildCollisionIfNeeded(boolean force) {
        Player local = client.getLocalPlayer();
        if (local == null) {
            return;
        }
        LocalPoint lp = local.getLocalLocation();
        if (lp == null || !lp.isInScene()) {
            return;
        }

        // Centre the window on whoever is doing the moving. Once Mario is under
        // player control he wanders independently, and centring on the player
        // would let him walk off the edge of the loaded collision.
        int sx, sy;
        if (marioId >= 0 && config.controls() && !config.snapToPlayer()) {
            int mx = collision.fromSm64XToLocalX(state.position[0]);
            int my = collision.fromSm64ZToLocalY(state.position[2]);
            sx = clampTile(mx / SceneCollision.LOCAL_TILE_SIZE);
            sy = clampTile(my / SceneCollision.LOCAL_TILE_SIZE);
        } else {
            sx = SceneCollision.sceneX(lp);
            sy = SceneCollision.sceneY(lp);
        }

        if (!force && !collision.needsRebuild(sx, sy)) {
            return;
        }

        // Capture Mario in world terms so he survives the origin change.
        boolean hadMario = marioId >= 0;
        int keepX = 0, keepY = 0, keepH = 0;
        float keepFaceAngle = 0f;
        if (hadMario) {
            keepX = collision.fromSm64XToLocalX(state.position[0]);
            keepY = collision.fromSm64ZToLocalY(state.position[2]);
            keepH = collision.fromSm64YToHeight(state.position[1]);
            keepFaceAngle = state.faceAngle;
        }

        // ORDER MATTERS. sm64_static_surfaces_load frees and replaces the entire
        // surface pool, and Mario's state holds pointers into it -- his current
        // floor, wall and ceiling. Leaving him alive across the swap leaves those
        // pointers dangling, and the next tick dereferences freed memory, which
        // hangs the client thread. Delete first, reload, then recreate.
        if (hadMario) {
            sm.sm64_mario_delete(marioId);
            marioId = -1;
        }

        collision.rebuild(client, client.getPlane(), sx, sy);
        sm.sm64_static_surfaces_load(collision.buffer().pointer(), collision.buffer().count());
        log.info("Uploaded {} surfaces around scene tile ({}, {})",
                collision.buffer().count(), sx, sy);

        if (hadMario) {
            float nx = collision.toSm64X(keepX);
            float ny = collision.toSm64Y(keepH);
            float nz = collision.toSm64Z(keepY);
            marioId = sm.sm64_mario_create(nx, ny + 32f, nz);
            if (marioId < 0) {
                log.warn("Could not recreate Mario after surface reload at "
                        + "({}, {}, {}) -- he will respawn at the player", nx, ny, nz);
            } else {
                sm.sm64_set_mario_faceangle(marioId, keepFaceAngle);
            }
        }
    }

    private static int clampTile(int t) {
        return Math.min(103, Math.max(0, t));
    }

    private void spawnMario() {
        Player local = client.getLocalPlayer();
        if (local == null) {
            return;
        }
        LocalPoint lp = local.getLocalLocation();
        if (lp == null || !lp.isInScene()) {
            return;
        }

        int height = getTileHeight(lp);
        float x = collision.toSm64X(lp.getX());
        float z = collision.toSm64Z(lp.getY());
        float y = collision.toSm64Y(height) + 200f; // drop in from slightly above

        marioId = sm.sm64_mario_create(x, y, z);
        if (marioId < 0) {
            log.warn("sm64_mario_create failed at ({}, {}, {}) -- "
                    + "no floor beneath. Try toggling 'Flip surface winding'.", x, y, z);
        } else {
            log.info("Mario spawned id={} at ({}, {}, {})", marioId, x, y, z);
        }
    }

    /**
     * True while Mario is somewhere the simulation can safely represent.
     *
     * SM64 drops surfaces outside +/-8192 units of the origin, so anything past
     * that is already off the collision map. The scene bounds check catches him
     * leaving the loaded region horizontally, and the depth check catches an
     * endless fall after he has already left it.
     */
    private static boolean isSleepAction(int action) {
        return action == LibSM64.ACT_START_SLEEPING
                || action == LibSM64.ACT_SLEEPING
                || action == LibSM64.ACT_WAKING_UP;
    }

    private boolean isMarioSane() {
        float x = state.position[0];
        float y = state.position[1];
        float z = state.position[2];

        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            return false;
        }
        if (Math.abs(x) > SM64_SAFE_RADIUS || Math.abs(z) > SM64_SAFE_RADIUS) {
            return false;
        }
        if (y < -SM64_SAFE_RADIUS || y > SM64_SAFE_RADIUS) {
            return false;
        }

        int lx = collision.fromSm64XToLocalX(x);
        int ly = collision.fromSm64ZToLocalY(z);
        int maxLocal = 104 * SceneCollision.LOCAL_TILE_SIZE;
        return lx >= 0 && ly >= 0 && lx < maxLocal && ly < maxLocal;
    }

    private void despawnMario() {
        if (marioId >= 0 && sm != null) {
            sm.sm64_mario_delete(marioId);
            marioId = -1;
        }
    }

    private void stepMario() {
        // Diagnostic: pin Mario to the player so the coordinate mapping can be
        // judged visually.
        if (config.snapToPlayer()) {
            Player p = client.getLocalPlayer();
            LocalPoint plp = p != null ? p.getLocalLocation() : null;
            // isInScene matters here: during a scene transition the player's
            // local point goes briefly stale, and teleporting Mario to a bogus
            // coordinate strands him outside the loaded collision.
            if (plp != null && plp.isInScene()) {
                sm.sm64_set_mario_position(marioId,
                        collision.toSm64X(plp.getX()),
                        collision.toSm64Y(getTileHeight(plp)),
                        collision.toSm64Z(plp.getY()));
            }
        }

        // SM64 walks Mario into the sleep chain after ~20 seconds of no input,
        // and the wake-up transition hangs inside sm64_mario_tick -- the client
        // thread spins in native code with no way to interrupt it. Catch the
        // chain early and put him back to idle before he gets there.
        if (config.preventSleep() && isSleepAction(state.action)) {
            sm.sm64_set_mario_action(marioId, LibSM64.ACT_IDLE);
        }

        updateCameraLook();

        if (config.controls()) {
            float sx = input.stickX();
            float sy = input.stickY();
            // Normalise so diagonals are not faster than cardinals.
            float mag = (float) Math.sqrt(sx * sx + sy * sy);
            if (mag > 1f) {
                sx /= mag;
                sy /= mag;
            }
            float stickScale = (float) config.stickScale();
            // SM64's stick X runs opposite to the intuitive key mapping, so D
            // (right) is a negative stick value. MarioInput reports plain key
            // state; the convention is applied here, at the conversion point.
            inputs.stickX = -sx * stickScale;
            inputs.stickY = sy * stickScale;
            inputs.buttonA = (byte) (input.jump() ? 1 : 0);
            inputs.buttonB = (byte) (input.dive() ? 1 : 0);
            inputs.buttonZ = (byte) (input.crouch() ? 1 : 0);
        } else {
            inputs.stickX = 0f;
            inputs.stickY = 0f;
            inputs.buttonA = 0;
            inputs.buttonB = 0;
            inputs.buttonZ = 0;
        }

        if (config.traceTicks()) {
            // Logged BEFORE the native call on purpose: if the tick never
            // returns, this line is the last thing written, and it describes
            // exactly the state and input that triggered it.
            log.info("tick action=0x{} animID={} frame={} stick=({}, {}) "
                            + "A={} B={} Z={} pos=({}, {}, {})",
                    Integer.toHexString(state.action), state.animID, state.animFrame,
                    inputs.stickX, inputs.stickY,
                    inputs.buttonA, inputs.buttonB, inputs.buttonZ,
                    state.position[0], state.position[1], state.position[2]);
        }

        sm.sm64_mario_tick(marioId, inputs, state, geo);

        // Leash. Once Mario is under player control he can run off the edge of
        // the loaded collision, at which point he falls forever and his
        // coordinates grow until they exceed SM64's +/-8192 spatial partition --
        // which indexes out of range inside native code and takes the JVM with
        // it. Catch it on our side first.
        if (!isMarioSane()) {
            log.warn("Mario escaped the world at ({}, {}, {}) -- respawning",
                    state.position[0], state.position[1], state.position[2]);
            despawnMario();
            rebuildCollisionIfNeeded(true);
            return;
        }

        objectRenderer.update();

        if (config.logState() && ++logCounter % 30 == 0) {
            logFloorProbe();
        }
    }

    /**
     * Feeds the game camera's facing into libsm64 so that "forward" on the stick
     * means "away from the camera".
     *
     * libsm64 derives the camera yaw as atan2s(camLookZ, camLookX) and adds it to
     * the stick direction. Empirically the vector wants to point from Mario back
     * TOWARD the camera, hence the negation -- if W drives Mario at the camera
     * instead of away from it, flip the "Invert camera-relative movement" config
     * option rather than editing this.
     */
    private void updateCameraLook() {
        int yaw = client.getCameraYaw() & 0x3FFF;
        double angle = yaw * (2.0 * Math.PI / 16384.0);

        // Camera forward in OSRS local space (x east, y north).
        //
        // The X sign is NOT sin(yaw). Working back from the projection: camera
        // depth is (y * yawCos - x * yawSin), which is maximised along
        // (-sin, cos). Using +sin mirrors the mapping about the north-south
        // axis, so W is correct facing north or south and progressively wrong
        // everywhere else.
        float fx = (float) -Math.sin(angle);
        float fy = (float) Math.cos(angle);

        // Into SM64 space: X unchanged, Z is negated north (see SceneCollision).
        float sx = fx;
        float sz = -fy;

        float sign = config.invertCameraLook() ? 1f : -1f;
        inputs.camLookX = sign * sx;
        inputs.camLookZ = sign * sz;
    }

    /**
     * Asks libsm64 for the floor height at the PLAYER's position and compares it
     * to what OSRS reports for the same tile. Zero delta means the scene-to-SM64
     * conversion is faithful. This is independent of where Mario happens to be,
     * which matters because Mario has no input yet and does not follow you.
     */
    private void logFloorProbe() {
        Player local = client.getLocalPlayer();
        LocalPoint lp = local != null ? local.getLocalLocation() : null;
        if (lp == null || !lp.isInScene()) {
            return;
        }

        float px = collision.toSm64X(lp.getX());
        float pz = collision.toSm64Z(lp.getY());

        // Probe from well above so we find the floor rather than a ceiling.
        float probe = sm.sm64_surface_find_floor_height(px, 10000f, pz);
        int probeHeight = collision.fromSm64YToHeight(probe);
        int expected = getTileHeight(lp);

        log.info("probe {} vs tile {}  delta {}   mario action=0x{}  tris={}",
                probeHeight, expected, probeHeight - expected,
                Integer.toHexString(state.action), getTriangleCount());

        // Where is Mario actually standing, in OSRS terms? If the tile offset
        // simply tracks how far you have walked since he spawned, the transform
        // is fine and he is just inert.
        int marioLocalX = collision.fromSm64XToLocalX(state.position[0]);
        int marioLocalY = collision.fromSm64ZToLocalY(state.position[2]);
        int marioHeight = collision.fromSm64YToHeight(state.position[1]);

        log.info("mario local ({}, {}) h={} | player local ({}, {}) h={} | tile offset ({}, {})",
                marioLocalX, marioLocalY, marioHeight,
                lp.getX(), lp.getY(), expected,
                (marioLocalX - lp.getX()) / SceneCollision.LOCAL_TILE_SIZE,
                (marioLocalY - lp.getY()) / SceneCollision.LOCAL_TILE_SIZE);
    }

    private int getTileHeight(LocalPoint lp) {
        int[][][] heights = client.getTileHeights();
        int sx = Math.min(103, Math.max(0, SceneCollision.sceneX(lp)));
        int sy = Math.min(103, Math.max(0, SceneCollision.sceneY(lp)));
        return heights[client.getPlane()][sx][sy];
    }
}