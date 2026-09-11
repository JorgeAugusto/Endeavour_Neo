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

import br.com.jorge.reis.endeavourneo.domain.indicator.Regression;
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
 * Fading a stretch back into two regression channels.
 *
 * <p>The thesis: price that has run to the edge of a channel which is itself
 * sloping the other way is stretched twice over, and it goes back. Nothing here
 * is a breakout — every order is a limit, resting where price would have to come
 * to it.
 *
 * <h2>The rule, for the sell; the buy is its mirror</h2>
 *
 * <ol>
 *   <li>The stochastic on one minute goes to eighty — the {@link StochasticLatch}
 *       arms a credit of entries, and throws it away again if the reading comes
 *       back to the middle before they are spent.</li>
 *   <li><b>Both</b> channels are sloping DOWN. Both, and not either: a stretch
 *       against one clock and with the other is not the trade.</li>
 *   <li>Price returns to one of the rungs — two deviations or two and a half,
 *       on the short channel or the long one.</li>
 *   <li>The target is the rung's own, on the rung's own channel, and it MOVES:
 *       the channel is refitted every bar and the order is re-sent at the level
 *       it has now. "Minus two of the channel" means the channel today.</li>
 * </ol>
 *
 * <h2>One order at a time, and why</h2>
 *
 * <p>Four rungs could all rest at once, and then a fill would have to be matched
 * to a rung by its PRICE — which is the bug this project already paid for on the
 * Range 90: a limit fills at the better of its level and the open, so on a gap
 * the fill price is not the level, and it can land exactly on another rung's.
 * A trade would be booked against the wrong target and the position would carry
 * a phantom lot.
 *
 * <p>So only the NEAREST unfilled rung rests, and a fill is unambiguous by
 * construction. It costs something real: a bar that crosses two rungs fills one
 * of them and the other waits for the next bar. It is the conservative side of
 * the error — the alternative reports trades the market did not give.
 *
 * <h2>There is no stop</h2>
 *
 * <p>Deliberately, and it is the first thing to look at in any result: a lot
 * leaves at its target or at the end of the session, and in between the loss is
 * bounded only by the clock. What a strategy without a stop measures is how far
 * the market can go against a fade before the day ends.
 */
public final class ChannelFade implements Strategy, Plotted, Sourced {

    /**
     * One rung of the ladder: where it enters, and where that lot leaves.
     *
     * <p>Both factors are MAGNITUDES and the side signs them. A sell enters at
     * {@code line + entry × sigma} and leaves at {@code line − target × sigma};
     * a buy is the mirror. Written signed instead, every rung would need two
     * spellings and the mirror would be a second rule to keep right.
     *
     * @param period which channel — the number of bars it is fitted over
     * @param entry  deviations from the line where the lot goes on
     * @param target deviations the other side where it comes off
     */
    public record Rung(int period, double entry, double target) {

        public Rung {
            if (period < 2) {
                throw new IllegalArgumentException("a channel of " + period + " bars is not one");
            }

            if (!(entry > 0) || !(target > 0)) {
                throw new IllegalArgumentException(
                        "a rung is measured in deviations from the line, and " + entry
                                + "/" + target + " is not a distance");
            }
        }
    }

    /**
     * The four rungs he specified.
     *
     * <p>Note the long channel's asymmetry — two deviations in, one and
     * three-quarters out — and that the short channel's two rungs share a
     * target. Both were confirmed rather than tidied: a ladder whose numbers
     * were rounded into a pattern would be measuring a strategy nobody asked
     * for.
     */
    public static final List<Rung> LADDER = List.of(
            new Rung(Regression.SHORT, 2.0, 2.0),
            new Rung(Regression.SHORT, 2.5, 2.0),
            new Rung(Regression.LONG, 2.0, 1.75),
            new Rung(Regression.LONG, 2.5, 2.0));

    private final ZoneId zone;

    private final List<Rung> ladder;

    private final int lot;

    private final StochasticLatch latch;

    // ---------------------------------------------------------------- the run

    private PriceSeries bars;

    private PriceSeries source;

    private int size;

    private int[] session;

    /** Each rung's fit, per decision bar, keyed the way {@link #ladder} is. */
    private Map<Integer, Regression.Fit[]> fits;

