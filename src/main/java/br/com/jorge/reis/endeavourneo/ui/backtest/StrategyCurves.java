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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The strategy's own working, drawn on the price.
 *
 * <p>Not an indicator the chart computed — the numbers the strategy decided
 * from, recorded bar by bar while it decided. The difference is the point: an
 * average the chart recomputes is seeded its own way and can put the crossing
 * on the neighbouring bar, and which bar the crossing fell on is exactly what a
 * reader checking an entry is checking.</p>
 *
 * <h2>It draws itself through the ordinary contract</h2>
 *
 * <p>One colour per curve and one value per bar, which is all the canvas asks
 * for — so these lines get the chart's own drawing, its own scale and its own
 * legend, with nothing here painting anything by hand. A value of
 * {@link Double#NaN} breaks the line, which is how the bars before an average
 * had enough history are said.</p>
 */
public final class StrategyCurves implements Overlay {

    /**
     * Enough for any strategy that should be drawing lines at all.
     *
     * <p>Chosen to read as a set rather than as a rainbow: the first two are the
     * pair a crossing needs and are told apart at a glance, and the rest step
     * away in hue without any of them shouting.</p>
     */
    private static final Color[] PALETTE = {
            new Color(0x3C78C8), new Color(0xD98E32), new Color(0x7A4FA3),
            new Color(0x2E8B57), new Color(0xB03A48), new Color(0x4E7C8A),
    };

    private final List<String> names = new ArrayList<>();

    private final List<double[]> lines = new ArrayList<>();

    private boolean visible = true;

    /** @param curves what the strategy computed, by name, in drawing order */
    public void show(Map<String, double[]> curves) {
        names.clear();
        lines.clear();

        if (curves == null) {
            return;
        }

        for (Map.Entry<String, double[]> each : curves.entrySet()) {
            if (names.size() == PALETTE.length) {
                // Past the palette the lines would repeat colours, and two lines
                // of the same colour are worse than one line missing: the reader
                // reads a crossing that is not there.
                break;
            }

            names.add(each.getKey());
            lines.add(each.getValue() == null ? new double[0] : each.getValue());
        }
    }

    /** Forgets everything, for a chart with no run on it. */
    public void clear() {
        names.clear();
        lines.clear();
    }

    @Override
    public String nameKey() {
        return "backtest.curves";
    }

    @Override
    public List<Integer> parameters() {
        return List.of();
    }

    /**
     * The names the strategy gave, joined — not the generic label.
     *
     * <p>"EMA 17 · EMA 34" says which two lines those are; the overlay's own
     * name would say only that a strategy drew something.</p>
     */
    @Override
    public String label() {
        return names.isEmpty()
                ? br.com.jorge.reis.endeavourneo.platform.Messages.get(nameKey())
                : String.join("  ·  ", names);
    }

    @Override
    public List<Color> colours() {
        List<Color> found = new ArrayList<>(names.size());

        for (int i = 0; i < names.size(); i++) {
            found.add(PALETTE[i]);
        }

        return found;
    }

    @Override
    public double[] valueAt(int bar) {
        double[] values = new double[lines.size()];

        for (int i = 0; i < lines.size(); i++) {
            double[] line = lines.get(i);

            values[i] = bar >= 0 && bar < line.length ? line[bar] : Double.NaN;
        }

        return values;
    }

    @Override
    public void calculate(PriceSeries series) {
        // Nothing to compute: the numbers arrive already decided from, out of a
        // run. Recomputing them here would be the very mistake this exists to
        // avoid.
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean wanted) {
        visible = wanted;
    }

    @Override
    public boolean fitsOnPrice() {
        return true;
    }
}
