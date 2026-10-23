package io.freemytv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
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
 * and the pictures of only the cards around the screen. In the focused row that is 15: the 5 on screen, the 5 just
 * scrolled past and the 5 coming next; moving along loads the next ones in the background before they slide in and
 * lets go of the ones left behind. Each picture is cut once to the focused card's size and kept in GPU memory.
 */
final class TitleRow {
    static final int VISIBLE = 5, AROUND = 5, MAX = 20;

    /** Where a title's picture is on disk, downloading it if needed (called on the loader thread; null: none). */
    interface Source { File file(Recs.Pick p); }

    String title = "";
    List<Recs.Pick> items = new ArrayList<>();
    int index;                                              // the selected card, kept while the focus is elsewhere
    final Spring scroll = new Spring(0.38f, 0.9f, 0);       // the first card on screen (in cards)
    Spring[] focus = new Spring[0], label = new Spring[0];
    RectF[] rects = new RectF[0];

    private final Executor loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable changed;
    private final Map<String, Bitmap> art = new HashMap<>();   // by Recs.key, only inside the window
    private final Set<String> loading = new HashSet<>();
    private volatile Set<String> wanted = Collections.emptySet();
    private int width, height;

    TitleRow(Executor loader, Runnable changed) {
        this.loader = loader;
        this.changed = changed;
    }

    /** New titles: the selection stays on the same title when it's still there; returns true if anything changed. */
    boolean set(String heading, List<Recs.Pick> list) {
        if (list.size() > MAX) list = new ArrayList<>(list.subList(0, MAX));
        boolean same = list.size() == items.size();
        for (int i = 0; same && i < list.size(); i++) same = Recs.key(list.get(i)).equals(Recs.key(items.get(i)));
        title = heading == null ? "" : heading;
        if (same) {
            items = list;   // the same titles, maybe with more details
            return false;
        }
        String selected = items.isEmpty() ? null : Recs.key(items.get(Math.min(index, items.size() - 1)));
        int at = -1;
        for (int i = 0; selected != null && i < list.size(); i++) if (Recs.key(list.get(i)).equals(selected)) at = i;
        index = at >= 0 ? at : Math.min(index, Math.max(0, list.size() - 1));
        items = list;
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
        return moving;
    }

    /** The cards' places (cw × ch, gap apart), slid by the scroll. */
    void layout(float left, float top, float cw, float ch, float gap) {
        for (int i = 0; i < rects.length; i++) {
            float x = left + (i - scroll.value) * (cw + gap);
            rects[i].set(x, top, x + cw, top + ch);
        }
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

    /**
     * Keeps the pictures of the cards on screen and `around` cards on each side of them, at w × h, and loads what's
     * missing: the ones on screen first, then the ones ahead, then the ones behind. around < 0: none (off screen).
     */
    void load(int around, int w, int h, Source source) {
        if (w < 16 || h < 16) return;
        if (w != width || h != height) {
            art.clear();    // another size: cut again
            width = w;
            height = h;
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
        art.keySet().retainAll(keep);
        for (final Recs.Pick p : order) {
            final String key = Recs.key(p);
            if (art.containsKey(key) || !loading.add(key)) continue;
            loader.execute(() -> {
                Bitmap cut = wanted.contains(key) ? cut(source.file(p), w, h) : null;   // scrolled away: skipped
                main.post(() -> {
                    loading.remove(key);
                    if (cut == null || !wanted.contains(key) || w != width || h != height) return;
                    art.put(key, cut);
                    changed.run();
                });
            });
        }
    }

    void clear() {
        wanted = Collections.emptySet();
        art.clear();
    }

    /** The center of the picture at w × h, decoded no bigger than needed, in GPU memory. */
    private static Bitmap cut(File f, int w, int h) {
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
            int sw = Math.round(w / scale), sh = Math.round(h / scale);
            int x = (b.getWidth() - sw) / 2, y = (b.getHeight() - sh) / 2;
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(b, new Rect(x, y, x + sw, y + sh), new Rect(0, 0, w, h),
                    new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG));
            b.recycle();
            return Backdrop.gpu(out);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w("row picture: " + e);
            return null;
        }
    }
}
