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
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.RangeBreakout;
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
 * The Range 90, and the screen that sets it up.
 *
 * <p>Four numbers, and deliberately not more. The target is <b>not</b> here: it
 * is 1,5R or 2R and which of the two is chosen by the walk-forward selector,
 * every morning, out of the sessions before it. Putting it on this screen would
 * offer the reader a decision the strategy takes away from him — and would let
 * him pick the target after seeing the result, which is the whole thing the
 * selector exists to prevent.</p>
 *
 * <p>The contracts of the {@code Backtest} tab of the settings do not reach this
 * strategy either. This one sizes itself: four to start and four per pullback up
 * to twenty, and "four" is a number of its own, not the window's default lot.</p>
 */
final class RangeBreakoutKind implements StrategyKind {

    private static final String LOT = "strategy.range90.lot";

    private static final String CAP = "strategy.range90.cap";

    private static final String WINDOW = "strategy.range90.window";

    private static final String FORMATION = "strategy.range90.formation";

    @Override
    public String label() {
        return Messages.get("backtest.strategy.range90");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new RangeBreakout(Timeframe.defaultZone(), lot(), cap(), window(), formation());
    }

    @Override
    public String toString() {
        return label();
    }

    // ------------------------------------------------------------- the values

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, RangeBreakout.LOT), 1, 100);
    }

    static int cap() {
        // NEVER BELOW THE LOT, here as well as in the strategy. A ceiling under
        // the first lot means no day is ever entered, and a screen that can be
        // left in that state is a screen that can silently turn the run off.
        return clamp(Settings.settings().getInt(CAP, RangeBreakout.CAP), lot(), 1_000);
    }

    static int window() {
        return clamp(Settings.settings().getInt(WINDOW, RangeBreakout.ENTRY_WINDOW), 1, 600);
    }

    static int formation() {
        return clamp(Settings.settings().getInt(FORMATION, RangeBreakout.FORMATION), 1, 600);
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The Range 90's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JSpinner formation =
                new JSpinner(new SpinnerNumberModel(RangeBreakout.FORMATION, 1, 600, 5));

        private final JSpinner window =
                new JSpinner(new SpinnerNumberModel(RangeBreakout.ENTRY_WINDOW, 1, 600, 5));

        private final JSpinner lot =
                new JSpinner(new SpinnerNumberModel(RangeBreakout.LOT, 1, 100, 1));

        private final JSpinner cap =
                new JSpinner(new SpinnerNumberModel(RangeBreakout.CAP, 1, 1_000, 1));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(row("strategy.range90.formation", formation));
            panel.add(hint("strategy.range90.formation.hint"));
            panel.add(row("strategy.range90.window", window));
            panel.add(hint("strategy.range90.window.hint"));
            panel.add(row("strategy.range90.lot", lot));
            panel.add(row("strategy.range90.cap", cap));
            panel.add(hint("strategy.range90.lot.hint"));
            panel.add(hint("strategy.range90.target.hint"));
            panel.add(Box.createVerticalGlue());
        }

        private static JPanel row(String key, JComponent control) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            row.add(new JLabel(Messages.get(key)));
            row.add(control);

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
            return Messages.get("backtest.strategy.range90");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            formation.setValue(formation());
            window.setValue(window());
            lot.setValue(lot());
            cap.setValue(cap());
        }

        @Override
        public void apply() {
            int wantedLot = (Integer) lot.getValue();
            int wantedCap = (Integer) cap.getValue();
            int wantedWindow = (Integer) window.getValue();
            int wantedFormation = (Integer) formation.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.putInt(LOT, wantedLot);

                // KEPT AT OR ABOVE THE LOT on the way in as well as on the way
                // out. Saving a ceiling below the lot and correcting it only
                // when reading would show a screen that says one thing and a run
                // that does another.
                settings.putInt(CAP, Math.max(wantedCap, wantedLot));
                settings.putInt(WINDOW, wantedWindow);
                settings.putInt(FORMATION, wantedFormation);
            });
        }
    }
}
