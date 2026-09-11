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

import java.util.List;

/**
 * What one run produced.
 *
 * <p>Everything here is in points, and everything here is about <b>closed</b>
 * trades.</p>
 *
 * <h2>The position left open is not counted, and is not hidden either</h2>
 *
 * <p>A run that ends with contracts still open has a trade that never finished.
 * Closing it at the last price would be inventing an exit the strategy never
 * asked for — at a price it never saw, on a bar that only exists because the
 * data stops there. So the totals cover the trades that ended, and
 * {@link #openAtTheEnd()} says what is still hanging, with
 * {@link #openResultAtTheEnd()} saying what it would be worth. A strategy that
 * zeroes at the end of the session, as all of his do, leaves both at zero.</p>
 *
 * <h2>Two numbers to read before the profit</h2>
 *
 * <p>{@link #ambiguousBars()} is how many bars had a cover stop and a cover
 * target both reachable, and so were decided by the tie-break rather than by the
 * data. {@link #costs()} is what a round trip was charged. A run where the first
 * is large, or where the second is not the measured cost of the raw series, has
 * a profit figure that is about the settings.</p>
 *
 * @param trades              the operations that ended, in order
 * @param fills               every execution, in order
 * @param ambiguousBars       bars decided by the stop-first tie-break
 * @param costs               what was charged
 * @param openAtTheEnd        signed contracts still open when the data ran out
 * @param openResultAtTheEnd  what those would be worth at the last close
 * @param worth               the account marked to market at every bar's close
 * @param exposedBars         bars that ended with a position open
 */
public record Result(List<Trade> trades, List<Fill> fills, int ambiguousBars,
                     Costs costs, int openAtTheEnd, double openResultAtTheEnd,
                     double[] worth, int exposedBars) {

    public Result {
        trades = List.copyOf(trades);
        fills = List.copyOf(fills);
        worth = worth == null ? new double[0] : worth.clone();
    }

    /** A run that produced nothing, for a screen with no result yet. */
    public static Result empty() {
        return new Result(List.of(), List.of(), 0, Costs.NONE, 0, 0, new double[0], 0);
    }

    /**
     * The mark-to-market curve: what the account was worth at each bar's close.
     *
     * <p>This is <b>patrimônio</b>, and it is a different question from
     * {@link #equity()}. The balance curve moves only when a trade ends, so a
     * position bleeding for three days is a flat line on it and a cliff on the
     * day it closes. This one shows the bleeding while it happens, and the gap
     * between the two curves is exactly the hole inside whatever is open.</p>
     *
     * @return points, one per bar, net of the costs paid so far
     */
    public double[] worth() {
        return worth.clone();
    }

    /**
     * @return the fraction of bars that ended with a position open
     *
     * <p>Together with the average length of a trade, this is the pair that
     * says whether a strategy can pay for itself at all — before any profit
     * figure is worth reading.</p>
     */
    public double exposure() {
        return worth.length == 0 ? 0 : (double) exposedBars / worth.length;
    }

    /**
     * The balance curve laid on the same axis as {@link #worth()}: bars.
     *
     * <p>{@link #equity()} is one point per operation, which is the right shape
     * for reading a strategy and the wrong shape for putting beside a curve
     * measured per bar. Ten losing trades in a week and ten in a year are the
     * same picture on the operation axis; on this one they are not — and since
     * the point of drawing the two together is the gap between them, they have
     * to share an axis or the gap is an artefact of the drawing.</p>
     *
     * <p>It is a staircase: it holds flat while a position is open and steps on
     * the bar the operation ends.</p>
     *
     * @return points, one per bar, net of costs
     */
    public double[] balancePerBar() {
        double[] curve = new double[worth.length];
        double running = 0;
        int next = 0;

        for (int bar = 0; bar < curve.length; bar++) {
            while (next < trades.size() && trades.get(next).closedAt() <= bar) {
                running += trades.get(next).net();
                next++;
            }

            curve[bar] = running;
        }

        return curve;
    }

    /**
     * What had been paid in costs by each bar, cumulative and positive.
     *
     * <p>The third line of the chart, and it answers for free the question the
     * other two raise: <b>how much of this was brokerage</b>. On the run that is
     * in front of us it is most of the loss.</p>
     *
     * @return points, one per bar
     */
    public double[] costPerBar() {
        double[] curve = new double[worth.length];
        double running = 0;
        int next = 0;

        for (int bar = 0; bar < curve.length; bar++) {
            while (next < fills.size() && fills.get(next).bar() <= bar) {
                running += costs.ofFill(fills.get(next).quantity());
                next++;
            }

            curve[bar] = running;
        }

        return curve;
    }

    /** @return points after costs, over the trades that ended */
    public double net() {
        return gross() - cost();
    }

    /** @return points before costs, over the trades that ended */
    public double gross() {
        return trades.stream().mapToDouble(Trade::gross).sum();
    }

    /** @return points paid, over the trades that ended */
    public double cost() {
        return trades.stream().mapToDouble(Trade::cost).sum();
    }

    /** @return how many operations ended */
    public int count() {
        return trades.size();
    }

    /** @return how many of them made money after costs */
    public int wins() {
        return (int) trades.stream().filter(Trade::won).count();
    }

    /**
     * Points per operation, after costs.
     *
     * <p>The number that decides whether a strategy is worth anything, because
     * it is the one the cost is subtracted from. A total can look healthy on a
     * thousand trades that each clear less than they pay.</p>
     *
     * @return net over count, or NaN when nothing closed
     */
    public double perTrade() {
        return trades.isEmpty() ? Double.NaN : net() / trades.size();
    }

    /** @return whether anything is still open — see the class note */
    public boolean endedHolding() {
        return openAtTheEnd != 0;
    }

    /**
     * The balance curve: the running total after each operation closed.
     *
     * <p>Starts at zero and has one more point than there are trades, so the
     * first segment is the first operation. This is the <b>saldo</b> curve of
     * the two the report wants — it moves only when a trade ends.</p>
     *
     * <p>The other one, <b>patrimônio</b>, marks the open position to market on
     * every bar, and the gap between the two is the hole inside a position that
     * is still open. It is not built: it needs a value per bar and this needs
     * one per trade, and quoting one while calling it the other is the kind of
     * mistake that hides a drawdown.</p>
     *
     * @return points, cumulative and net of costs
     */
    public double[] equity() {
        double[] curve = new double[trades.size() + 1];
        double running = 0;

        for (int i = 0; i < trades.size(); i++) {
            running += trades.get(i).net();
            curve[i + 1] = running;
        }

        return curve;
    }

    /**
     * The deepest the balance curve ever fell below its own peak.
     *
     * @return points, zero or negative
     */
    public double drawdown() {
        double[] curve = equity();
        double peak = 0;
        double worst = 0;

        for (double point : curve) {
            peak = Math.max(peak, point);
            worst = Math.min(worst, point - peak);
        }

        return worst;
    }
}
