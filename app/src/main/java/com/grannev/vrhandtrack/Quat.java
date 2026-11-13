package com.grannev.vrhandtrack;

public final class Quat {
    private Quat() {
    }

    public static void fromMatrix(float[] m, float[] q) {
        float m00 = m[0], m10 = m[1], m20 = m[2];
        float m01 = m[4], m11 = m[5], m21 = m[6];
        float m02 = m[8], m12 = m[9], m22 = m[10];
        float x, y, z, w;
        float tr = m00 + m11 + m22;
        if (tr > 0f) {
            float s = (float) Math.sqrt(tr + 1f) * 2f;
            w = 0.25f * s;
            x = (m21 - m12) / s;
            y = (m02 - m20) / s;
            z = (m10 - m01) / s;
        } else if (m00 > m11 && m00 > m22) {
            float s = (float) Math.sqrt(1f + m00 - m11 - m22) * 2f;
            w = (m21 - m12) / s;
            x = 0.25f * s;
            y = (m01 + m10) / s;
            z = (m02 + m20) / s;
        } else if (m11 > m22) {
            float s = (float) Math.sqrt(1f + m11 - m00 - m22) * 2f;
            w = (m02 - m20) / s;
            x = (m01 + m10) / s;
            y = 0.25f * s;
            z = (m12 + m21) / s;
        } else {
            float s = (float) Math.sqrt(1f + m22 - m00 - m11) * 2f;
            w = (m10 - m01) / s;
            x = (m02 + m20) / s;
            y = (m12 + m21) / s;
            z = 0.25f * s;
        }
        q[0] = x;
        q[1] = y;
        q[2] = z;
        q[3] = w;
    }

    public static void toMatrix(float[] q, float[] out) {
        float x = q[0], y = q[1], z = q[2], w = q[3];
        out[0] = 1f - 2f * (y * y + z * z);
        out[1] = 2f * (x * y + z * w);
        out[2] = 2f * (x * z - y * w);
        out[3] = 0f;
        out[4] = 2f * (x * y - z * w);
        out[5] = 1f - 2f * (x * x + z * z);
        out[6] = 2f * (y * z + x * w);
        out[7] = 0f;
        out[8] = 2f * (x * z + y * w);
        out[9] = 2f * (y * z - x * w);
        out[10] = 1f - 2f * (x * x + y * y);
        out[11] = 0f;
        out[12] = 0f;
        out[13] = 0f;
        out[14] = 0f;
        out[15] = 1f;
    }

    public static void nlerp(float[] a, float[] b, float t, float[] out) {
        float dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3];
        float tb = dot < 0f ? -t : t;
        float ta = 1f - t;
        float x = a[0] * ta + b[0] * tb;
        float y = a[1] * ta + b[1] * tb;
        float z = a[2] * ta + b[2] * tb;
        float w = a[3] * ta + b[3] * tb;
        float n = (float) Math.sqrt(x * x + y * y + z * z + w * w);
        if (n < 1e-6f) return;
        out[0] = x / n;
        out[1] = y / n;
        out[2] = z / n;
        out[3] = w / n;
    }
}
