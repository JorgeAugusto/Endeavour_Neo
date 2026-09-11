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
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything one session is, before anybody decides whether to trade it.
 *
 * <p>Split out of {@link RangeBreakout} because it is the half that is pure
 * arithmetic over the series and can be checked on its own: where a session
 * begins, what the first ninety minutes covered, which way the first break went,
 * and what one contract would have made at each candidate target. The strategy
 * reads these and decides; nothing here decides anything.</p>
 *
 * <h2>Ninety minutes of clock, from the first bar there is</h2>
 *
 * <p>Not from 09:00, and not ninety records. The specification is explicit and
 * the reason is in the data: there are sessions that start late, and counting
 * records instead of minutes gives a shorter range on the days a bar is missing
 * — which are exactly the days the market was doing something.</p>
 */
final class OpeningRange {

    /** Points beyond the range that count as a break, from the specification. */
    static final double TICK = 5;

    static final int FORMATION_MINUTES = 90;

    /** After this, an entry is refused — the day has stopped being an opening. */
    static final LocalTime LAST_ENTRY = LocalTime.of(17, 30);

    /**
     * One session, already measured.
     *
     * @param day          the date, in the exchange's zone
     * @param first        index of its first bar
     * @param last         index of its last bar
     * @param formedAt     when the formation window closes, in epoch millis
     * @param high         the highest high of the formation
     * @param low          the lowest low of it
     * @param breakBar     the bar of the first break, or -1 if there was none
     * @param side         {@code +1} up, {@code -1} down, {@code 0} none or both
     * @param entry        where that break would have been taken
     * @param stop         the other side of the range
     * @param afterMinutes minutes from the formation to the break
     */
    record Session(LocalDate day, int first, int last, long formedAt,
                   double high, double low, int breakBar, int side,
                   double entry, double stop, long afterMinutes) {

        /** @return the initial risk, in points */
        double risk() {
            return Math.abs(entry - stop);
        }

        /** @return whether a first break exists at all */
        boolean broke() {
            return breakBar >= 0 && side != 0;
        }
    }

    private OpeningRange() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Walks the series once and measures every session in it.
     *
     * @param series the bars, in order
     * @param zone   the exchange's zone, which decides where a session begins
     * @return one entry per session, chronological
     */
    static List<Session> of(PriceSeries series, ZoneId zone) {
        return of(series, zone, FORMATION_MINUTES);
    }

    /**
     * @param formation how many minutes of clock the range is built from
     * @see #of(PriceSeries, ZoneId)
     */
    static List<Session> of(PriceSeries series, ZoneId zone, int formation) {
        List<Session> found = new ArrayList<>();

        if (series == null || series.size() == 0) {
            return found;
        }

        ZoneId at = zone == null ? Timeframe.defaultZone() : zone;

        int start = 0;
        LocalDate day = dateOf(series, 0, at);

        for (int bar = 1; bar <= series.size(); bar++) {
            LocalDate now = bar == series.size() ? null : dateOf(series, bar, at);

            if (now == null || !now.equals(day)) {
                found.add(measure(series, start, bar - 1, day, at, formation));

                start = bar;
                day = now;
            }
        }

        return found;
    }

    private static LocalDate dateOf(PriceSeries series, int bar, ZoneId at) {
        return Instant.ofEpochMilli(series.timeAt(bar)).atZone(at).toLocalDate();
    }

    private static Session measure(PriceSeries series, int first, int last,
                                   LocalDate day, ZoneId at, int formation) {
        long formedAt = series.timeAt(first) + formation * 60_000L;

        double high = Double.NEGATIVE_INFINITY;
        double low = Double.POSITIVE_INFINITY;
        int after = first;

        while (after <= last && series.timeAt(after) < formedAt) {
            high = Math.max(high, series.highAt(after));
            low = Math.min(low, series.lowAt(after));
            after++;
        }

        if (after > last || high == Double.NEGATIVE_INFINITY) {
            // The session ended inside its own formation. Nothing to trade, and
            // no range worth quoting either.
            return new Session(day, first, last, formedAt, Double.NaN, Double.NaN,
                    -1, 0, Double.NaN, Double.NaN, 0);
        }

        return firstBreak(series, first, last, day, at, formedAt, high, low, after);
    }

    private static Session firstBreak(PriceSeries series, int first, int last, LocalDate day,
                                      ZoneId at, long formedAt, double high, double low,
                                      int from) {
        double up = high + TICK;
        double down = low - TICK;

        for (int bar = from; bar <= last; bar++) {
            LocalTime clock = Instant.ofEpochMilli(series.timeAt(bar)).atZone(at).toLocalTime();

            if (!clock.isBefore(LAST_ENTRY)) {
                break;
            }

            boolean rose = series.highAt(bar) >= up;
            boolean fell = series.lowAt(bar) <= down;

            if (rose && fell) {
                // AMBIGUOUS, and refused rather than guessed: one minute of OHLC
                // cannot say which side went first, and picking one would decide
                // the direction of the whole day by a coin this class would be
                // hiding.
                return new Session(day, first, last, formedAt, high, low, -1, 0,
                        Double.NaN, Double.NaN, 0);
            }

            if (rose || fell) {
                int side = rose ? 1 : -1;
                double trigger = rose ? up : down;
                double entry = rose
                        ? Math.max(series.openAt(bar), trigger)
                        : Math.min(series.openAt(bar), trigger);

                return new Session(day, first, last, formedAt, high, low, bar, side,
                        entry, rose ? low : high,
                        (series.timeAt(bar) - formedAt) / 60_000L);
            }
        }

        return new Session(day, first, last, formedAt, high, low, -1, 0,
                Double.NaN, Double.NaN, 0);
    }

    /**
     * What one contract would have made on that session, at that target.
     *
     * <p>The figure the walk-forward selector scores, and deliberately a cruder
     * simulation than the strategy itself: one contract, no pullbacks, no
     * partials. It exists to rank two targets against each other, and a ranking
     * built from the full machinery would be ranking the machinery.</p>
     *
     * <p><b>Stop before target</b> when a single minute holds both — the same
     * tie-break the engine makes, and for the same reason: it is the
     * conservative side rather than the true one.</p>
     *
     * @param series   the bars
     * @param session  the session, which must have broken
     * @param targetR  the target, as a multiple of the initial risk
     * @param costBrl  what one contract costs, round trip
     * @param perPoint reais per point per contract
     * @return the net result in reais
     */
    static double hypothetical(PriceSeries series, Session session, double targetR,
                               double costBrl, double perPoint) {
        double risk = session.risk();
        int side = session.side();
        double target = session.entry() + side * targetR * risk;

        for (int bar = session.breakBar(); bar <= session.last(); bar++) {
            boolean stopped = side > 0
                    ? series.lowAt(bar) <= session.stop()
                    : series.highAt(bar) >= session.stop();

            if (stopped) {
                return (session.stop() - session.entry()) * side * perPoint - costBrl;
            }

            boolean made = side > 0
                    ? series.highAt(bar) >= target
                    : series.lowAt(bar) <= target;

            if (made) {
                return (target - session.entry()) * side * perPoint - costBrl;
            }
        }

        return (series.closeAt(session.last()) - session.entry()) * side * perPoint - costBrl;
    }
}
