/*
 * Endeavour Neo -- a desktop application shell in Swing.
 * Copyright (C) 2026  Jorge Reis
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see <https://www.gnu.org/licenses/>.
 */
package br.com.jorge.reis.endeavourneo.platform;

import br.com.jorge.reis.endeavourneo.domain.market.MarketFile;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.io.IOException;
import java.lang.ref.SoftReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The series on disk: where they are, which exist, and reading one once.
 *
 * <h2>The files stay where they are</h2>
 *
 * <p>Thirty-three megabytes of minutes, outside any repository, read by the
 * first Endeavour as well. Copying them in would mean two series that drift
 * apart, and the one thing every measurement here depends on is that there is
 * exactly one of each. So this points at them; it never moves or writes them.
 * The folder is a setting, found once and remembered.</p>
 *
 * <h2>Read once</h2>
 *
 * <p>A terminal shows the same instrument in several windows, and reading the
 * series again for each one would cost a second and thirty megabytes every time.
 * Held softly rather than firmly: the memory goes back to the machine under
 * pressure, and the next chart pays to read it again instead of the application
 * dying with a heap it refused to let go of.</p>
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not know which series is for searching and which is held back for
 * the test that decides. That distinction governs how a result may be read, and
 * lives with the study, not with the file reader.</p>
 */
public final class SeriesCatalog {

    private static final String KEY = "data.directory";

    /** What the files are called. */
    private static final String SUFFIX = ".bin";

    /**
     * The series a chart opens when nothing else says which.
     *
     * <p>The SOURCE, since 03/09/2026: one series, checked minute by minute
     * against the reference product, with the search-and-test boundary living
     * inside it as segments. It used to be {@code winn-1m}, back when the
     * boundary was two files.</p>
     */
    private static final String DEFAULT = "winfull-1m";

    private static final String RETIRED_KEY = "data.retired";

    /**
     * SeriesCatalog that are on the disk and are not offered.
     *
     * <p>{@code win-1m} is the WIN adjusted by ratio, and it is retired for a
     * reason worth writing down: in it a point was worth R$ 0,20 in 2026 and
     * R$ 0,12 in 2022, so the adjustment inflated the older years by up to 67%.
     * In the raw series a point is worth R$ 0,20 always, and that constant is
     * what every measurement here depends on.</p>
     *
     * <p><b>Hidden, not deleted.</b> The file stays where it is: a series that
     * disappears from the disk is one somebody re-imports a year later without
     * knowing why it went. And hidden from the LISTING only — asked for by
     * name it still opens, so a workspace that remembers it is not silently
     * given a different instrument.</p>
     */
    private static final String RETIRED_BY_DEFAULT = "win-1m";

    private static final Map<String, SoftReference<PriceSeries>> LOADED =
            new ConcurrentHashMap<>();

    /**
     * The answer, once it is known.
     *
     * <p>Held here and not re-derived, for two reasons. Finding it means
     * opening every candidate file to check it is really a series, and {@link
     * #fileOf} asks for the folder on every read. And the search must not write
     * the answer into the reader's settings as a side effect of merely looking
     * — a test that lists the series would then change the machine it ran on.
     * Writing happens only when {@link #setFolder} is called, which is a
     * decision, not a guess.</p>
     */
    private static volatile Path folder;

    private SeriesCatalog() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @return where the series are
     *
     * <p>What the reader chose if they chose; otherwise the first place worth
     * looking that actually holds a series.</p>
     */
    public static Path folder() {
        Path known = folder;

        if (known != null) {
            return known;
        }

        String saved = Settings.settings().get(KEY, "");
        Path answer = null;

        if (!saved.isBlank()) {
            answer = Path.of(saved);
        } else {
            for (Path candidate : candidates()) {
                if (holdsABase(candidate)) {
                    answer = candidate;

                    break;
                }
            }
        }

        if (answer == null) {
            answer = candidates().get(0);
        }

        folder = answer;

        return answer;
    }

