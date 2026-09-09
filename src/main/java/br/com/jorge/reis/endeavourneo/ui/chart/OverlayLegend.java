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

import br.com.jorge.reis.endeavourneo.platform.Appearance;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The list of indicators on this chart, one per row.
 *
 * <p>Each row carries three controls: an <b>eye</b> to show or hide, a
 * <b>gear</b> to change the parameters, and an <b>×</b> to remove. They appear
 * on the row under the pointer rather than on every row: a chart can easily
 * carry a dozen indicators, and thirty-six permanent little buttons is a wall,
 * not a legend.</p>
 *
 * <p><b>Hidden is not the same as absent.</b> A hidden indicator keeps its row,
 * dimmed and with the eye struck through. Removing the row would make the eye a
 * one-way door — nothing left on screen to click to bring it back — and the
 * whole point of a toggle is that trying things costs nothing.</p>
 *
 * <p>The values shown are those of the bar under the cursor, falling back to the
 * last bar when the mouse is elsewhere. That fallback is what makes the list
 * useful without interaction: a glance reports where every line is right now.</p>
 *
 * <p><b>The list folds away.</b> Seven indicators is seven lines standing between
 * the reader and the price, and the price is what the window is for. Collapsed it
 * keeps one line: the triangle and how many are in there -- enough to know the
 * chart is carrying indicators, which a legend that vanished entirely would not
 * say. Folded or not is remembered per chart, because it is a property of that
 * chart's purpose and not a mood.</p>
 */
