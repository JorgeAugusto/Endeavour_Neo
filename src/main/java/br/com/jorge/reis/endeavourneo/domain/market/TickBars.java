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
package br.com.jorge.reis.endeavourneo.domain.market;

/**
 * A session's trades as bars of one price each.
 *
 * <h2>Why this is what renko wants</h2>
 *
 * <p>A renko built from candles has to GUESS the order in which a bar reached
 * its high and its low, and that guess is most of the answer. Measured on
 * WINFUT, brick 55, one day: 477 bricks from one-minute candles against 2.563
 * from the ticks. From candles the algorithm sees one high and one low a
 * minute, in an assumed order; the ticks show every reversal that happened, and
 * each one the minute hid is two more bricks.</p>
 *
 * <p>Here each bar is a single price, so open, high, low and close are the same
 * number and there is nothing left to assume. The renko that comes out is not
 * an approximation of the tick renko — it IS the tick renko.</p>
 *
 * <h2>Trades only</h2>
 *
 * <p>The rows that carry a quote and no trade are skipped: a bid that moved is
 * not a price the market paid, and renko is built from what traded. They stay
 * in the file, which is what lets the book be rebuilt; they simply are not
 * bars.</p>
 *
 * <p>A view, not a copy. A session is 4,4 million ticks and copying the traded
 * ones into new arrays would add 50 MB to a chart that is about to reduce them
 * all to a few thousand bricks.</p>
 */
public final class TickBars implements PriceSeries {

    private final TickSeries ticks;

    /** Where each bar's tick is, so a bar does not have to search for it. */
    private final int[] trades;

    private TickBars(TickSeries ticks, int[] trades) {
        this.ticks = ticks;
        this.trades = trades;
    }

    /** @return that session's trades, one bar each */
    public static TickBars of(TickSeries ticks) {
        int count = 0;

        for (int i = 0; i < ticks.size(); i++) {
            if (ticks.hasLast(i)) {
                count++;
            }
        }

        int[] trades = new int[count];
        int at = 0;

        for (int i = 0; i < ticks.size(); i++) {
            if (ticks.hasLast(i)) {
                trades[at++] = i;
            }
        }

        return new TickBars(ticks, trades);
    }

    @Override
    public int size() {
        return trades.length;
    }

    @Override
    public long timeAt(int index) {
        return ticks.timeAt(trades[index]);
    }

    @Override
    public double openAt(int index) {
        return ticks.lastAt(trades[index]);
    }

    @Override
    public double highAt(int index) {
        return ticks.lastAt(trades[index]);
    }

    @Override
    public double lowAt(int index) {
        return ticks.lastAt(trades[index]);
    }

    @Override
    public double closeAt(int index) {
        return ticks.lastAt(trades[index]);
    }

    @Override
    public double volumeAt(int index) {
        return ticks.volumeAt(trades[index]);
    }

    /**
     * @return the trades up to that instant, exclusive
     *
     * <p>What a replay needs: the renko as it stood at the moment being played,
     * and not one brick further. Binary search, because a replay asks for this
     * on every frame.</p>
     */
    public TickBars until(long when) {
        int low = 0;
        int high = trades.length - 1;
        int found = 0;

        while (low <= high) {
            int middle = (low + high) >>> 1;

            if (timeAt(middle) < when) {
                found = middle + 1;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }

        int[] head = new int[found];

        System.arraycopy(trades, 0, head, 0, found);

        return new TickBars(ticks, head);
    }
}
