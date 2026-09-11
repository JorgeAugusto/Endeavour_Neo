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

/**
 * How a moving average is worked out.
 *
 * <p>Two, and they are not interchangeable. The <b>exponential</b> never forgets
 * — every close it ever saw is still in it, with a weight that decays — so it
 * turns sooner and keeps a tail of whatever came before. The <b>simple</b>
 * forgets exactly {@code period} bars ago, all at once, which makes it move when
 * an old bar <i>leaves</i> the window as much as when a new one arrives.</p>
 *
 * <p>On the same periods they cross on different bars, and the third argument of
 * NTSL's average functions is precisely this choice — the note on porting from
 * the Profit records that it is the one that decides whether two implementations
 * agree.</p>
 */
public enum Average {

    /** Weighted by recency, remembering everything. NTSL's exponential. */
    EXPONENTIAL,

    /**
     * The plain mean of the last {@code period} closes.
     *
     * <p>Undefined until there are that many, and said so with
     * {@link Double#NaN} rather than by averaging what there is: an average of
     * three closes called "the average of thirty-four" moves like a different
     * indicator and crosses in a different place.</p>
     */
    SIMPLE;

    /** @return the name a settings screen shows */
    public String label() {
        return this == EXPONENTIAL ? "EMA" : "SMA";
    }
}
