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
import br.com.jorge.reis.endeavourneo.domain.trading.Sourced;
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
 * <h2>Doubling, when it is switched on</h2>
 *
 * <p>The lot doubles after every losing trade and goes back to one after a
 * winning one — {@code PatternBreakout} carries the same module and this is the
 * same record. The sequence dies with the session: a run of losses that ends
 * the afternoon holding eight would otherwise open tomorrow holding sixteen,
 * against a market that gapped overnight and owes yesterday nothing.
 *
 * <p><b>It changes the risk and not the edge.</b> Doubling does not make a
 * losing signal win; it makes the same signal bet more after each loss, which
 * moves the drawdown by a factor of two per step and leaves the expectancy per
 * contract exactly where it was. Read the drawdown of anything measured with
 * this on before reading its total.
 *
 * <h2>The gate, when it is switched on</h2>
 *
 * <p>Off by default, and when on the only side traded is the one BOTH opening
 * ranges agree about — see {@link RangeGate}. A crossing against the gate does
 * not enter.
 *
 * <p><b>But it still CLOSES.</b> A contrary crossing is this strategy's own
 * exit rule, and suppressing it would leave a position with nothing to end it
 * but the stop or the target — which is a third strategy, not this one with a
 * filter. So the gate refuses the opening and lets the closing through.
 *
 * <p>The gate's own measurement is in {@link RangeGate}: one of the two ranges
 * was refused for this very job by the project's own reading of it. The switch
 * exists so the two can be compared; it is not a recommendation.</p>
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
public final class MomentumCross implements Strategy, Plotted, Sourced {

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

    /**
     * Doubling the lot after a loss, and how many times it may.
     *
     * @param on   whether the module is on at all
     * @param most how many times the lot may double; N=3 gives 1, 2, 4, 8
     *
     * <p>A module that is off doubles nothing and says so in the ONE number
     * that decides it: {@code most} is zeroed, so the lot cannot move and there
     * is no second guard to keep in step with this one. The same shape
     * {@code PatternBreakout} uses, for the same reason.</p>
     */
    public record Doubling(boolean on, int most) {

        /** Past this the lot is astronomical and the cap is the only thing left. */
        public static final int CEILING = 20;

        public Doubling {
            most = on ? Math.max(0, Math.min(most, CEILING)) : 0;
        }

        public static Doubling off() {
            return new Doubling(false, 0);
        }
    }

    private final ZoneId zone;

    private final Pmo pmo;

    private final int lot;

    private final double reward;

    private final double slip;

    /** Which opening ranges have to agree before a side may be traded. */
    private final RangeGate.Mode mode;

    private final Doubling doubling;

    /** How many times the lot has doubled since the last winner. */
    private int doublings;

    /** What the open position cost, so a closing fill can be judged. */
    private double entered = Double.NaN;

    private PriceSeries bars = PriceSeries.empty();

    /** The bars as stored, which the ranges are read from. */
    private PriceSeries source;

    /** Which way both ranges agree, per bar, or zero — empty while ungated. */
    private int[] gate = new int[0];

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

    /** A crossing the gate refused: it closes what is open and opens nothing. */
    private boolean closeOnly;

    private double[] entryLine;

    private double[] stopLine;

    private double[] targetLine;

    public MomentumCross() {
        this(null, Pmo.standard(), 1, REWARD, SLIP, RangeGate.Mode.OFF, Doubling.off());
    }

    public MomentumCross(ZoneId zone, Pmo pmo, int lot) {
        this(zone, pmo, lot, REWARD, SLIP, RangeGate.Mode.OFF, Doubling.off());
    }

    public MomentumCross(ZoneId zone, Pmo pmo, int lot, double reward, double slip) {
        this(zone, pmo, lot, reward, slip, RangeGate.Mode.OFF, Doubling.off());
    }

    /**
     * @param zone   the exchange's zone, which decides where a session begins
     * @param pmo    the oscillator, with its four periods and its scale
     * @param lot    contracts per entry
     * @param reward the target, in multiples of the risk
     * @param slip   how far past its trigger the stop may still fill
     * @param gate   which opening ranges have to agree before a side is traded
     * @param doubling whether the lot doubles after a loss, and how far
     */
    public MomentumCross(ZoneId zone, Pmo pmo, int lot, double reward, double slip,
                         RangeGate.Mode gate) {

        this(zone, pmo, lot, reward, slip, gate, Doubling.off());
    }

