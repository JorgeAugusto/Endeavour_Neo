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
package br.com.jorge.reis.endeavourneo.ui.chart;

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the named layouts live, and how they survive a restart.
 *
 * <p>Stored as text in {@link Preferences}: one line per indicator, fields
 * separated by pipes. Java serialisation would be shorter to write and would
 * break the moment a class is renamed — a saved layout has to outlive
 * refactoring, because it is the user's work and not ours.</p>
 *
 * <pre>
 *   overlay.ema|17,55,200|true
 *   overlay.ema|9|false
 * </pre>
 *
 * <p><b>Nothing is validated on the way in.</b> A line naming an indicator that
 * no longer exists is skipped when the layout is built, not rejected when it is
 * read: a layout of six indicators must not fail to load because one of them was
 * removed in a later version.</p>
 */
public final class ChartLayouts {

    private static final Settings PREFS = Settings.settings();

    private static final String COUNT = "count";

    /** Preferences refuses a value longer than this, so a layout has a ceiling. */
    private static final int MAX_ENTRIES = 40;

    private ChartLayouts() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return every saved layout, with a default one when none exist yet */
    public static List<ChartLayout> all() {
        int count = PREFS.getInt(COUNT, 0);

        if (count <= 0) {
            return List.of(defaultLayout());
        }

        List<ChartLayout> layouts = new ArrayList<>(count);

        for (int i = 0; i < count; i++) {
            String name = PREFS.get("layout." + i + ".name", null);

            if (name != null) {
                layouts.add(new ChartLayout(name,
                        parse(PREFS.get("layout." + i + ".entries", "")),
                        parsePanes(PREFS.get("layout." + i + ".panes", ""))));
            }
        }

        return layouts.isEmpty() ? List.of(defaultLayout()) : layouts;
    }

    /**
     * Replaces the whole set.
     *
     * <p>All of them at once rather than one at a time: the tabs are reordered,
     * removed and renamed together, and writing entry by entry would leave the
     * stored count disagreeing with the stored names if anything failed halfway.</p>
     */
    public static void save(List<ChartLayout> layouts) {
        // ONE WRITE, and the javadoc above already promised it: "writing entry
        // by entry would leave the stored count disagreeing with the stored
        // names if anything failed halfway". Every put wrote the whole settings
        // file -- the class says so, "written on every change rather than at
        // exit" -- so five layouts were sixteen rewrites, on the interface
        // thread, and the storage really did pass through every intermediate
        // state the promise says it avoids.
        //
        // This is called from capture(), which hangs off the two commonest
        // events a chart has: an indicator changing and a pane being dragged.
        PREFS.hold(() -> {
            int previous = PREFS.getInt(COUNT, 0);

            for (int i = 0; i < layouts.size(); i++) {
                ChartLayout layout = layouts.get(i);

                PREFS.put("layout." + i + ".name", layout.name());
                PREFS.put("layout." + i + ".entries", format(layout.entries()));

                // A key of its own rather than more fields on the entry lines,
                // so a reader of the file can tell the two kinds apart at a
                // glance.
                PREFS.put("layout." + i + ".panes", formatPanes(layout.panes()));
            }

            // Anything past the new end is removed, or a shrinking list would
            // leave the old tail readable and it would come back on the next
            // launch.
            for (int i = layouts.size(); i < previous; i++) {
                PREFS.remove("layout." + i + ".name");
                PREFS.remove("layout." + i + ".entries");
                PREFS.remove("layout." + i + ".panes");
            }

            PREFS.putInt(COUNT, layouts.size());
        });
    }

    /** @return which layout each chart was last showing */
    public static String selectedFor(String chartKey) {
        return PREFS.get("selected." + chartKey, null);
    }

    public static void remember(String chartKey, String layoutName) {
        PREFS.put("selected." + chartKey, layoutName);
    }

