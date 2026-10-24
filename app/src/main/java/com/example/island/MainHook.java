package dev.flexy.dynamicisland.xposed;

import android.animation.ValueAnimator;
import android.app.Application;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.util.List;

public class MainHook implements IXposedHookLoadPackage {
    private static final String SYSTEMUI = "com.android.systemui";

    // Pill size in dp. Change these to resize.
    static final int W_DP = 110;
    static final int H_DP = 30;
    static final int TOP_DP = 10;

    private static boolean started;

    @Override
    public void handleLoadPackage(LoadPackageParam lpp) {
        if (!SYSTEMUI.equals(lpp.packageName)) return;
        XposedBridge.log("IslandPill: loaded in " + lpp.packageName);

        XC_MethodHook startHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (started) return;
                started = true;
                try {
                    new Controller((Context) param.thisObject).start();
                    XposedBridge.log("IslandPill: started");
                } catch (Throwable t) {
                    XposedBridge.log("IslandPill: start failed: " + t);
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(
                SYSTEMUI + ".SystemUIApplication", lpp.classLoader, "onCreate", startHook);
        } catch (Throwable t) {
            XposedBridge.log("IslandPill: using fallback hook: " + t);
            try {
                XposedHelpers.findAndHookMethod(Application.class, "onCreate", startHook);
            } catch (Throwable t2) {
                XposedBridge.log("IslandPill: fallback failed: " + t2);
            }
        }
    }

    // =====================================================================
    // Controller: window, media sessions, animation
    // =====================================================================
    static class Controller implements PillView.Host {
        private final Context ctx;
        private final float density;
        private final Handler main = new Handler(Looper.getMainLooper());
        private WindowManager wm;
        private PillView view;
        private WindowManager.LayoutParams lp;
        private boolean attached, expanded;
        private float fraction;
        private ValueAnimator anim;
        private int expW, expH;

        private MediaController ctl;
        private MediaController.Callback cb;

