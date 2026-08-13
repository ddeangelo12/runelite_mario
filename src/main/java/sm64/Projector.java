package sm64;

import net.runelite.api.Client;
import net.runelite.api.Perspective;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Projects OSRS local-space points to canvas coordinates, keeping the
 * camera-space depth.
 *
 * Mirrors RuneLite's Perspective.localToCanvas, which dispatches on
 * client.isGpu(). We reimplement rather than call it because it returns null
 * outside the viewport -- a triangle straddling the screen edge would vanish --
 * and it discards the depth value needed for sorting.
 *
 * NOTE the trig tables: camera pitch and yaw are 14-bit JAU, so they index
 * SINE14/COSINE14 (0x4000 entries), NOT SINE/COSINE (2048). Using the small
 * tables silently produces wrong angles, or an index out of bounds once the
 * yaw exceeds 2047.
 */
@Singleton
public class Projector {

    private final Client client;
    private float lastDepth;

    @Inject
    Projector(Client client) {
        this.client = client;
    }

    /** Camera-space depth of the most recent successful project() call. */
    public float lastDepth() {
        return lastDepth;
    }

    public boolean project(float localX, float localY, float height, int[] out) {
        return client.isGpu()
            ? projectGpu(localX, localY, height, out)
            : projectCpu(localX, localY, height, out);
    }

    private boolean projectGpu(float localX, float localY, float height, int[] out) {
        float cameraPitch = client.getCameraFpPitch();
        float cameraYaw = client.getCameraFpYaw();

        float pitchSin = (float) Math.sin(cameraPitch);
        float pitchCos = (float) Math.cos(cameraPitch);
        float yawSin = (float) Math.sin(cameraYaw);
        float yawCos = (float) Math.cos(cameraYaw);

        float fx = localX - client.getCameraFpX();
        float fy = localY - client.getCameraFpY();
        float fz = height - client.getCameraFpZ();

        float x1 = fx * yawCos + fy * yawSin;
        float y1 = fy * yawCos - fx * yawSin;
        float y2 = fz * pitchCos - y1 * pitchSin;
        float z1 = y1 * pitchCos + fz * pitchSin;

        if (z1 < 50f) {
            return false;
        }

        int scale = client.getScale();
        out[0] = Math.round(client.getViewportWidth() / 2f + x1 * scale / z1)
               + client.getViewportXOffset();
        out[1] = Math.round(client.getViewportHeight() / 2f + y2 * scale / z1)
               + client.getViewportYOffset();
        lastDepth = z1;
        return true;
    }

    private boolean projectCpu(float localX, float localY, float height, int[] out) {
        int cameraPitch = client.getCameraPitch() & 0x3FFF;
        int cameraYaw = client.getCameraYaw() & 0x3FFF;

        float pitchSin = Perspective.SINEF14[cameraPitch];
        float pitchCos = Perspective.COSINEF14[cameraPitch];
        float yawSin = Perspective.SINEF14[cameraYaw];
        float yawCos = Perspective.COSINEF14[cameraYaw];

        float x = localX - client.getCameraX();
        float y = localY - client.getCameraY();
        float z = height - client.getCameraZ();

        float x1 = x * yawCos + y * yawSin;
        float y1 = y * yawCos - x * yawSin;
        float y2 = z * pitchCos - y1 * pitchSin;
        float z1 = y1 * pitchCos + z * pitchSin;

        if (z1 < 50f) {
            return false;
        }

        int scale = client.getScale();
        out[0] = (int) (client.getViewportWidth() / 2 + x1 * scale / z1)
               + client.getViewportXOffset();
        out[1] = (int) (client.getViewportHeight() / 2 + y2 * scale / z1)
               + client.getViewportYOffset();
        lastDepth = z1;
        return true;
    }
}
