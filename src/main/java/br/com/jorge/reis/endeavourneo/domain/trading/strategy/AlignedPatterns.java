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

import br.com.jorge.reis.endeavourneo.domain.indicator.LastClosed;
import br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePatterns;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes candle patterns, but only with BOTH opening ranges pointing the same way.
 *
 * <p>The whole rule:
 *
 * <ul>
 *   <li><b>the gate</b> — the ninety-minute range and the first fight must both
 *       have been broken, and to the SAME side. That side is the only one
 *       traded all day;</li>
 *   <li><b>the credit</b> — two stochastic latches, 8/3, one on two minutes and
 *       one on five. Each arms when its own stochastic reaches the level, gives
 *       <b>two entries</b>, and disarms when it comes back to fifty. There is
 *       no daily ceiling: a latch that disarms and arms again brings two more.
 *       The ration is per MOVEMENT of the oscillator, not per day;</li>
 *   <li><b>the triggers</b> — 1-2-3 on two minutes, 1-2-3 on five, PFR on five
 *       and PFR on ten. The two-minute pattern spends the two-minute latch's
 *       credit; the other three spend the five-minute one's;</li>
 *   <li><b>the entry</b> — a stop order one tick beyond the pattern's extreme,
 *       which is how the archive triggers a 1-2-3 and how {@code PatternBreakout}
 *       already does it here;</li>
 *   <li><b>the stop</b> — the opposite extreme of the pattern's three bars, on
 *       the pattern's own scale;</li>
 *   <li><b>the target</b> — two R from the FILL, and each entry carries its own
 *       R because each pattern has its own geometry.</li>
 * </ul>
 *
 * <h2>Five scales in one run, and the trap they carry</h2>
 *
 * <p>Two minutes, five, ten, the ninety-minute range and the chart's own. Every
 * coarse reading is taken from the LAST CLOSED coarse bar — {@link LastClosed}
 * — because the obvious way round reads a bar that has not finished, and a
 * pattern that is not complete yet is a pattern that may not happen. That is
 * the one mistake in this file that would not show up as a defect: it would
 * show up as a very good result.
 *
 * <h2>What was measured about the gate, before anything was built on it</h2>
 *
 * <p>Measured 02/09/2026 across two raw bases, as separation per operation:
 * the fifteen-minute impulse was the only definition positive in all six cells,
 * and <b>the ninety-minute clock range was negative in all three cells of the
 * blind base</b> — −3,95 / −16,40 / −1,75. Even the impulse is weak: the
 * largest t of the six is 1,27. The conclusion recorded then was to use it as a
 * side filter and never as a trigger of its own, and three legs built on top of
 * it — pullback, 12:30 and the fade — all did badly on the blind base.
 *
 * <p>This strategy uses BOTH as a gate, one of them in a role its own
 * measurement refused. That is a deliberate choice of his and it is written
 * here so the number this produces is read knowing it.
 */
public final class AlignedPatterns implements Strategy, Plotted, Sourced {

    /** Two R, from the fill. */
    public static final double REWARD = 2.0;

    /** The WIN's tick: how far beyond the pattern the trigger sits. */
    public static final double TICK = 5;

    /**
     * How far past its trigger the protective stop may still fill.
     *
     * <p>NTSL spells a stop as {@code Stop(trigger, limit)} and with the two
     * equal it REFUSES to fill when the bar opens beyond the level — which is
     * exactly the bar a stop exists for.</p>
     */
    public static final double SLIP = 200;

    /** The clock range's formation, in minutes: the ninety of the name. */
    public static final int FORMATION = 90;

    /** Entries a latch hands out each time it arms. */
    public static final int PER_ARMING = 2;

    /**
     * One way in: a pattern, on a scale, spending one latch's credit.
     *
     * @param minutes the scale the pattern is read on
     * @param family  which shape
     * @param latch   the scale of the latch it spends, 2 or 5
     */
    public record Trigger(int minutes, CandlePattern.Family family, int latch) { }

