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

import br.com.jorge.reis.endeavourneo.domain.indicator.Pmo;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.BarTint;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import java.util.List;

/**
 * The TNO_PMO's other half: what it paints ON THE CANDLES.
 *
 * <p>{@code JorgeReis_TNO_PMO.src} draws in two places at once. Its two lines go
 * in a panel — that is {@code PriceMomentum} — and at the same time it colours
 * the price bars with {@code PaintBar} and drops a dot beside them with
 * {@code PlotText}. In the Profit one indicator reaches both surfaces; here an
 * overlay is placed on one, so the port is two entries in the catalogue. This is
 * the one that goes <b>on the price</b>, and it is the half that is actually
 * looked at.
 *
 * <h2>The priority is the original's, and it is the point</h2>
 *
 * <p>Three layers compete for one bar, and a bar has one colour:
 *
 * <ol>
 *   <li><b>crossing</b> — lime for a buy, red for a sell;</li>
 *   <li><b>exhaustion</b> — how many standard deviations the line is stretched:
 *       up goes orange, fuchsia, purple; down goes aqua, teal, navy. It
 *       overwrites a crossing;</li>
 *   <li><b>divergence</b> — blue for bullish, yellow for bearish, and it
 *       overwrites everything.</li>
 * </ol>
 *
 * <p>The colours are the NTSL's own, kept so the two screens can be read side by
 * side. Note when comparing sources: a raw integer colour in NTSL is
 * <b>BGR</b>, while {@code RGB(r,g,b)} takes them the usual way round.
 *
 * <h2>The divergence lands later here, and that is the look-ahead showing</h2>
 *
 * <p>{@link Pmo#divergences} files it under the bar that CONFIRMED it. The
 * original reads a pivot from the middle of a window that reaches into the
 * future, so its mark appears {@code wing} bars earlier — and repaints, which
 * its own header warns about. Side by side the marks are offset. That is not a
 * discrepancy to correct.
 *
 * @see br.com.jorge.reis.endeavourneo.ui.chart.study.pmo.PriceMomentum
 */
public final class MomentumBars implements Overlay, BarTint {

    /** {@code COR_VERDELIMAO} — clGreen vanished against the dark chart. */
    private static final Color BUY = new Color(0x00FF00);

    /** {@code COR_VERMELHO}. */
    private static final Color SELL = new Color(0xFF0000);

    /** Stretched UP: orange, fuchsia, purple. */
    private static final Color[] HIGH = {
        new Color(255, 128, 0), new Color(255, 0, 255), new Color(128, 0, 128)};

    /** Stretched DOWN: aqua, teal, navy. */
    private static final Color[] LOW = {
        new Color(0, 255, 255), new Color(0, 128, 128), new Color(0, 0, 128)};

    /** {@code COR_AZUL}: a bullish divergence. */
    private static final Color RISING = new Color(0x0000FF);

    /** {@code COR_AMARELO}: a bearish one. */
    private static final Color FALLING = new Color(0xFFFF00);

    /** How far off the candle the dot sits, in pixels. */
    private static final int AWAY = 6;

    private int change = Pmo.CHANGE;

    private int first = Pmo.FIRST;

    private int second = Pmo.SECOND;

    private int signalPeriod = Pmo.SIGNAL;

    private double scale = Pmo.SCALE;

    private int deviationWindow = Pmo.DEVIATION;

    private int wing = br.com.jorge.reis.endeavourneo.domain.indicator.Pivots.WING;

    private boolean paintBars = true;

    private boolean paintExhaustion = true;

    private boolean paintDivergence = true;

    private boolean showDots = true;

    private int dotSize = 7;

    private boolean useTurns;

    private boolean visible = true;

    private volatile int[] signals = new int[0];

    private volatile int[] stretch = new int[0];

    private volatile int[] diverging = new int[0];

    private volatile PriceSeries bars = PriceSeries.empty();

    public MomentumBars() {
    }

    public MomentumBars(int change, int first, int second, int signalPeriod) {
        setChange(change);
        setFirst(first);
        setSecond(second);
        setSignalPeriod(signalPeriod);
    }

