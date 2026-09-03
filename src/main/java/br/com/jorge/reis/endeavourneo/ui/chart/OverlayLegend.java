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
 */
public final class OverlayLegend extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int PADDING = 8;

    private static final int ROW_HEIGHT = 19;

    /** Side of each of the three little buttons. */
    private static final int BUTTON = 14;

    private static final int GAP = 3;

    private final transient ChartCanvas canvas;

    /** Hit areas for the row under the pointer, rebuilt on every paint. */
    private final transient List<Rectangle> buttons = new ArrayList<>();

    private int hovered = -1;

    public OverlayLegend(ChartCanvas canvas) {
        this.canvas = canvas;

        setOpaque(true);

        Mouse mouse = new Mouse();

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    @Override
    public Dimension getPreferredSize() {
        int rows = Math.max(1, canvas.overlays().size());

        return new Dimension(240, rows * ROW_HEIGHT + 4);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            buttons.clear();

            List<Overlay> overlays = canvas.overlays();

            if (overlays.isEmpty()) {
                return;
            }

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            int bar = canvas.hoveredBar();

            for (int i = 0; i < overlays.size(); i++) {
                paintRow(g, overlays.get(i), bar, i, i * ROW_HEIGHT + 2);
            }
        } finally {
            g.dispose();
        }
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

        String label = Messages.get(overlay.nameKey()) + " " + overlay.parameters();

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

        g.setFont(Appearance.monospaced(11));

        FontMetrics mono = g.getFontMetrics();
        DecimalFormat format = new DecimalFormat("#,##0.###",
                DecimalFormatSymbols.getInstance(Locale.getDefault()));

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

    private final class Mouse extends MouseAdapter {

        @Override
        public void mouseMoved(MouseEvent e) {
            int row = e.getY() / ROW_HEIGHT;
            int previous = hovered;

            hovered = row >= 0 && row < canvas.overlays().size() ? row : -1;

            if (hovered != previous) {
                repaint();
            }

            setCursor(Cursor.getPredefinedCursor(
                    over(e) >= 0 ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            hovered = -1;

            setCursor(Cursor.getDefaultCursor());
            repaint();
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            int button = over(e);

            if (button < 0 || hovered < 0 || hovered >= canvas.overlays().size()) {
                return;
            }

            Overlay overlay = canvas.overlays().get(hovered);

            switch (button) {
                case 0 -> {
                    overlay.setVisible(!overlay.isVisible());
                    canvas.repaint();
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
            Overlay replacement = InsertOverlayDialog.edit(
                    SwingUtilities.getWindowAncestor(OverlayLegend.this), overlay);

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
}
