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

import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

/**
 * The oldest strategy there is: two averages, and you follow the crossing.
 *
 * <p>Fast above slow, be long. Fast below slow, be short. Always in the market,
 * flipping at every cross. It is here as the <b>benchmark</b>, not as a
 * candidate — it is the thing every other strategy has to beat, and the thing
 * that proves the engine runs on real data instead of on fixtures.</p>
 *
 * <h2>What it is expected to do, written before it ran</h2>
 *
 * <p>Lose, and lose in a specific way: many trades, each one clearing less than
 * it costs. The WIN has enormous memory in the <i>size</i> of a move and none in
 * its <i>direction</i> — reversal is worth 0,87 points against a round trip that
 * costs 6,5. A crossing strategy pays the round trip on every flip to collect a
 * direction that is not there.</p>
 *
 * <p>Writing the prediction down first is the point. A benchmark that surprises
 * you is either a discovery or a bug, and there is no way to tell which if you
 * had no expectation.</p>
 *
 * <h2>The inversion is two orders, not one</h2>
 *
 * <p>{@code ReversePosition} exists in the language, and this does not use it.
 * The note from the port to the Profit records that an inversion is done as two
 * orders, and {@code RoboRenko11} keeps a backup named for the experiment of
 * trusting the single one. {@code ClosePosition} and then an opening order is
 * what his robots do, so it is what the benchmark does.</p>
 */
public final class MovingAverageCrossing implements Strategy {

    private final int fastPeriod;

    private final int slowPeriod;

    private final int size;

    private double fast = Double.NaN;

    private double slow = Double.NaN;

    private boolean above;

    private boolean known;

    /**
     * @param fastPeriod  bars of the fast average, his 17
     * @param slowPeriod  bars of the slow one, his 34
     * @param size        contracts per position
     */
    public MovingAverageCrossing(int fastPeriod, int slowPeriod, int size) {
        if (fastPeriod < 1 || slowPeriod <= fastPeriod) {
            throw new IllegalArgumentException(
                    "the fast average has to be faster: " + fastPeriod + " and " + slowPeriod);
        }

        if (size < 1) {
            throw new IllegalArgumentException("a position of " + size + " contracts is not a position");
        }

        this.fastPeriod = fastPeriod;
        this.slowPeriod = slowPeriod;
        this.size = size;
    }

    /** His periods, one contract. */
    public static MovingAverageCrossing his() {
        return new MovingAverageCrossing(17, 34, 1);
    }

    @Override
    public void start() {
        fast = Double.NaN;
        slow = Double.NaN;
        above = false;
        known = false;
    }

    @Override
    public void onBar(Market market, Desk desk) {
        double close = market.close();

        fast = step(fast, close, fastPeriod);
        slow = step(slow, close, slowPeriod);

        // The averages need to have separated before a crossing means anything.
        // Seeded from the same first close, they start equal, and the first bar
        // that moves would otherwise read as a cross.
        if (market.bar() < slowPeriod) {
            return;
        }

        boolean nowAbove = fast > slow;

        if (!known) {
            known = true;
            above = nowAbove;

            return;
        }

        if (nowAbove == above) {
            return;
        }

        above = nowAbove;

        if (market.hasPosition()) {
            desk.closePosition();
        }

        if (nowAbove) {
            desk.buyAtMarket(size);
        } else {
            desk.sellShortAtMarket(size);
        }
    }

    /** @return the exponential average, seeded from the first close it sees */
    private static double step(double average, double close, int period) {
        if (Double.isNaN(average)) {
            return close;
        }

        double weight = 2.0 / (period + 1);

        return average + weight * (close - average);
    }

    @Override
    public String toString() {
        return "cruzamento EMA " + fastPeriod + "/" + slowPeriod + ", " + size + " contrato(s)";
    }
}
