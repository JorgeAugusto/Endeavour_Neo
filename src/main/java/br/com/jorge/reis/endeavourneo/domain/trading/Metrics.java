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
package br.com.jorge.reis.endeavourneo.domain.trading;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Set;

/**
 * What a run is worth, read off a {@link Result}.
 *
 * <p>Everything here is derived — nothing is measured that {@code Result} did
 * not already record. It exists so that the screen does not do arithmetic, and
 * so that the arithmetic has one place to be wrong in.</p>
 *
 * <h2>Two numbers that are the point of the whole class</h2>
 *
 * <p><b>The average length of a trade</b> decides whether a strategy can pay
 * for itself, and it decides it before any profit figure is worth reading. With
 * a round trip costing 6,5 points, a trade that lasts one bar has to be right
 * far more often than one that lasts fifteen. Without it in view nobody notices
 * which of the two regimes they are in.</p>
 *
 * <p><b>The break-even hit rate</b> is the same fact said out loud. Given what
 * an average win pays, what an average loss takes and what the round trip costs,
 * it is the hit rate that would leave the account exactly where it started:</p>
 *
 * <pre>
 *     p · win  −  (1 − p) · loss  −  cost  =  0
 *                             loss + cost
 *     p  =  ────────────────────────────────
 *                  win + loss
 * </pre>
 *
 * <p>Beside the real hit rate it turns "28,5%" into "28,5% against the 31,9% you
 * needed", which is a sentence rather than a number.</p>
 *
 * <h2>Gross where the formula needs gross</h2>
 *
 * <p>The break-even calculation subtracts the cost itself, so the win and loss
 * that feed it are <b>gross</b> — counting it twice would make the bar it sets
 * too high. The profit factor is on the <b>net</b>, because that is what is
 * actually kept. The two are different on purpose.</p>
 *
 * @param trades            how many operations ended
 * @param wins              how many of them kept money after costs
 * @param exposure          fraction of bars that ended holding something
 * @param barsHeld          average bars an operation lasted
 * @param tradesPerSession  operations per trading day
 * @param averageWin        average gross of the operations that made money
 * @param averageLoss       average gross of the ones that lost, as a positive number
 * @param breakEvenHitRate  the hit rate that would have broken even
 * @param profitFactor      net kept over net lost; infinite when nothing lost
 * @param longestLosingRun  most operations in a row that lost
 * @param buyAndHold        one contract held from the first open to the last close
 */
public record Metrics(int trades, int wins, double exposure, double barsHeld,
                      double tradesPerSession, double averageWin, double averageLoss,
                      double breakEvenHitRate, double profitFactor,
                      int longestLosingRun, double buyAndHold) {

    /** @return the fraction of operations that kept money, or NaN with none */
    public double hitRate() {
        return trades == 0 ? Double.NaN : (double) wins / trades;
    }

    /**
     * @return how far the hit rate is above what it needed to be
     *
     * <p>Negative means the strategy was right less often than it had to be.
     * It is the one number that says "this loses" without knowing anything
     * about the size of the account.</p>
     */
    public double edge() {
        return hitRate() - breakEvenHitRate;
    }

    /**
     * Reads a finished run.
     *
     * @param result what the run produced
     * @param series the bars it ran over, for the buy-and-hold reference
     * @return the derived figures
     */
    public static Metrics of(Result result, PriceSeries series) {
        int count = result.count();

        double wonGross = 0;
        double lostGross = 0;
        int won = 0;
        int lost = 0;
        double heldBars = 0;
        int run = 0;
        int longest = 0;
        double keptNet = 0;
        double lostNet = 0;

        for (Trade trade : result.trades()) {
            heldBars += trade.bars();

            if (trade.gross() >= 0) {
                wonGross += trade.gross();
                won++;
            } else {
                lostGross -= trade.gross();
                lost++;
            }

            if (trade.net() > 0) {
                keptNet += trade.net();
                run = 0;
            } else {
                lostNet -= trade.net();
                run++;
                longest = Math.max(longest, run);
            }
        }

        double averageWin = won == 0 ? 0 : wonGross / won;
        double averageLoss = lost == 0 ? 0 : lostGross / lost;
        double averageCost = count == 0 ? 0 : result.cost() / count;

        return new Metrics(count, result.wins(),
                result.exposure(),
                count == 0 ? Double.NaN : heldBars / count,
                perSession(result, series),
                averageWin, averageLoss,
                breakEven(averageWin, averageLoss, averageCost),
                lostNet <= 0 ? Double.POSITIVE_INFINITY : keptNet / lostNet,
                longest,
                buyAndHold(series));
    }

    private static double breakEven(double win, double loss, double cost) {
        double span = win + loss;

        return span <= 0 ? Double.NaN : (loss + cost) / span;
    }

    /**
     * @return operations per trading day, counting the days the series covers
     *
     * <p>Sessions and not calendar days: a series skips weekends and holidays,
     * and dividing by those would flatter every strategy by a third.</p>
     */
    private static double perSession(Result result, PriceSeries series) {
        int sessions = sessionsIn(series);

        return sessions == 0 ? Double.NaN : (double) result.count() / sessions;
    }

    private static int sessionsIn(PriceSeries series) {
        if (series == null || series.size() == 0) {
            return 0;
        }

        ZoneId zone = Timeframe.defaultZone();
        Set<LocalDate> days = new HashSet<>();

        for (int bar = 0; bar < series.size(); bar++) {
            days.add(Instant.ofEpochMilli(series.timeAt(bar)).atZone(zone).toLocalDate());
        }

        return days.size();
    }

    /**
     * The reference: buy at the first open, sell at the last close.
     *
     * <p><b>One contract</b>, and the screen says so. A strategy that ladders up
     * to six has no single size to match, and picking its peak or its average
     * would make the reference a function of the strategy — which is the one
     * thing a reference must not be.</p>
     *
     * <p>No cost is charged. One round trip over six years is noise against the
     * number, and leaving it out keeps the reference something anyone can check
     * by subtracting two prices.</p>
     *
     * @return points
     */
    private static double buyAndHold(PriceSeries series) {
        if (series == null || series.size() < 2) {
            return Double.NaN;
        }

        return series.closeAt(series.size() - 1) - series.openAt(0);
    }
}
