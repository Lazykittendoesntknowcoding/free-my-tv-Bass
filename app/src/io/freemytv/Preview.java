package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.File;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * The title popup on the picks tabs (OK on a card opens it, Back closes it): the title's photo across the screen, its
 * name, year · genres · runtime, the ratings, what it's about, the cast, where it streams, and Play and Watch trailer.
 * Light on memory: the photo is read only when it opens, one at a time, straight into GPU memory, and let go once it
 * has closed; the details are one TMDB call (and one OMDb call), and Recs keeps only a few of them.
 */
final class Preview {
    static final int PLAY = 0, TRAILER = 1;
    private static final float LEFT = 120, TEXT_W = 900, BOTTOM = 972, BUTTON_H = 76, CHIP_H = 46;
    private static final long FADE_MS = 500;

    boolean open;
    Recs.Pick pick;         // the title shown (kept while it fades out)
    int button;
    final Spring shown = new Spring(0.32f, 1f, 0);
    final RectF[] buttons = {new RectF(), new RectF()};
    private final Spring[] focus = {new Spring(0.26f, 0.85f, 1), new Spring(0.26f, 0.85f, 0)};

    private final Context context;
    private final Recs recs;
    private final Executor loader;
    private final Runnable changed;     // redraw (on the main thread)
    private final Handler main = new Handler(Looper.getMainLooper());
    private Recs.Info info;
    private Bitmap image;
    private long imageAt;
    private int generation;             // each opening: a photo that arrives for an older one is dropped

    // Laid out when the content or the size changes, never while drawing.
    private int w, h;
    private float k = 1;
    private TextPaint titlePaint, bodyPaint;
    private Paint linePaint, smallPaint, chipName, chipValue, buttonPaint;
    private StaticLayout title, overview;
    private String line = "", cast = "", platforms = "";
    private final String[] labels = {"", ""};
    private String[] chipNames = new String[0], chipValues = new String[0];
    private RectF[] chips = new RectF[0];
    private float titleTop, lineBase, overviewTop, castBase, platformsBase;
    private LinearGradient leftScrim, bottomScrim;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final RectF photo = new RectF(), cell = new RectF();

    Preview(Context context, Recs recs, Executor loader, Runnable changed) {
        this.context = context;
        this.recs = recs;
        this.loader = loader;
        this.changed = changed;
    }

    void size(int width, int height, float scale) {
        w = width;
        h = height;
        k = scale;
        titlePaint = new TextPaint(Ui.text(Ui.displaySemibold, 72 * k, 0xFFFFFFFF));
        titlePaint.setLetterSpacing(0.004f);
        linePaint = Ui.text(Ui.medium, 29 * k, 0xE6FFFFFF);
        bodyPaint = new TextPaint(Ui.text(Ui.medium, 28 * k, 0xE6FFFFFF));
        smallPaint = Ui.text(Ui.medium, 25 * k, 0xB3FFFFFF);
        chipName = Ui.text(Ui.medium, 22 * k, 0xB3FFFFFF);
        chipValue = Ui.text(Ui.semibold, 25 * k, 0xFFFFFFFF);
        buttonPaint = Ui.text(Ui.semibold, 29 * k, 0xFFFFFFFF);
        for (Paint p : new Paint[] {titlePaint, linePaint, bodyPaint, smallPaint}) p.setShadowLayer(18 * k, 0, 2 * k, 0x66000000);
        leftScrim = new LinearGradient(0, 0, w * 0.66f, 0, new int[] {0xE6000000, 0xB3000000, 0x40000000, 0},
                new float[] {0, 0.35f, 0.75f, 1}, Shader.TileMode.CLAMP);
        bottomScrim = new LinearGradient(0, h, 0, h * 0.4f, new int[] {0xCC000000, 0x59000000, 0},
                new float[] {0, 0.45f, 1}, Shader.TileMode.CLAMP);
        layout();
    }

    /** Opens on the pick: its photo and details load now (nothing was loaded for it before). */
    void open(Recs.Pick p) {
        boolean other = pick == null || !Recs.key(p).equals(Recs.key(pick));
        open = true;
        pick = p;
        button = PLAY;
        focus[PLAY].snap(1);
        focus[TRAILER].snap(0);
        shown.target = 1;
        info = recs.info(p, this::refresh);
        if (other || image == null) loadImage(p);
        layout();
    }

    void close() {
        open = false;
        shown.target = 0;
    }

    /** Gone at once (Home pressed). */
    void dismiss() {
        close();
        shown.snap(0);
        release();
    }

    /** The details arrived, or Recs has news on the pick (its genre, where Play opens it). */
    void refresh() {
        if (pick == null) return;
        if (info == null) info = recs.info(pick, this::refresh);
        layout();
        changed.run();
    }

