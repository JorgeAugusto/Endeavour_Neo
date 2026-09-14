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

import br.com.jorge.reis.endeavourneo.domain.indicator.Rsi;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.RsiSnapback;
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
 * The IFR2 as a strategy, and the screen that sets it up.
 *
 * <p>Five numbers, and all five are on the screen because the model itself says
 * they are: <i>"Tudo pode ser alterado IFR(RSI), Stop high n períodos, sma"</i>.
 * Everything the published description names as a parameter is one here, and
 * nothing that it does not name was invented into one.
 *
 * <p>Which is why <b>there is no stop field</b>. The model has no loss stop, and
 * a screen with an empty stop box would read like an oversight the user is meant
 * to fill in. The hint says it in words instead, because someone is going to run
 * this on the mini-index and needs to know before the first drawdown, not after.
 *
 * <p>The scale is not here either: this trades the chart it is run on. The model
 * is a daily setup, so a run of it on five minutes is measuring something else —
 * which is allowed, and is the user's business, not the screen's.
 */
final class RsiSnapbackKind implements StrategyKind {

    private static final String PERIOD = "strategy.ifr2.period";

    private static final String OVERSOLD = "strategy.ifr2.oversold";

    private static final String TREND = "strategy.ifr2.trend";

    private static final String EXIT = "strategy.ifr2.exit";

    private static final String LOT = "strategy.ifr2.lot";

    @Override
    public String label() {
        return Messages.get("backtest.strategy.ifr2");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new RsiSnapback(period(), oversold(), trend(), exit(), lot());
    }

    @Override
    public String toString() {
        return label();
    }

    // ------------------------------------------------------------- the values

    static int period() {
        return clamp(Settings.settings().getInt(PERIOD, Rsi.SHORT), 1, 1_000);
    }

    static int oversold() {
        return clamp(Settings.settings().getInt(OVERSOLD, (int) RsiSnapback.OVERSOLD), 1, 99);
    }

    static int trend() {
        return clamp(Settings.settings().getInt(TREND, RsiSnapback.TREND), 1, 5_000);
    }

    static int exit() {
        return clamp(Settings.settings().getInt(EXIT, RsiSnapback.EXIT_BARS), 1, 1_000);
    }

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, 1), 1, 100);
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The IFR2's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JSpinner period =
                new JSpinner(new SpinnerNumberModel(Rsi.SHORT, 1, 1_000, 1));

        private final JSpinner oversold =
                new JSpinner(new SpinnerNumberModel((int) RsiSnapback.OVERSOLD, 1, 99, 1));

        private final JSpinner trend =
                new JSpinner(new SpinnerNumberModel(RsiSnapback.TREND, 1, 5_000, 1));

        private final JSpinner exit =
                new JSpinner(new SpinnerNumberModel(RsiSnapback.EXIT_BARS, 1, 1_000, 1));

        private final JSpinner lot =
                new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(row("strategy.ifr2.period", period));
            panel.add(row("strategy.ifr2.oversold", oversold));
            panel.add(hint("strategy.ifr2.entry.hint"));
            panel.add(row("strategy.ifr2.trend", trend));
            panel.add(hint("strategy.ifr2.trend.hint"));
            panel.add(row("strategy.ifr2.exit", exit));
            panel.add(hint("strategy.ifr2.exit.hint"));
            panel.add(row("strategy.ifr2.lot", lot));
            panel.add(hint("strategy.ifr2.stop.hint"));
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
            return Messages.get("backtest.strategy.ifr2");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            period.setValue(period());
            oversold.setValue(oversold());
            trend.setValue(trend());
            exit.setValue(exit());
            lot.setValue(lot());
        }

        @Override
        public void apply() {
            int wantedPeriod = (Integer) period.getValue();
            int wantedOversold = (Integer) oversold.getValue();
            int wantedTrend = (Integer) trend.getValue();
            int wantedExit = (Integer) exit.getValue();
            int wantedLot = (Integer) lot.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.putInt(PERIOD, wantedPeriod);
                settings.putInt(OVERSOLD, wantedOversold);
                settings.putInt(TREND, wantedTrend);
                settings.putInt(EXIT, wantedExit);
                settings.putInt(LOT, wantedLot);
            });
        }
    }
}
