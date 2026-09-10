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
 * Whether an order may open exposure, or may only give it back.
 *
 * <p>This is the half of an NTSL verb name that is easy to read past and
 * expensive to get wrong. {@code SellToCover} is not {@code SellShort} with a
 * different spelling: they behave differently when the position is not what the
 * strategy thought it was, and that is precisely when it matters.</p>
 *
 * <h2>The rules, quoted from the manual</h2>
 *
 * <p>Section 13 of the NTSL manual states them, and they are reproduced here
 * because the whole engine turns on them:</p>
 *
 * <ul>
 *   <li><b>An {@code OPEN} order on the wrong side covers instead.</b> "when a
 *       Buy order is sent while you are in a short position, it is treated as a
 *       cover order automatically" — and the mirror for a sell while long. So
 *       {@code OPEN} does not mean "always increases"; it means "is allowed
 *       to".</li>
 *   <li><b>A {@code COVER} order never inverts the position.</b> They "guarantee
 *       that the opposite order always respects the position of the
 *       operation".</li>
 *   <li><b>A {@code COVER} order with nothing to cover is ignored</b>, not
 *       clamped to zero and not an error: a {@code BuyToCover} while long or
 *       flat is silently dropped, and the mirror for {@code SellToCover}.</li>
 * </ul>
 *
 * <p>The manual recommends closing positions with {@code COVER} explicitly, and
 * the reason is the third rule: an exit written as {@code OPEN} that arrives
 * after the position already turned will happily open a new one on the other
 * side. Written as {@code COVER}, it does nothing — which is what an exit should
 * do when there is nothing left to exit.</p>
 *
 * <h2>Why COVER orders are the ones that group</h2>
 *
 * <p>The other consequence is in {@code Blotter}: the Profit sends cover orders
 * <b>as a single OCO</b>, so the first leg to fill kills the rest. That is how a
 * strategy asks for several exits at several prices and does not end up selling
 * the position twice. {@code OPEN} orders carry no such grouping — each stands
 * alone.</p>
 */
public enum Purpose {

    /**
     * May increase exposure — the {@code Buy} and {@code SellShort} families.
     *
     * <p>Covers instead when the position is on the other side, and may invert
     * it in the same order if the quantity exceeds what is open.</p>
     */
    OPEN,

    /**
     * May only give exposure back — the {@code BuyToCover} and
     * {@code SellToCover} families.
     *
     * <p>Clamped to the open position, never inverts, and dropped entirely when
     * there is nothing on the other side to give back.</p>
     */
    COVER;

    /** @return whether this purpose is allowed to open or increase a position */
    public boolean opens() {
        return this == OPEN;
    }
}
