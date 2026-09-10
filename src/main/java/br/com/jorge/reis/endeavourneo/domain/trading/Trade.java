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
 * @param gross      points made or lost before costs
 * @param cost       points paid, always positive
 * @param fills      everything that happened, in order
 */
public record Trade(int openedAt, int closedAt, Side side, int contracts,
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
}
