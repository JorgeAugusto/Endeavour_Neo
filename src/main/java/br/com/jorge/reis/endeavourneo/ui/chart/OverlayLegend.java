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
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.swing.JComponent;

/**
 * The strip above the chart naming each overlay and its current values.
 *
 * <p>The values are those of the bar under the cursor, falling back to the last
 * bar when the mouse is elsewhere. That fallback is what makes the legend useful
 * without interaction: a glance reports where every average is right now.</p>
 *
 * <p><b>The parameters are shown inline</b>, as {@code EMA [17 21 55]}. A chart
 * carrying three moving averages is unreadable without knowing which is which,
 * and opening a dialog to find out breaks the reading.</p>
 *
 * <p>Each row carries a show/hide box. Of the three controls Profit offers —
 * show/hide, colour, edit — this is the one that pays for itself: removing an
 * overlay to see the price underneath would mean retyping its parameters to get
 * it back, so people stop trying. A toggle makes comparison free, and comparing
 * is the whole reason for having several. Colour and edit need a picker and a
 * parameters dialog, and are not built yet.</p>
 */
public final class OverlayLegend extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int PADDING = 8;

    private static final int ROW_HEIGHT = 18;

    /** The clickable box at the start of each row. */
    private static final int TOGGLE = 11;

    private final transient ChartCanvas canvas;

    private final transient List<Rectangle> toggles = new ArrayList<>();

    public OverlayLegend(ChartCanvas canvas) {
        this.canvas = canvas;

        setOpaque(true);
        addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                List<Overlay> overlays = canvas.overlays();

                for (int i = 0; i < toggles.size() && i < overlays.size(); i++) {
                    if (toggles.get(i).contains(e.getPoint())) {
                        Overlay overlay = overlays.get(i);

                        overlay.setVisible(!overlay.isVisible());
                        canvas.repaint();
                        repaint();

                        return;
                    }
                }
            }
        });
    }

    @Override
    public Dimension getPreferredSize() {
        int rows = Math.max(1, canvas.overlays().size());

        return new Dimension(200, rows * ROW_HEIGHT + 4);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            toggles.clear();

            List<Overlay> overlays = canvas.overlays();

            if (overlays.isEmpty()) {
                return;
            }

            g.setFont(getFont().deriveFont(11f));

            FontMetrics metrics = g.getFontMetrics();
            int bar = canvas.hoveredBar();
            int y = 2;

            for (Overlay overlay : overlays) {
                paintRow(g, metrics, overlay, bar, y);

                y += ROW_HEIGHT;
            }
        } finally {
            g.dispose();
        }
    }

    private void paintRow(Graphics2D g, FontMetrics metrics, Overlay overlay, int bar, int y) {
        int baseline = y + metrics.getAscent() + 2;
        int x = PADDING;

        Rectangle box = new Rectangle(x, y + (ROW_HEIGHT - TOGGLE) / 2, TOGGLE, TOGGLE);

        toggles.add(box);

        g.setColor(ChartColors.grid());
        g.drawRect(box.x, box.y, box.width, box.height);

        if (overlay.isVisible()) {
            g.setColor(ChartColors.foreground());
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawLine(box.x + 2, box.y + 5, box.x + 4, box.y + 8);
            g.drawLine(box.x + 4, box.y + 8, box.x + 9, box.y + 2);
        }

        x += TOGGLE + 8;

        // A hidden overlay keeps its row, dimmed. Removing it would make the
        // toggle a one-way door: nothing left on screen to click to bring it
        // back.
        Color ink = overlay.isVisible()
                ? ChartColors.foreground()
                : dim(ChartColors.foreground());

        String label = Messages.get(overlay.nameKey()) + " " + overlay.parameters();

        g.setColor(ink);
        g.drawString(label, x, baseline);

        x += metrics.stringWidth(label) + 14;

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
            // Nothing during the warm-up. A dash rather than a zero, and rather
            // than nothing at all: the reader can see the line exists and has no
            // value yet, instead of wondering whether it broke.
            String text = Double.isFinite(values[line]) ? format.format(values[line]) : "—";

            g.setColor(line < colours.size() ? colours.get(line) : ink);
            g.drawString(text, x, baseline);

            x += mono.stringWidth(text) + 12;
        }

        g.setFont(g.getFont().deriveFont(11f));
    }

    private static Color dim(Color colour) {
        return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 110);
    }
}
