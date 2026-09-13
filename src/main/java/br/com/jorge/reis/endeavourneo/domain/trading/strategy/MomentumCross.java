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

import br.com.jorge.reis.endeavourneo.domain.indicator.Pmo;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Market;
import br.com.jorge.reis.endeavourneo.domain.trading.Plotted;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Desk;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Trades the TNO's signal: in at the crossing, out at the candle's extreme or 2R.
 *
 * <p>The rule, whole:
 *
 * <ul>
 *   <li>the PMO's main line crosses its signal — the dot the
 *       {@code JorgeReis_TNO_PMO} draws;</li>
 *   <li>up-cross buys, down-cross sells short, at market — which fills at the
 *       open of the next bar;</li>
 *   <li>the stop is the EXTREME OF THE SIGNAL CANDLE: its low for a buy, its
 *       high for a sell. The bar that crossed, not the bar that filled;</li>
 *   <li>the target is two R away from the fill, R being the distance from the
 *       fill to that stop;</li>
 *   <li>a signal arriving while positioned <b>reverses</b>: the position is
 *       closed and the new one opened, in that order.</li>
 * </ul>
 *
 * <h2>On the chart's own scale, and nothing else</h2>
 *
 * <p>It reads the series it is given and no other. The PMO of the chart is the
 * PMO of the run: put the chart on five minutes and it trades the five-minute
 * crossing. That is why this strategy carries no {@code Sourced} — an indicator
 * pinned to a scale of its own is exactly what it must not do here.
 *
 * <h2>The reversal is TWO ORDERS, not one</h2>
 *
 * <p>{@code ReversePosition} exists and would do it in one, sending double the
 * quantity. Two are sent instead: close, then open. It is how his robots write
 * an inversion, and it is what the report then shows — the losing trade ENDS
 * where it ended and the new one begins where it began. One order of double
 * size books as a single fill that crosses zero, and although the engine splits
 * it the reader is left with two trades that share a price and an instant and
 * no sign that anything was decided between them.
 *
 * <h2>The crossings alternate, so a same-side signal cannot arrive</h2>
 *
 * <p>An up-cross leaves the line above the signal; it cannot cross up again
 * without first crossing down. So "a signal while positioned" is always the
 * opposite signal, and there is no case where this would add to a position.
 *
 * <h2>R is measured from the FILL, not from the signal's close</h2>
 *
 * <p>A market order fills at the next open, which is not the close that decided
 * anything. Measuring R from the close would put the target at a distance the
 * trade never risked: on a bar that gaps, the real risk is larger than the one
 * the chart showed, and a 2R target computed from the close would be much less
 * than twice what was actually on the table.
 */
public final class MomentumCross implements Strategy, Plotted {

    /** Two R, as asked. */
    public static final double REWARD = 2.0;

    /**
     * How far past its trigger the protective stop may still fill.
     *
     * <p>Not a free parameter — it is the answer to a measured defect. NTSL
     * spells a stop as {@code Stop(trigger, limit)} and with the two equal the
     * order REFUSES to fill when the bar opens beyond the level, which is
     * exactly the bar a stop exists for. Two hundred points is far enough that
     * the fill happens and near enough that a fill at that distance is visible
     * as what it is: a gap, not an exit.</p>
     */
    public static final double SLIP = 200;

    private final ZoneId zone;

    private final Pmo pmo;

    private final int lot;

    private final double reward;

    private final double slip;

    private PriceSeries bars = PriceSeries.empty();

    private int size;

    private int[] session = new int[0];

    private int[] crossing = new int[0];

    // ------------------------------------------------------------- the trade

    /** Which way the open position faces, or zero while flat. */
    private int side;

    /** The extreme of the candle that crossed, which is the stop while it lasts. */
    private double stop = Double.NaN;

    private double target = Double.NaN;

    /** The side an order has been decided for and not yet sent, or zero. */
    private int wanted;

    /** The stop that order's fill will inherit. */
    private double wantedStop = Double.NaN;

    private double[] entryLine;

    private double[] stopLine;

    private double[] targetLine;

    public MomentumCross() {
        this(null, Pmo.standard(), 1, REWARD, SLIP);
    }

    public MomentumCross(ZoneId zone, Pmo pmo, int lot) {
        this(zone, pmo, lot, REWARD, SLIP);
    }

    /**
     * @param zone   the exchange's zone, which decides where a session begins
     * @param pmo    the oscillator, with its four periods and its scale
     * @param lot    contracts per entry
     * @param reward the target, in multiples of the risk
     * @param slip   how far past its trigger the stop may still fill
     */
    public MomentumCross(ZoneId zone, Pmo pmo, int lot, double reward, double slip) {
        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.pmo = pmo == null ? Pmo.standard() : pmo;
        this.lot = Math.max(1, lot);
        this.reward = reward > 0 ? reward : REWARD;
        this.slip = Math.max(0, slip);
    }