    /** @param folder where to look from now on, remembered across launches */
    public static void setFolder(Path folder) {
        Path absolute = folder.toAbsolutePath();

        Settings.settings().put(KEY, absolute.toString());

        SeriesCatalog.folder = absolute;

        LOADED.clear();
    }

    /**
     * Points at a folder without remembering it.
     *
     * <p>Apart from {@link #setFolder}, which decides and persists. This one is
     * for looking: a settings page previewing another folder, and the tests,
     * which must not write into the settings of whoever runs the suite.</p>
     */
    public static void useFolderForTest(Path folder) {
        SeriesCatalog.folder = folder;

        LOADED.clear();
    }

    /**
     * @return the places to look, in order
     *
     * <p><b>The sibling project is deliberately not on this list any more.</b>
     * It was, while the data lived there and this program was reading someone
     * else's folder. Since 03/09/2026 this project has its own {@code data},
     * and leaving the old path as a fallback would mean that a missing folder
     * here silently opens the uncut series over there — eight years instead of
     * six, with the months that have no afternoon. A series that is not found
     * must say so, not be replaced by a different one.</p>
     */
    private static List<Path> candidates() {
        Path here = Path.of(System.getProperty("user.dir", "."));
        List<Path> places = new ArrayList<>();

        places.add(here.resolve("data"));
        places.add(Path.of(System.getProperty("user.home", "."), ".endeavourneo", "data"));

        return places;
    }

    private static boolean holdsABase(Path folder) {
        return !namesIn(folder).isEmpty();
    }

    /** @return the series in the current folder, by name, sorted */
    public static List<String> names() {
        return namesIn(folder());
    }

    private static final String ROLES_KEY = "data.roles";

    /**
     * What each series is FOR, which is the thing that changes a decision.
     *
     * <p>Not derivable from the file: two series of the same instrument, the
     * same scale and the same format can have opposite roles.</p>
     *
     * <p>Since 03/09/2026 there is one SOURCE, {@code winfull-1m}, and the
     * search-and-test boundary lives inside it as segments rather than as two
     * files. The other two are the raw exports it was built from, kept because
     * they are the originals and because the source can be rebuilt from them.
     * Measuring on one of those by accident is the expensive mistake this label
     * exists to prevent: {@code winfut} is missing 94 business days in
     * 2018-2019 and its March-to-August 2020 has no afternoon.</p>
     *
     * <p>A setting, so the roles move as the work does — the merged series is
     * about to be cut into segments, and the roles will follow them.</p>
     */
    private static final String ROLES_BY_DEFAULT =
            "winfull-1m=source,winn-1m=export,winfut-1m=export";

    /** @return the role of each series, by name; a series may have none */
    public static Map<String, String> roles() {
        return stated(ROLES_KEY, ROLES_BY_DEFAULT);
    }

    /**
     * @return a {@code name=value,name=value} setting, read into a map
     *
     * <p>Three things are said about a series rather than derived from it --
     * its role, its market, its scale -- and they are all said the same way.
     * One parser, because three copies of it means the third one is where the
     * trim gets forgotten.</p>
     */
    private static Map<String, String> stated(String key, String fallback) {
        Map<String, String> pairs = new LinkedHashMap<>();

        for (String each : Settings.settings().get(key, fallback).split(",")) {
            int equals = each.indexOf('=');

            if (equals > 0) {
                pairs.put(each.substring(0, equals).trim(), each.substring(equals + 1).trim());
            }
        }

        return pairs;
    }

    /** @return the role of that series, or null when it has none */
    public static String roleOf(String name) {
        return roles().get(name);
    }

    private static final String GROUPS_KEY = "data.groups";

    /**
     * Which market each series belongs to.
     *
     * <p><b>Not derivable from the name,</b> and the first version of this
     * tried: it took the text up to the first dash, which makes {@code winn},
     * {@code winfut} and {@code winfull} three different markets when they are
     * three exports of one. The test caught it. So it is stated, and a series
     * nobody stated falls back to that prefix — which is right for a name like
     * {@code ouro-1m} and harmless for anything else.</p>
     *
     * <p><b>Stated by FAMILY, not by file.</b> The first version of this listed
     * whole names, {@code winfull-1m=win}, and it survived only while every
     * market had exactly one scale. The day {@code winfull-1s} arrived it fell
     * out of the map, derived its own market from its own prefix, and appeared
     * in the tree as a second WIN sitting beside the first — with the same
     * label, so it read as a duplicate rather than as a bug. Keyed by the
     * family, a new scale of a known market needs no new setting at all.</p>
     */
    private static final String GROUPS_BY_DEFAULT =
            "winn=win,winfut=win,winfull=win,win=win,btcusdt=btcusdt";

