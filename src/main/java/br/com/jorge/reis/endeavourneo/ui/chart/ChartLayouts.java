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

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

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

    private static final Preferences PREFS = Preferences.userRoot()
            .node("br/com/jorge/reis/endeavourneo/layouts");

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
                layouts.add(new ChartLayout(name, parse(PREFS.get("layout." + i + ".entries", ""))));
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
        }

        // Anything past the new end is removed, or a shrinking list would leave
        // the old tail readable and it would come back on the next launch.
        for (int i = layouts.size(); i < previous; i++) {
            PREFS.remove("layout." + i + ".name");
            PREFS.remove("layout." + i + ".entries");
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
                entries.add(new ChartLayout.Entry(fields[0],
                        List.copyOf(parameters), Boolean.parseBoolean(fields[2])));
            }
        }

        return entries;
    }
}
