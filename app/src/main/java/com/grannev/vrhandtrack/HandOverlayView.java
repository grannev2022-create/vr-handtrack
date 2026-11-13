package com.grannev.vrhandtrack;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.view.View;

import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.List;

public class HandOverlayView extends View {

    private static final float MIN_CUTOFF = 1.0f;
    private static final float BETA = 15f;
    private static final int MISSED_FRAMES_LIMIT = 3;

    private static final int[][] BONES = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4},
            {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {5, 9}, {9, 10}, {10, 11}, {11, 12},
            {9, 13}, {13, 14}, {14, 15}, {15, 16},
            {13, 17}, {17, 18}, {18, 19}, {19, 20},
            {0, 17}
    };
    private static final int[] TIPS = {4, 8, 12, 16, 20};
    private static final int[] HAND_COLORS = {0xFF00E5FF, 0xFFFF9100};

    private final Object lock = new Object();
    private final OneEuroFilter[][] filters = new OneEuroFilter[2][63];
    private final float[][] target = new float[2][63];
    private final boolean[] present = new boolean[2];
    private final int[] missed = new int[2];
    private int imgW = 1, imgH = 1;

    private final float[][] snap = new float[2][63];
    private final boolean[] vis = new boolean[2];
    private final float[] px = new float[21], py = new float[21];

    private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint jointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tipPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public HandOverlayView(Context context) {
        super(context);
        for (int s = 0; s < 2; s++)
            for (int i = 0; i < 63; i++)
                filters[s][i] = new OneEuroFilter(MIN_CUTOFF, BETA, 1.0f);

        bonePaint.setStrokeWidth(7f);
        bonePaint.setStrokeCap(Paint.Cap.ROUND);
        tipPaint.setColor(Color.WHITE);
        setWillNotDraw(false);
    }

    public void onHands(HandLandmarkerResult result, int w, int h) {
        double t = result.timestampMs() / 1000.0;
        boolean[] seen = new boolean[2];

        synchronized (lock) {
            imgW = w;
            imgH = h;
            List<List<NormalizedLandmark>> all = result.landmarks();
            List<List<Category>> hands = result.handedness();

            for (int i = 0; i < all.size() && i < 2; i++) {
                int slot = "Left".equals(hands.get(i).get(0).categoryName()) ? 0 : 1;
                if (seen[slot]) slot = 1 - slot;
                seen[slot] = true;

                List<NormalizedLandmark> lm = all.get(i);
                for (int k = 0; k < 21; k++) {
                    NormalizedLandmark p = lm.get(k);
                    target[slot][k * 3] = filters[slot][k * 3].filter(p.x(), t);
                    target[slot][k * 3 + 1] = filters[slot][k * 3 + 1].filter(p.y(), t);
                    target[slot][k * 3 + 2] = filters[slot][k * 3 + 2].filter(p.z(), t);
                }
                present[slot] = true;
                missed[slot] = 0;
            }

            for (int s = 0; s < 2; s++) {
                if (seen[s]) continue;
                if (++missed[s] > MISSED_FRAMES_LIMIT) {
                    present[s] = false;
                    for (OneEuroFilter f : filters[s]) f.reset();
                }
            }
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);

        int iw, ih;
        synchronized (lock) {
            iw = imgW;
            ih = imgH;
            for (int s = 0; s < 2; s++) {
                vis[s] = present[s];
                if (present[s]) System.arraycopy(target[s], 0, snap[s], 0, 63);
            }
        }

        int W = getWidth(), H = getHeight();
        float scale = Math.min((float) W / iw, (float) H / ih);
        float offY = (H - ih * scale) / 2f;
        float offX = (W - iw * scale) / 2f;

        for (int s = 0; s < 2; s++) {
            if (!vis[s]) continue;
            float[] p = snap[s];
            for (int j = 0; j < 21; j++) {
                px[j] = offX + p[j * 3] * iw * scale;
                py[j] = offY + p[j * 3 + 1] * ih * scale;
            }
            drawHand(canvas, s);
        }

        postInvalidateOnAnimation();
    }

    private void drawHand(Canvas c, int slot) {
        int color = HAND_COLORS[slot];
        bonePaint.setColor(color);
        jointPaint.setColor(color);
        for (int[] b : BONES) c.drawLine(px[b[0]], py[b[0]], px[b[1]], py[b[1]], bonePaint);
        for (int j = 0; j < 21; j++) c.drawCircle(px[j], py[j], 9f, jointPaint);
        for (int t : TIPS) c.drawCircle(px[t], py[t], 13f, tipPaint);
    }
}
