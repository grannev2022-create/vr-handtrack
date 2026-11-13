package com.grannev.vrhandtrack;

import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class VrRenderer implements GLSurfaceView.Renderer {

    private static final float IPD = 0.064f;
    private static final int RING_CUBES = 12;

    private static final String VERTEX_SHADER =
            "uniform mat4 uMvp;\n"
                    + "attribute vec3 aPos;\n"
                    + "attribute vec3 aNormal;\n"
                    + "varying float vLight;\n"
                    + "void main() {\n"
                    + "  gl_Position = uMvp * vec4(aPos, 1.0);\n"
                    + "  vLight = 0.45 + 0.55 * max(dot(aNormal, normalize(vec3(0.4, 0.8, 0.5))), 0.0);\n"
                    + "}\n";

    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n"
                    + "uniform vec3 uColor;\n"
                    + "varying float vLight;\n"
                    + "void main() { gl_FragColor = vec4(uColor * vLight, 1.0); }\n";

    private volatile PoseProvider provider;
    private volatile int targetFps = 60;
    private volatile int smoothingPercent = 30;

    private PoseProvider initializedProvider;
    private int width, height;
    private long lastFrameNs;

    private int program, aPos, aNormal, uMvp, uColor;
    private FloatBuffer cube;

    private final float[] proj = new float[16];
    private final float[] view = new float[16];
    private final float[] rot = new float[16];
    private final float[] model = new float[16];
    private final float[] mv = new float[16];
    private final float[] mvp = new float[16];

    private final float[] qNew = {0, 0, 0, 1};
    private final float[] pNew = new float[3];
    private final float[] qSmooth = {0, 0, 0, 1};
    private final float[] pSmooth = new float[3];
    private boolean hasSmooth;

    public void setPoseProvider(PoseProvider p) {
        provider = p;
        hasSmooth = false;
    }

    public void setTargetFps(int fps) {
        targetFps = Math.max(1, fps);
    }

    public void setSmoothingPercent(int percent) {
        smoothingPercent = Math.max(0, Math.min(100, percent));
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.04f, 0.04f, 0.09f, 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);

        int vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vs);
        GLES20.glAttachShader(program, fs);
        GLES20.glLinkProgram(program);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aNormal = GLES20.glGetAttribLocation(program, "aNormal");
        uMvp = GLES20.glGetUniformLocation(program, "uMvp");
        uColor = GLES20.glGetUniformLocation(program, "uColor");

        cube = buildCube();
        initializedProvider = null;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) {
        width = w;
        height = h;
        if (initializedProvider != null) initializedProvider.onSurfaceChanged(w, h);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        limitFps();

        PoseProvider p = provider;
        if (p != null) {
            if (p != initializedProvider) {
                p.onGlReady();
                p.onSurfaceChanged(width, height);
                initializedProvider = p;
            }
            if (p.getPose(qNew, pNew)) smooth();
        }

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        drawEye(0, -IPD / 2f);
        drawEye(1, IPD / 2f);
    }

    private void limitFps() {
        long frameNs = 1_000_000_000L / targetFps;
        long now = System.nanoTime();
        long wait = lastFrameNs + frameNs - now;
        if (wait > 0) {
            try {
                Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
            } catch (InterruptedException ignored) {
            }
            now = System.nanoTime();
        }
        lastFrameNs = now;
    }

    private void smooth() {
        if (!hasSmooth) {
            System.arraycopy(qNew, 0, qSmooth, 0, 4);
            System.arraycopy(pNew, 0, pSmooth, 0, 3);
            hasSmooth = true;
            return;
        }
        float alpha = 1f - 0.98f * (smoothingPercent / 100f);
        Quat.nlerp(qSmooth, qNew, alpha, qSmooth);
        for (int i = 0; i < 3; i++) pSmooth[i] += (pNew[i] - pSmooth[i]) * alpha;
    }

    private void drawEye(int eye, float offset) {
        int half = width / 2;
        GLES20.glViewport(eye == 0 ? 0 : half, 0, half, height);
        Matrix.perspectiveM(proj, 0, 90f, (float) half / Math.max(1, height), 0.05f, 100f);

        Quat.toMatrix(qSmooth, rot);
        float ex = pSmooth[0] + rot[0] * offset;
        float ey = pSmooth[1] + rot[1] * offset;
        float ez = pSmooth[2] + rot[2] * offset;

        Matrix.transposeM(view, 0, rot, 0);
        Matrix.translateM(view, 0, -ex, -ey, -ez);

        GLES20.glUseProgram(program);
        cube.position(0);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 24, cube);
        GLES20.glEnableVertexAttribArray(aPos);
        cube.position(3);
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 24, cube);
        GLES20.glEnableVertexAttribArray(aNormal);

        drawCube(0f, -1.6f, 0f, 14f, 0.1f, 14f, 0.22f, 0.22f, 0.26f);

        for (int i = 0; i < RING_CUBES; i++) {
            double a = i * 2.0 * Math.PI / RING_CUBES;
            float x = (float) (3.0 * Math.sin(a));
            float z = (float) (-3.0 * Math.cos(a));
            float y = ((i % 3) - 1) * 0.7f;
            int c = Color.HSVToColor(new float[]{i * 360f / RING_CUBES, 0.7f, 0.95f});
            drawCube(x, y, z, 0.6f, 0.6f, 0.6f,
                    Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f);
        }

        drawCube(0f, -0.5f, -1.2f, 0.3f, 0.3f, 0.3f, 1f, 0.85f, 0.2f);
    }

    private void drawCube(float x, float y, float z, float sx, float sy, float sz,
                          float r, float g, float b) {
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, x, y, z);
        Matrix.scaleM(model, 0, sx, sy, sz);
        Matrix.multiplyMM(mv, 0, view, 0, model, 0);
        Matrix.multiplyMM(mvp, 0, proj, 0, mv, 0);
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0);
        GLES20.glUniform3f(uColor, r, g, b);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
    }

    private static FloatBuffer buildCube() {
        float[] quad = {-1, -1, 1, -1, 1, 1, -1, -1, 1, 1, -1, 1};
        float[] data = new float[36 * 6];
        int i = 0;
        for (int axis = 0; axis < 3; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int u = (axis + 1) % 3;
                int v = (axis + 2) % 3;
                for (int k = 0; k < 6; k++) {
                    float[] p = new float[3];
                    p[axis] = sign;
                    p[u] = quad[k * 2];
                    p[v] = quad[k * 2 + 1];
                    data[i++] = p[0] * 0.5f;
                    data[i++] = p[1] * 0.5f;
                    data[i++] = p[2] * 0.5f;
                    data[i++] = axis == 0 ? sign : 0;
                    data[i++] = axis == 1 ? sign : 0;
                    data[i++] = axis == 2 ? sign : 0;
                }
            }
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(data.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }
}
