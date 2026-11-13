package com.grannev.vrhandtrack;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

public final class HandSilhouette {
    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    static {
        paint.setColor(Color.BLACK); // Тёмный силуэт
        paint.setStyle(Paint.Style.FILL_AND_STROKE);
        paint.setStrokeWidth(20f);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    public static void draw(Canvas canvas, float[] lm, int w, int h) {
        if (lm == null || lm.length < 42) return;
        int[][] bones = {
            {0, 1}, {1, 2}, {2, 3}, {3, 4}, {0, 5}, {5, 6}, {6, 7}, {7, 8},
            {5, 9}, {9, 10}, {10, 11}, {11, 12}, {9, 13}, {13, 14}, {14, 15}, {15, 16},
            {13, 17}, {17, 18}, {18, 19}, {19, 20}, {0, 17}
        };
        for (int[] b : bones) {
            canvas.drawLine(lm[b[0]*2]*w, lm[b[0]*2+1]*h, lm[b[1]*2]*w, lm[b[1]*2+1]*h, paint);
        }
        for (int i = 0; i < 21; i++) {
            canvas.drawCircle(lm[i*2]*w, lm[i*2+1]*h, 12f, paint);
        }
    }
}
