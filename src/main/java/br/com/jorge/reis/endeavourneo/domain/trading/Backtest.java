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
        return run(series, series, strategy, watching);
    }

    /**
     * Runs a strategy that <b>decides on one series and executes on another</b>.
     *
     * <h2>Why the two are separate</h2>
     *
     * <p>They answer different questions, and tying them together makes one of
     * the two lie:</p>
     *
     * <ul>
     *   <li><b>{@code decided}</b> is the scale the strategy reads. An EMA 17
     *       that does not name its own scale is seventeen of <i>these</i> bars —
     *       which is the whole meaning of "the chart's scale" — and the range,
     *       the pullback and every other rule are read here too.</li>
     *   <li><b>{@code executed}</b> is how finely the orders fill. Over a tick
     *       path a stop and a target inside the same bar are settled by whichever
     *       price came first, and several lots can come out one after another
     *       inside one bar. Over the decision bars themselves, at most one cover
     *       fills per bar and the tie is broken by a rule.</li>
     * </ul>
     *
     * <p>Running both on the executed series is what an earlier version did, and
     * it made a strategy's "EMA 17" mean <b>seventeen ticks</b> — under a second
     * of market — the moment the reader chose tick execution. Nothing on screen
     * said so.</p>
     *
     * <h2>The strategy is asked once per decision bar</h2>
     *
     * <p>At the close of it, never in the middle: the orders it leaves standing
     * are executed against every executed bar of the <i>next</i> decision bar.
     * So a decision still cannot see the bar it trades on, whichever pair of
     * series this is given.</p>
     *
     * @param executed what the orders fill against, finest
     * @param decided  what the strategy reads, coarsest; the same series when
     *                 there is no distinction to make
     */
    public Result run(PriceSeries executed, PriceSeries decided, Strategy strategy,
                      Watching watching) {
        PriceSeries series = executed;
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
        Market market = new Market(decided, broker.position(), broker.book(),
                broker.liveFills());

        // ONE POINT PER DECISION BAR, not per executed bar.
        //
        // It is the honest axis -- the curve is drawn beside a chart of exactly
        // these bars -- and it is the difference between running and not: a year
        // of WIN in ticks is 194 MILLION bars, and one double each is a gigabyte
        // and a half of curve for a chart that draws 141.602 candles. It ran out
        // of heap before it ran out of patience.
        double[] worth = new double[decided.size()];
        int exposed = 0;

        strategy.start(decided);

        // WHICH DECISION BAR WE ARE INSIDE, walked in step rather than looked up.
        // Both series are in time order, so one cursor answers it for seventeen
        // million bars without an index of seventeen million entries.
        int at = 0;

        for (int bar = 0; bar < series.size(); bar++) {
            while (at + 1 < decided.size() && series.timeAt(bar) >= decided.timeAt(at + 1)) {
                at++;
            }

            // THE DECISION BAR IS WHAT A FILL IS STAMPED WITH, not the executed
            // one. The number a fill carries is the one every reader points at:
            // the candle on the chart, the row in the table, the step in the
            // balance curve. Which of four and a half million ticks it was is
            // the engine's own business, and carrying it outwards bought one
            // thing -- a translation at every use site -- and cost the curves,
            // which are drawn per bar and were comparing tick numbers against
            // candle numbers.
            broker.executeDuring(at, series.openAt(bar), series.highAt(bar), series.lowAt(bar));

            // THE LAST EXECUTED BAR OF A DECISION BAR, which is known by looking
            // at the next bar's CLOCK. That is not looking ahead: a timestamp is
            // not a price, and the strategy is handed no number from a bar that
            // has not closed.
            boolean closes = bar + 1 == series.size()
                    || (at + 1 < decided.size() && series.timeAt(bar + 1) >= decided.timeAt(at + 1));

            if (closes) {
                market.at(at);
                desk.clear();
                strategy.onBar(market, desk);

                broker.book().reconcile(desk.instructions());
                market.settled();

                // AFTER the bar's executions and the strategy's turn, marked to
                // the close. This is the only place the hole inside a position
                // that is still open ever shows up -- the balance curve moves
                // only when a trade ends, and a position bleeding for three days
                // looks like a flat line on it.
                worth[at] = broker.worthAt(series.closeAt(bar));

                if (!broker.position().flat()) {
                    exposed++;
                }
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
