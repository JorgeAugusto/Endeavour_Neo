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
import br.com.jorge.reis.endeavourneo.ui.chart.study.StudyPane;
import br.com.jorge.reis.endeavourneo.ui.chart.study.StudyStack;

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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JRootPane;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;

/**
 * Picks an indicator, its parameters, and where it goes.
 *
 * <p>The list on the left, the parameters and the destination on the right,
 * both changing as the selection changes — the shape every "insert something"
 * dialog has, because it lets the reader browse without committing.</p>
 *
 * <h2>One list, and the destination is a question</h2>
 *
 * <p>There used to be two menu items and two lists, and which one an indicator
 * was in decided where it could go. That made a moving average in a panel
 * impossible to ask for, and it made a stochastic on the price impossible to
 * refuse for any reason better than "it is in the other list".</p>
 *
 * <p>Now the destination is picked here and each indicator answers for itself:
 * {@link Overlay#fitsOnPrice()} for the price, and {@link StudyStack#fits} for
 * joining a panel that already holds something.</p>
 *
 * <h2>The reason sits under the option, not behind the click</h2>
 *
 * <p>A destination that will not take this indicator is <b>disabled with the
 * reason written under it</b>, before anything is chosen. The alternative —
 * accepting the click and then showing an alert — is a message the reader gets
 * only after deciding, which is the point at which they are least willing to
 * read it.</p>
 *
 * <p><b>The parameters are spinners with real bounds, not free text.</b> A
 * moving average of period zero divides by zero and one of 500.000 allocates a
 * pointless array; both are typed by accident, and validating after the fact
 * means an error dialog where a constrained control would have prevented the
 * mistake.</p>
 */
