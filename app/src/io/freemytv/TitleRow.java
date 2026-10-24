package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * One sideways row of titles on the picks tabs ("Trending now"...): its cards, the selected one, how far it has slid,
 * and the pictures of only the cards around the screen: 7, the 5 on screen and one on each side. Moving along loads
 * the next one in the background before it slides in and lets go of the ones left behind. Each picture is cut once to
 * the focused card's size with its rounded corners (one plain draw per card), and kept in GPU memory (in the app's
 * memory on Android 8 to 11, where resting rows are drawn into a picture of their own, see HomeView.drawPickRow).
 */
final class TitleRow {
    static final int VISIBLE = 5, AROUND = 1;

    /** Where a title's picture is on disk, downloading it if needed (called on the loader thread; null: none). */
    interface Source { File file(Recs.Pick p); }

    String title = "", theme = "";     // its heading, and Recs' theme and kind (to ask for the next page)
    String kind;
    List<Recs.Pick> items = new ArrayList<>();
    int index;                                              // the selected card, kept while the focus is elsewhere
    final Spring scroll = new Spring(0.38f, 0.9f, 0);       // the first card on screen (in cards)
    Spring[] focus = new Spring[0], label = new Spring[0];
    RectF[] rects = new RectF[0];
    int version;            // goes up when what a resting row shows changes: its heading, titles or pictures
    boolean still;          // nothing moved in the last step: the row can be drawn from a picture of itself

    private final Executor loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final Map<String, Bitmap> art = new HashMap<>();   // by Recs.key, only inside the window
    private final Set<String> loading = new HashSet<>();
    private final Set<String> failed = new HashSet<>();     // no picture to be had: the card can be selected anyway
    private volatile Set<String> wanted = Collections.emptySet();
    private int width, height;
    private float radius;

    TitleRow(Executor loader, Runnable changed) {
        this.loader = loader;
        this.changed = changed;
    }

    /** New titles: the selection stays on the same title when it's still there; returns true if anything changed. */
    boolean set(String heading, String theme, String kind, List<Recs.Pick> list) {
        this.theme = theme == null ? "" : theme;
        this.kind = kind;
        boolean same = list.size() == items.size();
        for (int i = 0; same && i < list.size(); i++) same = Recs.key(list.get(i)).equals(Recs.key(items.get(i)));
        String head = heading == null ? "" : heading;
        if (!head.equals(title)) version++;
        title = head;
        if (same) {
            items = list;   // the same titles, maybe with more details
            return false;
        }
        String selected = items.isEmpty() ? null : Recs.key(items.get(Math.min(index, items.size() - 1)));
        int at = -1;
        for (int i = 0; selected != null && i < list.size(); i++) if (Recs.key(list.get(i)).equals(selected)) at = i;
        index = at >= 0 ? at : Math.min(index, Math.max(0, list.size() - 1));
        items = list;
        version++;
        if (focus.length < list.size()) {
            Spring[] f = new Spring[list.size()], l = new Spring[list.size()];
            RectF[] r = new RectF[list.size()];
            for (int i = 0; i < f.length; i++) {
                f[i] = i < focus.length ? focus[i] : new Spring(0.32f, 0.78f, 0);
                l[i] = i < label.length ? label[i] : new Spring(0.3f, 1f, 0);
                r[i] = i < rects.length ? rects[i] : new RectF();
            }
            focus = f;
            label = l;
            rects = r;
        }
        scroll.target = first(scroll.target, index, items.size());
        return true;
    }

    /**
     * Aims the springs: the selected card grows while the row has the focus, its title shows while the row is the
     * current one, and the row slides to keep it on screen. Returns true if a target changed.
     */
    boolean aim(boolean focused, boolean current, boolean snap) {
        boolean changed = false;
        for (int i = 0; i < focus.length; i++) {
            float f = focused && i == index ? 1 : 0, l = current && i == index ? 1 : 0;
            changed |= focus[i].target != f || label[i].target != l;
            focus[i].target = f;
            label[i].target = l;
            if (snap) {
                focus[i].snap(f);
                label[i].snap(l);
            }
        }
        float first = first(scroll.target, index, items.size());
        changed |= first != scroll.target;
        scroll.target = first;
        if (snap) scroll.snap(first);
        return changed;
    }

    /** Advances the animations; returns true while something moves. */
    boolean step(float dt) {
        boolean moving = scroll.step(dt);
        for (Spring s : focus) moving |= s.step(dt);
        for (Spring s : label) moving |= s.step(dt);
        still = !moving;
        return moving;
    }

