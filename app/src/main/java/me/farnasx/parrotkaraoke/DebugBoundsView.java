package me.farnasx.parrotkaraoke;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * TEMPORARY in-car diagnostic overlay (not for release).
 *
 * <p>Draws a red rectangle exactly where this view's coordinate space says the
 * "current" lyric box lives, plus a one-line yellow readout of the live text
 * metrics. A photo of the running app then shows, against the visible clip
 * position, exactly where the box edges are and whether the rendered lines
 * overflow the box. Remove together with its wiring in MainActivity once the
 * in-car layout issue is understood.
 */
public class DebugBoundsView extends View {

    private final RectF box = new RectF();
    private String info = "";

    public DebugBoundsView(Context context) {
        super(context);
        setWillNotDraw(false);
    }

    public void update(RectF r, String info) {
        if (r != null) {
            box.set(r);
        }
        this.info = (info == null) ? "" : info;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(0xFFFF2222);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2.5f);
        canvas.drawRect(box, boxPaint);

        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(0xFFFFFF22);
        textPaint.setTextSize(22f);
        canvas.drawText(info, 10f, getHeight() - 12f, textPaint);
    }
}