    /** The four he asked for. */
    public static final List<Trigger> TRIGGERS = List.of(
            new Trigger(2, CandlePattern.Family.ONE_TWO_THREE, 2),
            new Trigger(5, CandlePattern.Family.ONE_TWO_THREE, 5),
            new Trigger(5, CandlePattern.Family.PFR, 5),
            new Trigger(10, CandlePattern.Family.PFR, 5));

    private final ZoneId zone;

    private final int lot;

    private final double reward;

    private final double slip;

    private final StochasticLatch fast = new StochasticLatch(StochasticLatch.Settings.standard());

    private final StochasticLatch slow = new StochasticLatch(StochasticLatch.Settings.standard());

    private PriceSeries bars = PriceSeries.empty();

    private PriceSeries source;

    private int size;

    private int[] session = new int[0];

    /** How many latches armed on each bar: the ration is per arming. */
    private int[] armings = new int[0];

    /** Which trigger placed an order on each bar, or −1. */
    private int[] fired = new int[0];

    /** Which trigger had an order RESTING on each bar, or −1. */
    private int[] alive = new int[0];

    /** What each latch had left on each bar, before anything was decided. */
    private int[][] credit = new int[0][];

    /** Which way both ranges agree, per bar, or zero while they do not. */
    private int[] gate = new int[0];

    /** Per trigger: which coarse bar each decision bar is reading. */
    private double[][] reading = new double[0][];

    /** Per trigger, per coarse bar: the side of the pattern that closed there. */
    private int[][] pattern = new int[0][];

    /** Per trigger, per coarse bar: where the entry and the stop would sit. */
    private double[][] entryOf = new double[0][];

    private double[][] stopOf = new double[0][];

    // ------------------------------------------------------------- the book

    /** One entry that is on: where it is stopped and where it aims. */
    private record Lot(int side, int quantity, double stop, double target) { }

    private final List<Lot> open = new ArrayList<>();

    /** The trigger whose order rests, and what it would become. */
    private Trigger resting;

    private int restingSide;

    private double restingEntry = Double.NaN;

    private double restingStop = Double.NaN;

    /** Which coarse bar the resting order came from, so it can expire. */
    private int restingAt = -1;

    /** The lot the resting order will become, once it fills. */
    private int aiming;

    private double[] entryLine;

    private double[] stopLine;

    private double[] targetLine;

    public AlignedPatterns() {
        this(null, 1, REWARD, SLIP);
    }

    public AlignedPatterns(ZoneId zone, int lot) {
        this(zone, lot, REWARD, SLIP);
    }

    /**
     * @param zone   the exchange's zone, which decides where a session begins
     * @param lot    contracts per entry
     * @param reward the target, in multiples of the risk
     * @param slip   how far past its trigger the stop may still fill
     */
    public AlignedPatterns(ZoneId zone, int lot, double reward, double slip) {
        this.zone = zone == null ? Timeframe.defaultZone() : zone;
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
        armings = new int[size];
        fired = new int[size];
        credit = new int[size][2];
        alive = new int[size];

        Arrays.fill(fired, -1);
        Arrays.fill(alive, -1);

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

        PriceSeries stored = source == null ? bars : source;

        buildTheGate(stored);
        buildTheTriggers(stored);

        // THE LATCHES READ THEIR OWN SCALES. Two minutes and five, and neither
        // is the minute the latch used to be nailed to.
        fast.start(bars, stored, 2);
        slow.start(bars, stored, 5);

        entryLine = blank(size);
        stopLine = blank(size);
        targetLine = blank(size);

        open.clear();
        forgetTheOrder();
    }

