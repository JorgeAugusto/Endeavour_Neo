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
 * A day, arriving one price at a time.
 *
 * <p>The whole day is already in memory — it happened years ago — and this
 * decides how much of it exists <b>as far as anything reading it can tell</b>.
 * A chart drawing this series sees a market that is still going on.</p>
 *
 * <p><b>The last bar is unfinished, and moves.</b> Its open is fixed the moment
 * it starts; its high, low and close change with every price that arrives, the
 * way the last candle on a live screen does. Revealing whole bars instead would
 * make a one-minute chart jump once a minute and show nothing in between, which
 * is not what watching a market looks like.</p>
 *
 * <p><b>What has not arrived cannot be read</b>, and that is the point. If the
 * future were merely "not drawn", every indicator, measurement and strategy
 * could still reach it, and the first accidental look would be silent. Here it
 * is out of bounds, the way a bar tomorrow is out of bounds.</p>
 *
 * <p>Not thread-safe, and does not need to be: the clock that advances it and
 * the chart that reads it are both on the interface thread.</p>
 */
public final class ReplaySeries implements PriceSeries {

    private static final long DEFAULT_BAR_MILLIS = 60_000L;

    private final PriceSeries day;

    private final SyntheticTicks ticks;

    /** How much market time one source bar covers. */
    private final long barMillis;

    /** Bars that have finished forming. */
    private int completed;

    /** The prices inside the bar being formed, or null when none is. */
    private double[] path;

    private int cursor;

    private double high;

    private double low;

    private double close;

    /** Market time asked for and not yet spent, so slow speeds still advance. */
    private long owed;

    /**
     * @param day the whole session
     * @param completed how many bars have already finished
     * @param ticks how a bar is broken into prices, or null to jump bar by bar
     */
    public ReplaySeries(PriceSeries day, int completed, SyntheticTicks ticks) {
        this.day = day == null ? PriceSeries.empty() : day;
        this.ticks = ticks;
        this.completed = clamp(completed);
        this.barMillis = measureBar(this.day);
    }

    public ReplaySeries(PriceSeries day, int completed) {
        this(day, completed, null);
    }

    /** @param day the whole session, with nothing revealed yet */
    public static ReplaySeries of(PriceSeries day) {
        return new ReplaySeries(day, 0, null);
    }

    /**
     * @return how long one bar lasts, read off the data
     *
     * <p>From the first two bars rather than asked for: the series knows its own
     * spacing, and a caller passing the wrong number would make the replay run
     * at the wrong speed with nothing to show it.</p>
     */
    private static long measureBar(PriceSeries day) {
        if (day.size() < 2) {
            return DEFAULT_BAR_MILLIS;
        }

        long gap = day.timeAt(1) - day.timeAt(0);

        return gap > 0 ? gap : DEFAULT_BAR_MILLIS;
    }

    public int total() {
        return day.size();
    }

    public int revealed() {
        return size();
    }

    public boolean finished() {
        return completed >= day.size() && path == null;
    }

    // ------------------------------------------------------------- the clock

    /**
     * Lets that much market time pass.
     *
     * @param millis market time, not wall time — the transport scales it
     *
     * <p>What is left over is remembered rather than dropped. At one times
     * speed a frame is forty milliseconds and a price arrives every few seconds;
     * discarding the remainder would mean nothing ever arrived at all.</p>
     */
    public void advanceMarketTime(long millis) {
        if (ticks == null) {
            // No tick generator: fall back to whole bars, which is what the
            // chart got before this existed.
            advance((int) Math.max(0, millis / barMillis));

            return;
        }

        owed += Math.max(0L, millis);

        while (owed > 0) {
            if (path == null && !startForming()) {
                owed = 0;

                return;
            }

            long perPrice = Math.max(1L, barMillis / path.length);

            if (owed < perPrice) {
                return;
            }

            owed -= perPrice;

            step();
        }
    }

    private boolean startForming() {
        if (completed >= day.size()) {
            return false;
        }

        path = ticks.pathFor(day, completed);
        cursor = 0;
        high = path[0];
        low = path[0];
        close = path[0];

        return true;
    }

    private void step() {
        cursor++;

        if (cursor >= path.length) {
            // The bar is done; it becomes history exactly as it is stored, so
            // nothing invented survives into the finished chart.
            completed++;
            path = null;

            return;
        }

        double price = path[cursor];

        high = Math.max(high, price);
        low = Math.min(low, price);
        close = price;
    }

    // -------------------------------------------------------- the transport

    /**
     * @param bars how many more whole bars to reveal
     * @return how many were actually revealed, which is fewer at the close
     */
    public int advance(int bars) {
        int before = size();

        // Any half-formed bar is dropped: a step is a jump to a bar boundary,
        // and leaving a partial one behind would make the count disagree with
        // what is drawn.
        path = null;
        owed = 0;
        completed = clamp(before + Math.max(0, bars));

        return size() - before;
    }

    public void seek(int bar) {
        path = null;
        owed = 0;
        completed = clamp(bar);
    }

    public void seekFraction(double fraction) {
        if (!Double.isFinite(fraction)) {
            return;
        }

        seek((int) Math.round(Math.max(0.0, Math.min(1.0, fraction)) * day.size()));
    }

    /** @return the instant the session would show on its clock right now */
    public long clock() {
        if (day.size() == 0) {
            return 0L;
        }

        // Before anything arrives, the session's opening time -- not 1970, which
        // is what a bare zero would render as while the reader decides whether
        // to press play.
        return size() == 0 ? day.timeAt(0) : day.timeAt(size() - 1);
    }

    private int clamp(int bar) {
        return Math.max(0, Math.min(bar, day.size()));
    }

    // ------------------------------------------------ what has arrived so far

    @Override
    public int size() {
        return completed + (path == null ? 0 : 1);
    }

    @Override
    public long timeAt(int index) {
        return day.timeAt(check(index));
    }

    @Override
    public double openAt(int index) {
        // The open of the bar being formed is fixed the moment it starts, and it
        // is the stored open: only the other three move.
        return day.openAt(check(index));
    }

    @Override
    public double highAt(int index) {
        return forming(index) ? high : day.highAt(check(index));
    }

    @Override
    public double lowAt(int index) {
        return forming(index) ? low : day.lowAt(check(index));
    }

    @Override
    public double closeAt(int index) {
        return forming(index) ? close : day.closeAt(check(index));
    }

    @Override
    public double volumeAt(int index) {
        if (!forming(index)) {
            return day.volumeAt(check(index));
        }

        // The share of the bar's volume that has arrived so far. As made up as
        // the prices are, and consistent with them: a bar half formed shows
        // half its trading.
        double whole = day.volumeAt(completed);

        return Double.isFinite(whole) ? whole * cursor / (double) path.length : Double.NaN;
    }

    private boolean forming(int index) {
        return path != null && check(index) == completed;
    }

    /**
     * @throws IndexOutOfBoundsException saying the bar has not happened yet
     *
     * <p>Worth its own message. "Index 300 out of bounds for length 42" sends the
     * reader hunting for a bug in the chart; "bar 300 has not happened yet" names
     * what it is — something read ahead of the clock.</p>
     */
    private int check(int index) {
        if (index < 0 || index >= size()) {
            throw new IndexOutOfBoundsException(
                    "bar " + index + " has not happened yet: " + size() + " of "
                            + day.size() + " so far");
        }

        return index;
    }
}
