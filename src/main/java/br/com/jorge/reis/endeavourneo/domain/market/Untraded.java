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
package br.com.jorge.reis.endeavourneo.domain.market;

/**
 * A series whose bars can say that <b>nothing traded inside them</b>.
 *
 * <h2>Why a bar can exist without a trade</h2>
 *
 * <p>Only renko produces them, and only over a gap. A candle is cut by the
 * clock, so a minute with no trade is simply not there; a brick is cut by
 * price, so when the market reopens 1.400 points higher the ruler has to lay
 * every brick between the two levels — fourteen of them at 100 points — and
 * thirteen of those cover prices <b>at which nobody bought or sold
 * anything</b>. They are the shape of the gap, not the shape of a move.</p>
 *
 * <p>The reference product draws them in a flat grey, and that is worth
 * copying for a reason beyond looks: <b>it is the only way to see which brick
 * was the first one really traded</b>. Without the mark, a fourteen-brick gap
 * reads as a fourteen-brick rally that never happened, and any two renkos
 * compared brick-for-brick will disagree about where the session began.</p>
 *
 * <h2>What counts as traded</h2>
 *
 * <p><b>A brick is a band of price. It is traded when somebody traded inside
 * that band, and untraded when nobody did.</b> That is the whole rule. Not how
 * far the bar moved, not which extreme laid the brick, not how many bricks
 * arrived at once — the reference product answers it as a count, and the brick
 * it draws grey reads <i>Contratos Neg: 0,00</i>.</p>
 *
 * <p>A band owns its bottom edge and not its top, because every boundary is
 * shared by two bricks and a price on one has to belong to exactly one of
 * them. On this instrument prices move in fives and bricks in twenty-fives, so
 * a trade landing on a boundary is one in twenty, not a corner case.</p>
 *
 * <p>The prices that can put a trade in a band are those traded since the
 * previous brick was laid, and the range of the bar laying this one.</p>
 *
 * <p>This is measured from prices, not from volume. Volume is spread across a
 * batch of bricks by a convention the data does not support (see {@link
 * Renko}), so a grey brick can still show a share of it — the count is what is
 * measured and the share is what is guessed.</p>
 */
public interface Untraded {

    /**
     * @param index the bar
     * @return whether no trade happened anywhere inside that bar's range
     */
    boolean untradedAt(int index);

    /**
     * @return whether that bar of that series is known to hold no trade
     *
     * <p>False for every series that does not carry the mark, which is all of
     * them but renko. Here so a caller never has to write the {@code
     * instanceof} — and never has to remember that a plain series answering
     * "no" means "it does not know", not "there were trades".</p>
     */
    static boolean at(PriceSeries series, int index) {
        return series instanceof Untraded marked && marked.untradedAt(index);
    }
}
