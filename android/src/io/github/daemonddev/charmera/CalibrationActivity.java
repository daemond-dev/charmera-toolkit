package io.github.daemonddev.charmera;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * A full-screen reference target to photograph with the camera on each firmware iteration, so colour, exposure and
 * orientation are compared against a fixed image instead of random scenes. The card carries neutral grey/white patches
 * (white balance), full-strength red/green/blue bars (so a red/blue channel swap is instantly visible), a
 * black-to-white ramp (exposure), and a large "F" with a TOP arrow (orientation — an "F" is asymmetric under both flip
 * and mirror).
 *
 * <p>Brightness is deliberately NOT forced to maximum: the camera's exposure is fixed/non-adaptive, so a full-brightness
 * screen in a dark room overexposes the shot (measured 19-29% clipped pure white, washing out the colours). It starts
 * at a moderate level and the owner tunes it with the slider to suit the camera's fixed exposure — set it low enough
 * that the white patch does not clip, then keep it the same for every shot. The level is remembered. A phone screen is
 * not a colour-calibrated reference, but at a fixed brightness it is a consistent one, which is what comparison needs.
 */
public final class CalibrationActivity extends Activity {

    private static final String PREFS = "charmera_calibration";
    private static final String KEY_BRIGHTNESS = "brightness";
    private static final float DEFAULT_BRIGHTNESS = 0.45f;   // moderate — max overexposes the fixed-exposure camera
    private static final float MIN_BRIGHTNESS = 0.05f;        // never 0 (0 means "use system brightness", not black)

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        float start = prefs.getFloat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS);
        applyBrightness(start);

        FrameLayout root = new FrameLayout(this);
        final CalibrationView card = new CalibrationView(this);
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final LinearLayout panel = buildControls(prefs, start);
        root.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        // Tapping the card hides/shows the controls, so the target can be shot clean.
        card.onTap = () -> panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);

        setContentView(root);
        card.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void applyBrightness(float b) {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(MIN_BRIGHTNESS, Math.min(1f, b));
        getWindow().setAttributes(lp);
    }

    private LinearLayout buildControls(final SharedPreferences prefs, float start) {
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        int startPct = Math.round(start * 100);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(0xCC000000);
        panel.setPadding(pad, pad, pad, pad);

        TextView instr = new TextView(this);
        instr.setTextColor(Color.WHITE);
        instr.setText("Calibration target — photograph this each flash.\n"
                + "• Dark room; turn off auto-brightness and night-light / blue filter.\n"
                + "• Fill the frame; keep the phone flat and steady.\n"
                + "• \"F\" + ▲ TOP read orientation; R/G/B read a colour swap.\n"
                + "• The camera's exposure is fixed, so set brightness low enough that the white patch does not"
                + " clip, then keep it the same for every shot. Tap the card to hide these controls.");
        panel.addView(instr);

        final TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setPadding(0, pad, 0, 0);
        label.setText("Screen brightness: " + startPct + "%");
        panel.addView(label);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(startPct);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                applyBrightness(progress / 100f);
                label.setText("Screen brightness: " + progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat(KEY_BRIGHTNESS, Math.max(MIN_BRIGHTNESS, sb.getProgress() / 100f)).apply();
            }
        });
        panel.addView(bar);
        return panel;
    }

    /** Draws the reference card; a tap calls {@link #onTap} to toggle the controls panel. */
    private static final class CalibrationView extends View {
        Runnable onTap;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);

        CalibrationView(Context c) {
            super(c);
            setBackgroundColor(Color.rgb(0x80, 0x80, 0x80));   // neutral mid-grey surround
        }

        @Override
        public boolean performClick() {
            super.performClick();
            if (onTap != null) {
                onTap.run();
            }
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
            int w = getWidth(), h = getHeight();
            float pad = Math.min(w, h) * 0.04f;
            float x = pad, right = w - pad, innerW = right - x, y = pad;

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
            float patchH = h * 0.16f;
            int[] greys = {0x00, 0x40, 0x80, 0xC0, 0xFF};
            float pw = innerW / greys.length;
            for (int i = 0; i < greys.length; i++) {
                fill.setColor(Color.rgb(greys[i], greys[i], greys[i]));
                canvas.drawRect(x + i * pw, y, x + (i + 1) * pw, y + patchH, fill);
            }
            y += patchH + pad;

            // 3) Pure R/G/B bars at full strength — a red/blue swap is then unmistakable.
            float barH = h * 0.18f;
            int[] prim = {Color.rgb(255, 0, 0), Color.rgb(0, 255, 0), Color.rgb(0, 0, 255)};
            String[] lbl = {"R", "G", "B"};
            float bw = innerW / 3f;
            for (int i = 0; i < 3; i++) {
                fill.setColor(prim[i]);
                canvas.drawRect(x + i * bw, y, x + (i + 1) * bw, y + barH, fill);
                text.setColor(Color.WHITE);
                text.setTextAlign(Paint.Align.CENTER);
                text.setTextSize(barH * 0.30f);
                canvas.drawText(lbl[i], x + (i + 0.5f) * bw, y + barH * 0.60f, text);
            }
            y += barH + pad;

            // 4) Black-to-white exposure ramp.
            float rampH = h * 0.14f;
            fill.setShader(new LinearGradient(x, 0, right, 0, Color.BLACK, Color.WHITE, Shader.TileMode.CLAMP));
            canvas.drawRect(x, y, right, y + rampH, fill);
            fill.setShader(null);
        }
    }
}
