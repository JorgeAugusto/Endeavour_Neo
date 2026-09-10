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
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.ZigzagProjection;

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
 * The settings for {@link ZigzagProjection}.
 *
 * <p>Two tabs. The parameters tab holds the three things that decide which leg
 * is measured -- the average, its scale, and the zigzag's wing -- plus where
 * the ladder of levels is measured from. The appearance tab dresses the two
 * sets of lines, one colour for the leg that crossed up and one for the leg
 * that crossed down.</p>
 *
 * <p>The four percentages are NOT settings. Fifty, a hundred, a hundred and
 * fifty and two hundred are what the indicator is; a reader who wants other
 * numbers wants the regression channel's levels table, and putting one here
 * would be building a second one.</p>
 */
public final class ZigzagProjectionDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient ZigzagProjection projection;

    private final JSpinner period;

    private final JSpinner wing;

    private final JButton scale = new JButton();

    private transient String scaleCode;

    private final JComboBox<TopsAndBottoms.Ties> ties =
            new JComboBox<>(TopsAndBottoms.Ties.values());

    private final JComboBox<ZigzagProjection.Measure> measure =
            new JComboBox<>(ZigzagProjection.Measure.values());

    private final JComboBox<MovingAverage.Line> line =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner thickness;

    private final JButton risingColour = new JButton();

    private final JButton fallingColour = new JButton();

    private transient Color chosenRising;

    private transient Color chosenFalling;

    private final transient Forms.Sample risingSample;

    private final transient Forms.Sample fallingSample;

    private transient boolean accepted;

    private ZigzagProjectionDialog(Window owner, ZigzagProjection projection) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.projection = projection;
        this.chosenRising = projection.chosenRisingColour();
        this.chosenFalling = projection.chosenFallingColour();
        this.scaleCode = projection.scale();

        this.period = new JSpinner(new SpinnerNumberModel(projection.period(), 2, 2_000, 1));
        this.wing = new JSpinner(new SpinnerNumberModel(projection.wing(), 1,
                TopsAndBottoms.MOST_WING, 1));
        this.thickness = new JSpinner(new SpinnerNumberModel(projection.thickness(), 1, 8, 1));

        this.risingSample = new Forms.Sample(
                () -> chosenRising == null ? projection.colours().get(0) : chosenRising,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());
        this.fallingSample = new Forms.Sample(
                () -> chosenFalling == null ? projection.colours().get(4) : chosenFalling,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(projection.nameKey()) + " [" + projection.period() + "]"));

        ties.setSelectedItem(projection.ties());
        measure.setSelectedItem(projection.measure());
        line.setSelectedItem(projection.line());

        ties.setRenderer(Forms.named("overlay.pivots.ties."));
        measure.setRenderer(Forms.named("overlay.proj.measure."));
        line.setRenderer(Forms.lineStyles());

        measure.addActionListener(e -> refreshMeasure());

        scale.addActionListener(e -> askForTheScale());
        risingColour.addActionListener(e -> pick(true));
        fallingColour.addActionListener(e -> pick(false));

        line.addActionListener(e -> repaintSamples());
        thickness.addChangeListener(e -> repaintSamples());

        paintButtons();
        refreshScale();
        refreshMeasure();

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
    public static ZigzagProjection edit(Window owner, ZigzagProjection projection) {
        ZigzagProjectionDialog dialog = new ZigzagProjectionDialog(owner, projection);

        dialog.setVisible(true);

        return dialog.accepted ? projection : null;
    }

    private JComponent parameters() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.proj.average"));
        Forms.field(panel, 1, Messages.get("overlay.ma.period"), period);
        Forms.field(panel, 2, Messages.get("overlay.ma.scale"), scale);

        Forms.group(panel, 3, Messages.get("overlay.channel.turns"));
        Forms.field(panel, 4, Messages.get("overlay.pivots.wing"), wing);
        Forms.field(panel, 5, Messages.get("overlay.pivots.ties.rule"), ties);

        Forms.group(panel, 6, Messages.get("overlay.proj.ladder"));
        Forms.field(panel, 7, Messages.get("overlay.proj.measure"), measure);

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), line);
        Forms.field(panel, 2, Messages.get("overlay.ma.thickness"), thickness);

        Forms.group(panel, 3, Messages.get("overlay.proj.rising"));
        Forms.field(panel, 4, Messages.get("overlay.ma.colour"), risingColour);
        Forms.across(panel, 5, risingSample);

        Forms.group(panel, 6, Messages.get("overlay.proj.falling"));
        Forms.field(panel, 7, Messages.get("overlay.ma.colour"), fallingColour);
        Forms.across(panel, 8, fallingSample);

        return panel;
    }

    /** Says what the chosen reading does, because the two names cannot. */
    private void refreshMeasure() {
        measure.setToolTipText(measure.getSelectedItem() == ZigzagProjection.Measure.END
                ? Messages.get("overlay.proj.measure.END.hint")
                : Messages.get("overlay.proj.measure.ORIGIN.hint"));
    }

    private void askForTheScale() {
        PeriodCatalog.Choice choice = PeriodDialog.ask(this, null);

        if (choice != null) {
            scaleCode = choice.code();

            refreshScale();
        }
    }

    private void refreshScale() {
        scale.setText(scaleCode == null
                ? Messages.get("overlay.proj.chartScale") : scaleCode);
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
        projection.setPeriod((Integer) period.getValue());
        projection.setScale(scaleCode);
        projection.setWing((Integer) wing.getValue());
        projection.setTies((TopsAndBottoms.Ties) ties.getSelectedItem());
        projection.setMeasure((ZigzagProjection.Measure) measure.getSelectedItem());
        projection.setLine((MovingAverage.Line) line.getSelectedItem());
        projection.setThickness((Integer) thickness.getValue());
        projection.setRisingColour(chosenRising);
        projection.setFallingColour(chosenFalling);
    }

    private void pick(boolean rising) {
        Color current = rising ? chosenRising : chosenFalling;
        Color fallback = projection.colours().get(rising ? 0 : 4);
        Color picked = JColorChooser.showDialog(this,
                Messages.get(rising ? "overlay.proj.rising" : "overlay.proj.falling"),
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
        paintButton(fallingColour, chosenFalling, projection.colours().get(4));
    }

    private static void paintButton(JButton button, Color chosen, Color fallback) {
        button.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        button.setIcon(new Forms.Swatch(chosen == null ? fallback : chosen));
        button.setHorizontalAlignment(SwingConstants.LEFT);
    }
}
