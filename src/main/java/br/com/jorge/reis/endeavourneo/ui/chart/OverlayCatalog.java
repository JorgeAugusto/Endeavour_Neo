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

        /**
         * Takes its own copy of the list.
         *
         * <p>A record promises that what it holds does not change under it, and
         * without this it held the CALLER's list: whoever built it could go on
         * adding to what it was made of. Nobody does today, and that is
         * discipline rather than type -- which is the same thing this project
         * already learned about {@code Renko.Carry} and its mutable tally.</p>
         */
        public Kind {
            defaults = List.copyOf(defaults);
        }

        /**
         * @param parameters what a stored line or a dialog asked for
         * @return the same numbers, held to the range this kind declares
         *
         * <p>The range used to be obeyed in ONE place: the insert dialog, through
         * a SpinnerNumberModel. Both load paths -- OverlayCatalog.build and
         * ChartLayout.Entry.build -- handed whatever was in the file straight to
         * the factory, so a guard documented as "the lowest any parameter may
         * be" protected one door of three.</p>
         *
         * <p>Held rather than refused: a stored layout is the reader's work, and
         * a number outside the range is a file edited by hand or written by
         * another version -- not a reason to lose the chart. Each indicator still
         * defends itself after this, and now the three agree.</p>
         */
        public int[] held(List<Integer> parameters) {
            int[] numbers = new int[parameters.size()];

            for (int i = 0; i < numbers.length; i++) {
                numbers[i] = Math.max(minimum, Math.min(maximum, parameters.get(i)));
            }

            return numbers;
        }


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
                    br.com.jorge.reis.endeavourneo.ui.chart.overlay.BollingerBands::new),

            // The ceiling is this indicator's own and not the two thousand the
            // others carry: the fit is redone from the viewport on every frame,
            // and the indicator it was brought over from stops at four hundred
            // bars for the same reason. Ninety is what the reference chart is
            // set to.
            new Kind("overlay.regression",
                    List.of(br.com.jorge.reis.endeavourneo.ui.chart.overlay
                            .RegressionChannel.PERIOD),
                    2, br.com.jorge.reis.endeavourneo.ui.chart.overlay
                            .RegressionChannel.MOST_BARS,
                    br.com.jorge.reis.endeavourneo.ui.chart.overlay.RegressionChannel::new),

            // The ones that live in a panel are in the SAME list. Which of
            // them can go on the price is not decided by which list they are
            // in -- each says so itself, in fitsOnPrice, and the insert dialog
            // asks. Two lists were how the destination used to be decided, and
            // it meant a moving average could never be put in a panel.
            new Kind("study.rsi",
                    List.of(br.com.jorge.reis.endeavourneo.ui.chart.study.rsi
                            .RelativeStrength.PERIOD), 1, 2_000,
                    numbers -> new br.com.jorge.reis.endeavourneo.ui.chart.study.rsi
                            .RelativeStrength(first(numbers, br.com.jorge.reis.endeavourneo.ui.chart.study.rsi.RelativeStrength.PERIOD))),

            new Kind("study.stochastic",
                    List.of(br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic
                            .SlowStochastic.PERIOD,
                            br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic
                                    .SlowStochastic.AVERAGE), 1, 2_000,
                    numbers -> new br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic
                            .SlowStochastic(first(numbers, br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic.PERIOD),
                                    second(numbers, br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic.AVERAGE))));

    private static int first(int[] numbers, int fallback) {
        return numbers.length > 0 ? numbers[0] : fallback;
    }

    private static int second(int[] numbers, int fallback) {
        return numbers.length > 1 ? numbers[1] : fallback;
    }

    private OverlayCatalog() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return everything that can be inserted, in the order it should be listed */
    public static List<Kind> kinds() {
        return KINDS;
    }

    /**
     * @param nameKey what a stored line called it
     * @param parameters the numbers stored beside it
     * @return that indicator, or null when this version does not have it
     *
     * <p>Null rather than an exception. A workspace written by a later version
     * can name an indicator this one lacks, and refusing to open the chart at
     * all would turn one missing line into a lost window.</p>
     */
    public static Overlay build(String nameKey, List<Integer> parameters) {
        for (Kind kind : KINDS) {
            if (!kind.nameKey().equals(nameKey)) {
                continue;
            }

            int[] numbers = kind.held(parameters);

            return kind.factory().apply(numbers);
        }

        return null;
    }
}
