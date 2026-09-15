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
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.LevelFade;
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
 * The level fade as a strategy, and the screen that sets it up.
 *
 * <p>Four numbers, and the first two carry the whole of what the network was
 * going to decide. The model this comes from estimates a utility for nine
 * target/stop pairs per opportunity and picks the best; without it, the pair is
 * a setting, and its default is the first of the nine — {@code T100_S150}.
 *
 * <p>Nothing else from the model is on this screen, deliberately. The distances,
 * the cooldown, the ceilings and the level thinning are the specification's
 * constants, and a constant that can be typed is a constant that gets typed
 * after the curve has been looked at.
 */
final class LevelFadeKind implements StrategyKind {

    private static final String TARGET = "strategy.levelfade.target";

    private static final String STOP = "strategy.levelfade.stop";

    private static final String SLIP = "strategy.levelfade.slip";

    private static final String LOT = "strategy.levelfade.lot";

    @Override
    public String label() {
        return Messages.get("backtest.strategy.levelfade");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new LevelFade(Timeframe.defaultZone(), target(), stop(), slip(), lot());
    }

    @Override
    public String toString() {
        return label();
    }

    // ------------------------------------------------------------- the values

    static int target() {
        return clamp(Settings.settings().getInt(TARGET, (int) LevelFade.TARGET), 5, 100_000);
    }

    static int stop() {
        return clamp(Settings.settings().getInt(STOP, (int) LevelFade.STOP), 5, 100_000);
    }

    static int slip() {
        return clamp(Settings.settings().getInt(SLIP, (int) LevelFade.SLIP), 0, 100_000);
    }

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, LevelFade.QTY), 1, 100);
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The level fade's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JSpinner target =
                new JSpinner(new SpinnerNumberModel((int) LevelFade.TARGET, 5, 100_000, 5));

        private final JSpinner stop =
                new JSpinner(new SpinnerNumberModel((int) LevelFade.STOP, 5, 100_000, 5));

        private final JSpinner slip =
                new JSpinner(new SpinnerNumberModel((int) LevelFade.SLIP, 0, 100_000, 5));

        private final JSpinner lot =
                new JSpinner(new SpinnerNumberModel(LevelFade.QTY, 1, 100, 1));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(row("strategy.levelfade.target", target));
            panel.add(row("strategy.levelfade.stop", stop));
            panel.add(hint("strategy.levelfade.pair.hint"));
            panel.add(row("strategy.levelfade.lot", lot));
            panel.add(row("strategy.levelfade.slip", slip));
            panel.add(hint("strategy.levelfade.slip.hint"));
            panel.add(hint("strategy.levelfade.rule.hint"));
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
            return Messages.get("backtest.strategy.levelfade");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            target.setValue(target());
            stop.setValue(stop());
            slip.setValue(slip());
            lot.setValue(lot());
        }

        @Override
        public void apply() {
            int wantedTarget = (Integer) target.getValue();
            int wantedStop = (Integer) stop.getValue();
            int wantedSlip = (Integer) slip.getValue();
            int wantedLot = (Integer) lot.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.putInt(TARGET, wantedTarget);
                settings.putInt(STOP, wantedStop);
                settings.putInt(SLIP, wantedSlip);
                settings.putInt(LOT, wantedLot);
            });
        }
    }
}
