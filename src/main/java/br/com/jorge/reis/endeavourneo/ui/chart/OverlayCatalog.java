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

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.util.List;
import java.util.function.Function;

/**
 * What can be added to a chart, and with which parameters.
 *
 * <p>A list rather than a switch statement: adding an indicator is one entry
 * here plus one class, and nothing in the menu, the dialog or the canvas has to
 * learn about it. A {@code switch} in the dialog would be a second place to
 * forget.</p>
 *
 * <p><b>The defaults matter more than they look.</b> Someone inserting a moving
 * average for the first time should get something usable without picking numbers
 * they have no basis to pick. 17, 55 and 200 are the periods already in use
 * here.</p>
 */
public final class OverlayCatalog {

    /**
     * One kind of overlay, and how to build it.
     *
     * @param nameKey bundle key for the name
     * @param defaults the starting parameters; its size is how many it takes
     * @param minimum the lowest any parameter may be
     * @param maximum the highest
     * @param factory builds one from a set of parameters
     */
    public record Kind(String nameKey, List<Integer> defaults, int minimum, int maximum,
                       Function<int[], Overlay> factory) {

        /** @return the translated name, for the list and the legend */
        public String label() {
            return Messages.get(nameKey);
        }

        @Override
        public String toString() {
            return label();
        }
    }

    private static final List<Kind> KINDS = List.of(
            new Kind("overlay.movingAverage", List.of(9), 1, 2_000,
                    br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage::new),
            // Period only. The deviation is a setting of the dialog, not a
            // parameter in the legend's sense -- it can be 2,5, which does not
            // survive a list of integers, and the reference product titles the
            // indicator "[20]" for the same reason.
            new Kind("overlay.bollinger", List.of(20), 1, 2_000,
                    br.com.jorge.reis.endeavourneo.ui.chart.overlay.BollingerBands::new));

    private OverlayCatalog() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return everything that can be inserted, in the order it should be listed */
    public static List<Kind> kinds() {
        return KINDS;
    }
}
