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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Whether a renko may be built for what a chart is showing.
 *
 * <h2>Why this is a question at all</h2>
 *
 * <p>Renko from one-minute candles is not the same renko. Measured on WINFUT
 * over the twenty sessions of January 2021, reversal two:</p>
 *
 * <pre>
 * brick   from candles   from ticks   candles are
 *    25         65.999       60.855        1,08x
 *    55         15.100       11.886        1,27x
 *   105          3.372        3.112        1,08x
 * </pre>
 *
 * <p>The candles lay MORE, which is the surprise. Reading a bar as "the high
 * then the low", in an order that has to be assumed, manufactures a full swing
 * inside every minute; the real path did not swing that much. The first version
 * of this comment claimed the opposite and by a factor of five, because the
 * measurement behind it read the session's opening marker -- a row that states
 * zero for everything -- as a trade, and anchored the renko at price zero. See
 * {@code TickBars.isTrade}.</p>
 *
 * <h2>The rule</h2>
 *
 * <p>Allowed while the reader lets missing ticks be filled in, which is the
 * default and has to be: one month of the series has ticks and eight years do
 * not. With that off, allowed only when <b>every</b> session on screen was
 * exported — a renko built partly from ticks and partly from candles would
 * change density halfway across, and look like the market did it.</p>
 */
final class RenkoSource {

    private RenkoSource() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param series what the chart is showing
     * @param library the tick sessions for that instrument
     * @param mayInvent whether missing ticks may be filled in
     * @return whether a renko drawn from this would mean what it says
     */
    static boolean allows(PriceSeries series, TickLibrary library, boolean mayInvent) {
        if (mayInvent) {
            return true;
        }

        if (series == null || series.size() == 0 || library == null) {
            return false;
        }

        Set<LocalDate> exported = new HashSet<>(library.exported());

        if (exported.isEmpty()) {
            return false;
        }

        ZoneId zone = ZoneId.systemDefault();

        // Walked once, and the calendar is only asked where the day changes. A
        // conversion per bar would be 693 thousand of them on the full series, on
        // the interface thread, for one keystroke.
        LocalDate seen = null;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();

            if (day.equals(seen)) {
                continue;
            }

            seen = day;

            if (!exported.contains(day)) {
                return false;
            }
        }

        return true;
    }

    /**
     * @return the sessions the series covers, in order
     *
     * <p>Walked once, and the calendar is only asked where the day changes: a
     * conversion per bar would be 825 thousand of them on the full source.</p>
     */
    static List<LocalDate> sessionsIn(PriceSeries series) {
        List<LocalDate> days = new ArrayList<>();

        if (series == null) {
            return days;
        }

        ZoneId zone = ZoneId.systemDefault();
        LocalDate seen = null;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();

            if (!day.equals(seen)) {
                seen = day;

                days.add(day);
            }
        }

        return days;
    }

    /**
     * @return the market whose tick sessions to look for
     *
     * <p>The market and not the export: {@code winfull-1m}, {@code winn-1m} and
     * {@code winfut-1m} all read {@code win-2021-01-04.bin}, because the ticks
     * of a day are what the exchange printed and not what one export stitched.
     * </p>
     */
    static String rootOf(String name) {
        return br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.groupOf(name);
    }
}
