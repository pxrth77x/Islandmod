package com.example.island;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // Your collapsed pill settings
    private static final int PILL_WIDTH_DP = 110;
    private static final int PILL_HEIGHT_DP = 30;
    private static final int TOP_MARGIN_DP = 10;

    // Expanded island size
    private static final int EXPANDED_W_DP = 330;
    private static final int EXPANDED_H_DP = 92;
    private static final long ANIM_MS = 550;
    private static final long CHARGE_SHOW_MS = 3500;

    // Window layer above the status bar so the island receives taps
    private static final int WINDOW_TYPE = 2024; // TYPE_NAVIGATION_BAR_PANEL

    private static final int GREEN = 0xFF30D158;
    private static final int RED = 0xFFFF453A;

    private static boolean added = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Context ctx = (Context) param.thisObject;
                new Handler(Looper.getMainLooper()).post(() -> addIsland(ctx));
            }
        });
    }

    private static boolean isLandscape(Configuration c) {
        return c.orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    private static void readBattery(Intent i, BatteryContent content) {
        int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        boolean plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (level >= 0 && scale > 0) content.set(level * 100 / scale, plugged);
    }

    private static void addIsland(Context ctx) {
        if (added) return;
        try {
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            float d = ctx.getResources().getDisplayMetrics().density;

            int w0 = (int) (PILL_WIDTH_DP * d);
            int h0 = (int) (PILL_HEIGHT_DP * d);
            int y0 = (int) (TOP_MARGIN_DP * d);
            int w1 = (int) (EXPANDED_W_DP * d);
            int h1 = (int) (EXPANDED_H_DP * d);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.BLACK);
            bg.setCornerRadius(h0 / 2f);

            final BatteryContent content = new BatteryContent(ctx, d);
            content.setAlpha(0f);

            final FrameLayout root = new FrameLayout(ctx);
            root.setBackground(bg);
            root.addView(content, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    w0, h0,
                    WINDOW_TYPE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = y0;
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("IslandPill");

            final Island island = new Island(wm, root, content, bg, lp, d,
                    w0, h0, y0, w1, h1);
            root.setOnClickListener(v -> {
                XposedBridge.log("IslandMod: tap");
                island.toggle();
            });

            wm.addView(root, lp);
            root.setVisibility(isLandscape(ctx.getResources().getConfiguration())
                    ? View.GONE : View.VISIBLE);

            // Hide in landscape and show again in portrait
            final Handler main = new Handler(Looper.getMainLooper());
            ctx.registerComponentCallbacks(new ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                    final boolean land = isLandscape(newConfig);
                    main.post(() -> root.setVisibility(land ? View.GONE : View.VISIBLE));
                }

                @Override
                public void onLowMemory() {
                }
            });

            // Charging: expand when the charger is plugged in
            try {
                BroadcastReceiver rcv = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        String a = i.getAction();
                        if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                            readBattery(i, content);
                        } else if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                            XposedBridge.log("IslandMod: power connected");
                            content.setPlugged(true);
                            island.showEvent(CHARGE_SHOW_MS);
                        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                            XposedBridge.log("IslandMod: power disconnected");
                            content.setPlugged(false);
                        }
                    }
                };
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_BATTERY_CHANGED);
                f.addAction(Intent.ACTION_POWER_CONNECTED);
                f.addAction(Intent.ACTION_POWER_DISCONNECTED);
                Intent sticky = ctx.registerReceiver(rcv, f, Context.RECEIVER_EXPORTED);
                if (sticky != null) readBattery(sticky, content);
            } catch (Throwable t) {
                XposedBridge.log("IslandMod: receiver " + t);
            }

            added = true;
            XposedBridge.log("IslandMod: island added, window type " + WINDOW_TYPE);
        } catch (Throwable t) {
            XposedBridge.log("IslandMod: " + t);
        }
    }

    /** Controls the island size and animation. */
    private static class Island {
        final WindowManager wm;
        final FrameLayout root;
        final View content;
        final GradientDrawable bg;
        final WindowManager.LayoutParams lp;
        final float d;
        final int w0, h0, y0, w1, h1;
        final Handler h = new Handler(Looper.getMainLooper());
        final Runnable collapse = () -> setExpanded(false);
        boolean expanded = false;
        float progress = 0f;
        ValueAnimator anim;

        Island(WindowManager wm, FrameLayout root, View content, GradientDrawable bg,
               WindowManager.LayoutParams lp, float d,
               int w0, int h0, int y0, int w1, int h1) {
            this.wm = wm; this.root = root; this.content = content; this.bg = bg;
            this.lp = lp; this.d = d;
            this.w0 = w0; this.h0 = h0; this.y0 = y0;
            this.w1 = w1; this.h1 = h1;
        }

        void toggle() {
            h.removeCallbacks(collapse);
            setExpanded(!expanded);
        }

        /** Expand for a moment, then collapse by itself. */
        void showEvent(long ms) {
            if (root.getVisibility() != View.VISIBLE) return;
            h.removeCallbacks(collapse);
            setExpanded(true);
            h.postDelayed(collapse, ms);
        }

        void setExpanded(boolean e) {
            expanded = e;
            if (anim != null) anim.cancel();
            final float from = progress;
            final float to = e ? 1f : 0f;
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setDuration(ANIM_MS);
            anim.setInterpolator(new SpringInterpolator());
            anim.addUpdateListener(a -> {
                float f = (float) a.getAnimatedValue();
                if (a.getAnimatedFraction() >= 1f) f = 1f;
                apply(from + (to - from) * f);
            });
            anim.start();
        }

        void apply(float p) {
            progress = p;
            lp.width = Math.max(1, (int) (w0 + (w1 - w0) * p));
            lp.height = Math.max(1, (int) (h0 + (h1 - h0) * p));
            lp.y = y0;
            bg.setCornerRadius(Math.min(lp.height / 2f, 38f * d));
            float a = (p - 0.55f) / 0.45f;
            content.setAlpha(Math.max(0f, Math.min(1f, a)));
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Battery ring, title, status and percentage shown in the expanded island. */
    private static class BatteryContent extends LinearLayout {
        final BatteryRing ring;
        final TextView title, sub, pct;
        int level = 0;
        boolean plugged = false;

        BatteryContent(Context c, float d) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) (24 * d);
            setPadding(pad, 0, pad, 0);

            ring = new BatteryRing(c, d);
            addView(ring, new LinearLayout.LayoutParams((int) (44 * d), (int) (44 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            sub = new TextView(c);
            sub.setTextColor(0xB3FFFFFF);
            sub.setTextSize(12f);
            texts.addView(title);
            texts.addView(sub);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (14 * d);
            addView(texts, tp);

            pct = new TextView(c);
            pct.setTextSize(24f);
            pct.setTypeface(Typeface.DEFAULT_BOLD);
            addView(pct, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            refresh();
        }

        void set(int level, boolean plugged) {
            this.level = level;
            this.plugged = plugged;
            refresh();
        }

        void setPlugged(boolean plugged) {
            this.plugged = plugged;
            refresh();
        }

        void refresh() {
            ring.set(level, plugged);
            title.setText(plugged ? "Charging" : "Battery");
            if (plugged && level >= 100) sub.setText("Fully charged");
            else if (plugged) sub.setText("Plugged in");
            else if (level <= 15) sub.setText("Low battery");
            else sub.setText("On battery");
            pct.setText(level + "%");
            pct.setTextColor(plugged ? GREEN : (level <= 15 ? RED : Color.WHITE));
        }
    }

    /** Circular battery gauge with a lightning bolt while charging. */
    private static class BatteryRing extends View {
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint bolt = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final Path path = new Path();
        final float stroke;
        int level = 0;
        boolean charging = false;

        BatteryRing(Context c, float d) {
            super(c);
            stroke = 4f * d;
            track.setStyle(Paint.Style.STROKE);
            track.setStrokeWidth(stroke);
            track.setColor(0x33FFFFFF);
            arc.setStyle(Paint.Style.STROKE);
            arc.setStrokeWidth(stroke);
            arc.setStrokeCap(Paint.Cap.ROUND);
            bolt.setStyle(Paint.Style.FILL);
            bolt.setColor(GREEN);
        }

        void set(int level, boolean charging) {
            this.level = level;
            this.charging = charging;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float h = getHeight();
            float half = stroke / 2f;
            rect.set(half, half, w - half, h - half);
            c.drawArc(rect, 0f, 360f, false, track);

            arc.setColor(charging ? GREEN : (level <= 15 ? RED : Color.WHITE));
            c.drawArc(rect, -90f, 360f * level / 100f, false, arc);

            if (charging) {
                float cx = w / 2f;
                float cy = h / 2f;
                float s = w * 0.17f;
                path.reset();
                path.moveTo(cx + 0.3f * s, cy - 1.3f * s);
                path.lineTo(cx - 0.7f * s, cy + 0.15f * s);
                path.lineTo(cx - 0.05f * s, cy + 0.15f * s);
                path.lineTo(cx - 0.3f * s, cy + 1.3f * s);
                path.lineTo(cx + 0.7f * s, cy - 0.15f * s);
                path.lineTo(cx + 0.05f * s, cy - 0.15f * s);
                path.close();
                c.drawPath(path, bolt);
            }
        }
    }

    /** Damped spring with a small overshoot, similar to iOS. */
    private static class SpringInterpolator implements TimeInterpolator {
        @Override
        public float getInterpolation(float t) {
            return (float) (1.0 - Math.exp(-8.0 * t) * Math.cos(2.0 * Math.PI * 1.2 * t));
        }
    }
}
