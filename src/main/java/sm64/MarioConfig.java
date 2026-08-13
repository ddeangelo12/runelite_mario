package sm64;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("mario")
public interface MarioConfig extends Config {

    @ConfigItem(
            keyName = "romPath",
            name = "SM64 ROM path",
            description = "Absolute path to your own US .z64 Super Mario 64 ROM",
            position = 1
    )
    default String romPath() {
        return "";
    }

    @ConfigItem(
            keyName = "enabled",
            name = "Simulate Mario",
            description = "Run the SM64 physics tick",
            position = 2
    )
    default boolean enabled() {
        return false;
    }

    @ConfigItem(
            keyName = "flipWinding",
            name = "Flip surface winding",
            description = "Toggle if Mario falls through the floor",
            position = 3
    )
    default boolean flipWinding() {
        return false;
    }

    @ConfigItem(
            keyName = "logState",
            name = "Log Mario state",
            description = "Print the floor probe and action to the console each second",
            position = 4
    )
    default boolean logState() {
        return true;
    }

    @ConfigItem(
            keyName = "marioScale",
            name = "Mario scale",
            description = "OSRS local units per SM64 unit. Mario is ~160 units tall, "
                    + "a tile is 128 local units, so 1.0 makes him about 1.25 tiles.",
            position = 5
    )
    default double marioScale() {
        return 1.0;
    }

    @ConfigItem(
            keyName = "wireframe",
            name = "Wireframe",
            description = "Outline each triangle. Useful when nothing appears -- "
                    + "outlines with no fill means a colour problem, not projection.",
            position = 6
    )
    default boolean wireframe() {
        return false;
    }

    @ConfigItem(
            keyName = "snapToPlayer",
            name = "Snap to player",
            description = "Teleport Mario onto your character every tick. Diagnostic: "
                    + "if he sits exactly on you, the coordinate mapping is correct "
                    + "and he is only 'lost' because he has no input yet.",
            position = 7
    )
    default boolean snapToPlayer() {
        return false;
    }

    @ConfigItem(
            keyName = "controls",
            name = "WASD controls",
            description = "Drive Mario with WASD, space to jump, E to dive, shift to "
                    + "crouch. While this is on those keys are consumed, so camera "
                    + "keys and chat will not work -- toggle it off to type.",
            position = 8
    )
    default boolean controls() {
        return false;
    }

    @ConfigItem(
            keyName = "objectRenderer",
            name = "Use RuneLiteObject renderer",
            description = "Draw Mario inside the 3D scene so walls occlude him. "
                    + "Loses ROM textures and flattens his colours to the Jagex "
                    + "HSL palette. Turn off the overlay renderer to compare.",
            position = 10
    )
    default boolean objectRenderer() {
        return false;
    }

    @ConfigItem(
            keyName = "overlayRenderer",
            name = "Use overlay renderer",
            description = "The Java2D software renderer. Textureless and draws on top "
                    + "of everything, but always works.",
            position = 11
    )
    default boolean overlayRenderer() {
        return true;
    }

    @ConfigItem(
            keyName = "scratchModelId",
            name = "Scratch model id",
            description = "Cache model borrowed as a container for Mario's geometry. "
                    + "Any id works as long as copies of it can be merged to reach "
                    + "~750 faces.",
            position = 12
    )
    default int scratchModelId() {
        return 29260;
    }

    @ConfigItem(
            keyName = "flattenTiles",
            name = "Flatten tiles",
            description = "Collapse each tile to a single height, turning OSRS slopes "
                    + "into steps. Slopes near SM64's walkable threshold make Mario "
                    + "oscillate between walking and sliding, which hangs the client "
                    + "inside native code. Leave on unless you are debugging that.",
            position = 13
    )
    default boolean flattenTiles() {
        return true;
    }

    @ConfigItem(
            keyName = "notSlippery",
            name = "Non-slippery floors",
            description = "Marks generated floors as SURFACE_NOT_SLIPPERY so Mario "
                    + "cannot enter a slide on them.",
            position = 14
    )
    default boolean notSlippery() {
        return true;
    }

    @ConfigItem(
            keyName = "preventSleep",
            name = "Prevent sleeping",
            description = "SM64 puts Mario to sleep after ~20s idle, and waking him "
                    + "hangs the client inside native code. This forces him back to "
                    + "idle before he can drop off.",
            position = 15
    )
    default boolean preventSleep() {
        return true;
    }

    @ConfigItem(
            keyName = "traceTicks",
            name = "Trace every tick",
            description = "Log Mario's action and stick input before every native tick. "
                    + "Very noisy, but the last line written before a hang names the "
                    + "state the physics got stuck in.",
            position = 15
    )
    default boolean traceTicks() {
        return false;
    }

    @ConfigItem(
            keyName = "stickScale",
            name = "Stick scale",
            description = "Multiplier applied to WASD stick input before it reaches "
                    + "libsm64. If the expected range is not -1..1, a value just "
                    + "above the walk threshold makes Mario alternate between idle "
                    + "and walking every tick.",
            position = 16
    )
    default double stickScale() {
        return 1.0;
    }

    @ConfigItem(
            keyName = "invertCameraLook",
            name = "Invert camera-relative movement",
            description = "If W sends Mario toward the camera instead of away from it, "
                    + "turn this on.",
            position = 9
    )
    default boolean invertCameraLook() {
        return false;
    }
}