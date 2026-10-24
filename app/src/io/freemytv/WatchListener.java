package io.freemytv;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
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
        long playingSince = -1, playedMs, savedAt, savedPosition = -1;
        String key, linksFor, artFor;
        int artMisses;

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
        PlaybackState state = mc.getPlaybackState();
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        long position = position(state);
        // Paused: nothing new to keep (and every write makes the home screen read the list again).
        if (!isPlaying && known && (now - t.savedAt < 2_000 || position == t.savedPosition)) return;
        t.key = key;
        t.savedAt = now;
        t.savedPosition = position;
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
        String artKey = key + "|" + title;
        if (!artKey.equals(t.linksFor)) {
            t.linksFor = artKey;
            t.artMisses = 0;
            if (WatchHistory.needsVideoId(e) && YouTubeFeed.on()) {
                final String name = e.title, channel = e.subtitle;
                io.execute(() -> findYouTubeVideo(app, key, linkFor, name, channel));
            }
            if (WatchHistory.isStremio(pkg)) {
                final String name = e.title, episode = e.subtitle, id = e.mediaId + " " + e.mediaUri;
                io.execute(() -> findStremioLink(app, key, linkFor, name, episode, id));
            }
        }
        // The artwork once per title: a picture the app hands over, or one it links to. Apps often add it to their
        // metadata a little after the title, so without one it's looked for again on the next updates, and only then
        // found elsewhere (YouTube's thumbnail, TMDB's backdrop).
        if (artKey.equals(t.artFor)) return;
        Bitmap art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) art = md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        final Bitmap given = art == null ? null : art.copy(Bitmap.Config.ARGB_8888, false);
        final String link = text(md, MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI);
        final boolean elsewhere = given == null && link.isEmpty();
        if (elsewhere && ++t.artMisses < 2) return;
        t.artFor = artKey;
        final String name = e.title, mediaId = e.mediaId;
        io.execute(() -> saveArt(app, key, given, link, elsewhere ? name : null, pkg, mediaId));
    }

    /**
     * Saves the title's picture: the one the app gave, or the one it links to. With `name` (the app gave none), a
     * picture found for it instead, unless the title already has one (an earlier episode's).
     */
    private static void saveArt(Context c, String key, Bitmap given, String link, String name, String pkg, String mediaId) {
        Bitmap b = given;
        if (b == null && !link.isEmpty()) b = WatchHistory.download(c, link);
        if (b == null && name != null) {
            WatchHistory.findArt(c, key, name, pkg, mediaId);
            return;
        }
        if (b == null) AppLog.w("continue watching: no picture for " + key);
        else WatchHistory.saveArt(c, key, b);
    }

    /** Finds the YouTube video's ID (YouTubeFeed.findVideo), once per video, and keeps its link with the entry. */
    private static void findYouTubeVideo(Context c, String key, String linkFor, String title, String channel) {
        WatchHistory.Entry known = WatchHistory.find(c, key);
        if (known != null && known.linkFor.equals(linkFor) && known.link.startsWith(WatchHistory.YOUTUBE_WATCH)) return;
        try {
            String id = YouTubeFeed.findVideo(title, channel);
            if (id == null) return;
            WatchHistory.setLink(c, key, linkFor, WatchHistory.YOUTUBE_WATCH + id);
            AppLog.w("continue watching: " + title + " on YouTube is video " + id);
        } catch (Exception e) {
            AppLog.w("continue watching: no YouTube video for " + title + ": " + e.getMessage());
        }
    }

    /** Finds the Stremio title's page (WatchHistory.stremioLink) and keeps it with the entry. */
    private static void findStremioLink(Context c, String key, String linkFor, String name, String episode, String ids) {
        try {
            final String link = WatchHistory.stremioLink(name, episode, ids);
            if (link == null) return;
            WatchHistory.setLink(c, key, linkFor, link);
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