    /**
     * @param existing the names already taken
     * @param name the one being copied
     * @return "Cópia de X", or "Cópia de Cópia de X" when that is taken too
     *
     * <p>The reference product does exactly this, and the resulting names are
     * silly. They are also honest: they record that the layout came from another
     * one, which is what the reader needs to know when a chart looks almost like
     * the one next to it.</p>
     */
    public static String copyName(List<String> existing, String name) {
        return copyName(existing, name, of -> Messages.get("layout.copyOf", of));
    }

    /**
     * @param phrase how "a copy of X" is said; <b>may return the same thing
     *               every time, and this still has to end</b>
     *
     * <p>Package-visible so the failing condition can be ARRANGED. It cannot be
     * reached through the bundle from a test -- the entry is on the classpath
     * and every locale falls back to it -- and it is a real condition: the old
     * loop's only exit was the text CHANGING, and the text changes only because
     * the bundle entry carries a {@code {0}}. Take that entry away and {@code
     * Messages.get} answers {@code !layout.copyOf!}; {@code MessageFormat} over
     * a text with no placeholder gives back the same text; the candidate stops
     * moving and the loop never ends -- on the interface thread, from the
     * duplicate button.</p>
     *
     * <p>A missing translation freezing the whole application is the exact case
     * {@code Messages} exists to make harmless: "one forgotten string in a
     * translation should not stop the application from opening".</p>
     *
     * <p>So the phrase is asked ONCE and the collisions are numbered. That also
     * happens to read better: "copy of copy of copy of Volume" is not a
     * name.</p>
     */
    static String copyName(List<String> existing, String name,
            java.util.function.UnaryOperator<String> phrase) {
        String base = phrase.apply(name);
        String candidate = base;

        for (int n = 2; existing.contains(candidate); n++) {
            candidate = base + " (" + n + ")";
        }

        return candidate;
    }

    /**
     * @return what a reader who has never saved a layout is offered
     *
     * <p>Package-private rather than private so a test can build it. It reached
     * production naming a key nothing registers, and the only way that is caught
     * is by asking it for its indicators and counting them.</p>
     */
    static ChartLayout defaultLayout() {
        // The key has to be the one MovingAverage.nameKey() answers. It said
        // "overlay.ema" for as long as the class was called that, and the rename
        // moved the class without moving this line -- so the catalogue looked up
        // a key nobody registers, Entry.build handed back null, ChartLayout.build
        // dropped the null in silence (which it does on purpose, to tolerate a
        // layout written by a later version), and applying the default layout
        // produced a chart with no indicator at all.
        // THREE entries, not one entry with three numbers. An Entry's parameter
        // list is that one indicator's settings -- period, shift, kind -- so
        // List.of(17, 55, 200) asked for a single average of period 17, shifted
        // 55 bars sideways, of kind 200. It was never noticed because the key
        // beside it was dead and the whole entry was dropped before anything
        // tried to build it. MainWindow says the intended shape out loud:
        // "Three averages, three indicators. One indicator drawing three lines
        // meant they shared one set of settings."
        return new ChartLayout(Messages.get("layout.default"),
                List.of(new ChartLayout.Entry("overlay.movingAverage", List.of(17), true),
                        new ChartLayout.Entry("overlay.movingAverage", List.of(55), true),
                        new ChartLayout.Entry("overlay.movingAverage", List.of(200), true)));
    }

    // --------------------------------------------------------------- the text

    /**
     * One line per INDICATOR, each saying which pane it belongs to.
     *
     * <p>A line per pane would need a separator inside a separator to hold
     * three indicators, and a format with two levels of punctuation is a
     * format nobody can read in the file or fix by hand. The height and the
     * minimised flag repeat on every line of a pane; they are read from the
     * first and the repetition costs nothing.</p>
     */
    static String formatPanes(List<ChartLayout.Pane> panes) {
        StringBuilder text = new StringBuilder();
        int written = 0;

        for (int p = 0; p < panes.size(); p++) {
            ChartLayout.Pane pane = panes.get(p);

            for (ChartLayout.Entry entry : pane.entries()) {
                if (written >= MAX_ENTRIES) {
                    return text.toString();
                }

                if (written > 0) {
                    text.append('\n');
                }

                written++;

                text.append(p).append('|').append(entry.kindKey()).append('|');

                for (int n = 0; n < entry.parameters().size(); n++) {
                    if (n > 0) {
                        text.append(',');
                    }

                    text.append(entry.parameters().get(n));
                }

                text.append('|').append(pane.height())
                        .append('|').append(pane.minimised())
                        .append('|').append(entry.appearance());
            }
        }

        return text.toString();
    }

