package com.example.island;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

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

    // Expanded card size
    private static final int CARD_MARGIN_DP = 14;
    private static final int H_MEDIA_DP = 176;
    private static final int H_SMALL_DP = 96;

    private static final long CHARGE_SHOW_MS = 3500;
    private static final long MEDIA_SHOW_MS = 4500;
    private static final long RECORD_SHOW_MS = 3000;
    private static final long TAP_SHOW_MS = 8000;

    // Window layer above the status bar so the island receives taps
    private static final int WINDOW_TYPE = 2024; // TYPE_NAVIGATION_BAR_PANEL

    private static final int GREEN = 0xFF30D158;
    private static final int RED = 0xFFFF453A;
    private static final int DEFAULT_ACCENT = 0xFF8E8E93;
    private static final int GLASS = 0xF4151517;

    private static final int MODE_BATTERY = 0;
    private static final int MODE_MEDIA = 1;
    private static final int MODE_CALL = 2;
    private static final int MODE_RECORD = 3;

    private static final int CALL_NONE = 0;
    private static final int CALL_RINGING = 1;
    private static final int CALL_ONGOING = 2;

    private static Handler mainHandler;
    private static boolean added = false;
    private static Island theIsland;
    private static Context appCtx;
    private static String recKey;

    /** Created only when first used, never while the class loads. */
    private static Handler main() {
        if (mainHandler == null) mainHandler = new Handler(Looper.getMainLooper());
        return mainHandler;
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final Context ctx = (Context) param.thisObject;
                main().post(() -> addIsland(ctx));
            }
        });

        hookNotifications(lpparam.classLoader);
    }

    // ------------------------------------------------------------ helpers

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long sec = s % 60;
        return (s / 60) + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private static String fmtMedia(long ms) {
        long s = Math.max(0, ms / 1000);
        return two(s / 60) + ":" + two(s % 60);
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    private static int lerpColor(int a, int b, float t) {
        t = clamp01(t);
        int al = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
        int r = (int) (Color.red(a) + (Color.red(b) - Color.red(a)) * t);
        int g = (int) (Color.green(a) + (Color.green(b) - Color.green(a)) * t);
        int bl = (int) (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t);
        return Color.argb(al, r, g, bl);
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
    }

    /** Picks a vivid colour from album art for the glow. */
    private static int accentOf(Bitmap src) {
        try {
            if (src == null) return DEFAULT_ACCENT;
            Bitmap b = src;
            if (b.getConfig() == Bitmap.Config.HARDWARE) {
                b = b.copy(Bitmap.Config.ARGB_8888, false);
            }
            if (b == null) return DEFAULT_ACCENT;
            Bitmap s = Bitmap.createScaledBitmap(b, 24, 24, true);
            int[] px = new int[24 * 24];
            s.getPixels(px, 0, 24, 0, 0, 24, 24);
            float[] hsv = new float[3];
            float best = 0f;
            int bestColor = DEFAULT_ACCENT;
            for (int c : px) {
                Color.colorToHSV(c, hsv);
                float score = hsv[1] * hsv[2];
                if (score > best) {
                    best = score;
                    bestColor = c;
                }
            }
            if (best < 0.12f) return 0xFF9A9AA6;
            Color.colorToHSV(bestColor, hsv);
            hsv[1] = Math.min(1f, Math.max(hsv[1], 0.55f));
            hsv[2] = Math.max(hsv[2], 0.9f);
            return Color.HSVToColor(hsv);
        } catch (Throwable t) {
            return DEFAULT_ACCENT;
        }
    }

    private static PendingIntent findAction(Notification n, String[] keywords,
                                            boolean firstFallback) {
        if (n.actions == null) return null;
        for (Notification.Action a : n.actions) {
            if (a == null || a.title == null) continue;
            String t = a.title.toString().toLowerCase();
            for (String k : keywords) {
                if (t.contains(k)) return a.actionIntent;
            }
        }
        if (firstFallback && n.actions.length > 0 && n.actions[0] != null) {
            return n.actions[0].actionIntent;
        }
        return null;
    }

    /** The real recording notification: ongoing, has a Stop action and no progress bar. */
    private static boolean isRecordingNotif(Notification n) {
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) == 0) return false;
        Bundle ex = n.extras;
        if (ex != null) {
            if (ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return false;
            if (ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return false;
        }
        return n.actions != null && n.actions.length > 0;
    }

    private interface SeekListener {
        void onSeek(float fraction);
    }

    // ------------------------------------------------------- calls and recording

    private static void hookNotifications(ClassLoader cl) {
        try {
            XC_MethodHook posted = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof StatusBarNotification) {
                            onNotif((StatusBarNotification) param.args[0], false);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("IslandMod: notif " + t);
                    }
                }
            };
            XC_MethodHook removed = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args != null && param.args.length > 0
                                && param.args[0] instanceof StatusBarNotification) {
                            onNotif((StatusBarNotification) param.args[0], true);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("IslandMod: notif " + t);
                    }
                }
            };
            int n = 0;
            Class<?> sysui = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.NotificationListener", cl);
            if (sysui != null) {
                n += XposedBridge.hookAllMethods(sysui, "onNotificationPosted", posted).size();
                n += XposedBridge.hookAllMethods(sysui, "onNotificationRemoved", removed).size();
            }
            n += XposedBridge.hookAllMethods(
                    NotificationListenerService.class, "onNotificationPosted", posted).size();
            n += XposedBridge.hookAllMethods(
                    NotificationListenerService.class, "onNotificationRemoved", removed).size();
            XposedBridge.log("IslandMod: notification hooks " + n);
        } catch (Throwable t) {
            XposedBridge.log("IslandMod: hookNotifications " + t);
        }
    }

    private static void onNotif(StatusBarNotification sbn, boolean removed) {
        final Island isl = theIsland;
        final Context c = appCtx;
        if (isl == null || c == null) return;
        final Notification n = sbn.getNotification();
        if (n == null) return;
        String pkg = sbn.getPackageName();

        // Screen recording
        if ("screen_record".equals(n.getChannelId())) {
            String key = sbn.getKey();
            if (removed) {
                if (key != null && key.equals(recKey)) {
                    recKey = null;
                    main().post(() -> isl.setRecording(false, null, 0));
                }
                return;
            }
            if (isRecordingNotif(n)) {
                recKey = key;
                final PendingIntent stop = findAction(n, new String[]{"stop"}, true);
                final long when = sbn.getPostTime();
                main().post(() -> isl.setRecording(true, stop, when));
            } else if (recKey != null) {
                // "processing" or "saved" notification: the recording is over
                recKey = null;
                main().post(() -> isl.setRecording(false, null, 0));
            }
            return;
        }

        // Calls (phone app and apps like WhatsApp that use the call category)
        if (Notification.CATEGORY_CALL.equals(n.category)
                && !"android".equals(pkg) && !"com.android.systemui".equals(pkg)) {
            final String key = sbn.getKey();
            if (removed) {
                main().post(() -> isl.setCall(CALL_NONE, key, "", null, null, null));
                return;
            }
            Bundle ex = n.extras;
            if (ex == null) return;

            String t = str(ex.getCharSequence(Notification.EXTRA_TITLE));
            if (t.isEmpty()) t = "Call";
            final String title = t;

            int type = ex.getInt(Notification.EXTRA_CALL_TYPE, 0);
            int count = n.actions == null ? 0 : n.actions.length;
            boolean incoming = type == Notification.CallStyle.CALL_TYPE_INCOMING
                    || type == Notification.CallStyle.CALL_TYPE_SCREENING
                    || (type == 0 && count >= 2);

            PendingIntent answer = null;
            PendingIntent decline = null;
            // Call-style notifications keep their real intents in the extras
            try {
                answer = (PendingIntent) ex.getParcelable(Notification.EXTRA_ANSWER_INTENT);
                decline = (PendingIntent) ex.getParcelable(Notification.EXTRA_DECLINE_INTENT);
                if (decline == null) {
                    decline = (PendingIntent) ex.getParcelable(Notification.EXTRA_HANG_UP_INTENT);
                }
            } catch (Throwable ignored) {
            }
            if (answer == null) {
                answer = findAction(n, new String[]{"answer", "accept", "pick"}, false);
            }
            if (decline == null) {
                decline = findAction(n,
                        new String[]{"decline", "reject", "hang", "end", "dismiss", "disconnect"},
                        false);
            }
            if (n.actions != null) {
                if (incoming && count >= 2) {
                    if (decline == null) decline = n.actions[0].actionIntent;
                    if (answer == null) answer = n.actions[1].actionIntent;
                } else if (decline == null && count >= 1) {
                    decline = n.actions[0].actionIntent;
                }
            }
            XposedBridge.log("IslandMod: call pkg=" + pkg + " incoming=" + incoming
                    + " actions=" + count + " answer=" + (answer != null)
                    + " decline=" + (decline != null));

            Drawable icon = null;
            try {
                Icon li = n.getLargeIcon();
                if (li != null) icon = li.loadDrawable(c);
            } catch (Throwable ignored) {
            }
            if (icon == null) {
                try {
                    icon = c.getPackageManager().getApplicationIcon(pkg);
                } catch (Throwable ignored) {
                }
            }

            final int state = incoming ? CALL_RINGING : CALL_ONGOING;
            final Drawable fIcon = icon;
            final PendingIntent fAnswer = answer;
            final PendingIntent fDecline = decline;
            main().post(() -> isl.setCall(state, key, title, fIcon, fAnswer, fDecline));
        }
    }

    // ---------------------------------------------------------------- setup

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
            appCtx = ctx;
            WindowManager wm = ctx.getSystemService(WindowManager.class);
            float d = ctx.getResources().getDisplayMetrics().density;

            final Island isl = new Island(ctx, wm, d,
                    (int) (PILL_WIDTH_DP * d), (int) (PILL_HEIGHT_DP * d),
                    (int) (TOP_MARGIN_DP * d));
            theIsland = isl;

            // The pill first, the card on top of it
            wm.addView(isl.pillView, isl.pillLp);
            wm.addView(isl.card, isl.lp);
            isl.setHidden(isLandscape(ctx.getResources().getConfiguration()));

            // Hide in landscape and show again in portrait
            ctx.registerComponentCallbacks(new ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                    final boolean land = isLandscape(newConfig);
                    main().post(() -> isl.setHidden(land));
                }

                @Override
                public void onLowMemory() {
                }
            });

            // Charging
            try {
                BroadcastReceiver rcv = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context c, Intent i) {
                        String a = i.getAction();
                        if (Intent.ACTION_BATTERY_CHANGED.equals(a)) {
                            readBattery(i, isl.battery);
                        } else if (Intent.ACTION_POWER_CONNECTED.equals(a)) {
                            XposedBridge.log("IslandMod: power connected");
                            isl.battery.setPlugged(true);
                            isl.event(MODE_BATTERY, CHARGE_SHOW_MS);
                        } else if (Intent.ACTION_POWER_DISCONNECTED.equals(a)) {
                            XposedBridge.log("IslandMod: power disconnected");
                            isl.battery.setPlugged(false);
                        }
                    }
                };
                IntentFilter f = new IntentFilter();
                f.addAction(Intent.ACTION_BATTERY_CHANGED);
                f.addAction(Intent.ACTION_POWER_CONNECTED);
                f.addAction(Intent.ACTION_POWER_DISCONNECTED);
                Intent sticky = ctx.registerReceiver(rcv, f, Context.RECEIVER_EXPORTED);
                if (sticky != null) readBattery(sticky, isl.battery);
            } catch (Throwable t) {
                XposedBridge.log("IslandMod: receiver " + t);
            }

            // Music and video players
            try {
                MediaSessionManager msm = ctx.getSystemService(MediaSessionManager.class);
                MediaTracker tracker = new MediaTracker(isl, msm);
                msm.addOnActiveSessionsChangedListener(tracker, null, main());
                tracker.refresh();
                XposedBridge.log("IslandMod: media tracking on");
            } catch (Throwable t) {
                XposedBridge.log("IslandMod: media " + t);
            }

            added = true;
            XposedBridge.log("IslandMod: island added v3, window type " + WINDOW_TYPE);
        } catch (Throwable t) {
            XposedBridge.log("IslandMod: " + t);
        }
    }

    // ---------------------------------------------------------------- island

    /**
     * Three small windows: the pill, the card (shown only while expanded) and the
     * recording bubble. No window changes size while animating, so nothing jumps.
     */
    private static class Island {
        final Context ctx;
        final WindowManager wm;
        final float d;
        final int w0, h0, y0;
        final Handler h = new Handler(Looper.getMainLooper());

        // pill window
        final FrameLayout pillView;
        final WindowManager.LayoutParams pillLp;
        final ImageView leftIcon;
        final RightIndicator rightInd;

        // card window
        final Card card;
        final WindowManager.LayoutParams lp;
        final BatteryContent battery;
        final MediaContent media;
        final CallContent call;
        final RecordContent record;
        final View[] contents;

        final Bubble bubble;
        final Runnable collapse = () -> close();
        final Runnable ticker = () -> tick();
        final Runnable progressTick = new Runnable() {
            @Override
            public void run() {
                updateProgress();
                if (expanded && mode == MODE_MEDIA && mediaPlaying) h.postDelayed(this, 500);
            }
        };

        boolean hidden = false;
        boolean expanded = false;
        float p = 0f;
        int mode = MODE_BATTERY;
        int cardH = 0;
        long autoMs = 0;
        ValueAnimator anim;

        // call
        int callState = CALL_NONE;
        String callKey;
        String callTitle = "";
        Drawable callIcon;
        PendingIntent callAnswer, callDecline;
        long callStart = 0;

        // screen recording
        boolean recording = false;
        PendingIntent recStop;
        long recStart = 0;
        long recSince = 0;

        // media
        boolean mediaActive = false, mediaPlaying = false;
        String mediaTitle = "", mediaArtist = "", lastEventTitle = "";
        Bitmap mediaArt;
        int mediaAccent = DEFAULT_ACCENT;
        long mediaDuration = 0;
        MediaController mediaCtl;
        PlaybackState lastState;
        PendingIntent sessionActivity;
        Drawable appIcon;
        String appIconPkg = "";

        Island(Context ctx, WindowManager wm, final float d, int w0, int h0, int y0) {
            this.ctx = ctx;
            this.wm = wm;
            this.d = d;
            this.w0 = w0;
            this.h0 = h0;
            this.y0 = y0;

            // ----- pill window
            pillView = new FrameLayout(ctx);
            GradientDrawable pbg = new GradientDrawable();
            pbg.setColor(Color.BLACK);
            pbg.setCornerRadius(h0 / 2f);
            pillView.setBackground(pbg);

            int sz = (int) (20 * d);
            leftIcon = new ImageView(ctx);
            leftIcon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            leftIcon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 6 * d);
                }
            });
            leftIcon.setClipToOutline(true);
            leftIcon.setVisibility(View.GONE);
            FrameLayout.LayoutParams llp = new FrameLayout.LayoutParams(
                    sz, sz, Gravity.START | Gravity.CENTER_VERTICAL);
            llp.leftMargin = (int) (10 * d);
            pillView.addView(leftIcon, llp);

            rightInd = new RightIndicator(ctx, d);
            rightInd.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                    sz, sz, Gravity.END | Gravity.CENTER_VERTICAL);
            rlp.rightMargin = (int) (10 * d);
            pillView.addView(rightInd, rlp);

            pillLp = new WindowManager.LayoutParams(
                    w0, h0,
                    WINDOW_TYPE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            pillLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            pillLp.y = y0;
            pillLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            pillLp.setTitle("IslandPill");

            // ----- card window
            card = new Card(ctx, d, w0, h0);
            battery = new BatteryContent(ctx, d);
            media = new MediaContent(ctx, d);
            call = new CallContent(ctx, d);
            record = new RecordContent(ctx, d);
            contents = new View[]{battery, media, call, record};
            for (View v : contents) {
                v.setAlpha(0f);
                v.setVisibility(View.GONE);
                card.addView(v, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }
            card.setVisibility(View.GONE);

            lp = new WindowManager.LayoutParams(
                    expW(), expH(MODE_BATTERY),
                    WINDOW_TYPE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.y = y0;
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("IslandCard");

            pillView.setOnClickListener(v -> onTap());
            card.setOnClickListener(v -> onTap());

            call.decline.setOnClickListener(v -> {
                declineCall();
                touched();
            });
            call.answer.setOnClickListener(v -> {
                answerCall();
                touched();
            });
            record.stop.setOnClickListener(v -> {
                sendPi(recStop);
                close();
            });
            media.prev.setOnClickListener(v -> {
                try {
                    if (mediaCtl != null) mediaCtl.getTransportControls().skipToPrevious();
                } catch (Throwable ignored) {
                }
                touched();
            });
            media.next.setOnClickListener(v -> {
                try {
                    if (mediaCtl != null) mediaCtl.getTransportControls().skipToNext();
                } catch (Throwable ignored) {
                }
                touched();
            });
            media.play.setOnClickListener(v -> {
                try {
                    if (mediaCtl != null) {
                        if (mediaPlaying) mediaCtl.getTransportControls().pause();
                        else mediaCtl.getTransportControls().play();
                    }
                } catch (Throwable ignored) {
                }
                touched();
            });
            media.shuffle.setOnClickListener(v -> {
                customAction("shuffle");
                touched();
            });
            media.repeat.setOnClickListener(v -> {
                customAction("repeat", "loop");
                touched();
            });
            media.app.setOnClickListener(v -> openApp());
            media.bar.setSeekListener(f -> seek(f));

            bubble = new Bubble(ctx, wm, d, w0, h0, y0);
            bubble.view.setOnClickListener(v -> {
                if (recording && !expanded && !hidden) open(MODE_RECORD, 0);
            });
        }

        // ---------------- window and animation

        int expW() {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return Math.min(dm.widthPixels, dm.heightPixels) - (int) (2 * CARD_MARGIN_DP * d);
        }

        int expH(int m) {
            return (int) ((m == MODE_MEDIA ? H_MEDIA_DP : H_SMALL_DP) * d);
        }

        void showContents(boolean on) {
            for (int i = 0; i < contents.length; i++) {
                contents[i].setVisibility(on && i == mode ? View.VISIBLE : View.GONE);
            }
        }

        static float contentAlpha(float v) {
            return clamp01((v - 0.4f) / 0.5f);
        }

        /** Runs r once the card window has the new size, so the first frame is correct. */
        void waitLayout(final int w, final int hh, final Runnable r) {
            final boolean[] done = {false};
            final View.OnLayoutChangeListener[] ls = new View.OnLayoutChangeListener[1];
            final Runnable go = () -> {
                if (done[0]) return;
                done[0] = true;
                card.removeOnLayoutChangeListener(ls[0]);
                r.run();
            };
            ls[0] = (v, l, t, rr, b, ol, ot, orr, ob) -> {
                if (rr - l == w && b - t == hh) go.run();
            };
            card.addOnLayoutChangeListener(ls[0]);
            h.postDelayed(go, 160);
        }

        void animateTo(final float target) {
            if (anim != null) anim.cancel();
            final float from = p;
            if (from == target) {
                onSettled();
                return;
            }
            anim = ValueAnimator.ofFloat(from, target);
            anim.setDuration(target > from ? 430 : 340);
            anim.setInterpolator(new PathInterpolator(0.22f, 1f, 0.36f, 1f));
            anim.addUpdateListener(a -> apply((float) a.getAnimatedValue()));
            anim.addListener(new AnimatorListenerAdapter() {
                boolean cancelled = false;

                @Override
                public void onAnimationCancel(Animator a) {
                    cancelled = true;
                }

                @Override
                public void onAnimationEnd(Animator a) {
                    if (!cancelled) onSettled();
                }
            });
            anim.start();
        }

        void apply(float v) {
            p = v;
            card.setP(v);
            contents[mode].setAlpha(contentAlpha(v));
            float ia = 1f - clamp01(v / 0.5f);
            pillView.setAlpha(ia);
            bubble.view.setAlpha(ia);
        }

        void onSettled() {
            if (!expanded && p <= 0.001f) {
                showContents(false);
                card.setVisibility(View.GONE);
                pillView.setAlpha(1f);
                pillView.setVisibility(hidden ? View.GONE : View.VISIBLE);
            } else if (expanded && p >= 0.999f) {
                pillView.setVisibility(View.INVISIBLE);
            }
            updateBubble();
            startProgress();
        }

        void updateBubble() {
            bubble.show(recording && !expanded && !hidden);
        }

        void setHidden(boolean hd) {
            hidden = hd;
            if (hd) {
                h.removeCallbacks(collapse);
                if (anim != null) anim.cancel();
                expanded = false;
                p = 0f;
                card.setP(0f);
                showContents(false);
                card.setVisibility(View.GONE);
                pillView.setVisibility(View.GONE);
            } else {
                pillView.setAlpha(1f);
                pillView.setVisibility(View.VISIBLE);
            }
            updateBubble();
        }

        /** The island is visible (portrait) and the screen is on. */
        boolean canShow() {
            if (hidden) return false;
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        }

        int topMode() {
            if (callState != CALL_NONE) return MODE_CALL;
            if (recording) return MODE_RECORD;
            if (mediaActive) return MODE_MEDIA;
            return MODE_BATTERY;
        }

        void schedule(long ms) {
            h.removeCallbacks(collapse);
            if (ms > 0) h.postDelayed(collapse, ms);
        }

        void touched() {
            if (autoMs > 0) schedule(autoMs);
        }

        void open(int m, long ms) {
            if (hidden) return;
            int w = expW();
            int hh = expH(m);

            // A card of a different height replaces the old one while it is not visible
            if (card.getVisibility() == View.VISIBLE && cardH != hh) {
                if (anim != null) anim.cancel();
                p = 0f;
                card.setP(0f);
                card.setVisibility(View.GONE);
                pillView.setAlpha(1f);
                pillView.setVisibility(View.VISIBLE);
            }
            boolean shown = card.getVisibility() == View.VISIBLE;

            mode = m;
            autoMs = ms;
            expanded = true;
            showContents(true);
            contents[mode].setAlpha(contentAlpha(p));
            refreshContent();
            updateBubble();

            if (!shown) {
                lp.width = w;
                lp.height = hh;
                try {
                    wm.updateViewLayout(card, lp);
                } catch (Throwable ignored) {
                }
                p = 0f;
                card.setP(0f);
                card.setVisibility(View.VISIBLE);
                cardH = hh;
                waitLayout(w, hh, () -> animateTo(1f));
            } else {
                animateTo(1f);
            }
            if (pillView.getVisibility() != View.GONE) pillView.setVisibility(View.VISIBLE);
            schedule(ms);
        }

        void close() {
            h.removeCallbacks(collapse);
            if (!expanded) return;
            expanded = false;
            if (!hidden) pillView.setVisibility(View.VISIBLE);
            updateBubble();
            animateTo(0f);
        }

        /** Short automatic popup, skipped while a call or a pinned view is showing. */
        void event(int m, long ms) {
            if (!canShow()) return;
            if (callState != CALL_NONE && m != MODE_CALL) return;
            if (expanded && autoMs == 0) return;
            open(m, ms);
        }

        void onTap() {
            XposedBridge.log("IslandMod: tap");
            if (expanded) {
                close();
                return;
            }
            int m = topMode();
            open(m, (m == MODE_CALL || m == MODE_RECORD) ? 0 : TAP_SHOW_MS);
        }

        void sendPi(PendingIntent pi) {
            if (pi == null) return;
            try {
                pi.send();
            } catch (Throwable t) {
                XposedBridge.log("IslandMod: send " + t);
            }
        }

        // ---------------- call

        void answerCall() {
            XposedBridge.log("IslandMod: answer tap, intent=" + (callAnswer != null));
            sendPi(callAnswer);
            // If the call is still ringing a moment later, answer through Android's call system
            h.postDelayed(() -> {
                if (callState != CALL_RINGING) return;
                try {
                    TelecomManager tm = ctx.getSystemService(TelecomManager.class);
                    if (tm != null) {
                        tm.acceptRingingCall();
                        XposedBridge.log("IslandMod: acceptRingingCall");
                    }
                } catch (Throwable t) {
                    XposedBridge.log("IslandMod: acceptRingingCall " + t);
                }
            }, 700);
        }

        void declineCall() {
            XposedBridge.log("IslandMod: decline tap, intent=" + (callDecline != null));
            sendPi(callDecline);
            h.postDelayed(() -> {
                if (callState == CALL_NONE) return;
                try {
                    TelecomManager tm = ctx.getSystemService(TelecomManager.class);
                    if (tm != null) {
                        tm.endCall();
                        XposedBridge.log("IslandMod: endCall");
                    }
                } catch (Throwable t) {
                    XposedBridge.log("IslandMod: endCall " + t);
                }
            }, 700);
        }

        void setCall(int state, String key, String title, Drawable icon,
                     PendingIntent answer, PendingIntent decline) {
            if (state == CALL_NONE) {
                if (callKey != null && key != null && !key.equals(callKey)) return;
                callState = CALL_NONE;
                callKey = null;
                callAnswer = null;
                callDecline = null;
                callIcon = null;
                updateIndicators();
                startTicker();
                if (expanded && mode == MODE_CALL) close();
                return;
            }
            int prev = callState;
            callState = state;
            callKey = key;
            callTitle = title;
            callIcon = icon;
            callAnswer = answer;
            callDecline = decline;
            if (state == CALL_ONGOING && prev != CALL_ONGOING) {
                callStart = System.currentTimeMillis();
            }
            call.set(state, title, icon, true, true);
            tickTexts();
            updateIndicators();
            startTicker();
            XposedBridge.log("IslandMod: call state " + state);

            if (state == CALL_RINGING) {
                if (canShow()) open(MODE_CALL, 0);
            } else if (prev == CALL_RINGING) {
                if (expanded && mode == MODE_CALL) close();
            } else if (prev == CALL_NONE) {
                if (canShow()) open(MODE_CALL, 3000);
            }
        }

        // ---------------- screen recording

        boolean recordingNotifActive() {
            try {
                NotificationManager nm = ctx.getSystemService(NotificationManager.class);
                for (StatusBarNotification s : nm.getActiveNotifications()) {
                    Notification n = s.getNotification();
                    if (n != null && "screen_record".equals(n.getChannelId())
                            && isRecordingNotif(n)) {
                        return true;
                    }
                }
                return false;
            } catch (Throwable t) {
                return true; // unknown: keep showing
            }
        }

        void setRecording(boolean on, PendingIntent stop, long start) {
            if (on == recording) {
                if (on) recStop = stop;
                return;
            }
            recording = on;
            recStop = on ? stop : null;
            if (on) {
                recStart = start > 0 ? start : System.currentTimeMillis();
                recSince = SystemClock.elapsedRealtime();
            } else {
                recKey = null;
            }
            XposedBridge.log("IslandMod: recording " + on);
            tickTexts();
            startTicker();
            updateBubble();
            if (on) {
                event(MODE_RECORD, RECORD_SHOW_MS);
            } else if (expanded && mode == MODE_RECORD) {
                close();
            }
        }

        // ---------------- media

        void setMedia(boolean active, PlaybackState st, String title, String artist,
                      Bitmap art, int accent, long duration, MediaController ctl,
                      String pkg, PendingIntent sa) {
            mediaActive = active;
            lastState = st;
            mediaPlaying = active && st != null && st.getState() == PlaybackState.STATE_PLAYING;
            mediaTitle = title;
            mediaArtist = artist;
            mediaArt = art;
            mediaAccent = accent;
            mediaDuration = duration;
            mediaCtl = ctl;
            sessionActivity = sa;
            if (pkg != null && !pkg.equals(appIconPkg)) {
                try {
                    appIcon = ctx.getPackageManager().getApplicationIcon(pkg);
                    appIconPkg = pkg;
                } catch (Throwable ignored) {
                }
            }
            media.set(title, artist, art, mediaPlaying);
            media.setApp(appIcon);
            updateIndicators();
            if (expanded && mode == MODE_MEDIA) {
                applyAccent();
                startProgress();
            }
            if (!active) {
                lastEventTitle = "";
                if (expanded && mode == MODE_MEDIA) close();
                return;
            }
            boolean changed = !title.isEmpty() && !title.equals(lastEventTitle);
            if (mediaPlaying && changed) {
                lastEventTitle = title;
                event(MODE_MEDIA, MEDIA_SHOW_MS);
            }
        }

        long mediaPosition() {
            if (lastState == null) return 0;
            long pos = lastState.getPosition();
            if (lastState.getState() == PlaybackState.STATE_PLAYING) {
                long dt = SystemClock.elapsedRealtime() - lastState.getLastPositionUpdateTime();
                pos += (long) (dt * lastState.getPlaybackSpeed());
            }
            if (mediaDuration > 0) pos = Math.min(pos, mediaDuration);
            return Math.max(0, pos);
        }

        void updateProgress() {
            media.setProgress(mediaPosition(), mediaDuration);
        }

        void startProgress() {
            h.removeCallbacks(progressTick);
            if (expanded && mode == MODE_MEDIA) h.post(progressTick);
        }

        void seek(float f) {
            try {
                if (mediaCtl != null && mediaDuration > 0) {
                    mediaCtl.getTransportControls().seekTo((long) (f * mediaDuration));
                }
            } catch (Throwable ignored) {
            }
            touched();
        }

        void customAction(String... keys) {
            try {
                if (mediaCtl == null || lastState == null) return;
                for (PlaybackState.CustomAction a : lastState.getCustomActions()) {
                    String id = (a.getAction() + " " + a.getName()).toLowerCase();
                    for (String k : keys) {
                        if (id.contains(k)) {
                            mediaCtl.getTransportControls().sendCustomAction(a, null);
                            return;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        void openApp() {
            try {
                if (sessionActivity != null) {
                    sessionActivity.send();
                } else if (!appIconPkg.isEmpty()) {
                    Intent i = ctx.getPackageManager().getLaunchIntentForPackage(appIconPkg);
                    if (i != null) {
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        ctx.startActivity(i);
                    }
                }
            } catch (Throwable t) {
                XposedBridge.log("IslandMod: openApp " + t);
            }
            close();
        }

        // ---------------- content and indicators

        void applyAccent() {
            switch (mode) {
                case MODE_MEDIA:
                    card.setAccent(mediaAccent, 1f);
                    break;
                case MODE_BATTERY:
                    card.setAccent(GREEN, battery.plugged ? 0.55f : 0f);
                    break;
                case MODE_CALL:
                    card.setAccent(GREEN, 0.5f);
                    break;
                default:
                    card.setAccent(RED, 0.5f);
                    break;
            }
        }

        void refreshContent() {
            media.set(mediaTitle, mediaArtist, mediaArt, mediaPlaying);
            media.setApp(appIcon);
            call.set(callState, callTitle, callIcon, true, true);
            tickTexts();
            updateProgress();
            applyAccent();
        }

        void tickTexts() {
            long now = System.currentTimeMillis();
            if (callState == CALL_RINGING) {
                call.setSub("Incoming call");
            } else if (callState == CALL_ONGOING) {
                call.setSub("On call \u00b7 " + fmt(now - callStart));
            }
            if (recording) record.setTime(fmt(now - recStart));
        }

        void tick() {
            // Safety net: if the recording notification is gone, the recording is over
            if (recording && SystemClock.elapsedRealtime() - recSince > 2500
                    && !recordingNotifActive()) {
                setRecording(false, null, 0);
            }
            tickTexts();
            if (callState == CALL_ONGOING || recording) h.postDelayed(ticker, 1000);
        }

        void startTicker() {
            h.removeCallbacks(ticker);
            if (callState == CALL_ONGOING || recording) h.post(ticker);
        }

        void updateIndicators() {
            if (callState != CALL_NONE && callIcon != null) {
                leftIcon.setImageDrawable(callIcon);
                leftIcon.setVisibility(View.VISIBLE);
            } else if (callState == CALL_NONE && mediaActive && mediaArt != null) {
                leftIcon.setImageBitmap(mediaArt);
                leftIcon.setVisibility(View.VISIBLE);
            } else {
                leftIcon.setVisibility(View.GONE);
            }

            if (callState != CALL_NONE) rightInd.set(RightIndicator.BARS, GREEN);
            else if (mediaPlaying) rightInd.set(RightIndicator.BARS, mediaAccent);
            else rightInd.set(RightIndicator.NONE, Color.WHITE);
        }
    }

    // ---------------------------------------------------------------- the glass card

    /** Draws the rounded glass shape that grows from the pill to the full card. */
    private static class Card extends FrameLayout {
        final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final float d;
        final int w0, h0;
        float p = 0f;
        int accent = DEFAULT_ACCENT;
        float glowAmount = 0f;

        Card(Context c, float d, int w0, int h0) {
            super(c);
            this.d = d;
            this.w0 = w0;
            this.h0 = h0;
            setWillNotDraw(false);
            setClipToOutline(true);
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(d);
            edge.setColor(Color.WHITE);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    calc();
                    o.setRoundRect(Math.round(rect.left), Math.round(rect.top),
                            Math.round(rect.right), Math.round(rect.bottom), radius());
                }
            });
        }

        void calc() {
            float w = getWidth();
            float hh = getHeight();
            if (w <= 0f || hh <= 0f) {
                rect.set(0f, 0f, 0f, 0f);
                return;
            }
            float pl = (w - w0) / 2f;
            float left = pl * (1f - p);
            float right = pl + w0 + (w - (pl + w0)) * p;
            float bottom = h0 + (hh - h0) * p;
            rect.set(left, 0f, right, bottom);
        }

        float radius() {
            float r = h0 / 2f + (44f * d - h0 / 2f) * p;
            return Math.min(r, Math.min(rect.width(), rect.height()) / 2f);
        }

        void setP(float v) {
            p = v;
            invalidateOutline();
            invalidate();
        }

        void setAccent(int a, float g) {
            accent = a;
            glowAmount = g;
            rebuild();
            invalidate();
        }

        void rebuild() {
            int hh = getHeight();
            if (hh <= 0) return;
            glow.setShader(new LinearGradient(0f, hh * 0.5f, 0f, hh,
                    accent & 0x00FFFFFF, accent, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onSizeChanged(int w, int hh, int ow, int oh) {
            super.onSizeChanged(w, hh, ow, oh);
            rebuild();
            invalidateOutline();
        }

        @Override
        protected void onDraw(Canvas c) {
            calc();
            int w = getWidth();
            int hh = getHeight();
            bg.setColor(lerpColor(0xFF000000, GLASS, p));
            c.drawRect(0f, 0f, w, hh, bg);
            if (glowAmount > 0f && p > 0.05f) {
                glow.setAlpha((int) (255 * 0.75f * glowAmount * Math.min(1f, p * 1.3f)));
                c.drawRect(0f, 0f, w, hh, glow);
            }
            if (p > 0.05f) {
                edge.setAlpha((int) (60 * Math.min(1f, p)));
                float r = radius();
                float in = d * 0.5f;
                c.drawRoundRect(rect.left + in, rect.top + in, rect.right - in,
                        rect.bottom - in, r, r, edge);
            }
        }
    }

    /** The round bubble with a red dot shown beside the pill while recording. */
    private static class Bubble {
        final FrameLayout view;
        final WindowManager.LayoutParams lp;
        final RightIndicator dot;

        Bubble(Context ctx, WindowManager wm, float d, int w0, int h0, int y0) {
            view = new FrameLayout(ctx);
            view.setBackground(oval(Color.BLACK));
            dot = new RightIndicator(ctx, d);
            view.addView(dot, new FrameLayout.LayoutParams(h0, h0, Gravity.CENTER));
            lp = new WindowManager.LayoutParams(
                    h0, h0,
                    WINDOW_TYPE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            lp.x = w0 / 2 + (int) (6 * d) + h0 / 2;
            lp.y = y0;
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            lp.setTitle("IslandBubble");
            view.setVisibility(View.GONE);
            wm.addView(view, lp);
        }

        void show(boolean s) {
            if (s) {
                dot.set(RightIndicator.DOT, RED);
                view.setVisibility(View.VISIBLE);
            } else {
                dot.set(RightIndicator.NONE, RED);
                view.setVisibility(View.GONE);
            }
        }
    }

    // ---------------------------------------------------------------- media tracker

    private static class MediaTracker
            implements MediaSessionManager.OnActiveSessionsChangedListener {
        final Island isl;
        final MediaSessionManager msm;
        MediaController cur;
        Bitmap lastArt;
        int accent = DEFAULT_ACCENT;
        final MediaController.Callback cb = new MediaController.Callback() {
            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                push();
            }

            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                push();
            }

            @Override
            public void onSessionDestroyed() {
                refresh();
            }
        };

        MediaTracker(Island isl, MediaSessionManager msm) {
            this.isl = isl;
            this.msm = msm;
        }

        void refresh() {
            try {
                onActiveSessionsChanged(msm.getActiveSessions(null));
            } catch (Throwable t) {
                XposedBridge.log("IslandMod: media refresh " + t);
            }
        }

        @Override
        public void onActiveSessionsChanged(List<MediaController> list) {
            MediaController best = null;
            if (list != null) {
                for (MediaController c : list) {
                    PlaybackState s = c.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                        best = c;
                        break;
                    }
                }
                if (best == null && !list.isEmpty()) best = list.get(0);
            }
            if (best == null ? cur != null : !best.getSessionToken().equals(
                    cur == null ? null : cur.getSessionToken())) {
                if (cur != null) {
                    try {
                        cur.unregisterCallback(cb);
                    } catch (Throwable ignored) {
                    }
                }
                cur = best;
                if (cur != null) {
                    try {
                        cur.registerCallback(cb, main());
                    } catch (Throwable ignored) {
                    }
                }
            }
            push();
        }

        void push() {
            if (cur == null) {
                isl.setMedia(false, null, "", "", null, DEFAULT_ACCENT, 0, null, null, null);
                return;
            }
            MediaMetadata m = cur.getMetadata();
            PlaybackState s = cur.getPlaybackState();
            String title = "";
            String artist = "";
            Bitmap art = null;
            long dur = 0;
            if (m != null) {
                title = str(m.getString(MediaMetadata.METADATA_KEY_TITLE));
                if (title.isEmpty()) {
                    title = str(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
                }
                artist = str(m.getString(MediaMetadata.METADATA_KEY_ARTIST));
                if (artist.isEmpty()) {
                    artist = str(m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST));
                }
                if (artist.isEmpty()) {
                    artist = str(m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
                }
                art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                dur = m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            }
            if (art != lastArt) {
                lastArt = art;
                accent = accentOf(art);
            }
            boolean live = s != null && s.getState() != PlaybackState.STATE_NONE
                    && s.getState() != PlaybackState.STATE_STOPPED;
            isl.setMedia(live, s, title, artist, art, accent, dur, cur,
                    cur.getPackageName(), cur.getSessionActivity());
        }
    }

    // ---------------------------------------------------------------- content views

    /** Album art, song info, progress bar and playback controls. */
    private static class MediaContent extends LinearLayout {
        final ImageView art, app;
        final TextView title, artist, tPos, tDur;
        final ProgressView bar;
        final GlyphView shuffle, prev, play, next, repeat;

        MediaContent(Context c, final float d) {
            super(c);
            setOrientation(VERTICAL);
            setPadding((int) (20 * d), (int) (18 * d), (int) (20 * d), (int) (10 * d));

            // top row
            LinearLayout r1 = new LinearLayout(c);
            r1.setOrientation(HORIZONTAL);
            r1.setGravity(Gravity.CENTER_VERTICAL);

            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackgroundColor(0xFF2C2C2E);
            art.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 9 * d);
                }
            });
            art.setClipToOutline(true);
            r1.addView(art, new LinearLayout.LayoutParams((int) (56 * d), (int) (56 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setTextColor(Color.WHITE);
            title.setTextSize(18f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            artist = new TextView(c);
            artist.setTextColor(0x99FFFFFF);
            artist.setTextSize(15f);
            artist.setMaxLines(1);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            texts.addView(artist);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (14 * d);
            r1.addView(texts, tp);

            app = new ImageView(c);
            app.setScaleType(ImageView.ScaleType.CENTER_CROP);
            GradientDrawable ab = new GradientDrawable();
            ab.setColor(0x33FFFFFF);
            ab.setCornerRadius(12 * d);
            app.setBackground(ab);
            int ap = (int) (8 * d);
            app.setPadding(ap, ap, ap, ap);
            r1.addView(app, new LinearLayout.LayoutParams((int) (40 * d), (int) (40 * d)));

            addView(r1, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            // progress row
            LinearLayout r2 = new LinearLayout(c);
            r2.setOrientation(HORIZONTAL);
            r2.setGravity(Gravity.CENTER_VERTICAL);
            tPos = new TextView(c);
            tPos.setTextColor(0x99FFFFFF);
            tPos.setTextSize(12f);
            tPos.setText("00:00");
            bar = new ProgressView(c, d);
            tDur = new TextView(c);
            tDur.setTextColor(0x99FFFFFF);
            tDur.setTextSize(12f);
            tDur.setText("--:--");
            r2.addView(tPos);
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                    0, (int) (22 * d), 1f);
            bp.leftMargin = (int) (10 * d);
            bp.rightMargin = (int) (10 * d);
            r2.addView(bar, bp);
            r2.addView(tDur);
            LinearLayout.LayoutParams r2p = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            r2p.topMargin = (int) (12 * d);
            addView(r2, r2p);

            // controls row
            LinearLayout r3 = new LinearLayout(c);
            r3.setOrientation(HORIZONTAL);
            r3.setGravity(Gravity.CENTER_VERTICAL);
            shuffle = new GlyphView(c, GlyphView.SHUFFLE);
            prev = new GlyphView(c, GlyphView.PREV);
            play = new GlyphView(c, GlyphView.PLAY);
            next = new GlyphView(c, GlyphView.NEXT);
            repeat = new GlyphView(c, GlyphView.REPEAT);
            shuffle.setColor(0xCCFFFFFF);
            repeat.setColor(0xCCFFFFFF);
            play.setBackground(oval(0x26FFFFFF));
            addCell(r3, shuffle, 40, d);
            addCell(r3, prev, 44, d);
            addCell(r3, play, 52, d);
            addCell(r3, next, 44, d);
            addCell(r3, repeat, 40, d);
            addView(r3, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, (int) (54 * d)));
        }

        static void addCell(LinearLayout row, View v, int sizeDp, float d) {
            FrameLayout cell = new FrameLayout(row.getContext());
            cell.addView(v, new FrameLayout.LayoutParams(
                    (int) (sizeDp * d), (int) (sizeDp * d), Gravity.CENTER));
            row.addView(cell, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        }

        void set(String t, String a, Bitmap b, boolean playing) {
            title.setText(t.isEmpty() ? "Media" : t);
            artist.setText(a);
            artist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
            if (b != null) art.setImageBitmap(b);
            else art.setImageDrawable(null);
            play.setType(playing ? GlyphView.PAUSE : GlyphView.PLAY);
        }

        void setApp(Drawable icon) {
            app.setImageDrawable(icon);
        }

        void setProgress(long pos, long dur) {
            tPos.setText(fmtMedia(pos));
            tDur.setText(dur > 0 ? fmtMedia(dur) : "--:--");
            bar.setFrac(dur > 0 ? (float) pos / (float) dur : 0f);
        }
    }

    /** Thin progress line with a small round knob. Tap to seek. */
    private static class ProgressView extends View {
        final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint knobGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rf = new RectF();
        final float d;
        float frac = 0f;
        SeekListener listener;

        ProgressView(Context c, float d) {
            super(c);
            this.d = d;
            track.setColor(0x40FFFFFF);
            fill.setColor(Color.WHITE);
            knobGlow.setColor(0x55FFFFFF);
        }

        void setSeekListener(SeekListener l) {
            listener = l;
        }

        void setFrac(float f) {
            frac = clamp01(f);
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_UP) {
                float pad = 4f * d;
                float w = getWidth() - 2 * pad;
                if (w > 0 && listener != null) {
                    listener.onSeek(clamp01((e.getX() - pad) / w));
                }
            }
            return true;
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float hh = getHeight();
            float pad = 4f * d;
            float th = 3f * d;
            float cy = hh / 2f;
            rf.set(pad, cy - th / 2f, w - pad, cy + th / 2f);
            c.drawRoundRect(rf, th / 2f, th / 2f, track);
            float x = pad + (w - 2 * pad) * frac;
            rf.set(pad, cy - th / 2f, Math.max(x, pad + th), cy + th / 2f);
            c.drawRoundRect(rf, th / 2f, th / 2f, fill);
            c.drawCircle(x, cy, 6f * d, knobGlow);
            c.drawCircle(x, cy, 3f * d, fill);
        }
    }

    /** Caller, call status and answer or hang up buttons. */
    private static class CallContent extends LinearLayout {
        final ImageView icon;
        final TextView title, sub;
        final GlyphView decline, answer;

        CallContent(Context c, final float d) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding((int) (22 * d), 0, (int) (18 * d), 0);

            icon = new ImageView(c);
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setBackgroundColor(0xFF2C2C2E);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setOval(0, 0, v.getWidth(), v.getHeight());
                }
            });
            icon.setClipToOutline(true);
            addView(icon, new LinearLayout.LayoutParams((int) (52 * d), (int) (52 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setTextColor(Color.WHITE);
            title.setTextSize(17f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = new TextView(c);
            sub.setTextColor(0x99FFFFFF);
            sub.setTextSize(13f);
            sub.setMaxLines(1);
            texts.addView(title);
            texts.addView(sub);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (14 * d);
            addView(texts, tp);

            int bs = (int) (48 * d);
            decline = new GlyphView(c, GlyphView.CROSS);
            decline.setBackground(oval(RED));
            answer = new GlyphView(c, GlyphView.CHECK);
            answer.setBackground(oval(GREEN));
            LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(bs, bs);
            dp1.leftMargin = (int) (10 * d);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(bs, bs);
            dp2.leftMargin = (int) (10 * d);
            addView(decline, dp1);
            addView(answer, dp2);
        }

        void set(int state, String t, Drawable ic, boolean hasAnswer, boolean hasDecline) {
            title.setText(t);
            icon.setImageDrawable(ic);
            answer.setVisibility(state == CALL_RINGING && hasAnswer ? View.VISIBLE : View.GONE);
            decline.setVisibility(hasDecline ? View.VISIBLE : View.GONE);
        }

        void setSub(String s) {
            sub.setText(s);
        }
    }

    /** Screen recording status, timer and stop button. */
    private static class RecordContent extends LinearLayout {
        final TextView title, time, stop;

        RecordContent(Context c, final float d) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding((int) (26 * d), 0, (int) (22 * d), 0);

            View dot = new View(c);
            dot.setBackground(oval(RED));
            addView(dot, new LinearLayout.LayoutParams((int) (14 * d), (int) (14 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setText("Screen recording");
            title.setTextColor(Color.WHITE);
            title.setTextSize(16f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            time = new TextView(c);
            time.setTextColor(0x99FFFFFF);
            time.setTextSize(14f);
            time.setText("0:00");
            texts.addView(title);
            texts.addView(time);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (14 * d);
            addView(texts, tp);

            stop = new TextView(c);
            stop.setText("Stop");
            stop.setTextColor(Color.WHITE);
            stop.setTextSize(15f);
            stop.setTypeface(Typeface.DEFAULT_BOLD);
            stop.setGravity(Gravity.CENTER);
            GradientDrawable sb = new GradientDrawable();
            sb.setColor(RED);
            sb.setCornerRadius(22 * d);
            stop.setBackground(sb);
            stop.setPadding((int) (22 * d), (int) (10 * d), (int) (22 * d), (int) (10 * d));
            addView(stop, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setTime(String s) {
            time.setText(s);
        }
    }

    /** Battery ring, title, status and percentage shown for charging. */
    private static class BatteryContent extends LinearLayout {
        final BatteryRing ring;
        final TextView title, sub, pct;
        int level = 0;
        boolean plugged = false;

        BatteryContent(Context c, float d) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            int pad = (int) (26 * d);
            setPadding(pad, 0, pad, 0);

            ring = new BatteryRing(c, d);
            addView(ring, new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setTextColor(Color.WHITE);
            title.setTextSize(17f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            sub = new TextView(c);
            sub.setTextColor(0x99FFFFFF);
            sub.setTextSize(13f);
            texts.addView(title);
            texts.addView(sub);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (14 * d);
            addView(texts, tp);

            pct = new TextView(c);
            pct.setTextSize(26f);
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
            float hh = getHeight();
            float half = stroke / 2f;
            rect.set(half, half, w - half, hh - half);
            c.drawArc(rect, 0f, 360f, false, track);

            arc.setColor(charging ? GREEN : (level <= 15 ? RED : Color.WHITE));
            c.drawArc(rect, -90f, 360f * level / 100f, false, arc);

            if (charging) {
                float cx = w / 2f;
                float cy = hh / 2f;
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

    /** Small shapes: play, pause, next, previous, cross, check, shuffle and repeat. */
    private static class GlyphView extends View {
        static final int PLAY = 0, PAUSE = 1, NEXT = 2, PREV = 3, CROSS = 4, CHECK = 5,
                SHUFFLE = 6, REPEAT = 7;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        final RectF rf = new RectF();
        int type;
        int color = Color.WHITE;

        GlyphView(Context c, int type) {
            super(c);
            this.type = type;
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setType(int t) {
            if (t != type) {
                type = t;
                invalidate();
            }
        }

        void setColor(int c) {
            color = c;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float s = Math.min(getWidth(), getHeight()) * 0.22f;
            paint.setColor(color);
            paint.setStyle(Paint.Style.FILL);
            path.reset();
            switch (type) {
                case PLAY:
                    path.moveTo(cx - 0.7f * s, cy - s);
                    path.lineTo(cx - 0.7f * s, cy + s);
                    path.lineTo(cx + s, cy);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case PAUSE:
                    c.drawRect(cx - 0.9f * s, cy - s, cx - 0.25f * s, cy + s, paint);
                    c.drawRect(cx + 0.25f * s, cy - s, cx + 0.9f * s, cy + s, paint);
                    break;
                case NEXT:
                    path.moveTo(cx - s, cy - s);
                    path.lineTo(cx - s, cy + s);
                    path.lineTo(cx + 0.6f * s, cy);
                    path.close();
                    c.drawPath(path, paint);
                    c.drawRect(cx + 0.7f * s, cy - s, cx + s, cy + s, paint);
                    break;
                case PREV:
                    path.moveTo(cx + s, cy - s);
                    path.lineTo(cx + s, cy + s);
                    path.lineTo(cx - 0.6f * s, cy);
                    path.close();
                    c.drawPath(path, paint);
                    c.drawRect(cx - s, cy - s, cx - 0.7f * s, cy + s, paint);
                    break;
                case CROSS:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.5f);
                    c.drawLine(cx - s, cy - s, cx + s, cy + s, paint);
                    c.drawLine(cx + s, cy - s, cx - s, cy + s, paint);
                    break;
                case CHECK:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.5f);
                    path.moveTo(cx - s, cy);
                    path.lineTo(cx - 0.3f * s, cy + 0.75f * s);
                    path.lineTo(cx + s, cy - 0.75f * s);
                    c.drawPath(path, paint);
                    break;
                case SHUFFLE:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.32f);
                    path.moveTo(cx - 1.1f * s, cy - 0.7f * s);
                    path.lineTo(cx - 0.5f * s, cy - 0.7f * s);
                    path.cubicTo(cx + 0.2f * s, cy - 0.7f * s, cx + 0.1f * s, cy + 0.7f * s,
                            cx + 0.8f * s, cy + 0.7f * s);
                    path.moveTo(cx - 1.1f * s, cy + 0.7f * s);
                    path.lineTo(cx - 0.5f * s, cy + 0.7f * s);
                    path.cubicTo(cx + 0.2f * s, cy + 0.7f * s, cx + 0.1f * s, cy - 0.7f * s,
                            cx + 0.8f * s, cy - 0.7f * s);
                    c.drawPath(path, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.reset();
                    path.moveTo(cx + 0.7f * s, cy - 1.15f * s);
                    path.lineTo(cx + 1.3f * s, cy - 0.7f * s);
                    path.lineTo(cx + 0.7f * s, cy - 0.25f * s);
                    path.close();
                    path.moveTo(cx + 0.7f * s, cy + 0.25f * s);
                    path.lineTo(cx + 1.3f * s, cy + 0.7f * s);
                    path.lineTo(cx + 0.7f * s, cy + 1.15f * s);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                case REPEAT:
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(s * 0.32f);
                    rf.set(cx - 1.1f * s, cy - 0.7f * s, cx + 1.1f * s, cy + 0.7f * s);
                    c.drawRoundRect(rf, 0.6f * s, 0.6f * s, paint);
                    paint.setStyle(Paint.Style.FILL);
                    path.moveTo(cx + 0.55f * s, cy - 1.2f * s);
                    path.lineTo(cx + 1.2f * s, cy - 0.7f * s);
                    path.lineTo(cx + 0.55f * s, cy - 0.2f * s);
                    path.close();
                    c.drawPath(path, paint);
                    break;
                default:
                    break;
            }
        }
    }

    /** Right side of the collapsed pill: moving bars or a pulsing dot. */
    private static class RightIndicator extends View {
        static final int NONE = 0, BARS = 1, DOT = 2;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final RectF rect = new RectF();
        final float d;
        int kind = NONE;
        int color = Color.WHITE;
        float phase = 0f;
        ValueAnimator va;

        RightIndicator(Context c, float d) {
            super(c);
            this.d = d;
        }

        void set(int k, int col) {
            color = col;
            if (k == kind) {
                invalidate();
                return;
            }
            kind = k;
            if (va != null) {
                va.cancel();
                va = null;
            }
            if (k == NONE) {
                setVisibility(View.INVISIBLE);
            } else {
                setVisibility(View.VISIBLE);
                va = ValueAnimator.ofFloat(0f, 1f);
                va.setDuration(900);
                va.setRepeatCount(ValueAnimator.INFINITE);
                va.setInterpolator(new LinearInterpolator());
                va.addUpdateListener(a -> {
                    phase = (float) a.getAnimatedValue();
                    invalidate();
                });
                va.start();
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float hh = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            if (kind == DOT) {
                float a = 0.6f + 0.4f * (float) Math.sin(2.0 * Math.PI * phase);
                paint.setAlpha((int) (255 * a));
                c.drawCircle(w / 2f, hh / 2f, 5.5f * d, paint);
            } else if (kind == BARS) {
                float bw = 2.6f * d;
                float gap = 2.2f * d;
                int n = 4;
                float total = n * bw + (n - 1) * gap;
                float x = (w - total) / 2f;
                float maxH = hh * 0.8f;
                for (int i = 0; i < n; i++) {
                    float f = 0.3f + 0.7f * (float) Math.abs(
                            Math.sin(2.0 * Math.PI * phase + i * 1.3));
                    float bh = maxH * f;
                    rect.set(x, hh / 2f - bh / 2f, x + bw, hh / 2f + bh / 2f);
                    c.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
                    x += bw + gap;
                }
            }
        }
    }
}
