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
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The three controls that describe a line, and the sample under them.
 *
 * <p>Style, colour, thickness — and a stroke of the line as it will actually
 * be drawn, which is the part that turns three abstract settings into a
 * decision anybody can make.</p>
 *
 * <h2>Why it is not written once per dialog</h2>
 *
 * <p>Every indicator has at least one line and some have four. Written out
 * where it is used, each copy is another chance for one of them to lose its
 * sample, forget to repaint when the thickness changes, or drift to a
 * different label for the same thing. That already happened once here, with
 * two dialogs carrying private copies of the same list renderers and showing
 * {@code ARITHMETIC} where the other showed "Aritmética".</p>
 */
public final class LinePen {

    private final Component owner;

    private final JComboBox<MovingAverage.Line> style =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner thickness;

    private final JButton swatch = new JButton();

    private final Sample sample = new Sample(this);

    private Color chosen;

    /**
     * @param owner what the colour chooser opens over
     * @param line the style it starts on
     * @param colour the colour it starts on
     * @param width the thickness it starts on
     */
    public LinePen(Component owner, MovingAverage.Line line, Color colour, float width) {
        this.owner = owner;
        this.chosen = colour;

        style.setRenderer(Forms.lineStyles());
        style.setSelectedItem(line);
        thickness = new JSpinner(new SpinnerNumberModel(Math.round(width), 1, 8, 1));

        paintSwatch();

        style.addActionListener(e -> sample.repaint());
        thickness.addChangeListener(e -> sample.repaint());
        swatch.addActionListener(e -> pickColour());
    }

    /** The button IS the swatch: a rectangle of the colour it will draw in. */
    private void paintSwatch() {
        swatch.setBackground(chosen);
        swatch.setOpaque(true);
        swatch.setBorderPainted(false);
        swatch.setText(" ");
    }

    private void pickColour() {
        Color picked = JColorChooser.showDialog(owner,
                Messages.get("overlay.ma.colour"), chosen);

        if (picked != null) {
            chosen = picked;

            paintSwatch();
            sample.repaint();
        }
    }

    /**
     * @param title what this line is called, as its own group
     * @return the next free row
     */
    public int addTo(JPanel panel, int row, String title) {
        Forms.group(panel, row++, title);
        Forms.field(panel, row++, Messages.get("overlay.ma.style"), style);
        Forms.field(panel, row++, Messages.get("overlay.ma.colour"), swatch);
        Forms.field(panel, row++, Messages.get("overlay.ma.thickness"), thickness);
        Forms.across(panel, row++, sample);

        return row;
    }

    public void setEnabled(boolean on) {
        style.setEnabled(on);
        thickness.setEnabled(on);
        swatch.setEnabled(on);
        sample.setEnabled(on);
        sample.repaint();
    }

    public MovingAverage.Line line() {
        MovingAverage.Line picked = (MovingAverage.Line) style.getSelectedItem();

        return picked == null ? MovingAverage.Line.SOLID : picked;
    }

    public Color colour() {
        return chosen;
    }

    public float width() {
        return ((Number) thickness.getValue()).floatValue();
    }

    /** A single stroke of the line as it will be drawn. */
    private static final class Sample extends JComponent {

        private static final long serialVersionUID = 1L;

        private final transient LinePen pen;

        Sample(LinePen pen) {
            this.pen = pen;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(200, 24);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                setBorder(BorderFactory.createLineBorder(ChartColors.grid()));

                if (!isEnabled()) {
                    // Nothing drawn. A sample of a line that will not be drawn
                    // is a picture of something that is not going to happen.
                    return;
                }

                g.setColor(pen.colour());
                g.setStroke(pen.line().stroke(pen.width()));
                g.drawLine(6, getHeight() / 2, getWidth() - 6, getHeight() / 2);
            } finally {
                g.dispose();
            }
        }
    }
}