    // --------------------------------------------------------------- the book

    /** One lot that is on: which rung put it there, and which way it faces. */
    private record Lot(Rung rung, int side, int quantity) { }

    private final List<Lot> open = new ArrayList<>();

    /** The rung whose order is on the book, and which way it faces. */
    private Rung resting;

    private int restingSide;

    /** Rungs already spent since the latch last armed. */
    private final List<Rung> spent = new ArrayList<>();

    private double[] entryLine;

    private double[] targetLine;

    public ChannelFade() {
        this(null, LADDER, 1, StochasticLatch.Settings.standard());
    }

    /**
     * @param zone   the exchange's zone, which decides where a session begins
     * @param ladder the rungs, in any order; the nearest is chosen each bar
     * @param lot    contracts per rung
     * @param latch  the stochastic filter
     */
    public ChannelFade(ZoneId zone, List<Rung> ladder, int lot,
                       StochasticLatch.Settings latch) {

        this.zone = zone == null ? Timeframe.defaultZone() : zone;
        this.ladder = ladder == null || ladder.isEmpty() ? LADDER : List.copyOf(ladder);
        this.lot = Math.max(1, lot);
        this.latch = new StochasticLatch(latch);
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

        entryLine = blank(size);
        targetLine = blank(size);

        open.clear();
        spent.clear();

        latch.start(bars, source);
        fitTheChannels();
    }

    private static double[] blank(int many) {
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        return made;
    }

    /**
     * Fits every channel the ladder names, once, on ONE MINUTE.
     *
     * <p>On the minute and not on the decision bars, because that is what "canal
     * de regressão em 1m" says and because it has to survive the chart being put
     * on another scale. Each decision bar takes the fit of the LAST minute
     * inside it — every one of those has closed by the time the bar closes, so
     * nothing here reads a price that has not happened.</p>
     */
    private void fitTheChannels() {
        fits = new LinkedHashMap<>();

        if (size == 0) {
            return;
        }

        PriceSeries minutes = Timeframe.ONE_MINUTE.apply(source == null ? bars : source);

        for (Rung rung : ladder) {
            if (fits.containsKey(rung.period())) {
                continue;
            }

            Regression.Fit[] perMinute = new Regression(rung.period()).over(minutes);
            Regression.Fit[] perBar = new Regression.Fit[size];

            Arrays.fill(perBar, Regression.Fit.NONE);

            int at = 0;

            for (int minute = 0; minute < minutes.size(); minute++) {
                while (at + 1 < size && minutes.timeAt(minute) >= bars.timeAt(at + 1)) {
                    at++;
                }

                perBar[at] = perMinute[minute];
            }

            fits.put(rung.period(), perBar);
        }
    }

    private Regression.Fit fitAt(Rung rung, int bar) {
        Regression.Fit[] perBar = fits.get(rung.period());

        return perBar == null ? Regression.Fit.NONE : perBar[bar];
    }

    @Override
    public void onBar(Market market, Desk desk) {
        if (size == 0) {
            return;
        }

        int bar = market.bar();

        settle(market.filled());

        // NOTHING CROSSES THE SESSION, and the order goes out one bar early
        // because ClosePosition is a market order and a market order fills at
        // the NEXT bar's open. Sent on the last bar of the day, it would fill
        // tomorrow, and the exit price would be the overnight gap.
        boolean closing = bar + 2 >= size || session[bar + 2] != session[bar];

        if (closing) {
            open.clear();
            spent.clear();
            latch.clear();

            if (market.hasPosition()) {
                desk.closePosition();
            }

            return;
        }

        // A NEW ARMING FREES THE LADDER. The rungs are rationed per stretch, not
        // per run: price came back to the middle and went out again, so this is
        // a second stretch and it gets the whole ladder, not whatever the first
        // one left over.
        if (latch.at(bar)) {
            spent.clear();
        }

        cover(bar, desk);
        enter(bar, market, desk);
    }

