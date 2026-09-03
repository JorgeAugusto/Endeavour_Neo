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
import java.util.HashSet;
import java.util.Set;

/**
 * Whether a renko may be built for what a chart is showing.
 *
 * <h2>Why this is a question at all</h2>
 *
 * <p>Renko from one-minute candles is not renko. Measured on WINFUT, the same
 * day, reversal two:</p>
 *
 * <pre>
 * brick   from candles   from ticks
 *    25          2.469        6.785
 *    55            477        2.563
 *   105            105        1.242
 * </pre>
 *
 * <p>From candles the algorithm sees one high and one low a minute, in an order
 * it has to assume; the ticks show every reversal that really happened, and
 * each one the minute hid is two more bricks. Two to eleven times fewer bricks
 * is not an approximation of the same chart — it is a different chart wearing
 * its name.</p>
 *
 * <h2>The rule</h2>
 *
 * <p>Allowed while the reader lets missing ticks be filled in, which is the
 * default and has to be: one month of the base has ticks and eight years do
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
        // conversion per bar would be 693 thousand of them on the full base, on
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

    /** @return the instrument without the scale: winfut-1m names winfut sessions */
    static String rootOf(String name) {
        if (name == null) {
            return "";
        }

        int dash = name.indexOf('-');

        return dash > 0 ? name.substring(0, dash) : name;
    }
}
