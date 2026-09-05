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
                        panesOf(i)));
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
        int previous = PREFS.getInt(COUNT, 0);

        for (int i = 0; i < layouts.size(); i++) {
            ChartLayout layout = layouts.get(i);

            PREFS.put("layout." + i + ".name", layout.name());
            PREFS.put("layout." + i + ".entries", format(layout.entries()));

            // A key of its own rather than more fields on the entry lines. A
            // layout written before panes existed then loads untouched, and a
            // reader of the file can tell the two kinds apart at a glance.
            //
            // And a SECOND key now that a pane holds several indicators: the
            // old one had a line per pane and no room for a second indicator
            // in it. Writing both would mean two truths about the same pane,
            // so the old key is cleared as the new one is written.
            PREFS.put("layout." + i + ".panes2", formatPanes(layout.panes()));
            PREFS.remove("layout." + i + ".panes");
        }

        // Anything past the new end is removed, or a shrinking list would leave
        // the old tail readable and it would come back on the next launch.
        for (int i = layouts.size(); i < previous; i++) {
            PREFS.remove("layout." + i + ".name");
            PREFS.remove("layout." + i + ".entries");
            PREFS.remove("layout." + i + ".panes");
            PREFS.remove("layout." + i + ".panes2");
        }

        PREFS.putInt(COUNT, layouts.size());
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
        String candidate = Messages.get("layout.copyOf", name);

        while (existing.contains(candidate)) {
            candidate = Messages.get("layout.copyOf", candidate);
        }

        return candidate;
    }

    private static ChartLayout defaultLayout() {
        return new ChartLayout(Messages.get("layout.default"),
                List.of(new ChartLayout.Entry("overlay.ema", List.of(17, 55, 200), true)));
    }

    // --------------------------------------------------------------- the text

    /**
     * @param i which layout, by its slot
     * @return its panes, from whichever key holds them
     *
     * <p>The new key first, then the old one. A workspace written before a
     * pane could hold more than one indicator still opens, and the first save
     * afterwards moves it across.</p>
     */
    private static List<ChartLayout.Pane> panesOf(int i) {
        String written = PREFS.get("layout." + i + ".panes2", "");

        return written.isBlank()
                ? parseOldPanes(PREFS.get("layout." + i + ".panes", ""))
                : parsePanes(written);
    }

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

    /** The shape written before a pane could hold more than one indicator. */
    static List<ChartLayout.Pane> parseOldPanes(String text) {
        List<ChartLayout.Pane> panes = new ArrayList<>();

        if (text == null || text.isBlank()) {
            return panes;
        }

        for (String line : text.split("\n")) {
            String[] fields = line.split("\\|");

            if (fields.length < 4) {
                continue;
            }

            panes.add(new ChartLayout.Pane(fields[0].trim(), numbers(fields[1]),
                    fields.length > 4 ? fields[4] : "",
                    number(fields[2], 0), Boolean.parseBoolean(fields[3].trim())));
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
