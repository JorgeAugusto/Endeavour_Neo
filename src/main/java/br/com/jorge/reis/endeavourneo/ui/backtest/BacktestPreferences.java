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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.platform.Settings;

/**
 * How a run is charged and how big it trades.
 *
 * <p>These are settings and not controls on the command bar, and the difference
 * is worth naming: the bar says <b>what</b> to run — this series, this segment,
 * this strategy. These two say how the market treats it, and they should be the
 * same for every run until there is a reason to change them. A cost sitting in a
 * spinner beside the Run button is a cost that gets changed by accident, and a
 * cost changed by accident is a result that is wrong and looks ordinary.</p>
 *
 * <h2>The cost is kept in tenths of a point</h2>
 *
 * <p>{@link Settings} stores integers and strings. A string would have to be
 * parsed, and parsing a decimal means picking a locale — 6,5 and 6.5 are the
 * same number written by the same person on two different days. Tenths are
 * exact for every value the spinner can produce, and 65 in the file is
 * obviously 6,5 to anyone who opens it.</p>
 */
public final class BacktestPreferences {

    private static final Settings PREFS = Settings.settings();

    private static final String COST = "backtestCostTenths";

    private static final String CONTRACTS = "backtestContracts";

    private static final String POINT = "backtestPointCents";

    /** The measured cost of a round trip on the raw series, in tenths. */
    public static final int DEFAULT_COST_TENTHS = 65;

    /** A hundred points a round trip is already absurd; past that it is a typo. */
    public static final int MOST_COST_TENTHS = 1_000;

    public static final int DEFAULT_CONTRACTS = 1;

    public static final int MOST_CONTRACTS = 100;

    /** The WIN: twenty centavos per point per contract. */
    public static final int DEFAULT_POINT_CENTS = 20;

    /** The DOL is R$ 50; nothing on this exchange is worth more. */
    public static final int MOST_POINT_CENTS = 10_000;

    private BacktestPreferences() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return points charged for one contract in and out again */
    public static double cost() {
        return costTenths() / 10.0;
    }

    /** @return the cost as the engine wants it */
    public static Costs costs() {
        return new Costs(cost());
    }

    /** @return the stored value, in tenths of a point */
    public static int costTenths() {
        return clamp(PREFS.getInt(COST, DEFAULT_COST_TENTHS), 0, MOST_COST_TENTHS);
    }

    public static void setCostTenths(int tenths) {
        PREFS.putInt(COST, clamp(tenths, 0, MOST_COST_TENTHS));
    }

    /**
     * @return the default quantity of an order that does not state one
     *
     * <p>NTSL calls it "Quantity per Order", and it is the size a strategy gets
     * when it writes {@code BuyAtMarket} with no number.</p>
     */
    public static int contracts() {
        return clamp(PREFS.getInt(CONTRACTS, DEFAULT_CONTRACTS), 1, MOST_CONTRACTS);
    }

    public static void setContracts(int contracts) {
        PREFS.putInt(CONTRACTS, clamp(contracts, 1, MOST_CONTRACTS));
    }

    /**
     * What one point of the index is worth, in reais, per contract.
     *
     * <p><b>WIN 0,20 · IND 1,00 · WDO 10,00 · DOL 50,00.</b> It is a property of
     * the instrument and not of the strategy, and getting it wrong is not a
     * rounding error: running a WDO series at the WIN value is off by a factor
     * of fifty and every figure in reais is wrong while every figure in points
     * is right. That is why the quadro shows the value it used, beside the name
     * of the series it ran on.</p>
     *
     * @return reais per point per contract
     */
    public static double pointValue() {
        return pointCents() / 100.0;
    }

    /** @return the stored value, in centavos */
    public static int pointCents() {
        return clamp(PREFS.getInt(POINT, DEFAULT_POINT_CENTS), 1, MOST_POINT_CENTS);
    }

    public static void setPointCents(int cents) {
        PREFS.putInt(POINT, clamp(cents, 1, MOST_POINT_CENTS));
    }

    /** @return whether the stored value is the WIN's */
    public static boolean isTheMini() {
        return pointCents() == DEFAULT_POINT_CENTS;
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }
}
