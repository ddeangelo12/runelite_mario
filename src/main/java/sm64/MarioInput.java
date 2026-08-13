package sm64;

import net.runelite.client.input.KeyListener;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.awt.event.KeyEvent;

/**
 * Translates WASD/space into SM64 controller state.
 *
 * Key events are CONSUMED when control is active, otherwise W/A/S/D would also
 * rotate the game camera and space would advance dialogue. The consequence is
 * that you cannot type in chat while control is on -- toggle it off first.
 *
 * Mario is purely client-side. Nothing here moves your actual character or
 * sends anything to the server; it drives the simulation only.
 */
@Singleton
public class MarioInput implements KeyListener {

    private final MarioConfig config;

    private volatile boolean up, down, left, right;
    private volatile boolean jump, dive, crouch;

    @Inject
    MarioInput(MarioConfig config) {
        this.config = config;
    }

    private boolean active() {
        return config.enabled() && config.controls();
    }

    public void clear() {
        up = down = left = right = false;
        jump = dive = crouch = false;
    }

    /** -1..1, left negative. */
    public float stickX() {
        return (right ? 1f : 0f) - (left ? 1f : 0f);
    }

    /** -1..1, forward positive. */
    public float stickY() {
        return (up ? 1f : 0f) - (down ? 1f : 0f);
    }

    public boolean jump()   { return jump; }
    public boolean dive()   { return dive; }
    public boolean crouch() { return crouch; }

    @Override
    public void keyTyped(KeyEvent e) {
        if (active() && isBound(e.getKeyChar())) {
            e.consume();
        }
    }

    @Override
    public void keyPressed(KeyEvent e) {
        if (!active()) {
            return;
        }
        if (set(e.getKeyCode(), true)) {
            e.consume();
        }
    }

    @Override
    public void keyReleased(KeyEvent e) {
        // Always process releases, even if control was just switched off --
        // otherwise a key held during the toggle stays stuck down forever.
        if (set(e.getKeyCode(), false) && active()) {
            e.consume();
        }
    }

    private boolean set(int code, boolean value) {
        switch (code) {
            case KeyEvent.VK_W:      up = value;     return true;
            case KeyEvent.VK_S:      down = value;   return true;
            case KeyEvent.VK_A:      left = value;   return true;
            case KeyEvent.VK_D:      right = value;  return true;
            case KeyEvent.VK_SPACE:  jump = value;   return true;
            case KeyEvent.VK_E:      dive = value;   return true;
            case KeyEvent.VK_SHIFT:  crouch = value; return true;
            default:                                 return false;
        }
    }

    private static boolean isBound(char c) {
        switch (Character.toLowerCase(c)) {
            case 'w': case 'a': case 's': case 'd': case 'e': case ' ':
                return true;
            default:
                return false;
        }
    }
}
