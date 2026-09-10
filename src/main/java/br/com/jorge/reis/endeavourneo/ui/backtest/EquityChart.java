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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * The balance curve: what the account did, operation by operation.
 *
 * <p>The horizontal axis is <b>operations, not time</b>, and that is a real
 * choice with a real cost. It makes a stretch of ten losing trades look the same
 * whether it took a week or a year — but it makes the shape of the strategy
 * legible, which the time axis does not when four thousand trades are squeezed
 * into six years of pixels. The time axis is the price chart's job, and the
 * price chart is right there below.</p>
 *
 * <h2>Zero is always drawn</h2>
 *
 * <p>A curve auto-scaled to its own range can go from −40.000 to −69.000 and
 * look like a gentle slope on a healthy chart. Pinning zero into the scale is
 * what makes a losing strategy look like one at a glance.</p>
 */
final class EquityChart extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final Color UP = new Color(38, 166, 109);

    private static final Color DOWN = new Color(214, 73, 73);

    private static final int PAD = 8;

    private double[] curve = new double[0];

    private int chosen = -1;

    EquityChart() {
        setPreferredSize(new Dimension(400, 160));
    }

    /** @param points the running total after each operation, starting at zero */
    void show(double[] points) {
        curve = points == null ? new double[0] : points.clone();
        chosen = -1;

        repaint();
    }

    /** @param trade index of the operation to mark, or -1 */
    void highlight(int trade) {
        chosen = trade;

        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(UIManager.getColor("Panel.background"));
            g.fillRect(0, 0, getWidth(), getHeight());

            if (curve.length < 2) {
                hint(g);

                return;
            }

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            paintCurve(g);
        } finally {
            g.dispose();
        }
    }

    private void hint(Graphics2D g) {
        g.setColor(UIManager.getColor("Label.disabledForeground"));

        String text = br.com.jorge.reis.endeavourneo.platform.Messages.get("backtest.noRun");

        g.drawString(text, PAD, getHeight() / 2);
    }

    private void paintCurve(Graphics2D g) {
        double top = 0;
        double bottom = 0;

        for (double point : curve) {
            top = Math.max(top, point);
            bottom = Math.min(bottom, point);
        }

        if (top == bottom) {
            top = 1;
            bottom = -1;
        }

        int w = getWidth() - 2 * PAD;
        int h = getHeight() - 2 * PAD;

        // Zero is in the scale by construction, because top starts at zero and
        // so does bottom -- see the class note.
        double scale = h / (top - bottom);
        int zero = (int) (PAD + (top - 0) * scale);

        g.setColor(UIManager.getColor("Separator.foreground"));
        g.setStroke(new BasicStroke(1));
        g.drawLine(PAD, zero, PAD + w, zero);

        Path2D path = new Path2D.Double();

        for (int i = 0; i < curve.length; i++) {
            double x = PAD + (double) w * i / (curve.length - 1);
            double y = PAD + (top - curve[i]) * scale;

            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        g.setColor(curve[curve.length - 1] >= 0 ? UP : DOWN);
        g.setStroke(new BasicStroke(1.6f));
        g.draw(path);

        if (chosen >= 0 && chosen + 1 < curve.length) {
            double x = PAD + (double) w * (chosen + 1) / (curve.length - 1);

            g.setColor(UIManager.getColor("Label.foreground"));
            g.setStroke(new BasicStroke(1));
            g.drawLine((int) x, PAD, (int) x, PAD + h);
        }
    }
}
