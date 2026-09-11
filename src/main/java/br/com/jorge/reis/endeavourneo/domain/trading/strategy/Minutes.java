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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

/**
 * The minutes a run is made of, whatever the run is made of.
 *
 * <p>A strategy written against one-minute candles has to keep seeing them even
 * when the engine is walking ticks — otherwise it is not that strategy any more.
 * "The previous close" over a synthetic tick path is the price one tick ago, and
 * a pullback rule reading that fires on noise and means nothing.</p>
 *
 * <h2>Decisions in minutes, orders in ticks</h2>
 *
 * <p>That is the whole point of running a candle strategy over a tick series,
 * and it is worth saying plainly because the two halves are easy to mix up:</p>
 *
 * <ul>
 *   <li>the <b>decisions</b> stay on the bar the strategy was written for — the
 *       range, the pullback, the averages;</li>
 *   <li>the <b>execution</b> happens at the resolution the engine is walking, so
 *       a stop and a target inside the same minute are settled by which price
 *       came first rather than by a tie-break.</li>
 * </ul>
 *
 * <p>Handed a series that is already minutes, this is the identity: every bar is
 * its own minute and nothing is grouped. So a strategy built on it runs on
 * either, and the only difference is how precisely its orders fill — which is
 * exactly the difference that should exist.</p>
 */
final class Minutes {

    private static final long MINUTE = 60_000L;

    private final int[] firstBar;

    private final double[] open;

    private final double[] high;

    private final double[] low;

    private final double[] close;

    private final long[] when;

    private Minutes(int[] firstBar, double[] open, double[] high, double[] low,
                    double[] close, long[] when) {
        this.firstBar = firstBar;
        this.open = open;
        this.high = high;
        this.low = low;
        this.close = close;
        this.when = when;
    }

    /**
     * @param series whatever the engine is walking
     * @return its minutes, in order
     */
    static Minutes of(PriceSeries series) {
        int size = series == null ? 0 : series.size();

        if (size == 0) {
            return new Minutes(new int[0], new double[0], new double[0], new double[0],
                    new double[0], new long[0]);
        }

        int[] starts = new int[size];
        int many = 0;
        long bucket = Long.MIN_VALUE;

        for (int bar = 0; bar < size; bar++) {
            long now = Math.floorDiv(series.timeAt(bar), MINUTE);

            if (now != bucket) {
                starts[many++] = bar;
                bucket = now;
            }
        }

        double[] o = new double[many];
        double[] h = new double[many];
        double[] l = new double[many];
        double[] c = new double[many];
        long[] t = new long[many];

        for (int minute = 0; minute < many; minute++) {
            int from = starts[minute];
            int to = minute + 1 < many ? starts[minute + 1] - 1 : size - 1;

            o[minute] = series.openAt(from);
            c[minute] = series.closeAt(to);
            t[minute] = series.timeAt(from);
            h[minute] = Double.NEGATIVE_INFINITY;
            l[minute] = Double.POSITIVE_INFINITY;

            for (int bar = from; bar <= to; bar++) {
                h[minute] = Math.max(h[minute], series.highAt(bar));
                l[minute] = Math.min(l[minute], series.lowAt(bar));
            }
        }

        return new Minutes(java.util.Arrays.copyOf(starts, many), o, h, l, c, t);
    }

    int size() {
        return open.length;
    }

    /**
     * @param bar an index into the series this was built from
     * @return which minute that bar belongs to
     */
    int minuteOf(int bar) {
        int at = java.util.Arrays.binarySearch(firstBar, bar);

        return at >= 0 ? at : Math.max(0, -at - 2);
    }

    /** @return whether that bar is the last one of its minute */
    boolean endsAMinute(int bar, int size) {
        int minute = minuteOf(bar);
        int next = minute + 1 < firstBar.length ? firstBar[minute + 1] : size;

        return bar == next - 1;
    }

    /** @return the index of the first bar of that minute */
    int firstBarOf(int minute) {
        return firstBar[minute];
    }

    long timeAt(int minute) {
        return when[minute];
    }

    double openAt(int minute) {
        return open[minute];
    }

    double highAt(int minute) {
        return high[minute];
    }

    double lowAt(int minute) {
        return low[minute];
    }

    double closeAt(int minute) {
        return close[minute];
    }

    /** @return these minutes seen as an ordinary series, for anything that wants one */
    PriceSeries asSeries() {
        return new PriceSeries() {

            @Override
            public int size() {
                return Minutes.this.size();
            }

            @Override
            public long timeAt(int index) {
                return when[index];
            }

            @Override
            public double openAt(int index) {
                return open[index];
            }

            @Override
            public double highAt(int index) {
                return high[index];
            }

            @Override
            public double lowAt(int index) {
                return low[index];
            }

            @Override
            public double closeAt(int index) {
                return close[index];
            }
        };
    }
}
