package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Continue watching: the last titles played on the TV, newest first, one per show. WatchListener writes it (main
 * process) as titles play; the home screen (:home) reads it and removes titles. One JSON file in the app's files,
 * locked while it changes, so both processes can write. Artwork sits next to it (watch-art/<key hash>.jpg).
 */
final class WatchHistory {
    private WatchHistory() {}

    static final int MAX = 10;
    /** Watched past this share of its length: finished, it leaves the list. */
    static final float FINISHED = 0.95f;

    static final class Entry {
        String key = "", pkg = "", title = "", subtitle = "";
        String mediaId = "", mediaUri = "";
        long position, duration, updatedAt;

        float progress() {
            return duration <= 0 ? 0 : Math.max(0, Math.min(1, position / (float) duration));
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("pkg", pkg).put("title", title).put("subtitle", subtitle)
                    .put("mediaId", mediaId).put("mediaUri", mediaUri)
                    .put("position", position).put("duration", duration).put("updatedAt", updatedAt);
        }

        static Entry of(JSONObject o) {
            Entry e = new Entry();
            e.key = o.optString("key");
            e.pkg = o.optString("pkg");
            e.title = o.optString("title");
            e.subtitle = o.optString("subtitle");
            e.mediaId = o.optString("mediaId");
            e.mediaUri = o.optString("mediaUri");
            e.position = o.optLong("position");
            e.duration = o.optLong("duration");
            e.updatedAt = o.optLong("updatedAt");
            return e;
        }
    }

    interface Change { void apply(List<Entry> list); }

    private static File file(Context c) {
        return new File(c.getFilesDir(), "watch-history.json");
    }

    static File artDir(Context c) {
        File d = new File(c.getFilesDir(), "watch-art");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    static File art(Context c, String key) {
        return new File(artDir(c), Integer.toHexString(key.hashCode()) + ".jpg");
    }

    /** Changes since `stamp` (the file's time and size): the home screen checks this before reading again. */
    static long stamp(Context c) {
        File f = file(c);
        return f.lastModified() * 31 + f.length();
    }

    static List<Entry> load(Context c) {
        List<Entry> out = new ArrayList<>();
        File f = file(c);
        if (!f.exists()) return out;
        try {
            JSONArray a = new JSONArray(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            for (int i = 0; i < a.length(); i++) out.add(Entry.of(a.getJSONObject(i)));
        } catch (Exception e) {
            AppLog.w("watch history: " + e.getMessage());
        }
        return out;
    }

    /** Reads, changes and writes the list under a lock (the other process may be writing too). */
    static void update(Context c, Change change) {
        File lock = new File(c.getFilesDir(), "watch-history.lock");
        try (RandomAccessFile raf = new RandomAccessFile(lock, "rw"); FileLock ignored = raf.getChannel().lock()) {
            List<Entry> list = load(c);
            change.apply(list);
            while (list.size() > MAX) {
                Entry gone = list.remove(list.size() - 1);
                //noinspection ResultOfMethodCallIgnored
                art(c, gone.key).delete();
            }
            JSONArray a = new JSONArray();
            for (Entry e : list) a.put(e.json());
            File tmp = new File(c.getFilesDir(), "watch-history." + android.os.Process.myPid() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(a.toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (!tmp.renameTo(file(c))) throw new IOException("rename failed");
        } catch (Exception e) {
            AppLog.w("watch history: " + e.getMessage());
        }
    }

    static void remove(Context c, String key) {
        update(c, list -> {
            for (Iterator<Entry> it = list.iterator(); it.hasNext(); ) {
                if (it.next().key.equals(key)) it.remove();
            }
        });
        //noinspection ResultOfMethodCallIgnored
        art(c, key).delete();
    }

    /** One card per show: the series' name when the app gives one, else the title. */
    static String key(String pkg, String show) {
        return pkg + "|" + show.trim().toLowerCase(Locale.ROOT);
    }

    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern NETFLIX_ID = Pattern.compile("\\d{5,10}");

    /**
     * The link that plays this title straight away in its app, when we know one: the app's own media link, a YouTube
     * video by its ID, a Netflix title by its ID. null: only the app can be opened.
     */
    static Intent playIntent(Entry e) {
        String uri = null;
        if (e.mediaUri.startsWith("http://") || e.mediaUri.startsWith("https://")) {
            uri = e.mediaUri;
        } else if (e.pkg.startsWith("com.google.android.youtube") && YOUTUBE_ID.matcher(e.mediaId).matches()) {
            uri = "https://www.youtube.com/watch?v=" + e.mediaId;
        } else if (e.pkg.equals("com.netflix.ninja") && NETFLIX_ID.matcher(e.mediaId).matches()) {
            uri = "https://www.netflix.com/watch/" + e.mediaId;
        }
        if (uri == null) return null;
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage(e.pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
