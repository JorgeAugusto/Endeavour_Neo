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
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Type a number, get a period.
 *
 * <p>The window opens <b>already typing</b>: the reader pressed a digit on the
 * chart and that digit is in the box, so the list is useful before anything else
 * is done. Opening empty and asking them to type it again is the small rudeness
 * that makes a shortcut not worth using.</p>
 *
 * <p>Enter takes the first row, which is why the list is ordered best guess
 * first. Double-clicking takes the row clicked. Escape leaves everything as it
 * was.</p>
 */
public final class PeriodDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient JTextField box = new JTextField();

    private final DefaultListModel<PeriodCatalog.Choice> model = new DefaultListModel<>();

    private final JList<PeriodCatalog.Choice> list = new JList<>(model);

    private transient PeriodCatalog.Choice chosen;

    private PeriodDialog(Window owner, String start) {
        super(owner, Messages.get("period.title"), ModalityType.APPLICATION_MODAL);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Row());
        list.setVisibleRowCount(12);

        box.setText(start == null ? "" : start);
        box.setBorder(BorderFactory.createCompoundBorder(
                box.getBorder(), BorderFactory.createEmptyBorder(4, 6, 4, 6)));

        box.getDocument().addDocumentListener(new DocumentListener() {

            @Override
            public void insertUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refresh();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refresh();
            }
        });

        // The arrows move the list while the caret stays in the box: the reader
        // is typing and choosing at the same time, and having to Tab between the
        // two would undo the point of typing to search.
        box.addKeyListener(new KeyAdapter() {

            @Override
            public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> move(+1);
                    case KeyEvent.VK_UP -> move(-1);
                    case KeyEvent.VK_ENTER -> take();
                    case KeyEvent.VK_ESCAPE -> dispose();
                    default -> { }
                }
            }
        });

        list.addMouseListener(new MouseAdapter() {

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    take();
                }
            }
        });

        JPanel top = new JPanel(new BorderLayout(6, 0));

        top.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));
        top.add(new JLabel(Messages.get("period.prompt")), BorderLayout.WEST);
        top.add(box, BorderLayout.CENTER);

        JScrollPane scroll = new JScrollPane(list);

        scroll.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);

        refresh();
        setSize(new Dimension(460, 340));
        setLocationRelativeTo(owner);
    }

    /**
     * @param owner the window to sit over
     * @param start what the reader already typed, or null
     * @return the period chosen, or null if they left
     */
    public static PeriodCatalog.Choice ask(Window owner, String start) {
        PeriodDialog dialog = new PeriodDialog(owner, start);

        dialog.setVisible(true);

        return dialog.chosen;
    }

    private void refresh() {
        List<PeriodCatalog.Choice> found = PeriodCatalog.forText(box.getText());

        model.clear();

        for (PeriodCatalog.Choice choice : found) {
            model.addElement(choice);
        }

        if (!model.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    private void move(int step) {
        if (model.isEmpty()) {
            return;
        }

        int next = Math.floorMod(list.getSelectedIndex() + step, model.size());

        list.setSelectedIndex(next);
        list.ensureIndexIsVisible(next);
    }

    private void take() {
        chosen = list.getSelectedValue();

        if (chosen != null) {
            dispose();
        }
    }

    /** Code on the left, description beside it — the reference product's shape. */
    private static final class Row extends javax.swing.DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public java.awt.Component getListCellRendererComponent(
                JList<?> from, Object value, int index, boolean selected, boolean focused) {
            super.getListCellRendererComponent(from, value, index, selected, focused);

            if (value instanceof PeriodCatalog.Choice choice) {
                // Padded so the two columns line up without a table: a table
                // here would bring headers, sorting and resizing, none of which
                // a list of ten rows wants.
                setText(String.format("%-6s   %s", choice.code(), choice.description()));
                setFont(br.com.jorge.reis.endeavourneo.platform.Appearance.monospaced(12));
            }

            setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

            return this;
        }
    }
}