    /**
     * The cards' places (cw × ch, gap apart), slid by the scroll; the cards beside a focused one move `push` aside
     * (as much as it grows on each side), so it grows without covering them.
     */
    void layout(float left, float right, float top, float cw, float ch, float gap, float push) {
        int n = Math.min(rects.length, items.size());
        float total = 0, before = 0;
        for (int i = 0; i < n; i++) total += focus[i].value;
        for (int i = 0; i < n; i++) {
            float f = focus[i].value, after = total - before - f;
            float x = left + (i - scroll.value) * (cw + gap) + push * (before - after);
            rects[i].set(x, top, x + cw, top + ch);
            before += f;
        }
        keepInside(rects, focus, n, push, left, right);
    }

    /**
     * A selected card at either end grows past the row's edge: the whole row slides in by that much, so the grown
     * card keeps the margin to the screen's edge (`push`: how far a fully selected card grows on each side). Only
     * its growth is made up for, never more: a card still sliding in from off screen moves the row no further.
     */
    static void keepInside(RectF[] rects, Spring[] focus, int n, float push, float left, float right) {
        float shift = 0;
        for (int i = 0; i < n; i++) {
            float grow = push * Math.min(1, focus[i].value);
            if (grow <= 0) continue;
            shift += clamp(left - (rects[i].left - grow), grow) - clamp(rects[i].right + grow - right, grow);
        }
        if (shift != 0) for (int i = 0; i < n; i++) rects[i].offset(shift, 0);
    }

    private static float clamp(float overflow, float most) {
        return Math.max(0, Math.min(most, overflow));
    }

    Recs.Pick selected() {
        return items.isEmpty() ? null : items.get(Math.min(index, items.size() - 1));
    }

    /**
     * The first card on screen that keeps `index` one card in from the edges while more lie beyond them (the focus
     * walks along the visible cards; the row slides only near an edge), starting from `first`.
     */
    static float first(float first, int index, int count) {
        if (index - 1 < first) first = index - 1;
        else if (index + 1 > first + VISIBLE - 1) first = index + 1 - (VISIBLE - 1);
        return Math.max(0, Math.min(Math.max(0, count - VISIBLE), first));
    }

    Bitmap art(Recs.Pick p) {
        return art.get(Recs.key(p));
    }

    /** Its picture is in (or there's none to wait for): the selection may move onto it. */
    boolean ready(Recs.Pick p) {
        String key = Recs.key(p);
        return art.containsKey(key) || failed.contains(key);
    }

    /**
     * Keeps the pictures of the cards on screen and `around` cards on each side of them, at w × h, and loads what's
     * missing: the ones on screen first, then the ones ahead, then the ones behind. around < 0: none (off screen).
     * Corners are rounded by `r` (at w × h).
     */
    void load(int around, int w, int h, float r, Source source) {
        if (w < 16 || h < 16) return;
        if (w != width || h != height || r != radius) {
            art.clear();    // another size: cut again
            width = w;
            height = h;
            radius = r;
            version++;
        }
        List<Recs.Pick> order = new ArrayList<>();
        if (around >= 0) {
            int first = Math.round(scroll.target);
            int from = Math.max(0, first - around), to = Math.min(items.size(), first + VISIBLE + around);
            for (int i = first; i < to; i++) order.add(items.get(i));
            for (int i = Math.min(first, to) - 1; i >= from; i--) order.add(items.get(i));
        }
        Set<String> keep = new HashSet<>();
        for (Recs.Pick p : order) keep.add(Recs.key(p));
        wanted = keep;
        if (art.keySet().retainAll(keep)) version++;
        for (final Recs.Pick p : order) {
            final String key = Recs.key(p);
            if (art.containsKey(key) || failed.contains(key) || !loading.add(key)) continue;
            loader.execute(() -> {
                Bitmap cut = wanted.contains(key) ? cut(source.file(p), w, h, r) : null;   // scrolled away: skipped
                main.post(() -> {
                    loading.remove(key);
                    if (!wanted.contains(key) || w != width || h != height) return;
                    version++;
                    if (cut == null) {
                        failed.add(key);
                        changed.run();
                        return;
                    }
                    art.put(key, cut);
                    changed.run();
                });
            });
        }
    }

    void clear() {
        wanted = Collections.emptySet();
        art.clear();
        failed.clear();
        version++;
    }

    /** The center of the picture at w × h with corners rounded by r, decoded no bigger than needed. */
    private static Bitmap cut(File f, int w, int h, float r) {
        if (f == null || !f.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= w && bounds.outHeight / (opts.inSampleSize * 2) >= h) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) return null;
            float scale = Math.max(w / (float) b.getWidth(), h / (float) b.getHeight());
            BitmapShader shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(scale, scale);
            m.postTranslate((w - b.getWidth() * scale) / 2, (h - b.getHeight() * scale) / 2);
            shader.setLocalMatrix(m);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            paint.setShader(shader);
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawRoundRect(new RectF(0, 0, w, h), r, r, paint);
            b.recycle();
            // Android 8 to 11 draw resting rows in software (HomeView.drawPickRow), which can't read GPU pictures.
            return Ui.LEGACY ? out : Backdrop.gpu(out);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("row picture: " + e);
            return null;
        }
    }
}
