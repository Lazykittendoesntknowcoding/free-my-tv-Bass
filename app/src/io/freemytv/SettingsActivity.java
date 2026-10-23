package io.freemytv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free My TV's settings (the app's icon, "All Apps", or the gear on the home screen), in the home screen's look: its
 * light gray background by day and dark one at night (the Theme setting), the title and notes on the left, the list on
 * the right as glass rows, the focused row white with a shadow (the tvOS focus). "Home screen"
 * opens the Free My TV / Original choice (with a check on the current one); the switch runs in the main process
 * (HomeMode) and this screen only asks for it and follows the state file.
 */
public class SettingsActivity extends Activity {
    private SettingsView view;
    private DreamView preview;   // "Screensaver": the screensaver right here, without changing the system setting

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new SettingsView(this);
        setContentView(view);
    }

    @Override protected void onResume() {
        super.onResume();
        view.resume();
        // From ADB: am start -n io.freemytv/.SettingsActivity --ez screensaver true (straight to the preview).
        if (preview == null && getIntent().getBooleanExtra("screensaver", false)) {
            getIntent().removeExtra("screensaver");
            view.pause();
            showPreview();
        }
        // The home screen's Content button: straight to Content.
        if (preview == null && getIntent().getBooleanExtra("content", false)) {
            getIntent().removeExtra("content");
            view.openContentShortcut();
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override protected void onPause() {
        closePreview();     // before pausing the view: closing the preview resumes it (view.resume)
        view.pause();
        super.onPause();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (preview != null) {
            // Like the real screensaver: arrows, OK or Back close it (other keys go on to the system).
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_BACK:
                    if (event.getAction() == KeyEvent.ACTION_UP) closePreview();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }
        if (view.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    void showPreview() {
        preview = new DreamView(this);
        setContentView(preview);
        preview.start();
    }

    private void closePreview() {
        if (preview == null) return;
        preview.stop();
        preview = null;
        setContentView(view);
        view.resume();
    }

    static final class SettingsView extends View implements Choreographer.FrameCallback {
        private static final int MAIN = 0, HOME_CHOICE = 1, HOME_SETTINGS = 2, REMOTE_KEYS = 3, PICKER = 4, SHELF_EDIT = 5,
                CONTENT_TABS = 6, CONTENT_EDIT = 7, CONTENT_ADD = 8;
        private static final float LIST_ROW_H = 68, LIST_ROW_GAP = 12;
        private static final int PICK_VISIBLE = 9;
        // The list on the right; on the left, the title, the notes and (main menu) the info card.
        private static final float LIST_X = 980, LIST_R = 1840, LIST_TOP = 150, ROW_GAP = 14, CHOICE_H = 118;
        private static final float LEFT_X = 80, LEFT_R = 860, TITLE_BASE = 130, CARD_TOP = 300;
        // The main menu: ten rows; the comparison, the picks (with the TMDB credit) and two notices go on the left.
        private static final float MAIN_TOP = 150, ROW_H = 64, MAIN_GAP = 10;
        // The home screen's colors: dark text by day, white at night; white glass by day, smoked at night.
        private static final int AMBER = 0xFFFFC266, DEEP_AMBER = 0xFFB25A00, INK = 0xFF1C1C1E, L_INK = 0xFF1D1D1F,
                L_DIM = 0xFF6E6E73, L_FAINT = 0xFF8E8E93, WHITE = 0xFFF5F5F7, SMOKE = 0xFF111113;
        private static final int MAIN_ROWS = 10, CONTENT_MAIN = 4;   // Content: below Shelf
        private static final int OPTIMIZE_ROW = 8, DEEP_ROW = 9;      // the last two: they run right here

        private final SettingsActivity activity;
        private final Scene scene = new Scene(false);
        private final Shadows shadows = new Shadows();
        private final int[] glassPx = new int[Backdrop.GW * Backdrop.GH], frostPx = new int[Backdrop.FW * Backdrop.FH];
        private android.graphics.Shader dayGradient, nightGradient;
        private boolean night, nightSet;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint fitPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        // The left column's note, the picks' status and the TMDB credit, wrapped (rebuilt only when they change).
        private StaticLayout noteLayout, statusLayout, creditLayout;
        private String noteShown, statusShown, creditShown;
        // RAM and storage: used and total bytes (read with the state, every 2 s), for the two gauges on the left.
        private long ramUsed, ramTotal, diskUsed, diskTotal;
        private String ramText = "", diskText = "";
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Spring[] pop = new Spring[12];
        private final List<String> appPkgs = new ArrayList<>(), appLabels = new ArrayList<>();
        // The list picker (the app of a remote button): what it shows, which one is in use, where we are.
        private final List<String> pickLabels = new ArrayList<>();
        // The shelf editor (also the top buttons' one, with editingTop): what it can hold (selected ones first, in
        // order) and the selection.
        private final List<String[]> shelfRows = new ArrayList<>();
        private final List<String> shelfOrder = new ArrayList<>();
        private boolean editingTop;
        private int pickFor, pickCurrent, pickScroll;   // pickFor: the remote button (index + 1)
        private static final int PICK_CITY = 0, PICK_LANGUAGE = -1;
        private final List<String[]> cities = new ArrayList<>();
        private String cityStatus = "";
        private final RectF tmp = new RectF(), rowRect = new RectF(), focusBox = new RectF(), card = new RectF();
        private JSONObject state = new JSONObject(), recs = new JSONObject(), optimize = new JSONObject();
        private boolean hasKey;
        private int screen = MAIN, focus;
        private boolean running, okDown, backDown, wasBusy;
        private StaticLayout errorLayout;   // the last error, wrapped (rebuilt only when it changes)
        private String errorShown;
        private final Spoken spoken;
        private long startedAt, lastNanos, pendingAt;
        private int w, h;
        private float k = 1;
        private Paint title, version, rowLabel, rowValue, rowSub, header, cellLabel, cellValue, warn, spinner, white, shadow;
        private TextPaint notice, note, credit;

        private final Runnable poll = new Runnable() {
            @Override public void run() {
                if (!running) return;
                state = HomeMode.state(getContext());
                checkNight();
                hasKey = Recs.hasKey(getContext());
                recs = Recs.status(getContext());
                optimize = Optimize.state(getContext());
                readUsage();
                boolean nowBusy = !busyText().isEmpty();
                if (nowBusy) pendingAt = 0;
                // A switch just ended: TalkBack reads how it went (the newest notice).
                JSONArray notices = state.optJSONArray("notices");
                if (wasBusy && !nowBusy && notices != null && notices.length() > 0) {
                    JSONObject newest = notices.optJSONObject(0);
                    if (newest != null) announceForAccessibility(newest.optString("text"));
                }
                wasBusy = nowBusy;
                invalidate();
                handler.postDelayed(this, busy() || !optimizing().isEmpty() ? 500 : 2000);
            }
        };

        SettingsView(SettingsActivity a) {
            super(a);
            activity = a;
            for (int i = 0; i < pop.length; i++) pop[i] = new Spring(0.28f, 0.85f, 0);
            checkNight();
            setFocusable(true);
            setContentDescription("Free My TV");
            // TalkBack: one item per row, id = row + 1.
            spoken = new Spoken(this, new Spoken.Source() {
                @Override public void items(List<Integer> out) { for (int i = 1; i <= rowCount(); i++) out.add(i); }
                @Override public String text(int id) { return id >= 1 && id <= rowCount() ? rowText(id - 1) : ""; }
                @Override public boolean bounds(int id, Rect out) {
                    if (id < 1 || id > rowCount()) return false;
                    rowBox(id - 1).round(out);
                    return true;
                }
                @Override public int focused() { return focus + 1; }
                @Override public void focus(int id) {
                    focus = id - 1;
                    invalidate();
                }
                @Override public void click(int id) { activate(); }
            });
        }

        @Override public AccessibilityNodeProvider getAccessibilityNodeProvider() {
            return spoken;
        }

        /** A row as TalkBack reads it: its name and its value, as on screen. */
        private String rowText(int row) {
            switch (screen) {
                case MAIN: {
                    if (row == 0) {
                        String busyText = busyText();
                        String value = busy() ? (busyText.isEmpty() ? Ui.tr("Starting…") : busyText)
                                : freeActive() ? "Free My TV" : Ui.tr("Original");
                        return Ui.trf("Home Screen, {mode}", "mode", value);
                    }
                    String[] names = {Ui.tr("Screensaver, preview"), Ui.tr("Home Settings"), Ui.tr("Shelf"),
                            Ui.tr("Content"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("TV Settings")};
                    if (row == OPTIMIZE_ROW || row == DEEP_ROW) {
                        String value = optimizeValue(row == DEEP_ROW ? Optimize.DEEP : Optimize.QUICK);
                        String name = row == DEEP_ROW ? Ui.tr("Deep optimize") : Ui.tr("Optimize now");
                        return value.isEmpty() ? name : name + ", " + value;
                    }
                    return row - 1 < names.length ? names[row - 1] : "";
                }
                case HOME_CHOICE: {
                    String name = row == 0 ? "Free My TV, " + Ui.tr("no ads, with the new screensaver")
                            : "Original, " + Ui.tr("the TV's factory home screen");
                    boolean inUse = (row == 0) == freeActive();
                    return name + (inUse ? Ui.tr(". In use") : "") + ", " + Ui.trf("{i} of {n}", "i", row + 1, "n", 2);
                }
                case HOME_SETTINGS:
                case REMOTE_KEYS: {
                    boolean keys = screen == REMOTE_KEYS;
                    String[] labels = keys ? keyLabels() : settingLabels(), values = keys ? keyValues() : settingValues();
                    return row < labels.length ? labels[row] + ", " + values[row] : "";
                }
                case PICKER: {
                    int at = pickScroll + row;
                    if (at >= pickLabels.size()) return "";
                    return pickLabels.get(at) + (at == pickCurrent ? Ui.tr(", selected") : "")
                            + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", pickLabels.size());
                }
                case SHELF_EDIT: {
                    int at = pickScroll + row;
                    if (at >= shelfRows.size()) return "";
                    String[] app = shelfRows.get(at);
                    int place = shelfOrder.indexOf(app[0]);
                    if (editingTop) {
                        return app[1] + ", " + (place >= 0 ? Ui.trf("in the top buttons, place {n}", "n", place + 1)
                                : Ui.tr("not in the top buttons"));
                    }
                    return app[1] + ", " + (place >= 0 ? Ui.trf("on the shelf, place {n}", "n", place + 1)
                            : Ui.tr("not on the shelf"));
                }
                case CONTENT_TABS:
                    return row < CONTENT_TABS_IDS.length ? tabName(CONTENT_TABS_IDS[row]) + ", "
                            + Ui.plural("plural|one:{n} row|other:{n} rows", Recs.rowsOn(CONTENT_TABS_IDS[row]).size()) : "";
                case CONTENT_EDIT:
                case CONTENT_ADD: {
                    int at = pickScroll + row;
                    if (at >= contentItems.size()) return "";
                    String[] item = contentItems.get(at);
                    return contentLabel(item) + ", " + contentValue(item) + ", " + Ui.trf("{i} of {n}", "i", at + 1, "n", contentItems.size());
                }
                default:
                    return "";
            }
        }

        private RectF rowBox(int row) {
            float top, height;
            if (screen == MAIN) {
                top = MAIN_TOP + row * (ROW_H + MAIN_GAP);
                height = ROW_H;
            } else if (screen == HOME_CHOICE) {
                top = LIST_TOP + row * (CHOICE_H + ROW_GAP);
                height = CHOICE_H;
            } else {
                top = LIST_TOP + row * (LIST_ROW_H + LIST_ROW_GAP);
                height = LIST_ROW_H;
            }
            focusBox.set(LIST_X * k, top * k, LIST_R * k, (top + height) * k);
            return focusBox;
        }

        void resume() {
            if (running) return;
            running = true;
            startedAt = SystemClock.uptimeMillis();
            lastNanos = 0;
            handler.post(poll);
            Choreographer.getInstance().postFrameCallback(this);
            spoken.readAgain();
        }

        void pause() {
            running = false;
            handler.removeCallbacks(poll);
            Choreographer.getInstance().removeFrameCallback(this);
        }

        @Override public void doFrame(long frameTimeNanos) {
            if (!running) return;
            boolean moving = busy();
            for (Spring s : pop) moving |= !s.settled();
            if (moving) invalidate();
            Choreographer.getInstance().postFrameCallback(this);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            w = width;
            h = height;
            k = w / 1920f;
            scene.setSize(w, h);
            dayGradient = Ui.gradient(h, 0xFFF5F5F7, 0xFFE2E2E7);
            nightGradient = Ui.gradient(h, 0xFF1C1C1E, 0xFF0B0B0D);
            title = Ui.text(Ui.displaySemibold, 76 * k, L_INK);
            version = Ui.text(Ui.regular, 23 * k, L_FAINT);
            rowLabel = Ui.text(Ui.medium, 30 * k, L_INK);
            rowValue = Ui.text(Ui.regular, 28 * k, L_DIM);
            rowValue.setTextAlign(Paint.Align.RIGHT);
            rowSub = Ui.text(Ui.regular, 24 * k, L_DIM);
            header = Ui.text(Ui.semibold, 22 * k, L_FAINT);
            header.setLetterSpacing(0.06f);
            cellLabel = Ui.text(Ui.regular, 25 * k, L_INK);
            cellValue = Ui.numbers(Ui.regular, 25 * k, L_INK);
            cellValue.setTextAlign(Paint.Align.RIGHT);
            warn = Ui.text(Ui.medium, 24 * k, DEEP_AMBER);
            notice = new TextPaint(Ui.text(Ui.regular, 23 * k, L_DIM));
            note = new TextPaint(Ui.text(Ui.medium, 27 * k, L_DIM));
            credit = new TextPaint(Ui.text(Ui.regular, 21 * k, L_FAINT));
            spinner = new Paint(Paint.ANTI_ALIAS_FLAG);
            spinner.setStyle(Paint.Style.STROKE);
            spinner.setStrokeCap(Paint.Cap.ROUND);
            spinner.setStrokeWidth(3.5f * k);
            white = new Paint(Paint.ANTI_ALIAS_FLAG);
            white.setColor(0xFFFFFFFF);
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadow.setMaskFilter(new BlurMaskFilter(22 * k, BlurMaskFilter.Blur.NORMAL));
            nightSet = false;
            checkNight();   // the colors for day or night
        }

        // MARK: - Look (the home screen's: day and night)

        /** Day or night (the Theme setting, or the sun at your city): the background, the glass and the text follow. */
        private void checkNight() {
            boolean want = HomeData.get(getContext()).isNight(System.currentTimeMillis());
            if (nightSet && want == night) return;
            night = want;
            nightSet = true;
            // No photo here: the glass reads the background's own gray, as on the home screen without one.
            int color = want ? 0xFF141416 : 0xFFEBEBF0;
            java.util.Arrays.fill(glassPx, color);
            java.util.Arrays.fill(frostPx, color);
            scene.setFrost(frostPx);
            scene.setGlassImage(glassPx);
            if (title == null) return;
            title.setColor(tone(L_INK, WHITE));
            version.setColor(tone(L_FAINT, 0x8CFFFFFF));
            rowSub.setColor(tone(L_DIM, 0x99FFFFFF));
            header.setColor(tone(L_FAINT, 0x80FFFFFF));
            cellLabel.setColor(tone(L_INK, 0xE6FFFFFF));
            warn.setColor(tone(DEEP_AMBER, AMBER));
            notice.setColor(tone(L_DIM, 0xB3FFFFFF));
            note.setColor(tone(L_DIM, 0xFF98989D));
            credit.setColor(tone(L_FAINT, 0x8CFFFFFF));
            noteLayout = statusLayout = creditLayout = null;
            invalidate();
        }

        private int tone(int day, int dark) {
            return night ? dark : day;
        }

        /** Text on a row: dark by day, white at night; on the focused (white) row, dark. */
        private int ink(float f) {
            return blend(tone(L_INK, 0xF2FFFFFF), INK, f);
        }

        private int dim(float f) {
            return blend(tone(L_DIM, 0xADFFFFFF), 0xB31C1C1E, f);
        }

        private int faint(float f) {
            return blend(tone(0x991D1D1F, 0x73FFFFFF), 0x801C1C1E, f);
        }

        /** Glass for a row or a card: white by day, smoked at night, with the home screen's soft shadow by day. */
        private void glass(Canvas c, RectF r, float radius, float alpha) {
            if (alpha <= 0.004f) return;
            if (!night) {
                tmp.set(r);
                tmp.offset(0, 6 * k);
                shadow.setColor(Ui.alpha(0x14000000, alpha));
                shadows.draw(c, tmp, radius, 22 * k, shadow);
                scene.glass(c, r, radius, 0xFFFFFFFF, 0.7f, 0, 0, 0.02f, 1, alpha);
            } else {
                scene.glass(c, r, radius, SMOKE, 0.4f, 0, 0, 0.02f, 1, alpha);
            }
        }

        /** The title on the left, shrunk (then cut) to fit the column, and the screen's note wrapped below it. */
        private void drawHeading(Canvas c, String text, String noteText) {
            float room = (LEFT_R - LEFT_X) * k;
            title.setTextSize(76 * k);
            float wide = title.measureText(text);
            if (wide > room) title.setTextSize(Math.max(52 * k, 76 * k * room / wide));
            fitPaint.set(title);
            String shown = TextUtils.ellipsize(text, fitPaint, room, TextUtils.TruncateAt.END).toString();
            c.drawText(shown, LEFT_X * k - Ui.leftBearing(title, shown), TITLE_BASE * k, title);
            title.setTextSize(76 * k);
            if (noteText == null || noteText.isEmpty()) return;
            int width = Math.round(room);
            if (noteLayout == null || !noteText.equals(noteShown) || noteLayout.getWidth() != width) {
                noteShown = noteText;
                noteLayout = StaticLayout.Builder.obtain(noteText, 0, noteText.length(), note, width)
                        .setLineSpacing(6 * k, 1).setMaxLines(6).setEllipsize(TextUtils.TruncateAt.END).build();
            }
            c.save();
            c.translate(LEFT_X * k, (TITLE_BASE + 70) * k - noteLayout.getLineBaseline(0));
            noteLayout.draw(c);
            c.restore();
        }

        private void drawListBackground(Canvas c) {
            fill.setShader(night ? nightGradient : dayGradient);
            c.drawRect(0, 0, w, h, fill);
            fill.setShader(null);
        }

        private boolean busy() {
            return !busyText().isEmpty() || (pendingAt != 0 && SystemClock.uptimeMillis() - pendingAt < 10_000);
        }

        private String busyText() {
            long at = state.optLong("busyAt"), now = SystemClock.elapsedRealtime();
            boolean stale = now < at || now - at > 10 * 60_000L;
            return stale ? "" : state.optString("busy");
        }

        private boolean freeActive() {
            return HomeMode.FREE.equals(state.optString("mode")) && !state.optBoolean("homeReverted");
        }

        private int rowCount() {
            switch (screen) {
                case MAIN: return MAIN_ROWS;
                case HOME_CHOICE: return 2;
                case HOME_SETTINGS: return settingLabels().length;
                case PICKER: return Math.min(PICK_VISIBLE, pickLabels.size());
                case SHELF_EDIT: return Math.min(PICK_VISIBLE, shelfRows.size());
                case CONTENT_TABS: return CONTENT_TABS_IDS.length;
                case CONTENT_EDIT:
                case CONTENT_ADD: return Math.min(PICK_VISIBLE, contentItems.size());
                default: return Prefs.KEYS.length;
            }
        }

        @Override protected void onDraw(Canvas c) {
            if (w == 0) return;
            long now = System.nanoTime();
            float dt = lastNanos == 0 ? 1 / 60f : Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            for (int i = 0; i < pop.length; i++) {
                pop[i].target = i == focus && i < rowCount() ? 1 : 0;
                pop[i].step(dt);
            }
            float seconds = (SystemClock.uptimeMillis() - startedAt) / 1000f;
            drawListBackground(c);

            if (screen == MAIN) drawMain(c, seconds); else if (screen == HOME_CHOICE) drawChoice(c); else if (screen == PICKER) drawPicker(c); else if (screen == SHELF_EDIT) drawShelfEditor(c);
            else if (screen == CONTENT_TABS || screen == CONTENT_EDIT || screen == CONTENT_ADD) drawContent(c); else drawList(c);
        }

        private static final String[] MAIN_SYMBOLS = {"home", "bedtime", "settings", "apps", "content", "live_tv",
                "gamepad", "tv_settings", "sparkles", "sync"};

        private void drawMain(Canvas c, float seconds) {
            boolean working = busy();
            String busyText = busyText();
            drawHeading(c, "Free My TV", Ui.tr("Your TV's home screen and screensaver"));
            c.drawText(versionName(getContext()), LEFT_X * k, (TITLE_BASE + 112) * k, version);
            drawInfoCard(c);

            String mode = freeActive() ? "Free My TV" : Ui.tr("Original");
            String[] labels = {Ui.tr("Home Screen"), Ui.tr("Screensaver"), Ui.tr("Home Settings"), Ui.tr("Shelf"),
                    Ui.tr("Content"), Ui.tr("Top Buttons"), Ui.tr("Remote Buttons"), Ui.tr("TV Settings"),
                    Ui.tr("Optimize now"), Ui.tr("Deep optimize")};
            String running = optimizing();
            String[] values = {working ? "" : mode, Ui.tr("Preview"), "", "", "", "", "", "",
                    optimizeValue(Optimize.QUICK), optimizeValue(Optimize.DEEP)};
            for (int i = 0; i < labels.length; i++) {
                float y = (MAIN_TOP + i * (ROW_H + MAIN_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(ink(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                // The symbols differ in width: each is centered in the same column, so the names line up.
                Symbols.draw(getContext(), c, MAIN_SYMBOLS[i], rowRect.left + 52 * k, rowRect.centerY(), 30 * k, ink(f));
                c.drawText(labels[i], rowRect.left + 92 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                // Optimizing: the current step, with the spinning arc, as the Home Screen row while it switches.
                boolean spin = (i == 0 && working) || (i == OPTIMIZE_ROW && running.equals(Optimize.QUICK))
                        || (i == DEEP_ROW && running.equals(Optimize.DEEP));
                if (i >= 2 && i < OPTIMIZE_ROW) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, faint(f));
                } else if (spin) {
                    // Switching: the current step in place of the value, with the spinning arc.
                    String text = i != 0 ? values[i] : busyText.isEmpty() ? Ui.tr("Starting…") : busyText;
                    rowValue.setColor(dim(f));
                    fitPaint.set(rowValue);
                    String shown = TextUtils.ellipsize(text, fitPaint, 470 * k, TextUtils.TruncateAt.END).toString();
                    c.drawText(shown, right, base, rowValue);
                    float sx = right - rowValue.measureText(shown) - 26 * k;
                    spinner.setColor(ink(f));
                    Ui.spinner(c, sx, rowRect.centerY(), 12 * k, seconds * 1.3f, spinner);
                } else {
                    rowValue.setColor(dim(f));
                    fitPaint.set(rowValue);
                    c.drawText(TextUtils.ellipsize(values[i], fitPaint, 470 * k, TextUtils.TruncateAt.END).toString(), right, base, rowValue);
                }
            }

            float y = (MAIN_TOP + labels.length * (ROW_H + MAIN_GAP) + 34) * k;
            String error = state.optString("error");
            if (working && busyText.equals(Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”)."))) {
                c.drawText(Ui.tr("On the TV: tick “Always allow” and press OK."), LIST_X * k + 30 * k, y, warn);
            } else if (!working && !error.isEmpty()) {
                // The whole message, on up to five lines: some say what to do (the Wi-Fi one, #14).
                int width = Math.round((LIST_R - LIST_X - 60) * k);
                if (errorLayout == null || !error.equals(errorShown) || errorLayout.getWidth() != width
                        || errorLayout.getPaint().getColor() != warn.getColor()) {
                    errorShown = error;
                    errorLayout = StaticLayout.Builder.obtain(error, 0, error.length(), new TextPaint(warn), width)
                            .setMaxLines(5).setEllipsize(TextUtils.TruncateAt.END).build();
                }
                c.save();
                c.translate(LIST_X * k + 30 * k, y - errorLayout.getLineBaseline(0));
                errorLayout.draw(c);
                c.restore();
            }
        }

        /** On the left, under the title: one glass card with the comparison, the picks and the latest notices. */
        private void drawInfoCard(Canvas c) {
            card.set(LEFT_X * k, CARD_TOP * k, LEFT_R * k, (MAIN_TOP + MAIN_ROWS * (ROW_H + MAIN_GAP) - MAIN_GAP) * k);   // ends with the list
            glass(c, card, 30 * k, 1);
            float y = drawComparison(c, (CARD_TOP + 64) * k);
            y = drawRecs(c, y + 62 * k);
            drawNotices(c, y + 58 * k);
            float gw = (LEFT_R - LEFT_X - 40) / 2f * k, top = (MAIN_TOP + MAIN_ROWS * (ROW_H + MAIN_GAP) + 40) * k;
            drawGauge(c, LEFT_X * k, top, gw, Ui.tr("MEMORY"), ramUsed, ramTotal, ramText);
            drawGauge(c, LEFT_X * k + gw + 40 * k, top, gw, Ui.tr("STORAGE"), diskUsed, diskTotal, diskText);
        }

        private void readUsage() {
            android.app.ActivityManager am = (android.app.ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                ramTotal = mi.totalMem;
                ramUsed = mi.totalMem - mi.availMem;
            }
            try {
                android.os.StatFs fs = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                diskTotal = fs.getTotalBytes();
                diskUsed = diskTotal - fs.getAvailableBytes();
            } catch (IllegalArgumentException e) {
                diskTotal = 0;
            }
            ramText = usedOf(ramUsed, ramTotal);
            diskText = usedOf(diskUsed, diskTotal);
        }

        private static String usedOf(long used, long total) {
            if (total <= 0) return "";
            return Ui.trf("{used} of {total}", "used", size(used), "total", size(total));
        }

        private static String size(long bytes) {
            return Optimize.size(bytes);
        }

        /** What runs now (Optimize.QUICK or DEEP), or "". */
        private String optimizing() {
            return Optimize.running(optimize);
        }

        /** An optimize row's value: its current step, its error, or what the last run did. */
        private String optimizeValue(String kind) {
            if (kind.equals(optimizing())) return optimize.optString("busy");
            if (kind.equals(optimize.optString("errorKind")) && optimize.has("error")) return optimize.optString("error");
            JSONObject last = optimize.optJSONObject(kind);
            if (last == null) return kind.equals(Optimize.DEEP) ? Ui.tr("Takes a few minutes") : "";
            if (kind.equals(Optimize.DEEP)) return Ui.trf("Done · {when}", "when", ago(last.optLong("at")));
            return Ui.trf("Freed {ram} RAM · {disk} storage", "ram", size(last.optLong("ram")), "disk", size(last.optLong("disk")));
        }

        /** A gauge like a phone's battery, as a pill: the name and the percentage, the bar filled to it, and how much. */
        private void drawGauge(Canvas c, float x, float top, float width, String name, long used, long total, String detail) {
            float share = total > 0 ? Ui.clamp((float) used / total) : 0;
            c.drawText(name, x, top + 22 * k, header);
            cellValue.setColor(cellLabel.getColor());
            c.drawText(total > 0 ? Math.round(share * 100) + "%" : "—", x + width, top + 22 * k, cellValue);
            float nub = 6 * k, bh = 24 * k, by = top + 40 * k;
            card.set(x, by, x + width - nub - 4 * k, by + bh);
            fill.setColor(tone(0x1F000000, 0x33FFFFFF));
            c.drawRoundRect(card, bh / 2, bh / 2, fill);
            // The battery's tip, at the right end.
            tmp.set(card.right + 3 * k, by + bh * 0.3f, card.right + 3 * k + nub, by + bh * 0.7f);
            c.drawRoundRect(tmp, nub / 2, nub / 2, fill);
            if (share > 0) {
                float inset = 3 * k;
                tmp.set(card.left + inset, card.top + inset, card.left + inset + Math.max(bh - 2 * inset, (card.width() - 2 * inset) * share),
                        card.bottom - inset);
                fill.setColor(share >= 0.9f ? 0xFFFF3B30 : share >= 0.75f ? 0xFFFF9F0A : 0xFF34C759);
                c.drawRoundRect(tmp, tmp.height() / 2, tmp.height() / 2, fill);
            }
            if (!detail.isEmpty()) c.drawText(detail, x, by + bh + 36 * k, notice);
        }

        /** Home screen picks: status, and the credit TMDB asks for. */
        private float drawRecs(Canvas c, float y) {
            float x = (LEFT_X + 40) * k, room = (LEFT_R - LEFT_X - 80) * k;
            c.drawText(Ui.tr("RECOMMENDATIONS"), x, y, header);
            String line;
            String error = recs.optString("error");
            int count = recs.optInt("count");
            if (!hasKey) line = Ui.tr("No TMDB key: see the README (config.properties).");
            else if (error.equals(Recs.NO_KNOWN_APPS)) line = Ui.tr("None of the TV's streaming apps can give picks.");
            else if (error.equals(Recs.NO_APPS)) line = Ui.trf("TMDB has none of the TV's apps for tmdb_region={region}.", "region", Recs.REGION);
            else if (!error.isEmpty()) line = Ui.trf("Error: {error}.", "error", error);
            else if (count > 0) line = Ui.trf("On · {n} titles from the TV's apps, new picks at 5:00, 12:00 and 18:00.", "n", count);
            else line = Ui.tr("Fetching titles…");
            // The status on up to two lines (the refresh times don't fit on one).
            int width = Math.round(room);
            if (statusLayout == null || !line.equals(statusShown) || statusLayout.getWidth() != width) {
                statusShown = line;
                statusLayout = StaticLayout.Builder.obtain(line, 0, line.length(), notice, width).setMaxLines(2)
                        .setEllipsize(TextUtils.TruncateAt.END).build();
            }
            c.save();
            c.translate(x, y + 42 * k - statusLayout.getLineBaseline(0));
            statusLayout.draw(c);
            c.restore();
            y += statusLayout.getLineBaseline(statusLayout.getLineCount() - 1) - statusLayout.getLineBaseline(0);
            // The credit, whole: on two lines if it needs them.
            String text = Ui.tr("This product uses the TMDB API but is not endorsed or certified by TMDB.");
            if (!Config.WATCHMODE_KEY.trim().isEmpty()) text += " " + Ui.tr("Links to the titles by Watchmode.");
            if (creditLayout == null || !text.equals(creditShown) || creditLayout.getWidth() != width) {
                creditShown = text;
                creditLayout = StaticLayout.Builder.obtain(text, 0, text.length(), credit, width).setMaxLines(3)
                        .setEllipsize(TextUtils.TruncateAt.END).build();
            }
            c.save();
            c.translate(x, y + 80 * k - creditLayout.getLineBaseline(0));
            creditLayout.draw(c);
            c.restore();
            return y + 80 * k + creditLayout.getLineBaseline(creditLayout.getLineCount() - 1) - creditLayout.getLineBaseline(0);
        }

        /** List row: glass; focused, it turns white and grows a little, with a shadow (the tvOS focus). Returns how
         *  focused it is; rowRect is left where the row is drawn. */
        private float drawRowBackground(Canvas c, int i) {
            float f = Ui.clamp(pop[i].value);
            float s = 1 + 0.03f * f;
            float cx = rowRect.centerX(), cy = rowRect.centerY();
            tmp.set(cx - rowRect.width() * s / 2, cy - rowRect.height() * s / 2, cx + rowRect.width() * s / 2, cy + rowRect.height() * s / 2);
            float r = 20 * k * s;
            rowRect.set(tmp);
            if (f < 0.99f) glass(c, rowRect, r, 1 - f);
            if (f > 0.01f) {
                tmp.set(rowRect);
                tmp.offset(0, 10 * k * f);
                shadow.setColor(Ui.alpha(0xFF000000, (night ? 0.4f : 0.22f) * f));
                shadows.draw(c, tmp, r, 22 * k, shadow);
                white.setAlpha(Math.round(255 * f));
                c.drawRoundRect(rowRect, r, r, white);
            }
            return f;
        }

        private float drawComparison(Canvas c, float y) {
            float x = (LEFT_X + 40) * k, colA = (LEFT_R - 250) * k, colB = (LEFT_R - 40) * k;
            c.drawText(Ui.tr("COMPARISON"), x, y, header);
            cellValue.setColor(header.getColor());
            c.drawText(Ui.tr("Original"), colA, y, cellValue);
            c.drawText("Free My TV", colB, y, cellValue);
            cellValue.setColor(cellLabel.getColor());
            JSONObject metrics = state.optJSONObject("metrics");
            JSONObject a = metrics == null ? null : metrics.optJSONObject(HomeMode.ORIGINAL);
            JSONObject b = metrics == null ? null : metrics.optJSONObject(HomeMode.FREE);
            String[][] rows = {{Ui.tr("Home button"), "homeMs"}, {Ui.tr("Free memory"), "memAvailableMB"},
                    {Ui.tr("Home screen memory"), "homePssMB"}};
            for (int i = 0; i < rows.length; i++) {
                float base = y + (46 + 40 * i) * k;
                c.drawText(rows[i][0], x, base, cellLabel);
                c.drawText(metric(a, rows[i][1]), colA, base, cellValue);
                c.drawText(metric(b, rows[i][1]), colB, base, cellValue);
            }
            return y + (46 + 40 * (rows.length - 1)) * k;
        }

        private void drawNotices(Canvas c, float y) {
            float x = (LEFT_X + 40) * k, room = (LEFT_R - LEFT_X - 80) * k;
            c.drawText(Ui.tr("NOTICES"), x, y, header);
            JSONArray notices = state.optJSONArray("notices");
            int shown = 0;
            for (int i = 0; notices != null && i < notices.length() && shown < 2; i++) {
                JSONObject o = notices.optJSONObject(i);
                if (o == null) continue;
                String line = ago(o.optLong("at")) + " · " + o.optString("text");
                c.drawText(TextUtils.ellipsize(line, notice, room, TextUtils.TruncateAt.END).toString(), x, y + (42 + 38 * shown) * k, notice);
                shown++;
            }
            if (shown == 0) c.drawText(Ui.tr("None yet."), x, y + 42 * k, notice);
        }

        /** Home screen choice: Free My TV or the original, with a check on the one in use. */
        private void drawChoice(Canvas c) {
            drawHeading(c, Ui.tr("Home Screen"), Ui.tr("Switching takes a few seconds; the screen blinks while it measures."));
            String[][] options = {{"Free My TV", Ui.tr("No ads, with the new screensaver")},
                    {Ui.tr("Original"), Ui.tr("The TV's factory home screen")}};
            int current = freeActive() ? 0 : 1;
            for (int i = 0; i < 2; i++) {
                float y = (LIST_TOP + i * (CHOICE_H + ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + CHOICE_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(ink(f));
                rowSub.setColor(dim(f));
                c.drawText(options[i][0], rowRect.left + 34 * k, rowRect.top + 50 * k, rowLabel);
                c.drawText(options[i][1], rowRect.left + 34 * k, rowRect.top + 88 * k, rowSub);
                if (i == current) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, ink(f));
                }
            }
        }

        private static int blend(int a, int b, float f) {
            int aa = a >>> 24, ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
            int ba = b >>> 24, br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
            return (Math.round(aa + (ba - aa) * f) << 24) | (Math.round(ar + (br - ar) * f) << 16)
                    | (Math.round(ag + (bg - ag) * f) << 8) | Math.round(ab + (bb - ab) * f);
        }

        private static String metric(JSONObject m, String key) {
            if (m == null || !m.has(key)) return "—";
            long v = m.optLong(key);
            if (key.equals("homeMs")) return Ui.trf("{n} ms", "n", v);
            if (key.equals("memAvailableMB") && v >= 1024) return Ui.trf("{n} GB", "n", String.format(Ui.locale(), "%.2f", v / 1024.0));
            return Ui.trf("{n} MB", "n", v);
        }

        private static String ago(long at) {
            long minutes = Math.max(0, (System.currentTimeMillis() - at) / 60_000);
            if (minutes < 1) return Ui.tr("now");
            if (minutes < 60) return Ui.trf("{n} min ago", "n", minutes);
            if (minutes < 48 * 60) return Ui.trf("{n} h ago", "n", minutes / 60);
            return Ui.plural("plural|one:{n} day ago|other:{n} days ago", minutes / 1440);
        }

        private static String versionName(Context c) {
            try {
                return "Free My TV " + c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName;
            } catch (android.content.pm.PackageManager.NameNotFoundException e) {
                return "Free My TV";
            }
        }

        boolean onKey(KeyEvent e) {
            boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
            switch (e.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (down && focus > 0) focus--;
                    else if (down && scrolling() && pickScroll > 0) pickScroll--;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (down && focus < rowCount() - 1) focus++;
                    else if (down && scrolling() && pickScroll + rowCount() < listSize()) pickScroll++;
                    invalidate();
                    spoken.focusMoved();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    if (down && screen == SHELF_EDIT) {
                        moveShelfApp(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == CONTENT_EDIT || screen == CONTENT_ADD)) {
                        moveContentRow(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    } else if (down && (screen == HOME_SETTINGS || screen == REMOTE_KEYS)) {
                        adjust(e.getKeyCode() == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                        invalidate();
                    }
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    if (down) {
                        if (e.getRepeatCount() == 0) okDown = true;
                    } else {
                        if (okDown && !e.isCanceled() && spoken.keyClick()) activate();
                        okDown = false;
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    if (screen == MAIN) return false;   // leaves the settings
                    if (down) {
                        if (e.getRepeatCount() == 0) backDown = true;
                    } else {
                        if (backDown && !e.isCanceled()) {
                            if (screen == PICKER && (pickFor == PICK_CITY || pickFor == PICK_LANGUAGE)) {
                                focus = pickFor == PICK_CITY ? CITY_ROW : 0;
                                screen = HOME_SETTINGS;
                            } else if (screen == PICKER) {
                                focus = pickFor - 1;
                                screen = REMOTE_KEYS;
                            } else if (screen == CONTENT_ADD) {
                                String group = contentGroup;
                                openContent(contentTab);
                                for (int i = 0; i < contentItems.size(); i++) if (contentItems.get(i)[1].equals(group)) showContentItem(i);
                            } else if (screen == CONTENT_EDIT) {
                                focus = java.util.Arrays.asList(CONTENT_TABS_IDS).indexOf(contentTab);
                                screen = CONTENT_TABS;
                            } else if (screen == CONTENT_TABS && contentShortcut) {
                                contentShortcut = false;    // opened from the home screen: Back goes back there
                                activity.finish();
                                return true;
                            } else {
                                focus = screen == HOME_SETTINGS ? 2 : screen == SHELF_EDIT ? (editingTop ? 5 : 3)
                                        : screen == CONTENT_TABS ? CONTENT_MAIN : screen == REMOTE_KEYS ? 6 : 0;
                                screen = MAIN;
                            }
                        }
                        backDown = false;
                    }
                    invalidate();
                    spoken.focusMoved();
                    return true;
                default:
                    return false;
            }
        }

        // MARK: - Home Settings and Remote Buttons

        private static final String[] UNITS = {"auto", "C", "F"}, THEMES = {"auto", "light", "dark"};
        /** Home Settings: language, temperature, theme, sounds, weather, city, news, the screensaver's background and
         *  (with Photos) how often they change. */
        private static final int CITY_ROW = 5, BACKGROUND_ROW = 7, PHOTO_TIME_ROW = 8;

        private String[] settingLabels() {
            List<String> l = new ArrayList<>();
            l.add(Ui.tr("Language"));
            l.add(Ui.tr("Temperature"));
            l.add(Ui.tr("Theme"));
            l.add(Ui.tr("Navigation sounds"));
            l.add(Ui.tr("Weather widget"));
            l.add(Ui.tr("City"));
            l.add(Ui.tr("News widget"));
            l.add(Ui.tr("Screensaver background"));
            if (Prefs.dreamPhotos()) l.add(Ui.tr("Photo change"));
            return l.toArray(new String[0]);
        }

        private String[] settingValues() {
            String[] labels = settingLabels();
            String[] v = new String[labels.length];
            String lang = Prefs.language();
            v[0] = lang.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(lang);
            String unit = Prefs.temperature();
            v[1] = unit.equals("C") ? "°C" : unit.equals("F") ? "°F" : Ui.tr("unit|Automatic");
            String theme = Prefs.theme();
            v[2] = theme.equals("light") ? Ui.tr("Light") : theme.equals("dark") ? Ui.tr("Dark") : Ui.tr("Automatic");
            v[3] = onOff(Prefs.sounds());
            v[4] = onOff(Prefs.weather());
            v[CITY_ROW] = !cityStatus.isEmpty() ? cityStatus : Prefs.city().isEmpty() ? "—" : Prefs.city();
            v[6] = onOff(Prefs.news());
            v[BACKGROUND_ROW] = Prefs.dreamPhotos() ? Ui.tr("Photos") : Ui.tr("Aurora");
            if (Prefs.dreamPhotos()) v[PHOTO_TIME_ROW] = photoTime(Prefs.photoSeconds());
            return v;
        }

        private static String photoTime(int seconds) {
            return seconds < 60 ? Ui.trf("{n} sec", "n", seconds) : Ui.trf("{n} min", "n", seconds / 60);
        }

        /** "auto", then English and every language with a catalogue. */
        private static String[] languageChoices() {
            String[] all = Ui.languages();
            String[] v = new String[all.length + 1];
            v[0] = "auto";
            System.arraycopy(all, 0, v, 1, all.length);
            return v;
        }

        private static String onOff(boolean on) {
            return on ? Ui.tr("On") : Ui.tr("Off");
        }

        private String[] keyLabels() {
            if (RemoteKeys.STEM_NETFLIX) return new String[] {Ui.tr("Netflix button")};
            if (RemoteKeys.KTC) return new String[] {Ui.tr("Tabii button")};
            if (RemoteKeys.SONY) return new String[] {Ui.tr("BRAVIA CORE button"),
                    Ui.tr("Netflix button"), Ui.tr("Disney+ button"),
                    Ui.tr("Prime Video button"), Ui.tr("YouTube button"),
                    Ui.tr("Crunchyroll button"), Ui.tr("Google Play button")};
            return new String[] {Ui.tr("Netflix button"), Ui.tr("YouTube button"),
                    Ui.tr("Prime Video button"), Ui.tr("TCL Channel button")};
        }

        private String[] keyValues() {
            String[] v = new String[Prefs.KEYS.length];
            for (int i = 0; i < v.length; i++) {
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[i]));
                v[i] = at >= 0 ? appLabels.get(at) : Ui.tr("Not installed");
            }
            return v;
        }

        /** Installed TV apps, by name, after "Nothing": what a remote button can open. */
        private void loadApps() {
            List<String[]> found = new ArrayList<>();
            for (String[] app : HomeApps.launchable(getContext())) {
                if (!app[0].equals(getContext().getPackageName())) found.add(app);
            }
            Collections.sort(found, (a, b) -> a[1].toLowerCase(Locale.ROOT).compareTo(b[1].toLowerCase(Locale.ROOT)));
            appPkgs.clear();
            appLabels.clear();
            appPkgs.add("");
            // On Sony, "nothing" leaves the key to the TV, which opens its usual app.
            appLabels.add(RemoteKeys.SONY ? Ui.tr("Sony default") : Ui.tr("Nothing"));
            for (String[] f : found) {
                appPkgs.add(f[0]);
                appLabels.add(f[1]);
            }
        }

        /** Changes the focused row's value one step (OK and → go forward, ← goes back). */
        private void adjust(int dir) {
            if (screen == REMOTE_KEYS) {
                int n = appPkgs.size();
                int at = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[focus]));
                int next = at < 0 ? (dir > 0 ? 0 : n - 1) : (at + dir + n) % n;
                Prefs.setKeyApp(Prefs.KEYS[focus], appPkgs.get(next));
                return;
            }
            switch (focus) {
                case 0: break;   // OK opens the language picker.
                case 1: {
                    int at = java.util.Arrays.asList(UNITS).indexOf(Prefs.temperature());
                    Prefs.setTemperature(UNITS[(at + dir + UNITS.length) % UNITS.length]);
                    break;
                }
                case 2: {
                    int at = java.util.Arrays.asList(THEMES).indexOf(Prefs.theme());
                    Prefs.setTheme(THEMES[(at + dir + THEMES.length) % THEMES.length]);
                    break;
                }
                case 3: Prefs.setSounds(!Prefs.sounds()); break;
                case 4: Prefs.setWeather(!Prefs.weather()); break;
                case CITY_ROW: return;
                case 6: Prefs.setNews(!Prefs.news()); break;
                case BACKGROUND_ROW: Prefs.setDreamPhotos(!Prefs.dreamPhotos()); return;
                case PHOTO_TIME_ROW: {
                    int n = Prefs.PHOTO_TIMES.length, at = 0;
                    for (int i = 0; i < n; i++) if (Prefs.PHOTO_TIMES[i] == Prefs.photoSeconds()) at = i;
                    Prefs.setPhotoSeconds(Prefs.PHOTO_TIMES[(at + dir + n) % n]);
                    return;
                }
                default: return;
            }
            HomeData.get(getContext()).settingsChanged();
            checkNight();   // the Theme: this screen follows it at once
        }

        private void askCity() {
            if (Ui.tr("Searching…").equals(cityStatus)) return;
            EditText field = new EditText(activity);
            field.setSingleLine(true);
            field.setHint(Ui.tr("Type the city's name"));
            field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            AlertDialog dialog = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle(Ui.tr("City"))
                    .setView(field)
                    .setPositiveButton(Ui.tr("Search"), (d, which) -> searchCity(field.getText().toString()))
                    .setNegativeButton(Ui.tr("Cancel"), null)
                    .create();
            field.setOnEditorActionListener((v, action, event) -> {
                dialog.dismiss();
                searchCity(field.getText().toString());
                return true;
            });
            dialog.show();
        }

        private void searchCity(String name) {
            if (name.trim().isEmpty()) return;
            cityStatus = Ui.tr("Searching…");
            invalidate();
            new Thread(() -> {
                List<String[]> found;
                String error;
                try {
                    found = HomeData.findCity(name);
                    error = found.isEmpty() ? Ui.tr("No city with that name.") : "";
                } catch (Exception e) {
                    found = new ArrayList<>();
                    error = Ui.trf("Couldn't search now: {error}", "error", e.getMessage());
                    AppLog.w("city search: " + e.getMessage());
                }
                List<String[]> result = found;
                String message = error;
                handler.post(() -> {
                    cityStatus = message;
                    if (!result.isEmpty() && screen == HOME_SETTINGS) {
                        cities.clear();
                        cities.addAll(result);
                        openPicker(PICK_CITY);
                        spoken.focusMoved();
                    }
                    invalidate();
                    if (!message.isEmpty()) handler.postDelayed(() -> { cityStatus = ""; invalidate(); }, 4000);
                });
            }).start();
        }

        private boolean scrolling() {
            return screen == PICKER || screen == SHELF_EDIT || screen == CONTENT_EDIT || screen == CONTENT_ADD;
        }

        private int listSize() {
            return screen == SHELF_EDIT ? shelfRows.size() : screen == CONTENT_EDIT || screen == CONTENT_ADD ? contentItems.size()
                    : pickLabels.size();
        }

        // MARK: - Content (which rows Explore, Movies and Shows show)

        private static final String[] CONTENT_TABS_IDS = {"all", "movie", "tv"};
        private boolean contentShortcut;    // Content was opened by the home screen's button

        void openContentShortcut() {
            screen = CONTENT_TABS;
            focus = 0;
            contentShortcut = true;
            invalidate();
            spoken.focusMoved();
        }
        private String contentTab = "all", contentGroup = "";
        // The list: {"row", id} or {"group", group}. The tab's rows, then the groups to add more from; in a group, its
        // rows the tab shows (in order), then the others.
        private final List<String[]> contentItems = new ArrayList<>();
        private final List<String> contentOn = new ArrayList<>();     // the tab's rows, in order

        private static String tabName(String tab) {
            return tab.equals("movie") ? Ui.tr("Movies") : tab.equals("tv") ? Ui.tr("Shows") : Ui.tr("Explore");
        }

        /** The tab's list: its rows (in order), then the groups. */
        private void openContent(String tab) {
            contentTab = tab;
            buildContent();
            pickScroll = 0;
            focus = 0;
            screen = CONTENT_EDIT;
        }

        private void buildContent() {
            contentOn.clear();
            contentOn.addAll(Recs.rowsOn(contentTab));
            contentItems.clear();
            for (String id : contentOn) contentItems.add(new String[] {"row", id});
            for (String g : Recs.GROUPS) contentItems.add(new String[] {"group", g});
        }

        private void openGroup(String group) {
            contentGroup = group;
            buildGroup();
            pickScroll = 0;
            focus = 0;
            screen = CONTENT_ADD;
        }

        private void buildGroup() {
            contentOn.clear();
            contentOn.addAll(Recs.rowsOn(contentTab));
            List<String> choices = Recs.rowChoices(contentTab, contentGroup);
            contentItems.clear();
            for (String id : contentOn) if (choices.contains(id)) contentItems.add(new String[] {"row", id});
            for (String id : choices) if (!contentOn.contains(id)) contentItems.add(new String[] {"row", id});
        }

        /** Scrolls item i into view and focuses it. */
        private void showContentItem(int i) {
            int visible = rowCount();
            if (i < pickScroll) pickScroll = i;
            else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
            pickScroll = Math.max(0, Math.min(pickScroll, contentItems.size() - visible));
            focus = i - pickScroll;
        }

        /** The same list after a change, the focus still on that row. */
        private void refreshContent(String id) {
            if (screen == CONTENT_EDIT) buildContent(); else buildGroup();
            for (int i = 0; i < contentItems.size(); i++) if (contentItems.get(i)[1].equals(id)) showContentItem(i);
        }

        /** OK: a group opens (true: another screen); a row is ticked on or off (off, it goes back to its group). */
        private boolean activateContent() {
            String[] item = contentItems.get(pickScroll + focus);
            if (item[0].equals("group")) {
                openGroup(item[1]);
                return true;
            }
            boolean on = contentOn.contains(item[1]);
            Recs.get(getContext()).setRowOn(contentTab, item[1], !on);
            int at = pickScroll + focus;
            refreshContent(item[1]);
            // Off in the tab's list: the row leaves it, the focus stays where it was.
            if (on && screen == CONTENT_EDIT) showContentItem(Math.min(at, contentItems.size() - 1));
            return false;
        }

        /** ← → : the focused row swaps places with the shown row above or below it in the list. */
        private void moveContentRow(int dir) {
            int at = pickScroll + focus, to = at + dir;
            String[] item = contentItems.get(at);
            if (!item[0].equals("row") || !contentOn.contains(item[1])) return;
            if (to < 0 || to >= contentItems.size() || !contentItems.get(to)[0].equals("row")
                    || !contentOn.contains(contentItems.get(to)[1])) return;
            Recs.get(getContext()).swapRows(contentTab, item[1], contentItems.get(to)[1]);
            refreshContent(item[1]);
        }

        private String contentLabel(String[] item) {
            return item[0].equals("group") ? Ui.trf("Add rows · {group}", "group", Recs.groupName(item[1]))
                    : Recs.get(getContext()).rowName(contentTab, item[1]);
        }

        /** "#3" or "Off" for a row; how many of its rows are on for a group. */
        private String contentValue(String[] item) {
            if (item[0].equals("group")) {
                int n = 0;
                for (String id : Recs.rowChoices(contentTab, item[1])) if (contentOn.contains(id)) n++;
                return Ui.trf("{n} on", "n", n);
            }
            int place = contentOn.indexOf(item[1]);
            return place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off");
        }

        private void drawContent(Canvas c) {
            String heading = screen == CONTENT_TABS ? Ui.tr("Content")
                    : screen == CONTENT_EDIT ? Ui.trf("Content · {tab}", "tab", tabName(contentTab))
                    : tabName(contentTab) + " · " + Recs.groupName(contentGroup);
            String text = screen == CONTENT_TABS ? Ui.tr("Choose the rows each tab shows.")
                    : Ui.tr("OK turns a row on or off. ← → change the order.");
            if (screen != CONTENT_TABS) text = (pickScroll + focus + 1) + " / " + contentItems.size() + "  ·  " + text;
            drawHeading(c, heading, text);
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String label, value;
                boolean lit = true, more = false;
                if (screen == CONTENT_TABS) {
                    label = tabName(CONTENT_TABS_IDS[i]);
                    value = Ui.plural("plural|one:{n} row|other:{n} rows", Recs.rowsOn(CONTENT_TABS_IDS[i]).size());
                    more = true;
                } else {
                    String[] item = contentItems.get(pickScroll + i);
                    label = contentLabel(item);
                    value = contentValue(item);
                    more = item[0].equals("group");
                    lit = more || contentOn.contains(item[1]);
                }
                float y = (LIST_TOP + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(lit ? ink(f) : faint(f));
                rowValue.setColor(dim(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                fitPaint.set(rowLabel);
                c.drawText(TextUtils.ellipsize(label, fitPaint, 600 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 34 * k, base, rowLabel);
                float right = rowRect.right - 30 * k;
                if (more) {
                    float cw = Symbols.width(getContext(), "chevron_right", 22 * k);
                    Symbols.draw(getContext(), c, "chevron_right", right - cw / 2, rowRect.centerY(), 22 * k, faint(f));
                    right -= cw + 16 * k;
                }
                c.drawText(value, right, base, rowValue);
            }
        }

        // MARK: - Shelf editor

        private void openShelfEditor(boolean top) {
            editingTop = top;
            shelfOrder.clear();
            if (top) {
                shelfOrder.addAll(HomeApps.topButtonKeys());
                rebuildShelfRows(HomeApps.topCandidates());
            } else {
                // Only the apps the shelf shows: one that was uninstalled would hold a place (and count toward the
                // limit) without a row to take it off.
                PackageManager pm = getContext().getPackageManager();
                for (String pkg : HomeApps.shelfPackages(getContext())) if (HomeApps.launchIntent(pm, pkg) != null) shelfOrder.add(pkg);
                rebuildShelfRows(HomeApps.shelfCandidates(getContext()));
            }
            pickScroll = 0;
            focus = 0;
            screen = SHELF_EDIT;
        }

        /** Selected apps first, in shelf order, then the rest in the order they came. */
        private void rebuildShelfRows(List<String[]> candidates) {
            shelfRows.clear();
            for (String pkg : shelfOrder) {
                for (String[] cand : candidates) if (cand[0].equals(pkg)) { shelfRows.add(cand); break; }
            }
            for (String[] cand : candidates) if (!shelfOrder.contains(cand[0])) shelfRows.add(cand);
        }

        /** Same rows after a change: keeps the focus on the app that was focused. */
        private void refreshShelfRows(String focusedPkg) {
            List<String[]> all = new ArrayList<>(shelfRows);
            rebuildShelfRows(all);
            for (int i = 0; i < shelfRows.size(); i++) {
                if (!shelfRows.get(i)[0].equals(focusedPkg)) continue;
                int visible = rowCount();
                if (i < pickScroll) pickScroll = i;
                else if (i >= pickScroll + visible) pickScroll = i - visible + 1;
                focus = i - pickScroll;
                break;
            }
            if (editingTop) Prefs.setTopButtons(shelfOrder); else Prefs.setShelf(shelfOrder);
        }

        /** OK: puts the app on the shelf (at the end) or takes it off. */
        private void toggleShelfApp() {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            if (shelfOrder.contains(pkg)) shelfOrder.remove(pkg);
            else if (editingTop || shelfOrder.size() < HomeApps.MAX_SHELF) shelfOrder.add(pkg);   // four buttons: no limit to reach
            else return;
            refreshShelfRows(pkg);
        }

        /** ← → : moves a shelf app one place earlier or later. */
        private void moveShelfApp(int dir) {
            String pkg = shelfRows.get(pickScroll + focus)[0];
            int at = shelfOrder.indexOf(pkg), to = at + dir;
            if (at < 0 || to < 0 || to >= shelfOrder.size()) return;
            shelfOrder.remove(at);
            shelfOrder.add(to, pkg);
            refreshShelfRows(pkg);
        }

        private void drawShelfEditor(Canvas c) {
            drawHeading(c, editingTop ? Ui.tr("Top Buttons") : Ui.tr("Shelf"),
                    editingTop ? Ui.tr("OK adds or removes a button. ← → change the order.")
                            : Ui.trf("OK adds or removes (up to {n}). ← → change the order.", "n", HomeApps.MAX_SHELF));
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                String[] app = shelfRows.get(pickScroll + i);
                int place = shelfOrder.indexOf(app[0]);
                float y = (LIST_TOP + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(place >= 0 ? ink(f) : faint(f));
                rowValue.setColor(dim(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                fitPaint.set(rowLabel);
                c.drawText(TextUtils.ellipsize(app[1], fitPaint, 600 * k, TextUtils.TruncateAt.END).toString(), rowRect.left + 34 * k, base, rowLabel);
                c.drawText(place >= 0 ? "#" + (place + 1) : Ui.tr("shelf|Off"), rowRect.right - 30 * k, base, rowValue);
            }
        }

        private void openPicker(int which) {
            pickFor = which;
            pickLabels.clear();
            if (which == PICK_CITY) {
                for (String[] city : cities) pickLabels.add(city[0]);
                pickCurrent = -1;
            } else if (which == PICK_LANGUAGE) {
                String[] choices = languageChoices();
                for (String code : choices) {
                    pickLabels.add(code.equals("auto") ? Ui.tr("Automatic") : Ui.languageName(code));
                }
                pickCurrent = java.util.Arrays.asList(choices).indexOf(Prefs.language());
            } else {
                pickLabels.addAll(appLabels);
                pickCurrent = appPkgs.indexOf(Prefs.keyApp(Prefs.KEYS[which - 1]));
            }
            int visible = Math.min(PICK_VISIBLE, pickLabels.size());
            int at = Math.max(0, pickCurrent);
            pickScroll = Math.max(0, Math.min(at - 3, pickLabels.size() - visible));
            focus = at - pickScroll;
            screen = PICKER;
        }

        /** A long list (the apps): nine rows at a time, the one in use checked. */
        private void drawPicker(Canvas c) {
            String heading = pickFor == PICK_CITY ? Ui.tr("City")
                    : pickFor == PICK_LANGUAGE ? Ui.tr("Language") : keyLabels()[pickFor - 1];
            drawHeading(c, heading, (pickScroll + focus + 1) + " / " + pickLabels.size() + "  ·  " + Ui.tr("OK picks, Back cancels."));
            int rows = rowCount();
            for (int i = 0; i < rows; i++) {
                int at = pickScroll + i;
                float y = (LIST_TOP + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(ink(f));
                fitPaint.set(rowLabel);
                String label = TextUtils.ellipsize(pickLabels.get(at), fitPaint, 700 * k, TextUtils.TruncateAt.END).toString();
                c.drawText(label, rowRect.left + 34 * k, rowRect.centerY() + Ui.halfCap(rowLabel), rowLabel);
                if (at == pickCurrent) {
                    float cw = Symbols.width(getContext(), "check", 26 * k);
                    Symbols.draw(getContext(), c, "check", rowRect.right - 30 * k - cw / 2, rowRect.centerY(), 26 * k, ink(f));
                }
            }
        }

        private void drawList(Canvas c) {
            boolean keys = screen == REMOTE_KEYS;
            String[] labels = keys ? keyLabels() : settingLabels();
            String[] values = keys ? keyValues() : settingValues();
            String text = Ui.tr("OK or the arrows change the value.");
            if (keys) {
                text += "\n" + (RemoteKeys.VESTEL ? Ui.tr("YouTube, Prime Video and Google Play: the TV itself controls those buttons.")
                        : RemoteKeys.KTC ? Ui.tr("Netflix, YouTube and Prime Video: the TV itself controls those buttons.")
                        : RemoteKeys.SONY ? Ui.tr("Sony default: the button opens what it always did.")
                        : RemoteKeys.FORMOVIE ? Ui.tr("The other buttons: the TV itself controls them.")
                        : RemoteKeys.supported() ? Ui.tr("Disney and Globoplay: the TV itself controls those buttons.")
                        : Ui.tr("TCL remote buttons: on this TV they work as always."));
            }
            drawHeading(c, keys ? Ui.tr("Remote Buttons") : Ui.tr("Home Settings"), text);
            for (int i = 0; i < labels.length; i++) {
                float y = (LIST_TOP + i * (LIST_ROW_H + LIST_ROW_GAP)) * k;
                rowRect.set(LIST_X * k, y, LIST_R * k, y + LIST_ROW_H * k);
                float f = drawRowBackground(c, i);
                rowLabel.setColor(ink(f));
                rowValue.setColor(dim(f));
                float base = rowRect.centerY() + Ui.halfCap(rowLabel);
                c.drawText(labels[i], rowRect.left + 34 * k, base, rowLabel);
                fitPaint.set(rowValue);
                c.drawText(TextUtils.ellipsize(values[i], fitPaint, 440 * k, TextUtils.TruncateAt.END).toString(),
                        rowRect.right - 30 * k, base, rowValue);
            }
        }

        private void activate() {
            Context c = getContext();
            if (screen == HOME_CHOICE) {
                boolean wantFree = focus == 0;
                boolean already = wantFree ? freeActive() : HomeMode.ORIGINAL.equals(state.optString("mode", HomeMode.ORIGINAL));
                if (!already && !busy()) {
                    HomeMode.request(c, wantFree ? HomeMode.FREE : HomeMode.ORIGINAL);
                    pendingAt = SystemClock.uptimeMillis();
                }
                screen = MAIN;
                focus = 0;
            } else if (screen == PICKER && pickFor == PICK_CITY) {
                String[] city = cities.get(pickScroll + focus);
                Prefs.setCity(city[1], city[2], city[3]);
                HomeData.get(c).settingsChanged();
                focus = CITY_ROW;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER && pickFor == PICK_LANGUAGE) {
                Prefs.setLanguage(languageChoices()[pickScroll + focus]);
                focus = 0;
                screen = HOME_SETTINGS;
            } else if (screen == PICKER) {
                int at = pickScroll + focus;
                Prefs.setKeyApp(Prefs.KEYS[pickFor - 1], appPkgs.get(at));
                focus = pickFor - 1;
                screen = REMOTE_KEYS;
            } else if (screen == SHELF_EDIT) {
                toggleShelfApp();
                invalidate();
                return;
            } else if (screen == REMOTE_KEYS) {
                openPicker(focus + 1);
            } else if (screen == HOME_SETTINGS && focus == 0) {
                openPicker(PICK_LANGUAGE);
            } else if (screen == HOME_SETTINGS && focus == CITY_ROW) {
                askCity();
                return;
            } else if (screen == CONTENT_TABS) {
                openContent(CONTENT_TABS_IDS[focus]);
            } else if (screen == CONTENT_EDIT || screen == CONTENT_ADD) {
                if (!activateContent()) {
                    invalidate();
                    return;
                }
            } else if (screen == HOME_SETTINGS) {
                adjust(1);
                invalidate();
                return;
            } else if (focus == 0) {
                if (busy()) return;
                screen = HOME_CHOICE;
                focus = freeActive() ? 0 : 1;
            } else if (focus == 1) {
                pause();
                activity.showPreview();
            } else if (focus == 2) {
                screen = HOME_SETTINGS;
                focus = 0;
            } else if (focus == 3) {
                openShelfEditor(false);
            } else if (focus == CONTENT_MAIN) {
                screen = CONTENT_TABS;
                focus = 0;
                contentShortcut = false;
            } else if (focus == 5) {
                openShelfEditor(true);
            } else if (focus == OPTIMIZE_ROW || focus == DEEP_ROW) {
                if (!optimizing().isEmpty() || busy()) return;   // one at a time, and never during a switch
                Optimize.request(c, focus == DEEP_ROW ? Optimize.DEEP : Optimize.QUICK);
                optimize = Optimize.state(c);
                handler.removeCallbacks(poll);
                handler.post(poll);
                invalidate();
                spoken.focusMoved();
                return;
            } else if (focus == 6) {
                HomeMode.ensureRemoteService(c, 0);   // the buttons work from inside other apps through this service
                loadApps();
                screen = REMOTE_KEYS;
                focus = 0;
            } else {
                activity.startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            for (Spring s : pop) s.snap(0);
            invalidate();
            spoken.focusMoved();
        }
    }
}