    private static double[] blank(int many) {
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    /**
     * Works out, once, which days have both ranges pointing the same way.
     *
     * <p>Both must be broken and they must agree. A day where only one of them
     * has spoken has no side, and a day where they disagree has two — which is
     * the same as none.</p>
     */
    private void buildTheGate(PriceSeries stored) {
        gate = new int[size];

        PriceSeries five = Timeframe.ofMinutes(OpeningImpulse.MINUTES).apply(stored, zone);
        OpeningImpulse impulse = OpeningImpulse.standard();
        int[] fight = impulse.brokenAt(bars, impulse.of(five, zone), zone);

        List<OpeningRange.Session> sessions = OpeningRange.of(five, zone, FORMATION);
        int[] clock = brokeTheClockRange(sessions, five);

        for (int bar = 0; bar < size; bar++) {
            if (fight[bar] != 0 && fight[bar] == clock[bar]) {
                gate[bar] = fight[bar];
            }
        }
    }

    /**
     * The ninety-minute range's first break, latched, spread onto the run's bars.
     *
     * <p>Computed on five-minute candles and then spread by
     * {@link LastClosed}, so the answer at a decision bar is what a COMPLETED
     * five-minute candle had said — never one still forming.</p>
     */
    private int[] brokeTheClockRange(List<OpeningRange.Session> sessions, PriceSeries five) {
        double[] onFive = new double[five.size()];

        // THE SESSION ALREADY KNOWS. OpeningRange records the FIRST break --
        // which bar and which side -- and it is audited code with tests of its
        // own. Working it out again here would be a second implementation of
        // one rule, and the two would drift in silence.
        for (OpeningRange.Session each : sessions) {
            if (each.side() == 0 || each.breakBar() < 0) {
                continue;
            }

            for (int bar = each.breakBar(); bar <= each.last() && bar < five.size(); bar++) {
                onFive[bar] = each.side();
            }
        }

        double[] spread = LastClosed.spread(bars, five, onFive);
        int[] made = new int[size];

        for (int bar = 0; bar < size; bar++) {
            made[bar] = Double.isNaN(spread[bar]) ? 0 : (int) Math.round(spread[bar]);
        }

        return made;
    }

    /** Works out, once, every pattern of every trigger and where it would trade. */
    private void buildTheTriggers(PriceSeries stored) {
        reading = new double[TRIGGERS.size()][];
        pattern = new int[TRIGGERS.size()][];
        entryOf = new double[TRIGGERS.size()][];
        stopOf = new double[TRIGGERS.size()][];

        for (int which = 0; which < TRIGGERS.size(); which++) {
            Trigger trigger = TRIGGERS.get(which);
            PriceSeries coarse = Timeframe.ofMinutes(trigger.minutes()).apply(stored, zone);
            CandlePattern[] found = CandlePatterns.detectAll(coarse);

            pattern[which] = new int[coarse.size()];
            entryOf[which] = new double[coarse.size()];
            stopOf[which] = new double[coarse.size()];

            double[] index = new double[coarse.size()];

            for (int bar = 0; bar < coarse.size(); bar++) {
                index[bar] = bar;

                CandlePattern each = found[bar];

                if (each == null || each.family() != trigger.family() || each.direction() == 0
                        || bar < 2) {
                    continue;
                }

                // THE EXTREMES OF THE THREE BARS, which is what a 1-2-3 or a PFR
                // is made of. The entry is one tick beyond the extreme it points
                // at and the stop is the other one -- the same geometry
                // PatternBreakout uses, and the archive's.
                double high = Math.max(coarse.highAt(bar),
                        Math.max(coarse.highAt(bar - 1), coarse.highAt(bar - 2)));
                double low = Math.min(coarse.lowAt(bar),
                        Math.min(coarse.lowAt(bar - 1), coarse.lowAt(bar - 2)));

                pattern[which][bar] = each.direction();
                entryOf[which][bar] = each.direction() > 0 ? high + TICK : low - TICK;
                stopOf[which][bar] = each.direction() > 0 ? low : high;
            }

            reading[which] = LastClosed.spread(bars, coarse, index);
        }
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
        // bar is tomorrow and the exit price is the overnight gap.
        boolean closing = bar + 2 >= size || session[bar + 2] != session[bar];

        if (closing) {
            forgetTheOrder();
            fast.clear();
            slow.clear();

            if (market.hasPosition()) {
                desk.closePosition();
            } else {
                open.clear();
            }

            return;
        }

        // The latches first: what this bar's candles did to them happens before
        // anything is decided on it.
        //
        // WRITTEN DOWN because the ration is per ARMING, and a test that wants
        // to check the ration has no other way to know how many there were.
        armings[bar] = (fast.at(bar) ? 1 : 0) + (slow.at(bar) ? 1 : 0);

        // O QUE CADA LATCH TINHA ANTES DE QUALQUER DECISAO. Sem isto nao ha
        // como um teste dizer se uma entrada gastou credito ou se ela
        // aconteceu sem ter nenhum.
        credit[bar][0] = fast.credit(gate[bar]);
        credit[bar][1] = slow.credit(gate[bar]);

        look(bar);

        // DEPOIS do look: e o que ficou de pe nesta barra, que e outra coisa
        // do que o que foi COLOCADO nela.
        alive[bar] = resting == null ? -1 : aiming;

        draw(bar);
        emit(market, desk);
    }

    /** Chooses the order that will rest on the next bar, if any. */
    private void look(int bar) {
        int side = gate[bar];

        // UMA ORDEM VIVE UM CANDLE DA ESCALA DELA, e nao uma barra fina. Este
        // era um defeito de verdade e a sonda o achou: apagando a ordem em toda
        // barra, um stop colado num padrao de 5m tinha UM MINUTO para ser
        // tocado, e a estrategia fazia uma entrada a cada dois pregoes. E a
        // validade de um candle do PatternBreakout, so que dita na escala certa.
        if (resting != null) {
            int now = aiming < 0 ? -1 : readingAt(aiming, bar);

            if (side == restingSide && now >= 0 && now <= restingAt + 1) {
                return;
            }

            forgetTheOrder();
        }

        if (side == 0) {
            return;
        }

        for (int which = 0; which < TRIGGERS.size(); which++) {
            Trigger trigger = TRIGGERS.get(which);
            double where = reading[which][bar];

            if (Double.isNaN(where)) {
                continue;
            }

            int coarse = (int) Math.round(where);

            // ONLY THE BAR THE PATTERN BECAME KNOWN ON. Spread over the fine
            // bars, one coarse reading covers several of them; firing on all of
            // them would turn one pattern into a handful of entries.
            if (coarse < 0 || coarse >= pattern[which].length || pattern[which][coarse] != side) {
                continue;
            }

            if (bar > 0 && !Double.isNaN(reading[which][bar - 1])
                    && (int) Math.round(reading[which][bar - 1]) == coarse) {
                continue;
            }

            StochasticLatch latch = trigger.latch() == 2 ? fast : slow;

            if (latch.credit(side) <= 0) {
                continue;
            }

            resting = trigger;
            fired[bar] = which;
            restingSide = side;
            restingEntry = entryOf[which][coarse];
            restingStop = stopOf[which][coarse];
            restingAt = coarse;
            aiming = which;

            return;
        }
    }

    /**
     * Keeps the book in step with what executed.
     *
     * <p>An opening fill is the trigger chosen at the last close, recorded
     * rather than recognised — and it is the fill that finally fixes the target,
     * because only then is there a price to measure R from.</p>
     */
    private void settle(List<Fill> fills) {
        for (Fill fill : fills) {
            String verb = fill.verb();

            if (verb == null) {
                continue;
            }

            if (verb.contains("Close") || verb.contains("Reverse")) {
                open.clear();
            } else if (verb.contains("Cover")) {
                closeTheLotAt(fill.price(), verb.contains("Stop"));
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                opened(fill);
            }
        }
    }

    private void opened(Fill fill) {
        if (resting == null) {
            return;
        }

        int side = restingSide;
        double risk = side > 0 ? fill.price() - restingStop : restingStop - fill.price();

        StochasticLatch latch = resting.latch() == 2 ? fast : slow;

        latch.spend(side);

        // A FILL ALREADY PAST ITS OWN STOP: the bar opened beyond the pattern's
        // far extreme. There is no risk to multiply and no trade to be in.
        if (risk > 0) {
            open.add(new Lot(side, fill.quantity(), restingStop,
                    fill.price() + side * reward * risk));
        }

        forgetTheOrder();
    }

    /**
     * Closes the lot whose leg executed, found by the level it rested at.
     *
     * <p>A stop fills at its level or WORSE and a target at its level or better,
     * so each fill points at one side of the levels on the book. The nearest on
     * that side is the one that went.</p>
     */
    private void closeTheLotAt(double price, boolean wasStop) {
        int found = -1;
        double best = Double.NaN;

        for (int which = 0; which < open.size(); which++) {
            Lot lot = open.get(which);
            double level = wasStop ? lot.stop() : lot.target();

            // Reached: a stop of a long fills at or below its level, a target at
            // or above. Mirrored for a short.
            double past = lot.side() * (price - level);

            if (wasStop ? past > TICK : past < -TICK) {
                continue;
            }

            if (found < 0 || Math.abs(level - price) < Math.abs(best - price)) {
                found = which;
                best = level;
            }
        }

        if (found < 0) {
            if (!open.isEmpty()) {
                open.remove(0);
            }

            return;
        }

        open.remove(found);
    }

    private void forgetTheOrder() {
        resting = null;
        restingSide = 0;
        restingEntry = Double.NaN;
        restingStop = Double.NaN;
        restingAt = -1;
        aiming = -1;
    }

    private void emit(Market market, Desk desk) {
        int many = 0;

        for (Lot each : open) {
            many += each.quantity();
        }

        int held = Math.abs(market.buyPositionQty() - market.sellPositionQty());

        // THE BOOK BOWS TO THE POSITION. The book is a model and the engine's
        // position is the fact; a model claiming more contracts than exist spends
        // the day sending stops for paper that already left.
        while (many > held && !open.isEmpty()) {
            many -= open.remove(open.size() - 1).quantity();
        }

        // ONE LEG PER LOT, all of them on the book at once. Each pattern has its
        // own geometry, so each entry has its own stop and its own 2R -- which
        // is what he asked for, and what the engine's OCO can now carry.
        for (Lot lot : open) {
            if (lot.side() > 0) {
                desk.sellToCoverStop(lot.stop(), lot.stop() - slip, lot.quantity());
                desk.sellToCoverLimit(lot.target(), lot.quantity());
            } else {
                desk.buyToCoverStop(lot.stop(), lot.stop() + slip, lot.quantity());
                desk.buyToCoverLimit(lot.target(), lot.quantity());
            }
        }

        if (resting == null || Double.isNaN(restingEntry)) {
            return;
        }

        if (restingSide > 0) {
            desk.buyStop(restingEntry, restingEntry, lot);
        } else {
            desk.sellShortStop(restingEntry, restingEntry, lot);
        }
    }

    private void draw(int bar) {
        if (resting != null) {
            entryLine[bar] = restingEntry;
            stopLine[bar] = restingStop;

            return;
        }

        if (open.isEmpty()) {
            return;
        }

        Lot first = open.get(0);

        stopLine[bar] = first.stop();
        targetLine[bar] = first.target();
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
        return "Dois ranges";
    }

    /** @return the trigger whose order was resting there, or −1, for a test to read */
    int aliveAt(int bar) {
        return bar >= 0 && bar < alive.length ? alive[bar] : -1;
    }

    /** @return the trigger that placed an order there, or −1, for a test to read */
    int firedAt(int bar) {
        return bar >= 0 && bar < fired.length ? fired[bar] : -1;
    }

    /** @return what the latch of that scale had left there, for a test to read */
    int creditAt(int bar, int latch) {
        return bar >= 0 && bar < credit.length ? credit[bar][latch == 2 ? 0 : 1] : 0;
    }

    /** @return how many latches armed on that bar, for a test to read */
    int armingsAt(int bar) {
        return bar >= 0 && bar < armings.length ? armings[bar] : 0;
    }

    /** @return which way both ranges agreed at that bar, for a test to read */
    int gateAt(int bar) {
        return bar >= 0 && bar < gate.length ? gate[bar] : 0;
    }

    /** @return the coarse bar the trigger was reading there, or −1 */
    int readingAt(int which, int bar) {
        if (which < 0 || which >= reading.length || bar < 0 || bar >= size
                || Double.isNaN(reading[which][bar])) {
            return -1;
        }

        return (int) Math.round(reading[which][bar]);
    }
}