public final class OverlayLegend extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int PADDING = 8;

    private static final int ROW_HEIGHT = 19;

    /**
     * The fixed-width face the values are written in.
     *
     * <p>Built once. {@code Appearance.monospaced} asks the graphics environment
     * for the installed families, and this was inside {@code paintRow} -- so it
     * ran once per indicator per repaint, and the legend repaints on every
     * movement of the cursor.</p>
     */
    private static final java.awt.Font VALUES = Appearance.monospaced(11);

    /** Side of each of the three little buttons. */
    private static final int BUTTON = 14;

    private static final int GAP = 3;

    private static final Settings PREFS = Settings.workspace();

    private final transient ChartCanvas canvas;

    /** Identifies the chart, so folded or not survives a restart. */
    private final transient String key;

    private boolean collapsed;

    /** Hit area of the fold triangle, rebuilt on every paint. */
    private final transient Rectangle toggle = new Rectangle();

    /** Hit areas for the row under the pointer, rebuilt on every paint. */
    private final transient List<Rectangle> buttons = new ArrayList<>();

    private int hovered = -1;

    /**
     * @return the bar whose values every row reads
     *
     * <p><b>The last bar on screen when the pointer is away</b>, which is what
     * {@code ChartCanvas.lastVisibleBar} was written for and says so in its own
     * javadoc. Reading {@code hoveredBar()} raw, as this did, meant every row
     * printed a dash the moment the pointer left the canvas -- and this legend
     * sits ABOVE the canvas, so the pointer crosses it on the way in and out.
     * The study panes underneath always did this; only the price legend did
     * not.</p>
     *
     * <p>A method rather than two lines inside the paint, because a decision
     * inside a paint is a decision nothing can ask about.</p>
     */
    int readAt() {
        int under = canvas.hoveredBar();

        return under >= 0 ? under : canvas.lastVisibleBar();
    }

    /**
     * How far the pointer has to travel down a row before it counts as
     * carrying it rather than clicking it.
     *
     * <p>Every row here already answers to a click -- three buttons and, on
     * the fold line, the triangle. Without a threshold a hand that moves one
     * pixel between press and release would reorder the list instead.</p>
     */
    private static final int SLIP = 3;

    /** Which row was pressed, or -1; not yet a carry. */
    private transient int pressedRow = -1;

    private transient int pressedAt;

    /** Whether that press has travelled far enough to be a carry. */
    private transient boolean carrying;

    /** Whether the last release ended a carry, so the click is not a click. */
    private transient boolean carried;

    /** Where it would land, as a gap between rows; -1 when nothing is carried. */
    private transient int dropAt = -1;

    /**
     * @param canvas the chart this legend describes
     * @param key identifies the chart, so folded or not is remembered
     */
    public OverlayLegend(ChartCanvas canvas, String key) {
        this.canvas = canvas;
        this.key = key;
        this.collapsed = PREFS.getBoolean(key + ".collapsed", false);

        setOpaque(true);

        Mouse mouse = new Mouse();

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    @Override
    public Dimension getPreferredSize() {
        int overlays = canvas.overlays().size();

        if (overlays == 0) {
            return new Dimension(240, ROW_HEIGHT + 4);
        }

        // Collapsed: the one line carrying the triangle and the count. Expanded:
        // a line per indicator plus that same line, moved to the bottom, so the
        // way back is where the eye already is after reading the list.
        int rows = collapsed ? 1 : overlays + 1;

        return new Dimension(240, rows * ROW_HEIGHT + 4);
    }

    /** Folds the list away, or brings it back. */
    public void setCollapsed(boolean value) {
        if (collapsed == value) {
            return;
        }

        collapsed = value;

        PREFS.putBoolean(key + ".collapsed", value);

        hovered = -1;

        revalidate();
        repaint();
    }

    public boolean isCollapsed() {
        return collapsed;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            buttons.clear();
            toggle.setBounds(0, 0, 0, 0);

            List<Overlay> overlays = canvas.overlays();

            if (overlays.isEmpty()) {
                return;
            }

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            if (collapsed) {
                paintHandle(g, 2, overlays.size());

                return;
            }

            int bar = readAt();

            paintDrop(g, overlays.size());

            for (int i = 0; i < overlays.size(); i++) {
                paintRow(g, overlays.get(i), bar, i, i * ROW_HEIGHT + 2);
            }

            paintHandle(g, overlays.size() * ROW_HEIGHT + 2, overlays.size());
        } finally {
            g.dispose();
        }
    }

    /**
     * Draws where a carried row would land.
     *
     * <p>Before the rows, not after: the mark belongs BEHIND the text, and a
     * line drawn over a row's letters would look like a strikethrough on the
     * indicator rather than a gap between two of them.</p>
     */
    private void paintDrop(Graphics2D g, int rows) {
        if (dropAt < 0) {
            return;
        }

        int y = Math.min(dropAt, rows) * ROW_HEIGHT + 1;

        g.setColor(ChartColors.up());
        g.fillRect(0, Math.max(0, Math.min(y, getHeight() - 3)), getWidth(), 3);
    }

    /**
     * @param y a pixel down the list
     * @return which GAP between rows it points at, from zero
     *
     * <p>Gaps and not rows: dropping is answering "above which one", and that
     * answer has to include "below the last", which no row index can say.</p>
     */
    private int gapAt(int y) {
        int rows = canvas.overlays().size();

        for (int i = 0; i < rows; i++) {
            // THE MIDDLE OF THE ROW AS IT IS DRAWN. paintRow puts row i at
            // i * ROW_HEIGHT + 2, so its middle is two pixels lower than this
            // was looking -- and the gap the reader is pointing at flipped a
            // row early along a two-pixel band at every boundary.
            if (y < i * ROW_HEIGHT + 2 + ROW_HEIGHT / 2) {
                return i;
            }
        }

        return rows;
    }

    private void paintRow(Graphics2D g, Overlay overlay, int bar, int index, int top) {
        g.setFont(getFont().deriveFont(11f));

        FontMetrics metrics = g.getFontMetrics();
        int baseline = top + metrics.getAscent() + 2;
        int x = PADDING;

        if (index == hovered) {
            g.setColor(hoverTint());
            g.fillRect(0, top - 1, getWidth(), ROW_HEIGHT);
        }

        Color ink = overlay.isVisible()
                ? ChartColors.foreground()
                : fade(ChartColors.foreground(), 110);

        String label = overlay.title();

        g.setColor(ink);
        g.drawString(label, x, baseline);

        x += metrics.stringWidth(label) + 10;

        // The buttons only on the row under the pointer. On every row they would
        // be thirty-six glyphs on a chart carrying a dozen indicators.
        if (index == hovered) {
            int y = top + (ROW_HEIGHT - BUTTON) / 2 - 1;

            drawEye(g, x, y, overlay.isVisible());
            buttons.add(new Rectangle(x, y, BUTTON, BUTTON));

            x += BUTTON + GAP;

            drawGear(g, x, y);
            buttons.add(new Rectangle(x, y, BUTTON, BUTTON));

            x += BUTTON + GAP;

            drawCross(g, x, y);
            buttons.add(new Rectangle(x, y, BUTTON, BUTTON));

            x += BUTTON + 12;
        }

        if (!overlay.isVisible()) {
            return;
        }

        double[] values = overlay.valueAt(bar);
        List<Color> colours = overlay.colours();

        g.setFont(VALUES);

        FontMetrics mono = g.getFontMetrics();
        DecimalFormat format = format();

        for (int line = 0; line < values.length; line++) {
            // A dash during the warm-up, not a zero and not a blank: the reader
            // sees the line exists and has no value yet, instead of wondering
            // whether it broke.
            String text = Double.isFinite(values[line]) ? format.format(values[line]) : "—";

            g.setColor(line < colours.size() ? colours.get(line) : ink);
            g.drawString(text, x, baseline);

            x += mono.stringWidth(text) + 12;
        }
    }

    /**
     * The fold line: a triangle, and when folded the count beside it.
     *
     * <p>The count only when folded. Expanded, the list is the count.</p>
     */
    private void paintHandle(Graphics2D g, int top, int count) {
        int side = 13;
        int y = top + (ROW_HEIGHT - side) / 2;

        toggle.setBounds(PADDING - 3, y, side, side);

        g.setColor(fade(ChartColors.foreground(), 150));

        int cx = toggle.x + side / 2;
        int cy = toggle.y + side / 2;

        // Pointing down means "there is more below": the same direction the list
        // will grow when it opens.
        int[] xs = {cx - 4, cx + 4, cx};
        int[] ys = collapsed
                ? new int[] {cy - 2, cy - 2, cy + 3}
                : new int[] {cy + 2, cy + 2, cy - 3};

        g.fillPolygon(xs, ys, 3);

        if (!collapsed) {
            return;
        }

        g.setFont(getFont().deriveFont(11f));
        g.drawString(String.valueOf(count), toggle.x + side + 4,
                top + g.getFontMetrics().getAscent() + 2);
    }

    // ------------------------------------------------------------- the glyphs

    private void drawEye(Graphics2D g, int x, int y, boolean open) {
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.2f));

        int cx = x + BUTTON / 2;
        int cy = y + BUTTON / 2;

        g.drawOval(cx - 6, cy - 4, 12, 8);
        g.fillOval(cx - 2, cy - 2, 4, 4);

        if (!open) {
            // Struck through rather than a different icon: the same shape with a
            // line across is read as "this one is off" without having to learn a
            // second symbol.
            g.drawLine(cx - 6, cy + 5, cx + 6, cy - 5);
        }
    }

    private void drawGear(Graphics2D g, int x, int y) {
        g.setColor(ChartColors.foreground());
        g.setStroke(new BasicStroke(1.2f));

        int cx = x + BUTTON / 2;
        int cy = y + BUTTON / 2;

        g.drawOval(cx - 3, cy - 3, 6, 6);

        for (int i = 0; i < 6; i++) {
            double angle = Math.PI * i / 3.0;
            int x1 = cx + (int) Math.round(Math.cos(angle) * 4);
            int y1 = cy + (int) Math.round(Math.sin(angle) * 4);
            int x2 = cx + (int) Math.round(Math.cos(angle) * 6);
            int y2 = cy + (int) Math.round(Math.sin(angle) * 6);

            g.drawLine(x1, y1, x2, y2);
        }
    }

    private void drawCross(Graphics2D g, int x, int y) {
        g.setColor(ChartColors.down());
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        int cx = x + BUTTON / 2;
        int cy = y + BUTTON / 2;

        g.drawLine(cx - 4, cy - 4, cx + 4, cy + 4);
        g.drawLine(cx + 4, cy - 4, cx - 4, cy + 4);
    }

    private static Color fade(Color colour, int alpha) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), alpha);
    }

    private static Color hoverTint() {
        Color base = ChartColors.foreground();

        return new Color(base.getRed(), base.getGreen(), base.getBlue(), 20);
    }

    // -------------------------------------------------------------- the mouse

    /**
     * @param y a pixel down this component
     * @return which row of the list is drawn there
     *
     * <p>The rows start at y = 1, not at zero: paintRow fills from {@code top
     * - 1} with {@code top = i * ROW_HEIGHT + 2}. Dividing the raw y put the
     * top pixel of every band on the row ABOVE it -- so the line under the
     * pointer and the line lit up were different lines, by one pixel, in two
     * places that each did the arithmetic themselves.</p>
     */
    private static int rowUnder(int y) {
        return (y - 1) / ROW_HEIGHT;
    }

    private final class Mouse extends MouseAdapter {

        @Override
        public void mouseMoved(MouseEvent e) {
            int row = rowUnder(e.getY());
            int previous = hovered;

            hovered = !collapsed && row >= 0 && row < canvas.overlays().size() ? row : -1;

            if (hovered != previous) {
                repaint();
            }

            setCursor(Cursor.getPredefinedCursor(cursorFor(e)));
        }

        /** @return which pointer this point deserves */
        private int cursorFor(MouseEvent e) {
            if (over(e) >= 0 || toggle.contains(e.getPoint())) {
                return Cursor.HAND_CURSOR;
            }

            // A row that can be carried has to LOOK like one before it is
            // grabbed; there is nothing else on screen saying the list
            // reorders.
            return rowAt(e) >= 0 ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR;
        }

        /** @return the row this point can carry, or -1 for anything else */
        private int rowAt(MouseEvent e) {
            if (collapsed || over(e) >= 0 || toggle.contains(e.getPoint())) {
                return -1;
            }

            int row = rowUnder(e.getY());

            return row >= 0 && row < canvas.overlays().size() ? row : -1;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            pressedRow = rowAt(e);
            pressedAt = e.getY();
            carrying = false;
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            if (pressedRow < 0) {
                return;
            }

            if (!carrying && Math.abs(e.getY() - pressedAt) < SLIP) {
                return;
            }

            if (!carrying) {
                carrying = true;

                // While a row is in the air the three buttons must not follow
                // the pointer onto whatever it passes over: they would be
                // offering to delete a row the reader is only crossing.
                hovered = -1;
            }

            int wanted = gapAt(e.getY());

            if (wanted != dropAt) {
                dropAt = wanted;

                repaint();
            }
        }

        @Override
        public void mouseReleased(MouseEvent e) {
            int row = pressedRow;
            int gap = dropAt;
            boolean was = carrying;

            pressedRow = -1;
            carrying = false;
            carried = was;
            dropAt = -1;

            repaint();

            if (!was || row < 0 || gap < 0 || row >= canvas.overlays().size()) {
                return;
            }

            canvas.moveOverlay(canvas.overlays().get(row), gap);
            revalidate();
            repaint();
        }

        @Override
        public void mouseExited(MouseEvent e) {
            if (carrying) {
                // Still being carried; the pointer left the list on its way
                // somewhere and the drag is not over.
                return;
            }

            hovered = -1;

            setCursor(Cursor.getDefaultCursor());
            repaint();
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (carried) {
                // The release that ended a carry can still arrive here as a
                // click, and acting on it would toggle or delete the row that
                // was just moved.
                carried = false;

                return;
            }

            // The triangle first: folded, it is the only thing on the line, and
            // the row arithmetic below would still answer "row 0".
            if (toggle.contains(e.getPoint())) {
                setCollapsed(!collapsed);

                return;
            }

            if (collapsed) {
                return;
            }

            int button = over(e);

            if (button < 0 || hovered < 0 || hovered >= canvas.overlays().size()) {
                return;
            }

            Overlay overlay = canvas.overlays().get(hovered);

            switch (button) {
                case 0 -> {
                    overlay.setVisible(!overlay.isVisible());
                    canvas.repaint();
                    canvas.overlaysChanged();
                }
                case 1 -> edit(overlay);
                default -> {
                    canvas.removeOverlay(overlay);

                    // The row under the pointer is gone; without clearing this,
                    // the next click would act on whatever slid into its place.
                    hovered = -1;
                }
            }

            revalidate();
            repaint();
        }

        private void edit(Overlay overlay) {
            java.awt.Window owner = SwingUtilities.getWindowAncestor(OverlayLegend.this);

            // A moving average has settings of its own -- kind, shift, colour,
            // dash -- and a dialog of bare spinners cannot express them. Every
            // other indicator still gets the plain one until it earns better.
            if (overlay instanceof br.com.jorge.reis.endeavourneo.ui.chart.overlay
                    .MovingAverage average) {

                if (MovingAverageDialog.edit(owner, average) != null) {
                    average.calculate(canvas.series());

                    canvas.repaint();
                    canvas.overlaysChanged();
                }

                return;
            }

            // The bands have more still: two deviations, a middle line with its
            // own pen, and a shading between them.
            if (overlay instanceof br.com.jorge.reis.endeavourneo.ui.chart.overlay
                    .BollingerBands bands) {

                if (BollingerBandsDialog.edit(owner, bands) != null) {
                    bands.calculate(canvas.series());

                    canvas.repaint();
                    canvas.overlaysChanged();
                }

                return;
            }

            // And the channel has more again: a pen per pair of edges, as many
            // pairs as the reader asks for, and a scale of its own.
            if (overlay instanceof br.com.jorge.reis.endeavourneo.ui.chart.overlay
                    .RegressionChannel channel) {

                if (RegressionChannelDialog.edit(owner, channel) != null) {
                    channel.calculate(canvas.series());

                    canvas.repaint();
                    canvas.overlaysChanged();
                }

                return;
            }

            // The zigzag has a tie rule, which is a real choice and not a
            // detail -- the two answers differ on a tenth of the pivots -- and
            // a dialog of bare spinners has nowhere to put it.
            if (overlay instanceof br.com.jorge.reis.endeavourneo.ui.chart.overlay
                    .TopsAndBottoms pivots) {

                if (TopsAndBottomsDialog.edit(owner, pivots) != null) {
                    pivots.calculate(canvas.series());

                    canvas.repaint();
                    canvas.overlaysChanged();
                }

                return;
            }

            // The channel has four numbers that decide the fit and two edges
            // to dress; the plain dialog offers the first two and nothing else.
            if (overlay instanceof br.com.jorge.reis.endeavourneo.ui.chart.overlay
                    .TouchChannel channel) {

                if (TouchChannelDialog.edit(owner, channel) != null) {
                    channel.calculate(canvas.series());

                    canvas.repaint();
                    canvas.overlaysChanged();
                }

                return;
            }

            // And the trendlines have a ladder of three numbers plus three
            // things drawn beside the lines to explain the fit; none of that
            // fits in a dialog of bare spinners either.
            if (overlay instanceof br.com.jorge.reis.endeavourneo.ui.chart.overlay
                    .TouchTrendlines trendlines) {

                if (TouchTrendlinesDialog.edit(owner, trendlines) != null) {
                    trendlines.calculate(canvas.series());

                    canvas.repaint();
                    canvas.overlaysChanged();
                }

                return;
            }

            Overlay replacement = InsertOverlayDialog.edit(owner, overlay);

            if (replacement != null) {
                canvas.replaceOverlay(overlay, replacement);
            }
        }

        /** @return which of the three buttons is under the pointer, or -1 */
        private int over(MouseEvent e) {
            for (int i = 0; i < buttons.size(); i++) {
                if (buttons.get(i).contains(e.getPoint())) {
                    return i;
                }
            }

            return -1;
        }
    }

    /**
     * @return the format every number in this legend is written with
     *
     * <p>Built once and kept, and it used to be built inside the method that
     * draws ONE row -- so a chart with six indicators built six of them on every
     * repaint, and a {@code DecimalFormat} parses its pattern and reads the
     * locale's symbols each time.</p>
     *
     * <p>Rebuilt when the language changes, because the decimal separator
     * does.</p>
     */
    private static DecimalFormat format() {
        Locale now = Locale.getDefault();

        if (format == null || !now.equals(formatFor)) {
            formatFor = now;
            format = new DecimalFormat("#,##0.###", DecimalFormatSymbols.getInstance(now));
        }

        return format;
    }

    private static DecimalFormat format;

    private static Locale formatFor;
}
