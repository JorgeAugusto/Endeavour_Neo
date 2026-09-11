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
 * The three-bar shapes the Profit indicator paints: PFR, inside and 1-2-3.
 *
 * <p>They are <b>mutually exclusive</b>: a bar gets at most one. Where two could
 * hold at once, the precedence written down in {@link CandlePatterns} decides.
 *
 * <h2>No colour here, and that is a change</h2>
 *
 * <p>The version this came from carried the RGB of each pattern in this enum,
 * with a note explaining that a plain integer is not a graphics library and so
 * the domain stayed clean. That was true and it is no longer the point: the
 * colours are the reader's to choose now, so they are not a property of the
 * shape at all. A pattern is what the three bars did; what it is painted in is a
 * decision made somewhere with a screen.
 */
public enum CandlePattern {

    /** No pattern: the bar is just a bar. */
    NONE(Family.NONE, 0),

    /**
     * Preço de fechamento de reversão, upward.
     *
     * <p>The bar makes the lowest low of the last three and still closes above
     * the previous close and above its own open. It is a rejection of the
     * bottom: whoever sold at the low ended the bar losing.
     */
    PFR_BULLISH(Family.PFR, 1),

    /**
     * The mirror: highest high of the last three, closing below the previous
     * close and below its own open.
     */
    PFR_BEARISH(Family.PFR, -1),

    /**
     * An inside bar that closed up.
     *
     * <p>High and low entirely within the previous bar. Not a direction — a
     * <b>compression</b>: the market stopped widening its range. The side
     * separates the ones that closed up from the ones that closed down, and the
     * shape is the same.
     */
    INSIDE_BULLISH(Family.INSIDE, 1),

    /** An inside bar that closed down. */
    INSIDE_BEARISH(Family.INSIDE, -1),

    /**
     * One-two-three to buy.
     *
     * <p>The middle bar of the three is the lowest low — a bottom pivot — and
     * the current bar closes up, confirming it.
     */
    ONE_TWO_THREE_BUY(Family.ONE_TWO_THREE, 1),

    /** One-two-three to sell: a top pivot on the middle bar, the current closing down. */
    ONE_TWO_THREE_SELL(Family.ONE_TWO_THREE, -1);

    /**
     * The three shapes, which is what the reader switches on and off.
     *
     * <p>Separate from the pattern itself because a reader turning off "inside"
     * means both of them: up and down are one shape seen from two sides, not two
     * shapes. Three checkboxes and six colours, and this is the difference.
     */
    public enum Family {

        /** Not a pattern, and not something that can be switched on. */
        NONE,

        PFR,

        INSIDE,

        ONE_TWO_THREE;

        /** @return the bundle key for the name of this shape */
        public String nameKey() {
            return "pattern.family." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private final Family family;

    private final int direction;

    CandlePattern(Family family, int direction) {
        this.family = family;
        this.direction = direction;
    }

    public Family family() {
        return family;
    }

    /** @return {@code +1} for the upward reading, {@code -1} for the downward, 0 for none */
    public int direction() {
        return direction;
    }

    public boolean isNone() {
        return this == NONE;
    }

    /** @return the bundle key for the name, e.g. {@code pattern.pfrBullish} */
    public String nameKey() {
        StringBuilder key = new StringBuilder("pattern.");
        boolean upper = false;

        for (char letter : name().toCharArray()) {
            if (letter == '_') {
                upper = true;

                continue;
            }

            key.append(upper ? Character.toUpperCase(letter)
                    : Character.toLowerCase(letter));
            upper = false;
        }

        return key.toString();
    }
}
