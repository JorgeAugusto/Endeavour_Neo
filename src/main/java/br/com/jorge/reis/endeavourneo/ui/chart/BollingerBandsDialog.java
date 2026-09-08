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

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.BollingerBands;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;

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
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;

/**
 * The settings for {@link BollingerBands}, laid out as the reference product
 * lays them out.
 *
 * <p>Five tabs, and the last one is deliberately half of what the reference
 * shows: it offers the SCALE and not the instrument. An indicator on this chart
 * always reads this chart's instrument — the other half of that dialog exists
 * to compare two symbols, which is not something this program does, and a
 * control that cannot be honoured is worse than one that is absent.</p>
 */
public final class BollingerBandsDialog extends JDialog {

    private static final long serialVersionUID = 1L;

    private final transient BollingerBands bands;

    private final JSpinner upper;

    private final JSpinner lower;

    private final JSpinner period;

    private final JComboBox<MovingAverage.Kind> kind =
            new JComboBox<>(MovingAverage.Kind.values());

    private final JCheckBox showMiddle = new JCheckBox(Messages.get("overlay.bb.showMiddle"));

    private final JComboBox<MovingAverage.Line> line =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner thickness;

    private final JButton colour = new JButton();

    private final JCheckBox fill = new JCheckBox(Messages.get("overlay.bb.fill"));

    private final JButton fillColour = new JButton();

    private final JSlider opacity;

    private final JComboBox<MovingAverage.Line> middleLine =
            new JComboBox<>(MovingAverage.Line.values());

    private final JSpinner middleThickness;

    private final JButton middleColour = new JButton();

    private final JComboBox<MovingAverage.Source> source =
            new JComboBox<>(MovingAverage.Source.values());

    private final JCheckBox ownPeriod = new JCheckBox(Messages.get("overlay.ma.ownPeriod"));

    private final JButton periodButton = new JButton();

    private final JCheckBox interpolate = new JCheckBox(Messages.get("overlay.ma.interpolate"));

    private transient String periodCode;

    private transient Color chosen;

    private transient Color chosenFill;

    private transient Color chosenMiddle;

    private final transient Forms.Sample sample;

    private final transient Forms.Sample middleSample;

    private transient boolean accepted;

    private BollingerBandsDialog(Window owner, BollingerBands bands) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.bands = bands;
        this.chosen = bands.chosenColour();
        this.chosenFill = bands.chosenFillColour();
        this.chosenMiddle = bands.chosenMiddleColour();
        this.periodCode = bands.ownPeriod();

        this.upper = new JSpinner(
                new SpinnerNumberModel(bands.upperDeviations(), 0.0, 10.0, 0.1));
        this.lower = new JSpinner(
                new SpinnerNumberModel(bands.lowerDeviations(), 0.0, 10.0, 0.1));
        this.period = new JSpinner(new SpinnerNumberModel(bands.period(), 1, 2_000, 1));
        this.thickness = new JSpinner(new SpinnerNumberModel(bands.thickness(), 1, 8, 1));
        this.middleThickness =
                new JSpinner(new SpinnerNumberModel(bands.middleThickness(), 1, 8, 1));
        this.opacity = new JSlider(0, 100, bands.opacity());

