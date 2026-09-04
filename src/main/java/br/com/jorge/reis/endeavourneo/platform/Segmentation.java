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

import br.com.jorge.reis.endeavourneo.domain.market.Segment;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * How a series is divided, and where that division is kept.
 *
 * <h2>A selector, not a guard</h2>
 *
 * <p>Nothing here refuses anything. A segment says what a stretch of the series
 * is FOR — searching, testing, whatever the reader decides — and the value of
 * saying it is that the reader can then choose deliberately. Machinery that
 * blocked a chart or a run would be pretending to enforce a discipline that
 * lives in the reader's head, and pretending is worse than not enforcing: it
 * invites trusting a guard that has holes.</p>
 *
 * <h2>Kept per series, one key per field</h2>
 *
 * <pre>
 * segments.winfull-1m.0.name = busca
 * segments.winfull-1m.0.from = 2020-09-01
 * segments.winfull-1m.0.to   = 2024-12-31
 * segments.winfull-1m.1.name = teste
 * segments.winfull-1m.1.from = 2025-01-01
 * </pre>
 *
 * <p>One key per field rather than one packed line, so a name may contain
 * anything at all. A packed format would need escaping, and an escape that is
 * forgotten turns a segment called "busca, 2 anos" into two broken ones.</p>
 *
 * <p>An absent {@code to} means "onwards": the segment grows as the series
 * does. That is what the last one usually wants — the newest data belongs to
 * whatever is being held back, and a fixed end date would quietly stop
 * including it.</p>
 */
public final class Segmentation {

    private static final String PREFIX = "segments.";

    /**
     * Where the segments are written.
     *
     * <p>The workspace, unless a test says otherwise. Without the seam the only
     * way to test this is to write into the file of whoever runs the suite and
     * hope the cleanup runs -- and a test that fails halfway leaves its
     * scaffolding in their settings.</p>
     */
    private static Settings store;

    private Segmentation() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    private static Settings store() {
        return store == null ? Settings.workspace() : store;
    }

    /** Package-visible for the tests; null puts the workspace back. */
    static void useForTest(Settings other) {
        store = other;
    }

    /** @return the segments of that series, in the order they were saved */
    public static List<Segment> of(String series) {
        Settings workspace = store();
        List<Segment> found = new ArrayList<>();

        for (int at = 0; ; at++) {
            String name = workspace.get(keyOf(series, at, "name"), null);
            String from = workspace.get(keyOf(series, at, "from"), null);

            if (name == null || from == null) {
                return found;
            }

            try {
                String to = workspace.get(keyOf(series, at, "to"), null);

                found.add(new Segment(name, LocalDate.parse(from),
                        to == null || to.isBlank() ? null : LocalDate.parse(to)));
            } catch (DateTimeParseException | IllegalArgumentException e) {
                // A hand-edited file. The entry is skipped rather than the whole
                // segmentation refused: losing one segment is recoverable by
                // typing it again, and losing all of them because of one bad
                // date is not what the reader would have chosen.
                continue;
            }
        }
    }

    /** Replaces the segments of that series. */
    public static void set(String series, List<Segment> segments) {
        Settings workspace = store();

        workspace.removeStartingWith(PREFIX + series + ".");

        for (int at = 0; at < segments.size(); at++) {
            Segment each = segments.get(at);

            workspace.put(keyOf(series, at, "name"), each.name());
            workspace.put(keyOf(series, at, "from"), each.from().toString());

            if (each.to() != null) {
                workspace.put(keyOf(series, at, "to"), each.to().toString());
            }
        }
    }

    /**
     * @return the segments that overlap another, by name
     *
     * <p>Reported, never prevented. Two segments sharing days is usually a
     * mistake — a stretch used for searching and for testing at once proves
     * nothing — but it is occasionally what the reader meant, and this is not
     * the place to decide which.</p>
     */
    public static List<String> overlapping(List<Segment> segments) {
        List<String> clashing = new ArrayList<>();

        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                if (overlap(segments.get(i), segments.get(j))) {
                    String pair = segments.get(i).name() + " / " + segments.get(j).name();

                    if (!clashing.contains(pair)) {
                        clashing.add(pair);
                    }
                }
            }
        }

        return clashing;
    }

    private static boolean overlap(Segment one, Segment other) {
        LocalDate oneEnd = one.to() == null ? LocalDate.MAX : one.to();
        LocalDate otherEnd = other.to() == null ? LocalDate.MAX : other.to();

        return !one.from().isAfter(otherEnd) && !other.from().isAfter(oneEnd);
    }

    private static String keyOf(String series, int at, String field) {
        return PREFIX + series + "." + at + "." + field;
    }
}
