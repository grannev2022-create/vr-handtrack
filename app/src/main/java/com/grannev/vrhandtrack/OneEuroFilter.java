package com.grannev.vrhandtrack;

/**
 * One Euro Filter: убирает дрожание, когда рука почти стоит,
 * и почти не добавляет задержку, когда рука двигается быстро.
 */
final class OneEuroFilter {
    private final float minCutoff, beta, dCutoff;
    private float xPrev, dxPrev;
    private double tPrev;
    private boolean init = false;

    OneEuroFilter(float minCutoff, float beta, float dCutoff) {
        this.minCutoff = minCutoff;
        this.beta = beta;
        this.dCutoff = dCutoff;
    }

    float filter(float x, double tSeconds) {
        if (!init) {
            init = true;
            xPrev = x;
            dxPrev = 0f;
            tPrev = tSeconds;
            return x;
        }
        float dt = (float) (tSeconds - tPrev);
        if (dt <= 1e-4f) return xPrev;
        tPrev = tSeconds;

        float dx = (x - xPrev) / dt;
        float aD = alpha(dCutoff, dt);
        float dxHat = aD * dx + (1f - aD) * dxPrev;

        float cutoff = minCutoff + beta * Math.abs(dxHat);
        float a = alpha(cutoff, dt);
        float xHat = a * x + (1f - a) * xPrev;

        xPrev = xHat;
        dxPrev = dxHat;
        return xHat;
    }

    void reset() {
        init = false;
    }

    private static float alpha(float cutoff, float dt) {
        float tau = 1f / (2f * (float) Math.PI * cutoff);
        return 1f / (1f + tau / dt);
    }
}
