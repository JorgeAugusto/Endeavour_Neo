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
    }

    @Override
    public void apply() {
        ChartPreferences.setPeriodLine(periodLine.isSelected());
        ChartPreferences.setHorizontalGrid(horizontalGrid.isSelected());
        ChartPreferences.setVerticalGrid(verticalGrid.isSelected());
        ChartPreferences.setHollowCandles(hollow.isSelected());
    }
}