    /** ← → between Play and Watch trailer; false at either end. */
    boolean move(int dx) {
        int next = Math.max(PLAY, Math.min(TRAILER, button + dx));
        if (next == button) return false;
        button = next;
        changed.run();
        return true;
    }

    /** Covers the whole screen (the photo is in): the layers under it needn't be drawn. */
    boolean covers() {
        return open && image != null && shown.value > 0.999f && SystemClock.uptimeMillis() - imageAt >= FADE_MS;
    }

    /** Advances the animations; true while something moves. */
    boolean step(float dt) {
        for (int i = 0; i < focus.length; i++) focus[i].target = open && i == button ? 1 : 0;
        boolean moving = shown.step(dt) | focus[PLAY].step(dt) | focus[TRAILER].step(dt);
        moving |= image != null && SystemClock.uptimeMillis() - imageAt < FADE_MS;
        if (!open && !moving && shown.value < 0.002f && pick != null) release();
        return moving;
    }

    /** Lets go of the photo and the text (the GPU copy goes with the bitmap). */
    private void release() {
        generation++;
        pick = null;
        info = null;
        image = null;
        title = overview = null;
    }

    private void loadImage(final Recs.Pick p) {
        final int gen = ++generation;
        image = null;
        loader.execute(() -> {
            File f = recs.bigImage(p);
            final Bitmap b = f == null ? null : decode(f);
            main.post(() -> {
                if (gen != generation || !open) return;     // closed or another title: dropped (the GC frees it)
                image = b;
                imageAt = SystemClock.uptimeMillis();
                changed.run();
            });
        });
    }

