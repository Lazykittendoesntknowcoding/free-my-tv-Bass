package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URLEncoder;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Continue watching: the last titles played on the TV, newest first, one per show. WatchListener writes it (main
 * process) as titles play; the home screen (:home) reads it and removes titles. One JSON file in the app's files,
 * locked while it changes, so both processes can write. Artwork sits next to it (watch-art/<key hash>.jpg).
 */
final class WatchHistory {
    private WatchHistory() {}

    static final int MAX = 50;
    /** Watched past this share of its length: finished, it leaves the list. */
    static final float FINISHED = 0.95f;

    static final class Entry {
        String key = "", pkg = "", title = "", subtitle = "";
        String mediaId = "", mediaUri = "";
        /** A link found for the title afterwards (Stremio: its page, from TMDB), for the subtitle it was found for. */
        String link = "", linkFor = "";
        long position, duration, updatedAt;

        float progress() {
            return duration <= 0 ? 0 : Math.max(0, Math.min(1, position / (float) duration));
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("key", key).put("pkg", pkg).put("title", title).put("subtitle", subtitle)
                    .put("mediaId", mediaId).put("mediaUri", mediaUri).put("link", link).put("linkFor", linkFor)
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
            e.link = o.optString("link");
            e.linkFor = o.optString("linkFor");
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

    static void setLink(Context c, String key, String linkFor, String link) {
        update(c, list -> {
            for (Entry e : list) {
                if (!e.key.equals(key)) continue;
                e.link = link;
                e.linkFor = linkFor;
            }
        });
    }

    private static final Pattern IMDB = Pattern.compile("(tt\\d{6,10})(?::(\\d+):(\\d+))?");
    private static final Pattern[] EPISODE = {
            Pattern.compile("\\bS(\\d{1,2})\\s*[:.]?\\s*E(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(\\d{1,2})x(\\d{1,3})\\b"),
            Pattern.compile("Season\\s*(\\d{1,2}).{0,12}?Episode\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE)};

    /**
     * Stremio doesn't say which title it plays, only its name: TMDB finds its IMDb id, and Stremio's own link opens
     * that title's page (the episode's, for a series, when the name says which), one click from Play. The forms were
     * tested on the TV. null: not found (no TMDB key, or no match). Network: never on the UI thread.
     */
    static String stremioLink(String name, String episode, String ids) throws Exception {
        String imdb = null, type = null;
        int season = -1, ep = -1;
        Matcher m = IMDB.matcher(ids);
        if (m.find()) {
            imdb = m.group(1);
            if (m.group(2) != null) {
                season = Integer.parseInt(m.group(2));
                ep = Integer.parseInt(m.group(3));
                type = "series";
            }
        }
        String query = name;
        for (Pattern p : EPISODE) {
            Matcher n = p.matcher(name + " " + episode);
            if (!n.find()) continue;
            if (season < 0) {
                season = Integer.parseInt(n.group(1));
                ep = Integer.parseInt(n.group(2));
            }
            int at = name.indexOf(n.group());
            if (at > 0) query = name.substring(0, at).replaceAll("[\\s\\-:|·]+$", "");
            break;
        }
        if (imdb == null || type == null) {
            if (Config.TMDB_KEY.trim().isEmpty() || query.trim().isEmpty()) return null;
            JSONArray results = Recs.get("/search/multi?query=" + URLEncoder.encode(query.trim(), "UTF-8")).optJSONArray("results");
            JSONObject hit = null;
            for (int i = 0; results != null && i < results.length() && hit == null; i++) {
                JSONObject r = results.getJSONObject(i);
                String mt = r.optString("media_type");
                if (mt.equals("tv") || (mt.equals("movie") && season < 0)) hit = r;
            }
            if (hit == null) return null;
            boolean tv = hit.optString("media_type").equals("tv");
            type = tv ? "series" : "movie";
            if (imdb == null) imdb = Recs.get("/" + (tv ? "tv" : "movie") + "/" + hit.getLong("id") + "/external_ids").optString("imdb_id");
        }
        if (imdb == null || !imdb.startsWith("tt")) return null;
        if (type.equals("movie")) return "stremio:///detail/movie/" + imdb + "/" + imdb;
        return "stremio:///detail/series/" + imdb + (season >= 0 ? "/" + imdb + ":" + season + ":" + ep : "");
    }

    /**
     * Where to find a picture for a title whose app gave none: a YouTube video's thumbnail (its ID from the app, or the
     * one YouTubeFeed found), else the backdrop TMDB has for its name. Best first; empty when there's none. Network:
     * never on the UI thread.
     */
    static List<String> artElsewhere(Context c, String key, String name, String pkg, String mediaId) {
        List<String> out = new ArrayList<>();
        if (isYouTube(pkg)) {
            String id = YOUTUBE_ID.matcher(mediaId).matches() ? mediaId : null;
            Entry e = id == null ? find(c, key) : null;
            if (e != null && e.link.startsWith(YOUTUBE_WATCH)) id = e.link.substring(YOUTUBE_WATCH.length()).replaceAll("[&?#].*", "");
            if (id != null) {
                out.add("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg");
                out.add("https://i.ytimg.com/vi/" + id + "/mqdefault.jpg");   // 16:9 without the bars of hqdefault
            }
            return out;
        }
        if (Config.TMDB_KEY.trim().isEmpty()) return out;
        String query = name;
        for (Pattern p : EPISODE) {
            Matcher n = p.matcher(name);
            if (!n.find()) continue;
            if (n.start() > 0) query = name.substring(0, n.start()).replaceAll("[\\s\\-:|·]+$", "");
            break;
        }
        if (query.trim().isEmpty()) return out;
        try {
            JSONArray results = Recs.get("/search/multi?query=" + URLEncoder.encode(query.trim(), "UTF-8")).optJSONArray("results");
            for (int i = 0; results != null && i < results.length() && out.isEmpty(); i++) {
                JSONObject r = results.getJSONObject(i);
                String mt = r.optString("media_type"), path = r.optString("backdrop_path");
                if ((mt.equals("tv") || mt.equals("movie")) && path.startsWith("/")) out.add("https://image.tmdb.org/t/p/w780" + path);
            }
        } catch (Exception e) {
            AppLog.w("continue watching: no TMDB picture for " + query + ": " + e.getMessage());
        }
        return out;
    }

    /** Finds a picture elsewhere (artElsewhere) for a title that has none yet, and saves it. Network: off the UI thread. */
    static boolean findArt(Context c, String key, String name, String pkg, String mediaId) {
        if (art(c, key).exists()) return true;
        for (String url : artElsewhere(c, key, name, pkg, mediaId)) {
            android.graphics.Bitmap b = download(c, url);
            if (b != null) return saveArt(c, key, b);
        }
        AppLog.w("continue watching: no picture for " + key);
        return false;
    }

    /** Saves a title's picture (watch-art/), small enough for a selected card. */
    static boolean saveArt(Context c, String key, android.graphics.Bitmap b) {
        // Cards are about 330 px wide on a 1080p screen (500 when selected): 640 is plenty.
        if (b.getWidth() > 640) b = android.graphics.Bitmap.createScaledBitmap(b, 640, Math.max(1, Math.round(b.getHeight() * 640f / b.getWidth())), true);
        File f = art(c, key), tmp = new File(f.getPath() + "." + android.os.Process.myPid() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out);
        } catch (IOException e) {
            AppLog.w("continue watching artwork: " + e.getMessage());
            return false;
        }
        if (tmp.renameTo(f)) return true;
        AppLog.w("continue watching: artwork not saved");
        return false;
    }

    /** A picture from a link: the web (http goes as https: the app allows no plain http), or another app's content. */
    static android.graphics.Bitmap download(Context c, String link) {
        if (link.startsWith("http://")) link = "https://" + link.substring(7);
        try {
            if (link.startsWith("https://")) {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(link).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                try (java.io.InputStream in = conn.getInputStream()) {
                    return android.graphics.BitmapFactory.decodeStream(in);
                } finally {
                    conn.disconnect();
                }
            }
            if (link.startsWith("content://") || link.startsWith("android.resource://")) {
                try (java.io.InputStream in = c.getContentResolver().openInputStream(Uri.parse(link))) {
                    return in == null ? null : android.graphics.BitmapFactory.decodeStream(in);
                }
            }
        } catch (Exception e) {
            AppLog.w("continue watching artwork: " + e.getMessage());
        }
        return null;
    }

    /** Stremio's search for the title: when its page can't be found, the next best thing. */
    static String stremioSearch(String name) {
        try {
            return "stremio:///search?search=" + URLEncoder.encode(name, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return null;
        }
    }

    static final String YOUTUBE_WATCH = "https://www.youtube.com/watch?v=";

    /** Whether this YouTube entry still needs its video's ID (only a search would open it). */
    static boolean needsVideoId(Entry e) {
        return isYouTube(e.pkg) && !YOUTUBE_ID.matcher(e.mediaId).matches() && !e.link.startsWith(YOUTUBE_WATCH);
    }

    static Entry find(Context c, String key) {
        for (Entry e : load(c)) if (e.key.equals(key)) return e;
        return null;
    }

    private static final Pattern YOUTUBE_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Pattern NETFLIX_ID = Pattern.compile("\\d{5,10}");

    static boolean isYouTube(String pkg) {
        return pkg.startsWith("com.google.android.youtube") && !pkg.equals("com.google.android.youtube.tvmusic");
    }

    static boolean isStremio(String pkg) {
        return pkg.startsWith("com.stremio");
    }

    /**
     * The link that opens this title in its app, when we know one: the app's own media link, a YouTube video by its
     * ID (its own, or the one YouTubeFeed found), a Netflix title by its ID, the Stremio page found on TMDB. Without a
     * YouTube key, a YouTube video is a search for its title and channel, where it's the first result. null: only the app can be opened.
     */
    static Intent playIntent(Entry e) {
        String uri = null;
        if (e.mediaUri.startsWith("http://") || e.mediaUri.startsWith("https://")) {
            uri = e.mediaUri;
        } else if (isYouTube(e.pkg) && YOUTUBE_ID.matcher(e.mediaId).matches()) {
            uri = YOUTUBE_WATCH + e.mediaId;
        } else if (e.pkg.equals("com.netflix.ninja") && NETFLIX_ID.matcher(e.mediaId).matches()) {
            uri = "https://www.netflix.com/watch/" + e.mediaId;
        } else if (!e.link.isEmpty()) {
            uri = e.link;
        } else if (isYouTube(e.pkg) && !e.title.isEmpty()) {
            String q = (e.title + " " + e.subtitle).trim();
            try {
                uri = "https://www.youtube.com/results?search_query=" + java.net.URLEncoder.encode(q, "UTF-8");
            } catch (java.io.UnsupportedEncodingException ignored) {
                // UTF-8 is always there
            }
        }
        if (uri == null) return null;
        // A YouTube video starts where it was left (10 s back, to pick up the thread).
        if (uri.startsWith(YOUTUBE_WATCH) && e.position > 30_000) uri += "&t=" + (e.position / 1000 - 10) + "s";
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
        i.setPackage(e.pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return i;
    }
}
