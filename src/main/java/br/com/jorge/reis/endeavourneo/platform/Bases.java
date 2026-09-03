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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * The bases on disk: where they are, which exist, and reading one once.
 *
 * <h2>The files stay where they are</h2>
 *
 * <p>Thirty-three megabytes of minutes, outside any repository, read by the
 * first Endeavour as well. Copying them in would mean two bases that drift
 * apart, and the one thing every measurement here depends on is that there is
 * exactly one of each. So this points at them; it never moves or writes them.
 * The folder is a setting, found once and remembered.</p>
 *
 * <h2>Read once</h2>
 *
 * <p>A terminal shows the same instrument in several windows, and reading the
 * base again for each one would cost a second and thirty megabytes every time.
 * Held softly rather than firmly: the memory goes back to the machine under
 * pressure, and the next chart pays to read it again instead of the application
 * dying with a heap it refused to let go of.</p>
 *
 * <h2>What this deliberately does not do</h2>
 *
 * <p>It does not know which base is for searching and which is held back for
 * the test that decides. That distinction governs how a result may be read, and
 * lives with the study, not with the file reader.</p>
 */
public final class Bases {

    private static final String KEY = "data.directory";

    /** What the files are called. */
    private static final String SUFFIX = ".bin";

    /**
     * The base a chart opens when nothing else says which.
     *
     * <p>The SOURCE, since 03/09/2026: one series, checked minute by minute
     * against the reference product, with the search-and-test boundary living
     * inside it as segments. It used to be {@code winn-1m}, back when the
     * boundary was two files.</p>
     */
    private static final String DEFAULT = "winfull-1m";

    private static final String RETIRED_KEY = "data.retired";

    /**
     * Bases that are on the disk and are not offered.
     *
     * <p>{@code win-1m} is the WIN adjusted by ratio, and it is retired for a
     * reason worth writing down: in it a point was worth R$ 0,20 in 2026 and
     * R$ 0,12 in 2022, so the adjustment inflated the older years by up to 67%.
     * In the raw series a point is worth R$ 0,20 always, and that constant is
     * what every measurement here depends on.</p>
     *
     * <p><b>Hidden, not deleted.</b> The file stays where it is: a base that
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
     * opening every candidate file to check it is really a base, and {@link
     * #fileOf} asks for the folder on every read. And the search must not write
     * the answer into the reader's settings as a side effect of merely looking
     * — a test that lists the bases would then change the machine it ran on.
     * Writing happens only when {@link #setFolder} is called, which is a
     * decision, not a guess.</p>
     */
    private static volatile Path folder;

    private Bases() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @return where the bases are
     *
     * <p>What the reader chose if they chose; otherwise the first place worth
     * looking that actually holds a base.</p>
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

        Bases.folder = absolute;

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
        Bases.folder = folder;

        LOADED.clear();
    }

    /**
     * @return the places to look, in order
     *
     * <p><b>The sibling project is deliberately not on this list any more.</b>
     * It was, while the data lived there and this program was reading someone
     * else's folder. Since 03/09/2026 this project has its own {@code data},
     * and leaving the old path as a fallback would mean that a missing folder
     * here silently opens the uncut base over there — eight years instead of
     * six, with the months that have no afternoon. A base that is not found
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

    /** @return the bases in the current folder, by name, sorted */
    public static List<String> names() {
        return namesIn(folder());
    }

    private static final String ROLES_KEY = "data.roles";

    /**
     * What each base is FOR, which is the thing that changes a decision.
     *
     * <p>Not derivable from the file: two bases of the same instrument, the
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
     * <p>A setting, so the roles move as the work does — the merged base is
     * about to be cut into segments, and the roles will follow them.</p>
     */
    private static final String ROLES_BY_DEFAULT =
            "winfull-1m=source,winn-1m=export,winfut-1m=export";

    /** @return the role of each base, by name; a base may have none */
    public static Map<String, String> roles() {
        Map<String, String> roles = new LinkedHashMap<>();

        for (String each : Settings.settings().get(ROLES_KEY, ROLES_BY_DEFAULT).split(",")) {
            int equals = each.indexOf('=');

            if (equals > 0) {
                roles.put(each.substring(0, equals).trim(), each.substring(equals + 1).trim());
            }
        }

        return roles;
    }

    /** @return the role of that base, or null when it has none */
    public static String roleOf(String name) {
        return roles().get(name);
    }

    private static final String GROUPS_KEY = "data.groups";

    /**
     * Which market each base belongs to.
     *
     * <p><b>Not derivable from the name,</b> and the first version of this
     * tried: it took the text up to the first dash, which makes {@code winn},
     * {@code winfut} and {@code winfull} three different markets when they are
     * three exports of one. The test caught it. So it is stated, and a base
     * nobody stated falls back to that prefix — which is right for a name like
     * {@code ouro-1m} and harmless for anything else.</p>
     */
    private static final String GROUPS_BY_DEFAULT =
            "winn-1m=win,winfut-1m=win,winfull-1m=win,win-1m=win,"
                    + "btcusdt-1m=btcusdt,btcusdt-1m-1y=btcusdt";

    /** @return which market each base belongs to, by name */
    public static Map<String, String> groups() {
        Map<String, String> groups = new LinkedHashMap<>();

        for (String each : Settings.settings().get(GROUPS_KEY, GROUPS_BY_DEFAULT).split(",")) {
            int equals = each.indexOf('=');

            if (equals > 0) {
                groups.put(each.substring(0, equals).trim(), each.substring(equals + 1).trim());
            }
        }

        return groups;
    }

    /**
     * @return the market a base belongs to
     *
     * <p>What lets the tree put the exports of one market together instead of
     * listing five files flat.</p>
     */
    public static String groupOf(String name) {
        if (name == null) {
            return "";
        }

        String stated = groups().get(name);

        if (stated != null) {
            return stated;
        }

        int dash = name.indexOf('-');

        return dash > 0 ? name.substring(0, dash) : name;
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

    /** @param names the bases to stop offering; the files are untouched */
    public static void setRetired(Set<String> names) {
        Settings.settings().put(RETIRED_KEY, String.join(",", names));
    }

    /** @return the bases in that folder, by name, sorted */
    public static List<String> namesIn(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }

        try (Stream<Path> files = Files.list(folder)) {
            return files
                    .filter(file -> file.getFileName().toString().endsWith(SUFFIX))
                    .filter(MarketFile::isSeries)
                    .map(file -> {
                        String name = file.getFileName().toString();

                        return name.substring(0, name.length() - SUFFIX.length());
                    })
                    .filter(name -> !retired().contains(name))
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

    /** @return whether a base by that name is on disk and readable */
    public static boolean has(String name) {
        return MarketFile.isSeries(fileOf(name));
    }

    public static Path fileOf(String name) {
        return folder().resolve(name + SUFFIX);
    }

    /**
     * @param name a base's name, as {@link #names()} gives it
     * @return the bars, or empty if there is no such base
     * @throws IOException if there is one and it cannot be read
     *
     * <p>Empty and an exception mean different things on purpose. No such base
     * is an ordinary answer — the reader asked for a name that is not there.
     * A base that exists and will not read is a fault worth showing, and
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

    /** Drops what is held in memory. The files are untouched. */
    public static void forget() {
        LOADED.clear();
    }
}
