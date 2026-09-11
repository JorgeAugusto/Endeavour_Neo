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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.Color;
import java.util.List;

/**
 * Something drawn on top of the price: a moving average, a band, a level.
 *
 * <p><b>{@link #valueAt(int)} is the method the rest of the chart is built
 * around.</b> One implementation feeds three things at once — the legend across
 * the top, the crosshair readout, and any future export. Chartsy reached the
 * same shape and calls it {@code getValues(chartFrame, i)}; arriving at it
 * independently is a good sign it is the right cut.</p>
 *
 * <p>An overlay computes once and is asked many times. Recomputing inside
 * {@code valueAt} would run the whole series on every mouse move, which is
 * exactly the sort of thing that makes a chart feel heavy without anything
 * obviously wrong in the code.</p>
 *
 * <p><b>Distinct from a study in its own panel</b> — an RSI or a MACD lives
 * below the price on its own scale, and putting one on the price axis would
 * squash the candles to nothing. That is a separate contract, and it is not
 * this one.</p>
 */
public interface Overlay {

    /** @return the bundle key for the name, e.g. {@code overlay.ema} */
    String nameKey();

    /**
     * @return the parameters, rendered inline in the legend
     *
     * <p>Shown as {@code [17 21 0 8 21]}, because a chart carrying three moving
     * averages is unreadable without knowing which is which — and having to open
     * a dialog to find out breaks the reading.</p>
     */
    List<Integer> parameters();

    /**
     * @return this indicator as a legend writes it: name, then parameters
     *
     * <p><b>Written once, and it used to be three copies of
     * {@code Messages.get(nameKey()) + " " + parameters()}</b> -- in the chart
     * canvas, in the legend and in the study pane. Each of them leaned on
     * {@code List.toString()} for the brackets and the comma-space between
     * numbers: the shape of the label was a detail of a collection's debug
     * output, in three places, and a dialog title beside them spelled the same
     * thing out by hand as {@code " [" + period() + "]"}.</p>
     */
    default String label() {
        StringBuilder text = new StringBuilder(
                br.com.jorge.reis.endeavourneo.platform.Messages.get(nameKey()));

        // NO BRACKETS WHEN THERE IS NOTHING IN THEM. An indicator with no
        // parameters is a real thing -- a three-bar pattern has no period to
        // choose -- and "Padroes []" is a pair of brackets asking the reader
        // what is missing from them.
        if (parameters().isEmpty()) {
            return text.toString();
        }

        text.append(" [");

        for (int i = 0; i < parameters().size(); i++) {
            if (i > 0) {
                text.append(' ');
            }

            text.append(parameters().get(i));
        }

        return text.append(']').toString();
    }

    /**
     * @return the label, and the indicator's own scale when it has one
     *
     * <p><b>The scale was written down and only one of the three readers asked
     * for it.</b> {@link #ownPeriod()} says in its own javadoc why it exists --
     * "two stochastics in one pane are the same word and the same numbers, and
     * without this they are two identical rows over two different lines" -- and
     * the study pane was the only place that ever put it on screen. The price
     * chart's legend and its remove menu asked for {@link #label()} alone, so
     * two averages of period twenty, one on the chart's bars and one on five
     * minutes, were two identical rows and two identical menu entries. The
     * setting was offered, stored, honoured in the arithmetic, and invisible.</p>
     *
     * <p>Here and not in {@code label()} because a dialog title wants the name
     * of the indicator and not the name of this instance of it, and because
     * {@code label()} is what a layout file compares.</p>
     */
    default String title() {
        String scale = ownPeriod();

        return scale == null || scale.isBlank() ? label()
                : br.com.jorge.reis.endeavourneo.platform.Messages.get(
                        "study.withScale", label(), scale);
    }

    /** @return one colour per line this overlay draws, in the same order as the values */
    List<Color> colours();

    /**
     * @param bar an index into the series
     * @return one value per line, or NaN where the overlay has nothing yet
     *
     * <p>NaN rather than zero during the warm-up: a 200-period average has no
     * value at bar 3, and zero would be a claim — plotted, it drags a line from
     * the bottom of the chart to the first real value.</p>
     */
    double[] valueAt(int bar);

    /**
     * Computes everything, once, against this series.
     *
     * @param series the data
     */
    void calculate(PriceSeries series);

    /** @return whether it should be drawn; the show/hide toggle */
    boolean isVisible();

    void setVisible(boolean visible);

    /**
     * Paints anything that is an AREA rather than a line, under the lines.
     *
     * <p>Empty by default, because almost every indicator here IS its lines and
     * the canvas draws those from {@link #valueAt}. Bollinger bands are the
     * exception: the shading between the two bands cannot be said as a
     * polyline, and letting the indicator paint it is smaller than teaching the
     * canvas about a kind of indicator it otherwise knows nothing about.</p>
     *
     * <p>Under, not over: whatever this paints is a background for the lines,
     * and painting it afterwards would hide them.</p>
     *
     * @param from the first visible bar
     * @param to one past the last visible bar
     */
    default void paintUnder(java.awt.Graphics2D g, Viewport viewport, int from, int to) {
        // Nothing. An indicator that is only lines has no area to paint.
    }

