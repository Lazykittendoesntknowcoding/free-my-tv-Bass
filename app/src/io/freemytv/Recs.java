package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
        String year = "", overview = "", poster = "";
        float rating;           // TMDB's score out of 10 (0: too few votes to say)
        String trailer;         // YouTube video id of the official trailer, or null: search YouTube
        String imdb = "";       // IMDb id ("tt…"): Stremio's page for the title
        String image;           // its picture's whole URL (YouTube), or null: TMDB's, from `backdrop`
        Set<String> themes = new java.util.LinkedHashSet<>();   // the rows it's in: HOT, POPULAR, NEW, TOP, "g<genre id>"
        // Continue watching (kind "watch"): the history's key and how far in (0 to 1).
        String watchKey;
        float progress;
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Images kept on disk (each kind: the cards' and the big backgrounds'). */
    private static final int MAX_PICKS = 60;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 6;
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** The rows of a tab: trending this week, recent releases, the best rated. */
    static final String HOT = "hot", POPULAR = "popular", NEW = "new", TOP = "top", KIDS = "kids";
    /** Genre rows (TMDB ids), in this order; their names come from TMDB in the TV's language. */
    private static final int[] MOVIE_GENRES = {28, 35, 18, 53, 878, 27, 80, 10749, 99};
    private static final int[] TV_GENRES = {10759, 35, 18, 80, 10765, 9648, 99, 10764};
    /** Kids: TMDB's Kids genre for shows; for movies (no such genre), Family without horror, thrillers or crime. */
    private static final int TV_KIDS = 10762, FAMILY = 10751;

    /**
     * Where a pick opens: Apple TV when the title is there and its own page is known (Watchmode), otherwise its page in
     * Stremio (Netflix asks for a profile first and drops the title's link; Prime Video too, the owner chose Stremio).
     */
    static final String STREMIO = "com.stremio.one";
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"okko", "okko", "ru.more.play"},
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("stremio", "Stremio");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Config.TMDB_REGION.trim().toUpperCase(Locale.ROOT);
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final ExecutorService quick = Executors.newSingleThreadExecutor();   // what the person is waiting for
    private final Set<String> detailing = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, Backdrop> images = new LinkedHashMap<>();   // this hour's, built and ready to draw
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile Map<String, String> genreNames = new HashMap<>();   // "movie:28" → "Action", in the TV's language
    private volatile String filters = "";   // the TMDB list filters: the TV's services in REGION (rows' next pages)
    private final Map<String, Integer> pages = new HashMap<>();          // row ("movie|top") → pages it has
    private final Set<String> exhausted = java.util.Collections.synchronizedSet(new HashSet<>());
    private final Set<String> growing = java.util.Collections.synchronizedSet(new HashSet<>());
    // Per tab ("all", "movie", "tv"): each title's row (key → theme), so a title shows in one row of a tab only and
    // stays in the row it first showed in as more pages come. A new list starts again.
    private final Map<String, Map<String, String>> owners = new HashMap<>();
    private long ownersAt;
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            JSONObject names = new JSONObject(cache.getString("genres", "{}"));
            Map<String, String> loaded = new HashMap<>();
            for (java.util.Iterator<String> it = names.keys(); it.hasNext(); ) {
                String k = it.next();
                loaded.put(k, names.optString(k));
            }
            genreNames = loaded;
            filters = cache.getString("filters", "");
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** This hour's 3, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) if (shown(p, kind, have)) list.add(p);
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // Three per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…").
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < list.size() && out.size() < 3; i++) {
            Pick p = list.get((int) ((hour * 3 + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** Of that kind (null: any), its app on the TV (skipped when missing), not hidden and not already being watched. */
    private boolean shown(Pick p, String kind, Map<String, Boolean> have) {
        if (kind != null && !kind.equals(p.kind)) return false;
        if (p.detailed && (p.pkg == null || !have.computeIfAbsent(p.pkg, this::installed))) return false;
        return !hidden().contains(key(p)) && !watched(p.title);
    }

    static final class Row {
        String theme, title;
        String kind;    // "movie" or "tv", or null for movies and shows together (what more() takes)
        final List<Pick> picks = new ArrayList<>();

        /** What Prefs keeps a moved or hidden row by: its kind and theme ("movie:popular", "tv:g16"). */
        String id() { return rowId(kind, theme); }
    }

    static String rowId(String kind, String theme) {
        return kind + ":" + theme;
    }

    // MARK: - Which rows a tab shows (Home Settings › Content, and Move and Hide on the home screen)

    /** The groups Home Settings › Content lists the rows in. */
    static final String[] GROUPS = {"picks", "services", "genres", "more", "youtube"};
    /** Services with rows of their own: theme "s" + id (popular), + "n" (new), + "t" (top rated); TMDB's ids. */
    private static final String[][] ROW_SERVICES = {
        {"8", "8|1796", "Netflix"},          // 1796: Netflix with ads
        {"9", "9|119", "Prime Video"},       // TMDB lists Prime Video under both
        {"350", "350", "Apple TV"},
        {"337", "337", "Disney+"},
        {"39", "39", "NOW"},
        {"531", "531", "Paramount+"},
    };
    /** Every TMDB genre (Kids is KIDS); the ones not in MOVIE_GENRES or TV_GENRES are rows to add. */
    private static final int[] ALL_MOVIE_GENRES = {28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878, 10770, 53, 10752, 37};
    private static final int[] ALL_TV_GENRES = {10759, 16, 35, 80, 99, 18, 10751, 9648, 10763, 10764, 10765, 10766, 10767, 10768, 37};
    static final String GEMS = "gems", CLASSICS = "classics";
    private static final int[] DECADES = {1970, 1980, 1990, 2000, 2010};
    /** YouTube's trending charts by category ("y" + YouTube's category id). */
    private static final String[] YOUTUBE_CATEGORIES = {"y10", "y20", "y17", "y1"};   // music, gaming, sport, film
    /** A row a tab can show: on unless hidden (`standard`, the rows a tab always had), or off until added. */
    private static final class RowDef {
        final String kind, theme, group;
        final boolean standard;

        RowDef(String kind, String theme, String group, boolean standard) {
            this.kind = kind;
            this.theme = theme;
            this.group = group;
            this.standard = standard;
        }

        String id() { return rowId(kind, theme); }
    }

    private static final Map<String, List<RowDef>> CATALOGUES = new HashMap<>();

    /** Every row the tab ("all", "movie", "tv") can show, the standard ones in their own order. */
    private static synchronized List<RowDef> catalogue(String tab) {
        List<RowDef> out = CATALOGUES.get(tab);
        if (out != null) return out;
        out = new ArrayList<>();
        boolean explore = tab.equals("all");
        String[] kinds = explore ? new String[] {"movie", "tv"} : new String[] {tab};
        for (String theme : new String[] {HOT, POPULAR, NEW, TOP, KIDS}) {
            for (String kd : kinds) out.add(new RowDef(kd, theme, "picks", true));
            if (explore && theme.equals(HOT)) out.add(new RowDef(YouTubeFeed.KIND, HOT, "youtube", true));
        }
        if (!explore) for (int g : tab.equals("movie") ? MOVIE_GENRES : TV_GENRES) out.add(new RowDef(tab, "g" + g, "genres", true));
        for (String[] s : ROW_SERVICES) {
            for (String suffix : new String[] {"", "n", "t"}) for (String kd : kinds) out.add(new RowDef(kd, "s" + s[0] + suffix, "services", false));
        }
        for (String kd : kinds) {
            int[] standard = kd.equals("movie") ? MOVIE_GENRES : TV_GENRES;
            for (int g : kd.equals("movie") ? ALL_MOVIE_GENRES : ALL_TV_GENRES) {
                if (Config.HIDE_ANIMATION && g == ANIMATION) continue;
                boolean has = false;
                for (int s : standard) has |= s == g;
                if (explore || !has) out.add(new RowDef(kd, "g" + g, "genres", false));
            }
        }
        List<String> more = new ArrayList<>(Arrays.asList(GEMS, CLASSICS));
        for (int d : DECADES) more.add("d" + d);
        for (String theme : more) for (String kd : kinds) out.add(new RowDef(kd, theme, "more", false));
        if (!explore) out.add(new RowDef(YouTubeFeed.KIND, HOT, "youtube", false));
        for (String y : YOUTUBE_CATEGORIES) out.add(new RowDef(YouTubeFeed.KIND, y, "youtube", false));
        CATALOGUES.put(tab, out);
        return out;
    }

    private static RowDef def(String tab, String id) {
        for (RowDef d : catalogue(tab)) if (d.id().equals(id)) return d;
        return null;
    }

    private static boolean on(RowDef d, Set<String> hidden, List<String> added) {
        return d.standard ? !hidden.contains(d.id()) : added.contains(d.id());
    }

    /** The rows the tab shows, in their order: the standard ones, then the added ones as they came, then moved rows. */
    private static List<RowDef> defsOn(String tab) {
        Set<String> hidden = Prefs.hiddenRows(tab);
        List<String> added = Prefs.addedRows(tab);
        List<RowDef> out = new ArrayList<>();
        for (RowDef d : catalogue(tab)) if (d.standard && on(d, hidden, added)) out.add(d);
        for (String id : added) {
            RowDef d = def(tab, id);
            if (d != null && !d.standard) out.add(d);
        }
        return ordered(out, Prefs.rowOrder(tab), RowDef::id);
    }

    /** Hide on the home screen: the row leaves the tab ("all", "movie" or "tv"); an added one is just taken off. */
    static void hideRow(String tab, String id) {
        RowDef d = def(tab, id);
        if (d != null && !d.standard) Prefs.setRowAdded(tab, id, false);
        else Prefs.hideRow(tab, id);
    }

    /** Home Settings › Content: the ids of the rows the tab shows, in order. */
    static List<String> rowsOn(String tab) {
        List<String> out = new ArrayList<>();
        for (RowDef d : defsOn(tab)) out.add(d.id());
        return out;
    }

    /** Home Settings › Content: the ids of a group's rows. */
    static List<String> rowChoices(String tab, String group) {
        List<String> out = new ArrayList<>();
        for (RowDef d : catalogue(tab)) if (d.group.equals(group)) out.add(d.id());
        return out;
    }

    static boolean rowOn(String tab, String id) {
        RowDef d = def(tab, id);
        return d != null && on(d, Prefs.hiddenRows(tab), Prefs.addedRows(tab));
    }

    /** Ticks a row on or off; a row turned on goes at the end of the tab. */
    void setRowOn(String tab, String id, boolean on) {
        RowDef d = def(tab, id);
        if (d == null) return;
        if (d.standard && on) {
            Prefs.unhideRow(tab, id);
            List<String> order = Prefs.rowOrder(tab);
            if (order.remove(id)) {   // a moved row comes back at the end too
                order.add(id);
                Prefs.setRowOrder(tab, order);
            }
        } else if (d.standard) {
            Prefs.hideRow(tab, id);
        } else {
            Prefs.setRowAdded(tab, id, on);
        }
        notifyListeners();
    }

    /** Swaps two rows the tab shows (Home Settings › Content's ← →: with the row above or below in its list). */
    void swapRows(String tab, String id, String other) {
        List<String> ids = rowsOn(tab);
        int at = ids.indexOf(id), to = ids.indexOf(other);
        if (at < 0 || to < 0) return;
        Collections.swap(ids, at, to);
        Prefs.setRowOrder(tab, ids);
        notifyListeners();
    }

    /** A row's name in Home Settings › Content: its heading, with Movies or Shows on Explore. */
    String rowName(String tab, String id) {
        RowDef d = def(tab, id);
        if (d == null) return id;
        String title = title(d, tab.equals("all"));
        return title != null ? title : Ui.tr("Genre");
    }

    static String groupName(String group) {
        switch (group) {
            case "picks": return Ui.tr("Trending, popular, new");
            case "services": return Ui.tr("Streaming services");
            case "genres": return Ui.tr("Genres");
            case "more": return Ui.tr("Gems, classics, decades");
            default: return "YouTube";
        }
    }

    /** The row's heading (null while its genre's name hasn't come). */
    private String title(RowDef d, boolean explore) {
        if (YouTubeFeed.KIND.equals(d.kind)) {
            String name = d.theme.equals("y10") ? Ui.tr("youtube|Music") : d.theme.equals("y20") ? Ui.tr("youtube|Gaming")
                    : d.theme.equals("y17") ? Ui.tr("youtube|Sport") : d.theme.equals("y1") ? Ui.tr("youtube|Film & animation")
                    : Ui.tr("Trending now");
            return Ui.trf("{row} · {kind}", "row", name, "kind", "YouTube");
        }
        String title = themeTitle(d.theme, d.kind);
        if (title == null || !explore) return title;
        return Ui.trf("{row} · {kind}", "row", title, "kind", d.kind.equals("movie") ? Ui.tr("Movies") : Ui.tr("Shows"));
    }

    /**
     * A tab's rows (movies "movie", shows "tv", both null): the ones Home Settings › Content has on, in their order.
     * Standard: Trending now, Popular, New releases, Top rated, Kids, then (movies or shows) one row per genre; Explore
     * has each of the first ones twice, a movies row then a shows row, with Trending now · YouTube after Trending.
     */
    List<Row> rows(String kind) {
        List<Row> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        Map<String, Boolean> have = new HashMap<>();
        String tab = kind == null ? "all" : kind;
        List<Row> all = new ArrayList<>();
        for (RowDef d : defsOn(tab)) all.add(row(d.kind, d.theme, title(d, kind == null)));
        // Titles go to the narrowest row first: Popular (and New, Top) would otherwise take most of Kids' and the
        // genres' titles, and their feeds are far longer to make up for it.
        List<Row> claim = new ArrayList<>();
        for (Row row : all) if (!YouTubeFeed.KIND.equals(row.kind)) claim.add(row);
        Collections.sort(claim, (a, b) -> claimOrder(a.theme) - claimOrder(b.theme));   // stable: movies before shows
        for (Row row : claim) fill(row, have);
        Set<String> seen = new HashSet<>();   // a video shows in one of the tab's YouTube rows only
        for (Row row : all) {
            if (YouTubeFeed.KIND.equals(row.kind)) fillYouTube(row, seen);
            if (!row.picks.isEmpty()) out.add(row);
        }
        return out;
    }

    /**
     * The rows moved on the TV take the places of the moved rows, in the order saved; the others keep their own
     * places (a row that came later, such as an added one, shows where it would anyway).
     */
    private static <T> List<T> ordered(List<T> rows, List<String> order, java.util.function.Function<T, String> id) {
        if (order.isEmpty()) return rows;
        List<T> moved = new ArrayList<>();
        for (T row : rows) if (order.contains(id.apply(row))) moved.add(row);
        Collections.sort(moved, (a, b) -> order.indexOf(id.apply(a)) - order.indexOf(id.apply(b)));
        List<T> out = new ArrayList<>(rows.size());
        int next = 0;
        for (T row : rows) out.add(order.contains(id.apply(row)) ? moved.get(next++) : row);
        return out;
    }

    /** Narrow rows first: Trending, Kids, a genre, a service, the gems, classics and decades; then New, Top, Popular. */
    private static int claimOrder(String theme) {
        return theme.equals(HOT) ? 0 : theme.equals(KIDS) ? 1 : theme.equals(NEW) ? 3 : theme.equals(TOP) ? 4
                : theme.equals(POPULAR) ? 5 : 2;
    }

    private static Row row(String kind, String theme, String title) {
        Row row = new Row();
        row.kind = kind;
        row.theme = theme;
        row.title = title;
        return row;
    }

    /** A row's heading: the theme's, or the genre's name in the TV's language (null until the genre list came). */
    private String themeTitle(String theme, String kind) {
        if (theme.equals(HOT)) return Ui.tr("Trending now");
        if (theme.equals(POPULAR)) return Ui.tr("Popular");
        if (theme.equals(NEW)) return Ui.tr("New releases");
        if (theme.equals(TOP)) return Ui.tr("Top rated");
        if (theme.equals(KIDS)) return Ui.tr("genre|Kids");
        if (theme.equals(GEMS)) return Ui.tr("Hidden gems");
        if (theme.equals(CLASSICS)) return Ui.tr("Classics");
        if (theme.startsWith("d")) return Ui.trf("The {decade}s", "decade", theme.substring(1));
        if (theme.startsWith("s")) {
            String[] s = rowService(theme);
            if (s == null) return null;
            String suffix = theme.substring(1 + s[0].length());
            if (suffix.equals("n")) return Ui.trf("New on {service}", "service", s[2]);
            if (suffix.equals("t")) return Ui.trf("Top rated on {service}", "service", s[2]);
            return Ui.trf("Popular on {service}", "service", s[2]);
        }
        return genreNames.get(kind + ":" + theme.substring(1));
    }

    /** The service of a theme "s8", "s8n", "s8t". */
    private static String[] rowService(String theme) {
        int end = 1;
        while (end < theme.length() && Character.isDigit(theme.charAt(end))) end++;
        String id = theme.substring(1, end);
        for (String[] s : ROW_SERVICES) if (s[0].equals(id)) return s;
        return null;
    }

    /** The row's titles, but those another row of the tab already shows; a short row asks for its next pages. */
    private void fill(Row row, Map<String, Boolean> have) {
        if (row.title == null) return;
        synchronized (owners) {
            Map<String, String> own = owners(row.kind);
            for (Pick p : picks) {
                if (!p.themes.contains(row.theme) || !shown(p, row.kind, have)) continue;
                String o = own.get(key(p));
                if (o == null) own.put(key(p), o = row.theme);   // the first row to show it keeps it
                if (o.equals(row.theme)) row.picks.add(p);
            }
        }
        if (row.picks.size() < SHORT_ROW && pagesOf(row.kind, row.theme) < 20) more(row.kind, row.theme);
    }

    private static final int SHORT_ROW = 12;

    /** Rows the first list doesn't fetch (added in Home Settings › Content): their first page comes with more(). */
    private static boolean listed(String kind, String theme) {
        if (theme.equals(HOT) || theme.equals(POPULAR) || theme.equals(NEW) || theme.equals(TOP) || theme.equals(KIDS)) return true;
        if (!theme.startsWith("g")) return false;
        for (String kd : kind == null ? new String[] {"movie", "tv"} : new String[] {kind}) {
            for (int g : kd.equals("movie") ? MOVIE_GENRES : TV_GENRES) if (theme.equals("g" + g)) return true;
        }
        return false;
    }

    // MARK: - Trending on YouTube

    /** One of YouTube's trending charts (theme HOT: all videos; "y10": music…). */
    private static final class Feed {
        volatile List<Pick> items = new ArrayList<>();
        String next;
        long at, failedAt;
        final java.util.concurrent.atomic.AtomicBoolean busy = new java.util.concurrent.atomic.AtomicBoolean();
    }

    private final Map<String, Feed> feeds = new HashMap<>();
    private static final long YOUTUBE_EVERY = 3600_000L;

    private synchronized Feed feed(String theme) {
        Feed f = feeds.get(theme);
        if (f == null) feeds.put(theme, f = new Feed());
        return f;
    }

    /** A YouTube row (with youtube_key), its first page fetched again every hour; videos an earlier row has are left out. */
    private void fillYouTube(Row row, Set<String> seen) {
        if (!YouTubeFeed.on()) return;
        Feed f = feed(row.theme);
        long now = System.currentTimeMillis();
        if ((f.items.isEmpty() || now - f.at > YOUTUBE_EVERY || now < f.at) && now - f.failedAt > 10 * 60_000L) {
            fetchYouTube(row.theme, true);
        }
        for (Pick p : f.items) if (!hidden().contains(key(p)) && seen.add(key(p))) row.picks.add(p);
    }

    /** The first page again (fresh), or the next one; one fetch at a time per chart, then the rows are rebuilt. */
    private void fetchYouTube(final String theme, final boolean fresh) {
        final Feed f = feed(theme);
        if (!fresh && f.next == null) return;   // the end of YouTube's list
        if (!f.busy.compareAndSet(false, true)) return;
        final String token = fresh ? null : f.next;
        final String category = theme.startsWith("y") ? theme.substring(1) : null;
        quick.execute(() -> {
            try {
                String[] next = new String[1];
                List<Pick> page = YouTubeFeed.page(REGION, lang(), category, token, next);
                List<Pick> all = fresh ? new ArrayList<>() : new ArrayList<>(f.items);
                Set<String> seen = new HashSet<>();
                for (Pick p : all) seen.add(key(p));
                for (Pick p : page) if (seen.add(key(p))) all.add(p);
                f.items = all;
                f.next = next[0];
                if (fresh) f.at = System.currentTimeMillis();
                notifyListeners();
            } catch (IOException e) {
                f.failedAt = System.currentTimeMillis();
                AppLog.w("youtube " + theme + ": " + e.getMessage());
            } finally {
                f.busy.set(false);
            }
        });
    }

    /** The tab's titles and the row each shows in (hold `owners`). */
    private Map<String, String> owners(String kind) {
        if (ownersAt != listAt) {
            owners.clear();
            ownersAt = listAt;
        }
        String tab = kind == null ? "all" : kind;
        Map<String, String> own = owners.get(tab);
        if (own == null) owners.put(tab, own = new HashMap<>());
        return own;
    }

    private int pagesOf(String kind, String theme) {
        synchronized (pages) {
            Integer n = pages.get(pageKey(kind, theme));
            return n == null ? 1 : n;
        }
    }

    // MARK: - Hidden and already watched

    /** Titles hidden with "Not interested" (their keys), in their own file: a new cache version doesn't bring them back. */
    private Set<String> hiddenKeys;

    private synchronized Set<String> hidden() {
        if (hiddenKeys == null) {
            hiddenKeys = new HashSet<>(context.getSharedPreferences("recs-hidden", Context.MODE_PRIVATE)
                    .getStringSet("keys", new HashSet<>()));
        }
        return hiddenKeys;
    }

    /** "Not interested": the title doesn't show again, in any tab or row. */
    synchronized void hide(Pick p) {
        Set<String> keys = new HashSet<>(hidden());
        keys.add(key(p));
        hiddenKeys = keys;
        context.getSharedPreferences("recs-hidden", Context.MODE_PRIVATE).edit().putStringSet("keys", keys).apply();
        notifyListeners();
    }

    private long watchedStamp = -1;
    private Set<String> watchedTitles = new HashSet<>();

    /** Being watched already (in Continue watching): no need to recommend it. */
    private synchronized boolean watched(String title) {
        long stamp = WatchHistory.stamp(context);
        if (stamp != watchedStamp) {
            watchedStamp = stamp;
            Set<String> titles = new HashSet<>();
            for (WatchHistory.Entry e : WatchHistory.load(context)) {
                titles.add(e.title.trim().toLowerCase(Locale.ROOT));
                int bar = e.key.indexOf('|');
                if (bar >= 0) titles.add(e.key.substring(bar + 1));   // the series' name
            }
            watchedTitles = titles;
        }
        return title != null && watchedTitles.contains(title.trim().toLowerCase(Locale.ROOT));
    }

    // MARK: - Trailer

    /** The title's trailer in YouTube: the official one when TMDB knows it, else a YouTube search for it. */
    static android.content.Intent trailer(Pick p) {
        android.content.Intent i;
        if (p.trailer != null) {
            i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.youtube.com/watch?v=" + p.trailer));
        } else {
            i = new android.content.Intent(android.content.Intent.ACTION_SEARCH).putExtra(android.app.SearchManager.QUERY, trailerQuery(p));
        }
        return i.setPackage(YOUTUBE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** When YouTube won't take the search request: its search page. */
    static android.content.Intent trailerSearch(Pick p) {
        try {
            return new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(
                    "https://www.youtube.com/results?search_query=" + URLEncoder.encode(trailerQuery(p), "UTF-8")))
                    .setPackage(YOUTUBE).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new RuntimeException(e);
        }
    }

    private static String trailerQuery(Pick p) {
        return p.title + (p.year.isEmpty() ? "" : " " + p.year) + " trailer";
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language)
                || filters.isEmpty();   // a list saved before the filters were: no row could load its next pages
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {null, "movie", "tv"}) {
            for (Pick p : now(kind)) if (seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        filters = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        synchronized (pages) {
            pages.clear();
            exhausted.clear();
        }
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now): the details say
        //    where each streams (and are then ready); on none of the TV's services, Stremio.
        Map<String, Pick> all = new LinkedHashMap<>();
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get(path(kind, HOT, 1)).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length(); i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                pick.themes.add(HOT);
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        alternate(all, hotMovies, hotShows);
        // 2. On the TV's services: the most popular, recent releases and the best rated.
        for (String theme : new String[] {POPULAR, NEW, TOP}) alternate(all, page("movie", theme, 1), page("tv", theme, 1));
        // 3. Kids, then one row per genre.
        Map<String, String> names = new HashMap<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray genres = get("/genre/" + kind + "/list?language=" + lang()).optJSONArray("genres");
            for (int i = 0; genres != null && i < genres.length(); i++) {
                JSONObject g = genres.getJSONObject(i);
                names.put(kind + ":" + g.optInt("id"), genre(g.optString("name")));
            }
            List<String> themes = new ArrayList<>();
            themes.add(KIDS);
            for (int g : kind.equals("movie") ? MOVIE_GENRES : TV_GENRES) themes.add("g" + g);
            for (String theme : themes) {
                try {
                    merge(all, page(kind, theme, 1));
                } catch (Exception e) {
                    AppLog.w("recommendations: " + theme + ": " + e.getMessage());
                }
            }
        }
        genreNames = names;
        List<Pick> list = new ArrayList<>(all.values());
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    /** Movies and shows alternating; a title already in the list just joins the new row too. */
    private static void alternate(Map<String, Pick> out, List<Pick> a, List<Pick> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            if (i < a.size()) merge(out, a.get(i));
            if (i < b.size()) merge(out, b.get(i));
        }
    }

    private static void merge(Map<String, Pick> out, List<Pick> list) {
        for (Pick p : list) merge(out, p);
    }

    private static void merge(Map<String, Pick> out, Pick p) {
        Pick had = out.get(key(p));
        if (had == null) out.put(key(p), p);
        else had.themes.addAll(p.themes);
    }

    /**
     * The TMDB request for page `page` of a row ("movie" or "tv"): trending this week, or a list of what's on the TV's
     * services (filters): the most popular, recent releases (movies from the past year, shows with a new episode in the
     * past 3 months), the best rated (with enough votes to mean something), kids, a genre (with some votes, so not
     * just what's new and unknown).
     */
    private String path(String kind, String theme, int page) throws IOException {
        if (theme.equals(HOT)) return "/trending/" + kind + "/week?language=" + lang() + "&page=" + page;
        boolean movie = kind.equals("movie");
        String f = filters;
        if (theme.startsWith("s")) {
            // A service's own row: that service only (even one the TV lacks: its titles open in Stremio), then as
            // Popular, New releases or Top rated.
            String[] s = rowService(theme);
            if (s == null) throw new IOException("no such row: " + theme);
            String suffix = theme.substring(1 + s[0].length());
            f = filters.replaceAll("&with_watch_providers=[^&]*", "") + "&with_watch_providers=" + URLEncoder.encode(s[1], "UTF-8");
            theme = suffix.equals("n") ? NEW : suffix.equals("t") ? TOP : POPULAR;
        }
        String common = f + "&sort_by=popularity.desc";
        String date = movie ? "primary_release_date" : "first_air_date";
        String q;
        if (theme.equals(POPULAR)) {
            q = common;
        } else if (theme.equals(NEW)) {
            q = movie ? "&vote_count.gte=30&primary_release_date.gte=" + day(-365) + "&primary_release_date.lte=" + day(0) + common
                    : "&vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0) + common;
        } else if (theme.equals(TOP)) {
            q = (movie ? "&vote_count.gte=1000" : "&vote_count.gte=300") + "&sort_by=vote_average.desc" + f;
        } else if (theme.equals(KIDS)) {
            // Shows have a Kids genre; movies don't: Family without horror, thrillers or crime.
            q = movie ? "&with_genres=" + FAMILY + "&without_genres=27,53,80" + (Config.HIDE_ANIMATION ? "," + ANIMATION : "")
                        + "&vote_count.gte=50" + common.replace("&without_genres=" + ANIMATION, "")
                    : "&with_genres=" + TV_KIDS + "&vote_count.gte=10" + common;
        } else if (theme.equals(GEMS)) {
            // Rated highly by the few who saw them.
            q = "&vote_average.gte=7.5" + (movie ? "&vote_count.gte=100&vote_count.lte=1500" : "&vote_count.gte=50&vote_count.lte=600") + common;
        } else if (theme.equals(CLASSICS)) {
            q = "&" + date + ".lte=1989-12-31" + (movie ? "&vote_count.gte=300" : "&vote_count.gte=50") + common;
        } else if (theme.startsWith("d")) {
            int from = Integer.parseInt(theme.substring(1));
            q = "&" + date + ".gte=" + from + "-01-01&" + date + ".lte=" + (from + 9) + "-12-31"
                    + (movie ? "&vote_count.gte=200" : "&vote_count.gte=50") + common;
        } else {
            q = "&with_genres=" + theme.substring(1) + "&vote_count.gte=50" + common;
        }
        return "/discover/" + kind + "?" + q.substring(1) + "&page=" + page;
    }

    /** One page of a row (20 titles at most), all in row `theme`. */
    private List<Pick> page(String kind, String theme, int page) throws Exception {
        JSONObject o = get(path(kind, theme, page));
        JSONArray results = o.getJSONArray("results");
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) add(out, results.getJSONObject(i), kind);
        for (Pick p : out) p.themes.add(theme);
        if (page >= o.optInt("total_pages", 1) || results.length() == 0) exhausted.add(pageKey(kind, theme));
        return out;
    }

    private static String pageKey(String kind, String theme) {
        return (kind == null ? "all" : kind) + "|" + theme;
    }

    /**
     * The row goes on: its next page (movies and shows, on Explore), added at its end. Called as the selection nears
     * the end; does nothing while that row's page is coming or when there's no more.
     */
    void more(String kind, String theme) {
        if (YouTubeFeed.KIND.equals(kind)) {
            if (theme != null) fetchYouTube(theme, false);
            return;
        }
        if (!hasKey(context) || filters.isEmpty() || theme == null) return;
        final String k = pageKey(kind, theme);
        synchronized (pages) {
            if (exhausted.contains(k) || !growing.add(k)) return;
        }
        final long list = listAt;
        quick.execute(() -> {
            try {
                // Titles another row of the tab already shows are skipped there, so a page may add few: up to 5 pages
                // until the row has 8 new ones.
                int added = 0;
                for (int tries = 0; tries < 5 && added < 8 && !exhausted.contains(k); tries++) {
                    int next;
                    synchronized (pages) {
                        next = pages.containsKey(k) ? pages.get(k) + 1 : listed(kind, theme) ? 2 : 1;
                    }
                    List<List<Pick>> found = new ArrayList<>();
                    for (String kd : kind == null ? new String[] {"movie", "tv"} : new String[] {kind}) {
                        if (exhausted.contains(pageKey(kd, theme)) && kind == null) continue;
                        found.add(page(kd, theme, next));
                    }
                    synchronized (pages) {
                        if (list != listAt) return;   // a new list came meanwhile: its own pages start again
                        pages.put(k, next);
                        if (kind == null && exhausted.contains(pageKey("movie", theme)) && exhausted.contains(pageKey("tv", theme))) exhausted.add(k);
                        Map<String, Pick> all = new LinkedHashMap<>();
                        for (Pick p : picks) all.put(key(p), p);
                        if (found.size() == 2) {
                            alternateCopy(all, found.get(0), found.get(1));
                        } else {
                            for (List<Pick> l : found) alternateCopy(all, l, new ArrayList<>());
                        }
                        picks = new ArrayList<>(all.values());
                    }
                    // The row that fetched a title keeps it: claimed now, an earlier row whose own next pages brought
                    // it too (Popular has many of Kids') can't take it at the next rebuild and leave this row short.
                    synchronized (owners) {
                        Map<String, String> own = owners(kind);
                        Map<String, Boolean> have = new HashMap<>();
                        for (List<Pick> l : found) {
                            for (Pick p : l) if (shown(p, kind, have) && own.putIfAbsent(key(p), theme) == null) added++;
                        }
                    }
                    if (found.isEmpty()) break;
                }
                AppLog.w("recommendations: more " + k + ": " + added + " new, " + pagesOf(kind, theme) + " pages"
                        + (exhausted.contains(k) ? ", the end" : ""));
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("recommendations: more " + theme + ": " + e.getMessage());
            } finally {
                growing.remove(k);
            }
        });
    }

    /** As alternate, but a title already shown gets a new set of rows (the home screen may be reading the old one). */
    private static void alternateCopy(Map<String, Pick> out, List<Pick> a, List<Pick> b) {
        for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
            for (Pick p : i < a.size() && i < b.size() ? new Pick[] {a.get(i), b.get(i)} : i < a.size() ? new Pick[] {a.get(i)} : new Pick[] {b.get(i)}) {
                Pick had = out.get(key(p));
                if (had == null) {
                    out.put(key(p), p);
                } else {
                    Set<String> themes = new java.util.LinkedHashSet<>(had.themes);
                    themes.addAll(p.themes);
                    had.themes = themes;
                }
            }
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        // What the list already says (the details, fetched when needed, add the runtime, the service and the trailer).
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date", "");
        p.year = date.length() >= 4 ? date.substring(0, 4) : "";
        p.rating = o.optInt("vote_count") >= 20 ? (float) o.optDouble("vote_average", 0) : 0;
        p.overview = o.optString("overview", "").trim();
        String poster = o.optString("poster_path", "");
        p.poster = poster.equals("null") ? "" : poster;
        List<String> parts = new ArrayList<>();
        if (kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (!p.year.isEmpty()) parts.add(p.year);
        if (p.rating > 0) parts.add(String.format(Ui.locale(), "\u2605 %.1f", p.rating));
        p.meta = String.join(" · ", parts);
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        String own = Ui.language.replaceAll("-.*", "");
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,videos,external_ids&include_image_language=null," + own + ",en"
                + "&include_video_language=" + own + ",en,null");
        p.title = title(p, o);
        String date = o.optString(p.kind.equals("movie") ? "release_date" : "first_air_date", "");
        p.year = date.length() >= 4 ? date.substring(0, 4) : "";
        p.rating = o.optInt("vote_count") >= 20 ? (float) o.optDouble("vote_average", 0) : 0;
        p.overview = o.optString("overview", "").trim();
        String poster = o.optString("poster_path", "");
        p.poster = poster.equals("null") ? "" : poster;
        p.trailer = trailer(o.optJSONObject("videos"));
        JSONObject ids = o.optJSONObject("external_ids");
        String imdb = o.optString("imdb_id", ids == null ? "" : ids.optString("imdb_id", ""));
        p.imdb = imdb.startsWith("tt") ? imdb : "";
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (!p.year.isEmpty()) parts.add(p.year);
        if (p.rating > 0) parts.add(String.format(Ui.locale(), "\u2605 %.1f", p.rating));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        // On none of the TV's services (trending, or a page the details say left them): it opens in Stremio.
        if (p.app == null && stremio()) toStremio(p);
        if (p.app != null) parts.add(opensInStremio(p) ? "Stremio" : SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /** On none of the TV's services: it opens in Stremio. */
    private static void toStremio(Pick p) {
        p.app = "stremio";
        p.pkg = STREMIO;
    }

    private boolean stremio() {
        return HomeApps.launchIntent(context.getPackageManager(), STREMIO) != null;
    }

    /** Opens in Stremio: anything not on Apple TV, and Apple TV titles without a Watchmode key to find their page. */
    private boolean opensInStremio(Pick p) {
        return stremio() && !("appletv".equals(p.app) && linksOn());
    }

    /** What opening the pick opens (after resolveLink): Apple TV on the title's page, else Stremio on it, else the app. */
    HomeApps.Item target(Pick p) {
        if ("appletv".equals(p.app) && p.link != null) return HomeApps.app(p.pkg, SERVICE_NAME.get(p.app), p.link);
        if (stremio()) {
            String link;
            if (!p.imdb.isEmpty()) {
                link = p.kind.equals("tv") ? "stremio:///detail/series/" + p.imdb   // the forms tested on the TV
                        : "stremio:///detail/movie/" + p.imdb + "/" + p.imdb;
            } else {
                try {
                    link = "stremio:///search?search=" + URLEncoder.encode(p.title, "UTF-8").replace("+", "%20");
                } catch (java.io.UnsupportedEncodingException e) {
                    link = null;
                }
            }
            return HomeApps.app(STREMIO, "Stremio", link);
        }
        return HomeApps.app(p.pkg, serviceName(p), p.link);
    }

    /** What Play opens the pick in (the title popup's button names it): Apple TV, Stremio, or the service's app. */
    String playsIn(Pick p) {
        if ("appletv".equals(p.app) && (p.link != null || linksOn())) return SERVICE_NAME.get("appletv");
        if (stremio()) return "Stremio";
        return serviceName(p);
    }

    // MARK: - The title popup

    /** What the title popup shows besides the pick: fetched when it opens (nothing is kept on disk), a few in memory. */
    static final class Info {
        String line = "", cast = "", platforms = "";
        final List<String[]> ratings = new ArrayList<>();   // {source, score}: IMDb, Rotten Tomatoes, Metacritic (OMDb), TMDB
    }

    private final Map<String, Info> infos = new LinkedHashMap<String, Info>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Info> e) { return size() > 12; }
    };
    private final Set<String> fetchingInfo = java.util.Collections.synchronizedSet(new HashSet<>());

    /** The pick's popup details if they're in; else null, and `then` runs on the main thread once they are. */
    Info info(Pick p, Runnable then) {
        final String key = key(p);
        synchronized (infos) {
            Info i = infos.get(key);
            if (i != null) return i;
        }
        if (!fetchingInfo.add(key)) return null;
        quick.execute(() -> {
            Info i = new Info();
            try {
                fetchInfo(p, i);
            } catch (Exception e) {
                AppLog.w("title popup: " + e.getMessage());
            }
            synchronized (infos) { infos.put(key, i); }
            fetchingInfo.remove(key);
            main.post(then);
        });
        return null;
    }

    /** Year · genres · runtime (or seasons), the first names of the cast, the services it streams on, the ratings. */
    private void fetchInfo(Pick p, Info info) throws Exception {
        boolean movie = p.kind.equals("movie");
        JSONObject o = get("/" + p.kind + "/" + p.id + "?language=" + lang() + "&append_to_response=credits,watch/providers,external_ids");
        List<String> parts = new ArrayList<>();
        String date = o.optString(movie ? "release_date" : "first_air_date", "");
        if (date.length() >= 4) parts.add(date.substring(0, 4));
        JSONArray genres = o.optJSONArray("genres");
        for (int i = 0; genres != null && i < Math.min(2, genres.length()); i++) parts.add(genre(genres.getJSONObject(i).optString("name")));
        if (movie) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        info.line = String.join(" · ", parts);
        JSONObject credits = o.optJSONObject("credits");
        JSONArray cast = credits == null ? null : credits.optJSONArray("cast");
        List<String> names = new ArrayList<>();
        for (int i = 0; cast != null && i < cast.length() && names.size() < 4; i++) {
            String name = cast.getJSONObject(i).optString("name");
            if (!name.isEmpty()) names.add(name);
        }
        info.cast = String.join(", ", names);
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject results = wp == null ? null : wp.optJSONObject("results");
        JSONObject here = results == null ? null : results.optJSONObject(REGION);
        JSONArray flat = here == null ? null : here.optJSONArray("flatrate");
        List<String> services = new ArrayList<>();
        for (int i = 0; flat != null && i < flat.length() && services.size() < 4; i++) {
            String name = flat.getJSONObject(i).optString("provider_name");
            if (!name.isEmpty() && !services.contains(name)) services.add(name);
        }
        info.platforms = String.join(", ", services);
        JSONObject ids = o.optJSONObject("external_ids");
        String imdb = o.optString("imdb_id", ids == null ? "" : ids.optString("imdb_id", ""));
        if (!imdb.startsWith("tt") && ids != null) imdb = ids.optString("imdb_id", "");
        omdb(imdb, info);
        if (o.optInt("vote_count") >= 20) {
            info.ratings.add(new String[] {"TMDB", String.format(Ui.locale(), "%.1f", o.optDouble("vote_average", 0))});
        }
    }

    /** IMDb, Rotten Tomatoes and Metacritic scores from OMDb (with omdb_key in config.properties). */
    private static void omdb(String imdb, Info info) {
        String key = Config.OMDB_KEY.trim();
        if (key.isEmpty() || imdb == null || !imdb.startsWith("tt")) return;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL("https://www.omdbapi.com/?i=" + URLEncoder.encode(imdb, "UTF-8")
                    + "&apikey=" + URLEncoder.encode(key, "UTF-8")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            JSONObject o;
            try {
                int code = c.getResponseCode();
                if (code != 200) throw new IOException("OMDb returned HTTP " + code);   // not the URL: it has the key
                o = new JSONObject(read(c.getInputStream()));
            } finally {
                c.disconnect();
            }
            JSONArray list = o.optJSONArray("Ratings");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject r = list.getJSONObject(i);
                String source = r.optString("Source"), value = r.optString("Value");
                if (value.isEmpty() || value.equals("N/A")) continue;
                if (source.equals("Internet Movie Database")) info.ratings.add(new String[] {"IMDb", value.replace("/10", "")});
                else if (source.equals("Rotten Tomatoes")) info.ratings.add(new String[] {"Rotten Tomatoes", value});
                else if (source.equals("Metacritic")) info.ratings.add(new String[] {"Metacritic", value.replace("/100", "")});
            }
            String rating = o.optString("imdbRating", "");
            if (info.ratings.isEmpty() && !rating.isEmpty() && !rating.equals("N/A")) info.ratings.add(new String[] {"IMDb", rating});
            AppLog.w("OMDb " + imdb + ": " + info.ratings.size() + " scores");
        } catch (Exception e) {
            AppLog.w("OMDb: " + e.getClass().getSimpleName());
        }
    }

    /** The pick's big photo on disk (TMDB's w1280 on a 720p UI), downloaded now if missing: blocks. null: none. */
    File bigImage(Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty()) return null;
        try {
            download(p);
            return imageFile(p);
        } catch (IOException e) {
            AppLog.w("popup image: " + e.getMessage());
            return null;
        }
    }

    /** The YouTube id of the title's trailer: an official trailer first, then any trailer, then a teaser. */
    private static String trailer(JSONObject videos) {
        JSONArray list = videos == null ? null : videos.optJSONArray("results");
        String best = null;
        int bestScore = 0;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject v = list.optJSONObject(i);
            if (v == null || !"YouTube".equals(v.optString("site")) || v.optString("key").isEmpty()) continue;
            String type = v.optString("type");
            int score = (type.equals("Trailer") ? 4 : type.equals("Teaser") ? 2 : 0) + (v.optBoolean("official") ? 1 : 0);
            if (score > bestScore) {
                bestScore = score;
                best = v.optString("key");
            }
        }
        return best;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        for (String[] s : SERVICES) {
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private boolean installed(String pkg) {
        if (!HIDDEN.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        stamp = stamp * 31L + (stremio() ? 1 : 0);
        stamp = stamp * 31L + (linksOn() ? 1 : 0);   // with a Watchmode key, Apple TV titles open there (the meta says where)
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm:" + p.kind + p.id + p.app;
    }

    private boolean needsLink(Pick p) {
        return linksOn() && "appletv".equals(p.app) && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || !"appletv".equals(p.app) || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    boolean same = want.equals("max") ? name.equals("max") : name.contains(want);
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    /** Before opening a pick: its link looked up now if it wasn't yet (then `then` runs on the main thread). */
    void resolveLink(Pick p, Runnable then) {
        if (p.detailed && !needsLink(p)) {
            then.run();
            return;
        }
        // Its own thread: behind a whole refresh it would take too long.
        quick.execute(() -> {
            try {
                if (!p.detailed) fetchDetails(p);   // the IMDb id (Stremio) and where it streams
            } catch (Exception e) {
                AppLog.w("recommendation details: " + e.getMessage());
            }
            findLink(p);
            main.post(then);
        });
    }

    /** The focused title's details (genre, runtime, where it opens), if the list didn't bring them: notifies when in. */
    void detail(Pick p) {
        if (p == null || p.detailed || !detailing.add(key(p))) return;
        quick.execute(() -> {
            try {
                fetchDetails(p);
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("recommendation details: " + e.getMessage());
            } finally {
                detailing.remove(key(p));
            }
        });
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    /** A TMDB API call (also used by WatchListener to find a Stremio title's IMDb id). */
    static JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private static String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to MAX_PICKS so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        HttpURLConnection c = (HttpURLConnection) new URL(p.image != null ? p.image : IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /**
     * A card-sized copy of the pick's backdrop (TMDB's w780) on disk, downloaded now if missing: blocks, so call it off
     * the UI thread. null when it has no backdrop or the download failed.
     */
    File cardImage(Pick p) {
        if (p == null || p.backdrop == null || p.backdrop.isEmpty()) return null;
        File f = new File(new File(context.getCacheDir(), "recs-cards"), p.backdrop.replace("/", ""));
        if (f.exists()) return f;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        try {
            String url = p.image != null ? p.image : "https://image.tmdb.org/t/p/w780" + p.backdrop;
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            } finally {
                c.disconnect();
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the image");
            prune(f.getParentFile());
            return f;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            AppLog.w("card image: " + e.getMessage());
            return null;
        }
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 3 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now(null)) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= MAX_PICKS) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - MAX_PICKS; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("year", p.year).put("rating", p.rating).put("overview", p.overview)
                        .put("poster", p.poster).put("trailer", p.trailer == null ? "" : p.trailer).put("themes", new JSONArray(p.themes)).put("imdb", p.imdb));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putString("genres", new JSONObject(genreNames).toString()).putString("filters", filters).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if (k.startsWith("wm:") && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.year = o.optString("year");
            p.rating = (float) o.optDouble("rating", 0);
            p.overview = o.optString("overview");
            p.poster = o.optString("poster");
            p.trailer = o.optString("trailer").isEmpty() ? null : o.optString("trailer");
            JSONArray themes = o.optJSONArray("themes");
            for (int t = 0; themes != null && t < themes.length(); t++) p.themes.add(themes.optString(t));
            p.imdb = o.optString("imdb");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
