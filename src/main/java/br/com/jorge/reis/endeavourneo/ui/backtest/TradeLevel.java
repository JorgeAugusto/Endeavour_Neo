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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.Color;

/**
 * What a fill <i>was</i>, so the chart can say it in a colour and a word.
 *
 * <p>Read off the NTSL verb the fill carries, which is why the verb is on
 * {@link Fill} at all. {@code SellToCoverStop} is a stop and
 * {@code SellToCoverLimit} is a target — the engine already knows the
 * difference, and without carrying it here the chart would have to guess from
 * the price, which is exactly backwards: whether a level was a stop is a fact
 * about the order, not about where the price ended up.</p>
 *
 * <h2>The colours say the outcome, not the direction</h2>
 *
 * <p>Straight from the previous project, and worth keeping for its reason: red
 * for the stop and green for the target hold for a long and for a short alike,
 * because what is being read is "here I died" and "here I took it" — not which
 * way the position faced. The four values are the same four.</p>
 */
enum TradeLevel {

    /** An order that opened or added to the position. */
    ENTRY(new Color(0xB0BEC5), "backtest.level.entry"),

    /** A cover stop: where it gave up. */
    STOP(new Color(0xEF5350), "backtest.level.stop"),

    /** A cover limit: where it took the money. */
    TARGET(new Color(0x66BB6A), "backtest.level.target"),

    /**
     * A cover at market, or a {@code ClosePosition}.
     *
     * <p>Its own colour because it is its own thing: an exit by <b>time</b>
     * rather than by price — the end of the session, a circuit breaker. Painted
     * as a target it would read as a plan that worked.</p>
     */
    EXIT(new Color(0x64B5F6), "backtest.level.exit");

    private final Color colour;

    private final String key;

    TradeLevel(Color colour, String key) {
        this.colour = colour;
        this.key = key;
    }

    Color colour() {
        return colour;
    }

    String label() {
        return Messages.get(key);
    }

    /**
     * @param fill what executed
     * @return which kind of level it marks
     */
    static TradeLevel of(Fill fill) {
        String verb = fill.verb();

        if (!verb.contains("ToCover") && !verb.equals("ClosePosition")
                && !verb.equals("ReversePosition")) {
            return ENTRY;
        }

        if (verb.endsWith("Stop")) {
            return STOP;
        }

        if (verb.endsWith("Limit")) {
            return TARGET;
        }

        return EXIT;
    }
}
