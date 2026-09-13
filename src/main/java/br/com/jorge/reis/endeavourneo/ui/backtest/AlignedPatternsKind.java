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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.AlignedPatterns;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsPage;

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
 * The two-range setup, and the screen that sets it up.
 *
 * <p>Two numbers, and deliberately not more. Everything that DEFINES this
 * strategy came from him whole — the two ranges, the two latches on two and
 * five minutes, the four triggers, the stop on the pattern and the two R — and
 * a screen that let each of those be typed would be a screen for building a
 * different strategy every afternoon.
 */
final class AlignedPatternsKind implements StrategyKind {

    private static final String LOT = "strategy.aligned.lot";

    private static final String SLIP = "strategy.aligned.slip";

    @Override
    public String label() {
        return Messages.get("backtest.strategy.aligned");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new AlignedPatterns(Timeframe.defaultZone(), lot(),
                AlignedPatterns.REWARD, slip());
    }

    @Override
    public String toString() {
        return label();
    }

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, 1), 1, 100);
    }

    static double slip() {
        return clamp(Settings.settings().getInt(SLIP, (int) AlignedPatterns.SLIP), 0, 100_000);
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The two-range setup's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JSpinner lot = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));

        private final JSpinner slip = new JSpinner(
                new SpinnerNumberModel((int) AlignedPatterns.SLIP, 0, 100_000, 5));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(row("strategy.aligned.lot", lot));
            panel.add(row("strategy.aligned.slip", slip));
            panel.add(hint("strategy.aligned.slip.hint"));
            panel.add(hint("strategy.aligned.rule.hint"));
            panel.add(hint("strategy.aligned.warning"));
            panel.add(Box.createVerticalGlue());
        }

        private static JPanel row(String key, JComponent control) {
            JPanel made = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

            made.setAlignmentX(Component.LEFT_ALIGNMENT);
            made.add(new JLabel(Messages.get(key)));
            made.add(control);

            return made;
        }

        private static JLabel hint(String key) {
            JLabel made = new JLabel(Messages.get(key));

            made.setAlignmentX(Component.LEFT_ALIGNMENT);
            made.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 0));
            made.setEnabled(false);

            return made;
        }

        @Override
        public String getTitle() {
            return Messages.get("backtest.strategy.aligned");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            lot.setValue(lot());
            slip.setValue((int) slip());
        }

        @Override
        public void apply() {
            int wantedLot = (Integer) lot.getValue();
            int wantedSlip = (Integer) slip.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.putInt(LOT, wantedLot);
                settings.putInt(SLIP, wantedSlip);
            });
        }
    }
}
