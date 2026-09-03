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
 * The prices a bar passed through, in order.
 *
 * <p>A candle says four numbers and hides the path between them. A replay needs
 * that path: it is what makes the last bar grow and wobble instead of appearing
 * whole. There are two ways to get it, and they are interchangeable on
 * purpose.</p>
 *
 * <p><b>Recorded</b> — the exchange's own ticks, when we have them. This is the
 * truth, and it is what a simulator should use to be worth trusting.</p>
 *
 * <p><b>Synthetic</b> — a plausible walk between the four numbers, for every
 * day we do not have ticks for. Which is most of them: the base runs from 2021
 * to 2026 and the tick export covers one month.</p>
 *
 * <p>Splitting them behind one method is what lets a replay cross from a day
 * with ticks into one without, mid-playback, and only get less faithful rather
 * than stopping.</p>
 */
public interface TickPath {

    /**
     * @param series the bars being replayed
     * @param index which bar is forming
     * @return the prices inside it, opening price first and closing price last
     */
    double[] pathFor(PriceSeries series, int index);
}
