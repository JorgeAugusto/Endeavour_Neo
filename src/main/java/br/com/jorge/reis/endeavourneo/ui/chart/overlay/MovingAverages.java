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
package br.com.jorge.reis.endeavourneo.ui.chart.overlay;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * A fan of exponential moving averages over the close.
 *
 * <p>The first overlay, and it exists as much to prove the contract as to be
 * useful: several lines, a warm-up during which there is nothing to say, and
 * parameters worth showing in the legend. Anything the contract gets wrong shows
 * up here first.</p>
 */
public final class MovingAverages implements Overlay {

    private static final Color[] PALETTE = {
            new Color(0x4F8FD4), new Color(0xD48A4F),
            new Color(0x6FB86F), new Color(0xB86FB2),
            new Color(0xC4A83E),
    };

    private final int[] periods;

    private double[][] values;

    private boolean visible = true;

    /**
     * @param periods one per line, in the order they should be drawn
     */
    public MovingAverages(int... periods) {
        this.periods = periods.clone();
        this.values = new double[periods.length][0];
    }

    @Override
    public String nameKey() {
        return "overlay.ema";
    }

    @Override
    public List<Integer> parameters() {
        List<Integer> list = new ArrayList<>(periods.length);

        for (int period : periods) {
            list.add(period);
        }

        return list;
    }

    @Override
    public List<Color> colours() {
        List<Color> list = new ArrayList<>(periods.length);

        for (int i = 0; i < periods.length; i++) {
            list.add(PALETTE[i % PALETTE.length]);
        }

        return list;
    }

    @Override
    public double[] valueAt(int bar) {
        double[] row = new double[periods.length];

        for (int line = 0; line < periods.length; line++) {
            row[line] = bar >= 0 && bar < values[line].length
                    ? values[line][bar]
                    : Double.NaN;
        }

        return row;
    }

    @Override
    public void calculate(PriceSeries series) {
        values = new double[periods.length][series.size()];

        for (int line = 0; line < periods.length; line++) {
            int period = Math.max(1, periods[line]);
            double weight = 2.0 / (period + 1);
            double average = 0.0;

            for (int i = 0; i < series.size(); i++) {
                double close = series.closeAt(i);

                if (i == 0) {
                    average = close;
                } else {
                    average += weight * (close - average);
                }

                // NaN until the average has seen its own period. An exponential
                // average produces a number from the first bar, but that number
                // is mostly the seed: plotting it draws a line converging out of
                // nowhere, which reads as signal and is not.
                values[line][i] = i + 1 >= period ? average : Double.NaN;
            }
        }
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
