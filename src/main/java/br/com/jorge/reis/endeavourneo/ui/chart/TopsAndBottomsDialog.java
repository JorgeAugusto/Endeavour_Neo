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
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.TopsAndBottoms;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.JButton;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

/**
 * The settings for {@link TopsAndBottoms}, in the tabs the other indicators
 * use.
 *
 * <p>Two tabs, because there are two things to say. The wing decides how much
 * of a turn counts as one, and the tie rule decides what happens on a flat
 * stretch — that one is a real choice and not a detail, which is why it is on
 * the parameters tab beside the wing rather than hidden anywhere.</p>
 *
 * <p>No scale of its own and no source. A pivot IS a bar's high or low, so
 * there is nothing to choose a source from; and a zigzag drawn from a coarser
 * scale would need its legs mapped back bar by bar, which is not the mapping
 * {@code OwnScale} does — it spreads one value per coarse bar, and a leg is two
 * points and the straight line between them.</p>
 */
public final class TopsAndBottomsDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient TopsAndBottoms pivots;

    private final JSpinner wing;

    private final JComboBox<TopsAndBottoms.Ties> ties =
            new JComboBox<>(TopsAndBottoms.Ties.values());

    private final JComboBox<MovingAverage.Line> line =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner thickness;

    private final JButton colour = new JButton();

    private transient Color chosen;

    private final transient Forms.Sample sample;

    private transient boolean accepted;

    private TopsAndBottomsDialog(Window owner, TopsAndBottoms pivots) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.pivots = pivots;
        this.chosen = pivots.chosenColour();

        this.wing = new JSpinner(new SpinnerNumberModel(pivots.wing(), 1,
                TopsAndBottoms.MOST_WING, 1));
        this.thickness = new JSpinner(new SpinnerNumberModel(pivots.thickness(), 1, 8, 1));

        this.sample = new Forms.Sample(
                () -> chosen == null ? pivots.colours().get(0) : chosen,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(pivots.nameKey()) + " [" + pivots.wing() + "]"));

        ties.setSelectedItem(pivots.ties());
        line.setSelectedItem(pivots.line());

        ties.setRenderer(Forms.named("overlay.pivots.ties."));
        line.setRenderer(Forms.lineStyles());

        colour.addActionListener(e -> pick());
        line.addActionListener(e -> sample.repaint());
        thickness.addChangeListener(e -> sample.repaint());
        ties.addActionListener(e -> refreshTies());

        paintButton();

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());

        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(Math.max(460, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * @return the indicator with the changes applied, or null if it was cancelled
     *
     * <p>The SAME object, changed in place, as the other dialogs do it: the
     * chart holds the one it was given.</p>
     */
    public static TopsAndBottoms edit(Window owner, TopsAndBottoms pivots) {
        TopsAndBottomsDialog dialog = new TopsAndBottomsDialog(owner, pivots);

        dialog.setVisible(true);

        return dialog.accepted ? pivots : null;
    }

    private JComponent parameters() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.tab.parameters"));
        Forms.field(panel, 1, Messages.get("overlay.pivots.wing"), wing);

        Forms.group(panel, 2, Messages.get("overlay.pivots.ties"));
        Forms.field(panel, 3, Messages.get("overlay.pivots.ties.rule"), ties);

        refreshTies();

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), line);
        Forms.field(panel, 2, Messages.get("overlay.ma.colour"), colour);
        Forms.field(panel, 3, Messages.get("overlay.ma.thickness"), thickness);

        Forms.group(panel, 4, Messages.get("overlay.ma.sample"));
        Forms.across(panel, 5, sample);

        return panel;
    }

    /** Says what the chosen rule does, because the names cannot say it alone. */
    private void refreshTies() {
        ties.setToolTipText(ties.getSelectedItem() == TopsAndBottoms.Ties.LAST
                ? Messages.get("overlay.pivots.ties.LAST.hint")
                : Messages.get("overlay.pivots.ties.STRICT.hint"));
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
        pivots.setWing((Integer) wing.getValue());
        pivots.setTies((TopsAndBottoms.Ties) ties.getSelectedItem());
        pivots.setLine((MovingAverage.Line) line.getSelectedItem());
        pivots.setThickness((Integer) thickness.getValue());
        pivots.setColour(chosen);
    }

    private void pick() {
        Color picked = JColorChooser.showDialog(this, Messages.get("overlay.ma.colour"),
                chosen == null ? pivots.colours().get(0) : chosen);

        if (picked == null) {
            return;
        }

        chosen = picked;

        paintButton();
        sample.repaint();
    }

    private void paintButton() {
        colour.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        colour.setIcon(new Forms.Swatch(chosen == null ? pivots.colours().get(0) : chosen));
        colour.setHorizontalAlignment(SwingConstants.LEFT);
    }
}
