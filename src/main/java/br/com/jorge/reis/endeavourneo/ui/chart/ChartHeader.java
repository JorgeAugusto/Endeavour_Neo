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

import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * The instrument and the period, inside the chart.
 *
 * <p>The window title says it too, and that is not a duplication worth removing:
 * the title belongs to the window and disappears when the window is maximised
 * into a workspace or when three of them are stacked. This line belongs to the
 * chart and travels with it.</p>
 *
 * <p><b>Double-clicking it changes the period.</b> The same window a digit opens
 * — one way in for people who type and one for people who point, and only one
 * window to learn.</p>
 */
public final class ChartHeader extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final int PADDING = 8;

    private final transient ChartCanvas canvas;

    private final transient String name;

    /** Where the text actually is, so only the text answers the pointer. */
    private final transient Rectangle hot = new Rectangle();

    public ChartHeader(ChartCanvas canvas, String name) {
        this.canvas = canvas;
        this.name = name;

        setOpaque(true);
        setToolTipText(Messages.get("period.hint"));

        Mouse mouse = new Mouse();

        addMouseListener(mouse);
        addMouseMotionListener(mouse);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(240, 20);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(ChartColors.background());
            g.fillRect(0, 0, getWidth(), getHeight());

            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Font base = getFont().deriveFont(12f);

            g.setFont(base.deriveFont(Font.BOLD));

            FontMetrics bold = g.getFontMetrics();
            int baseline = (getHeight() + bold.getAscent()) / 2 - 2;

            g.setColor(ChartColors.foreground());
            g.drawString(name, PADDING, baseline);

            int x = PADDING + bold.stringWidth(name) + 8;

            g.setFont(base);

            FontMetrics plain = g.getFontMetrics();
            String period = canvas.period().label();

            // The period dimmer than the instrument: they are read together, and
            // making both the same weight leaves neither leading.
            g.setColor(fade(ChartColors.foreground()));
            g.drawString(period, x, baseline);

            hot.setBounds(PADDING - 3, 0,
                    bold.stringWidth(name) + 8 + plain.stringWidth(period) + 6, getHeight());
        } finally {
            g.dispose();
        }
    }

    private static java.awt.Color fade(java.awt.Color colour) {
        return new java.awt.Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 165);
    }

    private final class Mouse extends MouseAdapter {

        @Override
        public void mouseMoved(MouseEvent e) {
            setCursor(Cursor.getPredefinedCursor(
                    hot.contains(e.getPoint()) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            setCursor(Cursor.getDefaultCursor());
        }

        @Override
        public void mouseClicked(MouseEvent e) {
            if (e.getClickCount() == 2 && hot.contains(e.getPoint())) {
                canvas.askForPeriod(SwingUtilities.getWindowAncestor(ChartHeader.this), null);
            }
        }
    }
}
