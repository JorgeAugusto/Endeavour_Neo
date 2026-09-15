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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

/**
 * What each of the nine target/stop pairs would have produced, per opportunity.
 *
 * <p>Section 6 of the integrated fade model's specification. For one signal it
 * walks forward through the bars and asks, nine times, "if the lot had been
 * taken with THIS target and THIS stop, how would it have ended and when".
 *
 * <h2>These are training labels, and they are not a backtest</h2>
 *
 * <p>Read that distinction carefully, because everything downstream depends on
 * it. A label here is computed by looking at what happened AFTER the signal —
 * which is exactly what a decision may never do. It is legitimate only in one
 * arrangement: the network is trained on sessions that have already ended, and
 * the day it decides on contributes no label to the network deciding it. Section
 * 17 of the specification states the rule and section 9 arranges the training
 * around it.
 *
 * <p>So nothing here may be used to place an order on the day it describes. Its
 * only consumer is the training set.
 *
 * <h2>The stop wins a tie, and how often that mattered was counted</h2>
 *
 * <p>When a bar reaches the stop and the target both, this picks the stop. Not
 * because it is true — one-minute bars cannot say which came first — but because
 * it is the conservative side, and it is the same tie-break the engine itself
 * makes. The specification's own audit found ONE ambiguous case in 3.220 taken
 * entries, worth at most R$180, which is the useful form of that caveat: a
 * measured number rather than a worry.
 *
 * <h2>Two contracts, and the money is reais and not points</h2>
 *
 * <p>The specification's profit and loss is in reais and already net:
 * {@code pnl = (exit - entry) * side * 0.4 - 2.0}, where {@code 0.4} is two
 * contracts at R$0,20 a point and {@code 2.0} is R$1 per contract per round
 * trip. Both are parameters here rather than literals, because the day the cost
 * changes, a literal buried in an expression is the thing nobody finds.
 *
 * @param pointValue what one point of one contract is worth
 * @param contracts  how many contracts a lot carries
 * @param costPerContract what a full round trip of one contract costs
 */
public record FadeOutcomes(double pointValue, int contracts, double costPerContract) {

    /** R$0,20 per point per contract, for the WIN. */
    public static final double POINT = 0.20;

    /** {@code QTY = 2}. */
    public static final int CONTRACTS = 2;

    /** R$1 per contract per completed operation. */
    public static final double COST = 1.0;

    /**
     * One of the nine, as the specification names them.
     *
     * @param target points from the entry, in the trade's favour
     * @param stop   points from the entry, against it
     */
    public record Action(double target, double stop) {

        @Override
        public String toString() {
            return "T" + (int) target + "_S" + (int) stop;
        }
    }

    /**
     * The nine, IN THE SPECIFICATION'S ORDER, which is load-bearing.
     *
     * <p>The network's ninth output is the ninth of these and there is nothing
     * in the numbers to say so: reorder this array and every trained set of
     * weights silently starts naming different pairs. Targets vary slowest.</p>
     */
    public static final Action[] ACTIONS = {
        new Action(100, 150), new Action(100, 250), new Action(100, 350),
        new Action(150, 150), new Action(150, 250), new Action(150, 350),
        new Action(200, 150), new Action(200, 250), new Action(200, 350),
    };

    /**
     * How one pair ended.
     *
     * @param money   reais, net of cost
     * @param minutes how long it was held, at least one
     * @param exitBar the bar it left on
     * @param stopped whether it left at its stop rather than its target
     */
    public record Outcome(double money, int minutes, int exitBar, boolean stopped) { }

    public FadeOutcomes {
        if (contracts < 1) {
            throw new IllegalArgumentException("a lot of " + contracts + " contracts is not one");
        }
    }

    public static FadeOutcomes standard() {
        return new FadeOutcomes(POINT, CONTRACTS, COST);
    }

    /**
     * Walks forward once per pair and reports all nine.
     *
     * @param bars      the series the signal lives in
     * @param signalBar the bar the opportunity was seen on
     * @param lastBar   the last bar of that session, where an unfinished lot ends
     * @param side      {@code +1} to buy, {@code -1} to sell
     * @param entry     the price the lot would have been taken at
     * @return one outcome per action, in {@link #ACTIONS} order
     */
    public Outcome[] of(PriceSeries bars, int signalBar, int lastBar, int side, double entry) {
        Outcome[] made = new Outcome[ACTIONS.length];

        for (int action = 0; action < ACTIONS.length; action++) {
            made[action] = one(bars, signalBar, lastBar, side, entry, ACTIONS[action]);
        }

        return made;
    }

    private Outcome one(PriceSeries bars, int signalBar, int lastBar,
                        int side, double entry, Action action) {

        double target = entry + side * action.target();
        double stop = entry - side * action.stop();

        // FROM THE BAR AFTER THE SIGNAL. The signal's own bar is excluded by the
        // specification and by sense: the opportunity was seen at its close, and
        // a level it had already reached is not a level the lot could have left
        // through.
        for (int bar = signalBar + 1; bar <= lastBar && bar < bars.size(); bar++) {
            boolean hitStop = side > 0 ? bars.lowAt(bar) <= stop : bars.highAt(bar) >= stop;
            boolean hitTarget = side > 0
                    ? bars.highAt(bar) >= target : bars.lowAt(bar) <= target;

            if (hitStop) {
                // THE STOP TAKES THE TIE. See the class javadoc: conservative,
                // and measured to have decided one case in 3.220.
                double price = side > 0
                        ? Math.min(bars.openAt(bar), stop) : Math.max(bars.openAt(bar), stop);

                return ended(bars, signalBar, bar, side, entry, price, true);
            }

            if (hitTarget) {
                double price = side > 0
                        ? Math.max(bars.openAt(bar), target) : Math.min(bars.openAt(bar), target);

                return ended(bars, signalBar, bar, side, entry, price, false);
            }
        }

        int end = Math.min(lastBar, bars.size() - 1);

        return ended(bars, signalBar, end, side, entry, bars.closeAt(end), false);
    }

    private Outcome ended(PriceSeries bars, int signalBar, int exitBar,
                          int side, double entry, double exit, boolean stopped) {

        double money = (exit - entry) * side * pointValue * contracts
                - costPerContract * contracts;

        long apart = bars.timeAt(exitBar) - bars.timeAt(signalBar);

        // AT LEAST ONE MINUTE, which the specification asks for and which the
        // training target needs: duration is penalised linearly, and a zero
        // would make an instant exit look free rather than merely quick.
        int minutes = (int) Math.max(1, apart / 60_000L);

        return new Outcome(money, minutes, exitBar, stopped);
    }

    /**
     * The training target of section 8.
     *
     * <pre>{@code Y = P - 0.15 * max(-P, 0) - 0.08 * D + 1.0}</pre>
     *
     * <p>Three opinions in one line, and they are the model's and not ours. A
     * loss counts for fifteen per cent more than the same amount of gain, so the
     * network prefers a smaller edge that loses less. Every minute held costs
     * eight centavos, so a lot that sits all afternoon has to have earned it. And
     * a flat real is added to every action, which tilts the whole thing towards
     * TAKING a trade — without it, the utility of a marginal opportunity sits
     * just under zero and the model abstains from everything.</p>
     */
    public static double utility(Outcome outcome) {
        double money = outcome.money();

        return money - 0.15 * Math.max(-money, 0) - 0.08 * outcome.minutes() + 1.0;
    }
}