    /** At the UI's size or just above it, straight into GPU memory. */
    private static Bitmap decode(File f) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W && bounds.outHeight / (opts.inSampleSize * 2) >= Backdrop.H) {
                opts.inSampleSize *= 2;
            }
            opts.inPreferredConfig = Bitmap.Config.HARDWARE;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b != null) return b;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            b = BitmapFactory.decodeFile(f.getPath(), opts);
            return b == null ? null : Backdrop.gpu(b);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("popup image: " + e.getMessage());
            return null;
        }
    }

    /** Lays the text out from the bottom up: buttons, where it streams, cast, overview, ratings, details, title. */
    private void layout() {
        Recs.Pick p = pick;
        if (p == null || titlePaint == null) return;
        String name = p.title == null ? "" : p.title;
        int width = Math.round(TEXT_W * k);
        title = StaticLayout.Builder.obtain(name, 0, name.length(), titlePaint, width)
                .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 0.94f).setIncludePad(false).build();
        String about = p.overview == null ? "" : p.overview;
        overview = about.isEmpty() ? null : StaticLayout.Builder.obtain(about, 0, about.length(), bodyPaint, width)
                .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0, 1.08f).setIncludePad(false).build();
        Recs.Info in = info;
        line = in != null && !in.line.isEmpty() ? in.line : p.meta == null ? "" : p.meta;
        cast = in == null || in.cast.isEmpty() ? "" : fit(Ui.trf("Starring {names}", "names", in.cast), smallPaint);
        platforms = in == null || in.platforms.isEmpty() ? "" : fit(Ui.trf("Streaming on {services}", "services", in.platforms), smallPaint);
        line = fit(line, linePaint);
        String app = recs.playsIn(p);
        labels[PLAY] = app == null || app.isEmpty() ? Ui.tr("Play") : Ui.trf("Play in {app}", "app", app);
        labels[TRAILER] = Ui.tr("Watch trailer");

        float x = LEFT * k, y = BOTTOM * k - BUTTON_H * k;
        float play = Symbols.width(context, "play", 29 * k);
        for (int i = 0; i < 2; i++) {
            float bw = buttonPaint.measureText(labels[i]) + 2 * 40 * k + (i == PLAY ? play + 12 * k : 0);
            buttons[i].set(x, y, x + bw, y + BUTTON_H * k);
            x += bw + 24 * k;
        }
        y -= 46 * k;
        platformsBase = y;
        if (!platforms.isEmpty()) y -= 40 * k;
        castBase = y;
        if (!cast.isEmpty()) y -= 40 * k;
        if (overview != null) {
            y -= 8 * k;
            overviewTop = y - overview.getHeight();
            y = overviewTop - 30 * k;
        }
        List<String[]> ratings = in == null ? null : in.ratings;
        int n = ratings == null ? 0 : ratings.size();
        chipNames = new String[n];
        chipValues = new String[n];
        chips = new RectF[n];
        float cx = LEFT * k, top = y - CHIP_H * k;
        for (int i = 0; i < n; i++) {
            chipNames[i] = ratings.get(i)[0];
            chipValues[i] = ratings.get(i)[1];
            float cw = chipName.measureText(chipNames[i]) + 10 * k + chipValue.measureText(chipValues[i]) + 2 * 18 * k;
            chips[i] = new RectF(cx, top, cx + cw, top + CHIP_H * k);
            cx += cw + 14 * k;
        }
        if (n > 0) y = top - 28 * k;
        lineBase = y;
        y -= Ui.cap(linePaint) + 30 * k;
        titleTop = y - title.getHeight();
    }

    private String fit(String s, Paint p) {
        TextPaint t = new TextPaint(p);
        return TextUtils.ellipsize(s, t, TEXT_W * k, TextUtils.TruncateAt.END).toString();
    }

    void draw(Canvas c) {
        if (pick == null || title == null) return;
        // Until the photo is in: the rows, dimmed, show through.
        fill.setShader(null);
        fill.setColor(0xD90B0B0D);
        c.drawRect(0, 0, w, h, fill);
        if (image != null) {
            float e = Ui.clamp((SystemClock.uptimeMillis() - imageAt) / (float) FADE_MS);
            e = e * e * (3 - 2 * e);
            int alpha = Math.round(255 * e);
            float s = Math.max((float) w / image.getWidth(), (float) h / image.getHeight());
            float iw = image.getWidth() * s, ih = image.getHeight() * s;
            photo.set((w - iw) / 2, (h - ih) / 2, (w + iw) / 2, (h + ih) / 2);
            imagePaint.setAlpha(alpha);
            c.drawBitmap(image, null, photo, imagePaint);
            fill.setShader(leftScrim);
            fill.setAlpha(alpha);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(bottomScrim);
            c.drawRect(0, h * 0.4f, w, h, fill);
            fill.setShader(null);
            fill.setAlpha(255);
        }
        float x = LEFT * k;
        c.save();
        c.translate(x, titleTop);
        title.draw(c);
        c.restore();
        if (!line.isEmpty()) c.drawText(line, x, lineBase, linePaint);
        for (int i = 0; i < chips.length; i++) {
            RectF r = chips[i];
            fill.setColor(0x33FFFFFF);
            c.drawRoundRect(r, r.height() / 2, r.height() / 2, fill);
            float base = r.centerY() + Ui.halfCap(chipValue);
            c.drawText(chipNames[i], r.left + 18 * k, base, chipName);
            c.drawText(chipValues[i], r.left + 18 * k + chipName.measureText(chipNames[i]) + 10 * k, base, chipValue);
        }
        if (overview != null) {
            c.save();
            c.translate(x, overviewTop);
            overview.draw(c);
            c.restore();
        }
        if (!cast.isEmpty()) c.drawText(cast, x, castBase, smallPaint);
        if (!platforms.isEmpty()) c.drawText(platforms, x, platformsBase, smallPaint);
        for (int i = 0; i < 2; i++) {
            float f = Ui.clamp(focus[i].value), s = 1 + 0.06f * focus[i].value;
            RectF r = buttons[i];
            cell.set(r.centerX() - r.width() * s / 2, r.centerY() - r.height() * s / 2,
                    r.centerX() + r.width() * s / 2, r.centerY() + r.height() * s / 2);
            fill.setColor(blend(0x38FFFFFF, 0xFFFFFFFF, f));
            c.drawRoundRect(cell, cell.height() / 2, cell.height() / 2, fill);
            int ink = blend(0xFFFFFFFF, 0xFF1D1D1F, f);
            buttonPaint.setColor(ink);
            buttonPaint.setTextSize(29 * k * s);
            float tx = cell.left + 40 * k * s, base = cell.centerY() + Ui.halfCap(buttonPaint);
            if (i == PLAY) {
                float pw = Symbols.width(context, "play", 29 * k * s);
                Symbols.draw(context, c, "play", tx + pw / 2, cell.centerY(), 29 * k * s, ink);
                tx += pw + 12 * k * s;
            }
            c.drawText(labels[i], tx, base, buttonPaint);
        }
        buttonPaint.setTextSize(29 * k);
    }

    /** What TalkBack reads on Play: the whole popup. */
    String spokenText() {
        Recs.Pick p = pick;
        if (p == null) return "";
        StringBuilder s = new StringBuilder(labels[PLAY]).append(". ").append(p.title == null ? "" : p.title);
        if (!line.isEmpty()) s.append(", ").append(line);
        for (int i = 0; i < chipNames.length; i++) s.append(", ").append(chipNames[i]).append(' ').append(chipValues[i]);
        if (p.overview != null && !p.overview.isEmpty()) s.append(". ").append(p.overview);
        if (!cast.isEmpty()) s.append(". ").append(cast);
        if (!platforms.isEmpty()) s.append(". ").append(platforms);
        return s.toString();
    }

    String label(int i) {
        return labels[i];
    }

    private static int blend(int a, int b, float t) {
        int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return (Math.round(aa + (ba - aa) * t) << 24) | (Math.round(ar + (br - ar) * t) << 16)
                | (Math.round(ag + (bg - ag) * t) << 8) | Math.round(ab + (bb - ab) * t);
    }
}