    @Override
    public void start(PriceSeries series) {
        bars = series == null ? PriceSeries.empty() : series;
        size = bars.size();
        session = new int[size];

        LocalDate day = null;
        int which = -1;

        for (int bar = 0; bar < size; bar++) {
            LocalDate now = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();

            if (!now.equals(day)) {
                day = now;
                which++;
            }

            session[bar] = which;
        }

        // ONCE, FOR THE WHOLE RUN. The crossing at bar i depends only on bars up
        // to i -- the two lines are exponential averages and nothing here reads
        // forward -- so one pass gives the same answer as asking bar by bar, and
        // it is the only way a double smoothing over a long series finishes in
        // reasonable time.
        crossing = Pmo.crossings(pmo.over(bars));

        entryLine = blank(size);
        stopLine = blank(size);
        targetLine = blank(size);

        side = 0;
        wanted = 0;
        wantedStop = Double.NaN;
        stop = Double.NaN;
        target = Double.NaN;
    }

    private static double[] blank(int many) {
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    @Override
    public void onBar(Market market, Desk desk) {
        if (size == 0) {
            return;
        }

        int bar = market.bar();

        settle(market.filled());

        // NOTHING CROSSES THE SESSION, and the order goes out one bar EARLY --
        // that is what the + 2 is for. ClosePosition is a market order and fills
        // at the open of the NEXT bar; sent on the session's last bar, that next
        // bar is tomorrow and the exit price is the overnight gap, which is a
        // number the day never offered.
        boolean closing = bar + 2 >= size || session[bar + 2] != session[bar];

        if (closing) {
            wanted = 0;
            wantedStop = Double.NaN;

            if (market.hasPosition()) {
                desk.closePosition();
            } else {
                forget();
            }

            return;
        }

        if (bar < crossing.length && crossing[bar] != 0) {
            // THE STOP IS THIS CANDLE'S EXTREME, read here and carried into the
            // trade. Read at the fill instead it would be the extreme of the
            // NEXT bar -- a different level, and one the signal never pointed
            // at.
            wanted = crossing[bar];
            wantedStop = wanted > 0 ? bars.lowAt(bar) : bars.highAt(bar);
        }

        draw(bar);
        emit(market, desk);
    }

    /**
     * Keeps the book in step with what executed.
     *
     * <p>The opening fill is what finally fixes the trade: only then is there a
     * price to measure R from. On a reversal the two fills arrive together and
     * IN ORDER — the close first, then the opening — so walking the list in
     * order is what keeps the side right.</p>
     */
    private void settle(List<Fill> fills) {
        for (Fill fill : fills) {
            String verb = fill.verb();

            if (verb == null) {
                continue;
            }

            if (verb.contains("Cover") || verb.contains("Close")) {
                forget();
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                opened(fill, verb.startsWith("Buy") ? 1 : -1);
            }
        }
    }

    private void opened(Fill fill, int which) {
        side = which;
        stop = wantedStop;
        wantedStop = Double.NaN;

        double risk = which > 0 ? fill.price() - stop : stop - fill.price();

        // A FILL ALREADY PAST ITS OWN STOP: the bar opened beyond the signal
        // candle's extreme. There is no risk to multiply and no trade to be in,
        // so it is closed at once rather than given an invented target on the
        // wrong side of the entry.
        if (!(risk > 0)) {
            stop = Double.NaN;
            target = Double.NaN;

            return;
        }

        target = fill.price() + which * reward * risk;
    }

    private void forget() {
        side = 0;
        stop = Double.NaN;
        target = Double.NaN;
    }

    private void emit(Market market, Desk desk) {
        int many = Math.abs(market.buyPositionQty() - market.sellPositionQty());
        boolean holding = side != 0 && many > 0;

        if (holding && wanted != 0 && wanted != side) {
            // THE INVERSION, IN TWO ORDERS: close, then open. Both are market
            // orders and both execute at the next open, in the order they were
            // asked -- so the losing trade ends and the new one begins, as two
            // trades in the report instead of one fill that crosses zero.
            desk.closePosition();

            if (wanted > 0) {
                desk.buyAtMarket(lot);
            } else {
                desk.sellShortAtMarket(lot);
            }

            wanted = 0;

            return;
        }

        if (holding) {
            if (Double.isNaN(stop) || Double.isNaN(target)) {
                // The fill landed past its own stop: out, at market.
                desk.closePosition();

                return;
            }

            // RE-SENT ON EVERY BAR, because the book is rebuilt from what the
            // strategy asks at each close -- an order placed once and assumed to
            // stay leaves the position naked from the following bar onward.
            if (side > 0) {
                desk.sellToCoverStop(stop, stop - slip, many);
                desk.sellToCoverLimit(target, many);
            } else {
                desk.buyToCoverStop(stop, stop + slip, many);
                desk.buyToCoverLimit(target, many);
            }

            return;
        }

        if (wanted != 0) {
            if (wanted > 0) {
                desk.buyAtMarket(lot);
            } else {
                desk.sellShortAtMarket(lot);
            }

            // SENT ONCE. A market order does not rest -- it goes at the next
            // open and is gone -- so a strategy that kept asking would buy again
            // on every bar until something else stopped it.
            wanted = 0;
        }
    }

    private void draw(int bar) {
        if (wanted != 0) {
            entryLine[bar] = bars.closeAt(bar);
            stopLine[bar] = wantedStop;
        } else if (side != 0) {
            stopLine[bar] = stop;
            targetLine[bar] = target;
        }
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("Entrada", entryLine);
        drawn.put("Stop", stopLine);
        drawn.put("Alvo", targetLine);

        return drawn;
    }

    @Override
    public String toString() {
        return "TNO";
    }
}
