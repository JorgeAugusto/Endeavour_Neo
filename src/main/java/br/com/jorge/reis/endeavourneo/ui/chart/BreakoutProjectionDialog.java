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

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.BreakoutProjection;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.TopsAndBottoms;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import javax.swing.JButton;
import javax.swing.JCheckBox;
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
 * The settings for {@link BreakoutProjection}.
 *
 * <p>Two tabs. The parameters tab holds what decides IF and WHERE a projection
 * appears -- the wing of the zigzag, how close the price has to come, and the
 * smallest leg worth projecting. The appearance tab dresses the two sides and
 * switches the rungs on and off.</p>
 *
 * <p>The three distances are in POINTS OF THE INDEX and not percentages,
 * exactly as the reference product spells them, because that is how a reader of
 * this market thinks about them: fifty points of the mini index is a number he
 * has a feel for, and half a percent of it is not.</p>
 */
public final class BreakoutProjectionDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient BreakoutProjection projection;

    private final JSpinner wing;

    private final JComboBox<TopsAndBottoms.Ties> ties =
            new JComboBox<>(TopsAndBottoms.Ties.values());

    private final JSpinner proximity;

    private final JSpinner leastLeg;

    private final JSpinner entry;

    private final JComboBox<MovingAverage.Line> line =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner thickness;

    private final JButton risingColour = new JButton();

    private final JButton fallingColour = new JButton();

    private final JCheckBox show25 = new JCheckBox("25%");

    private final JCheckBox show75 = new JCheckBox("75%");

    private final JCheckBox show200 = new JCheckBox("200%");

    private final JCheckBox show261 = new JCheckBox("261%");

    private final JCheckBox showEntry =
            new JCheckBox(Messages.get("overlay.romp.entry"));

    private final JCheckBox showStop =
            new JCheckBox(Messages.get("overlay.romp.stop"));

    private transient Color chosenRising;

    private transient Color chosenFalling;

    private final transient Forms.Sample risingSample;

    private final transient Forms.Sample fallingSample;

    private transient boolean accepted;

    private BreakoutProjectionDialog(Window owner, BreakoutProjection projection) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.projection = projection;
        this.chosenRising = projection.chosenRisingColour();
        this.chosenFalling = projection.chosenFallingColour();

        this.wing = new JSpinner(new SpinnerNumberModel(projection.wing(), 1,
                TopsAndBottoms.MOST_WING, 1));
        this.proximity = new JSpinner(
                new SpinnerNumberModel(projection.proximity(), 0, 10_000, 5));
        this.leastLeg = new JSpinner(
                new SpinnerNumberModel(projection.leastLeg(), 0, 10_000, 5));
        this.entry = new JSpinner(new SpinnerNumberModel(projection.entry(), 0, 10_000, 5));
        this.thickness = new JSpinner(new SpinnerNumberModel(projection.thickness(), 1, 8, 1));

        this.risingSample = new Forms.Sample(
                () -> chosenRising == null ? projection.colours().get(0) : chosenRising,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());
        this.fallingSample = new Forms.Sample(
                () -> chosenFalling == null ? projection.colours().get(1) : chosenFalling,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(projection.nameKey()) + " [" + projection.wing() + "]"));

        ties.setSelectedItem(projection.ties());
        line.setSelectedItem(projection.line());
        show25.setSelected(projection.shows25());
        show75.setSelected(projection.shows75());
        show200.setSelected(projection.shows200());
        show261.setSelected(projection.shows261());
        showEntry.setSelected(projection.showsEntry());
        showStop.setSelected(projection.showsStop());

        ties.setRenderer(Forms.named("overlay.pivots.ties."));
        line.setRenderer(Forms.lineStyles());

        risingColour.addActionListener(e -> pick(true));
        fallingColour.addActionListener(e -> pick(false));

        line.addActionListener(e -> repaintSamples());
        thickness.addChangeListener(e -> repaintSamples());

        paintButtons();

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());

        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(Math.max(520, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * @return the indicator with the changes applied, or null if it was cancelled
     *
     * <p>The SAME object, changed in place, as the other dialogs do it.</p>
     */
    public static BreakoutProjection edit(Window owner, BreakoutProjection projection) {
        BreakoutProjectionDialog dialog = new BreakoutProjectionDialog(owner, projection);

        dialog.setVisible(true);

        return dialog.accepted ? projection : null;
    }

    private JComponent parameters() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.channel.turns"));
        Forms.field(panel, 1, Messages.get("overlay.pivots.wing"), wing);
        Forms.field(panel, 2, Messages.get("overlay.pivots.ties.rule"), ties);

        Forms.group(panel, 3, Messages.get("overlay.romp.trigger"));
        Forms.field(panel, 4, Messages.get("overlay.romp.proximity"), proximity);
        Forms.field(panel, 5, Messages.get("overlay.romp.leastLeg"), leastLeg);
        Forms.field(panel, 6, Messages.get("overlay.romp.entryPoints"), entry);

        // Zero espera o rompimento consumado; o padrao dispara cinquenta pontos
        // antes, que e o que o indicador do Profit faz.
        proximity.setToolTipText(Messages.get("overlay.romp.proximity.hint"));

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), line);
        Forms.field(panel, 2, Messages.get("overlay.ma.thickness"), thickness);

        Forms.group(panel, 3, Messages.get("overlay.romp.rising"));
        Forms.field(panel, 4, Messages.get("overlay.ma.colour"), risingColour);
        Forms.across(panel, 5, risingSample);

        Forms.group(panel, 6, Messages.get("overlay.romp.falling"));
        Forms.field(panel, 7, Messages.get("overlay.ma.colour"), fallingColour);
        Forms.across(panel, 8, fallingSample);

        Forms.group(panel, 9, Messages.get("overlay.romp.rungs"));
        Forms.across(panel, 10, show25);
        Forms.across(panel, 11, show75);
        Forms.across(panel, 12, show200);
        Forms.across(panel, 13, show261);
        Forms.across(panel, 14, showEntry);
        Forms.across(panel, 15, showStop);

        return panel;
    }

    private void repaintSamples() {
        risingSample.repaint();
        fallingSample.repaint();
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
        projection.setWing((Integer) wing.getValue());
        projection.setTies((TopsAndBottoms.Ties) ties.getSelectedItem());
        projection.setProximity((Integer) proximity.getValue());
        projection.setLeastLeg((Integer) leastLeg.getValue());
        projection.setEntry((Integer) entry.getValue());
        projection.setLine((MovingAverage.Line) line.getSelectedItem());
        projection.setThickness((Integer) thickness.getValue());
        projection.setRisingColour(chosenRising);
        projection.setFallingColour(chosenFalling);
        projection.setShows25(show25.isSelected());
        projection.setShows75(show75.isSelected());
        projection.setShows200(show200.isSelected());
        projection.setShows261(show261.isSelected());
        projection.setShowsEntry(showEntry.isSelected());
        projection.setShowsStop(showStop.isSelected());
    }

    private void pick(boolean rising) {
        Color current = rising ? chosenRising : chosenFalling;
        Color fallback = projection.colours().get(rising ? 0 : 1);
        Color picked = JColorChooser.showDialog(this,
                Messages.get(rising ? "overlay.romp.rising" : "overlay.romp.falling"),
                current == null ? fallback : current);

        if (picked == null) {
            return;
        }

        if (rising) {
            chosenRising = picked;
        } else {
            chosenFalling = picked;
        }

        paintButtons();
        repaintSamples();
    }

    private void paintButtons() {
        paintButton(risingColour, chosenRising, projection.colours().get(0));
        paintButton(fallingColour, chosenFalling, projection.colours().get(1));
    }

    private static void paintButton(JButton button, Color chosen, Color fallback) {
        button.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        button.setIcon(new Forms.Swatch(chosen == null ? fallback : chosen));
        button.setHorizontalAlignment(SwingConstants.LEFT);
    }
}