        this.sample = new Forms.Sample(
                () -> chosen == null ? bands.colours().get(0) : chosen,
                () -> (MovingAverage.Line) line.getSelectedItem(),
                () -> (Integer) thickness.getValue());
        this.middleSample = new Forms.Sample(
                () -> chosenMiddle == null ? bands.colours().get(1) : chosenMiddle,
                () -> (MovingAverage.Line) middleLine.getSelectedItem(),
                () -> (Integer) middleThickness.getValue());

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(bands.nameKey()) + " [" + bands.period() + "]"));

        kind.setSelectedItem(bands.kind());
        source.setSelectedItem(bands.source());
        line.setSelectedItem(bands.line());
        middleLine.setSelectedItem(bands.middleLine());
        showMiddle.setSelected(bands.isMiddleShown());
        fill.setSelected(bands.isFilled());

        kind.setRenderer(Forms.named("overlay.ma.kind."));
        source.setRenderer(Forms.named("overlay.ma.source."));
        line.setRenderer(Forms.lineStyles());
        middleLine.setRenderer(Forms.lineStyles());

        colour.addActionListener(e -> pick(true, false));
        middleColour.addActionListener(e -> pick(false, false));
        fillColour.addActionListener(e -> pick(false, true));

        line.addActionListener(e -> sample.repaint());
        thickness.addChangeListener(e -> sample.repaint());
        middleLine.addActionListener(e -> middleSample.repaint());
        middleThickness.addChangeListener(e -> middleSample.repaint());
        fill.addActionListener(e -> refreshFill());

        paintButtons();

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());
        tabs.addTab(Messages.get("overlay.tab.values"), values());
        tabs.addTab(Messages.get("overlay.bb.tab.middleAppearance"), middleAppearance());
        tabs.addTab(Messages.get("overlay.tab.period"), period());

        add(tabs, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);

        pack();
        setMinimumSize(new Dimension(Math.max(520, getWidth()), getHeight()));
        setLocationRelativeTo(owner);
    }

    /**
     * @return the indicator with the changes applied, or null if it was cancelled
     *
     * <p>The SAME object, changed in place, for the same reason the average's
     * dialog does it: the chart holds the one it was given.</p>
     */
    public static BollingerBands edit(Window owner, BollingerBands bands) {
        BollingerBandsDialog dialog = new BollingerBandsDialog(owner, bands);

        dialog.setVisible(true);

        return dialog.accepted ? bands : null;
    }

    // ------------------------------------------------------------- the tabs

    private JComponent parameters() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.tab.parameters"));
        Forms.field(panel, 1, Messages.get("overlay.bb.upper"), upper);
        Forms.field(panel, 2, Messages.get("overlay.bb.lower"), lower);

        Forms.group(panel, 3, Messages.get("overlay.bb.average"));
        Forms.field(panel, 4, Messages.get("overlay.ma.period"), period);
        Forms.field(panel, 5, Messages.get("overlay.ma.kind"), kind);
        Forms.across(panel, 6, showMiddle);

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), line);
        Forms.field(panel, 2, Messages.get("overlay.ma.colour"), colour);
        Forms.field(panel, 3, Messages.get("overlay.ma.thickness"), thickness);

        Forms.group(panel, 4, Messages.get("overlay.bb.fillGroup"));
        Forms.across(panel, 5, fill);
        Forms.field(panel, 6, Messages.get("overlay.ma.colour"), fillColour);

        // Labelled, and with the numbers on it: "how transparent" is a question
        // a bare slider cannot answer, and the reader setting a shade behind
        // candles wants to come back to the same value later.
        opacity.setMajorTickSpacing(25);
        opacity.setPaintTicks(true);
        opacity.setPaintLabels(true);
        Forms.field(panel, 7, Messages.get("overlay.bb.opacity"), opacity);

        Forms.group(panel, 8, Messages.get("overlay.ma.sample"));
        Forms.across(panel, 9, sample);

        refreshFill();

        return panel;
    }

    private JComponent middleAppearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), middleLine);
        Forms.field(panel, 2, Messages.get("overlay.ma.colour"), middleColour);
        Forms.field(panel, 3, Messages.get("overlay.ma.thickness"), middleThickness);

        Forms.group(panel, 4, Messages.get("overlay.ma.sample"));
        Forms.across(panel, 5, middleSample);

        return panel;
    }

    private JComponent values() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.values"));
        Forms.field(panel, 1, Messages.get("overlay.ma.source"), source);

        return panel;
    }

    private JComponent period() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.tab.period"));
        Forms.across(panel, 1, ownPeriod);

        // Set before the field is laid out: field() freezes the height it
        // measures, and an empty button measures almost nothing.
        ownPeriod.setSelected(periodCode != null);
        interpolate.setSelected(bands.isInterpolated());
        refreshPeriod();

        Forms.field(panel, 2, Messages.get("overlay.ma.scale"), periodButton);

        Forms.group(panel, 3, Messages.get("overlay.ma.painting"));
        Forms.across(panel, 4, interpolate);

        // TICKING IT ASKS. Ticked with no scale chosen, `periodCode` stayed null
        // and apply() wrote null -- so the indicator went back to following the
        // chart with the box ticked, which is the state saying it does not.
        // refreshPeriod only enabled the button; nothing required it to be used
        // and OK accepted the result.
        //
        // The chooser opens instead of the OK refusing: refusing at the end
        // means telling somebody they did the wrong thing after they finished,
        // and there is exactly one thing they can do about it.
        ownPeriod.addActionListener(e -> {
            if (ownPeriod.isSelected() && periodCode == null) {
                askForTheScale();

                if (periodCode == null) {
                    // They cancelled. The box goes back rather than standing
                    // ticked over a scale that was never picked.
                    ownPeriod.setSelected(false);
                }
            }

            refreshPeriod();
        });

        periodButton.addActionListener(e -> askForTheScale());

        return panel;
    }

    // ------------------------------------------------------------ the pieces

    /** Opens the scale chooser, and keeps what it answers. */
    private void askForTheScale() {
        PeriodCatalog.Choice choice = PeriodDialog.ask(this, null);

        if (choice != null) {
            periodCode = choice.code();

            refreshPeriod();
        }
    }

    private void refreshPeriod() {
        boolean own = ownPeriod.isSelected();

        periodButton.setEnabled(own);
        interpolate.setEnabled(own);
        periodButton.setText(periodCode == null
                ? Messages.get("overlay.ma.chooseScale") : periodCode);
    }

    /** The fill's colour and its transparency mean nothing while it is off. */
    private void refreshFill() {
        boolean on = fill.isSelected();

        fillColour.setEnabled(on);
        opacity.setEnabled(on);
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
        bands.setUpperDeviations(((Number) upper.getValue()).doubleValue());
        bands.setLowerDeviations(((Number) lower.getValue()).doubleValue());
        bands.setPeriod((Integer) period.getValue());
        bands.setKind((MovingAverage.Kind) kind.getSelectedItem());
        bands.setSource((MovingAverage.Source) source.getSelectedItem());
        bands.setMiddleShown(showMiddle.isSelected());
        bands.setLine((MovingAverage.Line) line.getSelectedItem());
        bands.setThickness((Integer) thickness.getValue());
        bands.setColour(chosen);
        bands.setMiddleLine((MovingAverage.Line) middleLine.getSelectedItem());
        bands.setMiddleThickness((Integer) middleThickness.getValue());
        bands.setMiddleColour(chosenMiddle);
        bands.setFilled(fill.isSelected());
        bands.setFillColour(chosenFill);
        bands.setOpacity(opacity.getValue());
        bands.setOwnPeriod(ownPeriod.isSelected() ? periodCode : null);
        bands.setInterpolated(interpolate.isSelected());
    }

    private void pick(boolean band, boolean shading) {
        Color current = shading ? chosenFill : band ? chosen : chosenMiddle;
        Color fallback = bands.colours().get(band || shading ? 0 : 1);
        Color picked = JColorChooser.showDialog(this, Messages.get("overlay.ma.colour"),
                current == null ? fallback : current);

        if (picked == null) {
            return;
        }

        if (shading) {
            chosenFill = picked;
        } else if (band) {
            chosen = picked;
        } else {
            chosenMiddle = picked;
        }

        paintButtons();
        sample.repaint();
        middleSample.repaint();
    }

    private void paintButtons() {
        paintButton(colour, chosen, bands.colours().get(0));
        paintButton(middleColour, chosenMiddle, bands.colours().get(1));
        paintButton(fillColour, chosenFill, chosen == null ? bands.colours().get(0) : chosen);
    }

    private static void paintButton(JButton button, Color chosen, Color fallback) {
        button.setText(chosen == null
                ? Messages.get("overlay.ma.automatic")
                : Messages.get("overlay.ma.chosen"));
        button.setIcon(new Forms.Swatch(chosen == null ? fallback : chosen));
        button.setHorizontalAlignment(SwingConstants.LEFT);
    }
}
