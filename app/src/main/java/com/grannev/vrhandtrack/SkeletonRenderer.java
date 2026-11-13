package com.grannev.vrhandtrack;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;

public class SkeletonRenderer {
    private final Paint linePaint = new Paint();
    private final Paint pointPaint = new Paint();
    private final Paint pinchPaint = new Paint();

    private static final int[][] CONNECTIONS = {
        {0,1},{1,2},{2,3},{3,4}, {0,5},{5,6},{6,7},{7,8},
        {5,9},{9,10},{10,11},{11,12}, {9,13},{13,14},{14,15},{15,16},
        {13,17},{17,18},{18,19},{19,20}, {0,17}
    };

    public SkeletonRenderer() {
        linePaint.setColor(Color.GREEN);
        linePaint.setStrokeWidth(5f);
        linePaint.setStyle(Paint.Style.STROKE);
        pointPaint.setColor(Color.CYAN);
        pointPaint.setStyle(Paint.Style.FILL);
        pinchPaint.setColor(Color.RED);
        pinchPaint.setStyle(Paint.Style.FILL);
    }

    public void clear(Canvas canvas) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
    }

    public void drawSkeleton(Canvas canvas, float[][] points, boolean isPinching, float offsetX, float offsetY, int viewW, int viewH) {
        if (points == null) return;
        for (int[] conn : CONNECTIONS) {
            canvas.drawLine(offsetX + points[conn[0]][0] * viewW, offsetY + points[conn[0]][1] * viewH,
                    offsetX + points[conn[1]][0] * viewW, offsetY + points[conn[1]][1] * viewH, linePaint);
        }
        for (int i = 0; i < 21; i++) {
            float radius = (i == 4 || i == 8) ? 12f : 6f;
            if (isPinching && (i == 4 || i == 8)) {
                canvas.drawCircle(offsetX + points[i][0] * viewW, offsetY + points[i][1] * viewH, radius * 2, pinchPaint);
            } else {
                canvas.drawCircle(offsetX + points[i][0] * viewW, offsetY + points[i][1] * viewH, radius, pointPaint);
            }
        }
    }
}