    // ------------------------------------------------------------ the values

    public int change() {
        return change;
    }

    public void setChange(int value) {
        change = Math.max(1, value);
    }

    public int first() {
        return first;
    }

    public void setFirst(int value) {
        first = Math.max(1, value);
    }

    public int second() {
        return second;
    }

    public void setSecond(int value) {
        second = Math.max(1, value);
    }

    public int signalPeriod() {
        return signalPeriod;
    }

    public void setSignalPeriod(int value) {
        signalPeriod = Math.max(1, value);
    }

    public double scale() {
        return scale;
    }

    public void setScale(double value) {
        scale = value > 0 ? value : Pmo.SCALE;
    }

    public int deviationWindow() {
        return deviationWindow;
    }

    public void setDeviationWindow(int value) {
        deviationWindow = Math.max(1, value);
    }

    public int wing() {
        return wing;
    }

    public void setWing(int value) {
        wing = Math.max(1, value);
    }

    public boolean paintsBars() {
        return paintBars;
    }

    public void setPaintsBars(boolean value) {
        paintBars = value;
    }

    public boolean paintsExhaustion() {
        return paintExhaustion;
    }

    public void setPaintsExhaustion(boolean value) {
        paintExhaustion = value;
    }

    public boolean paintsDivergence() {
        return paintDivergence;
    }

    public void setPaintsDivergence(boolean value) {
        paintDivergence = value;
    }

    public boolean showsDots() {
        return showDots;
    }

    public void setShowsDots(boolean value) {
        showDots = value;
    }

    public int dotSize() {
        return dotSize;
    }

    public void setDotSize(int value) {
        dotSize = Math.max(1, value);
    }

    /**
     * @return whether the signal is the TURN of the line rather than its
     *         crossing — the original's {@code UsarInclinacao}
     *
     * <p>The turn comes earlier, always, and for the same reason it is wrong
     * more often: a line that turns towards the signal has not reached it.</p>
     */
    public boolean usesTurns() {
        return useTurns;
    }

    public void setUsesTurns(boolean value) {
        useTurns = value;
    }

    // ----------------------------------------------------------- the overlay

    @Override
    public String nameKey() {
        return "overlay.pmoBars";
    }

    @Override
    public boolean fitsOnPrice() {
        // THE WHOLE POINT. It has no line of its own -- it changes the bars.
        return true;
    }

    @Override
    public List<Integer> parameters() {
        return List.of(change, first, second, signalPeriod);
    }

    /**
     * @return nothing, always
     *
     * <p>It draws no line. What it has to say is said by the colour of the bar
     * and by a dot beside it, and an empty list is how an overlay says "I am
     * not a curve" — the same answer {@link Patterns} gives.</p>
     */
    @Override
    public List<Color> colours() {
        return List.of();
    }

    @Override
    public double[] valueAt(int bar) {
        return new double[0];
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean value) {
        visible = value;
    }

    @Override
    public String appearance() {
        return paintBars + ";" + paintExhaustion + ";" + paintDivergence
                + ";" + showDots + ";" + dotSize + ";" + useTurns
                + ";" + scale + ";" + deviationWindow + ";" + wing;
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] fields = text.split(";");

