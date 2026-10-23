package io.freemytv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Explore's "Trending now · YouTube" row: YouTube's most popular videos in the TV's country (YouTube Data API, with the
 * person's own free key, config.properties "youtube_key"; the key never goes to the log). Each video is a Pick of kind
 * "youtube" that plays in the YouTube app.
 */
final class YouTubeFeed {
    static final String KIND = "youtube", PKG = "com.google.android.youtube.tv";
    private static final String API = "https://www.googleapis.com/youtube/v3/videos";

    private YouTubeFeed() {}

    static boolean on() {
        return !Config.YOUTUBE_KEY.trim().isEmpty();
    }

    /** One page of 25 (blocks: call it off the UI thread); next[0] receives the next page's token, or null. */
    static List<Recs.Pick> page(String region, String language, String token, String[] next) throws IOException {
        String url;
        try {
            url = API + "?part=snippet&chart=mostPopular&maxResults=25&regionCode=" + URLEncoder.encode(region, "UTF-8")
                    + "&hl=" + URLEncoder.encode(language, "UTF-8")
                    + (token == null ? "" : "&pageToken=" + URLEncoder.encode(token, "UTF-8"))
                    + "&key=" + URLEncoder.encode(Config.YOUTUBE_KEY.trim(), "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IOException(e.toString());
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code == 400 || code == 403) throw new IOException("YouTube refused the key (HTTP " + code + ")");
            if (code != 200) throw new IOException("YouTube returned HTTP " + code);
            JSONObject o = new JSONObject(read(c.getInputStream()));
            next[0] = o.has("nextPageToken") ? o.getString("nextPageToken") : null;
            List<Recs.Pick> out = new ArrayList<>();
            JSONArray items = o.optJSONArray("items");
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject v = items.getJSONObject(i), s = v.getJSONObject("snippet");
                String id = v.getString("id"), thumb = thumbnail(s.optJSONObject("thumbnails"));
                if (thumb == null) continue;
                JSONObject local = s.optJSONObject("localized");
                Recs.Pick p = new Recs.Pick();
                p.kind = KIND;
                p.id = id.hashCode();
                p.title = local != null ? local.optString("title", s.optString("title")) : s.optString("title");
                p.meta = s.optString("channelTitle", "");
                p.overview = firstLines(local != null ? local.optString("description", "") : s.optString("description", ""));
                p.backdrop = "/youtube-" + id + ".jpg";   // the cache file's name
                p.image = thumb;
                p.pkg = PKG;
                p.app = "youtube";
                p.link = "https://www.youtube.com/watch?v=" + id;
                p.detailed = true;      // nothing to look up on TMDB
                out.add(p);
            }
            return out;
        } catch (org.json.JSONException e) {
            throw new IOException("YouTube's answer: " + e.getMessage());
        } finally {
            c.disconnect();
        }
    }

    /** The largest 16:9 thumbnail (maxres 1280×720, else standard or high). */
    private static String thumbnail(JSONObject t) {
        if (t == null) return null;
        for (String size : new String[] {"maxres", "standard", "high", "medium"}) {
            JSONObject o = t.optJSONObject(size);
            if (o != null && !o.optString("url").isEmpty()) return o.optString("url");
        }
        return null;
    }

    /** The description's start, without its links and hashtag lines (the card shows a few lines). */
    private static String firstLines(String text) {
        StringBuilder out = new StringBuilder();
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.contains("http") || l.startsWith("#")) continue;
            if (out.length() > 0) out.append(' ');
            out.append(l);
            if (out.length() > 300) break;
        }
        return out.toString();
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
}
