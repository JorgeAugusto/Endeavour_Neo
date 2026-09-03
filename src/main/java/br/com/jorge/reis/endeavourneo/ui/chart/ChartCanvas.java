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

import br.com.jorge.reis.endeavourneo.ui.chart.style.CandleStyle;

import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.swing.JComponent;

/**
 * The price chart.
 *
 * <p>It paints in layers, and the order is the design:</p>
 *
 * <pre>
 *   background   the theme's surface
 *   grid         horizontal price lines, recessive
 *   style        the price itself -- see {@link ChartStyle}
 *   crosshair    where the mouse is
 * </pre>
 *
 * <p><b>Each layer knows nothing of the others.</b> That is what the previous
 * project's canvas lacked: one {@code paintComponent} of 1.576 lines drawing
 * everything, where adding a style meant editing the method that also draws the
 * axis. Here a new style is a new class and this file does not change.</p>
 *
 * <p>The viewport is rebuilt on every paint rather than cached. It costs one
 * pass over the visible bars — a few hundred — and removes the entire class of
 * bug where the component resized and the scale did not.</p>
 */
public final class ChartCanvas extends JComponent {

    private static final long serialVersionUID = 1L;

    /** How many bars fill the screen before anyone zooms. */
    private static final int DEFAULT_VISIBLE_BARS = 120;

    private static final int MINIMUM_VISIBLE_BARS = 10;

    /** Roughly how many pixels apart the horizontal grid lines should sit. */
    private static final int GRID_SPACING = 56;

    private transient PriceSeries series = PriceSeries.empty();

    private transient ChartStyle style = new CandleStyle();

    private int firstBar;

    private int visibleBars = DEFAULT_VISIBLE_BARS;

    private transient Point cursor;

    public ChartCanvas() {
        setOpaque(true);
        setPreferredSize(new Dimension(640, 360));
        setDoubleBuffered(true);

        Mouse mouse = new Mouse();

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
    }

    /** @param newSeries the data to draw; showing the most recent bars */
    public void setSeries(PriceSeries newSeries) {
        this.series = newSeries == null ? PriceSeries.empty() : newSeries;
        this.visibleBars = Math.min(DEFAULT_VISIBLE_BARS, Math.max(1, this.series.size()));
        this.firstBar = Math.max(0, this.series.size() - visibleBars);

        repaint();
    }

    public void setStyle(ChartStyle newStyle) {
        if (newStyle != null) {
            this.style = newStyle;

            repaint();
        }
    }

    public ChartStyle getStyle() {
        return style;
    }

    /** @return the bar under the mouse, or -1 when the mouse is elsewhere */
    public int hoveredBar() {
        if (cursor == null || series.size() == 0) {
            return -1;
        }

        return viewport().barAt(cursor.x);
    }

    private Viewport viewport() {
        return Viewport.of(series, new Rectangle(0, 0, getWidth(), getHeight()),
                firstBar, visibleBars);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            if (series.size() == 0 || getWidth() < 2 || getHeight() < 2) {
                return;
            }

            Viewport viewport = viewport();

            paintGrid(g, viewport);
            style.paint(g, series, viewport);
            paintCrosshair(g, viewport);
        } finally {
            g.dispose();
        }
    }

    /**
     * Horizontal lines at round prices.
     *
     * <p>Round, not evenly spaced: a grid at 104.317 and 104.822 is arithmetic
     * showing through. The step is chosen from the 1-2-5 sequence, which is what
     * produces the numbers a reader expects to see on an axis.</p>
     */
    private void paintGrid(Graphics2D g, Viewport viewport) {
        double span = viewport.highestPrice() - viewport.lowestPrice();
        double target = span * GRID_SPACING / Math.max(1, getHeight());
        double step = niceStep(target);

        g.setColor(ChartColors.grid());
        g.setStroke(new BasicStroke(1.0f));

        double price = Math.ceil(viewport.lowestPrice() / step) * step;

        while (price <= viewport.highestPrice()) {
            int y = (int) Math.round(viewport.y(price));

            g.drawLine(0, y, getWidth(), y);

            price += step;
        }
    }

    /** @return the closest 1, 2 or 5 times a power of ten at or above {@code target} */
    private static double niceStep(double target) {
        if (!(target > 0.0)) {
            return 1.0;
        }

        double magnitude = Math.pow(10, Math.floor(Math.log10(target)));
        double normalised = target / magnitude;

        if (normalised <= 1.0) {
            return magnitude;
        }
        if (normalised <= 2.0) {
            return 2.0 * magnitude;
        }
        if (normalised <= 5.0) {
            return 5.0 * magnitude;
        }

        return 10.0 * magnitude;
    }

    /**
     * The crosshair, snapped to the centre of the bar under the mouse.
     *
     * <p>Snapped rather than free: a vertical line between two candles invites
     * the reader to attribute a price to a bar that is not there. Landing it on
     * the bar's centre says which bar is being read.</p>
     */
    private void paintCrosshair(Graphics2D g, Viewport viewport) {
        if (cursor == null) {
            return;
        }

        int bar = viewport.barAt(cursor.x);
        int x = (int) Math.round(viewport.x(bar));

        g.setColor(ChartColors.grid());
        g.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                1.0f, new float[]{3.0f, 3.0f}, 0.0f));

        g.drawLine(x, 0, x, getHeight());
        g.drawLine(0, cursor.y, getWidth(), cursor.y);
    }

    /** Wheel zooms around the cursor; dragging pans. */
    private final class Mouse extends MouseAdapter {

        private int grabbedAt = -1;

        private int grabbedFirstBar;

        @Override
        public void mouseMoved(MouseEvent e) {
            cursor = e.getPoint();

            repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            cursor = null;

            repaint();
        }

        @Override
        public void mousePressed(MouseEvent e) {
            grabbedAt = e.getX();
            grabbedFirstBar = firstBar;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (grabbedAt < 0 || series.size() == 0) {
                return;
            }

            // Panning moves by BARS, not pixels: at four pixels per bar a
            // ten-pixel drag must move two bars and a half, not ten.
            double perBar = (double) getWidth() / visibleBars;
            int moved = (int) Math.round((grabbedAt - e.getX()) / perBar);

            firstBar = clampFirstBar(grabbedFirstBar + moved);
            cursor = e.getPoint();

            repaint();
        }

        @Override
        public void mouseWheelMoved(MouseWheelEvent e) {
            if (series.size() == 0) {
                return;
            }

            // The bar under the cursor stays under the cursor. Zooming around
            // the centre instead makes the reader chase what they were looking
            // at, and it is the single thing that most makes a chart feel wrong.
            int anchor = viewport().barAt(e.getX());
            double share = (e.getX() - 0.0) / Math.max(1, getWidth());

            int zoomed = (int) Math.round(visibleBars * (e.getWheelRotation() > 0 ? 1.25 : 0.8));

            visibleBars = Math.max(MINIMUM_VISIBLE_BARS, Math.min(zoomed, series.size()));
            firstBar = clampFirstBar((int) Math.round(anchor - share * visibleBars));

            repaint();
        }

        private int clampFirstBar(int candidate) {
            return Math.max(0, Math.min(candidate, Math.max(0, series.size() - visibleBars)));
        }
    }
}
