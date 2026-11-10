package io.github.daemonddev.charmera;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * A full-screen reference target to photograph with the camera on every firmware iteration, so colour, exposure and
 * orientation are compared against a fixed image instead of random scenes. It locks the phone's brightness to maximum
 * and keeps the screen on; the card carries neutral grey/white patches (white balance), full-strength red/green/blue
 * bars (so a red/blue channel swap is instantly visible), a black-to-white ramp (exposure), and a large "F" with a TOP
 * arrow (orientation — an "F" is asymmetric under both flip and mirror). A phone screen is not a colour-calibrated
 * reference, but it is a consistent one, which is what comparing iterations needs. Tap toggles the instructions.
 */
public final class CalibrationActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 1f;                 // full brightness, so the target is lit the same way every time
        getWindow().setAttributes(lp);
        CalibrationView view = new CalibrationView(this);
        setContentView(view);
        view.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    /** Draws the reference card and a dismissible instruction overlay; a tap toggles the overlay. */
    private static final class CalibrationView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean showHelp = true;

        CalibrationView(Context c) {
            super(c);
            setBackgroundColor(Color.rgb(0x80, 0x80, 0x80));   // neutral mid-grey surround
        }

        @Override
        public boolean performClick() {
            super.performClick();
            showHelp = !showHelp;
            invalidate();
            return true;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (e.getAction() == MotionEvent.ACTION_UP) {
                performClick();
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            float pad = Math.min(w, h) * 0.04f;
            float x = pad, right = w - pad, innerW = right - x;
            float y = pad;

            // 1) Orientation marker: a bold "F" plus a TOP arrow — an "F" is asymmetric under both flip and mirror.
            float markH = h * 0.18f;
            fill.setColor(Color.BLACK);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawRect(x, y, right, y + markH, fill);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(markH * 0.72f);
            canvas.drawText("F", x + pad, y + markH * 0.80f, text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(markH * 0.26f);
            canvas.drawText("▲ TOP", right - pad, y + markH * 0.46f, text);
            canvas.drawText("keep upright", right - pad, y + markH * 0.82f, text);
            y += markH + pad;

            // 2) White-balance / exposure patches: black, greys, white.
            float patchH = h * 0.14f;
            int[] greys = {0x00, 0x40, 0x80, 0xC0, 0xFF};
            float pw = innerW / greys.length;
            for (int i = 0; i < greys.length; i++) {
                fill.setColor(Color.rgb(greys[i], greys[i], greys[i]));
                canvas.drawRect(x + i * pw, y, x + (i + 1) * pw, y + patchH, fill);
            }
            y += patchH + pad;

            // 3) Pure R/G/B bars at full strength — a red/blue swap is then unmistakable.
            float barH = h * 0.16f;
            int[] prim = {Color.rgb(255, 0, 0), Color.rgb(0, 255, 0), Color.rgb(0, 0, 255)};
            String[] lbl = {"R", "G", "B"};
            float bw = innerW / 3f;
            for (int i = 0; i < 3; i++) {
                fill.setColor(prim[i]);
                canvas.drawRect(x + i * bw, y, x + (i + 1) * bw, y + barH, fill);
                text.setColor(Color.WHITE);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(barH * 0.32f);
                canvas.drawText(lbl[i], x + (i + 0.5f) * bw, y + barH * 0.64f, text);
            }
            y += barH + pad;

            // 4) Black-to-white exposure ramp.
            float rampH = h * 0.12f;
            fill.setShader(new LinearGradient(x, 0, right, 0, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP));
            canvas.drawRect(x, y, right, y + rampH, fill);
            fill.setShader(null);

            // Instruction overlay — tap anywhere to hide, tap again to show.
            if (showHelp) {
                fill.setColor(0xCC000000);
                canvas.drawRect(0, h * 0.70f, w, h, fill);
                text.setColor(Color.WHITE);
                text.setTextAlign(Paint.Align.LEFT);
                float ts = Math.min(w, h) * 0.034f;
                text.setTextSize(ts);
                String[] lines = {
                        "Calibration target — photograph this each flash",
                        "• Turn OFF auto-brightness and night-light / blue filter",
                        "• Shoot in a dark room, the screen as the only light",
                        "• Fill the frame; keep the phone flat and steady",
                        "• \"F\" + ▲ TOP read orientation; R/G/B read a colour swap",
                        "",
                        "Tap anywhere to hide this — tap again to bring it back",
                };
                float ly = h * 0.74f;
                float lh = ts * 1.5f;
                for (String line : lines) {
                    canvas.drawText(line, pad, ly, text);
                    ly += lh;
                }
            }
        }
    }
}