    /**
     * Keeps the book in step with what executed.
     *
     * <p>A cover closes the lot whose target was nearest, which is the one whose
     * order was resting — see the class note on why only one rests at a time.
     * An opening fill is the rung chosen at the last close, which is recorded
     * rather than recognised.
     */
    private void settle(List<Fill> fills) {
        for (Fill fill : fills) {
            String verb = fill.verb();

            if (verb == null) {
                continue;
            }

            if (verb.contains("Cover") || verb.contains("Close") || verb.contains("Reverse")) {
                if (!open.isEmpty()) {
                    open.remove(0);
                }
            } else if (verb.startsWith("Buy") || verb.startsWith("SellShort")) {
                if (resting != null) {
                    open.add(new Lot(resting, restingSide, fill.quantity()));
                    spent.add(resting);
                    latch.spend(restingSide);

                    resting = null;
                }
            }
        }
    }

    /**
     * Sends the cover for the lot closest to leaving.
     *
     * <p>Re-sent every bar at the level the channel has NOW: the book is rebuilt
     * from what a strategy asks for at each close, so an order not asked for
     * again is cancelled — and a target that did not move would be a target of
     * a channel that no longer exists.</p>
     */
    private void cover(int bar, Desk desk) {
        if (open.isEmpty()) {
            return;
        }

        Lot first = open.get(0);
        Regression.Fit fit = fitAt(first.rung(), bar);

        if (!fit.known()) {
            return;
        }

        // The other side of the line from the entry: a sell came on above it and
        // comes off below it. The side is -1 for a sell, so adding it subtracts.
        double price = fit.line() + first.side() * first.rung().target() * fit.sigma();

        targetLine[bar] = price;

        if (first.side() > 0) {
            desk.sellToCoverLimit(price, first.quantity());
        } else {
            desk.buyToCoverLimit(price, first.quantity());
        }
    }

    /**
     * Rests the nearest unspent rung, if everything agrees.
     *
     * <p>Nearest to the price that is there now, which is what makes the fill
     * unambiguous: price has to travel through the near rung to reach the far
     * one, so taking them in that order is what the market actually does.</p>
     */
    private void enter(int bar, Market market, Desk desk) {
        resting = null;

        int side = sideAllowed(bar);

        if (side == 0 || latch.credit(side) <= 0) {
            return;
        }

        double now = bars.closeAt(bar);
        Rung nearest = null;
        double best = Double.POSITIVE_INFINITY;
        double level = Double.NaN;

        for (Rung rung : ladder) {
            if (spent.contains(rung)) {
                continue;
            }

            Regression.Fit fit = fitAt(rung, bar);

            if (!fit.known() || fit.direction() != side) {
                continue;
            }

            // A sell rests ABOVE the price, on the high side of the line.
            double price = fit.line() - side * rung.entry() * fit.sigma();
            double away = -side * (price - now);

            // Already past it: the level is on the wrong side of the price now,
            // and a limit there would fill at once at a price the thesis never
            // asked for.
            if (away < 0 || away >= best) {
                continue;
            }

            best = away;
            nearest = rung;
            level = price;
        }

        if (nearest == null) {
            return;
        }

        resting = nearest;
        restingSide = side;
        entryLine[bar] = level;

        int many = lot;

        if (side < 0) {
            desk.sellShortLimit(level, many);
        } else {
            desk.buyLimit(level, many);
        }
    }

    /**
     * @return the side BOTH channels allow, or zero
     *
     * <p>Both, and it is the condition that makes this a fade rather than a
     * guess: a channel falling is a market the model already knows is going
     * down, and the rung is how far above that expectation price has got. Two
     * clocks disagreeing means there is no expectation to be stretched from.</p>
     */
    private int sideAllowed(int bar) {
        int agreed = 0;

        for (Rung rung : ladder) {
            Regression.Fit fit = fitAt(rung, bar);

            if (!fit.known() || fit.direction() == 0) {
                return 0;
            }

            // The SELL is the side that fades a FALLING channel: the stretch is
            // upward, against a market pointing down.
            int wants = fit.direction();

            if (agreed == 0) {
                agreed = wants;
            } else if (agreed != wants) {
                return 0;
            }
        }

        return agreed;
    }

    @Override
    public Map<String, double[]> curves() {
        Map<String, double[]> drawn = new LinkedHashMap<>();

        drawn.put("Entrada", entryLine);
        drawn.put("Alvo", targetLine);

        return drawn;
    }

    @Override
    public String toString() {
        return "Fade de canal";
    }
}
