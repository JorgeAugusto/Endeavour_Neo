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
 * its high and its low, and that guess is most of the answer. Measured on WINFUT over the twenty sessions of January 2021, brick
 * 55: 9.718 bricks from one-minute candles against 8.076 from the exchange's
 * own ticks. The CANDLES lay 27% MORE, and that is the surprise: reading a bar
 * as "the high then the low", in an order that has to be assumed, manufactures
 * a full swing inside every minute. The real path did not swing that much.</p>
 *
 * <p>Here each bar is a single price, so open, high, low and close are the same
 * number and there is nothing left to assume. The renko that comes out is not
 * an approximation of the tick renko — it IS the tick renko.</p>
 *
 * <h2>Trades only</h2>
 *
 * <p>The rows that carry a quote and no trade are skipped: a bid that moved is
 * not a price the market paid, and renko is built from what traded. So are the
 * session markers that state zero for everything. They stay in the file, which
 * is what lets the book be rebuilt; they simply are not bars.</p>
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
            if (isTrade(ticks, i)) {
                count++;
            }
        }

        int[] trades = new int[count];
        int at = 0;

        for (int i = 0; i < ticks.size(); i++) {
            if (isTrade(ticks, i)) {
                trades[at++] = i;
            }
        }

        return new TickBars(ticks, trades);
    }

    /**
     * @return whether that row is a trade
     *
     * <p><b>Having a price is not enough: it has to be a price.</b> Every
     * session opens with a row that states zero for the bid, the ask, the last
     * and the volume — a marker, not a trade, and the whole reason this file
     * keeps "said zero" apart from "said nothing".</p>
     *
     * <p>This cost a measurement, and the wrong answer was published before it was
     * caught. Reading those rows as trades put a bar at price zero at the head
     * of every session, and a renko then climbed from zero to 120.000 laying
     * two thousand bricks that no trade made. It produced "477 bricks from
     * candles against 2.563 from ticks", which went into four javadocs, a
     * message the reader sees, and a commit.</p>
     *
     * <p>The arithmetic gave it away: 566 minutes whose ranges sum to 45.295
     * points cannot hold 2.563 bricks of 55. Corrected, the candles lay MORE
     * than the ticks, not fewer -- 9.718 against 8.076 over January 2021.</p>
     *
     * <p>Forty-one rows in twenty sessions. Two a day, and they moved the
     * answer by a factor of five.</p>
     */
    private static boolean isTrade(TickSeries ticks, int index) {
        return ticks.hasLast(index) && ticks.lastAt(index) > 0;
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
        return range(0, countUntil(when));
    }

    /**
     * @return how many bars happened strictly before that instant
     *
     * <p>Apart from {@link #until} because a replay does not want the head of
     * the session again on every frame -- it wants what arrived since the last
     * one. This gives the boundary; {@link #range} gives the slice.</p>
     */
    public int countUntil(long when) {
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

        return found;
    }

    /**
     * @param from the first bar, inclusive
     * @param to one past the last
     * @return that stretch of the session
     *
     * <p>Still a view of the ticks -- only the index of traded rows is copied,
     * which is four bytes a bar against the twenty-two the tick costs.</p>
     */
    public TickBars range(int from, int to) {
        int first = Math.max(0, Math.min(from, trades.length));
        int last = Math.max(first, Math.min(to, trades.length));
        int[] slice = new int[last - first];

        System.arraycopy(trades, first, slice, 0, slice.length);

        return new TickBars(ticks, slice);
    }
}
