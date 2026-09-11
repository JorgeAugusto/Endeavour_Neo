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
package br.com.jorge.reis.endeavourneo.domain.indicator;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

/**
 * Tops and bottoms: the bars the zigzag turns on.
 *
 * <p>A bar is a <b>top</b> when its high is the highest of the window that
 * reaches {@code wing} bars either side of it, and a <b>bottom</b> when its low
 * is the lowest. The zigzag his chart draws is called <i>2</i> because its wing
 * is two.
 *
 * <h2>A pivot is not known when it happens</h2>
 *
 * <p>It is known {@code wing} bars later, when the right-hand side of the window
 * has arrived, and that delay is the whole cost of using one in a rule: a bottom
 * at 10:26 becomes a fact at 10:28. {@link #confirmedAt} answers with the pivot
 * that became a fact at each bar, never the one that is still forming — which
 * is why a strategy can read this array from front to back without reading a
 * price it has not seen.
 *
 * <h2>A confirmed bottom has not been broken</h2>
 *
 * <p>Worth stating because it removes a case a rule would otherwise have to
 * handle: a bottom is confirmed precisely <i>because</i> the {@code wing} bars
 * after it did not go below it. So a stop placed at — or one tick beyond — a
 * freshly confirmed pivot cannot already be violated at the moment it is placed.
 *
 * <h2>What this is not</h2>
 *
 * <p>The chart's zigzag additionally makes the pivots ALTERNATE, so that the
 * drawn line really zigs and zags; two bottoms in a row with no top between
 * them are joined into one. That step is about drawing. For "the first bottom
 * after X" it changes nothing — the first candidate is the first bottom either
 * way — so it is left out rather than reimplemented here.
 *
 * @param wing how many bars either side the window reaches
 */
public record Pivots(int wing) {

    /** The wing his chart opens with, and what "zigzag 2" means. */
    public static final int WING = 2;

    /**
     * @param bar   where it is, not where it was learned
     * @param top   whether it is a top; otherwise a bottom
     * @param price the high of a top, the low of a bottom
     */
    public record Pivot(int bar, boolean top, double price) { }

    public Pivots {
        if (wing < 1) {
            throw new IllegalArgumentException("a wing of " + wing + " bars is not one");
        }
    }

    /** @return the zigzag his chart is born with */
    public static Pivots standard() {
        return new Pivots(WING);
    }

    /**
     * @param bars the series to read
     * @return one entry per bar: the pivot that became a FACT at that bar, or
     *         null. The pivot's own {@code bar} is {@code wing} earlier.
     *
     * <p>A bar can confirm a top and a bottom at once — a single bar that is
     * both the highest and the lowest of its window, which happens where the
     * window is flat. The top is answered, arbitrarily and consistently: a flat
     * window has no turn in it, so neither answer means anything and having two
     * would only give a caller a choice it cannot make well.</p>
     */
    public Pivot[] confirmedAt(PriceSeries bars) {
        int size = bars == null ? 0 : bars.size();
        Pivot[] made = new Pivot[size];

        for (int at = wing; at < size - wing; at++) {
            double high = bars.highAt(at);
            double low = bars.lowAt(at);
            boolean top = true;
            boolean bottom = true;

            for (int near = at - wing; near <= at + wing; near++) {
                if (near == at) {
                    continue;
                }

                if (bars.highAt(near) > high) {
                    top = false;
                }

                if (bars.lowAt(near) < low) {
                    bottom = false;
                }
            }

            if (!top && !bottom) {
                continue;
            }

            // FILED UNDER THE BAR THAT LEARNED IT, not the bar it happened on.
            // Filed under its own bar, a strategy walking this array forwards
            // would act on a pivot whose right-hand wing is still in the
            // future -- the plainest look-ahead there is, and invisible in a
            // result because every trade it produces looks reasonable.
            made[at + wing] = new Pivot(at, top, top ? high : low);
        }

        return made;
    }
}
