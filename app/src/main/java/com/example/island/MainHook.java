package com.example.island;

import android.animation.TimeInterpolator;
import android.animation.ValueAnimator;
import android.app.Application;
import android.app.Notification;
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
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
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
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
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

    // Expanded island size
    private static final int EXPANDED_W_DP = 330;
    private static final int EXPANDED_H_DP = 92;
    private static final long ANIM_MS = 550;

    private static final long CHARGE_SHOW_MS = 3500;
    private static final long MEDIA_SHOW_MS = 4000;
    private static final long RECORD_SHOW_MS = 3000;
    private static final long TAP_SHOW_MS = 8000;

    // Window layer above the status bar so the island receives taps
    private static final int WINDOW_TYPE = 2024; // TYPE_NAVIGATION_BAR_PANEL

    private static final int GREEN = 0xFF30D158;
    private static final int RED = 0xFFFF453A;

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

    private static String str(CharSequence s) {
        return s == null ? "" : s.toString().trim();
    }

    private static String fmt(long ms) {
        long s = Math.max(0, ms / 1000);
        long sec = s % 60;
        return (s / 60) + ":" + (sec < 10 ? "0" : "") + sec;
    }

    private static GradientDrawable oval(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        return g;
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

    private static void onNotif(StatusBarNotification sbn, boolean removed) {
        final Island isl = theIsland;
        final Context c = appCtx;
        if (isl == null || c == null) return;
        final Notification n = sbn.getNotification();
        if (n == null) return;
        String pkg = sbn.getPackageName();

        // Screen recording: the ongoing notification System UI posts while recording
        if ("screen_record".equals(n.getChannelId())) {
            if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) {
                final PendingIntent stop = findAction(n, new String[]{"stop"}, true);
                final long when = sbn.getPostTime();
                final boolean on = !removed;
                main().post(() -> isl.setRecording(on, stop, when));
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

            PendingIntent answer = findAction(n,
                    new String[]{"answer", "accept", "pick"}, false);
            PendingIntent decline = findAction(n,
                    new String[]{"decline", "reject", "hang", "end", "dismiss", "disconnect"},
                    false);
            if (n.actions != null) {
                if (incoming && count >= 2) {
                    if (decline == null) decline = n.actions[0].actionIntent;
                    if (answer == null) answer = n.actions[1].actionIntent;
                } else if (decline == null && count >= 1) {
                    decline = n.actions[0].actionIntent;
                }
            }

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

    // ---------------------------------------------------------------- island

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
                    (int) (TOP_MARGIN_DP * d),
                    (int) (EXPANDED_W_DP * d), (int) (EXPANDED_H_DP * d));
            theIsland = isl;

            wm.addView(isl.root, isl.lp);
            isl.root.setVisibility(isLandscape(ctx.getResources().getConfiguration())
                    ? View.GONE : View.VISIBLE);

            // Hide in landscape and show again in portrait
            ctx.registerComponentCallbacks(new ComponentCallbacks() {
                @Override
                public void onConfigurationChanged(Configuration newConfig) {
                    final boolean land = isLandscape(newConfig);
                    main().post(() -> isl.root.setVisibility(land ? View.GONE : View.VISIBLE));
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
            XposedBridge.log("IslandMod: island added, window type " + WINDOW_TYPE);
        } catch (Throwable t) {
            XposedBridge.log("IslandMod: " + t);
        }
    }

    /** Controls the island size, content, indicators and animation. */
    private static class Island {
        final Context ctx;
        final WindowManager wm;
        final float d;
        final int w0, h0, y0, w1, h1;
        final Handler h = new Handler(Looper.getMainLooper());
        final GradientDrawable bg = new GradientDrawable();
        final FrameLayout root;
        final WindowManager.LayoutParams lp;
        final BatteryContent battery;
        final MediaContent media;
        final CallContent call;
        final RecordContent record;
        final View[] contents;
        final ImageView leftIcon;
        final RightIndicator rightInd;
        final Runnable collapse = () -> setExpanded(false);
        final Runnable ticker = () -> tick();

        boolean expanded = false;
        float progress = 0f;
        int mode = MODE_BATTERY;
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

        // media
        boolean mediaActive = false, mediaPlaying = false;
        String mediaTitle = "", mediaArtist = "", lastEventTitle = "";
        Bitmap mediaArt;
        MediaController mediaCtl;

        Island(Context ctx, WindowManager wm, final float d,
               int w0, int h0, int y0, int w1, int h1) {
            this.ctx = ctx;
            this.wm = wm;
            this.d = d;
            this.w0 = w0; this.h0 = h0; this.y0 = y0;
            this.w1 = w1; this.h1 = h1;

            bg.setColor(Color.BLACK);
            bg.setCornerRadius(h0 / 2f);

            battery = new BatteryContent(ctx, d);
            media = new MediaContent(ctx, d);
            call = new CallContent(ctx, d);
            record = new RecordContent(ctx, d);
            contents = new View[]{battery, media, call, record};

            root = new FrameLayout(ctx);
            root.setBackground(bg);
            for (View v : contents) {
                v.setAlpha(0f);
                v.setVisibility(View.GONE);
                root.addView(v, new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
            }
            battery.setVisibility(View.VISIBLE);

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
            root.addView(leftIcon, llp);

            rightInd = new RightIndicator(ctx, d);
            rightInd.setVisibility(View.INVISIBLE);
            FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(
                    sz, sz, Gravity.END | Gravity.CENTER_VERTICAL);
            rlp.rightMargin = (int) (10 * d);
            root.addView(rightInd, rlp);

            lp = new WindowManager.LayoutParams(
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

            root.setOnClickListener(v -> onTap());

            call.decline.setOnClickListener(v -> {
                sendPi(callDecline);
                touched();
            });
            call.answer.setOnClickListener(v -> {
                sendPi(callAnswer);
                touched();
            });
            record.stop.setOnClickListener(v -> {
                sendPi(recStop);
                h.removeCallbacks(collapse);
                setExpanded(false);
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
        }

        static float contentAlpha(float p) {
            return Math.max(0f, Math.min(1f, (p - 0.55f) / 0.45f));
        }

        void sendPi(PendingIntent pi) {
            if (pi == null) return;
            try {
                pi.send();
            } catch (Throwable t) {
                XposedBridge.log("IslandMod: send " + t);
            }
        }

        /** The island is visible (portrait) and the screen is on. */
        boolean canShow() {
            if (root.getVisibility() != View.VISIBLE) return false;
            PowerManager pm = ctx.getSystemService(PowerManager.class);
            return pm == null || pm.isInteractive();
        }

        void setMode(int m) {
            mode = m;
            for (int i = 0; i < contents.length; i++) {
                contents[i].setVisibility(i == m ? View.VISIBLE : View.GONE);
            }
            contents[m].setAlpha(contentAlpha(progress));
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
            setMode(m);
            refreshContent();
            autoMs = ms;
            setExpanded(true);
            schedule(ms);
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
                h.removeCallbacks(collapse);
                setExpanded(false);
                return;
            }
            int m = topMode();
            open(m, (m == MODE_CALL || m == MODE_RECORD) ? 0 : TAP_SHOW_MS);
        }

        // ---------------- call

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
                if (expanded && mode == MODE_CALL) {
                    h.removeCallbacks(collapse);
                    setExpanded(false);
                }
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
            call.set(state, title, icon, answer != null, decline != null);
            tickTexts();
            updateIndicators();
            startTicker();
            XposedBridge.log("IslandMod: call state " + state);

            if (state == CALL_RINGING) {
                if (canShow()) open(MODE_CALL, 0);
            } else if (prev == CALL_RINGING) {
                if (expanded && mode == MODE_CALL) {
                    h.removeCallbacks(collapse);
                    setExpanded(false);
                }
            } else if (prev == CALL_NONE) {
                if (canShow()) open(MODE_CALL, 3000);
            }
        }

        // ---------------- screen recording

        void setRecording(boolean on, PendingIntent stop, long start) {
            if (on == recording) {
                if (on) recStop = stop;
                return;
            }
            recording = on;
            recStop = on ? stop : null;
            if (on) recStart = start > 0 ? start : System.currentTimeMillis();
            XposedBridge.log("IslandMod: recording " + on);
            tickTexts();
            updateIndicators();
            startTicker();
            if (on) {
                event(MODE_RECORD, RECORD_SHOW_MS);
            } else if (expanded && mode == MODE_RECORD) {
                h.removeCallbacks(collapse);
                setExpanded(false);
            }
        }

        // ---------------- media

        void setMedia(boolean active, boolean playing, String title, String artist,
                      Bitmap art, MediaController ctl) {
            mediaActive = active;
            mediaPlaying = active && playing;
            mediaTitle = title;
            mediaArtist = artist;
            mediaArt = art;
            mediaCtl = ctl;
            media.set(title, artist, art, mediaPlaying);
            updateIndicators();
            if (!active) {
                lastEventTitle = "";
                if (expanded && mode == MODE_MEDIA) {
                    h.removeCallbacks(collapse);
                    setExpanded(false);
                }
                return;
            }
            boolean trackChanged = !title.isEmpty() && !title.equals(lastEventTitle);
            if (mediaPlaying && trackChanged) {
                lastEventTitle = title;
                event(MODE_MEDIA, MEDIA_SHOW_MS);
            }
        }

        // ---------------- content and indicators

        void refreshContent() {
            media.set(mediaTitle, mediaArtist, mediaArt, mediaPlaying);
            call.set(callState, callTitle, callIcon, callAnswer != null, callDecline != null);
            tickTexts();
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
            else if (recording) rightInd.set(RightIndicator.DOT, RED);
            else if (mediaPlaying) rightInd.set(RightIndicator.BARS, Color.WHITE);
            else rightInd.set(RightIndicator.NONE, Color.WHITE);
        }

        // ---------------- animation

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
            contents[mode].setAlpha(contentAlpha(p));
            float ia = 1f - Math.max(0f, Math.min(1f, p / 0.35f));
            leftIcon.setAlpha(ia);
            rightInd.setAlpha(ia);
            try {
                wm.updateViewLayout(root, lp);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- media tracker

    private static class MediaTracker
            implements MediaSessionManager.OnActiveSessionsChangedListener {
        final Island isl;
        final MediaSessionManager msm;
        MediaController cur;
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
                isl.setMedia(false, false, "", "", null, null);
                return;
            }
            MediaMetadata m = cur.getMetadata();
            PlaybackState s = cur.getPlaybackState();
            String title = "";
            String artist = "";
            Bitmap art = null;
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
            }
            boolean playing = s != null && s.getState() == PlaybackState.STATE_PLAYING;
            isl.setMedia(true, playing, title, artist, art, cur);
        }
    }

    // ---------------------------------------------------------------- views

    /** Album art, song info and playback controls. */
    private static class MediaContent extends LinearLayout {
        final ImageView art;
        final TextView title, artist;
        final GlyphView prev, play, next;

        MediaContent(Context c, final float d) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding((int) (18 * d), 0, (int) (10 * d), 0);

            art = new ImageView(c);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.setBackgroundColor(0xFF2C2C2E);
            art.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 12 * d);
                }
            });
            art.setClipToOutline(true);
            addView(art, new LinearLayout.LayoutParams((int) (56 * d), (int) (56 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setTextColor(Color.WHITE);
            title.setTextSize(15f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            artist = new TextView(c);
            artist.setTextColor(0xB3FFFFFF);
            artist.setTextSize(12.5f);
            artist.setMaxLines(1);
            artist.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(title);
            texts.addView(artist);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (12 * d);
            addView(texts, tp);

            int bs = (int) (36 * d);
            prev = new GlyphView(c, GlyphView.PREV);
            play = new GlyphView(c, GlyphView.PLAY);
            next = new GlyphView(c, GlyphView.NEXT);
            addView(prev, new LinearLayout.LayoutParams(bs, bs));
            addView(play, new LinearLayout.LayoutParams(bs, bs));
            addView(next, new LinearLayout.LayoutParams(bs, bs));
        }

        void set(String t, String a, Bitmap b, boolean playing) {
            title.setText(t.isEmpty() ? "Media" : t);
            artist.setText(a);
            artist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
            if (b != null) art.setImageBitmap(b);
            else art.setImageDrawable(null);
            play.setType(playing ? GlyphView.PAUSE : GlyphView.PLAY);
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
            setPadding((int) (20 * d), 0, (int) (16 * d), 0);

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
            addView(icon, new LinearLayout.LayoutParams((int) (48 * d), (int) (48 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setTextColor(Color.WHITE);
            title.setTextSize(16f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            sub = new TextView(c);
            sub.setTextColor(0xB3FFFFFF);
            sub.setTextSize(12.5f);
            sub.setMaxLines(1);
            texts.addView(title);
            texts.addView(sub);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            tp.leftMargin = (int) (12 * d);
            addView(texts, tp);

            int bs = (int) (44 * d);
            decline = new GlyphView(c, GlyphView.CROSS);
            decline.setBackground(oval(RED));
            answer = new GlyphView(c, GlyphView.CHECK);
            answer.setBackground(oval(GREEN));
            LinearLayout.LayoutParams dp1 = new LinearLayout.LayoutParams(bs, bs);
            dp1.leftMargin = (int) (8 * d);
            LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(bs, bs);
            dp2.leftMargin = (int) (8 * d);
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
            setPadding((int) (24 * d), 0, (int) (20 * d), 0);

            View dot = new View(c);
            dot.setBackground(oval(RED));
            addView(dot, new LinearLayout.LayoutParams((int) (14 * d), (int) (14 * d)));

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(VERTICAL);
            title = new TextView(c);
            title.setText("Screen recording");
            title.setTextColor(Color.WHITE);
            title.setTextSize(15f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            time = new TextView(c);
            time.setTextColor(0xB3FFFFFF);
            time.setTextSize(13f);
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
            stop.setTextSize(14f);
            stop.setTypeface(Typeface.DEFAULT_BOLD);
            stop.setGravity(Gravity.CENTER);
            GradientDrawable sb = new GradientDrawable();
            sb.setColor(RED);
            sb.setCornerRadius(20 * d);
            stop.setBackground(sb);
            stop.setPadding((int) (20 * d), (int) (9 * d), (int) (20 * d), (int) (9 * d));
            addView(stop, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setTime(String s) {
            time.setText(s);
        }
    }

    /** Small shapes drawn in white: play, pause, next, previous, cross and check. */
    private static class GlyphView extends View {
        static final int PLAY = 0, PAUSE = 1, NEXT = 2, PREV = 3, CROSS = 4, CHECK = 5;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        int type;

        GlyphView(Context c, int type) {
            super(c);
            this.type = type;
            paint.setColor(Color.WHITE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        void setType(int t) {
            if (t != type) {
                type = t;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas c) {
            float cx = getWidth() / 2f;
            float cy = getHeight() / 2f;
            float s = Math.min(getWidth(), getHeight()) * 0.22f;
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
            float h = getHeight();
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            if (kind == DOT) {
                float a = 0.6f + 0.4f * (float) Math.sin(2.0 * Math.PI * phase);
                paint.setAlpha((int) (255 * a));
                c.drawCircle(w / 2f, h / 2f, 5f * d, paint);
            } else if (kind == BARS) {
                float bw = 2.6f * d;
                float gap = 2.2f * d;
                int n = 4;
                float total = n * bw + (n - 1) * gap;
                float x = (w - total) / 2f;
                float maxH = h * 0.8f;
                for (int i = 0; i < n; i++) {
                    float f = 0.3f + 0.7f * (float) Math.abs(
                            Math.sin(2.0 * Math.PI * phase + i * 1.3));
                    float bh = maxH * f;
                    rect.set(x, h / 2f - bh / 2f, x + bw, h / 2f + bh / 2f);
                    c.drawRoundRect(rect, bw / 2f, bw / 2f, paint);
                    x += bw + gap;
                }
            }
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
