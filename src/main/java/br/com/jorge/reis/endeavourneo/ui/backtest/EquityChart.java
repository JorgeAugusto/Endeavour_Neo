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
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * The three curves, on one axis: bars.
 *
 * <p><b>Saldo</b> is what has been banked — it holds flat while a position is
 * open and steps when the operation ends. <b>Patrimônio</b> marks that open
 * position to market on every bar. The gap between the two is the hole inside
 * whatever is still open, and it is the only place that hole is ever visible: a
 * position bleeding for three days is a flat line on the balance curve and a
 * cliff on the day it closes. <b>Custo acumulado</b> is the third line because
 * it answers for free the question the other two raise — how much of this was
 * brokerage.</p>
 *
 * <h2>Zero is always in the scale</h2>
 *
 * <p>A curve auto-scaled to its own range can fall from −40.000 to −69.000 and
 * look like a gentle slope on a healthy chart. Pinning zero is what makes a
 * losing strategy look like one at a glance.</p>
 *
 * <h2>Drawn by column, not by point</h2>
 *
 * <p>Six years of one-minute bars is 165 thousand points into a strip 300 pixels
 * wide. Feeding every one of them to a {@code Path2D} costs more than the rest
 * of the window put together and draws the same picture: the curves are reduced
 * to one value per column first.</p>
 */
final class EquityChart extends JComponent {

    private static final long serialVersionUID = 1L;

    private static final Color BALANCE = new Color(0x2E8B57);

    private static final Color WORTH = new Color(0x2F74B5);

    private static final Color COST = new Color(0xB26A00);

    private static final int PAD = 6;

    private transient List<Line> lines = List.of();

    private int mark = -1;

    /** One curve and the colour it is drawn in. */
    private record Line(double[] points, Color colour, float width) {
    }

    EquityChart() {
        setPreferredSize(new Dimension(300, 96));
    }

    /**
     * @param balance the banked result per bar
     * @param worth   the same, marked to market
     * @param cost    what had been paid by each bar, positive
     */
    void show(double[] balance, double[] worth, double[] cost) {
        List<Line> built = new ArrayList<>(3);

        // Costs go NEGATIVE on the chart although they are counted positive:
        // what the reader is comparing is how far the result fell against how
        // much of the fall was paid out, and two lines heading in opposite
        // directions would have to be read twice.
        if (cost != null && cost.length > 1) {
            built.add(new Line(negated(cost), COST, 1.2f));
        }

        if (worth != null && worth.length > 1) {
            built.add(new Line(worth, WORTH, 1.2f));
        }

        if (balance != null && balance.length > 1) {
            built.add(new Line(balance, BALANCE, 1.8f));
        }

        lines = built;
        mark = -1;

        repaint();
    }

    /** @param bar the bar to mark with a vertical line, or -1 for none */
    void highlight(int bar) {
        mark = bar;

        repaint();
    }

    private static double[] negated(double[] values) {
        double[] flipped = new double[values.length];

        for (int i = 0; i < values.length; i++) {
            flipped[i] = -values[i];
        }

        return flipped;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();

        try {
            g.setColor(UIManager.getColor("Panel.background"));
            g.fillRect(0, 0, getWidth(), getHeight());

            if (lines.isEmpty()) {
                return;
            }

            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            paintLines(g);
        } finally {
            g.dispose();
        }
    }

    private void paintLines(Graphics2D g) {
        double top = 0;
        double bottom = 0;
        int longest = 0;

        for (Line line : lines) {
            longest = Math.max(longest, line.points().length);

            for (double point : line.points()) {
                top = Math.max(top, point);
                bottom = Math.min(bottom, point);
            }
        }

        if (top == bottom) {
            top = 1;
            bottom = -1;
        }

        int w = Math.max(getWidth() - 2 * PAD, 1);
        int h = Math.max(getHeight() - 2 * PAD, 1);
        double scale = h / (top - bottom);

        g.setColor(UIManager.getColor("Separator.foreground"));
        g.setStroke(new BasicStroke(1));

        int zero = (int) (PAD + top * scale);
        g.drawLine(PAD, zero, PAD + w, zero);

        for (Line line : lines) {
            g.setColor(line.colour());
            g.setStroke(new BasicStroke(line.width()));
            g.draw(pathOf(line.points(), w, h, top, scale));
        }

        if (mark >= 0 && longest > 1) {
            int x = PAD + (int) ((double) w * Math.min(mark, longest - 1) / (longest - 1));

            g.setColor(UIManager.getColor("Label.foreground"));
            g.setStroke(new BasicStroke(1));
            g.drawLine(x, PAD, x, PAD + h);
        }
    }

    /** One point per column, so the cost does not follow the number of bars. */
    private static Path2D pathOf(double[] points, int w, int h, double top, double scale) {
        Path2D path = new Path2D.Double();
        int columns = Math.min(w, points.length);

        for (int column = 0; column < columns; column++) {
            int at = (int) ((long) column * (points.length - 1) / Math.max(1, columns - 1));
            double x = PAD + (double) w * column / Math.max(1, columns - 1);
            double y = PAD + (top - points[at]) * scale;

            if (column == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }

        return path;
    }
}