    /** @return which market each series belongs to, by name */
    public static Map<String, String> groups() {
        return stated(GROUPS_KEY, GROUPS_BY_DEFAULT);
    }

    /**
     * @return the market a series belongs to
     *
     * <p>What lets the tree put the exports of one market together instead of
     * listing five files flat.</p>
     */
    /**
     * @param name a series as it is stored, which is a file name
     * @return how it is NAMED on screen
     *
     * <p>{@code winfull-1m} is read under WINFUT and under "1 minuto", so
     * repeating either is saying it three times. What is left is what actually
     * tells this series from its neighbours -- {@code full} -- and it is read
     * with the market: <b>WINFUT-FULL</b>.</p>
     *
     * <p>A series named after its market and nothing else keeps just the
     * market's name; there is nothing to distinguish it from.</p>
     *
     * <p>Here rather than in the tree because the tree is not the only place a
     * series is named: the chart's own title bar showed the file name until
     * this existed, so the same series read two ways in one window.</p>
     */
    public static String displayOf(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }

        String instrument = groupOf(name);
        String scale = scaleOf(name);
        String rest = name;

        if (!scale.isEmpty() && rest.endsWith("-" + scale)) {
            rest = rest.substring(0, rest.length() - scale.length() - 1);
        }

        if (rest.startsWith(instrument)) {
            rest = rest.substring(instrument.length());
        }

        String market = br.com.jorge.reis.endeavourneo.platform.Messages.market(instrument);

