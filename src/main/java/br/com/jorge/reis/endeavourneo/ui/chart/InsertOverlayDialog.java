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

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

/**
 * Picks an indicator and its parameters.
 *
 * <p>The list on the left, the parameters on the right, changing as the
 * selection changes — the shape every "insert something" dialog has, because it
 * lets the reader browse without committing.</p>
 *
 * <p><b>The parameters are spinners with real bounds, not free text.</b> A
 * moving average of period zero divides by zero and one of 500.000 allocates a
 * pointless array; both are typed by accident, and validating after the fact
 * means an error dialog where a constrained control would have prevented the
 * mistake.</p>
 */
public final class InsertOverlayDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient List<JSpinner> spinners = new ArrayList<>();

    private final JPanel parameters = new JPanel();

    private final JList<OverlayCatalog.Kind> kinds =
            new JList<>(OverlayCatalog.kinds().toArray(new OverlayCatalog.Kind[0]));

    private transient Overlay chosen;

    private InsertOverlayDialog(Window owner, Overlay editing) {
        super(owner, Messages.get(editing == null ? "overlay.insertTitle" : "overlay.editTitle"),
                ModalityType.APPLICATION_MODAL);

        kinds.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        kinds.addListSelectionListener(e -> showParametersFor(kinds.getSelectedValue()));

        parameters.setLayout(new BoxLayout(parameters, BoxLayout.Y_AXIS));
        parameters.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 4));

        JScrollPane left = new JScrollPane(kinds);
        left.setPreferredSize(new Dimension(180, 200));

        JPanel body = new JPanel(new BorderLayout(8, 0));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        body.add(left, BorderLayout.WEST);
        body.add(parameters, BorderLayout.CENTER);

        add(body, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        closeOnEscape();

        selectFor(editing);

        setSize(new Dimension(460, 300));
        setLocationRelativeTo(owner);
    }

    /**
     * @param owner the window to centre on
     * @return the overlay to add, or null when the dialog was cancelled
     */
    public static Overlay ask(Window owner) {
        InsertOverlayDialog dialog = new InsertOverlayDialog(owner, null);

        dialog.setVisible(true);

        return dialog.chosen;
    }

    /**
     * @param owner the window to centre on
     * @param overlay the one being changed
     * @return a replacement built from the edited parameters, or null if cancelled
     *
     * <p>A replacement rather than a mutation. An overlay holds computed values;
     * changing a period means recomputing everything anyway, and building a new
     * one keeps it impossible to end up with parameters that no longer match the
     * numbers.</p>
     */
    public static Overlay edit(Window owner, Overlay overlay) {
        InsertOverlayDialog dialog = new InsertOverlayDialog(owner, overlay);

        dialog.setVisible(true);

        return dialog.chosen;
    }

    /**
     * Selects the kind that matches, and fills in the values it already has.
     *
     * <p>Matched by name key. An overlay whose kind is no longer in the
     * catalogue -- removed between versions -- simply lands on the first entry
     * rather than opening an empty dialog.</p>
     */
    private void selectFor(Overlay overlay) {
        if (overlay == null) {
            kinds.setSelectedIndex(0);

            return;
        }

        for (int i = 0; i < OverlayCatalog.kinds().size(); i++) {
            if (OverlayCatalog.kinds().get(i).nameKey().equals(overlay.nameKey())) {
                kinds.setSelectedIndex(i);

                List<Integer> current = overlay.parameters();

                for (int line = 0; line < spinners.size() && line < current.size(); line++) {
                    spinners.get(line).setValue(current.get(line));
                }

                return;
            }
        }

        kinds.setSelectedIndex(0);
    }

    private void showParametersFor(OverlayCatalog.Kind kind) {
        parameters.removeAll();
        spinners.clear();

        if (kind == null) {
            parameters.revalidate();
            parameters.repaint();

            return;
        }

        JLabel heading = new JLabel(Messages.get("overlay.periods"));

        heading.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        parameters.add(heading);

        for (int value : kind.defaults()) {
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                    value, kind.minimum(), kind.maximum(), 1));

            spinner.setAlignmentX(Component.LEFT_ALIGNMENT);
            spinner.setMaximumSize(new Dimension(120, spinner.getPreferredSize().height));

            spinners.add(spinner);
            parameters.add(spinner);
            parameters.add(javax.swing.Box.createVerticalStrut(4));
        }

        parameters.add(javax.swing.Box.createVerticalGlue());
        parameters.revalidate();
        parameters.repaint();
    }

    private JPanel buttons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));

        JButton cancel = new JButton(Messages.get("settings.cancel"));
        cancel.addActionListener(e -> dispose());

        JButton insert = new JButton(Messages.get("overlay.insert"));
        insert.addActionListener(e -> {
            OverlayCatalog.Kind kind = kinds.getSelectedValue();

            if (kind != null) {
                int[] values = new int[spinners.size()];

                for (int i = 0; i < values.length; i++) {
                    values[i] = (Integer) spinners.get(i).getValue();
                }

                chosen = kind.factory().apply(values);
            }

            dispose();
        });

        row.add(cancel);
        row.add(insert);

        getRootPane().setDefaultButton(insert);

        return row;
    }

    private void closeOnEscape() {
        getRootPane().registerKeyboardAction(new AbstractAction() {

            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                dispose();
            }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JRootPane.WHEN_IN_FOCUSED_WINDOW);
    }
}