        // FIELD BY FIELD, each guarded on its own: a line written by an older
        // build is short, and one from a newer build may carry something this
        // one does not know. Neither may throw away what does make sense.
        setPaintsBars(readBoolean(at(fields, 0), paintBars));
        setPaintsExhaustion(readBoolean(at(fields, 1), paintExhaustion));
        setPaintsDivergence(readBoolean(at(fields, 2), paintDivergence));
        setShowsDots(readBoolean(at(fields, 3), showDots));
        setDotSize((int) readDouble(at(fields, 4), dotSize));
        setUsesTurns(readBoolean(at(fields, 5), useTurns));
        setScale(readDouble(at(fields, 6), scale));
        setDeviationWindow((int) readDouble(at(fields, 7), deviationWindow));
        setWing((int) readDouble(at(fields, 8), wing));
    }

    private static String at(String[] fields, int index) {
        return index < fields.length ? fields[index] : null;
    }

    private static double readDouble(String text, double fallback) {
        try {
            return text == null ? fallback : Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean readBoolean(String text, boolean fallback) {
        return text == null ? fallback : Boolean.parseBoolean(text);
    }

    // ----------------------------------------------------------- the numbers

    @Override
    public void calculate(PriceSeries series) {
        PriceSeries source = series == null ? PriceSeries.empty() : series;
        Pmo what = new Pmo(change, first, second, signalPeriod, scale);
        Pmo.Lines lines = what.over(source);

        int[] builtSignals = useTurns ? Pmo.turns(lines) : Pmo.crossings(lines);
        int[] builtStretch = Pmo.exhaustion(lines.line(),
                Pmo.deviation(lines.line(), deviationWindow));
        int[] builtDiverging = Pmo.divergences(source, lines.line(), wing);

        // PUBLISHED WHOLE, at the end, and the series with them: this runs off
        // the interface thread, and a repaint that read the new colours against
        // the old bars would tint the wrong candles.
        this.bars = source;
        this.signals = builtSignals;
        this.stretch = builtStretch;
        this.diverging = builtDiverging;
    }

    /**
     * @param bar which bar
     * @return the colour it must be drawn in, or null to leave it alone
     *
     * <p>The switches are read HERE and not at {@link #calculate}, so turning a
     * layer off repaints the chart that is already open instead of waiting for
     * the next series to load — which is how {@link Patterns} does it too.</p>
     */
    @Override
    public Color at(int bar) {
        if (!paintBars) {
            return null;
        }

        // Read ONCE into locals: a background recalculation replaces these
        // whole, and reading one against another is how that swap surfaces.
        int[] nowSignals = signals;
        int[] nowStretch = stretch;
        int[] nowDiverging = diverging;

        Color made = null;

        if (bar >= 0 && bar < nowSignals.length && nowSignals[bar] != 0) {
            made = nowSignals[bar] > 0 ? BUY : SELL;
        }

        // EXHAUSTION OVERWRITES A CROSSING, and divergence overwrites both. It
        // is the original's order, and it is not arbitrary: a crossing happens
        // every few bars, a third deviation of stretch happens rarely, and a
        // divergence rarest of all -- so the rarer thing gets the bar.
        if (paintExhaustion && bar >= 0 && bar < nowStretch.length && nowStretch[bar] != 0) {
            int step = Math.abs(nowStretch[bar]) - 1;

            made = nowStretch[bar] > 0 ? HIGH[step] : LOW[step];
        }

        if (paintDivergence && bar >= 0 && bar < nowDiverging.length && nowDiverging[bar] != 0) {
            made = nowDiverging[bar] > 0 ? RISING : FALLING;
        }

        return made;
    }

    /**
     * Draws the dot beside the bar that gave the signal.
     *
     * <p>The original's {@code PlotText("●", ...)}: below the candle for a buy,
     * above it for a sell. Under the candles rather than over them is fine and
     * is what the contract offers — the dot sits outside the body, past the
     * low or the high, so there is nothing on top of it to hide it.</p>
     */
    @Override
    public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
        if (!showDots || viewport == null) {
            return;
        }

        int[] nowSignals = signals;
        PriceSeries nowBars = bars;

        for (int bar = Math.max(0, from); bar < to && bar < nowSignals.length; bar++) {
            if (nowSignals[bar] == 0 || bar >= nowBars.size()) {
                continue;
            }

            boolean buying = nowSignals[bar] > 0;
            double price = buying ? nowBars.lowAt(bar) : nowBars.highAt(bar);
            double x = viewport.x(bar);
            double y = viewport.y(price) + (buying ? AWAY + dotSize : -AWAY - dotSize);

            g.setColor(buying ? BUY : SELL);
            g.fill(new Ellipse2D.Double(x - dotSize / 2.0, y - dotSize / 2.0,
                    dotSize, dotSize));
        }
    }
}
