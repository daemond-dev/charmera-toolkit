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
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
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
 * <p>The activity is pinned to {@code reverseLandscape} in the manifest so the phone can be set down flipped 180°
 * (easier to prop against the camera) and still photograph upright.
 *
 * <p>Brightness is deliberately NOT forced to maximum: the camera's exposure is fixed/non-adaptive, so a full-brightness
 * screen in a dark room overexposes the shot (measured 19-29% clipped pure white, washing out the colours). It starts
 * at a moderate level and the owner tunes it with the slider to suit the camera's fixed exposure — set it low enough
 * that the white patch does not clip, then keep it the same for every shot. The chosen level is remembered AND printed
 * onto the card itself (inside the camera frame), so every capture records the brightness it was shot at and two shots
 * taken at different levels can never be mixed up. A phone screen is not a colour-calibrated reference, but at a fixed
 * brightness it is a consistent one, which is what comparison needs.
 *
 * <p>The controls (brightness readout, slider, Done) sit in their own reserved strip that is always on screen; only the
 * instruction text collapses, via the {@code ⓘ} button.
 */
public final class CalibrationActivity extends Activity {

    private static final String PREFS = "charmera_calibration";
    private static final String KEY_BRIGHTNESS = "brightness";
    private static final float DEFAULT_BRIGHTNESS = 0.45f;   // moderate — max overexposes the fixed-exposure camera
    private static final float MIN_BRIGHTNESS = 0.05f;        // never 0 (0 means "use system brightness", not black)

    private CalibrationView card;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        float start = prefs.getFloat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS);
        applyBrightness(start);

        // Vertical stack: the card fills all space above a reserved, always-visible controls strip (no overlap).
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        card = new CalibrationView(this);
        card.setBrightnessPct(Math.round(start * 100));
        root.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(buildControls(prefs, start), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        root.setSystemUiVisibility(
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

        // Instruction text — the ONLY element that hides (toggled by the ⓘ button below).
        final TextView instr = new TextView(this);
        instr.setTextColor(Color.WHITE);
        instr.setText("Photograph this each flash — dark room, auto-brightness and night-light off, fill the frame with"
                + " the card. Set brightness low enough that the white patch doesn't clip, then keep it there for every"
                + " shot. The level is printed on the card, so each photo records the brightness it was taken at.");
        instr.setPadding(0, 0, 0, pad);
        panel.addView(instr);

        // Always-visible control row: Done | brightness readout | slider | instructions toggle.
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        final TextView done = new TextView(this);
        done.setText("✕ Done");
        done.setTextColor(Color.WHITE);
        done.setTypeface(Typeface.DEFAULT_BOLD);
        done.setPadding(0, 0, pad, 0);
        done.setOnClickListener(v -> finish());
        row.addView(done);

        final TextView label = new TextView(this);
        label.setTextColor(Color.WHITE);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setText("Brightness: " + startPct + "%");
        label.setPadding(0, 0, pad, 0);
        row.addView(label);

        final SeekBar bar = new SeekBar(this);
        bar.setMax(100);
        bar.setProgress(startPct);
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final TextView toggle = new TextView(this);
        toggle.setText("ⓘ Hide");
        toggle.setTextColor(0xFFFFD400);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setPadding(pad, 0, 0, 0);
        toggle.setOnClickListener(v -> {
            boolean show = instr.getVisibility() != View.VISIBLE;
            instr.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "ⓘ Hide" : "ⓘ Help");
        });
        row.addView(toggle);

        panel.addView(row);

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                applyBrightness(progress / 100f);
                label.setText("Brightness: " + progress + "%");
                card.setBrightnessPct(progress);   // keep the in-frame stamp in sync
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                prefs.edit().putFloat(KEY_BRIGHTNESS, Math.max(MIN_BRIGHTNESS, sb.getProgress() / 100f)).apply();
            }
        });
        return panel;
    }

    /** Draws the reference card, including the current brightness stamped inside the camera frame. */
    private static final class CalibrationView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private int brightnessPct;

        CalibrationView(Context c) {
            super(c);
            setBackgroundColor(Color.rgb(0x80, 0x80, 0x80));   // neutral mid-grey surround
        }

        void setBrightnessPct(int pct) {
            if (pct != brightnessPct) {
                brightnessPct = pct;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            float pad = Math.min(w, h) * 0.04f;
            float x = pad, right = w - pad, innerW = right - x, y = pad;

            // 1) Orientation marker ("F" + TOP arrow) plus the brightness stamp, all on one black header bar so the
            //    capture records both orientation and the brightness it was shot at.
            float markH = h * 0.18f;
            fill.setColor(Color.BLACK);
            fill.setStyle(Paint.Style.FILL);
            canvas.drawRect(x, y, right, y + markH, fill);
            text.setColor(Color.WHITE);
            text.setTypeface(Typeface.DEFAULT_BOLD);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(markH * 0.72f);
            canvas.drawText("F", x + pad, y + markH * 0.80f, text);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(markH * 0.40f);
            canvas.drawText("BRIGHT " + brightnessPct + "%", (x + right) / 2f, y + markH * 0.64f, text);
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