public final class InsertOverlayDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    /**
     * An indicator and where it was sent.
     *
     * @param indicator what to place
     * @param pane the pane to join, or null for the price or a new one
     * @param onPrice whether it goes on the price
     */
    public record Placement(Overlay indicator, StudyPane pane, boolean onPrice) {

        /**
         * Refuses the state that means two things at once.
         *
         * <p>A placement on the price line has no pane, and one in a pane is not
         * on the price line. Holding both, {@code inNewPane()} answers "no",
         * which is a third meaning nobody wrote -- and the caller reads
         * {@code onPrice()} first and escapes only because of the order it
         * happens to test in.</p>
         */
        public Placement {
            if (onPrice && pane != null) {
                throw new IllegalArgumentException(
                        "a placement on the price line cannot also name a pane");
            }
        }

        /** @return whether this asks for a pane of its own */
        public boolean inNewPane() {
            return !onPrice && pane == null;
        }
    }

    private final transient List<JSpinner> spinners = new ArrayList<>();

    private final JPanel parameters = new JPanel();

    private final JPanel destination = new JPanel();

    private final JList<OverlayCatalog.Kind> kinds =
            new JList<>(OverlayCatalog.kinds().toArray(new OverlayCatalog.Kind[0]));

    /** The panes already on this chart, in the order they are stacked. */
    private final transient List<StudyPane> panes;

    private final JRadioButton onPrice = new JRadioButton(Messages.get("overlay.where.price"));

    private final JRadioButton newPane = new JRadioButton(Messages.get("overlay.where.newPane"));

    private final transient List<JRadioButton> joins = new ArrayList<>();

    private transient Placement chosen;

    private InsertOverlayDialog(Window owner, Overlay editing, List<StudyPane> panes) {
        super(owner, Messages.get(editing == null ? "overlay.insertTitle" : "overlay.editTitle"),
                ModalityType.APPLICATION_MODAL);

        this.panes = List.copyOf(panes);

        kinds.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        kinds.addListSelectionListener(e -> showKind(kinds.getSelectedValue()));

        parameters.setLayout(new BoxLayout(parameters, BoxLayout.Y_AXIS));

        destination.setLayout(new BoxLayout(destination, BoxLayout.Y_AXIS));
        destination.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

        JPanel right = new JPanel();

        right.setLayout(new BoxLayout(right, BoxLayout.Y_AXIS));
        right.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 4));
        right.add(parameters);
        right.add(destination);
        right.add(Box.createVerticalGlue());

        JScrollPane left = new JScrollPane(kinds);
        left.setPreferredSize(new Dimension(180, 240));

        JPanel body = new JPanel(new BorderLayout(8, 0));
        body.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        body.add(left, BorderLayout.WEST);
        body.add(new JScrollPane(right), BorderLayout.CENTER);

        add(body, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        closeOnEscape();

        selectFor(editing);

        setSize(new Dimension(520, 380));
        setLocationRelativeTo(owner);
    }

    /**
     * @param owner the window to centre on
     * @param panes the panes already under this chart, offered as destinations
     * @return where to put what, or null when the dialog was cancelled
     */
    public static Placement ask(Window owner, List<StudyPane> panes) {
        InsertOverlayDialog dialog = new InsertOverlayDialog(owner, null, panes);

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
     *
     * <p>No destination here: the indicator is already somewhere, and editing
     * its period is not a request to move it.</p>
     */
    public static Overlay edit(Window owner, Overlay overlay) {
        InsertOverlayDialog dialog = new InsertOverlayDialog(owner, overlay, List.of());

        dialog.destination.setVisible(false);
        dialog.setVisible(true);

        return dialog.chosen == null ? null : dialog.chosen.indicator();
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

    private void showKind(OverlayCatalog.Kind kind) {
        showParametersFor(kind);
        showDestinationsFor(kind);
    }

    private void showParametersFor(OverlayCatalog.Kind kind) {
        parameters.removeAll();
        spinners.clear();

        if (kind == null) {
            parameters.revalidate();
            parameters.repaint();

            return;
        }

        parameters.add(heading(Messages.get("overlay.periods")));

        for (int value : kind.defaults()) {
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(
                    value, kind.minimum(), kind.maximum(), 1));

            spinner.setAlignmentX(Component.LEFT_ALIGNMENT);
            spinner.setMaximumSize(new Dimension(120, spinner.getPreferredSize().height));

            spinners.add(spinner);
            parameters.add(spinner);
            parameters.add(Box.createVerticalStrut(4));
        }

        parameters.revalidate();
        parameters.repaint();
    }

    /**
     * The places this indicator could go, and why it cannot go to the others.
     *
     * <p>Rebuilt on every selection because the answers are the indicator's,
     * not the chart's: a stochastic and a moving average offered the same three
     * destinations would be a dialog that had not asked either of them.</p>
     */
    private void showDestinationsFor(OverlayCatalog.Kind kind) {
        destination.removeAll();
        joins.clear();

        if (kind == null) {
            destination.revalidate();
            destination.repaint();

            return;
        }

        Overlay sample = build(kind);
        ButtonGroup group = new ButtonGroup();

        destination.add(heading(Messages.get("overlay.where")));

        onPrice.setEnabled(sample != null && sample.fitsOnPrice());
        add(group, onPrice, onPrice.isEnabled() ? null
                : Messages.get("overlay.where.notOnPrice", kind.label()));

        newPane.setEnabled(true);
        add(group, newPane, null);

        for (StudyPane pane : panes) {
            JRadioButton join = new JRadioButton(
                    Messages.get("overlay.where.pane", pane.title()));

            join.setEnabled(sample != null && StudyStack.fits(pane.studies(), sample));

            joins.add(join);
            add(group, join, join.isEnabled() ? null
                    : Messages.get("overlay.where.notThisPane"));
        }

        // Whatever is possible, preferring the price for something that belongs
        // there. Landing on a disabled option would be a dialog whose Insert
        // button does nothing.
        (onPrice.isEnabled() ? onPrice : newPane).setSelected(true);

        destination.revalidate();
        destination.repaint();
    }

    private void add(ButtonGroup group, JRadioButton button, String why) {
        button.setAlignmentX(Component.LEFT_ALIGNMENT);

        group.add(button);
        destination.add(button);

        if (why == null) {
            return;
        }

        // Under the option it explains, and indented past it, so it reads as
        // that option's reason and not as a warning about the dialog.
        // The width is written into the html because that is the only thing a
        // label wraps on. Left free, it lays itself out on one line as wide as
        // the sentence and the dialog clips it mid-word.
        // ESCAPED, because `why` carries an indicator's name inside it and a
        // name is text somebody can choose. SeriesSummary already solves this
        // exact problem the same way and says why: a name typed by the reader
        // tomorrow could carry a bracket and take the rest of the label with
        // it.
        JLabel reason = new JLabel("<html><body style='width:250px'>"
                + escape(why) + "</body></html>");

        reason.setAlignmentX(Component.LEFT_ALIGNMENT);
        reason.setBorder(BorderFactory.createEmptyBorder(0, 22, 4, 0));
        reason.setForeground(ChartColors.foreground());
        reason.setFont(reason.getFont().deriveFont(reason.getFont().getSize2D() - 1f));
        reason.setEnabled(false);

        destination.add(reason);
    }

    private static JLabel heading(String text) {
        JLabel label = new JLabel(text);

        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        label.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

        return label;
    }

    /** @return one built from what the spinners currently say */
    private Overlay build(OverlayCatalog.Kind kind) {
        int[] values = new int[spinners.size()];

        for (int i = 0; i < values.length; i++) {
            values[i] = (Integer) spinners.get(i).getValue();
        }

        return kind.factory().apply(values.length == 0
                ? toArray(kind.defaults()) : values);
    }

    private static int[] toArray(List<Integer> numbers) {
        int[] values = new int[numbers.size()];

        for (int i = 0; i < values.length; i++) {
            values[i] = numbers.get(i);
        }

        return values;
    }

    private JPanel buttons() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));

        JButton cancel = new JButton(Messages.get("settings.cancel"));
        cancel.addActionListener(e -> dispose());

        JButton insert = new JButton(Messages.get("overlay.insert"));
        insert.addActionListener(e -> {
            OverlayCatalog.Kind kind = kinds.getSelectedValue();

            if (kind != null) {
                chosen = new Placement(build(kind), joined(), onPrice.isSelected());
            }

            dispose();
        });

        row.add(cancel);
        row.add(insert);

        getRootPane().setDefaultButton(insert);

        return row;
    }

    /** @return the pane whose option is selected, or null for none */
    private StudyPane joined() {
        for (int i = 0; i < joins.size() && i < panes.size(); i++) {
            if (joins.get(i).isSelected()) {
                return panes.get(i);
            }
        }

        return null;
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
    /**
     * @param text anything that may carry a name somebody chose
     * @return the same text, safe to put inside a Swing HTML label
     *
     * <p>The same four replacements SeriesSummary makes, and for the reason it
     * states there: a name typed by the reader tomorrow could carry a bracket
     * and take the rest of the label with it.</p>
     */
    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }
}
