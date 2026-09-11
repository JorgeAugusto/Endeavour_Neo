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

import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import java.util.List;

/**
 * One operation: from flat, back to flat.
 *
 * <p>Defining where a trade begins and ends is a decision, not a fact, and this
 * is the one the Profit's own report makes: <b>a trade runs from the moment the
 * position leaves zero until it returns</b>. Everything that happens in between
 * — three entries up a ladder, two partial exits, a stop on the remainder —
 * belongs to the same trade.</p>
 *
 * <p>The alternative, pairing each entry with an exit, sounds tidier and is
 * unusable here: his robots average into a position deliberately, and there is
 * no honest way to say which contract the first partial exit gave back. The
 * average price knows; the pairing would have to invent a rule (FIFO? LIFO?) and
 * the rule would change the numbers.</p>
 *
 * @param openedAt   bar where the position left zero
 * @param closedAt   bar where it came back
 * @param side       the side it was opened on: {@code BUY} is a long trade
 * @param contracts  the largest the position got — not the number traded
 * @param turned     how many contracts were OPENED, which is the number traded:
 *                   a ladder that goes to twenty in five lots of four, sheds two
 *                   of each at a partial and refills, turns far more than twenty
 * @param gross      points made or lost before costs
 * @param cost       points paid, always positive
 * @param fills      everything that happened, in order
 */
public record Trade(int openedAt, int closedAt, Side side, int contracts, int turned,
                    double gross, double cost, List<Fill> fills) {

    public Trade {
        fills = List.copyOf(fills);
    }

    /** @return points after costs — the only number worth quoting */
    public double net() {
        return gross - cost;
    }

    /** @return bars the position was open */
    public int bars() {
        return closedAt - openedAt;
    }

    /** @return whether it made money after costs */
    public boolean won() {
        return net() > 0;
    }

    /**
     * The average price it was opened at.
     *
     * <p>Weighted, because a trade may have been opened three times up a ladder
     * — which is exactly the case where a single "entry price" would be a
     * fiction and the average is the only honest number.</p>
     *
     * @return points, or NaN if somehow nothing opened it
     */
    public double entryPrice() {
        return averageOf(true);
    }

    /** The average price it was closed at. See {@link #entryPrice()}. */
    public double exitPrice() {
        return averageOf(false);
    }

    /**
     * One execution of the trade, with what it did to the position.
     *
     * @param fill     the execution itself
     * @param opening  whether it added contracts or took them off
     * @param held     how many contracts were open after it
     * @param average  the average price of what was open after it, or NaN once
     *                 nothing is
     * @param points   realised by THIS execution, per the whole of it and not
     *                 per contract; zero for an opening, which realises nothing
     */
    public record Step(Fill fill, boolean opening, int held, double average, double points) { }

    /**
     * Every execution of the trade, in order, with the arithmetic done.
     *
     * <p>This is the answer to "where did each piece go in and out", which a
     * single entry price and a single exit price cannot give: a trade that
     * ladders into twenty contracts and leaves in six pieces has one average and
     * twenty-six stories, and the averages hide precisely the thing worth
     * looking at — that the first partial made money and the last two gave it
     * back.</p>
     *
     * <p>The points of a closing execution are measured against the <b>average
     * of what was open at that moment</b>, which is the only honest reading: the
     * contracts are not distinguishable, so pairing a particular exit with a
     * particular entry would need a rule — FIFO? LIFO? — and the rule would
     * change the numbers. It is the same arithmetic the engine itself uses.</p>
     *
     * @return one entry per fill
     */
    public List<Step> steps() {
        List<Step> walked = new java.util.ArrayList<>();

        int held = 0;
        double average = Double.NaN;

        for (Fill fill : fills) {
            boolean opening = fill.side() == side;
            double points = 0;

            if (opening) {
                average = held == 0
                        ? fill.price()
                        : (average * held + fill.price() * fill.quantity()) / (held + fill.quantity());
                held += fill.quantity();
            } else {
                // The sign comes from the side of the TRADE: a short that covers
                // lower made money, and the subtraction has to know that.
                points = (fill.price() - average) * fill.quantity() * side.signal();
                held -= fill.quantity();

                if (held <= 0) {
                    average = Double.NaN;
                }
            }

            walked.add(new Step(fill, opening, Math.max(0, held), average, points));
        }

        return walked;
    }

    private double averageOf(boolean opening) {
        double paid = 0;
        int contracts = 0;

        for (Fill fill : fills) {
            if ((fill.side() == side) == opening) {
                paid += fill.price() * fill.quantity();
                contracts += fill.quantity();
            }
        }

        return contracts == 0 ? Double.NaN : paid / contracts;
    }
}
