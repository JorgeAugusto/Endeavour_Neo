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
package br.com.jorge.reis.endeavourneo.ui.series;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Sessions;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Something a segment can be cut out of, named the same way wherever it is
 * stored.
 *
 * <h2>Why ticks needed a name</h2>
 *
 * <p>Segments are kept per series, keyed by the series' name — and a tick
 * source has no name in the catalog, only a folder. So it is given one here:
 * {@code win/ticks/profit}. It is a key and not a file; nothing looks for it on
 * disk. What it buys is that <b>everything that has sessions can be
 * segmented</b>, through one list, with one settings key shape, and without
 * {@link br.com.jorge.reis.endeavourneo.platform.Segmentation} learning what a
 * tick is.</p>
 *
 * <h2>Sessions, not bars</h2>
 *
 * <p>Everything a segment window asks — where the data starts, where it ends,
 * how many sessions a range holds — is answered by the list of days. A bar
 * series has to be read to produce that list; a tick source produces it from
 * the directory listing, without opening a single file. Working in days rather
 * than in bars is what lets the two be treated alike.</p>
 */
public final class Segmentable {

    /** What separates a market from its tick source in a key. */
    static final String TICKS = "/ticks/";

    private Segmentable() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return every series and tick source that has sessions, in tree order */
    public static List<String> keys() {
        List<String> found = new ArrayList<>(SeriesCatalog.names());

        for (String instrument : instruments()) {
            for (TickSource source : TickSource.values()) {
                if (!daysOfTicks(instrument, source).isEmpty()) {
                    found.add(keyOfTicks(instrument, source));
                }
            }
        }

        return found;
    }

    public static String keyOfTicks(String instrument, TickSource source) {
        return instrument + TICKS + source.key();
    }

    public static boolean isTicks(String key) {
        return key != null && key.contains(TICKS);
    }

    /** @return the market half of a ticks key, or null when it is not one */
    public static String instrumentOf(String key) {
        return isTicks(key) ? key.substring(0, key.indexOf(TICKS)) : null;
    }

    /**
     * @return the export half of a ticks key, or null when it is not one
     *
     * <p>Decoded here and nowhere else. It was picked apart inline in two
     * places, which is two places to forget when the separator changes.</p>
     */
    public static TickSource sourceOf(String key) {
        if (!isTicks(key)) {
            return null;
        }

        String wanted = key.substring(key.indexOf(TICKS) + TICKS.length());

        for (TickSource each : TickSource.values()) {
            if (each.key().equals(wanted)) {
                return each;
            }
        }

        return null;
    }

    /** @return how the key is written on screen */
    public static String labelOf(String key) {
        if (!isTicks(key)) {
            return key;
        }

        String source = key.substring(key.indexOf(TICKS) + TICKS.length());

        return Messages.market(instrumentOf(key)) + "  ·  " + Messages.get("navigator.ticks")
                + "  ·  " + Messages.orElse("navigator.tickSource." + source, source);
    }

    /**
     * @return the days that key holds, or an empty set when it will not read
     *
     * <p>Empty rather than an exception: a series whose file is gone still has
     * segments, and the window that lists them is still worth opening. What it
     * cannot do is place them on a map, and an empty set says exactly that.</p>
     */
    public static NavigableSet<LocalDate> sessionsOf(String key) {
        if (isTicks(key)) {
            TickSource source = sourceOf(key);

            return source == null ? new TreeSet<>()
                    : new TreeSet<>(daysOfTicks(instrumentOf(key), source));
        }

        try {
            PriceSeries bars = SeriesCatalog.open(key).orElse(null);

            return bars == null || bars.size() == 0 ? new TreeSet<>() : Sessions.of(bars);
        } catch (IOException e) {
            return new TreeSet<>();
        }
    }

    /**
     * @return the days that export holds
     *
     * <p><b>A walk plus one header per file.</b> This used to say "a directory
     * listing, not a read: this costs nothing", and it is not a listing: a
     * session is recognised by opening it and reading its header, which is what
     * makes the answer trustworthy -- a file filed under the wrong month is not
     * offered as a day the replay can open. Cheap next to folding the bars, and
     * not free.</p>
     *
     * <p>The sentence mattered because it was the reason given for reading this
     * on the interface thread. That reason is gone: the series window asks for
     * its days behind the window now, whichever kind of source it is.</p>
     */
    private static List<LocalDate> daysOfTicks(String instrument, TickSource source) {
        TickLibrary library =
                new TickLibrary(SeriesCatalog.ticksOf(instrument), instrument, source);

        try {
            return library.exported();
        } finally {
            library.close();
        }
    }

    /** @return the markets that have any series at all */
    private static List<String> instruments() {
        List<String> found = new ArrayList<>();

        for (String name : SeriesCatalog.names()) {
            String instrument = SeriesCatalog.groupOf(name);

            if (!found.contains(instrument)) {
                found.add(instrument);
            }
        }

        return found;
    }
}
