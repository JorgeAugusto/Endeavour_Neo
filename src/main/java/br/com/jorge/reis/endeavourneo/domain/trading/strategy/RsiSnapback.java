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

import br.com.jorge.reis.endeavourneo.domain.indicator.Rsi;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Plotted;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The IFR2, as the TradingView model of it is published.
 *
 * <p>Ported from the description of the script <i>IFR 2 Stormer RSI 2</i>,
 * which is closed source — so what is here is its published rule and not its
 * code. Transcribed whole, because every word of it matters:
 *
 * <blockquote>"Comprar quando IFR de 2 Períodos menor Oversold 'Sobrevendido' n
 * períodos no exemplo 10. E encerrar o trade quando o preço atingir a máxima
 * dos últimos 2 periodos. É usado também um filtro sma 200 períodos. Tudo pode
 * ser alterado IFR(RSI), Stop high n períodos, sma."</blockquote>
 *
 * <p>Which is:
 *
 * <ul>
 *   <li>buy when the two-period IFR closes below {@link #oversold()} — ten in
 *       the example;</li>
 *   <li>only with the close above a simple average of {@link #trend()} — two
 *       hundred in the example. Long only: there is no short side in the
 *       model;</li>
 *   <li>leave when the price reaches the HIGH OF THE LAST {@link #exitBars()}
 *       bars — two in the example. That is the "Stop high n períodos" of the
 *       parameter list;</li>
 *   <li>daily, preferentially. It reads the series it is given, so the run's
 *       scale is the setup's scale.</li>
 * </ul>
 *
 * <h2>There is NO loss stop, and that is the model and not an omission</h2>
 *
 * <p>The published rule names one exit and it is the target. Stormer's own
 * IFR2, as it is taught, carries a stop at 130% of the entry candle's range
 * projected downwards; this model does not carry it, and porting it in would be
 * porting something else. So a position here is held until the high of the last
 * two bars is reached — however long that takes and however far it falls first.
 *
 * <p>Read the drawdown of anything measured with this before reading its total.
 * A setup with a near target and no stop wins often and loses rarely and
 * enormously, and the average of those two is not what the win rate suggests.
 *
 * <h2>It holds across sessions, unlike everything else here</h2>
 *
 * <p>Every other strategy in this package is intraday and flattens at the bell.
 * This one is a swing setup: the position is meant to survive the night, and
 * closing it at the session end would be measuring a different rule. Nothing
 * here looks at the clock.
 *
 * <h2>Where the published rule is silent</h2>
 *
 * <p>It does not say WHEN the buy happens — at the close that made the signal,
 * or at the next open. This buys at market on the signal's close, which fills
 * at the next open: the engine cannot execute at a close that has already
 * happened, and pretending otherwise is the commonest way a backtest invents
 * money.
 *
 * <p>Which has a consequence worth stating rather than discovering. The exit
 * order is only sent once there is a position to cover — a cover against a flat
 * book is ignored, in NTSL and here — so the bar the entry fills on carries no
 * exit, and the earliest the target can be reached is the bar after that. If
 * the high of the last two is taken during the entry bar itself, this misses it
 * and waits. That is not a shortcut: it is what the robot does in the Profit,
 * for the same reason, and the same thing {@link MomentumCross} does.
 */
public final class RsiSnapback implements Strategy, Plotted {

    /** {@code Oversold}: ten, in the model's own example. */
    public static final double OVERSOLD = 10;

    /** {@code sma 200 períodos}. */
    public static final int TREND = 200;

    /** {@code Stop high n períodos}: the high of the last two. */
    public static final int EXIT_BARS = 2;

    private final Rsi rsi;

    private final double oversold;

    private final int trend;

    private final int exitBars;

    private final int lot;

    private PriceSeries bars = PriceSeries.empty();

    private int size;

    private double[] index = new double[0];

    private double[] average = new double[0];

    private double[] entryLine;

    private double[] targetLine;

    public RsiSnapback() {
        this(Rsi.SHORT, OVERSOLD, TREND, EXIT_BARS, 1);
    }

    /**
     * @param period   the IFR's period — two, in the model
     * @param oversold the level it must close below
     * @param trend    the simple average the close must be above
     * @param exitBars how many bars the exit's high is taken from
     * @param lot      contracts per entry
     */
    public RsiSnapback(int period, double oversold, int trend, int exitBars, int lot) {
        this.rsi = new Rsi(Math.max(1, period), Rsi.Smoothing.CLASSIC);
        this.oversold = oversold;
        this.trend = Math.max(1, trend);
        this.exitBars = Math.max(1, exitBars);
        this.lot = Math.max(1, lot);
    }

    @Override
    public void start(PriceSeries series) {
        bars = series == null ? PriceSeries.empty() : series;
        size = bars.size();

        index = rsi.over(bars);
        average = simpleAverage();

        entryLine = blank(size);
        targetLine = blank(size);
    }

    private static double[] blank(int many) {
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    /**
     * The simple average of the closes, one value per bar.
     *
     * <p>SIMPLE and not exponential, because the model says {@code sma}. The two
     * are different numbers and the difference is not cosmetic on a filter of
     * two hundred: an exponential average of that length reacts to the last
     * fortnight far more than a plain mean of two hundred does, so the days each
     * of them authorises are not the same days.</p>
     */
    private double[] simpleAverage() {
        double[] made = blank(size);
        double running = 0;

        for (int bar = 0; bar < size; bar++) {
            running += bars.closeAt(bar);

            if (bar >= trend) {
                running -= bars.closeAt(bar - trend);
            }

            if (bar >= trend - 1) {
                made[bar] = running / trend;
            }
        }

        return made;
    }

    @Override
    public void onBar(Market market, Desk desk) {
        if (size == 0) {
            return;
        }

        int bar = market.bar();
        int many = market.buyPositionQty();

        draw(bar, many > 0);

        if (many > 0) {
            // THE ONE EXIT THE MODEL NAMES, RE-SENT ON EVERY BAR -- the book is
            // rebuilt from what the strategy asks at each close, so an order
            // placed once and assumed to stay is no order from the next bar on.
            desk.sellToCoverLimit(highOfTheLast(bar), many);

            return;
        }

        if (buys(bar)) {
            // SENT ONCE, and only while flat. A market order does not rest, so
            // a strategy that kept asking would buy again every bar; and the
            // condition can hold for several closes in a row.
            desk.buyAtMarket(lot);
        }
    }

    /**
     * @param bar the bar that has just closed
     * @return whether the model says to buy on it
     */
    private boolean buys(int bar) {
        if (bar < 0 || bar >= size || Double.isNaN(index[bar]) || Double.isNaN(average[bar])) {
            return false;
        }

        return index[bar] < oversold && bars.closeAt(bar) > average[bar];
    }

    /**
     * @param bar the bar the order is being sent from
     * @return the highest high of the last {@link #exitBars()} CLOSED bars
     *
     * <p>Including this bar, which has closed: the order goes out at its close
     * and rests through the next one, and the high it aims at is the one a
     * reader would have measured at that moment.</p>
     */
    private double highOfTheLast(int bar) {
        double most = Double.NEGATIVE_INFINITY;

        for (int back = 0; back < exitBars && bar - back >= 0; back++) {
            most = Math.max(most, bars.highAt(bar - back));
        }

        return most;
    }

    private void draw(int bar, boolean holding) {
        if (holding) {
            targetLine[bar] = highOfTheLast(bar);
        } else if (buys(bar)) {
            entryLine[bar] = bars.closeAt(bar);
        }
    }

    public double oversold() {
        return oversold;
    }

    public int trend() {
        return trend;
    }

    public int exitBars() {
        return exitBars;
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("Entrada", entryLine);
        drawn.put("Alvo", targetLine);
        drawn.put("Média", average);

        return drawn;
    }

    @Override
    public String toString() {
        return "IFR 2";
    }
}