        private final Runnable autoCollapse = this::collapse;
        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                updateProgress();
                if (expanded) main.postDelayed(this, 400);
            }
        };

        Controller(Context ctx) {
            this.ctx = ctx;
            this.density = ctx.getResources().getDisplayMetrics().density;
        }

        void start() { main.post(this::init); }

        private int px(int dp) { return Math.round(dp * density); }

        private void init() {
            try {
                wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                int screenW = ctx.getResources().getDisplayMetrics().widthPixels;
                expW = Math.min(screenW - px(24), px(420));
                expH = px(172);

                view = new PillView(ctx, this);
                view.setExpandedSize(expW, expH);

                lp = new WindowManager.LayoutParams(
                    px(W_DP), px(H_DP),
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
                lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                lp.y = px(TOP_DP);
                lp.setTitle("IslandPill");
                view.setFraction(0f, px(H_DP));

                MediaSessionManager msm =
                    (MediaSessionManager) ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                msm.addOnActiveSessionsChangedListener(this::pick, null, main);
                pick(msm.getActiveSessions(null));
            } catch (Throwable t) {
                XposedBridge.log("IslandPill: init failed: " + t);
            }
        }

        // ---- media sessions ----

        private void pick(List<MediaController> list) {
            MediaController best = null;
            if (list != null) {
                for (MediaController c : list) {
                    PlaybackState s = c.getPlaybackState();
                    if (s != null && s.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
                }
                if (best == null && !list.isEmpty()) best = list.get(0);
            }
            if (ctl != null && best != null && ctl.getSessionToken().equals(best.getSessionToken())) {
                refreshState();
                return;
            }
            if (ctl != null && cb != null) ctl.unregisterCallback(cb);
            ctl = best;
            if (ctl == null) { detach(); return; }
            cb = new MediaController.Callback() {
                @Override public void onMetadataChanged(MediaMetadata m) { refreshMeta(); }
                @Override public void onPlaybackStateChanged(PlaybackState s) { refreshState(); }
                @Override public void onSessionDestroyed() {
                    try {
                        MediaSessionManager msm = (MediaSessionManager)
                            ctx.getSystemService(Context.MEDIA_SESSION_SERVICE);
                        pick(msm.getActiveSessions(null));
                    } catch (Throwable ignored) { detach(); }
                }
            };
            ctl.registerCallback(cb, main);
            refreshMeta();
            refreshState();
        }

        private void refreshMeta() {
            if (ctl == null || view == null) return;
            MediaMetadata m = ctl.getMetadata();
            if (m == null) return;
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (t == null) t = m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (a == null) a = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            Bitmap art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            int tint = 0xFF444444;
            if (art != null) {
                try { tint = Bitmap.createScaledBitmap(art, 1, 1, true).getPixel(0, 0) | 0xFF000000; }
                catch (Throwable ignored) {}
            }
            view.setMeta(t, a, art, tint);
            view.setFraction(fraction, px(H_DP));
            updateProgress();
        }

        private void refreshState() {
            if (ctl == null || view == null) return;
            PlaybackState s = ctl.getPlaybackState();
            boolean visible = s != null && s.getState() != PlaybackState.STATE_NONE
                && s.getState() != PlaybackState.STATE_STOPPED;
            if (!visible) { detach(); return; }
            view.setPlaying(s.getState() == PlaybackState.STATE_PLAYING);
            attach();
            updateProgress();
        }

        private void updateProgress() {
            if (ctl == null || view == null) return;
            PlaybackState s = ctl.getPlaybackState();
            MediaMetadata m = ctl.getMetadata();
            if (s == null) return;
            long pos = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime())
                    * s.getPlaybackSpeed());
            }
            long dur = m == null ? 0 : m.getLong(MediaMetadata.METADATA_KEY_DURATION);
            view.setProgress(pos, dur);
        }

        // ---- window ----

        private void attach() {
            if (attached || view == null) return;
            try { apply(fraction); wm.addView(view, lp); attached = true; }
            catch (Throwable t) { XposedBridge.log("IslandPill: attach: " + t); }
        }

        private void detach() {
            if (!attached) return;
            try { wm.removeView(view); } catch (Throwable ignored) {}
            attached = false;
            expanded = false;
            fraction = 0f;
            if (anim != null) anim.cancel();
            main.removeCallbacks(autoCollapse);
            main.removeCallbacks(ticker);
            view.setFraction(0f, px(H_DP));
        }

        private void apply(float f) {
            lp.width = Math.round(px(W_DP) + (expW - px(W_DP)) * f);
            lp.height = Math.round(px(H_DP) + (expH - px(H_DP)) * f);
            view.setFraction(f, px(H_DP));
            if (attached) {
                try { wm.updateViewLayout(view, lp); } catch (Throwable ignored) {}
            }
        }

        private void animateTo(float target) {
            if (anim != null) anim.cancel();
            anim = ValueAnimator.ofFloat(fraction, target);
            anim.setDuration(320);
            anim.setInterpolator(new DecelerateInterpolator(1.5f));
            anim.addUpdateListener(a -> { fraction = (float) a.getAnimatedValue(); apply(fraction); });
            anim.start();
        }

        private void expand() {
            expanded = true;
            animateTo(1f);
            updateProgress();
            main.removeCallbacks(ticker);
            main.post(ticker);
            resetAutoCollapse();
        }

        private void collapse() {
            expanded = false;
            main.removeCallbacks(autoCollapse);
            main.removeCallbacks(ticker);
            animateTo(0f);
        }

        private void resetAutoCollapse() {
            main.removeCallbacks(autoCollapse);
            main.postDelayed(autoCollapse, 6000);
        }

        // ---- PillView.Host ----

        @Override public void onTap() { if (expanded) collapse(); else expand(); }

        @Override public void onPrev() {
            if (ctl != null) ctl.getTransportControls().skipToPrevious();
            resetAutoCollapse();
        }

        @Override public void onNext() {
            if (ctl != null) ctl.getTransportControls().skipToNext();
            resetAutoCollapse();
        }

        @Override public void onPlayPause() {
            if (ctl != null) {
                PlaybackState s = ctl.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) {
                    ctl.getTransportControls().pause();
                } else {
                    ctl.getTransportControls().play();
                }
            }
            resetAutoCollapse();
        }
    }

    // =====================================================================
    // PillView: compact (art + bars) and expanded (media card)
    // =====================================================================
    static class PillView extends FrameLayout {
        interface Host { void onTap(); void onPrev(); void onPlayPause(); void onNext(); }

        private final float d;
        private final GradientDrawable bg = new GradientDrawable();
        private final FrameLayout compact;
        private final LinearLayout card;
        private final ImageView miniArt, bigArt;
        private final Bars bars;
        private final Line line;
        private final TextView title, artist, elapsed, total, play;
        private float radius;
        private int tintDark = 0xFF1C1C1E;

        PillView(Context c, Host host) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            bg.setOrientation(GradientDrawable.Orientation.TOP_BOTTOM);
            bg.setColors(new int[]{Color.BLACK, Color.BLACK});
            setBackground(bg);
            setClipToOutline(true);
            setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View v, Outline o) {
                    o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            setOnClickListener(v -> host.onTap());

            compact = new FrameLayout(c);
            miniArt = new ImageView(c);
            miniArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            round(miniArt, dp(5));
            LayoutParams mp = new LayoutParams(dp(22), dp(22), Gravity.START | Gravity.CENTER_VERTICAL);
            mp.leftMargin = dp(10);
            compact.addView(miniArt, mp);
            bars = new Bars(c);
            LayoutParams bp = new LayoutParams(dp(24), dp(14), Gravity.END | Gravity.CENTER_VERTICAL);
            bp.rightMargin = dp(14);
            compact.addView(bars, bp);
            addView(compact, new LayoutParams(-1, -1));

            card = new LinearLayout(c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(16), dp(16), dp(12));
            card.setVisibility(GONE);

            LinearLayout top = new LinearLayout(c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            bigArt = new ImageView(c);
            bigArt.setScaleType(ImageView.ScaleType.CENTER_CROP);
            bigArt.setBackgroundColor(0x33FFFFFF);
            round(bigArt, dp(10));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(dp(56), dp(56));
            ap.rightMargin = dp(12);
            top.addView(bigArt, ap);

            LinearLayout texts = new LinearLayout(c);
            texts.setOrientation(LinearLayout.VERTICAL);
            title = text(c, 16, Color.WHITE, true);
            artist = text(c, 14, 0xB3FFFFFF, false);
            texts.addView(title);
            texts.addView(artist);
            top.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView note = text(c, 16, Color.WHITE, false);
            note.setText("\u266A");
            note.setGravity(Gravity.CENTER);
            note.setBackgroundColor(0x33FFFFFF);
            round(note, dp(10));
            top.addView(note, new LinearLayout.LayoutParams(dp(32), dp(32)));
            card.addView(top);

            line = new Line(c);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(4));
            lp.topMargin = dp(14);
            card.addView(line, lp);

            LinearLayout times = new LinearLayout(c);
            elapsed = text(c, 11, 0xB3FFFFFF, false);
            total = text(c, 11, 0xB3FFFFFF, false);
            total.setGravity(Gravity.END);
            times.addView(elapsed, new LinearLayout.LayoutParams(0, -2, 1f));
            times.addView(total, new LinearLayout.LayoutParams(0, -2, 1f));
            card.addView(times);

            LinearLayout ctl = new LinearLayout(c);
            ctl.setGravity(Gravity.CENTER_VERTICAL);
            ctl.addView(btn(c, "\u21C4", 20, null));
            ctl.addView(btn(c, "\u23EE", 24, host::onPrev));
            play = btn(c, "\u23F8", 32, host::onPlayPause);
            ctl.addView(play);
            ctl.addView(btn(c, "\u23ED", 24, host::onNext));
            ctl.addView(btn(c, "\u21BB", 20, null));
            card.addView(ctl, new LinearLayout.LayoutParams(-1, 0, 1f));

            addView(card, new LayoutParams(dp(300), dp(172), Gravity.TOP | Gravity.CENTER_HORIZONTAL));
        }

        private int dp(float v) { return Math.round(v * d); }

        private TextView text(Context c, int sp, int color, boolean bold) {
            TextView t = new TextView(c);
            t.setTextSize(sp);
            t.setTextColor(color);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
            return t;
        }

        private TextView btn(Context c, String s, int sp, Runnable r) {
            TextView t = text(c, sp, Color.WHITE, false);
            t.setText(s);
            t.setGravity(Gravity.CENTER);
            t.setLayoutParams(new LinearLayout.LayoutParams(0, -1, 1f));
            if (r != null) t.setOnClickListener(v -> r.run());
            else t.setAlpha(0.6f);
            return t;
        }

        private static void round(View v, float r) {
            v.setClipToOutline(true);
            v.setOutlineProvider(new ViewOutlineProvider() {
                @Override public void getOutline(View view, Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), r);
                }
            });
        }

        private static int blend(int a, int b, float t) {
            return Color.rgb(
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
        }

        void setExpandedSize(int w, int h) {
            LayoutParams p = (LayoutParams) card.getLayoutParams();
            p.width = w;
            p.height = h;
            card.setLayoutParams(p);
        }

        void setFraction(float f, int compactHeightPx) {
            radius = compactHeightPx / 2f + (dp(30) - compactHeightPx / 2f) * f;
            bg.setColors(new int[]{blend(Color.BLACK, 0xFF2A2A2D, f), blend(Color.BLACK, tintDark, f)});
            compact.setAlpha(Math.max(0f, 1f - f * 2.2f));
            compact.setVisibility(f >= 0.5f ? GONE : VISIBLE);
            card.setAlpha(Math.max(0f, (f - 0.45f) / 0.55f));
            card.setVisibility(f <= 0.01f ? GONE : VISIBLE);
            invalidateOutline();
        }

        void setMeta(String t, String a, Bitmap art, int tint) {
            title.setText(t == null ? "" : t);
            artist.setText(a == null ? "" : a);
            miniArt.setImageBitmap(art);
            bigArt.setImageBitmap(art);
            tintDark = blend(Color.BLACK, tint, 0.55f);
        }

        void setPlaying(boolean p) {
            play.setText(p ? "\u23F8" : "\u25B6");
            bars.setActive(p);
        }

        void setProgress(long pos, long dur) {
            line.set(dur > 0 ? Math.min(1f, pos / (float) dur) : 0f);
            elapsed.setText(fmt(pos));
            total.setText(fmt(dur));
        }

        private static String fmt(long ms) {
            long s = Math.max(0, ms) / 1000;
            return String.format("%02d:%02d", s / 60, s % 60);
        }

        static class Bars extends View {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private boolean active;

            Bars(Context c) { super(c); p.setColor(0xFFFF9F43); }

            void setActive(boolean a) { active = a; invalidate(); }

            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight();
                float bw = w / 8f, gap = bw / 2f;
                long t = System.currentTimeMillis();
                for (int i = 0; i < 5; i++) {
                    float f = active ? 0.25f + 0.75f * (float) Math.abs(Math.sin(t / 170.0 + i * 1.3)) : 0.25f;
                    float bh = h * f, x = i * (bw + gap);
                    cv.drawRoundRect(new RectF(x, (h - bh) / 2, x + bw, (h + bh) / 2), bw / 2, bw / 2, p);
                }
                if (active) postInvalidateOnAnimation();
            }
        }

        static class Line extends View {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private float frac;

            Line(Context c) { super(c); }

            void set(float f) { frac = f; invalidate(); }

            @Override protected void onDraw(Canvas cv) {
                float w = getWidth(), h = getHeight();
                p.setColor(0x4DFFFFFF);
                cv.drawRoundRect(new RectF(0, 0, w, h), h / 2, h / 2, p);
                p.setColor(Color.WHITE);
                cv.drawRoundRect(new RectF(0, 0, w * frac, h), h / 2, h / 2, p);
            }
        }
    }
}
