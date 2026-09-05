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

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * One indicator, in its own strip under the chart.
 *
 * <h2>It borrows the chart's horizontal, and nothing else</h2>
 *
 * <p>The x of a bar comes from the chart's own {@link Viewport}, so a peak here
 * sits exactly under the candle that made it — scrolling, zooming and the
 * right-hand price strip all line up without either side being told. The y is
 * this pane's alone, from the study's {@link Study#bounds()}.</p>
 *
 * <p>That is the whole of the coupling: a pane asks the canvas where bar 40 is
 * and asks its own study what to draw there. It does not know what else is
 * stacked with it, and the canvas does not know it exists beyond having to
 * repaint it.</p>
 *
 * <h2>Minimised, never maximised</h2>
 *
 * <p>Minimising leaves the header and takes the plot away; the height it
 * releases goes to the CHART, not to the neighbouring panes, which keep theirs.
 * That is the behaviour asked for and it is the one that makes a stack
 * predictable: hiding one indicator does not silently resize the other two.</p>
 *
 * <p>There is no maximise. A pane that could swallow the window would leave the
 * price behind, and an indicator without the price it is measuring is a graph
 * of nothing.</p>
 */
public final class StudyPane extends JComponent {

    private static final long serialVersionUID = 1L;

    /** The strip along the top: name, values, and the two buttons. */
    private static final int HEADER = 20;

    /** How much of the top edge grabs, for dragging the pane taller or shorter. */
    private static final int GRIP = 4;

    private static final int SHORTEST = HEADER + 30;

    private final transient ChartCanvas canvas;

    private final transient Study study;

    private final transient Runnable onChanged;

    private int height = 110;

    private boolean minimised;

    /** Where the drag started, or -1 when nothing is being dragged. */
    private transient int grabbedAt = -1;

    private transient int grabbedHeight;

    public StudyPane(ChartCanvas canvas, Study study, Runnable onChanged) {
        this.canvas = canvas;
        this.study = study;
        this.onChanged = onChanged == null ? () -> { } : onChanged;

        setToolTipText(Messages.get("study.paneHint"));

        MouseAdapter mouse = new MouseAdapter() {

            @Override
            public void mouseMoved(MouseEvent e) {
                setCursor(Cursor.getPredefinedCursor(e.getY() <= GRIP && !minimised
                        ? Cursor.N_RESIZE_CURSOR : Cursor.DEFAULT_CURSOR));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (closeAt(e.getX())) {
                    remove();

                    return;
                }

                if (minimiseAt(e.getX())) {
                    setMinimised(!minimised);

                    return;
                }

                if (e.getY() <= GRIP && !minimised) {
                    grabbedAt = e.getYOnScreen();
                    grabbedHeight = height;
                }
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (grabbedAt < 0) {
                    return;
                }

                // Dragging the TOP edge, so pulling up makes it taller.
                setHeight(grabbedHeight + (grabbedAt - e.getYOnScreen()));
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                grabbedAt = -1;
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && e.getY() > GRIP && e.getY() < HEADER) {
                    // The header, double-clicked: the same gesture every title
                    // bar in this application already answers to.
                    setMinimised(!minimised);
                }
            }
        };

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    public Study study() {
        return study;
    }

    /** @return how tall this pane wants to be right now */
    public int wantedHeight() {
        return minimised ? HEADER : Math.max(SHORTEST, height);
    }

    public void setHeight(int pixels) {
        int wanted = Math.max(SHORTEST, pixels);

        if (wanted != height) {
            height = wanted;

            onChanged.run();
        }
    }

    public boolean isMinimised() {
        return minimised;
    }

    public void setMinimised(boolean value) {
        if (minimised != value) {
            minimised = value;

            onChanged.run();
        }
    }

    /**
     * Called when this pane's own close button is pressed.
     *
     * <p>Through the stack, never straight out of the container: the canvas
     * repaints its panes from a list it keeps, and a pane torn off the screen
     * without leaving that list is a component being painted for ever after it
     * stopped existing on screen.</p>
     */
    private void remove() {
        if (getParent() instanceof StudyStack stack) {
            stack.hide(this);
        }
    }

    // ------------------------------------------------------------- the boxes

    private int closeLeft() {
        return getWidth() - 18;
    }

    private int minimiseLeft() {
        return getWidth() - 36;
    }

    private boolean closeAt(int x) {
        return x >= closeLeft() && x < closeLeft() + 14;
    }

    private boolean minimiseAt(int x) {
        return x >= minimiseLeft() && x < minimiseLeft() + 14;
    }

    // ----------------------------------------------------------- the drawing

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            paintHeader(g);

            if (!minimised) {
                paintPlot(g);
            }

            // AFTER the plot, because the scale strip on the right fills its
            // whole column -- drawn before, the two buttons were painted and
            // then covered by it, which is a bug that only shows on screen.
            paintButton(g, minimiseLeft(), minimised);
            paintClose(g, closeLeft());

            // The seam with whatever is above, and the thing the pointer grabs.
            g.setColor(ChartColors.grid());
            g.drawLine(0, 0, getWidth(), 0);
        } finally {
            g.dispose();
        }
    }

    private void paintHeader(Graphics2D g) {
        FontMetrics metrics = g.getFontMetrics();
        int baseline = HEADER - 6;
        int at = 6;

        String name = Messages.get(study.nameKey()) + " " + study.parameters();

        g.setColor(ChartColors.foreground());
        g.drawString(name, at, baseline);

        at += metrics.stringWidth(name) + 10;

        // The values under the crosshair, or the last ones when it is away.
        double[] values = study.valueAt(readAt());
        List<Color> colours = study.colours();

        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) {
                continue;
            }

            String text = format().format(values[i]);

            g.setColor(i < colours.size() ? colours.get(i) : ChartColors.foreground());
            g.drawString(text, at, baseline);

            at += metrics.stringWidth(text) + 8;
        }

    }

    private void paintButton(Graphics2D g, int left, boolean restore) {
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.2f));

        int middle = HEADER / 2;

        if (restore) {
            // A box: what it will become. A minus sign on a pane that is
            // already flat would offer to do what it has done.
            g.drawRect(left + 2, middle - 4, 10, 8);

            return;
        }

        g.drawLine(left + 2, middle, left + 12, middle);
    }

    private void paintClose(Graphics2D g, int left) {
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.2f));
        g.drawLine(left + 3, HEADER / 2 - 4, left + 11, HEADER / 2 + 4);
        g.drawLine(left + 11, HEADER / 2 - 4, left + 3, HEADER / 2 + 4);
    }

    /** @return the bar whose values the header shows */
    private int readAt() {
        int under = canvas.barUnderCursor();

        return under >= 0 ? under : canvas.lastVisibleBar();
    }

    private void paintPlot(Graphics2D g) {
        Viewport viewport = canvas.plotViewport();

        if (viewport == null) {
            return;
        }

        int top = HEADER;
        int bottom = getHeight() - 2;
        double[] range = study.bounds();
        double low = range == null ? fittedLow(viewport) : range[0];
        double high = range == null ? fittedHigh(viewport) : range[1];

        if (high - low <= 0) {
            return;
        }

        paintLevels(g, top, bottom, low, high);
        paintLines(g, viewport, top, bottom, low, high);
        paintScale(g, top, bottom, low, high);
    }

    private double y(double value, int top, int bottom, double low, double high) {
        return bottom - (value - low) / (high - low) * (bottom - top);
    }

    private void paintLevels(Graphics2D g, int top, int bottom, double low, double high) {
        for (Study.Level level : study.levels()) {
            g.setColor(level.colour());
            g.setStroke(level.stroke());

            int at = (int) Math.round(y(level.at(), top, bottom, low, high));

            g.drawLine(0, at, plotWidth(), at);
        }
    }

    private void paintLines(Graphics2D g, Viewport viewport,
                            int top, int bottom, double low, double high) {
        List<Color> colours = study.colours();
        List<java.awt.Stroke> strokes = study.strokes();
        int lines = colours.size();

        for (int line = 0; line < lines; line++) {
            g.setColor(colours.get(line));
            g.setStroke(line < strokes.size() ? strokes.get(line) : study.stroke());

            int lastX = Integer.MIN_VALUE;
            int lastY = 0;

            for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
                double[] values = study.valueAt(bar);

                if (line >= values.length || Double.isNaN(values[line])) {
                    lastX = Integer.MIN_VALUE;

                    continue;
                }

                int x = (int) Math.round(viewport.x(bar));
                int at = (int) Math.round(y(values[line], top, bottom, low, high));

                if (lastX != Integer.MIN_VALUE) {
                    g.drawLine(lastX, lastY, x, at);
                }

                lastX = x;
                lastY = at;
            }
        }
    }

    /**
     * The numbers down the right, in the strip the chart leaves for its own.
     *
     * <p>Only the ends and the levels. A pane sixty pixels tall with five
     * labels down it is five labels nobody can read; the ends say the scale and
     * the levels are the only other numbers that mean anything here.</p>
     */
    private void paintScale(Graphics2D g, int top, int bottom, double low, double high) {
        g.setColor(ChartColors.background());
        g.fillRect(plotWidth(), top, getWidth() - plotWidth(), getHeight() - top);

        g.setColor(ChartColors.grid());
        g.drawLine(plotWidth(), top, plotWidth(), getHeight());

        g.setColor(ChartColors.foreground());

        DecimalFormat format = format();
        java.util.List<Integer> written = new java.util.ArrayList<>();

        // The levels first, because they are the ones worth reading: twenty and
        // eighty are where a stochastic says something, and the ends of the
        // scale only say how tall the pane is.
        for (Study.Level level : study.levels()) {
            int at = (int) Math.round(y(level.at(), top, bottom, low, high));

            g.drawString(format.format(level.at()), plotWidth() + 6, at + 4);
            written.add(at);
        }

        // And an end only when nothing is already written across it. Two
        // numbers one pixel apart are two numbers nobody can read, and on a
        // stochastic the ends sit right beside the levels by default.
        drawIfClear(g, format.format(high), top + 10, written);
        drawIfClear(g, format.format(low), bottom - 2, written);
    }

    private void drawIfClear(Graphics2D g, String text, int at, java.util.List<Integer> taken) {
        for (int each : taken) {
            if (Math.abs(each - at) < 16) {
                return;
            }
        }

        g.drawString(text, plotWidth() + 6, at);
    }

    private int plotWidth() {
        return Math.max(1, getWidth() - canvas.axisWidth());
    }

    private double fittedLow(Viewport viewport) {
        double lowest = Double.MAX_VALUE;

        for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
            for (double each : study.valueAt(bar)) {
                if (!Double.isNaN(each)) {
                    lowest = Math.min(lowest, each);
                }
            }
        }

        return lowest == Double.MAX_VALUE ? 0 : lowest;
    }

    private double fittedHigh(Viewport viewport) {
        double highest = -Double.MAX_VALUE;

        for (int bar = viewport.firstBar(); bar < viewport.lastBar(); bar++) {
            for (double each : study.valueAt(bar)) {
                if (!Double.isNaN(each)) {
                    highest = Math.max(highest, each);
                }
            }
        }

        return highest == -Double.MAX_VALUE ? 1 : highest;
    }

    private static DecimalFormat format() {
        return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.getDefault()));
    }
}
