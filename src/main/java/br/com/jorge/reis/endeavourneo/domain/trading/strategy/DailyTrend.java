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

import java.util.List;

/**
 * Which way the daily average was leaning, using only sessions already closed.
 *
 * <p>The filter that turns the opening range from "a break happened" into "a
 * break happened the way the market has been going". It is also the single
 * place in this strategy where reading one day too many would make every number
 * downstream a fiction, so it is worth having on its own.</p>
 *
 * <h2>Two closes back, not one</h2>
 *
 * <pre>
 *     slope(t) = EMA10(t-1) − EMA10(t-2)
 * </pre>
 *
 * <p>The decision for session {@code t} is taken while {@code t} is still
 * happening, so {@code t}'s own close does not exist yet — and {@code t-1} is
 * the most recent one that does. Using {@code EMA10(t) − EMA10(t-1)} is the
 * mistake this shape exists to make impossible: it reads the close of the very
 * day being traded, every strategy built on it looks wonderful, and nothing on
 * screen says why.</p>
 */
final class DailyTrend {

    static final int PERIOD = 10;

    /** Fewer closes than this and the average is still finding its feet. */
    static final int WARM_UP = 10;

    /**
     * The least warm-up the shape itself needs: the slope reads t−1 and t−2.
     *
     * <p>The EMA is seeded on the first close and has a value from the first
     * session, so a slope exists from the third. It is a <b>noisy</b> slope —
     * ten sessions is what makes it mean something — and this floor is not an
     * opinion that it does. It is the point below which there is no slope at
     * all, offered for the runs where the alternative is not trading.</p>
     */
    static final int LEAST_WARM_UP = 1;

    private DailyTrend() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param series   the bars
     * @param sessions the sessions, chronological
     * @return one sign per session: {@code +1}, {@code -1}, or {@code 0} for
     *         "flat, or not enough history" — and zero means do not trade
     */
    static int[] directions(PriceSeries series, List<OpeningRange.Session> sessions) {
        return directions(series, sessions, WARM_UP);
    }

    /**
     * @param warmUp closes to let go by before the slope is trusted
     * @see #directions(PriceSeries, List)
     *
     * <p>The warm-up is counted from the start of the SERIES the strategy was
     * given, and a recorte is a series of its own: a week of it holds five
     * sessions, all five fall inside a ten-session warm-up, and the filter
     * refuses every one of them. Nothing on the screen says that — the run
     * comes back with no operations and the strategy looks broken.</p>
     */
    static int[] directions(PriceSeries series, List<OpeningRange.Session> sessions,
                            int warmUp) {

        int[] leaning = new int[sessions.size()];

        double[] average = new double[sessions.size()];
        double weight = 2.0 / (PERIOD + 1);
        double running = Double.NaN;

        for (int day = 0; day < sessions.size(); day++) {
            double close = series.closeAt(sessions.get(day).last());

            running = Double.isNaN(running) ? close : running + weight * (close - running);
            average[day] = running;
        }

        for (int day = 0; day < sessions.size(); day++) {
            // t-1 and t-2, and the warm-up counted from the start of the series.
            if (day < Math.max(LEAST_WARM_UP, warmUp) + 1) {
                continue;
            }

            double slope = average[day - 1] - average[day - 2];

            leaning[day] = (int) Math.signum(slope);
        }

        return leaning;
    }
}
