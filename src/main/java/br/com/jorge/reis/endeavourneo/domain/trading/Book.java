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

import br.com.jorge.reis.endeavourneo.domain.trading.order.Command;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Instruction;

import java.util.ArrayList;
import java.util.List;

/**
 * The orders resting in the market, waiting for the next bar.
 *
 * <p>Nothing here decides anything about the position — it is the set of things
 * that <i>could</i> happen next bar, and it is rebuilt from scratch at every
 * close.</p>
 *
 * <h2>Rebuilt, not amended — and that is NTSL's rule, not a shortcut</h2>
 *
 * <p>The manual is explicit for entry orders: ones that have not executed by the
 * end of the following candle "will be cancelled or edited when the next candle
 * finishes, according to the user's strategy". <b>Not re-emitting an order is
 * how you cancel it.</b> That is why his robots re-emit everything, every bar,
 * unconditionally — the stop level is a {@code var} that persists, and each
 * candle re-sends {@code *ToCoverStop} at the current level.</p>
 *
 * <p>For cover orders the manual describes the mechanism in more detail: if the
 * code emits the same <i>number</i> of them, the OCO is edited to the new
 * prices; if the number changes, the OCO is cancelled and a new one sent. Both
 * branches end with the resting set being what the code just asked for, and the
 * difference between them — queue position, latency — is invisible at bar
 * resolution. So the two are one rule here, and this note is why that is not a
 * simplification anyone has to re-derive later.</p>
 *
 * <h2>CancelPendingOrders clears what was asked so far</h2>
 *
 * <p>Since the book is rebuilt anyway, the only thing left for the command to do
 * is act on the current pass. A strategy that calls it at the top — the usual
 * idiom, {@code if HasPendingOrders then CancelPendingOrders} — gets exactly the
 * NTSL behaviour. One that calls it in the middle gets the sequential reading,
 * which is the only one that respects the order the strategy wrote.</p>
 */
final class Book {

    private final List<Instruction> resting = new ArrayList<>();

    /**
     * Replaces everything resting with what the strategy just asked for.
     *
     * @param asked the instructions of this bar, in the order they were asked
     */
    void reconcile(List<Instruction> asked) {
        resting.clear();

        for (Instruction instruction : asked) {
            if (instruction == Command.CANCEL_PENDING_ORDERS) {
                resting.clear();
            } else {
                resting.add(instruction);
            }
        }
    }

    /** @return what is resting, in the order it was asked */
    List<Instruction> resting() {
        return List.copyOf(resting);
    }

    /** {@code HasPendingOrders} */
    boolean hasPending() {
        return !resting.isEmpty();
    }

    /** {@code NumberOfPendingOrders} */
    int pendingCount() {
        return resting.size();
    }

    /** Empties the book. */
    void clear() {
        resting.clear();
    }
}