    static List<ChartLayout.Pane> parsePanes(String text) {
        List<ChartLayout.Pane> panes = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return panes;
        }

        List<ChartLayout.Entry> gathering = new ArrayList<>();
        int belongsTo = -1;
        int height = 0;
        boolean minimised = false;

        for (String line : text.split("\n")) {
            String[] fields = line.split("\\|");

            if (fields.length < 5) {
                // Skipped in silence, like a malformed overlay line: one bad
                // line must not cost the other five.
                continue;
            }

            int mine = number(fields[0], -1);

            if (mine < 0) {
                continue;
            }

            if (mine != belongsTo && !gathering.isEmpty()) {
                panes.add(new ChartLayout.Pane(List.copyOf(gathering), height, minimised));
                gathering.clear();
            }

            belongsTo = mine;
            height = number(fields[3], 0);
            minimised = Boolean.parseBoolean(fields[4].trim());

            gathering.add(new ChartLayout.Entry(fields[1].trim(), numbers(fields[2]), true,
                    fields.length > 5 ? fields[5] : ""));
        }

        if (!gathering.isEmpty()) {
            panes.add(new ChartLayout.Pane(List.copyOf(gathering), height, minimised));
        }

        return panes;
    }

    private static List<Integer> numbers(String text) {
        List<Integer> found = new ArrayList<>();

        for (String piece : text.split(",")) {
            try {
                found.add(Integer.valueOf(piece.trim()));
            } catch (NumberFormatException e) {
                // One unreadable number makes the whole list meaningless: the
                // indicator would be built with the wrong shape rather than
                // with its defaults.
                return List.of();
            }
        }

        return List.copyOf(found);
    }

    private static int number(String text, int fallback) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    static String format(List<ChartLayout.Entry> entries) {
        StringBuilder text = new StringBuilder();

        for (int i = 0; i < entries.size() && i < MAX_ENTRIES; i++) {
            ChartLayout.Entry entry = entries.get(i);

            if (i > 0) {
                text.append('\n');
            }

            text.append(entry.kindKey()).append('|');

            for (int p = 0; p < entry.parameters().size(); p++) {
                if (p > 0) {
                    text.append(',');
                }

                text.append(entry.parameters().get(p));
            }

            text.append('|').append(entry.visible());

            // A fourth field, and older lines have three. Appending rather than
            // reshaping is what lets a layout written before appearance existed
            // still load: the parser asks for at least three and reads a fourth
            // if it is there.
            if (!entry.appearance().isEmpty()) {
                text.append('|').append(entry.appearance());
            }
        }

        return text.toString();
    }

    static List<ChartLayout.Entry> parse(String text) {
        List<ChartLayout.Entry> entries = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return entries;
        }

        for (String line : text.split("\n")) {
            String[] fields = line.split("\\|");

            if (fields.length < 3) {
                // A malformed line is skipped in silence. It came from a file the
                // user did not write by hand, and refusing the whole layout over
                // one bad line would lose the other five.
                continue;
            }

            List<Integer> parameters = new ArrayList<>();

            for (String piece : fields[1].split(",")) {
                try {
                    parameters.add(Integer.valueOf(piece.trim()));
                } catch (NumberFormatException e) {
                    parameters.clear();

                    break;
                }
            }

            if (!parameters.isEmpty()) {
                entries.add(new ChartLayout.Entry(fields[0], List.copyOf(parameters),
                        Boolean.parseBoolean(fields[2]),
                        fields.length > 3 ? fields[3] : ""));
            }
        }

        return entries;
    }
}
