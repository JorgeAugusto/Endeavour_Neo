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
public final class ReplaySeries implements PriceSeries, Untraded, Counted {

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

    /**
     * When each of those prices printed, or null when nobody knows.
     *
     * <p><b>Two rulers used to run in one frame.</b> The forming bar walked by
     * POSITION -- the k-th price, with the clock reading k/length of a minute --
     * while the tick renko, driven by that same clock, cut by the trade's real
     * stamp. Both read the same trades of the same minute. Where the trades are
     * not uniform, the two panels of one window disagreed about what had already
     * happened, and the open is exactly where that is worst and where a replay
     * is worth using.</p>
     *
     * <p>Null for an invented walk, which has no arrival times and can only
     * claim an even spread. See {@link TickPath#timedPathFor}.</p>
     */
    private long[] when;

    /** The market instant reached inside the forming bar, when it is stamped. */
    private long now;

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

        // THE MEDIAN OF THE FIRST HUNDRED GAPS, not the first one. The series is
        // not evenly spaced: a minute with no trade in it does not exist at all,
        // which is a rule this package states elsewhere in so many words. One
        // hole between bar 0 and bar 1 and a one-minute series measured itself
        // as two minutes -- and the first pair is the first pair of the first
        // session of HISTORY, which is the least examined stretch there is.
        //
        // Three silent effects came out of that: the scrubber labelled the wrong
        // end, every bar lasted twice as long as it should, and the clock walked
        // BACKWARDS at each bar boundary, because it had been pushed to
        // start + barMillis and the next bar's own stamp is nearer than that.
        //
        // The median rather than the minimum: a series folded to five minutes
        // whose first pair happens to be one minute apart would measure itself
        // as one, and then every bar would close leaving prices behind.
        int wanted = (int) Math.min(100L, day.size() - 1L);
        long[] gaps = new long[wanted];
        int found = 0;

        for (int bar = 0; bar < wanted; bar++) {
            long gap = day.timeAt(bar + 1) - day.timeAt(bar);

            if (gap > 0) {
                gaps[found++] = gap;
            }
        }

        if (found == 0) {
            return DEFAULT_BAR_MILLIS;
        }

        long[] positive = java.util.Arrays.copyOf(gaps, found);

        java.util.Arrays.sort(positive);

        return positive[found / 2];
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
        owed += Math.max(0L, millis);

        if (ticks == null) {
            // No tick generator: whole bars, which is what the chart got before
            // this existed -- THROUGH `owed`, like everything else.
            //
            // This used to be advance(millis / barMillis) on the raw argument,
            // which does the very thing the paragraph above calls fatal: at one
            // times speed a frame is forty milliseconds, 40 / 60_000 is zero,
            // and the remainder was dropped. Called frame after frame it never
            // advanced at all: a series built with no generator -- which is what
            // the two-argument constructor makes, and what its own javadoc
            // describes as "null to jump bar by bar" -- sat still for ever with
            // the play icon lit. The same symptom the long comment below
            // describes as already fixed in the other branch.
            long whole = Math.max(1L, barMillis);
            int bars = (int) (owed / whole);

            owed -= (long) bars * whole;

            if (bars > 0) {
                // advance() clears `owed` on purpose -- a jump to a bar boundary
                // leaves no half bar behind -- so what is left over is put back
                // after it.
                long left = owed;

                advance(bars);

                owed = left;
            }

            return;
        }

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

