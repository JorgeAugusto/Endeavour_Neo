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
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

/**
 * The loop: one strategy over one series.
 *
 * <p>Four lines of it matter, and they are in this order for a reason:</p>
 *
 * <pre>
 *   for each bar:
 *       1. execute what was resting from the last close
 *       2. show the strategy this bar, now closed
 *       3. let it ask for what it wants
 *       4. rebuild the book from what it asked
 * </pre>
 *
 * <p><b>Step 1 comes before step 2.</b> The strategy never sees the bar its own
 * orders executed on before they execute — it decides at a close, and the
 * earliest anything can happen is the next open. Swapping these two lines is the
 * whole of look-ahead: it lets a strategy place an order knowing the bar it will
 * fill on, and every strategy becomes profitable.</p>
 *
 * <h2>The run is a function of the data</h2>
 *
 * <p>Nothing survives between runs. Two runs over the same series with the same
 * strategy produce the same fills, in the same order, at the same prices —
 * which is what lets the chart's marks, the replay and the report all agree
 * about what happened. It is also why the same series sliced in two and run
 * separately gives exactly the two halves of the whole, which is what the
 * walk-forward test is going to depend on.</p>
 */
public final class Backtest {

    private final Costs costs;

    private final int lot;

    /**
     * @param costs what a round trip is charged
     * @param lot   the default quantity: NTSL's "Quantity per Order"
     */
    public Backtest(Costs costs, int lot) {
        if (costs == null) {
            throw new IllegalArgumentException("a backtest without a cost is not a measurement");
        }

        this.costs = costs;
        this.lot = lot;
    }

    /**
     * Runs the strategy over every bar of the series.
     *
     * @param series what to run over
     * @param strategy what to run
     * @return the trades, the fills and what they cost
     */
    public Result run(PriceSeries series, Strategy strategy) {
        return run(series, strategy, Watching.NOBODY);
    }

    /**
     * @param watching told how far along the run is, occasionally
     * @see #run(PriceSeries, Strategy)
     */
    public Result run(PriceSeries series, Strategy strategy, Watching watching) {
        Watching told = watching == null ? Watching.NOBODY : watching;

        // ONE REPORT IN TWO HUNDRED BARS, at the most. A month of ticks is
        // seventeen million of them and a bar on screen repaints sixty times a
        // second: reporting every one would be seventeen million calls to draw
        // the same two hundred pictures. The cadence is the engine's to pick
        // because only the engine knows how many bars there are -- a caller
        // choosing it would have to guess, and would guess per strategy.
        int every = Math.max(1, series.size() / 200);

        Broker broker = new Broker(costs);
        Desk desk = new Desk(lot);
        Market market = new Market(series, broker.position(), broker.book(),
                broker.liveFills());

        double[] worth = new double[series.size()];
        int exposed = 0;

        strategy.start(series);

        for (int bar = 0; bar < series.size(); bar++) {
            broker.executeDuring(bar, series.openAt(bar), series.highAt(bar), series.lowAt(bar));

            market.at(bar);
            desk.clear();
            strategy.onBar(market, desk);

            broker.book().reconcile(desk.instructions());

            // AFTER the bar's executions and the strategy's turn, marked to the
            // close. This is the only place the hole inside a position that is
            // still open ever shows up -- the balance curve moves only when a
            // trade ends, and a position bleeding for three days looks like a
            // flat line on it.
            worth[bar] = broker.worthAt(series.closeAt(bar));

            if (!broker.position().flat()) {
                exposed++;
            }

            if (bar % every == 0) {
                told.at(bar + 1, series.size());
            }
        }

        // AND ONCE AT THE END, whatever the cadence landed on. Without this a
        // run of 999 bars reporting every fifth stops at 996, and the bar sits
        // just short of full while the window says it has finished -- which is
        // exactly the moment a reader looks at it.
        told.at(series.size(), series.size());

        double last = series.size() == 0 ? Double.NaN : series.closeAt(series.size() - 1);

        return new Result(broker.trades(), broker.fills(), broker.ambiguousBars(), costs,
                broker.position().net(),
                broker.position().flat() ? 0 : broker.position().openResult(last),
                worth, exposed);
    }
}
