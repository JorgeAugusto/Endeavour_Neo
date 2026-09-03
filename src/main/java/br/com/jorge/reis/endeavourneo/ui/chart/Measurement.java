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

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.util.ArrayList;
import java.util.List;

/**
 * What the ruler measured: two points on the chart, and everything derived.
 *
 * <p><b>Bars and time are reported separately, and that is the point.</b> Six
 * fifteen-minute candles cover an hour and a half within a session and eighteen
 * hours if they straddle a night. Reporting only one of the two lets the reader
 * infer the other and be wrong — and the projection they draw from it is wrong
 * by the same amount.</p>
 *
 * <p>A record because it is a value: two points in, a set of derived numbers
 * out, nothing kept and nothing to invalidate.</p>
 *
 * @param fromBar the bar the drag started on
 * @param toBar the bar it ended on
 * @param fromPrice the price the drag started at
 * @param toPrice the price it ended at
 * @param fromTime the first bar's instant, in milliseconds
 * @param toTime the last bar's instant, in milliseconds
 */
public record Measurement(int fromBar, int toBar, double fromPrice, double toPrice,
                          long fromTime, long toTime) {

    /**
     * @param series the data
     * @param fromBar where the drag started
     * @param fromPrice the price under the cursor when it started
     * @param toBar where it is now
     * @param toPrice the price under the cursor now
     * @return the measurement, or null when either bar is outside the series
     */
    public static Measurement between(PriceSeries series, int fromBar, double fromPrice,
                                      int toBar, double toPrice) {
        if (fromBar < 0 || toBar < 0 || fromBar >= series.size() || toBar >= series.size()) {
            return null;
        }

        return new Measurement(fromBar, toBar, fromPrice, toPrice,
                series.timeAt(fromBar), series.timeAt(toBar));
    }

    /** @return the price difference, signed */
    public double difference() {
        return toPrice - fromPrice;
    }

    /**
     * @return the move as a percentage of where it started, or NaN from zero
     *
     * <p>NaN rather than infinity from a zero start: infinity formats as a
     * symbol nobody expects to see in a price box, and the box omits the row.</p>
     */
    public double percent() {
        return fromPrice == 0.0 ? Double.NaN : 100.0 * difference() / fromPrice;
    }

    /**
     * @return how many bars the measurement spans
     *
     * <p>Inclusive of both ends: dragging across six candles reports six, which
     * is what the reader counted on screen. Reporting the gap between indices
     * would say five and start an argument with the picture.</p>
     */
    public int bars() {
        return Math.abs(toBar - fromBar) + 1;
    }

    /** @return elapsed wall-clock time between the two bars, in milliseconds */
    public long elapsed() {
        return Math.abs(toTime - fromTime);
    }

    /**
     * @return the elapsed time in words, e.g. "1 hora e 30 minutos"
     *
     * <p>Built from parts rather than a fixed pattern because the units that
     * matter change with the span: minutes for a scalp, days for a swing, and
     * printing "0 dias 0 horas 12 minutos" for the first would be noise.</p>
     */
    public String elapsedInWords() {
        long minutes = elapsed() / 60_000L;

        if (minutes == 0) {
            return Messages.get("ruler.lessThanAMinute");
        }

        long days = minutes / 1_440;
        long hours = minutes % 1_440 / 60;
        long rest = minutes % 60;

        List<String> parts = new ArrayList<>(3);

        if (days > 0) {
            parts.add(unit(days, "ruler.day", "ruler.days"));
        }
        if (hours > 0) {
            parts.add(unit(hours, "ruler.hour", "ruler.hours"));
        }
        if (rest > 0) {
            parts.add(unit(rest, "ruler.minute", "ruler.minutes"));
        }

        if (parts.size() == 1) {
            return parts.get(0);
        }

        // The last part joins with "and", the rest with commas -- the way the
        // phrase is said aloud, which is how the reader parses it.
        String last = parts.remove(parts.size() - 1);

        return String.join(", ", parts) + " " + Messages.get("ruler.and") + " " + last;
    }

    private static String unit(long amount, String singular, String plural) {
        return Messages.get(amount == 1 ? singular : plural, amount);
    }
}
