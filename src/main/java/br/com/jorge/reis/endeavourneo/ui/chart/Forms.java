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
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;

/**
 * The pieces an indicator's settings dialog is built from.
 *
 * <p>Here rather than in one dialog because there are now two of them, and a
 * second copy of the layout is how two dialogs of the same product end up
 * looking subtly unlike each other — different label spacing, a control a few
 * pixels shorter. The reader notices that without being able to say what is
 * wrong.</p>
 */
public final class Forms {

    private Forms() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    public static JPanel form() {
        JPanel panel = new JPanel(new GridBagLayout());

        panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        return panel;
    }

    /** A heading with a rule after it, as the reference product draws a group. */
    public static void group(JPanel panel, int row, String text) {
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
                UIManager.getColor("Separator.foreground")));

        panel.add(label, at);
    }

    public static void field(JPanel panel, int row, String text, JComponent editor) {
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

        // Never shorter than a text field would be. A component measured before
        // it has any content reports a height of almost nothing, and freezing
        // that is how a control ends up as a sliver -- which is exactly what
        // happened to the period button.
        int floor = new JTextField("X").getPreferredSize().height;

        editor.setPreferredSize(new Dimension(150,
                Math.max(editor.getPreferredSize().height, floor)));
        panel.add(editor, at);
    }

    /** One control spanning both columns, for a checkbox with no label beside it. */
    public static void across(JPanel panel, int row, JComponent control) {
        GridBagConstraints at = new GridBagConstraints();

        at.gridx = 0;
        at.gridy = row;
        at.gridwidth = 2;
        at.anchor = GridBagConstraints.WEST;
        at.insets = new Insets(3, 8, 3, 0);

        panel.add(control, at);
    }

    /** @param prefix a message key prefix; the enum's own name completes it */
    public static ListCellRenderer<Object> named(String prefix) {
        return new DefaultListCellRenderer() {

            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);

                if (value instanceof Enum<?> item) {
                    setText(Messages.get(prefix + item.name()));
                }

                return this;
            }
        };
    }

    /** A combo that draws each line style instead of naming it. */
    public static ListCellRenderer<Object> lineStyles() {
        return new DefaultListCellRenderer() {

            private static final long serialVersionUID = 1L;

            private transient MovingAverage.Line drawing = MovingAverage.Line.SOLID;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
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
        };
    }

    /** A square of the colour, beside its name. */
    static final class Swatch implements Icon {

        private final Color colour;

        Swatch(Color colour) {
            this.colour = colour;
        }

        @Override
        public void paintIcon(Component on, Graphics g, int x, int y) {
            g.setColor(colour);
            g.fillRect(x, y + 1, 12, 12);
            g.setColor(UIManager.getColor("Component.borderColor"));
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

    /**
     * The line as the chart will draw it.
     *
     * <p>Asks for its colour, style and width when it paints rather than being
     * told them, so it follows the controls as they change without anyone
     * having to remember to push a new value into it.</p>
     */
    static final class Sample extends JComponent {

        private static final long serialVersionUID = 1L;

        private final transient Supplier<Color> colour;

        private final transient Supplier<MovingAverage.Line> style;

        private final transient IntSupplier width;

        Sample(Supplier<Color> colour, Supplier<MovingAverage.Line> style, IntSupplier width) {
            this.colour = colour;
            this.style = style;
            this.width = width;
        }

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
                g.setColor(colour.get());

                MovingAverage.Line drawn = style.get();

                g.setStroke((drawn == null ? MovingAverage.Line.SOLID : drawn)
                        .stroke(width.getAsInt()));
                g.drawLine(4, getHeight() / 2, getWidth() - 4, getHeight() / 2);
            } finally {
                g.dispose();
            }
        }
    }

}