    /** @see #MomentumCross(ZoneId, Pmo, int, double, double, RangeGate.Mode) */
    public MomentumCross(ZoneId zone, Pmo pmo, int lot, double reward, double slip,
                         RangeGate.Mode gate, Doubling doubling) {

        this.doubling = doubling == null ? Doubling.off() : doubling;
        this.mode = gate == null ? RangeGate.Mode.OFF : gate;
        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.pmo = pmo == null ? Pmo.standard() : pmo;
        this.lot = Math.max(1, lot);
        this.reward = reward > 0 ? reward : REWARD;
        this.slip = Math.max(0, slip);
    }

    @Override
    public void sourcedFrom(PriceSeries stored) {
        source = stored;
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

        // THE GATE IS BUILT ONCE, and only when it is asked for: it folds the
        // stored bars to five minutes and walks two more indicators, which is
        // work nobody switched on should pay for.
        gate = RangeGate.of(bars, source, zone, mode);

        entryLine = blank(size);
        stopLine = blank(size);
        targetLine = blank(size);

        side = 0;
        wanted = 0;
        wantedStop = Double.NaN;
        closeOnly = false;
        doublings = 0;
        entered = Double.NaN;
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
            closeOnly = false;

            // A SEQUENCIA MORRE COM O PREGAO. Uma sequencia de perdas que fecha
            // a tarde com oito contratos abriria amanha com dezesseis, contra um
            // mercado que deu gap na noite e nao deve nada a ontem.
            doublings = 0;

            if (market.hasPosition()) {
                desk.closePosition();
            } else {
                forget();
            }

            return;
        }

        // THE GATE REFUSES THE OPENING, never the closing. A crossing against
        // it is still this strategy's exit rule, and a position with no exit but
        // the stop and the target would be a third strategy.
        boolean allowed = mode == RangeGate.Mode.OFF || crossing[bar] == gate[bar];

        if (bar < crossing.length && crossing[bar] != 0 && (allowed || side != 0)) {
            // THE STOP IS THIS CANDLE'S EXTREME, read here and carried into the
            // trade. Read at the fill instead it would be the extreme of the
            // NEXT bar -- a different level, and one the signal never pointed
            // at.
            wanted = allowed ? crossing[bar] : 0;
            wantedStop = wanted > 0 ? bars.lowAt(bar) : bars.highAt(bar);

            // REFUSED, BUT NOT IGNORED: with a position on, a crossing the gate
            // will not open still ends what is open.
            if (!allowed) {
                closeOnly = true;
            }
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
                count(fill.price());
                forget();
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                opened(fill, verb.startsWith("Buy") ? 1 : -1);
            }
        }
    }

    /**
     * Counts the trade that has just ended, for the doubling.
     *
     * <p>No check of {@code doubling.on()} here, on purpose: a module that is
     * off has {@code most() == 0} by construction, so the count cannot leave
     * zero. A second guard saying the same thing is one that can be edited out
     * of step with the first.</p>
     */
    private void count(double left) {
        if (Double.isNaN(entered) || side == 0) {
            return;
        }

        double points = side * (left - entered);

        if (points < 0) {
            doublings = Math.min(doublings + 1, doubling.most());
        } else if (points > 0) {
            doublings = 0;
        }
    }

    /** @return contracts for the next entry: the lot, doubled once per loss */
    private int lotNow() {
        return lot << Math.min(doublings, Doubling.CEILING);
    }

    private void opened(Fill fill, int which) {
        side = which;
        entered = fill.price();
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
        entered = Double.NaN;
        stop = Double.NaN;
        target = Double.NaN;
    }

    private void emit(Market market, Desk desk) {
        int many = Math.abs(market.buyPositionQty() - market.sellPositionQty());
        boolean holding = side != 0 && many > 0;

        if (holding && closeOnly) {
            // O SINAL CONTRARIO RECUSADO PELA PORTA: fecha e nao abre nada.
            desk.closePosition();

            closeOnly = false;

            return;
        }

        closeOnly = false;

        if (holding && wanted != 0 && wanted != side) {
            // THE INVERSION, IN TWO ORDERS: close, then open. Both are market
            // orders and both execute at the next open, in the order they were
            // asked -- so the losing trade ends and the new one begins, as two
            // trades in the report instead of one fill that crosses zero.
            desk.closePosition();

            if (wanted > 0) {
                desk.buyAtMarket(lotNow());
            } else {
                desk.sellShortAtMarket(lotNow());
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
                desk.buyAtMarket(lotNow());
            } else {
                desk.sellShortAtMarket(lotNow());
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
