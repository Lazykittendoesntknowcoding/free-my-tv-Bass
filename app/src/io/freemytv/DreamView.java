package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The screensaver, over a slow aurora that follows the real day at your location (Aurora) or, when Home Settings says
 * Photos, over photos that change every few minutes (Photos), crossfading:
 *  - top left, the 24-hour clock and the date; top right, the weather now with today's high and low;
 *  - in the middle, today's forecast: now and every 3 hours up to 12 hours ahead, each with its symbol, temperature
 *    and chance of rain;
 *  - at the bottom, the 4 latest BBC News headlines as tiles, with their photos.
 * Everything drifts a few pixels over 10 minutes so nothing sits still for hours. Any key exits (as on Apple TV).
 */
final class DreamView extends View implements Choreographer.FrameCallback, HomeData.Listener {
    private static final long SKY_STEP_MS = 50;
    /** A photo stays still but for the drift (about 0.2 px a second): redrawn twice a second, not 20 times. */
    private static final long PHOTO_STEP_MS = 500;
    private static final double DRIFT_PERIOD_S = 600;
    // Layout in a 1920×1080 space (k = width / 1920).
    private static final float MARGIN = 96, RIGHT = 1824;
    private static final float CLOCK_BASE = 280, DATE_BASE = 348;
    private static final float STRIP_TOP = 404, STRIP_H = 256;
    private static final float NEWS_LABEL = 708, TILES_TOP = 730, TILE_H = 300, TILE_GAP = 32, PHOTO_H = 160;
    private static final int SLOTS = 5, TILES = 4;
    private static final long FADE_MS = 2000, PHOTO_RETRY_MS = 60_000;
    private static final int PHOTO_SCRIM = 0x4D000000;

