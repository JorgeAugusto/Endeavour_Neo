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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;

/**
 * The settings of one moving average, in the shape the reference product uses.
 *
 * <p>Three tabs, and the split is not arbitrary: <b>Parameters</b> changes what
 * is computed, <b>Appearance</b> changes only how it is drawn, and <b>Values</b>
 * says which price is read. Somebody adjusting a colour never has to look at a
 * period, which is the whole reason for tabs rather than one long form.</p>
 *
 * <p>The reference product has a fourth tab, <i>Instrument/Period</i>, for
 * reading an average of a different symbol or a larger scale. That is a real
 * feature and it is not built, so the tab is <b>absent rather than empty</b>: a
 * tab that opens onto nothing is discovered by clicking, and reads as broken.</p>
 *
 * <p>The <b>sample</b> at the bottom of Appearance is drawn with the very stroke
 * the chart will use. A preview built by other code is a preview that can
 * disagree with the thing it previews.</p>
 */
public final class MovingAverageDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient MovingAverage average;

    private final JSpinner period;

    private final JSpinner shift;

    private final JSpinner thickness;

    private final JComboBox<MovingAverage.Kind> kind =
            new JComboBox<>(MovingAverage.Kind.values());

    private final JComboBox<MovingAverage.Source> source =
            new JComboBox<>(MovingAverage.Source.values());

    private final JComboBox<MovingAverage.Line> line =
            new JComboBox<>(MovingAverage.Line.values());

    private final JButton colour = new JButton();

    private final Sample sample = new Sample();

    private transient Color chosen;

    private transient boolean accepted;

    private MovingAverageDialog(Window owner, MovingAverage average) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.average = average;
        this.chosen = average.chosenColour();

        this.period = new JSpinner(new SpinnerNumberModel(average.period(), 1, 2_000, 1));
        this.shift = new JSpinner(new SpinnerNumberModel(average.shift(), -500, 500, 1));
        this.thickness = new JSpinner(new SpinnerNumberModel(average.thickness(), 1, 8, 1));

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(average.nameKey()) + " [" + average.period() + "]"));

        kind.setSelectedItem(average.kind());
        source.setSelectedItem(average.source());
        line.setSelectedItem(average.line());

        kind.setRenderer(named("overlay.ma.kind."));
        source.setRenderer(named("overlay.ma.source."));
        line.setRenderer(new LineRenderer());

        colour.addActionListener(e -> pickColour());
        paintColourButton();

        for (JComponent each : new JComponent[]{line, thickness}) {
            addRefresh(each);
        }

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());
        tabs.addTab(Messages.get("overlay.tab.values"), values());

        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(Math.max(460, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * @return the average with the changes applied, or null if it was cancelled
     *
     * <p>The SAME object, changed in place. Handing back a copy would break the
     * chart's own list, which holds the one it was given.</p>
     */
    public static MovingAverage edit(Window owner, MovingAverage average) {
        MovingAverageDialog dialog = new MovingAverageDialog(owner, average);

        dialog.setVisible(true);

        return dialog.accepted ? average : null;
    }

    // ------------------------------------------------------------- the tabs

    private JComponent parameters() {
        JPanel panel = form();

        group(panel, 0, Messages.get("overlay.ma.average"));
        field(panel, 1, Messages.get("overlay.ma.period"), period);
        field(panel, 2, Messages.get("overlay.ma.kind"), kind);

        group(panel, 3, Messages.get("overlay.ma.shift"));
        field(panel, 4, Messages.get("overlay.ma.period"), shift);

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = form();

        group(panel, 0, Messages.get("overlay.ma.line"));
        field(panel, 1, Messages.get("overlay.ma.style"), line);
        field(panel, 2, Messages.get("overlay.ma.colour"), colour);
        field(panel, 3, Messages.get("overlay.ma.thickness"), thickness);

        group(panel, 4, Messages.get("overlay.ma.sample"));

        GridBagConstraints at = new GridBagConstraints();

        at.gridx = 0;
        at.gridy = 5;
        at.gridwidth = 2;
        at.weightx = 1;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.insets = new Insets(4, 12, 8, 12);

        panel.add(sample, at);

        return panel;
    }

    private JComponent values() {
        JPanel panel = form();

        group(panel, 0, Messages.get("overlay.ma.values"));
        field(panel, 1, Messages.get("overlay.ma.source"), source);

        return panel;
    }

    // ------------------------------------------------------------ the pieces

    private static JPanel form() {
        JPanel panel = new JPanel(new GridBagLayout());

        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        return panel;
    }

    /** A heading with a rule after it, as the reference product draws a group. */
    private static void group(JPanel panel, int row, String text) {
        GridBagConstraints at = new GridBagConstraints();

        at.gridx = 0;
        at.gridy = row;
        at.gridwidth = 2;
        at.weightx = 1;
        at.anchor = GridBagConstraints.WEST;
        at.fill = GridBagConstraints.HORIZONTAL;
        at.insets = new Insets(row == 0 ? 0 : 14, 0, 4, 0);

        JLabel label = new JLabel(text);

        label.setFont(label.getFont().deriveFont(Font.BOLD));
        label.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                javax.swing.UIManager.getColor("Separator.foreground")));

        panel.add(label, at);
    }

    private static void field(JPanel panel, int row, String text, JComponent editor) {
        GridBagConstraints label = new GridBagConstraints();

        label.gridx = 0;
        label.gridy = row;
        label.anchor = GridBagConstraints.EAST;
        label.insets = new Insets(3, 12, 3, 8);

        panel.add(new JLabel(text), label);

        GridBagConstraints at = new GridBagConstraints();

        at.gridx = 1;
        at.gridy = row;
        at.weightx = 1;
        at.anchor = GridBagConstraints.WEST;
        at.insets = new Insets(3, 0, 3, 0);

        editor.setPreferredSize(new Dimension(150, editor.getPreferredSize().height));
        panel.add(editor, at);
    }

    private JComponent buttons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));

        JButton cancel = new JButton(Messages.get("dialog.cancel"));
        JButton ok = new JButton(Messages.get("dialog.ok"));

        cancel.addActionListener(e -> dispose());
        ok.addActionListener(e -> {
            apply();

            accepted = true;

            dispose();
        });

        row.add(cancel);
        row.add(ok);
        getRootPane().setDefaultButton(ok);

        return row;
    }

    private void apply() {
        average.setPeriod((Integer) period.getValue());
        average.setShift((Integer) shift.getValue());
        average.setThickness((Integer) thickness.getValue());
        average.setKind((MovingAverage.Kind) kind.getSelectedItem());
        average.setSource((MovingAverage.Source) source.getSelectedItem());
        average.setLine((MovingAverage.Line) line.getSelectedItem());
        average.setColour(chosen);
    }

    private void pickColour() {
        Color picked = JColorChooser.showDialog(this, Messages.get("overlay.ma.colour"),
                chosen == null ? average.colours().get(0) : chosen);

        if (picked != null) {
            chosen = picked;

            paintColourButton();
            sample.repaint();
        }
    }

    private void paintColourButton() {
        colour.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        colour.setIcon(new Swatch(chosen == null ? average.colours().get(0) : chosen));
        colour.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
    }

    private void addRefresh(JComponent editor) {
        if (editor instanceof JComboBox<?> combo) {
            combo.addActionListener(e -> sample.repaint());
        } else if (editor instanceof JSpinner spinner) {
            spinner.addChangeListener(e -> sample.repaint());
        }
    }

    private javax.swing.ListCellRenderer<Object> named(String prefix) {
        return new javax.swing.DefaultListCellRenderer() {

            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);

                if (value instanceof Enum<?> item) {
                    setText(Messages.get(prefix + item.name()));
                }

                return this;
            }
        };
    }

    /** The style list shows the styles themselves; a word for a dash is a riddle. */
    private final class LineRenderer extends javax.swing.DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        private transient MovingAverage.Line drawing = MovingAverage.Line.SOLID;

        @Override
        public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
                int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);

            if (value instanceof MovingAverage.Line item) {
                drawing = item;
            }

            setText(" ");
            setPreferredSize(new Dimension(120, 18));

            return this;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);

            Graphics2D g = (Graphics2D) graphics.create();

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(getForeground());
                g.setStroke(drawing.stroke(1.4f));
                g.drawLine(8, getHeight() / 2, getWidth() - 8, getHeight() / 2);
            } finally {
                g.dispose();
            }
        }
    }

    /** A square of the colour, beside its name. */
    private static final class Swatch implements javax.swing.Icon {

        private final Color colour;

        Swatch(Color colour) {
            this.colour = colour;
        }

        @Override
        public void paintIcon(Component on, Graphics g, int x, int y) {
            g.setColor(colour);
            g.fillRect(x, y + 1, 12, 12);
            g.setColor(javax.swing.UIManager.getColor("Component.borderColor"));
            g.drawRect(x, y + 1, 12, 12);
        }

        @Override
        public int getIconWidth() {
            return 14;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }

    /** The line as the chart will draw it, using the chart's own stroke. */
    private final class Sample extends JComponent {

        private static final long serialVersionUID = 1L;

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(200, 26);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(chosen == null ? average.colours().get(0) : chosen);

                MovingAverage.Line drawn = (MovingAverage.Line) line.getSelectedItem();

                g.setStroke((drawn == null ? MovingAverage.Line.SOLID : drawn)
                        .stroke((Integer) thickness.getValue()));
                g.drawLine(4, getHeight() / 2, getWidth() - 4, getHeight() / 2);
            } finally {
                g.dispose();
            }
        }
    }

    /** Kept out of the way of the layout: it is a spacer, not a control. */
    @SuppressWarnings("unused")
    private static Component glue() {
        return Box.createVerticalGlue();
    }
}
