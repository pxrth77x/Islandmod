package com.example.island;

import android.app.Application;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // Tweak these to fit your Nothing Phone 3a camera cutout
    private static final int PILL_WIDTH_DP = 110;
    private static final int PILL_HEIGHT_DP = 32;
    private static final int TOP_MARGIN_DP = 6;

    private static boolean added = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Context ctx = (Context) param.thisObject;
                new Handler(Looper.getMainLooper()).post(() -> addPill(ctx));
            }
        });
    }

    private static void addPill(Context ctx) {
        if (added) return;
        try {
            float d = ctx.getResources().getDisplayMetrics().density;

            View pill = new View(ctx);
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(0xFF000000);
            bg.setCornerRadius(PILL_HEIGHT_DP * d / 2f);
            pill.setBackground(bg);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    (int) (PILL_WIDTH_DP * d), (int) (PILL_HEIGHT_DP * d),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = (int) (TOP_MARGIN_DP * d);
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("IslandPill");

            ctx.getSystemService(WindowManager.class).addView(pill, lp);
            added = true;
        } catch (Throwable t) {
            XposedBridge.log("IslandMod: " + t);
        }
    }
}
