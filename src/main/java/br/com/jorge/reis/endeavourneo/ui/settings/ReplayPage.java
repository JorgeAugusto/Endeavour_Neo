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
import br.com.jorge.reis.endeavourneo.ui.replay.ReplayPreferences;

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
 * How much of the past the replay puts on the chart before the session starts.
 */
public final class ReplayPage implements SettingsPage {

    private final JPanel panel = new JPanel();

    private final JSpinner days = new JSpinner(new SpinnerNumberModel(
            ReplayPreferences.DEFAULT_HISTORY, 0, ReplayPreferences.MAX_HISTORY, 5));

    private final JSpinner window = new JSpinner(new SpinnerNumberModel(
            ReplayPreferences.DEFAULT_WINDOW, 1, ReplayPreferences.MAX_WINDOW, 1));

    public ReplayPage() {
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.add(new JLabel(Messages.get("settings.replay.history")));
        row.add(days);
        row.add(new JLabel(Messages.get("settings.replay.days")));

        JLabel hint = new JLabel(Messages.get("settings.replay.hint"));

        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 0));
        hint.setEnabled(false);

        JPanel windowRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        windowRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        windowRow.add(new JLabel(Messages.get("settings.replay.window")));
        windowRow.add(window);
        windowRow.add(new JLabel(Messages.get("settings.replay.windowDays")));

        JLabel windowHint = new JLabel(Messages.get("settings.replay.window.hint"));

        windowHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        windowHint.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 0));
        windowHint.setEnabled(false);

        panel.add(row);
        panel.add(hint);
        panel.add(windowRow);
        panel.add(windowHint);
        panel.add(Box.createVerticalGlue());
    }

    @Override
    public String getTitle() {
        return Messages.get("settings.replay");
    }

    @Override
    public JComponent getComponent() {
        return panel;
    }

    @Override
    public void load() {
        days.setValue(ReplayPreferences.historyDays());
        window.setValue(ReplayPreferences.windowDays());
    }

    @Override
    public void apply() {
        ReplayPreferences.setHistoryDays((Integer) days.getValue());
        ReplayPreferences.setWindowDays((Integer) window.getValue());
    }
}
