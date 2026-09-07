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
 * What has arrived since the last brick closed, waiting for the next one.
 *
 * <h2>The rule it carries</h2>
 *
 * <p><b>A brick holds everything that traded while it was the one being
 * built.</b> Not the trades that landed inside its own price band — every
 * trade, wherever it printed. A brick is a stretch of time as much as a band of
 * price, and this is the stretch.</p>
 *
 * <p>Measured against the reference product on 02/09/2026 at 100 points, its
 * last two boxes:</p>
 *
 * <table>
 *   <caption>Read off the chart, then counted on the tape</caption>
 *   <tr><th></th><th>box</th><th>it says</th><th>the tape, in that stretch</th></tr>
 *   <tr><td>17:06:59</td><td>188.000 &rarr; 187.900</td><td>43.900</td>
 *       <td>44.081, ranging 187.900 to 188.150</td></tr>
 *   <tr><td>17:16:03</td><td>188.000 &rarr; 188.100</td><td>146.009</td>
 *       <td>146.120, ranging 187.820 to 188.065</td></tr>
 * </table>
 *
 * <p>Both stretches run well outside the box drawn over them — the first counts
 * trades up at 188.150 and the second counts trades down at 187.820 — so the
 * count cannot be by band. It is by time. (The small gap between the two
 * columns is the second the clock is rounded to: a box's own timestamp is the
 * only boundary the chart shows, and it names a whole second.)</p>
 *
 * <h2>Who gets it</h2>
 *
 * <p>When one trade lays several bricks at once, <b>the first of them takes all
 * of this and the rest take nothing</b> — they were passed through in an
 * instant and were never the brick being built. Then this starts again from the
 * trade that laid them, which belongs to the brick now forming.</p>
 *
 * <p>That is what draws the grey run after a gap, and it is measured: on
 * 03/09/2026 the first print of the day, 500 contracts at 189.480, closed the
 * box that had been forming since 17:16 the evening before — 146.009 trades —
 * and created thirteen more on its way up, every one of them empty.</p>
 *
 * <p>It crosses the night for the same reason. The box that the morning's first
 * print closed had been collecting since the previous afternoon; a ruler that
 * carries but a count that does not would have thrown those trades away.</p>
 */
public final class TradeTally {

    private long trades;

    private double volume;

    private long first = Long.MAX_VALUE;

    /** Whether a bar with a range was seen, which makes counting impossible. */
    private boolean summarised;

    public TradeTally() {
        // Empty: nothing has arrived yet.
    }

    /**
     * @return whether that tally holds the same numbers
     *
     * <p>By VALUE, and the reason is {@link Renko.Carry}: a record compares its
     * components with {@code equals}, so without this two carries holding the
     * same figures were two different carries. The one test that compared them
     * — <i>an empty session moved the ruler</i> — passed only because the empty
     * path hands the same object straight back, which is a property of that
     * path and not of the ruler the test claimed to be checking.</p>
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }

        if (!(other instanceof TradeTally that)) {
            return false;
        }

        return trades == that.trades
                && Double.compare(volume, that.volume) == 0
                && first == that.first
                && summarised == that.summarised;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(trades, volume, first, summarised);
    }

    @Override
    public String toString() {
        return (summarised ? "unknown trades" : trades + " trades") + ", " + volume;
    }

    /** @return a copy, so folding a stretch never writes through the caller's carry */
    public TradeTally copy() {
        TradeTally other = new TradeTally();

        other.trades = trades;
        other.volume = volume;
        other.first = first;
        other.summarised = summarised;

        return other;
    }

    /**
     * @return whether anything here came from a bar that is not a single trade
     *
     * <p><b>Only trades can be counted.</b> A minute candle is a summary of
     * trades at prices and times it does not report, so a renko built from
     * candles answers "unknown" rather than inventing a number. See {@link
     * Counted}.</p>
     */
    public boolean summarised() {
        return summarised;
    }

    public long trades() {
        return trades;
    }

    public double volume() {
        return volume;
    }

    /** @return when the first of them arrived; only meaningful when there are any */
    public long first() {
        return first;
    }

    /**
     * Notes what KIND of bar is arriving, before it is added.
     *
     * <p>Apart from {@link #add} because a bar is added after the bricks it
     * laid, and the question "can this be counted at all" has to be answered
     * before them. Without this, the very bar whose range makes counting
     * impossible would arrive too late to stop it, and a renko of candles would
     * report the number of CANDLES as its number of trades.</p>
     */
    void seeing(PriceSeries source, int index) {
        if (source.highAt(index) != source.lowAt(index)) {
            summarised = true;
        }
    }

    /** Adds one bar. */
    void add(PriceSeries source, int index) {
        seeing(source, index);

        double lots = source.volumeAt(index);

        trades++;
        first = Math.min(first, source.timeAt(index));

        if (Double.isFinite(lots)) {
            volume += lots;
        }
    }

    /**
     * Starts again, from nothing.
     *
     * <p><b>Except for {@link #summarised}</b>, which is a property of the
     * SOURCE and not of one stretch of it. Clearing it here was a defect: one
     * bar can lay two batches -- its low and then its high -- and the second
     * batch would find a tally that had forgotten it was reading candles, and
     * would start counting them as trades.</p>
     */
    void clear() {
        trades = 0;
        volume = 0.0;
        first = Long.MAX_VALUE;
    }
}
