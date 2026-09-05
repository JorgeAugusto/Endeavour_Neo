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

import java.util.ArrayList;
import java.util.List;

/**
 * A named set of indicators, as data rather than as objects.
 *
 * <p>A layout is what to build, not what was built: the kind, its parameters and
 * whether it is showing. Holding live {@link Overlay} instances instead would
 * mean two charts on the same layout sharing computed values from different
 * series — plausible on screen and wrong.</p>
 *
 * <p><b>Layouts are shared between charts, and that is deliberate.</b> Naming a
 * set of indicators is only worth the trouble if the name can be applied
 * elsewhere; a layout private to one window is just that window's state. The
 * consequence is that changing the indicators of a chart changes the layout for
 * every chart using it — which is exactly why "duplicate" exists, and why the
 * reference product is full of names like <i>Cópia de Limpo Ok</i>.</p>
 *
 * @param name what the tab shows
 * @param entries the indicators, in the order they are drawn
 */
public record ChartLayout(String name, List<Entry> entries, List<Pane> panes) {

    /** A layout written before panes existed carries none. */
    public ChartLayout(String name, List<Entry> entries) {
        this(name, entries, List.of());
    }

    /**
     * One indicator in a pane of its own, as a layout remembers it.
     *
     * @param kindKey the catalogue key, e.g. {@code study.stochastic}
     * @param parameters the numbers that decide what is computed
     * @param appearance everything else, as one line
     * @param height how tall the pane was
     * @param minimised whether it was folded away
     *
     * <p>The height and the minimised flag belong to the LAYOUT and not to the
     * chart, for the same reason the indicators do: a layout is a way of
     * looking at something, and how much room the stochastic got is part of
     * that way.</p>
     */
    public record Pane(List<Entry> entries, int height, boolean minimised) {

        /** A pane that holds one indicator, which is how they all started. */
        public Pane(String kindKey, List<Integer> parameters, String appearance,
                    int height, boolean minimised) {
            this(List.of(new Entry(kindKey, parameters, true, appearance)),
                    height, minimised);
        }

        /**
         * @return the indicators of this pane, skipping any kind this version
         *         does not have
         *
         * <p>Skipping and not failing: a layout written by a later version can
         * name an indicator this one lacks, and refusing to open the chart
         * would turn one missing line into a lost window.</p>
         */
        public List<br.com.jorge.reis.endeavourneo.ui.chart.Overlay> build() {
            List<br.com.jorge.reis.endeavourneo.ui.chart.Overlay> found =
                    new ArrayList<>(entries.size());

            for (Entry entry : entries) {
                br.com.jorge.reis.endeavourneo.ui.chart.Overlay study =
                        br.com.jorge.reis.endeavourneo.ui.chart.study.StudyCatalog
                                .build(entry.kindKey(), entry.parameters());

                if (study != null) {
                    study.applyAppearance(entry.appearance());
                    found.add(study);
                }
            }

            return found;
        }
    }

    /**
     * One indicator inside a layout.
     *
     * @param kindKey the catalogue key, e.g. {@code overlay.ema}
     * @param parameters its periods
     * @param visible whether the eye is open
     */
    public record Entry(String kindKey, List<Integer> parameters, boolean visible,
                       String appearance) {

        public Entry(String kindKey, List<Integer> parameters, boolean visible) {
            this(kindKey, parameters, visible, "");
        }

        /** @return this entry as an overlay, or null when the kind is unknown */
        public Overlay build() {
            for (OverlayCatalog.Kind kind : OverlayCatalog.kinds()) {
                if (!kind.nameKey().equals(kindKey)) {
                    continue;
                }

                int[] values = new int[parameters.size()];

                for (int i = 0; i < values.length; i++) {
                    values[i] = parameters.get(i);
                }

                Overlay overlay = kind.factory().apply(values);

                overlay.applyAppearance(appearance);
                overlay.setVisible(visible);

                return overlay;
            }

            // A kind that no longer exists -- removed between versions -- is
            // skipped rather than throwing. A saved layout must not stop a chart
            // from opening because one of its indicators went away.
            return null;
        }
    }

    /** @return an empty layout under that name */
    public static ChartLayout empty(String name) {
        return new ChartLayout(name, List.of(), List.of());
    }

    /**
     * @param name the layout's name
     * @param overlays what a chart currently shows
     * @return a layout capturing them
     */
    public static ChartLayout of(String name, List<Overlay> overlays) {
        return of(name, overlays, List.of());
    }

    /**
     * @param panes what each indicator pane was showing, and how tall
     *
     * <p>Overlays and panes together, because a layout is one answer to "how am
     * I looking at this" and splitting it would let half of it change without
     * the other half.</p>
     */
    public static ChartLayout of(String name, List<Overlay> overlays, List<Pane> panes) {
        List<Entry> entries = new ArrayList<>(overlays.size());

        for (Overlay overlay : overlays) {
            entries.add(new Entry(overlay.nameKey(), overlay.parameters(), overlay.isVisible(),
                    overlay.appearance()));
        }

        return new ChartLayout(name, List.copyOf(entries), List.copyOf(panes));
    }

    /** @return every study this layout describes, pane by pane */
    public List<br.com.jorge.reis.endeavourneo.ui.chart.Overlay> studies() {
        List<br.com.jorge.reis.endeavourneo.ui.chart.Overlay> found = new ArrayList<>();

        for (Pane pane : panes) {
            found.addAll(pane.build());
        }

        return found;
    }

    /** @return the overlays this layout describes, skipping any unknown kind */
    public List<Overlay> build() {
        List<Overlay> overlays = new ArrayList<>(entries.size());

        for (Entry entry : entries) {
            Overlay overlay = entry.build();

            if (overlay != null) {
                overlays.add(overlay);
            }
        }

        return overlays;
    }

    public ChartLayout renamedTo(String newName) {
        return new ChartLayout(newName, entries, panes);
    }
}
