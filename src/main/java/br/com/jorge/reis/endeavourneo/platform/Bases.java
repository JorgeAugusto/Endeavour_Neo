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
import java.util.List;
import java.util.Map;
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
     * <p>The same default the first Endeavour uses, so a window opened in
     * either program shows the same prices.</p>
     */
    private static final String DEFAULT = "winn-1m";

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
     * <p>Package-visible for the tests, which must not write into the settings
     * of whoever runs the suite.</p>
     */
    static void useFolder(Path folder) {
        Bases.folder = folder;

        LOADED.clear();
    }

    /**
     * @return the places to look, in order
     *
     * <p>The sibling project is on the list because that is where the files
     * are: the first Endeavour wrote them and still reads them. Naming it here
     * is not a dependency on that project, it is a reasonable first guess that
     * the reader can change.</p>
     */
    private static List<Path> candidates() {
        Path here = Path.of(System.getProperty("user.dir", "."));
        List<Path> places = new ArrayList<>();

        places.add(here.resolve("data"));

        Path parent = here.getParent();

        if (parent != null) {
            places.add(parent.resolve("endeavour").resolve("data"));
        }

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
