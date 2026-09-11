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

import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePatterns;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.BarTint;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.PatternPalette;

import java.awt.Color;
import java.util.List;

/**
 * Paints each bar by the shape it formed: PFR, inside or 1-2-3.
 *
 * <h2>An indicator that draws nothing</h2>
 *
 * <p>Every other one here adds ink to the chart — a line, a band, a mark beside
 * a bar. This one adds none: it changes the colour of the bars that are already
 * there, which is how the Profit indicator it came from works and is the reason
 * it reads at a glance. A mark beside the bar would be one more thing to look
 * at; a bar in a different colour is the thing you were already looking at.
 *
 * <p>So {@link #valueAt} has nothing to say and says NaN, and the work happens
 * through {@link BarTint}, which the candle style asks as it paints.
 *
 * <h2>It has no parameters</h2>
 *
 * <p>Three bars, and three is not a setting: the PFR, the inside and the 1-2-3
 * are defined over exactly three bars, and a "period" here would be a different
 * indicator wearing the same name. What the reader chooses is which of the three
 * shapes to show and in what colour, and those live in {@link PatternPalette}
 * because they are a legend rather than decoration.
 */
public final class Patterns implements Overlay, BarTint {

    private CandlePattern[] patterns = new CandlePattern[0];

    private boolean visible = true;

    /**
     * @param parameters ignored; the catalog hands every indicator its numbers
     *                   and this one has none
     */
    public Patterns(int[] parameters) {
        // Nothing. Three bars is the definition, not a setting.
    }

    public Patterns() {
        this(new int[0]);
    }

    @Override
    public String nameKey() {
        return "overlay.patterns";
    }

    @Override
    public List<Integer> parameters() {
        return List.of();
    }

    /**
     * @return nothing, because it draws no lines
     *
     * <p>The contract is one colour per LINE, in the same order as the values,
     * and the canvas walks it to draw that many polylines. The six colours of
     * the patterns are not lines — they are what the BARS are painted in — so
     * listing them here would promise six polylines that have no values behind
     * them. Which is exactly what the catalog's own test says: "draws a line it
     * has no colour for, or the reverse".</p>
     */
    @Override
    public List<Color> colours() {
        return List.of();
    }

    /**
     * @return NaN, always
     *
     * <p>An indicator on the price axis with no line on it. The crosshair readout
     * and the legend both take NaN to mean "nothing to show here", which is
     * exactly right: this one has no value at a bar, it has a <b>classification</b>
     * of it, and {@link #at(int)} is where that comes out.</p>
     */
    @Override
    public double[] valueAt(int bar) {
        return new double[0];
    }

    @Override
    public void calculate(PriceSeries series) {
        patterns = CandlePatterns.detectAll(series);
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean visible) {
        this.visible = visible;
    }

    @Override
    public boolean fitsOnPrice() {
        return true;
    }

    /** @return what that bar formed, or {@link CandlePattern#NONE} */
    public CandlePattern patternAt(int bar) {
        return bar < 0 || bar >= patterns.length || patterns[bar] == null
                ? CandlePattern.NONE : patterns[bar];
    }

    @Override
    public Color at(int bar) {
        // The switches are read HERE and not stored at calculate time, so
        // turning a shape off repaints the chart that is already open instead of
        // waiting for the next series to be loaded.
        return PatternPalette.tintFor(patternAt(bar));
    }
}
