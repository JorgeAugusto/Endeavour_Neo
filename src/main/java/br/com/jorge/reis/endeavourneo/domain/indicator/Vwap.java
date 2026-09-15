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
package br.com.jorge.reis.endeavourneo.domain.indicator;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;

/**
 * The volume-weighted average price of the session, and its own deviation.
 *
 * <p>Ported from the specification of the integrated fade model, section 4.2,
 * which defines it for a one-minute bar as
 *
 * <pre>{@code
 * tp    = (high + low + close) / 3
 * w     = max(tickvol, 1)
 * vwap  = sum(tp * w) / sum(w)
 * var   = sum((tp - vwap_at_that_bar)^2 * w) / sum(w)
 * sd    = sqrt(max(var, 0))
 * }</pre>
 *
 * <h2>The deviation is NOT the ordinary weighted variance, and that is on purpose</h2>
 *
 * <p>Read the middle line again: each term subtracts the VWAP <b>as it stood at
 * that bar</b>, not the VWAP at the end. The ordinary weighted variance would
 * subtract one number from every term; this subtracts a moving one, so early
 * bars are measured against an average built from almost nothing and late bars
 * against a settled one.
 *
 * <p>It is not what a statistician would write, and the specification says so in
 * as many words: <i>"não substituir silenciosamente por outra fórmula de
 * variância ponderada"</i>. The bands are where the model's orders rest, so the
 * two formulas do not differ by a rounding — they put the orders at different
 * prices, and a port that quietly improved the arithmetic would be measuring a
 * different strategy while reporting the original's name.
 *
 * <h2>It restarts every session</h2>
 *
 * <p>The specification calls it the <i>intraday</i> deviation and uses it for
 * levels that belong to one day, so the sums begin again at each session's first
 * bar. That reading is an inference rather than a quoted line — the source
 * document never writes the reset — and it is recorded here so that whoever
 * checks parity against the Python knows which of the two readings this is.
 *
 * <h2>Volume</h2>
 *
 * <p>{@link PriceSeries} carries no volume, so {@code w} is one for every bar
 * and the weighting collapses to a plain running mean of the typical price.
 * When a series that knows its volume arrives, {@link #over(PriceSeries,
 * double[], ZoneId)} takes it; the arithmetic is already written for it.
 *
 * @param bands how many deviations out the outermost band sits
 */
public record Vwap(int bands) {

    /** {@code vwap_m1..vwap_m4} and {@code vwap_p1..vwap_p4}. */
    public static final int BANDS = 4;

    /** One value per bar: the average, and the deviation around it. */
    public record Lines(double[] vwap, double[] deviation) {

        /**
         * @param bar  which bar
         * @param away how many deviations out; negative is below
         * @return that band's price, or {@link Double#NaN} before it exists
         */
        public double bandAt(int bar, double away) {
            if (bar < 0 || bar >= vwap.length || Double.isNaN(vwap[bar])) {
                return Double.NaN;
            }

            return vwap[bar] + away * deviation[bar];
        }
    }

    public Vwap {
        if (bands < 1) {
            throw new IllegalArgumentException("a band count of " + bands + " is not one");
        }
    }

    public static Vwap standard() {
        return new Vwap(BANDS);
    }

    /**
     * @param bars the series, one session after another
     * @param zone the exchange's zone, which decides where a session begins
     * @return the average and the deviation, one value per bar
     */
    public Lines over(PriceSeries bars, ZoneId zone) {
        return over(bars, null, zone);
    }

    /**
     * @param volume one weight per bar, or null when the series has none
     * @see #over(PriceSeries, ZoneId)
     */
    public Lines over(PriceSeries bars, double[] volume, ZoneId zone) {
        int size = bars == null ? 0 : bars.size();
        double[] average = new double[size];
        double[] spread = new double[size];

        Arrays.fill(average, Double.NaN);
        Arrays.fill(spread, Double.NaN);

        if (size == 0) {
            return new Lines(average, spread);
        }

        ZoneId at = zone == null ? Timeframe.defaultZone() : zone;

        double weighted = 0;
        double weight = 0;
        double squared = 0;

        LocalDate day = dateOf(bars, 0, at);

        for (int bar = 0; bar < size; bar++) {
            LocalDate now = dateOf(bars, bar, at);

            if (!now.equals(day)) {
                // A NEW SESSION STARTS THE SUMS OVER. Carrying them across the
                // night would make the first bands of the morning describe
                // yesterday, and they are what today's orders rest on.
                weighted = 0;
                weight = 0;
                squared = 0;
                day = now;
            }

            double typical = (bars.highAt(bar) + bars.lowAt(bar) + bars.closeAt(bar)) / 3;
            double each = volume == null || bar >= volume.length
                    ? 1 : Math.max(volume[bar], 1);

            weighted += typical * each;
            weight += each;

            double here = weighted / weight;

            // THE RUNNING AVERAGE INSIDE THE TERM, not the settled one. See the
            // class javadoc: this is the specification's formula and not a
            // slip, and the ordinary weighted variance is a different number.
            squared += (typical - here) * (typical - here) * each;

            average[bar] = here;
            spread[bar] = Math.sqrt(Math.max(squared / weight, 0));
        }

        return new Lines(average, spread);
    }

    private static LocalDate dateOf(PriceSeries bars, int bar, ZoneId at) {
        return Instant.ofEpochMilli(bars.timeAt(bar)).atZone(at).toLocalDate();
    }
}
