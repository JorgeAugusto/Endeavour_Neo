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

import java.util.Arrays;

/**
 * Wilder's Relative Strength Index — the IFR.
 *
 * <p>How much of the recent movement was upward, as a number between nought and
 * a hundred: the average rise over the average rise plus the average fall.
 *
 * <h2>Why this is in the domain and not with the chart</h2>
 *
 * <p>The arithmetic was inside the chart's study, where the engine cannot reach
 * it — {@code domain} may not import {@code ui} — so a strategy that wants to
 * read an IFR would have needed a second implementation of it. Two
 * implementations of one indicator drift, and the drift is invisible: the chart
 * says the market was oversold and the run says it was not, and both pictures
 * look right on their own. Same move the stochastic and the PMO already made.
 *
 * <h2>A short period is a different animal</h2>
 *
 * <p>At {@link #SHORT} — two — this stops being a gauge and becomes a switch.
 * Two bars of falls and nothing else in the window puts it near zero; two bars
 * of rises put it near a hundred. That is the whole point of the setups built
 * on it, and it is also why a level like "below ten" is rare rather than
 * routine.
 *
 * @param period    how many bars the averages cover
 * @param smoothing how the rises and falls are averaged
 */
public record Rsi(int period, Smoothing smoothing) {

    /** What the chart is born with. */
    public static final int PERIOD = 9;

    /** The two of the IFR2 setups. */
    public static final int SHORT = 2;

    /**
     * How the rises and falls are averaged.
     *
     * <p>The same two the chart's study offers, named the same way so the
     * interface can map one onto the other by name. Repeated here rather than
     * imported because the arrow only points one way: the domain cannot see
     * {@code ui}, and it must not learn to.</p>
     */
    public enum Smoothing {

        /** Wilder's: every bar ever seen still counts, a little. */
        CLASSIC,

        /** The plain mean of the last N. */
        SIMPLE
    }

    public Rsi {
        if (period < 1) {
            throw new IllegalArgumentException("an index over " + period + " bars is not one");
        }

        smoothing = smoothing == null ? Smoothing.CLASSIC : smoothing;
    }

    /** @return Wilder's, over the period the chart is born with */
    public static Rsi standard() {
        return new Rsi(PERIOD, Smoothing.CLASSIC);
    }

    /** @return the two-period index the IFR2 setups are built on */
    public static Rsi shortOne() {
        return new Rsi(SHORT, Smoothing.CLASSIC);
    }

    /**
     * @param bars the series to read
     * @return one value per bar; the warm-up is {@link Double#NaN}
     */
    public double[] over(PriceSeries bars) {
        int size = bars == null ? 0 : bars.size();
        double[] into = new double[size];

        Arrays.fill(into, Double.NaN);

        if (size <= period) {
            // Not enough closes for even one window. Every bar is unknown,
            // which is what NaN says and what every reader skips over.
            return into;
        }

        double[] rises = new double[size];
        double[] falls = new double[size];

        for (int i = 1; i < size; i++) {
            double change = bars.closeAt(i) - bars.closeAt(i - 1);

            rises[i] = Math.max(change, 0.0);
            falls[i] = Math.max(-change, 0.0);
        }

        // The seed is the plain mean of the first window, for BOTH kinds. That
        // is how Wilder starts too: his smoothing needs a previous value, and
        // the first one has to come from somewhere.
        double up = 0.0;
        double down = 0.0;

        for (int i = 1; i <= period; i++) {
            up += rises[i];
            down += falls[i];
        }

        up /= period;
        down /= period;

        double carried = 50.0;

        into[period] = carried = reading(up, down, carried);

        for (int i = period + 1; i < size; i++) {
            if (smoothing == Smoothing.CLASSIC) {
                // Every bar ever seen still counts, a little, and none ever
                // leaves the window because there is no window.
                up = (up * (period - 1) + rises[i]) / period;
                down = (down * (period - 1) + falls[i]) / period;
            } else {
                up = mean(rises, i);
                down = mean(falls, i);
            }

            into[i] = carried = reading(up, down, carried);
        }

        return into;
    }

    /** @return the plain mean of the last {@code period} entries ending at {@code i} */
    private double mean(double[] of, int i) {
        double total = 0.0;

        for (int back = 0; back < period; back++) {
            total += of[i - back];
        }

        return total / period;
    }

    /**
     * @param up      the average rise
     * @param down    the average fall
     * @param carried the last real reading, for a window where nothing moved
     * @return the indicator between nought and a hundred
     */
    private static double reading(double up, double down, double carried) {
        if (down <= 0.0) {
            // Nothing fell. A hundred is the definition working, not failing --
            // unless nothing rose either, in which case nothing happened at all
            // and the honest answer is the one from before.
            return up <= 0.0 ? carried : 100.0;
        }

        return 100.0 - 100.0 / (1.0 + up / down);
    }
}
