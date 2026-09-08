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
package br.com.jorge.reis.endeavourneo.ui.chart;

import br.com.jorge.reis.endeavourneo.ui.chart.style.CandleStyle;
import br.com.jorge.reis.endeavourneo.ui.chart.style.LineStyle;

import java.util.List;
import java.util.function.Supplier;

/**
 * The drawing styles, by the word a workspace file writes them as.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The canvas used to store the style as {@code style instanceof LineStyle ?
 * "line" : "candle"} and read it back with the mirror of that — two literals
 * naming the two styles that happen to exist, in a file whose own javadoc
 * promises that "a new style is a new class and this file does not change". A
 * third style would have needed the canvas edited, and until somebody
 * remembered, would have come back as candles with nothing said.</p>
 *
 * <p>So the list of styles is here, in one place, the way {@code PeriodCatalog}
 * already holds the scales. A style declares its own {@link ChartStyle#code}, so
 * adding one is adding a line HERE and nothing anywhere else.</p>
 */
public final class ChartStyles {

    private ChartStyles() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * Every style, in the order a toolbar should offer them.
     *
     * <p>Suppliers and not instances: a style is stateless today, and a shared
     * instance handed to two charts would be one object away from stopping being
     * so.</p>
     */
    private static final List<Supplier<ChartStyle>> ALL =
            List.of(CandleStyle::new, LineStyle::new);

    /** @return a fresh instance of every style, in order */
    public static List<ChartStyle> all() {
        return ALL.stream().map(Supplier::get).toList();
    }

    /**
     * @param code what a workspace file said, or null
     * @return that style, or the default one when nothing matches
     *
     * <p>The default rather than null, because the caller is restoring a chart
     * and a chart has to be drawn somehow. A word this version does not know is
     * a workspace written by a later one, and candles are the honest fallback:
     * they show every number the bar has.</p>
     */
    public static ChartStyle byCode(String code) {
        for (Supplier<ChartStyle> each : ALL) {
            ChartStyle style = each.get();

            if (style.code().equalsIgnoreCase(code)) {
                return style;
            }
        }

        return new CandleStyle();
    }
}
