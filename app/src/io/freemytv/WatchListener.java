package io.freemytv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Continue watching, recorded on the TV itself: streaming apps announce what they're playing (title, artwork,
 * progress) in a media session, for the remote and the Assistant. Reading other apps' sessions takes notification
 * access, which Free My TV gives itself over ADB when you switch (`cmd notification allow_listener`); it reads no
 * notifications. A title joins the list after a minute of playing (trailers and previews don't), and its position is
 * kept every 30 s while it plays. Nothing leaves the TV.
 *
 * It also answers the home screen's "play this" (ACTION_PLAY): when the title's app still has its session (paused in
 * the background), it brings the player back and resumes it.
 */
public class WatchListener extends NotificationListenerService implements MediaSessionManager.OnActiveSessionsChangedListener {
    static final String ACTION_PLAY = "io.freemytv.action.PLAY_WATCHED";
    static final String EXTRA_KEY = "key";

    private static final long JOIN_AFTER_MS = 60_000, SAVE_EVERY_MS = 30_000;
    /** Music apps: songs aren't something to continue watching. */
    private static final Set<String> MUSIC = new HashSet<>(Arrays.asList(
            "com.spotify.tv.android", "com.spotify.music", "deezer.android.tv", "com.google.android.youtube.tvmusic",
            "com.amazon.mp3", "com.aspiro.tidal", "com.apple.android.music", "com.soundcloud.android", "tunein.player"));

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Map<MediaSession.Token, Tracked> tracked = new HashMap<>();
    private MediaSessionManager sessions;
    private boolean registered;

    /** One app's session: its last metadata and how long it has played without stopping. */
    private final class Tracked extends MediaController.Callback {
        final MediaController controller;
        long playingSince = -1, playedMs, savedAt;
        String key, artFor;

        Tracked(MediaController controller) {
            this.controller = controller;
        }

        @Override public void onPlaybackStateChanged(PlaybackState state) { update(this); }
        @Override public void onMetadataChanged(MediaMetadata metadata) {
            playedMs = 0;   // a new title: its minute starts again
            playingSince = playing(controller) ? SystemClock.elapsedRealtime() : -1;
            update(this);
        }
        @Override public void onSessionDestroyed() { forget(controller.getSessionToken(), true); }
    }

    private final BroadcastReceiver play = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) {
            if (resume(intent.getStringExtra(EXTRA_KEY))) setResultCode(1);
        }
    };

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            for (Tracked t : new ArrayList<>(tracked.values())) update(t);
            handler.postDelayed(this, SAVE_EVERY_MS);
        }
    };

    @Override public void onListenerConnected() {
        super.onListenerConnected();
        try {
            sessions = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);
            ComponentName me = new ComponentName(this, WatchListener.class);
            sessions.addOnActiveSessionsChangedListener(this, me, handler);
            onActiveSessionsChanged(sessions.getActiveSessions(me));
        } catch (SecurityException e) {
            AppLog.w("continue watching: no access to media sessions: " + e.getMessage());
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_PLAY);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(play, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(play, filter);
        registered = true;
        handler.postDelayed(tick, SAVE_EVERY_MS);
    }

    @Override public void onListenerDisconnected() {
        if (sessions != null) sessions.removeOnActiveSessionsChangedListener(this);
        for (MediaSession.Token t : new ArrayList<>(tracked.keySet())) forget(t, true);
        if (registered) unregisterReceiver(play);
        registered = false;
        handler.removeCallbacks(tick);
        super.onListenerDisconnected();
    }

    @Override public void onActiveSessionsChanged(List<MediaController> controllers) {
        Set<MediaSession.Token> now = new HashSet<>();
        for (MediaController mc : controllers == null ? new ArrayList<MediaController>() : controllers) {
            MediaSession.Token token = mc.getSessionToken();
            now.add(token);
            if (tracked.containsKey(token) || getPackageName().equals(mc.getPackageName()) || MUSIC.contains(mc.getPackageName())) continue;
            Tracked t = new Tracked(mc);
            tracked.put(token, t);
            mc.registerCallback(t, handler);
            if (playing(mc)) t.playingSince = SystemClock.elapsedRealtime();
            update(t);
        }
        for (MediaSession.Token token : new ArrayList<>(tracked.keySet())) if (!now.contains(token)) forget(token, false);
    }

    private void forget(MediaSession.Token token, boolean save) {
        Tracked t = tracked.remove(token);
        if (t == null) return;
        if (save) update(t);
        t.controller.unregisterCallback(t);
    }

    private static boolean playing(MediaController mc) {
        PlaybackState s = mc.getPlaybackState();
        return s != null && s.getState() == PlaybackState.STATE_PLAYING;
    }

    /** Where playback is now: the last reported position, moved on by the time since, while playing. */
    private static long position(PlaybackState s) {
        if (s == null) return 0;
        long p = s.getPosition();
        if (s.getState() == PlaybackState.STATE_PLAYING && s.getLastPositionUpdateTime() > 0) {
            p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
        }
        return Math.max(0, p);
    }

    private static String text(MediaMetadata md, String... keys) {
        for (String k : keys) {
            CharSequence v = md.getText(k);
            if (v != null && v.toString().trim().length() > 0) return v.toString().trim();
        }
        return "";
    }

    private void update(Tracked t) {
        MediaController mc = t.controller;
        long now = SystemClock.elapsedRealtime();
        boolean isPlaying = playing(mc);
        if (isPlaying && t.playingSince < 0) t.playingSince = now;
        if (!isPlaying && t.playingSince >= 0) {
            t.playedMs += now - t.playingSince;
            t.playingSince = -1;
        }
        long played = t.playedMs + (t.playingSince >= 0 ? now - t.playingSince : 0);
        MediaMetadata md = mc.getMetadata();
        if (md == null) return;
        String title = text(md, MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE);
        if (title.isEmpty()) return;
        // A series: the app often names the show as the album (or the display subtitle): one card per show.
        String show = text(md, MediaMetadata.METADATA_KEY_ALBUM);
        String pkg = mc.getPackageName();
        String key = WatchHistory.key(pkg, show.isEmpty() ? title : show);
        boolean known = key.equals(t.key);
        if (played < JOIN_AFTER_MS && !known) return;
        if (!isPlaying && known && now - t.savedAt < 2_000) return;
        t.key = key;
        t.savedAt = now;
        PlaybackState state = mc.getPlaybackState();
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = position(state);
        boolean finished = duration > 0 && position >= duration * WatchHistory.FINISHED;
        final WatchHistory.Entry e = new WatchHistory.Entry();
        e.key = key;
        e.pkg = pkg;
        e.title = show.isEmpty() ? title : show;
        e.subtitle = show.isEmpty() ? text(md, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST) : title;
        e.mediaId = text(md, MediaMetadata.METADATA_KEY_MEDIA_ID);
        e.mediaUri = text(md, MediaMetadata.METADATA_KEY_MEDIA_URI);
        e.position = position;
        e.duration = duration;
        e.updatedAt = System.currentTimeMillis();
        final Context app = getApplicationContext();
        final String linkFor = e.title + "|" + e.subtitle;
        io.execute(() -> WatchHistory.update(app, list -> {
            for (Iterator<WatchHistory.Entry> it = list.iterator(); it.hasNext(); ) {
                WatchHistory.Entry old = it.next();
                if (!old.key.equals(e.key)) continue;
                if (old.linkFor.equals(linkFor)) {   // the same episode: the link found for it still holds
                    e.link = old.link;
                    e.linkFor = old.linkFor;
                }
                it.remove();
            }
            if (!finished) list.add(0, e);
        }));
        if (finished) {
            //noinspection ResultOfMethodCallIgnored
            WatchHistory.art(app, key).delete();
            return;
        }
        // The artwork once per title: a picture the app hands over, or one it links to.
        String artKey = key + "|" + title;
        if (artKey.equals(t.artFor)) return;
        t.artFor = artKey;
        if (WatchHistory.isStremio(pkg)) {
            final String name = e.title, episode = e.subtitle, id = e.mediaId + " " + e.mediaUri;
            io.execute(() -> findStremioLink(app, key, linkFor, name, episode, id));
        }
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        final Bitmap given = art == null ? null : art.copy(Bitmap.Config.ARGB_8888, false);
        final String link = text(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        io.execute(() -> saveArt(app, key, given, link));
    }

    private static void saveArt(Context c, String key, Bitmap given, String link) {
        Bitmap b = given;
        try {
            if (b == null && link.startsWith("https://")) {
                HttpURLConnection conn = (HttpURLConnection) new URL(link).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                try (InputStream in = conn.getInputStream()) {
                    b = BitmapFactory.decodeStream(in);
                } finally {
                    conn.disconnect();
                }
            }
            if (b == null) return;
            // Cards are about 330 px wide on a 1080p screen: 480 is plenty.
            if (b.getWidth() > 480) b = Bitmap.createScaledBitmap(b, 480, Math.max(1, Math.round(b.getHeight() * 480f / b.getWidth())), true);
            File f = WatchHistory.art(c, key), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                b.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            if (!tmp.renameTo(f)) AppLog.w("continue watching: artwork not saved");
        } catch (Exception e) {
            AppLog.w("continue watching artwork: " + e.getMessage());
        }
    }

    private static final Pattern IMDB = Pattern.compile("(tt\\d{6,10})(?::(\\d+):(\\d+))?");
    private static final Pattern[] EPISODE = {
            Pattern.compile("\\bS(\\d{1,2})\\s*[:.]?\\s*E(\\d{1,3})\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(\\d{1,2})x(\\d{1,3})\\b"),
            Pattern.compile("Season\\s*(\\d{1,2}).{0,12}?Episode\\s*(\\d{1,3})", Pattern.CASE_INSENSITIVE)};

    /**
     * Stremio doesn't say which title it plays, only its name: TMDB finds its IMDb id, and Stremio's own link opens
     * that title's page (the episode's, for a series), one click from Resume. Saved with the entry.
     */
    private static void findStremioLink(Context c, String key, String linkFor, String name, String episode, String ids) {
        try {
            String imdb = null, type = null;
            int season = -1, ep = -1;
            Matcher m = IMDB.matcher(ids);
            if (m.find()) {
                imdb = m.group(1);
                if (m.group(2) != null) {
                    season = Integer.parseInt(m.group(2));
                    ep = Integer.parseInt(m.group(3));
                }
                type = season >= 0 ? "series" : null;
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
                if (!Recs.hasKey(c) || query.trim().isEmpty()) return;
                JSONArray results = Recs.get("/search/multi?query=" + URLEncoder.encode(query.trim(), "UTF-8"))
                        .optJSONArray("results");
                JSONObject hit = null;
                for (int i = 0; results != null && i < results.length() && hit == null; i++) {
                    JSONObject r = results.getJSONObject(i);
                    String mt = r.optString("media_type");
                    if (mt.equals("tv") || (mt.equals("movie") && season < 0)) hit = r;
                }
                if (hit == null) return;
                boolean tv = hit.optString("media_type").equals("tv");
                type = tv ? "series" : "movie";
                if (imdb == null) {
                    imdb = Recs.get("/" + (tv ? "tv" : "movie") + "/" + hit.getLong("id") + "/external_ids").optString("imdb_id");
                }
            }
            if (imdb == null || !imdb.startsWith("tt")) return;
            final String link = "stremio:///detail/" + type + "/" + imdb
                    + (type.equals("series") && season >= 0 ? "/" + imdb + ":" + season + ":" + ep : "");
            WatchHistory.update(c, list -> {
                for (WatchHistory.Entry e : list) {
                    if (!e.key.equals(key)) continue;
                    e.link = link;
                    e.linkFor = linkFor;
                }
            });
            AppLog.w("continue watching: " + name + " in Stremio is " + link);
        } catch (Exception e) {
            AppLog.w("continue watching: no Stremio link for " + name + ": " + e.getMessage());
        }
    }

    /** The title's session is still alive: show its player and resume. */
    private boolean resume(String key) {
        if (key == null) return false;
        for (Tracked t : tracked.values()) {
            if (!key.equals(t.key)) continue;
            MediaController mc = t.controller;
            PendingIntent screen = mc.getSessionActivity();
            if (screen == null) return false;
            try {
                screen.send();
            } catch (PendingIntent.CanceledException e) {
                return false;
            }
            mc.getTransportControls().play();
            return true;
        }
        return false;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        io.shutdown();
        super.onDestroy();
    }
}