    private final HomeData data;
    private final Aurora aurora = new Aurora();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Recording content = Recording.create("dream");
    private final Map<String, Bitmap> photos = new HashMap<>();     // story image URL -> photo cut to the tile
    private final List<String> loading = new ArrayList<>();
    private final RectF rect = new RectF();
    private final Rect src = new Rect();
    private final Path clip = new Path();
    // The panels are clear glass: only a brighter band inside the edge and a rim of light.
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF inset = new RectF();
    private final Paint photoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint backPaint = new Paint(Paint.FILTER_BITMAP_FLAG), scrim = new Paint();
    private final Photos photoSource;
    private Photos.Photo shown, before;   // the background photo, and the one it is fading in over (null: the aurora)
    private long fadeAt = -1;
    private boolean fetching;
    private int photoRun;                 // bumped by stop(): a photo that arrives later is thrown away
    private final float timeOffset = (float) (Math.random() * 3000);
    private boolean running, dirty = true;
    private long startedAt, lastSky = -1, minute = -1;
    private int w, h, generation;
    private float k = 1;
    private String clock = "", date = "";
    private Paint clockPaint, datePaint, tempPaint, conditionPaint, slotLabel, slotTemp, slotRain, newsLabel, agePaint;
    private TextPaint headline;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            data.refreshIfStale();
            loadPhotos();
            dirty = true;
            handler.postDelayed(this, 60_000);
        }
    };

    DreamView(Context c) {
        super(c);
        data = HomeData.get(c);
        photoSource = new Photos(c);
    }

    private final Runnable nextPhoto = this::fetchPhoto;

    void start() {
        if (running) return;
        running = true;
        if (worker.isShutdown()) worker = Executors.newSingleThreadExecutor();
        startedAt = SystemClock.uptimeMillis();
        lastSky = -1;
        data.addListener(this);
        data.refreshIfStale();
        loadPhotos();
        fetchPhoto();
        handler.postDelayed(tick, 60_000);
        Choreographer.getInstance().postFrameCallback(this);
        invalidate();
    }

    void stop() {
        if (!running) return;
        running = false;
        generation++;
        Choreographer.getInstance().removeFrameCallback(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(nextPhoto);
        photoRun++;
        fetching = false;
        data.removeListener(this);
        // The photos stay in memory until the garbage collector runs: release them now (after the last frame).
        final List<Bitmap> gone = new ArrayList<>(photos.values());
        photos.clear();
        loading.clear();
        for (Photos.Photo p : new Photos.Photo[] {shown, before}) if (p != null) gone.add(p.image);
        shown = before = null;
        fadeAt = -1;
        if (!gone.isEmpty()) handler.postDelayed(() -> { for (Bitmap b : gone) b.recycle(); }, 1000);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        worker.shutdownNow();
    }

    @Override public void onData() {
        dirty = true;
        loadPhotos();
        invalidate();
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        w = width;
        h = height;
        k = w / 1920f;
        content.setPosition(0, 0, w, h);
        clockPaint = Ui.numbers(Ui.thin, 230 * k, 0xF7FFFFFF);
        clockPaint.setLetterSpacing(-0.01f);
        datePaint = Ui.text(Ui.semibold, 38 * k, 0xD9FFFFFF);
        tempPaint = Ui.numbers(Ui.light, 128 * k, 0xF7FFFFFF);
        tempPaint.setTextAlign(Paint.Align.RIGHT);
        conditionPaint = Ui.text(Ui.medium, 34 * k, 0xD9FFFFFF);
        conditionPaint.setTextAlign(Paint.Align.RIGHT);
        slotLabel = Ui.numbers(Ui.medium, 28 * k, 0xB3FFFFFF);
        slotLabel.setTextAlign(Paint.Align.CENTER);
        slotTemp = Ui.numbers(Ui.medium, 40 * k, 0xF2FFFFFF);
        slotTemp.setTextAlign(Paint.Align.CENTER);
        slotRain = Ui.numbers(Ui.semibold, 25 * k, 0xFF8FD3FF);
        slotRain.setTextAlign(Paint.Align.CENTER);
        newsLabel = Ui.text(Ui.semibold, 22 * k, 0xB3FFFFFF);
        newsLabel.setLetterSpacing(0.12f);
        headline = new TextPaint(Ui.text(Ui.semibold, 27 * k, 0xF7FFFFFF));
        agePaint = Ui.text(Ui.medium, 22 * k, 0x99FFFFFF);
        for (Paint p : new Paint[] {clockPaint, datePaint, tempPaint, conditionPaint, newsLabel}) {
            p.setShadowLayer(18 * k, 0, 2 * k, 0x59000000);
        }
        // Photos are cut to the tile's size: a new size means new cuts.
        for (Bitmap b : photos.values()) b.recycle();
        photos.clear();
        loading.clear();
        generation++;
        loadPhotos();
        fetchPhoto();   // start() can come before the first layout, with no size yet
        dirty = true;
    }

    // MARK: - Frames

    private long clockNow() {
        return SystemClock.uptimeMillis();
    }

    @Override public void doFrame(long frameTimeNanos) {
        if (!running) return;
        long up = clockNow();
        if (fadeAt >= 0) {
            invalidate();   // a crossfade: every frame
        } else if (lastSky < 0 || up - lastSky >= (shown == null ? SKY_STEP_MS : PHOTO_STEP_MS)) {
            lastSky = up;
            if (shown == null) {
                HomeData.Weather wx = data.weather;
                aurora.palette(System.currentTimeMillis(), wx.sunrise, wx.sunset);
                aurora.step(timeOffset + (up - startedAt) / 1000f);
            }
            invalidate();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (w == 0) return;
        long now = System.currentTimeMillis();
        if (now / 60_000 != minute) {
            minute = now / 60_000;
            clock = Ui.clock(now);
            date = Ui.date(now);
            dirty = true;
        }
        drawBackground(canvas);
        if (dirty) {
            Canvas c = content.beginRecording(w, h);
            try {
                drawContent(c);
            } finally {
                content.endRecording();
            }
            dirty = false;
        }
        double phase = 2 * Math.PI * ((clockNow() - startedAt) / 1000.0) / DRIFT_PERIOD_S;
        content.setTranslationX((float) (14 * k * Math.sin(phase)));
        content.setTranslationY((float) (10 * k * Math.sin(2 * phase + 0.7)));
        content.draw(canvas);
    }

    // MARK: - Background

    private void drawBackground(Canvas canvas) {
        float f = 1;
        if (fadeAt >= 0) {
            f = Ui.clamp((clockNow() - fadeAt) / (float) FADE_MS);
            if (f >= 1) {
                fadeAt = -1;
                // The old photo goes once this frame no longer draws it.
                if (before != null) {
                    recycleLater(before);
                    before = null;
                }
            }
        }
        if (shown == null || (before == null && f < 1)) aurora.draw(canvas, w, h);
        rect.set(0, 0, w, h);
        if (before != null && !before.image.isRecycled()) {
            backPaint.setAlpha(255);
            canvas.drawBitmap(before.image, null, rect, backPaint);
        }
        if (shown != null && !shown.image.isRecycled()) {
            backPaint.setAlpha(Math.round(f * 255));
            canvas.drawBitmap(shown.image, null, rect, backPaint);
            // A light shade over the photo, so the white text reads on bright skies and snow.
            scrim.setColor(PHOTO_SCRIM);
            if (before == null) scrim.setAlpha(Math.round(f * Color.alpha(PHOTO_SCRIM)));
            canvas.drawRect(rect, scrim);
        }
    }

    /** After the frames that may still draw it. */
    private void recycleLater(final Photos.Photo p) {
        handler.postDelayed(p::recycle, 1000);
    }

    /** Loads the next photo in the background; once it's in, it fades in, and the next one comes after the timer. */
    private void fetchPhoto() {
        handler.removeCallbacks(nextPhoto);
        if (!running || w == 0 || fetching) return;
        if (!Prefs.dreamPhotos()) {
            if (shown != null) {   // switched back to the aurora while the screensaver shows (the preview)
                if (before != null) recycleLater(before);
                before = null;
                recycleLater(shown);
                shown = null;
                dirty = true;
            }
            return;
        }
        fetching = true;
        final int run = photoRun, pw = w, ph = h;
        worker.execute(() -> {
            final Photos.Photo p = photoSource.next(pw, ph);
            handler.post(() -> {
                if (run != photoRun || !running) {
                    if (p != null) p.recycle();
                    return;
                }
                fetching = false;
                if (p == null) {
                    handler.postDelayed(nextPhoto, PHOTO_RETRY_MS);   // offline: the aurora (or the last photo) stays
                    return;
                }
                if (before != null) recycleLater(before);   // a fade still running: skip to the new one
                before = shown;
                shown = p;
                fadeAt = clockNow();
                dirty = true;
                invalidate();
                handler.postDelayed(nextPhoto, Prefs.photoSeconds() * 1000L);
            });
        });
    }

    private void drawContent(Canvas c) {
        drawClock(c);
        HomeData.Weather wx = data.weather;
        if (Prefs.weather() && !Double.isNaN(wx.temp)) {
            drawNow(c, wx);
            drawForecast(c, wx);
        }
        if (Prefs.news()) drawNews(c);
    }

    /** A glass panel's edge: more solid toward the border (a band fading inward) and a rim, brightest at the top. */
    private void glassEdge(Canvas c, RectF r, float radius) {
        final int steps = 6;
        float step = 3 * k;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(step);
        for (int i = 0; i < steps; i++) {
            float d = (i + 0.5f) * step, fall = 1 - i / (float) steps;
            edge.setColor(Math.round(0x2E * fall * fall) << 24 | 0xFFFFFF);
            inset.set(r.left + d, r.top + d, r.right - d, r.bottom - d);
            c.drawRoundRect(inset, Math.max(0, radius - d), Math.max(0, radius - d), edge);
        }
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * k);
        rim.setShader(new LinearGradient(0, r.top, 0, r.bottom, 0x80FFFFFF, 0x1FFFFFFF, Shader.TileMode.CLAMP));
        float d = 0.75f * k;
        inset.set(r.left + d, r.top + d, r.right - d, r.bottom - d);
        c.drawRoundRect(inset, radius - d, radius - d, rim);
    }

    // MARK: - Clock and weather

    private void drawClock(Canvas c) {
        float x = MARGIN * k;
        c.drawText(clock, x - Ui.leftBearing(clockPaint, clock), CLOCK_BASE * k, clockPaint);
        c.drawText(date, x, DATE_BASE * k, datePaint);
    }

    private void drawNow(Canvas c, HomeData.Weather wx) {
        float right = RIGHT * k;
        String temp = HomeData.degrees(wx.temp);
        float tw = tempPaint.measureText(temp);
        c.drawText(temp, right, CLOCK_BASE * k, tempPaint);
        float size = 96 * k;
        String symbol = Symbols.weather(wx.code, wx.isDay);
        float sw = Symbols.width(getContext(), symbol, size);
        Symbols.draw(getContext(), c, symbol, right - tw - 28 * k - sw / 2, CLOCK_BASE * k - Ui.cap(tempPaint) / 2, size, 0xF2FFFFFF);
        String line = HomeData.condition(wx.code, wx.isDay);
        String range = Double.isNaN(wx.max) ? "" : HomeData.highLow(wx, "  ");
        c.drawText(line.isEmpty() ? range : range.isEmpty() ? line : line + "   " + range, right, DATE_BASE * k, conditionPaint);
    }

    /** Now, then every 3 hours: the hourly list starts at the next full hour, so +3 h is its third entry. */
    private void drawForecast(Canvas c, HomeData.Weather wx) {
        float left = MARGIN * k, right = RIGHT * k, top = STRIP_TOP * k, bottom = (STRIP_TOP + STRIP_H) * k;
        rect.set(left, top, right, bottom);
        glassEdge(c, rect, 32 * k);
        float slot = (right - left) / SLOTS;
        for (int i = 0; i < SLOTS; i++) {
            String label, temp;
            int code, rain;
            boolean day;
            if (i == 0) {
                label = capitalized(Ui.tr("now"));
                temp = HomeData.degrees(wx.temp);
                code = wx.code;
                day = wx.isDay;
                rain = wx.hourRain.isEmpty() ? -1 : wx.hourRain.get(0);
            } else {
                int at = 3 * i - 1;
                if (at >= wx.hours.size()) break;
                label = Ui.time(wx.hours.get(at));
                temp = HomeData.degrees(wx.hourTemp.get(at));
                code = wx.hourCode.get(at);
                day = wx.hourDay.get(at);
                rain = at < wx.hourRain.size() ? wx.hourRain.get(at) : -1;
            }
            float cx = left + slot * (i + 0.5f);
            c.drawText(label, cx, top + 58 * k, slotLabel);
            Symbols.draw(getContext(), c, Symbols.weather(code, day), cx, top + 120 * k, 64 * k, 0xF2FFFFFF);
            c.drawText(temp, cx, top + 196 * k, slotTemp);
            // The chance of rain, only when it's worth knowing (like the iPhone's Weather).
            if (rain >= 10) c.drawText(rain + "%", cx, top + 234 * k, slotRain);
        }
    }

    private static String capitalized(String s) {
        return s.isEmpty() ? s : s.substring(0, 1).toUpperCase(Ui.locale()) + s.substring(1);
    }

    // MARK: - News

    private List<HomeData.Story> stories() {
        List<HomeData.Story> all = data.news;
        return all.subList(0, Math.min(TILES, all.size()));
    }

    private float tileWidth() {
        return ((RIGHT - MARGIN) * k - (TILES - 1) * TILE_GAP * k) / TILES;
    }

    private void drawNews(Canvas c) {
        List<HomeData.Story> list = stories();
        if (list.isEmpty()) return;
        float left = MARGIN * k, tw = tileWidth(), top = TILES_TOP * k, th = TILE_H * k, r = 24 * k;
        c.drawText("BBC NEWS", left, NEWS_LABEL * k, newsLabel);
        for (int i = 0; i < list.size(); i++) {
            HomeData.Story st = list.get(i);
            float x = left + i * (tw + TILE_GAP * k);
            rect.set(x, top, x + tw, top + th);
            Bitmap photo = st.image == null ? null : photos.get(st.image);
            float textTop = top + 24 * k;
            if (photo != null && !photo.isRecycled()) {
                c.save();
                clip.reset();
                rect.set(x, top, x + tw, top + PHOTO_H * k);
                clip.addRoundRect(rect, new float[] {r, r, r, r, 0, 0, 0, 0}, Path.Direction.CW);
                c.clipPath(clip);
                src.set(0, 0, photo.getWidth(), photo.getHeight());
                c.drawBitmap(photo, src, rect, photoPaint);
                c.restore();
                textTop = top + PHOTO_H * k + 18 * k;
            }
            rect.set(x, top, x + tw, top + th);
            glassEdge(c, rect, r);
            float pad = 22 * k;
            int lines = photo != null ? 2 : 4;
            StaticLayout layout = StaticLayout.Builder.obtain(st.title, 0, st.title.length(), headline, Math.round(tw - 2 * pad))
                    .setMaxLines(lines)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .build();
            c.save();
            c.translate(x + pad, textTop);
            layout.draw(c);
            c.restore();
            if (st.published != 0) c.drawText(HomeData.ago(st.published), x + pad, top + th - 22 * k, agePaint);
        }
    }

    /** Downloads the tiles' photos in the background, each cut to the tile's shape. */
    private void loadPhotos() {
        if (!running || w == 0 || !Prefs.news()) return;
        final int tw = Math.round(tileWidth()), ph = Math.round(PHOTO_H * k), gen = generation;
        for (HomeData.Story st : stories()) {
            final String url = st.image;
            if (url == null || photos.containsKey(url) || loading.contains(url)) continue;
            loading.add(url);
            worker.execute(() -> {
                Bitmap cut = null;
                try {
                    cut = cut(download(url), tw, ph);
                } catch (Exception e) {
                    AppLog.w("news photo: " + e.getMessage());
                }
                final Bitmap done = cut;
                handler.post(() -> {
                    if (gen != generation || !running) {
                        if (done != null) done.recycle();
                        return;
                    }
                    loading.remove(url);
                    if (done == null) return;
                    photos.put(url, done);
                    dirty = true;
                    invalidate();
                });
            });
        }
    }

    private static byte[] download(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** The photo scaled to cover w×h and cropped in the middle. */
    private static Bitmap cut(byte[] bytes, int w, int h) {
        Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (b == null) return null;
        float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int x = (b.getWidth() - cw) / 2, y = (b.getHeight() - ch) / 2;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(b, new Rect(x, y, x + cw, y + ch), new Rect(0, 0, w, h), new Paint(Paint.FILTER_BITMAP_FLAG));
        b.recycle();
        return out;
    }
}