    /**
     * @return how the line is drawn: thickness and dash pattern
     *
     * <p>A default, so an indicator that has nothing to say about its own
     * appearance says nothing. One and a bit pixels, solid, rounded -- what
     * every line here was before any of them could be configured.</p>
     */
    default java.awt.Stroke stroke() {
        return new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                java.awt.BasicStroke.JOIN_ROUND);
    }

    /**
     * @return whether this can be drawn on the PRICE, and not only in a panel
     *
     * <p><b>No default, deliberately.</b> Every indicator has to answer, and
     * the compiler is what asks: an indicator that forgot to say would inherit
     * "yes", go onto the price, and draw a flat line along the floor of the
     * chart — in silence, which is exactly how that mistake looks. Making it a
     * question the author cannot skip is the whole of the guard.</p>
     *
     * <p>Yes for anything measured in price: an average, a band. No for
     * anything on a scale of its own — a stochastic runs nought to a hundred
     * whatever the index does, and putting the index on that axis would
     * flatten the candles instead.</p>
     *
     * <p>The reference implementation this was checked against says the same
     * thing the same way: MotiveWave's {@code @StudyHeader} carries an
     * {@code overlay} flag with no default value either.</p>
     */
    boolean fitsOnPrice();

    /**
     * @return the fixed vertical range as {@code {low, high}}, or null to fit
     *         whatever the values happen to be
     *
     * <p>Fixed for anything bounded by its own definition — a stochastic is
     * nought to a hundred whatever the market does, and letting a panel
     * rescale to the visible values would make eighty look like the top of the
     * world on a quiet afternoon. Null for the ones that are not bounded, a
     * MACD being the obvious one, and for everything drawn on the price, which
     * has the price's own scale and needs none of its own.</p>
     */
    default double[] bounds() {
        return null;
    }

    /**
     * @return the horizontal lines that belong to this indicator, if any
     *
     * <p>Part of the indicator and not of the panel, because they are part of
     * what it MEANS: twenty and eighty are where a stochastic says something,
     * and a panel that drew its own grid instead would be drawing lines that
     * mean nothing.</p>
     */
    default List<Level> levels() {
        return List.of();
    }

    /**
     * @return one stroke per line, in the same order as {@link #valueAt}
     *
     * <p>{@link #stroke()} gives one for the whole indicator, which is right
     * when every line of it is the same kind of thing. An indicator whose
     * second line is a smoothing of its first wants to say so by drawing them
     * differently, and one stroke cannot.</p>
     */
    default List<java.awt.Stroke> strokes() {
        return List.of(stroke());
    }

    /**
     * A horizontal line at a fixed value.
     *
     * @param at where it sits on the indicator's own scale
     * @param colour how it is drawn
     * @param stroke thickness and dash
     */
    record Level(double at, Color colour, java.awt.Stroke stroke) {

        /**
         * Refuses a level that is not a number.
         *
         * <p>{@code at} goes straight into {@code Math.round(y(at))} where the
         * pane draws it, and rounding NaN gives zero -- a line across the top of
         * the pane that no indicator asked for, at a price nothing has. Better
         * to fail where the level is made, with the value in the message.</p>
         */
        public Level {
            if (Double.isNaN(at) || Double.isInfinite(at)) {
                throw new IllegalArgumentException(at + " is not a level");
            }
        }
    }

    /**
     * @return the code of the scale this is computed on, or null to follow the chart
     *
     * <p>Here, and not only on the classes that have the setting, because the
     * scale is part of the indicator's NAME wherever it is listed. Two slow
     * stochastics in one pane -- the chart's own scale and five minutes -- are
     * the same word and the same numbers, and without this they are two
     * identical rows over two different lines.</p>
     *
     * <p>Null and not the chart's own code: an indicator that follows the chart
     * has nothing to add to its name, and saying the scale twice on every row
     * is how a legend stops being read.</p>
     */
    default String ownPeriod() {
        return null;
    }

    /**
     * @return everything about this indicator that is NOT its shape, as one line
     *
     * <p>Kept apart from {@link #parameters()} because the two are stored
     * differently and answer different questions. The parameters are the numbers
     * that decide what is computed; this is colour, dash and thickness -- what
     * it looks like once computed. A layout stores both, and an indicator that
     * came back in the right place wearing the wrong colour would be a layout
     * that only half worked.</p>
     *
     * <p>Empty by default: an indicator with nothing to remember remembers
     * nothing, and the stored line stays short.</p>
     */
    default String appearance() {
        return "";
    }

    /** @param text whatever {@link #appearance()} produced, possibly from an older version */
    default void applyAppearance(String text) {
        // Nothing to apply.
    }
}
