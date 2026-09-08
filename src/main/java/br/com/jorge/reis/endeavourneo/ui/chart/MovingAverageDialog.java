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
 * <p>Four tabs, and the split is not arbitrary: <b>Parameters</b> changes what
 * is computed, <b>Appearance</b> changes only how it is drawn, <b>Values</b>
 * says which price is read, and <b>Period</b> says on what scale. Somebody
 * adjusting a colour never has to look at a period, which is the whole reason
 * for tabs rather than one long form.</p>
 *
 * <p>The reference product's fourth tab also lets an indicator read another
 * <i>instrument</i>. That half is deliberately absent: this one always follows
 * the chart's instrument, and a control that only ever has one value costs a
 * glance and gives nothing back.</p>
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

    private final javax.swing.JCheckBox ownPeriod =
            new javax.swing.JCheckBox(Messages.get("overlay.ma.ownPeriod"));

    private final JButton periodButton = new JButton();

    private final javax.swing.JCheckBox interpolate =
            new javax.swing.JCheckBox(Messages.get("overlay.ma.interpolate"));

    private transient String periodCode;

    /**
     * The line as it will be drawn, from the three controls above it.
     *
     * <p>Forms.Sample and not a private copy. This file used to carry its own,
     * word for word, along with the form, the group heading, the field row, the
     * combo renderer, the swatch and the line-style renderer -- seven members
     * that Forms exists to keep from being written twice, and this dialog was
     * the one caller that did not import it. LinePen already records what that
     * costs: two dialogs of the same product showing ARITHMETIC where the other
     * showed "Aritmética".</p>
     */
    private final Forms.Sample sample = new Forms.Sample(
            this::inkNow, this::lineNow, this::widthNow);

    private transient Color chosen;

    private transient boolean accepted;

    private MovingAverageDialog(Window owner, MovingAverage average) {
        super(owner, ModalityType.APPLICATION_MODAL);

        this.average = average;
        this.chosen = average.chosenColour();
        this.periodCode = average.ownPeriod();

        this.period = new JSpinner(new SpinnerNumberModel(average.period(), 1, 2_000, 1));
        // From ZERO. It read -500, which looks like a symmetric range typed
        // rather than a decision made: a negative shift pulls the line left and
        // shows, on each bar, an average of bars to its right. See setShift.
        this.shift = new JSpinner(new SpinnerNumberModel(average.shift(), 0, 500, 1));
        this.thickness = new JSpinner(new SpinnerNumberModel(average.thickness(), 1, 8, 1));

        setTitle(Messages.get("overlay.dialog.title",
                Messages.get(average.nameKey()) + " [" + average.period() + "]"));

        kind.setSelectedItem(average.kind());
        source.setSelectedItem(average.source());
        line.setSelectedItem(average.line());

        kind.setRenderer(Forms.named("overlay.ma.kind."));
        source.setRenderer(Forms.named("overlay.ma.source."));
        line.setRenderer(Forms.lineStyles());

        colour.addActionListener(e -> pickColour());
        paintColourButton();

        for (JComponent each : new JComponent[]{line, thickness}) {
            addRefresh(each);
        }

        JTabbedPane tabs = new JTabbedPane();

        tabs.addTab(Messages.get("overlay.tab.parameters"), parameters());
        tabs.addTab(Messages.get("overlay.tab.appearance"), appearance());
        tabs.addTab(Messages.get("overlay.tab.values"), values());
        tabs.addTab(Messages.get("overlay.tab.period"), period());

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
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.average"));
        Forms.field(panel, 1, Messages.get("overlay.ma.period"), period);
        Forms.field(panel, 2, Messages.get("overlay.ma.kind"), kind);

        Forms.group(panel, 3, Messages.get("overlay.ma.shift"));
        Forms.field(panel, 4, Messages.get("overlay.ma.period"), shift);

        return panel;
    }

    private JComponent appearance() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.line"));
        Forms.field(panel, 1, Messages.get("overlay.ma.style"), line);
        Forms.field(panel, 2, Messages.get("overlay.ma.colour"), colour);
        Forms.field(panel, 3, Messages.get("overlay.ma.thickness"), thickness);

        Forms.group(panel, 4, Messages.get("overlay.ma.sample"));

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

    /**
     * The scale this average is computed on, when it is not the chart's.
     *
     * <p>The reference product also lets an indicator read another INSTRUMENT
     * here. That half is left out on purpose — this one always follows the
     * chart's instrument, and a control that only ever has one value is a
     * control that costs a glance and gives nothing.</p>
     *
     * <p>The period is chosen through the same window a digit opens on the
     * chart. Inventing a second way to name a period — a unit beside a count —
     * would mean two vocabularies for one idea in one application.</p>
     */
    private JComponent period() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.tab.period"));

        GridBagConstraints across = new GridBagConstraints();

        across.gridx = 0;
        across.gridy = 1;
        across.gridwidth = 2;
        across.anchor = GridBagConstraints.WEST;
        across.insets = new Insets(3, 8, 3, 0);

        panel.add(ownPeriod, across);

        // The text goes on BEFORE the field is laid out. It was set afterwards,
        // and field() freezes the height it measures: an empty button measures
        // almost nothing, so the control came out a flat sliver with its label
        // spilling out of it.
        ownPeriod.setSelected(periodCode != null);
        interpolate.setSelected(average.isInterpolated());
        refreshPeriod();

        Forms.field(panel, 2, Messages.get("overlay.ma.scale"), periodButton);

        Forms.group(panel, 3, Messages.get("overlay.ma.painting"));

        GridBagConstraints last = new GridBagConstraints();

        last.gridx = 0;
        last.gridy = 4;
        last.gridwidth = 2;
        last.anchor = GridBagConstraints.WEST;
        last.insets = new Insets(3, 8, 3, 0);

        panel.add(interpolate, last);

        ownPeriod.addActionListener(e -> refreshPeriod());

        periodButton.addActionListener(e -> {
            PeriodCatalog.Choice choice = PeriodDialog.ask(this, null);

            if (choice != null) {
                periodCode = choice.code();

                refreshPeriod();
            }
        });

        return panel;
    }

    private void refreshPeriod() {
        boolean own = ownPeriod.isSelected();

        periodButton.setEnabled(own);
        interpolate.setEnabled(own);
        periodButton.setText(periodCode == null
                ? Messages.get("overlay.ma.chooseScale") : periodCode);
    }

    private JComponent values() {
        JPanel panel = Forms.form();

        Forms.group(panel, 0, Messages.get("overlay.ma.values"));
        Forms.field(panel, 1, Messages.get("overlay.ma.source"), source);

        return panel;
    }

    /** @return the colour the sample should draw with right now */
    private Color inkNow() {
        return chosen == null ? average.colours().get(0) : chosen;
    }

    /** @return the dash pattern chosen right now */
    private MovingAverage.Line lineNow() {
        return (MovingAverage.Line) line.getSelectedItem();
    }

    /** @return the thickness chosen right now */
    private int widthNow() {
        return (Integer) thickness.getValue();
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
        average.setOwnPeriod(ownPeriod.isSelected() ? periodCode : null);
        average.setInterpolated(interpolate.isSelected());
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
        colour.setIcon(new Forms.Swatch(chosen == null ? average.colours().get(0) : chosen));
        colour.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
    }

    private void addRefresh(JComponent editor) {
        if (editor instanceof JComboBox<?> combo) {
            combo.addActionListener(e -> sample.repaint());
        } else if (editor instanceof JSpinner spinner) {
            spinner.addChangeListener(e -> sample.repaint());
        }
    }

    /** Kept out of the way of the layout: it is a spacer, not a control. */
    @SuppressWarnings("unused")
    private static Component glue() {
        return Box.createVerticalGlue();
    }
}
