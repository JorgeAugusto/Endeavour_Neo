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

    /**
     * Bars before the session, always visible.
     *
     * <p>The days leading up to the one being played. They are history and were
     * never in doubt, so the transport cannot hide them: rewinding to the start
     * goes back to the session's open, not to an empty screen.</p>
     */
    private final int origin;

    private final TickPath ticks;

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
    public ReplaySeries(PriceSeries day, int completed, TickPath ticks) {
        this(day, 0, completed, ticks);
    }

    /**
     * @param day everything the chart may see, history and session together
     * @param origin how many leading bars are history and always visible
     * @param completed how many bars have finished, counted from the start
     * @param ticks how a bar is broken into prices, or null to jump bar by bar
     */
    public ReplaySeries(PriceSeries day, int origin, int completed, TickPath ticks) {
        this.day = day == null ? PriceSeries.empty() : day;
        this.ticks = ticks;
        this.origin = Math.max(0, Math.min(origin, this.day.size()));
        this.completed = clamp(Math.max(this.origin, completed));
        this.barMillis = measureBar(this.day);
    }

    public ReplaySeries(PriceSeries day, int completed) {
        this(day, completed, null);
    }

    /** @param day the whole session, with nothing revealed yet */
    public static ReplaySeries of(PriceSeries day) {
        return new ReplaySeries(day, 0, null);
    }

    /** @return how many leading bars are history rather than replay */
    public int origin() {
        return origin;
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

    /**
     * @return the instant this session ENDS, read off the bars it holds
     *
     * <p>The same number {@link #clock} settles on once everything has been
     * revealed, reached the other way: from the last bar of the day rather than
     * from how much of it has been played. The transport labels the far end of
     * its scrubber with this, and used to label it with a constant — nine in the
     * morning plus a fixed number of minutes — which is a guess about a session
     * rather than a reading of one.</p>
     */
    public long end() {
        if (day.size() == 0) {
            return 0L;
        }

        return day.timeAt(day.size() - 1) + barMillis;
    }

    /** @return how far through the SESSION it is, ignoring the history */
    public double progress() {
        int playable = day.size() - origin;

        return playable <= 0 ? 1.0 : (size() - origin) / (double) playable;
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
                if (completed >= day.size()) {
                    // The session is over. Nothing left to hand out, and
                    // finished() says so from here on, which is what lets the
                    // transport stop its timer.
                    owed = 0;

                    return;
                }

                // No path for THIS bar, and none invented: the minute has no
                // recorded ticks and the reader has turned the synthetic ones
                // off. What happens next is written in startForming's own
                // comment -- "the bar then appears whole instead of forming" --
                // and this loop did not do it. It returned, owed zeroed, and
                // NOTHING moved again: the transport went on calling this
                // twenty-five times a second with the play icon lit, for as long
                // as the window stayed open, and only Stop got out. One minute
                // without ticks froze the replay for good, without a word.
                long whole = Math.max(1L, barMillis);

                if (owed < whole) {
                    return;
                }

                owed -= whole;
                completed = clamp(completed + 1);

                continue;
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

        if (path == null || path.length == 0) {
            // No path for this bar, and none invented. Happens where there are
            // no recorded ticks and the reader has turned the synthetic ones
            // off: the bar then appears whole instead of forming, which is the
            // honest picture of what is known about it.
            return false;
        }

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

    /**
     * @param fraction 0 for the session's open, 1 for its close
     *
     * <p>Measured over the SESSION, not over everything on screen: the scrubber
     * is the day being played, and history taking up nine tenths of it would
     * leave the whole replay squeezed into the last centimetre.</p>
     */
    public void seekFraction(double fraction) {
        if (!Double.isFinite(fraction)) {
            return;
        }

        double clamped = Math.max(0.0, Math.min(1.0, fraction));

        seek(origin + (int) Math.round(clamped * (day.size() - origin)));
    }

    /**
     * @return the instant the session would show on its clock right now
     *
     * <p><b>Inside the bar as well as between bars.</b> A bar carries the time
     * its bucket STARTS, so a clock that only read bar times would sit still
     * for a whole minute and jump — and anything driving off it would sit still
     * with it. That is what froze the tick renko: it asked what time it was,
     * got 09:02 for sixty seconds of market, and had nothing new to lay.</p>
     *
     * <p>The forming bar knows how far along its own path it is, so the answer
     * is the bucket's start plus that fraction of the bar.</p>
     */
    public long clock() {
        if (day.size() == 0) {
            return 0L;
        }

        // Before anything arrives, the session's opening time -- not 1970, which
        // is what a bare zero would render as while the reader decides whether
        // to press play.
        if (size() == 0) {
            return day.timeAt(0);
        }

        long start = day.timeAt(size() - 1);

        if (path == null) {
            // NOTHING IS FORMING, so the clock stands at the END of the last bar
            // revealed, not at its start. size() counts the forming bar, so the
            // moment one completes the count drops by one and this line starts
            // reading the bar BEFORE -- and returning its start sent the clock
            // backwards by almost a whole bar, once per bar, for the whole
            // session. Anything reading the clock to decide what had happened
            // yet -- the tick renko does exactly that -- saw time undo itself.
            return start + barMillis;
        }

        if (path.length <= 1) {
            return start;
        }

        return start + (long) ((double) cursor / path.length * barMillis);
    }

    private int clamp(int bar) {
        return Math.max(origin, Math.min(bar, day.size()));
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
