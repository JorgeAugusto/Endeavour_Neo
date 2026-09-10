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
package br.com.jorge.reis.endeavourneo.domain.trading.order;

/**
 * Anything a strategy can ask for: an {@link Order}, or a {@link Command}.
 *
 * <p>The two are kept in one ordered list rather than two, because the order
 * between them is the difference between two different strategies. NTSL has no
 * inverting market order that his robots trust — the note from the port to the
 * Profit records that an inversion is done as <b>two orders</b> — so a turn is
 * written {@code ClosePosition} and then {@code BuyAtMarket}. Sorted into
 * separate buckets those two would replay in whichever order the buckets happen
 * to be read, and the second reading closes the position it just opened.</p>
 *
 * <p>This is the whole of the execution layer. It is deliberately small: the
 * layer that decides — indicators, patterns, a network — may be as rich as it
 * likes, and never has to be translated. This one is what a robot in the Profit
 * has to be able to say, and so it can say nothing the Profit cannot.</p>
 */
public sealed interface Instruction permits Order, Command {

    /**
     * What this instruction is called in NTSL.
     *
     * @return the verb, e.g. {@code "SellToCoverLimit"} or {@code "ClosePosition"}
     */
    String verb();
}
