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

import java.util.Arrays;

/**
 * Reading an indicator of a LARGER scale on a series of a smaller one.
 *
 * <p>The obvious way is wrong, and it is wrong in the direction that flatters a
 * backtest: asking "which coarse bar contains this fine bar" and taking its
 * value hands the strategy a bar that has not finished. At 10:01 the
 * five-minute bar running from 10:00 to 10:05 still has four minutes of trading
 * to absorb, and its close — the number the average is made of — is a price
 * nobody has seen yet.
 *
 * <p>So the answer is always <b>the last coarse bar that had CLOSED</b> at that
 * instant. At 10:01 that is the bar of 09:55, and it stays the bar of 09:55
 * until 10:05.
 *
 * <p>The chart solves the same problem in {@code ui.chart.OwnScale}, whose own
 * javadoc calls it the trap this project has already paid for once. This is that
 * rule, in the domain, where the engine can reach it.
 */
public final class LastClosed {

    private LastClosed() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Spreads a coarse reading over the fine bars.
     *
     * @param fine   the bars the answer is wanted for
     * @param coarse the same stretch folded to a larger scale
     * @param value  one value per coarse bar
     * @return one value per fine bar, NaN until a coarse bar has closed
     */
    public static double[] spread(PriceSeries fine, PriceSeries coarse, double[] value) {
        int size = fine == null ? 0 : fine.size();
        double[] made = new double[size];

        if (coarse == null || coarse.size() == 0) {
            Arrays.fill(made, Double.NaN);

            return made;
        }

        int closed = -1;

        for (int bar = 0; bar < size; bar++) {
            // A RUNNING POINTER and not a search per bar: both series run
            // forwards, so the answer only ever moves forwards too. Asked as a
            // search once per bar, this walks the coarse series once for every
            // fine bar there is.
            //
            // The condition is "the bar AFTER the next one has already begun",
            // which is what makes the next one finished.
            while (closed + 1 < coarse.size() - 1
                    && coarse.timeAt(closed + 2) <= fine.timeAt(bar)) {
                closed++;
            }

            made[bar] = closed < 0 ? Double.NaN : value[closed];
        }

        return made;
    }
}
