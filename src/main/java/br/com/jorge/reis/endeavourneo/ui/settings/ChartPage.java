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
package br.com.jorge.reis.endeavourneo.ui.settings;

import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences;

import java.awt.Component;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * What every chart draws.
 *
 * <p>Its own page rather than a corner of <i>General</i>: the chart is where
 * almost all of this application's surface is, and its settings will not stay
 * at one. General is for what belongs to no single part.</p>
 */
public final class ChartPage implements SettingsPage {

    private final JPanel panel = new JPanel();

    private final JCheckBox periodLine = new JCheckBox(Messages.get("settings.chart.periodLine"));

    private final JCheckBox horizontalGrid =
            new JCheckBox(Messages.get("settings.chart.horizontalGrid"));

    private final JCheckBox verticalGrid =
            new JCheckBox(Messages.get("settings.chart.verticalGrid"));

    private final JCheckBox hollow = new JCheckBox(Messages.get("settings.chart.hollow"));

    private final JCheckBox interpolate =
            new JCheckBox(Messages.get("settings.chart.interpolate"));

    /**
     * How many of the most recent bars a chart loads.
     *
     * <p>A spinner and not a list of tiers: the reference product's steps are
     * its licences, not a judgement about what is useful, and the reader here
     * has one licence and a machine of their own.</p>
     */
    private final javax.swing.JSpinner window = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(ChartPreferences.WINDOW_DEFAULT,
                    ChartPreferences.LEAST_WINDOW, ChartPreferences.MOST_WINDOW, 10_000));

    /**
     * How much of the width stays empty past the newest candle.
     *
     * <p>Per cent and not bars, because that is how it reads at any zoom: a
     * quarter of the screen is a quarter of the screen whether ninety bars fit
     * or nine hundred.</p>
     */
    private final javax.swing.JSpinner margin = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(ChartPreferences.MARGIN_DEFAULT,
                    ChartPreferences.LEAST_MARGIN, ChartPreferences.MOST_MARGIN, 5));

    public ChartPage() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        periodLine.setAlignmentX(Component.LEFT_ALIGNMENT);
        periodLine.setMnemonic(Messages.mnemonic("settings.chart.periodLine"));

        JLabel hint = new JLabel(Messages.get("settings.chart.periodLine.hint"));

        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        hint.setEnabled(false);

        panel.add(periodLine);
        panel.add(hint);

        for (JCheckBox grid : new JCheckBox[]{horizontalGrid, verticalGrid}) {
            grid.setAlignmentX(Component.LEFT_ALIGNMENT);
            panel.add(grid);
        }

        horizontalGrid.setMnemonic(Messages.mnemonic("settings.chart.horizontalGrid"));
        verticalGrid.setMnemonic(Messages.mnemonic("settings.chart.verticalGrid"));

        JLabel gridHint = new JLabel(Messages.get("settings.chart.grid.hint"));

        gridHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        gridHint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        gridHint.setEnabled(false);

        panel.add(gridHint);

        hollow.setAlignmentX(Component.LEFT_ALIGNMENT);
        hollow.setMnemonic(Messages.mnemonic("settings.chart.hollow"));

        JLabel hollowHint = new JLabel(Messages.get("settings.chart.hollow.hint"));

        hollowHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hollowHint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        hollowHint.setEnabled(false);

        panel.add(hollow);
        panel.add(hollowHint);

        interpolate.setAlignmentX(Component.LEFT_ALIGNMENT);
        interpolate.setMnemonic(Messages.mnemonic("settings.chart.interpolate"));

        JLabel interpolateHint = new JLabel(Messages.get("settings.chart.interpolate.hint"));

        interpolateHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        interpolateHint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        interpolateHint.setEnabled(false);

        panel.add(interpolate);
        panel.add(interpolateHint);

        JPanel row = new JPanel();

        row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(Messages.get("settings.chart.window"));

        label.setLabelFor(window);
        label.setDisplayedMnemonic(Messages.mnemonic("settings.chart.window"));

        window.setMaximumSize(window.getPreferredSize());

        row.add(label);
        row.add(Box.createHorizontalStrut(8));
        row.add(window);
        row.add(Box.createHorizontalGlue());

        JLabel windowHint = new JLabel(Messages.get("settings.chart.window.hint"));

        windowHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        windowHint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        windowHint.setEnabled(false);

        panel.add(Box.createVerticalStrut(8));
        panel.add(row);
        panel.add(windowHint);

        JPanel marginRow = new JPanel();

        marginRow.setLayout(new BoxLayout(marginRow, BoxLayout.X_AXIS));
        marginRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel marginLabel = new JLabel(Messages.get("settings.chart.margin"));

        marginLabel.setLabelFor(margin);
        marginLabel.setDisplayedMnemonic(Messages.mnemonic("settings.chart.margin"));

        margin.setMaximumSize(margin.getPreferredSize());

        marginRow.add(marginLabel);
        marginRow.add(Box.createHorizontalStrut(8));
        marginRow.add(margin);
        marginRow.add(Box.createHorizontalGlue());

        JLabel marginHint = new JLabel(Messages.get("settings.chart.margin.hint"));

        marginHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        marginHint.setBorder(BorderFactory.createEmptyBorder(0, 24, 8, 0));
        marginHint.setEnabled(false);

        panel.add(Box.createVerticalStrut(8));
        panel.add(marginRow);
        panel.add(marginHint);
        panel.add(Box.createVerticalGlue());
    }

    @Override
    public String getTitle() {
        return Messages.get("settings.chart");
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void load() {
        // Read on every opening rather than once at construction: the dialog has
        // to show what is true now, not what was true when the window was built.
        periodLine.setSelected(ChartPreferences.periodLine());
        horizontalGrid.setSelected(ChartPreferences.horizontalGrid());
        verticalGrid.setSelected(ChartPreferences.verticalGrid());
        hollow.setSelected(ChartPreferences.hollowCandles());
        interpolate.setSelected(ChartPreferences.interpolateOwnScale());
        window.setValue(ChartPreferences.window());
        margin.setValue(ChartPreferences.rightMargin());
    }

    @Override
    public void apply() {
        ChartPreferences.setPeriodLine(periodLine.isSelected());
        ChartPreferences.setHorizontalGrid(horizontalGrid.isSelected());
        ChartPreferences.setVerticalGrid(verticalGrid.isSelected());
        ChartPreferences.setHollowCandles(hollow.isSelected());
        ChartPreferences.setInterpolateOwnScale(interpolate.isSelected());
        ChartPreferences.setWindow(((Number) window.getValue()).intValue());
        ChartPreferences.setRightMargin(((Number) margin.getValue()).intValue());
    }
}
