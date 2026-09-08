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

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Where one trading day ends and the next begins, and what that is worth.
 *
 * <p>The chart already draws the boundary — the day band does it — but drawing
 * it and being able to answer questions about it are different things. This is
 * the second one: <b>how far is the price from where the last session left
 * it</b>, which is the number a quote screen puts beside the instrument's name
 * and the only one that says whether today has gone well.</p>
 *
 * <p>A day here is a <b>calendar day in the local zone</b>, not a session with
 * declared opening and closing times. The series does not say what it trades,
 * and inventing a schedule for it would be wrong for every instrument that does
 * not keep that schedule — an overnight future, a crypto pair, another
 * exchange. The calendar day is a weaker claim, and a true one.</p>
 */
public final class DayChange {

    private DayChange() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param series the bars
     * @param index the bar being asked about
     * @param zone the zone whose midnight separates the days
     * @return the close of the last bar of the previous day, or NaN
     *
     * <p>NaN when the bar belongs to the first day the series carries: there is
     * no previous close, and any number invented here would be read as one.</p>
     */
    public static double previousDayClose(PriceSeries series, int index, ZoneId zone) {
        if (series == null || index < 0 || index >= series.size()) {
            return Double.NaN;
        }

        LocalDate day = dayOf(series, index, zone);

        // Walked backwards from the bar rather than forwards from the start:
        // the answer is always a few bars away in an intraday series, and a
        // scan from the beginning would cost the whole history on every paint.
        for (int i = index - 1; i >= 0; i--) {
            if (!dayOf(series, i, zone).equals(day)) {
                return series.closeAt(i);
            }
        }

        return Double.NaN;
    }

    /**
     * @return where the last bar stands against the previous day's close, as a
     *         percentage, or NaN when the series cannot answer
     */
    public static double changeOnDay(PriceSeries series, ZoneId zone) {
        if (series == null || series.size() == 0) {
            return Double.NaN;
        }

        int last = series.size() - 1;

        return percentChange(previousDayClose(series, last, zone), series.closeAt(last));
    }

    /**
     * @param from the reference price
     * @param to where the price is now
     * @return the move as a percentage, or NaN when there is nothing to compare
     *
     * <p>NaN and not zero when the reference is zero or missing. Zero is an
     * answer — "it has not moved" — and it would be the wrong one.</p>
     */
    public static double percentChange(double from, double to) {
        if (!Double.isFinite(from) || !Double.isFinite(to) || from == 0.0) {
            return Double.NaN;
        }

        return 100.0 * (to - from) / from;
    }

    /**
     * @return the change formatted the way a quote screen writes it, or an empty
     *         string when there is no change to show
     *
     * <p>Signed whenever there is a direction, plus included: a bare "3,04%"
     * beside an instrument name reads as a quantity rather than as a move.</p>
     *
     * <p><b>Except exactly zero</b>, which has no direction to state. This used
     * to say "always signed" and print "0,00%" bare, which is the right output
     * under a promise it was not keeping. A "+0,00%" would be claiming a rise
     * of nothing.</p>
     */
    public static String formatChange(double percent) {
        if (!Double.isFinite(percent)) {
            return "";
        }

        java.text.DecimalFormat format = new java.text.DecimalFormat("#,##0.00",
                java.text.DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()));

        return (percent > 0 ? "+" : percent < 0 ? "-" : "")
                + format.format(Math.abs(percent)) + "%";
    }

    private static LocalDate dayOf(PriceSeries series, int index, ZoneId zone) {
        return Instant.ofEpochMilli(series.timeAt(index)).atZone(zone).toLocalDate();
    }
}
