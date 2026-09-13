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
package br.com.jorge.reis.endeavourneo.ui.chart.study.pmo;

import br.com.jorge.reis.endeavourneo.domain.indicator.Pmo;
import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.OwnScale;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import java.awt.Color;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The TNO_PMO in a panel, ported from {@code JorgeReis_TNO_PMO.src}.
 *
 * <p>Two lines around zero: the main one and its signal. Everything it MEANS is
 * in {@link Pmo} — a strategy has to be able to read the same numbers, and
 * {@code domain} may not import {@code ui}. What is here is how it looks.
 *
 * <h2>The colours are the original's, on purpose</h2>
 *
 * <p>Green main line above the signal, red below; grey signal; an orange line at
 * zero. They are kept so that this pane and the Profit's can be read side by
 * side without translating — which is the whole point of porting an indicator
 * that already exists somewhere else. The NTSL writes its own as {@code clGreen}
 * and friends; note that a raw integer colour in NTSL is <b>BGR</b>, while
 * {@code RGB(r,g,b)} takes them the usual way round.
 *
 * <h2>What the original paints on the CANDLES is not here</h2>
 *
 * <p>The NTSL uses {@code PaintBar} to colour the price bars by crossing,
 * exhaustion and divergence, and {@code PlotText} to drop a dot at the signal.
 * A panel indicator here cannot reach the price bars — the contract is one
 * value per line per bar — so those three layers stay computed, in
 * {@link Pmo#crossings}, {@link Pmo#exhaustion} and {@link Pmo#divergences},
 * and unpainted. Doing it properly needs the price canvas to accept a per-bar
 * colour from an overlay, which is a change to {@code Overlay} and not to this
 * class, and it is worth doing on its own rather than smuggled in beneath a
 * port.
 *
 * <h2>The bands</h2>
 *
 * <p>Optional, off by default, exactly as {@code MostrarBandas(False)}: ±
 * {@link #deviations()} standard deviations of the main line. They are lines
 * and not levels — the deviation moves from bar to bar, so a fixed horizontal
 * rule could not draw them.
 */
public final class PriceMomentum implements Overlay {

    /** The periods the original is born with, re-exported so the catalog can read them. */
    public static final int CHANGE = Pmo.CHANGE;

    public static final int FIRST = Pmo.FIRST;

    public static final int SECOND = Pmo.SECOND;

    public static final int SIGNAL = Pmo.SIGNAL;

    private int change = CHANGE;

    private int first = FIRST;

    private int second = SECOND;

    private int signalPeriod = SIGNAL;

    private double scale = Pmo.SCALE;

    private int deviationWindow = Pmo.DEVIATION;

    private double deviations = 2.0;

    private boolean showSignal = true;

    private boolean showBands;

    private boolean showZero = true;

    private Color colour = new Color(0, 160, 0);

    private Color fallingColour = new Color(200, 0, 0);

    private Color signalColour = Color.GRAY;

    private Color zeroColour = new Color(255, 128, 0);

    private Color bandColour = new Color(120, 120, 160);

    private MovingAverage.Line line = MovingAverage.Line.SOLID;

    private MovingAverage.Line signalLine = MovingAverage.Line.SOLID;

    private MovingAverage.Line bandLine = MovingAverage.Line.DASHED;

    private float width = 2f;

    private float signalWidth = 1f;

    private float bandWidth = 1f;

    private String ownPeriod;

    private boolean visible = true;

    private volatile double[] values = new double[0];

    private volatile double[] signal = new double[0];

    private volatile double[] upper = new double[0];

    private volatile double[] lower = new double[0];

    public PriceMomentum() {
    }

    public PriceMomentum(int change, int first, int second, int signalPeriod) {
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
        // A SCALE OF ZERO FLATTENS IT to a line on zero, and the record would
        // refuse it anyway. One is the raw percentage.
        scale = value > 0 ? value : Pmo.SCALE;
    }

    public int deviationWindow() {
        return deviationWindow;
    }

    public void setDeviationWindow(int value) {
        deviationWindow = Math.max(1, value);
    }

    public double deviations() {
        return deviations;
    }

    public void setDeviations(double value) {
        deviations = value > 0 ? value : 2.0;
    }

    public boolean showsSignal() {
        return showSignal;
    }

    public void setShowsSignal(boolean value) {
        showSignal = value;
    }

    public boolean showsBands() {
        return showBands;
    }

    public void setShowsBands(boolean value) {
        showBands = value;
    }

    public boolean showsZero() {
        return showZero;
    }

    public void setShowsZero(boolean value) {
        showZero = value;
    }

    public Color colour() {
        return colour;
    }

    public void setColour(Color value) {
        colour = value == null ? colour : value;
    }

    public Color fallingColour() {
        return fallingColour;
    }

    public void setFallingColour(Color value) {
        fallingColour = value == null ? fallingColour : value;
    }

    public Color signalColour() {
        return signalColour;
    }

    public void setSignalColour(Color value) {
        signalColour = value == null ? signalColour : value;
    }

    public Color zeroColour() {
        return zeroColour;
    }

    public void setZeroColour(Color value) {
        zeroColour = value == null ? zeroColour : value;
    }

    public Color bandColour() {
        return bandColour;
    }

    public void setBandColour(Color value) {
        bandColour = value == null ? bandColour : value;
    }

    public MovingAverage.Line line() {
        return line;
    }

    public void setLine(MovingAverage.Line value) {
        line = value == null ? line : value;
    }

    public MovingAverage.Line signalLine() {
        return signalLine;
    }

    public void setSignalLine(MovingAverage.Line value) {
        signalLine = value == null ? signalLine : value;
    }

    public MovingAverage.Line bandLine() {
        return bandLine;
    }

    public void setBandLine(MovingAverage.Line value) {
        bandLine = value == null ? bandLine : value;
    }

    public float width() {
        return width;
    }

    public void setWidth(float value) {
        width = value > 0 ? value : width;
    }

    public float signalWidth() {
        return signalWidth;
    }

    public void setSignalWidth(float value) {
        signalWidth = value > 0 ? value : signalWidth;
    }

    public float bandWidth() {
        return bandWidth;
    }

    public void setBandWidth(float value) {
        bandWidth = value > 0 ? value : bandWidth;
    }

    @Override
    public String ownPeriod() {
        return ownPeriod;
    }

    public void setOwnPeriod(String code) {
        ownPeriod = code;
    }

    // ----------------------------------------------------------- the overlay

    @Override
    public String nameKey() {
        return "study.pmo";
    }

    @Override
    public boolean fitsOnPrice() {
        // It swings around zero and the WIN trades at 130 thousand. On the
        // price axis it would be a line along the floor.
        return false;
    }

    /**
     * @return the four periods, always
     *
     * <p>All four even when the signal is hidden, for the reason the stochastic
     * gives: these are what a layout STORES, and a list that shrank when a line
     * was switched off lost that line's period with it.</p>
     */
    @Override
    public List<Integer> parameters() {
        return List.of(change, first, second, signalPeriod);
    }

    @Override
    public List<Color> colours() {
        List<Color> made = new ArrayList<>();

        made.add(colour);

        if (showSignal) {
            made.add(signalColour);
        }

        if (showBands) {
            made.add(bandColour);
            made.add(bandColour);
        }

        return List.copyOf(made);
    }

    @Override
    public List<Stroke> strokes() {
        List<Stroke> made = new ArrayList<>();

        made.add(line.stroke(width));

        if (showSignal) {
            made.add(signalLine.stroke(signalWidth));
        }

        if (showBands) {
            made.add(bandLine.stroke(bandWidth));
            made.add(bandLine.stroke(bandWidth));
        }

        return List.copyOf(made);
    }

    /**
     * @return null, always
     *
     * <p>Unlike the stochastic, which is nought to a hundred by construction,
     * a PMO has no range of its own: it is a rate of change smoothed twice, and
     * how far it swings depends on the instrument, the scale and the minute.
     * The panel fits it to what is on screen.</p>
     */
    @Override
    public double[] bounds() {
        return null;
    }

    @Override
    public List<Level> levels() {
        // THE ZERO LINE IS PART OF THE INDICATOR, not decoration of the panel:
        // this oscillator is read against it -- above is bought momentum, below
        // sold -- and the original draws it in orange for the same reason.
        return showZero
                ? List.of(new Level(0, zeroColour, MovingAverage.Line.SOLID.stroke(1f)))
                : List.of();
    }

    @Override
    public double[] valueAt(int bar) {
        // Read ONCE into locals: a background recalculation replaces these
        // fields whole, and checking one array's length while reading another
        // is how that swap would surface -- out of bounds, on the painting
        // thread, at a moment nobody can reproduce.
        double[] nowLine = values;
        double[] nowSignal = signal;
        double[] nowUpper = upper;
        double[] nowLower = lower;

        int many = 1 + (showSignal ? 1 : 0) + (showBands ? 2 : 0);
        double[] made = new double[many];

        Arrays.fill(made, Double.NaN);

        if (bar < 0 || bar >= nowLine.length) {
            return made;
        }

        int at = 0;

        made[at++] = nowLine[bar];

        if (showSignal) {
            made[at++] = bar < nowSignal.length ? nowSignal[bar] : Double.NaN;
        }

        if (showBands) {
            made[at++] = bar < nowUpper.length ? nowUpper[bar] : Double.NaN;
            made[at] = bar < nowLower.length ? nowLower[bar] : Double.NaN;
        }

        return made;
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
    public Stroke stroke() {
        return line.stroke(width);
    }

    @Override
    public String appearance() {
        return line + ";" + hex(colour) + ";" + width
                + ";" + signalLine + ";" + hex(signalColour) + ";" + signalWidth
                + ";" + bandLine + ";" + hex(bandColour) + ";" + bandWidth
                + ";" + hex(zeroColour) + ";" + hex(fallingColour)
                + ";" + showSignal + ";" + showBands + ";" + showZero
                + ";" + scale + ";" + deviationWindow + ";" + deviations
                + ";" + (ownPeriod == null ? "chart" : ownPeriod);
    }

    @Override
    public void applyAppearance(String text) {
        if (text == null || text.isBlank()) {
            return;
        }

        String[] fields = text.split(";");

        // FIELD BY FIELD, each guarded on its own. A line written by an older
        // version is short, and one written by a newer one may carry something
        // this build does not know: neither may throw away the fields that DO
        // make sense.
        setLine(readLine(at(fields, 0), line));
        setColour(readColour(at(fields, 1), colour));
        setWidth(readFloat(at(fields, 2), width));
        setSignalLine(readLine(at(fields, 3), signalLine));
        setSignalColour(readColour(at(fields, 4), signalColour));
        setSignalWidth(readFloat(at(fields, 5), signalWidth));
        setBandLine(readLine(at(fields, 6), bandLine));
        setBandColour(readColour(at(fields, 7), bandColour));
        setBandWidth(readFloat(at(fields, 8), bandWidth));
        setZeroColour(readColour(at(fields, 9), zeroColour));
        setFallingColour(readColour(at(fields, 10), fallingColour));
        setShowsSignal(readBoolean(at(fields, 11), showSignal));
        setShowsBands(readBoolean(at(fields, 12), showBands));
        setShowsZero(readBoolean(at(fields, 13), showZero));
        setScale(readDouble(at(fields, 14), scale));
        setDeviationWindow((int) readDouble(at(fields, 15), deviationWindow));
        setDeviations(readDouble(at(fields, 16), deviations));

        String scaleCode = at(fields, 17);

        setOwnPeriod(scaleCode == null || "chart".equals(scaleCode) ? null : scaleCode);
    }

    private static String at(String[] fields, int index) {
        return index < fields.length ? fields[index] : null;
    }

    private static String hex(Color value) {
        return String.format("%06X", value.getRGB() & 0xFFFFFF);
    }

    private static Color readColour(String text, Color fallback) {
        try {
            return text == null ? fallback : new Color(Integer.parseInt(text, 16));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static MovingAverage.Line readLine(String text, MovingAverage.Line fallback) {
        try {
            return text == null ? fallback : MovingAverage.Line.valueOf(text);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static float readFloat(String text, float fallback) {
        try {
            return text == null ? fallback : Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
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
        int size = series == null ? 0 : series.size();
        double[] builtLine = new double[size];
        double[] builtSignal = new double[size];
        double[] builtUpper = new double[size];
        double[] builtLower = new double[size];

        if (size > 0) {
            build(series, builtLine, builtSignal, builtUpper, builtLower);
        }

        // PUBLISHED WHOLE, at the end: this runs off the interface thread, and
        // a repaint landing halfway through would read an array half full of
        // zeros -- which on an oscillator read against zero is not a gap, it is
        // a claim that momentum was exactly neutral.
        this.values = builtLine;
        this.signal = builtSignal;
        this.upper = builtUpper;
        this.lower = builtLower;
    }

    private void build(PriceSeries series, double[] intoLine, double[] intoSignal,
                       double[] intoUpper, double[] intoLower) {

        Aggregation coarser = OwnScale.of(ownPeriod);

        if (coarser == null) {
            computeOver(series, intoLine, intoSignal, intoUpper, intoLower);

            return;
        }

        PriceSeries coarse = coarser.apply(series);

        if (coarse.size() == 0) {
            Arrays.fill(intoLine, Double.NaN);
            Arrays.fill(intoSignal, Double.NaN);
            Arrays.fill(intoUpper, Double.NaN);
            Arrays.fill(intoLower, Double.NaN);

            return;
        }

        double[] coarseLine = new double[coarse.size()];
        double[] coarseSignal = new double[coarse.size()];
        double[] coarseUpper = new double[coarse.size()];
        double[] coarseLower = new double[coarse.size()];

        computeOver(coarse, coarseLine, coarseSignal, coarseUpper, coarseLower);

        // The last CLOSED coarse bar, never the one this one sits inside. The
        // rule and the reason are in OwnScale.
        OwnScale.map(series, coarse, coarseLine, intoLine);
        OwnScale.map(series, coarse, coarseSignal, intoSignal);
        OwnScale.map(series, coarse, coarseUpper, intoUpper);
        OwnScale.map(series, coarse, coarseLower, intoLower);

        // NO SMOOTHING BETWEEN COARSE POINTS, for the stochastic's reason: the
        // CROSSING of these two lines is the signal, and sloping them between
        // closed points moves where they cross to an instant the coarse scale
        // never reached.
    }

    /** The numbers, from the one place they are worked out. */
    private void computeOver(PriceSeries bars, double[] intoLine, double[] intoSignal,
                             double[] intoUpper, double[] intoLower) {

        Pmo what = new Pmo(change, first, second, signalPeriod, scale);
        Pmo.Lines lines = what.over(bars);

        System.arraycopy(lines.line(), 0, intoLine, 0, intoLine.length);
        System.arraycopy(lines.signal(), 0, intoSignal, 0, intoSignal.length);

        double[] spread = Pmo.deviation(lines.line(), deviationWindow);

        for (int i = 0; i < intoUpper.length; i++) {
            double far = i < spread.length ? deviations * spread[i] : Double.NaN;

            intoUpper[i] = far;
            intoLower[i] = -far;
        }
    }
}
