package com.example.island;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
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

    private static boolean isLandscape(Context ctx) {
        return ctx.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
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

            TextView label = new TextView(ctx);
            label.setText("Dynamic Island");
            label.setTextColor(Color.WHITE);
            label.setTextSize(15f);
            label.setGravity(Gravity.CENTER);
            label.setAlpha(0f);

            // Hides itself in landscape, shows again in portrait
            FrameLayout root = new FrameLayout(ctx) {
                @Override
                protected void onConfigurationChanged(Configuration newConfig) {
                    super.onConfigurationChanged(newConfig);
                    setVisibility(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
                            ? View.GONE : View.VISIBLE);
                }
            };
            root.setBackground(bg);
            root.addView(label, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    w0, h0,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = y0;
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("IslandPill");

            final Island island = new Island(wm, root, label, bg, lp, d,
                    w0, h0, y0, w1, h1);
            root.setOnClickListener(v -> island.toggle());

            wm.addView(root, lp);
            root.setVisibility(isLandscape(ctx) ? View.GONE : View.VISIBLE);
            added = true;
        } catch (Throwable t) {
            XposedBridge.log("IslandMod: " + t);
        }
    }

    /** Controls the island size and animation. */
    private static class Island {
        final WindowManager wm;
        final FrameLayout root;
        final TextView label;
        final GradientDrawable bg;
        final WindowManager.LayoutParams lp;
        final float d;
        final int w0, h0, y0, w1, h1;
        boolean expanded = false;
        float progress = 0f;
        ValueAnimator anim;

        Island(WindowManager wm, FrameLayout root, TextView label, GradientDrawable bg,
               WindowManager.LayoutParams lp, float d,
               int w0, int h0, int y0, int w1, int h1) {
            this.wm = wm; this.root = root; this.label = label; this.bg = bg;
            this.lp = lp; this.d = d;
            this.w0 = w0; this.h0 = h0; this.y0 = y0;
            this.w1 = w1; this.h1 = h1;
        }

        void toggle() {
            setExpanded(!expanded);
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
            label.setAlpha(Math.max(0f, Math.min(1f, a)));
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignored) {
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
