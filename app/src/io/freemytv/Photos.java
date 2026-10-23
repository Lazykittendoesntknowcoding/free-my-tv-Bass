package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The screensaver's photos, when its background is Photos: mostly Bing's photos of the day (from several countries,
 * so there are more of them), with one in every few from the Chromecast featured photos (app/assets/photos.txt). That
 * list is from 2014 and many links are gone: one that fails for good is written to dead-photos.txt and never asked for
 * again. Only photos of 4K (3840×2160) or more are shown, cropped to the screen's shape, never stretched.
 * next() blocks (network and decoding): call it off the UI thread.
 */
final class Photos {
    /** A photo ready to draw: the image at the screen's size (in GPU memory), its frosted copy (1/6) and credit. */
    static final class Photo {
        final Bitmap image, frost;
        final String credit;

        Photo(Bitmap image, Bitmap frost, String credit) {
            this.image = image;
            this.frost = frost;
            this.credit = credit;
        }

        void recycle() {
            image.recycle();
            frost.recycle();
        }
    }

    private static final int MIN_W = 3840, MIN_H = 2160;
    /** One photo in this many is a Chromecast one. */
    private static final int CHROMECAST_EVERY = 4;
    /** Chromecast photos that fail in a row before the rest are left alone (until the screensaver starts again). */
    private static final int CHROMECAST_GIVE_UP = 8;
    private static final String BING = "https://www.bing.com/HPImageArchive.aspx?format=js&n=8&mkt=";
    private static final String[] MARKETS = {"en-GB", "en-US", "en-AU", "en-CA", "en-IN", "de-DE", "fr-FR", "es-ES",
            "it-IT", "ja-JP", "zh-CN", "pt-BR"};

    private final Context context;
    private final List<String[]> deck = new ArrayList<>();      // url, credit: still to show, in random order
    private final List<String[]> bing = new ArrayList<>();
    private int count, chromecastFails, bingAt;
    private boolean bingFetched;

    Photos(Context c) {
        context = c.getApplicationContext();
    }

    /** The next photo at w×h, or null when none could be loaded now. */
    Photo next(int w, int h) {
        count++;
        if (count % CHROMECAST_EVERY == 0 && chromecastFails < CHROMECAST_GIVE_UP) {
            Photo p = chromecast(w, h);
            if (p != null) return p;
        }
        for (int tries = 0; tries < 4; tries++) {
            String[] pick = nextBing();
            if (pick == null) break;
            Photo p = load(pick[0], pick[1], w, h, false);
            if (p != null) return p;
        }
        return chromecastFails < CHROMECAST_GIVE_UP ? chromecast(w, h) : null;   // Bing unreachable: try the others
    }

    private Photo chromecast(int w, int h) {
        for (int tries = 0; tries < 3 && chromecastFails < CHROMECAST_GIVE_UP; tries++) {
            String[] pick = nextChromecast();
            if (pick == null) {
                chromecastFails = CHROMECAST_GIVE_UP;
                return null;
            }
            Photo p = load(pick[0], pick[1], w, h, true);
            if (p != null) {
                chromecastFails = 0;
                return p;
            }
            chromecastFails++;
        }
        return null;
    }

    /** Downloads, checks the size and makes the screen's copy; a Chromecast link that can't work is marked dead. */
    private Photo load(String url, String credit, int w, int h, boolean listed) {
        File f = new File(context.getCacheDir(), "dream-photo");
        try {
            // Google's photo URLs carry their size ("/s1920-w1920-h1080-c/"): s0 is the original, cropped here.
            int code = download(listed ? url.replaceFirst("/s\\d+-[^/]*/", "/s0/") : url, f);
            if (code != 200) {
                AppLog.w("screensaver photo: HTTP " + code + " " + url);
                if (listed && (code == 403 || code == 404 || code == 410)) markDead(url);
                return null;
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            if (bounds.outWidth < MIN_W || bounds.outHeight < MIN_H) {
                AppLog.w("screensaver photo: " + bounds.outWidth + "×" + bounds.outHeight + ", under 4K: " + url);
                if (listed && bounds.outWidth > 0) markDead(url);   // it won't grow
                return null;
            }
            Bitmap[] made = Backdrop.photo(f, w, h, null);
            return new Photo(Backdrop.gpu(made[0]), made[1], credit);
        } catch (Exception e) {
            AppLog.w("screensaver photo: " + e.getMessage() + " " + url);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    // MARK: - Chromecast

    private File deadFile() {
        return new File(context.getFilesDir(), "dead-photos.txt");
    }

    private void markDead(String url) {
        try (OutputStream out = new FileOutputStream(deadFile(), true)) {
            out.write((url + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            AppLog.w("dead photos: " + e.getMessage());
        }
    }

    private String[] nextChromecast() {
        if (deck.isEmpty()) {
            Set<String> dead = new HashSet<>();
            try (BufferedReader r = new BufferedReader(new FileReader(deadFile()))) {
                String line;
                while ((line = r.readLine()) != null) dead.add(line.trim());
            } catch (Exception ignored) {   // no file yet
            }
            try (BufferedReader r = new BufferedReader(new InputStreamReader(context.getAssets().open("photos.txt"), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int tab = line.indexOf('\t');
                    String url = tab < 0 ? line : line.substring(0, tab), author = tab < 0 ? "" : line.substring(tab + 1).trim();
                    if (dead.contains(url)) continue;
                    deck.add(new String[] {url, author.isEmpty() ? "" : Ui.trf("Photo: {name}", "name", author)});
                }
            } catch (Exception e) {
                AppLog.w("photos list: " + e.getMessage());
            }
            Collections.shuffle(deck);
        }
        return deck.isEmpty() ? null : deck.remove(deck.size() - 1);
    }

    // MARK: - Bing

    /** Bing's last 16 photos of the day in each country, without repeats, in 4K (fetched once per screensaver). */
    private String[] nextBing() {
        if (!bingFetched) {
            bingFetched = true;
            Set<String> seen = new HashSet<>();
            outer:
            for (String market : MARKETS) {
                for (int idx = 0; idx <= 8; idx += 8) {
                    try {
                        JSONArray images = new JSONObject(new String(get(BING + market + "&idx=" + idx), StandardCharsets.UTF_8))
                                .getJSONArray("images");
                        for (int i = 0; i < images.length(); i++) {
                            JSONObject im = images.getJSONObject(i);
                            String base = im.optString("urlbase", "");
                            if (base.isEmpty() || !seen.add(photoId(base))) continue;
                            String url = (base.startsWith("http") ? base : "https://www.bing.com" + base) + "_UHD.jpg";
                            bing.add(new String[] {url, im.optString("copyright", "")});
                        }
                    } catch (Exception e) {
                        AppLog.w("bing photos: " + e.getMessage());
                        if (bing.isEmpty()) break outer;   // offline: don't wait on every country
                    }
                }
            }
            Collections.shuffle(bing);
        }
        return bing.isEmpty() ? null : bing.get(bingAt++ % bing.size());
    }

    /** The same photo in every country: "/th?id=OHR.LavenderField_EN-GB1234" and "_DE-DE5678" are one. */
    private static String photoId(String base) {
        int us = base.lastIndexOf('_');
        return us > 0 ? base.substring(0, us) : base;
    }

    // MARK: - Network

    /** Saves the body to `to`; returns the HTTP status. */
    private static int download(String url, File to) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) return code;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(to)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            return code;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] get(String url) throws Exception {
        HttpURLConnection c = open(url);
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

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "FreeMyTV");
        return c;
    }
}
