package cc.joycreator.textlab;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/** Where the light is, -1..1 each way: a little pad with a cyan dot to drag (Studio's Look row and the TextLab room). */
public final class TlLightPad extends View {
    public interface Moved {
        void moved(float x, float y, boolean done);
    }

    @Nullable public Moved onMoved;
    private float x, y;
    private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG);

    public TlLightPad(Context c) {
        super(c);
        frame.setStyle(Paint.Style.STROKE);
        frame.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, c.getResources().getDisplayMetrics()));
        frame.setColor(0xFF3F3F46);
        dot.setColor(0xFF22D3EE);
        setContentDescription(c.getString(com.fadcam.R.string.textlab_light_pad));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) setTooltipText(c.getString(com.fadcam.R.string.textlab_light_pad));
    }

    public void set(float x, float y) {
        this.x = x;
        this.y = y;
        invalidate();
    }

    @Override
    protected void onDraw(@NonNull Canvas c) {
        float r = getHeight() * 0.12f;
        c.drawRoundRect(1, 1, getWidth() - 1, getHeight() - 1, r, r, frame);
        float cx = (x + 1) / 2 * getWidth(), cy = (1 - (y + 1) / 2) * getHeight();
        c.drawCircle(cx, cy, Math.max(4, getHeight() * 0.11f), dot);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float nx = Math.max(-1, Math.min(1, e.getX() / Math.max(1, getWidth()) * 2 - 1));
        float ny = Math.max(-1, Math.min(1, 1 - e.getY() / Math.max(1, getHeight()) * 2));
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                set(nx, ny);
                if (onMoved != null) onMoved.moved(nx, ny, false);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                set(nx, ny);
                if (onMoved != null) onMoved.moved(nx, ny, true);
                return true;
            default:
                return true;
        }
    }
}
