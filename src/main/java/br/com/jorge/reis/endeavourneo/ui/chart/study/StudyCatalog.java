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
package br.com.jorge.reis.endeavourneo.ui.chart.study;

import br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic;

import java.util.List;
import java.util.function.Function;

/**
 * Every indicator that can live in a pane, and how to build one from numbers.
 *
 * <h2>Why a catalogue and not a constructor</h2>
 *
 * <p>A workspace stores what was open as text — a key and a few numbers — and
 * something has to turn that back into an object without the reader of the
 * workspace knowing which classes exist. This is that something, and it is the
 * one place a new indicator has to be mentioned to become restorable.</p>
 *
 * <p>An unknown key gives null rather than an exception. A workspace written by
 * a later version can name an indicator this one does not have, and refusing to
 * open the chart at all would turn one missing pane into a lost window.</p>
 */
public final class StudyCatalog {

    /**
     * @param nameKey the bundle key the study answers with
     * @param defaults the parameters a fresh one starts with
     * @param factory builds one from a list of parameters
     */
    public record Kind(String nameKey, List<Integer> defaults,
                       Function<List<Integer>, Study> factory) { }

    private static final List<Kind> KINDS = List.of(
            new Kind("study.stochastic",
                    List.of(SlowStochastic.PERIOD, SlowStochastic.AVERAGE),
                    numbers -> new SlowStochastic(
                            numbers.isEmpty() ? SlowStochastic.PERIOD : numbers.get(0),
                            numbers.size() < 2 ? SlowStochastic.AVERAGE : numbers.get(1))));

    private StudyCatalog() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /** @return everything that can be put in a pane, in the order it should be listed */
    public static List<Kind> kinds() {
        return KINDS;
    }

    /**
     * @param nameKey what the stored line called it
     * @param parameters the numbers stored beside it
     * @return that indicator, or null when this version does not have it
     */
    public static Study build(String nameKey, List<Integer> parameters) {
        for (Kind kind : KINDS) {
            if (kind.nameKey().equals(nameKey)) {
                return kind.factory().apply(parameters);
            }
        }

        return null;
    }
}