        return rest.isBlank() ? market
                : market + "-" + rest.replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }

    public static String groupOf(String name) {
        if (name == null) {
            return "";
        }

        Map<String, String> stated = groups();
        String byName = stated.get(name);

        if (byName != null) {
            return byName;
        }

        int dash = name.indexOf('-');
        String family = dash > 0 ? name.substring(0, dash) : name;
        String byFamily = stated.get(family);

        return byFamily == null ? family : byFamily;
    }

    private static final String SCALES_KEY = "data.scales";

    /**
     * The pseudo-scale of the tick sessions, finer than any bar.
     *
     * <p>Not a file on disk and not a suffix on any name. It is here so ticks
     * can be SORTED with the scales instead of listed beside them: under an
     * instrument the ticks are the finest scale that instrument has, not a
     * different kind of thing.</p>
     */
    public static final String TICKS = "ticks";

    /**
     * The scale a series is stored at, as a code: {@code 1m}, {@code 1s}.
     *
     * <p>Read from the name, because it genuinely is in there -- and the FIRST
     * such part, never the last. {@code btcusdt-1m-1y} is a year of minutes, so
     * reading the last part would file it under a scale of "one year", which is
     * a recorte and not a scale at all.</p>
     *
     * <p>Stated in the settings when a name does not carry it, the same way the
     * market is. Empty when neither says, and empty is honest: such a series
     * hangs straight off its instrument, where inventing a heading for it would
     * put a word in the tree that nothing on disk agrees with.</p>
     */
    public static String scaleOf(String name) {
        if (name == null) {
            return "";
        }

        String stated = stated(SCALES_KEY, "").get(name);

        if (stated != null) {
            return stated;
        }

        for (String part : name.split("-")) {
            if (isScale(part)) {
                return part;
            }
        }

        return "";
    }

    /** @return whether that is a count of seconds, minutes, hours or days */
    private static boolean isScale(String part) {
        if (part.length() < 2 || "smhd".indexOf(part.charAt(part.length() - 1)) < 0) {
            return false;
        }

        for (int i = 0; i < part.length() - 1; i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return how many seconds one bar of that scale spans
     *
     * <p>{@link #TICKS} is zero, since a tick spans no time at all, and a code
     * that cannot be read is -1 -- which keeps it out of the ordering rather
     * than letting it claim to be finer than a tick.</p>
     */
    public static long secondsOf(String scale) {
        if (TICKS.equals(scale)) {
            return 0;
        }

        if (scale == null || !isScale(scale)) {
            return -1;
        }

        long count = Long.parseLong(scale.substring(0, scale.length() - 1));

        return switch (scale.charAt(scale.length() - 1)) {
            case 's' -> count;
            case 'm' -> count * 60;
            case 'h' -> count * 3_600;
            default -> count * 86_400;
        };
    }

    /**
     * @return coarsest first, ticks last, and the unreadable after even those
     *
     * <p>Reading down the list is zooming in, which is the order the scales get
     * spoken in and the only one where the tree does not have to be scanned to
     * find the coarse one.</p>
     */
    public static Comparator<String> coarsestFirst() {
        return Comparator.comparingLong(scale -> {
            long seconds = secondsOf(scale);

            return seconds < 0 ? Long.MAX_VALUE : -seconds;
        });
    }

    /** @return the names not offered, which the reader may change */
    public static Set<String> retired() {
        String saved = Settings.settings().get(RETIRED_KEY, RETIRED_BY_DEFAULT);
        Set<String> names = new LinkedHashSet<>();

        for (String each : saved.split(",")) {
            String name = each.trim();

            if (!name.isEmpty()) {
                names.add(name);
            }
        }

        return names;
    }

    /** @param names the series to stop offering; the files are untouched */
    public static void setRetired(Set<String> names) {
        Settings.settings().put(RETIRED_KEY, String.join(",", names));
    }

    /**
     * @return the series in that folder, by name, sorted
     *
     * <p>Walks, because the folders ARE the tree: instrument, then scale, then
     * the files. Three levels is the whole of it, and stopping there keeps a
     * folder of raw exports underneath from being read as series.</p>
     *
     * <p>A file counts only when it sits where {@link #fileOf} would put it.
     * That is what makes the listing and the opening answer the same question,
     * and it is what lets the instrument and the scale be read off the path at
     * all: a file somewhere else would claim a market it is not filed under.</p>
     */
    public static List<String> namesIn(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        try (Stream<Path> files = Files.walk(folder, 3)) {
            return files
                    .filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .filter(MarketFile::isSeries)
                    .map(file -> {
                        String name = file.getFileName().toString();

                        return name.substring(0, name.length() - SUFFIX.length());
                    })
                    .filter(name -> !retired().contains(name))
                    // Against the folder being LISTED, which is not always the
                    // current one -- a settings page may be previewing another.
                    //
                    // The first version answered that by pointing the catalog
                    // at it for the length of the walk and putting it back
                    // afterwards. That is a race, and it cost real data: the
                    // interface thread listing series at the same moment read
                    // the field, was interrupted, and restored a folder that
                    // had since been changed. A test then wrote a
                    // twenty-four-byte header over ninety megabytes of exported
                    // ticks -- because it asked where a file goes and was told
                    // the wrong place.
                    //
                    // So nothing global moves. The path is built from the
                    // folder in hand.
                    .filter(name -> Files.isRegularFile(folder.resolve(relativeTo(name))))
                    .distinct()
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /** @return the name a chart opens with when nothing else says which */
    public static String defaultName() {
        List<String> names = names();

        if (names.contains(DEFAULT)) {
            return DEFAULT;
        }

        return names.isEmpty() ? DEFAULT : names.get(0);
    }

    /** @return whether a series by that name is on disk and readable */
    public static boolean has(String name) {
        return MarketFile.isSeries(fileOf(name));
    }

    /**
     * Where a series lives: {@code <data>/win/1m/winfull-1m.bin}.
     *
     * <p>The folders are the tree the reader sees, and for the same reason:
     * what gets picked is always "this market, at this resolution". It also
     * turns the market and the scale into FACTS of where a file is rather than
     * guesses about what it is called.</p>
     *
     * <p>A series whose name says no scale sits straight under its instrument,
     * exactly as it hangs straight off the instrument in the tree.</p>
     *
     * <p>The name still carries both, redundantly and on purpose: a file that
     * is moved, copied or mailed still says what it is. Same reason the date
     * stays in a tick file's name.</p>
     *
     * <p><b>Computed, never searched.</b></p>
     */
    public static Path fileOf(String name) {
        return folder().resolve(relativeTo(name));
    }

    /**
     * @return the instrument, the scale and the file name, below the data folder
     *
     * <p>Separate from {@link #fileOf} so a folder that is not the current one
     * can be asked about without pointing the whole program at it. That used to
     * be how it was done, and it was a race that destroyed data.</p>
     */
    private static Path relativeTo(String name) {
        String scale = scaleOf(name);
        Path under = Path.of(groupOf(name));

        return (scale.isEmpty() ? under : under.resolve(scale)).resolve(name + SUFFIX);
    }

    /**
     * @return where that instrument's tick sessions live
     *
     * <p>Beside its series rather than in one pile at the top, so everything
     * about a market is under the market. Each source gets a folder of its own
     * inside; see {@link br.com.jorge.reis.endeavourneo.domain.market.TickSource}.</p>
     */
    public static Path ticksOf(String instrument) {
        return folder().resolve(instrument).resolve("ticks");
    }

    /**
     * @param name a series's name, as {@link #names()} gives it
     * @return the bars, or empty if there is no such series
     * @throws IOException if there is one and it cannot be read
     *
     * <p>Empty and an exception mean different things on purpose. No such series
     * is an ordinary answer — the reader asked for a name that is not there.
     * A series that exists and will not read is a fault worth showing, and
     * swallowing it would put an empty chart on screen with no reason given.</p>
     */
    public static Optional<PriceSeries> open(String name) throws IOException {
        SoftReference<PriceSeries> held = LOADED.get(name);
        PriceSeries cached = held == null ? null : held.get();

        if (cached != null) {
            return Optional.of(cached);
        }

        Path file = fileOf(name);

        if (!MarketFile.isSeries(file)) {
            return Optional.empty();
        }

        PriceSeries series = MarketFile.read(file);

        LOADED.put(name, new SoftReference<>(series));

        return Optional.of(series);
    }

    /**
     * @param name a series's name, as {@link #names()} gives it
     * @param bars how many of the MOST RECENT bars to read
     * @return the last {@code bars} bars, or empty if there is no such series
     * @throws IOException if there is one and it cannot be read
     *
     * <p><b>A window, anchored to the right.</b> What a reader opens a chart to
     * see is the recent end of it; six years of one-minute bars is 39 MB read to
     * draw a screen that shows a month. Every terminal worth the name does this
     * -- the reference product loads 10.000 by default and 100.000 at its top
     * licence, MetaTrader 4 stops the chart at 65.000 while keeping 512.000 on
     * disk, and NinjaTrader loads five days of minutes.</p>
     *
     * <p><b>Not cached.</b> The whole-file {@link #open(String)} is, because
     * everything asking for it wants the same thing; a window is asked for with
     * a size, and holding one window would hand the next caller somebody else's.
     * Reading 100.000 bars is 4,8 MB and a seek.</p>
     */
    public static Optional<PriceSeries> open(String name, int bars) throws IOException {
        if (bars <= 0) {
            return open(name);
        }

        SoftReference<PriceSeries> held = LOADED.get(name);
        PriceSeries whole = held == null ? null : held.get();

        if (whole != null && whole.size() <= bars) {
            // The file is already in memory and is no bigger than the window.
            // Reading it again to get fewer bars than it holds would be work for
            // nothing.
            return Optional.of(whole);
        }

        Path file = fileOf(name);

        if (!MarketFile.isSeries(file)) {
            return Optional.empty();
        }

        int total = MarketFile.countIn(file);

        return Optional.of(MarketFile.read(file, Math.max(0, total - bars), bars));
    }

    /** Drops what is held in memory. The files are untouched. */
    public static void forget() {
        LOADED.clear();
    }
}
