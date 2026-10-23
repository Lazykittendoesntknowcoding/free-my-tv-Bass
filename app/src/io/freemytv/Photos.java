package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The screensaver's photos, when its background is Photos: the Chromecast featured photos (app/assets/photos.txt,
 * shuffled), or Bing's photos of the day when those stop loading (the list is from 2014: a few links may be gone).
 * next() blocks (network and decoding): call it off the UI thread.
 */
final class Photos {
    /** A photo ready to draw: the image at the screen's size, in GPU memory, and its credit. */
    static final class Photo {
        final Bitmap image;
        final String credit;

        Photo(Bitmap image, String credit) {
            this.image = image;
            this.credit = credit;
        }
    }

    /** Chromecast photos that fail in a row before Bing takes over (until the screensaver starts again). */
    private static final int FAILS_BEFORE_BING = 5;
    private static final String BING = "https://www.bing.com/HPImageArchive.aspx?format=js&mkt=en-GB&n=8&idx=";

    private final Context context;
    private final List<String[]> deck = new ArrayList<>();      // url, credit: still to show, in random order
    private final List<String[]> bing = new ArrayList<>();
    private int fails, bingAt;

    Photos(Context c) {
        context = c.getApplicationContext();
    }

    /** The next photo at w×h, or null when none could be loaded now. */
    Photo next(int w, int h) {
        for (int tries = 0; tries < FAILS_BEFORE_BING + 3; tries++) {
            boolean useBing = fails >= FAILS_BEFORE_BING;
            String[] pick = useBing ? nextBing() : nextChromecast();
            if (pick == null) {
                if (useBing) return null;
                fails = FAILS_BEFORE_BING;
                continue;
            }
            String url = useBing ? pick[0] : sized(pick[0], w, h);
            try {
                File f = new File(context.getCacheDir(), "dream-photo");
                download(url, f);
                Bitmap[] made = Backdrop.photo(f, w, h, null);
                made[1].recycle();   // the frosted copy: not used here
                Bitmap gpu = Backdrop.gpu(made[0]);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                if (!useBing) fails = 0;
                return new Photo(gpu, pick[1]);
            } catch (Exception e) {
                AppLog.w("screensaver photo: " + e.getMessage());
                if (!useBing) fails++;
                else if (bing.isEmpty()) return null;
            }
        }
        return null;
    }

    private String[] nextChromecast() {
        if (deck.isEmpty()) {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(context.getAssets().open("photos.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    String url = tab < 0 ? line : line.substring(0, tab), author = tab < 0 ? "" : line.substring(tab + 1).trim();
                    deck.add(new String[] {url, author.isEmpty() ? "" : Ui.trf("Photo: {name}", "name", author)});
                }
            } catch (Exception e) {
                AppLog.w("photos list: " + e.getMessage());
            }
            Collections.shuffle(deck);
        }
        return deck.isEmpty() ? null : deck.remove(deck.size() - 1);
    }

    /** Bing's last 16 photos of the day, in turn (fetched once each time the screensaver starts). */
    private String[] nextBing() {
        if (bing.isEmpty()) {
            for (int idx = 0; idx <= 8; idx += 8) {
                try {
                    JSONArray images = new JSONObject(new String(get(BING + idx), StandardCharsets.UTF_8)).getJSONArray("images");
                    for (int i = 0; i < images.length(); i++) {
                        JSONObject im = images.getJSONObject(i);
                        String path = im.optString("url", "");
                        if (path.isEmpty()) continue;
                        bing.add(new String[] {path.startsWith("http") ? path : "https://www.bing.com" + path, im.optString("copyright", "")});
                    }
                } catch (Exception e) {
                    AppLog.w("bing photos: " + e.getMessage());
                }
            }
            Collections.shuffle(bing);
            bingAt = 0;
        }
        return bing.isEmpty() ? null : bing.get(bingAt++ % bing.size());
    }

    /** Google's photo URLs carry their size ("/s1920-w1920-h1080-c/"): ask for exactly the screen's. */
    private static String sized(String url, int w, int h) {
        return url.replaceFirst("/s\\d+-[^/]*/", "/s" + w + "-w" + w + "-h" + h + "-c/");
    }

    private static void download(String url, File to) throws Exception {
        byte[] body = get(url);
        try (FileOutputStream out = new FileOutputStream(to)) {
            out.write(body);
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                return out.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }
}
