package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Environment;
import android.os.StatFs;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Settings › Optimize now and Deep optimize, run in the main process (ModeJob) over the TV's own ADB (Adb), with fixed
 * commands only. Optimize now: closes background apps (Stremio stays, so Continue watching can still resume it),
 * deletes Free My TV's saved pictures, clears every app's cache files, trims the storage and clears the system logs,
 * then says how much memory and storage it freed. Deep optimize: Android's own background app compilation, run now
 * (apps open faster; a few minutes of hard work for the TV). Nothing here clears app data or uninstalls anything.
 * Progress and the last results are in optimize.json, which the settings screen reads.
 */
final class Optimize {
    static final String QUICK = "quick", DEEP = "deep";
    private static final Object RUN = new Object();
    private static final Object FILE_LOCK = new Object();
    /** Apps Optimize now leaves running: Stremio (its paused session is what Continue watching resumes) and us. */
    private static final String[] KEEP = {"com.stremio", HomeMode.PKG};
    /** Free My TV's picture caches (they fill again as the rows load). */
    private static final String[] PICTURES = {"recs", "recs-cards", "logos", "dream-photo"};

    private Optimize() {}

    static void request(Context c, String kind) {
        if (!running(c).isEmpty()) return;
        status(c, kind, Ui.tr("Starting…"));
        ModeJob.schedule(c, DEEP.equals(kind) ? ModeJob.DEEP_OPTIMIZE : ModeJob.OPTIMIZE, null, 0);
    }

    static void quickNow(Context c) {
        synchronized (RUN) {
            long ram = availableRam(c), disk = availableDisk();
            try (Adb adb = connect(c, QUICK)) {
                status(c, QUICK, Ui.tr("Closing background apps…"));
                closeBackgroundApps(adb);
                status(c, QUICK, Ui.tr("Clearing saved pictures and caches…"));
                for (String dir : PICTURES) delete(new File(c.getCacheDir(), dir));
                adb.shell("pm trim-caches 999999999999", 120_000);   // "free this much": every app's cache goes
                status(c, QUICK, Ui.tr("Tidying the storage…"));
                adb.shell("sm fstrim", 120_000);
                adb.shell("logcat -c");
                SystemClock.sleep(1500);    // the closed apps' memory comes back after a moment
                long freedRam = Math.max(0, availableRam(c) - ram), freedDisk = Math.max(0, availableDisk() - disk);
                finish(c, QUICK, new JSONObject().put("at", System.currentTimeMillis()).put("ram", freedRam).put("disk", freedDisk));
                HomeMode.notice(c, Ui.trf("Optimized: {ram} of memory and {disk} of storage freed.",
                        "ram", size(freedRam), "disk", size(freedDisk)));
            } catch (IOException | JSONException | RuntimeException e) {
                fail(c, QUICK, e);
            }
        }
    }

    static void deepNow(Context c) {
        synchronized (RUN) {
            try (Adb adb = connect(c, DEEP)) {
                status(c, DEEP, Ui.tr("Optimizing apps… (a few minutes)"));
                adb.shell("cmd package bg-dexopt-job", 30 * 60_000);
                finish(c, DEEP, new JSONObject().put("at", System.currentTimeMillis()));
                HomeMode.notice(c, Ui.tr("Deep optimize is done: apps are ready to open faster."));
            } catch (IOException | JSONException | RuntimeException e) {
                fail(c, DEEP, e);
            }
        }
    }

    private static Adb connect(Context c, String kind) throws IOException {
        status(c, kind, Ui.tr("Connecting…"));
        return Adb.connect(c, 120_000, () -> status(c, kind, Ui.tr("Accept “Allow USB debugging?” on the TV (tick “Always allow”).")));
    }

    /** `am kill` for each app with a process running, except KEEP: Android only stops what is safe to stop (in the
     *  background, not playing or showing anything). */
    private static void closeBackgroundApps(Adb adb) throws IOException {
        Set<String> apps = new LinkedHashSet<>();
        for (String line : adb.shell("ps -A -o NAME").split("\n")) {
            String name = line.trim();
            int colon = name.indexOf(':');
            if (colon > 0) name = name.substring(0, colon);
            if (!name.matches("[a-zA-Z][\\w]*(\\.[\\w]+)+")) continue;
            boolean keep = false;
            for (String k : KEEP) keep |= name.equals(k) || name.startsWith(k + ".");
            if (!keep) apps.add(name);
        }
        List<String> commands = new ArrayList<>();
        for (String app : apps) commands.add("am kill " + app);
        if (!commands.isEmpty()) adb.shell(String.join("; ", commands), 120_000);
    }

    private static void delete(File f) {
        File[] children = f.listFiles();
        if (children != null) for (File child : children) delete(child);
        if (f.exists() && !f.delete()) AppLog.w("optimize: could not delete " + f.getName());
    }

    static long availableRam(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) return 0;
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        return mi.availMem;
    }

    static long availableDisk() {
        try {
            return new StatFs(Environment.getDataDirectory().getPath()).getAvailableBytes();
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    static String size(long bytes) {
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb < 1) return Ui.trf("{n} MB", "n", bytes / (1024 * 1024));
        return Ui.trf("{n} GB", "n", String.format(Ui.locale(), gb < 10 ? "%.1f" : "%.0f", gb));
    }

    // MARK: - State (optimize.json: {"kind", "busy", "busyAt"} while running, the last results under "quick" and "deep")

    /** What's running now (QUICK or DEEP), or "": a step older than 40 minutes is stale (the job was stopped). */
    static String running(Context c) {
        return running(state(c));
    }

    static String running(JSONObject s) {
        long at = s.optLong("busyAt"), now = SystemClock.elapsedRealtime();
        if (s.optString("busy").isEmpty() || now < at || now - at > 40 * 60_000L) return "";
        return s.optString("kind");
    }

    static JSONObject state(Context c) {
        synchronized (FILE_LOCK) {
            File f = new File(c.getFilesDir(), "optimize.json");
            if (!f.exists()) return new JSONObject();
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] data = new byte[(int) f.length()];
                int read = 0;
                while (read < data.length) {
                    int n = in.read(data, read, data.length - read);
                    if (n < 0) break;
                    read += n;
                }
                return new JSONObject(new String(data, 0, read, StandardCharsets.UTF_8));
            } catch (IOException | JSONException | RuntimeException e) {
                return new JSONObject();
            }
        }
    }

    private static void save(Context c, JSONObject s) {
        synchronized (FILE_LOCK) {
            File dir = c.getFilesDir(), tmp = new File(dir, "optimize.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(s.toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                AppLog.w("optimize: could not save the state: " + e.getMessage());
                return;
            }
            if (!tmp.renameTo(new File(dir, "optimize.json"))) AppLog.w("optimize: could not replace the state file");
        }
    }

    private static void status(Context c, String kind, String text) {
        JSONObject s = state(c);
        try {
            s.put("kind", kind).put("busy", text).put("busyAt", SystemClock.elapsedRealtime()).remove("error");
        } catch (JSONException ignored) {}
        save(c, s);
    }

    private static void finish(Context c, String kind, JSONObject result) throws JSONException {
        JSONObject s = state(c);
        s.remove("busy");
        s.put(kind, result);
        save(c, s);
    }

    private static void fail(Context c, String kind, Exception e) {
        String error = Ui.trf("Couldn't optimize: {error}", "error", String.valueOf(e.getMessage()));
        JSONObject s = state(c);
        s.remove("busy");
        try { s.put("error", error).put("errorKind", kind); } catch (JSONException ignored) {}
        save(c, s);
        HomeMode.notice(c, error);
    }
}