            if (when != null) {
                if (!spendStamped()) {
                    return;
                }

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

    /**
     * Lets the clock run inside a STAMPED bar, taking the prices it passes.
     *
     * @return whether there is market time left to go on spending
     *
     * <p>The clock moves first and the prices follow it, which is the whole
     * correction: the bar shows what had printed BY that instant, and the tick
     * renko asked the same question of the same instant gets the same answer.
     * The clock is not allowed past the end of the minute, so a burst of trades
     * cannot finish the bar early and a quiet stretch cannot hold it open.</p>
     */
    private boolean spendStamped() {
        long start = day.timeAt(completed);
        long end = start + barMillis;
        long step = Math.min(owed, Math.max(0L, end - now));

        now += step;
        owed -= step;

        while (cursor + 1 < path.length && when[cursor + 1] <= now) {
            take(path[++cursor]);
        }

        if (now >= end) {
            // The minute is over. The bar becomes history exactly as it is
            // stored, so nothing invented survives into the finished chart --
            // and it ends when the MINUTE ends, not when its last trade printed.
            completed++;
            path = null;
            when = null;

            return true;
        }

        return owed > 0;
    }

    private void take(double price) {
        high = Math.max(high, price);
        low = Math.min(low, price);
        close = price;
    }

    private boolean startForming() {
        if (completed >= day.size()) {
            return false;
        }

        TickPath.Timed timed = ticks.timedPathFor(day, completed);

        path = timed.prices();
        when = timed.when();
        now = day.timeAt(completed);

        if (path == null || path.length == 0) {
            // BOTH of them. This cleared `when` and left `path` pointing at the
            // empty array, and everything downstream reads `path != null` as
            // "there is a bar forming": size() counted a bar that does not
            // exist, forming(completed) went true, and high/low/close answered
            // with the PREVIOUS bar's numbers. The next call divided the frame
            // by path.length -- by zero.
            //
            // Unreachable today, because no generator here returns an empty
            // array; the defect is the guard leaving the object in a state its
            // own readers cannot make sense of, and the barrier hiding that is a
            // property of two other classes rather than of this one.
            path = null;
            when = null;

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
            when = null;

            return;
        }

        take(path[cursor]);
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
        when = null;
        owed = 0;
        completed = clamp(before + Math.max(0, bars));

        return size() - before;
    }

    public void seek(int bar) {
        path = null;
        when = null;
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

        if (when != null) {
            // THE REAL INSTANT, for a bar whose prices carry one. This used to
            // be the position-based estimate below for every bar, which is what
            // put the candle and the renko on two different rulers.
            return now;
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

        // Over path.length - 1, because that is as far as the cursor goes: the
        // stamped branch loops while `cursor + 1 < path.length` and the other
        // closes the bar as soon as `cursor >= path.length`. Dividing by the
        // whole length made the last frame before a bar closed show a little
        // less volume than the bar has, and then jump to the total -- 12% short
        // at the floor of eight steps.
        int steps = Math.max(1, path.length - 1);

        return Double.isFinite(whole) ? whole * cursor / (double) steps : Double.NaN;
    }

    /**
     * @return whether nothing traded inside that bar
     *
     * <p><b>Carried, not dropped.</b> The other three envelopes -- {@code
     * ConcatSeries}, {@code SegmentedSeries} and the join inside {@code
     * SeriesMerge} -- pass these two questions through to what they wrap, and
     * this one answered "does not know" for every bar. Today what it wraps is
     * a day of minutes, which cannot answer either; the moment a renko is
     * played back it would take the knowledge out of the middle of the screen
     * with nothing said.</p>
     *
     * <p><b>The bar being formed says nothing</b>, and that is not a shortcut.
     * Whether anything traded inside it is decided by trades that have not
     * arrived yet, so answering from the whole bar would be reading the
     * future -- which is the one thing this class exists to prevent. False here
     * means "it does not know", which is what {@link Untraded} says false means
     * for every series that cannot answer.</p>
     */
    @Override
    public boolean untradedAt(int index) {
        return !forming(index) && Untraded.at(day, check(index));
    }

    /**
     * @return how many trades made that bar, or {@link Counted#UNKNOWN}
     *
     * <p>Unknown for the bar being formed, for the reason in {@link
     * #untradedAt}: the count of the whole bar is the count of trades that have
     * not all happened yet. {@code volumeAt} shows a SHARE of the bar's volume
     * instead, and a share of a count is not a count -- "seven and a half
     * trades" is not a thing to put on a screen.</p>
     */
    @Override
    public long tradesAt(int index) {
        return forming(index) ? Counted.UNKNOWN : Counted.at(day, check(index));
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
