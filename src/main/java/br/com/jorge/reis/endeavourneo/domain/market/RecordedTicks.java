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

import java.time.Instant;
import java.time.LocalDate;

/**
 * The path a bar really took, from the exchange's own ticks.
 *
 * <p>Falls back to a synthetic walk for any bar it has no ticks for, which is
 * most of them: the base runs from 2018 to 2026 and the tick export covers one
 * month. The fallback can be refused — see the constructor — for a reader who
 * would rather see nothing than see a guess.</p>
 *
 * <p><b>The ticks are not waited for.</b> {@link TickLibrary#at} answers with
 * what is in memory and nothing else, because this is called while the chart is
 * painting. A session still loading therefore draws synthetic for a moment and
 * then the real thing, which is the right trade: a quarter-second freeze in the
 * middle of an animation is worse than a quarter-second of approximation.</p>
 */
public final class RecordedTicks implements TickPath {

    private static final long ASSUMED_BAR = 60_000L;

    private final TickLibrary library;

    private final TickPath fallback;

    /**
     * @param fallback what to use where there are no ticks, or null to refuse
     */
    public RecordedTicks(TickLibrary library, TickPath fallback) {
        this.library = library;
        this.fallback = fallback;
    }

    /** @return whether that bar can be drawn from real ticks right now */
    public boolean isRecorded(PriceSeries series, int index) {
        if (index < 0 || index >= series.size()) {
            return false;
        }

        return library.at(dayOf(series.timeAt(index))) != null;
    }

    @Override
    public double[] pathFor(PriceSeries series, int index) {
        return timedPathFor(series, index).prices();
    }

    /**
     * @return the recorded trades of that bar, each with the instant it printed
     *
     * <p><b>One pass, so the two can never disagree.</b> Asking for the prices
     * and then for the times would scan the session twice, and the library loads
     * on another thread -- a session that arrived between the two calls would
     * hand back prices from one answer and stamps from the other.</p>
     */
    @Override
    public Timed timedPathFor(PriceSeries series, int index) {
        if (index < 0 || index >= series.size()) {
            return fallen(series, index);
        }

        long from = series.timeAt(index);
        TickSeries ticks = library.at(dayOf(from));

        if (ticks == null) {
            return fallen(series, index);
        }

        long to = endOf(series, index, from);
        int first = firstAtOrAfter(ticks, from);
        int count = 0;

        for (int i = first; i < ticks.size() && ticks.timeAt(i) < to; i++) {
            if (ticks.hasLast(i) && ticks.lastAt(i) > 0) {
                count++;
            }
        }

        if (count < 2) {
            // A bar the export has no trades for. It happens at the edges of a
            // session and on the days the exchange barely opened. One price is
            // not a path, and pretending otherwise would freeze the animation
            // on that bar.
            return fallen(series, index);
        }

        double[] traded = new double[count];
        long[] stamps = new long[count];
        int at = 0;

        for (int i = first; i < ticks.size() && ticks.timeAt(i) < to; i++) {
            // Above zero: a session's opening row states zero for everything,
            // and animating a bar down to zero and back would be a spike no
            // trade made. See TickBars for what this cost.
            if (ticks.hasLast(i) && ticks.lastAt(i) > 0) {
                stamps[at] = ticks.timeAt(i);
                traded[at++] = ticks.lastAt(i);
            }
        }

        return bracketed(traded, stamps, from, to,
                series.openAt(index), series.closeAt(index));
    }

    /** @return what the fallback makes of that bar, or nothing when it is refused */
    private Timed fallen(PriceSeries series, int index) {
        return fallback == null ? new Timed(null, null)
                : fallback.timedPathFor(series, index);
    }

    /**
     * @return the trades with the bar's own open in front and its close behind
     *
     * <p>What {@link TickPath#pathFor} publishes: <b>opening price first and
     * closing price last</b>. The recorded trades do not honour that on their
     * own and have no reason to -- the candles are folded from the minute base
     * and the ticks come from a separate export, two sources that need not
     * agree on the ends of a minute. {@link SyntheticTicks} does honour it,
     * so the two implementations of one interface disagreed about the contract
     * the interface exists to state.</p>
     *
     * <p>Visible: the forming bar animated up to the last recorded trade and
     * then SNAPPED to the stored close the instant it completed -- once a bar,
     * all session, on exactly the days whose ticks we have.</p>
     *
     * <p>Bracketed rather than overwritten. Replacing the first and last trades
     * would throw two real prices away, and either of them can be the bar's
     * high or its low.</p>
     */
    private static Timed bracketed(double[] traded, long[] stamps, long from, long to,
            double open, double close) {
        boolean ahead = Double.isFinite(open) && traded[0] != open;
        boolean behind = Double.isFinite(close) && traded[traded.length - 1] != close;

        if (!ahead && !behind) {
            return new Timed(traded, stamps);
        }

        double[] path = new double[traded.length + (ahead ? 1 : 0) + (behind ? 1 : 0)];
        long[] when = new long[path.length];
        int at = 0;

        if (ahead) {
            // The bar's own open, at the bar's own start: it is the price the
            // minute began at, whatever the first recorded trade says.
            path[at] = open;
            when[at++] = from;
        }

        System.arraycopy(traded, 0, path, at, traded.length);
        System.arraycopy(stamps, 0, when, at, stamps.length);

        at += traded.length;

        if (behind) {
            // And the close on the bar's last instant. Not on the last trade's:
            // the minute is not over until it is over.
            path[at] = close;
            when[at] = Math.max(to - 1, stamps[stamps.length - 1]);
        }

        return new Timed(path, when);
    }

    /**
     * @return where the bar ends
     *
     * <p>The next bar's opening instant. For the last bar there is none, so the
     * width of the one before is used — and a minute when even that is missing.
     * Guessing long would pull the next bar's ticks into this one; guessing
     * short would cut the bar's own tail off.</p>
     */
    private static long endOf(PriceSeries series, int index, long from) {
        if (index + 1 < series.size()) {
            return series.timeAt(index + 1);
        }

        if (index > 0) {
            return from + Math.max(1L, from - series.timeAt(index - 1));
        }

        return from + ASSUMED_BAR;
    }

    /** @return the first tick at or after that instant */
    private static int firstAtOrAfter(TickSeries ticks, long when) {
        int low = 0;
        int high = ticks.size() - 1;
        int found = ticks.size();

        while (low <= high) {
            int middle = (low + high) >>> 1;

            if (ticks.timeAt(middle) >= when) {
                found = middle;
                high = middle - 1;
            } else {
                low = middle + 1;
            }
        }

        return found;
    }

    /**
     * @return which session that instant belongs to
     *
     * <p><b>The exchange's zone, asked for once and never held.</b> This used to
     * carry a zone of its own, handed in at construction, and it decided only
     * WHICH FILE to open: the session inside computed its own midnight in the
     * machine's zone regardless. On a machine set to anything but the market's
     * the two disagreed, so a bar was matched to a session whose ticks were
     * hours away from it, the window {@code [from, to)} caught none of them,
     * and every bar fell through to the synthetic walk -- silently, because
     * "no ticks for this bar" is a normal answer.</p>
     *
     * <p>One zone now, {@link Timeframe#defaultZone}, read by this and by the
     * session alike. Two places that must agree cannot be given two answers.</p>
     */
    private static LocalDate dayOf(long when) {
        return Instant.ofEpochMilli(when).atZone(Timeframe.defaultZone()).toLocalDate();
    }
}
