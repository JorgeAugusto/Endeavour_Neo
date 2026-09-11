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
import br.com.jorge.reis.endeavourneo.ui.backtest.BacktestPreferences;

import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * What every run is charged, and how big it trades.
 *
 * <p>Both used to sit on the command bar beside the Run button, which put the
 * cost one stray scroll away from being wrong. They belong here: the bar says
 * what to run, and these say how the market treats it.</p>
 */
public final class BacktestPage implements SettingsPage {

    private final JPanel panel = new JPanel();

    private final JSpinner cost = new JSpinner(new SpinnerNumberModel(
            BacktestPreferences.DEFAULT_COST_TENTHS / 10.0,
            0.0, BacktestPreferences.MOST_COST_TENTHS / 10.0, 0.5));

    private final JSpinner contracts = new JSpinner(new SpinnerNumberModel(
            BacktestPreferences.DEFAULT_CONTRACTS, 1, BacktestPreferences.MOST_CONTRACTS, 1));

    private final JSpinner point = new JSpinner(new SpinnerNumberModel(
            BacktestPreferences.DEFAULT_POINT_CENTS / 100.0,
            0.01, BacktestPreferences.MOST_POINT_CENTS / 100.0, 0.01));

    public BacktestPage() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        panel.add(row("settings.backtest.cost", cost, "settings.backtest.points"));
        panel.add(hint("settings.backtest.cost.hint"));
        panel.add(row("settings.backtest.contracts", contracts, null));
        panel.add(hint("settings.backtest.contracts.hint"));
        panel.add(row("settings.backtest.point", point, "settings.backtest.perContract"));
        panel.add(hint("settings.backtest.point.hint"));
        panel.add(Box.createVerticalGlue());
    }

    private static JPanel row(String key, JSpinner spinner, String unitKey) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel(Messages.get(key)));
        row.add(spinner);

        if (unitKey != null) {
            row.add(new JLabel(Messages.get(unitKey)));
        }

        return row;
    }

    private static JLabel hint(String key) {
        JLabel hint = new JLabel(Messages.get(key));

        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 0));
        hint.setEnabled(false);

        return hint;
    }

    @Override
    public String getTitle() {
        return Messages.get("settings.backtest");
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void load() {
        cost.setValue(BacktestPreferences.cost());
        contracts.setValue(BacktestPreferences.contracts());
        point.setValue(BacktestPreferences.pointValue());
    }

    @Override
    public void apply() {
        // Rounded rather than truncated: the spinner steps by half a point, and
        // (int) of 6.499999 is 64 tenths -- a cost that drifts down every time
        // the dialog is opened and closed.
        BacktestPreferences.setCostTenths(
                (int) Math.round(((Number) cost.getValue()).doubleValue() * 10));
        BacktestPreferences.setContracts((Integer) contracts.getValue());
        BacktestPreferences.setPointCents(
                (int) Math.round(((Number) point.getValue()).doubleValue() * 100));
    }
}
