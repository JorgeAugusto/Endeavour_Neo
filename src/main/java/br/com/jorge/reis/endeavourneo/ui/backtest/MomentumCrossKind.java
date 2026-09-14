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

import br.com.jorge.reis.endeavourneo.domain.indicator.Pmo;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.MomentumCross;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.RangeGate.Mode;
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
 * The TNO's crossing as a strategy, and the screen that sets it up.
 *
 * <p>Six numbers: the PMO's four periods, the lot, and how far the stop may
 * fill past its trigger. The target is NOT one of them — it is two R, which is
 * what was asked for, and a reward that can be typed is a reward that gets
 * typed after the curve has been looked at.
 *
 * <p>The scale is not here either, and its absence is the point: this strategy
 * trades the chart it is run on. Put the run on five minutes and it is the
 * five-minute crossing.
 */
final class MomentumCrossKind implements StrategyKind {

    private static final String CHANGE = "strategy.tno.change";

    private static final String FIRST = "strategy.tno.first";

    private static final String SECOND = "strategy.tno.second";

    private static final String SIGNAL = "strategy.tno.signal";

    private static final String LOT = "strategy.tno.lot";

    private static final String SLIP = "strategy.tno.slip";

    private static final String GATE = "strategy.tno.gate";

    @Override
    public String label() {
        return Messages.get("backtest.strategy.tno");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new MomentumCross(Timeframe.defaultZone(),
                new Pmo(change(), first(), second(), signal(), Pmo.SCALE),
                lot(), MomentumCross.REWARD, slip(), gate() ? Mode.BOTH : Mode.OFF);
    }

    @Override
    public String toString() {
        return label();
    }

    // ------------------------------------------------------------- the values

    static int change() {
        return clamp(Settings.settings().getInt(CHANGE, Pmo.CHANGE), 1, 5_000);
    }

    static int first() {
        return clamp(Settings.settings().getInt(FIRST, Pmo.FIRST), 1, 5_000);
    }

    static int second() {
        return clamp(Settings.settings().getInt(SECOND, Pmo.SECOND), 1, 5_000);
    }

    static int signal() {
        return clamp(Settings.settings().getInt(SIGNAL, Pmo.SIGNAL), 1, 5_000);
    }

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, 1), 1, 100);
    }

    static double slip() {
        return clamp(Settings.settings().getInt(SLIP, (int) MomentumCross.SLIP), 0, 100_000);
    }

    /**
     * @return whether only the side both opening ranges agree on is traded
     *
     * <p>OFF by default, and that matters: every TNO number already published
     * here was measured without it. Turning it on is a different strategy, not
     * a tuned one.</p>
     */
    static boolean gate() {
        return Settings.settings().getBoolean(GATE, false);
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The TNO's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JSpinner change =
                new JSpinner(new SpinnerNumberModel(Pmo.CHANGE, 1, 5_000, 1));

        private final JSpinner first =
                new JSpinner(new SpinnerNumberModel(Pmo.FIRST, 1, 5_000, 1));

        private final JSpinner second =
                new JSpinner(new SpinnerNumberModel(Pmo.SECOND, 1, 5_000, 1));

        private final JSpinner signal =
                new JSpinner(new SpinnerNumberModel(Pmo.SIGNAL, 1, 5_000, 1));

        private final JSpinner lot =
                new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));

        private final javax.swing.JCheckBox gate =
                new javax.swing.JCheckBox(Messages.get("strategy.tno.gate"));

        private final JSpinner slip = new JSpinner(
                new SpinnerNumberModel((int) MomentumCross.SLIP, 0, 100_000, 5));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(row("strategy.tno.change", change));
            panel.add(row("strategy.tno.first", first));
            panel.add(row("strategy.tno.second", second));
            panel.add(row("strategy.tno.signal", signal));
            panel.add(hint("strategy.tno.periods.hint"));
            panel.add(row("strategy.tno.lot", lot));
            panel.add(row("strategy.tno.slip", slip));
            panel.add(hint("strategy.tno.slip.hint"));
            panel.add(row("strategy.tno.gate", gate));
            panel.add(hint("strategy.tno.gate.hint"));
            panel.add(hint("strategy.tno.rule.hint"));
            panel.add(Box.createVerticalGlue());
        }

        private static JPanel row(String key, JComponent control) {
            JPanel made = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

            made.setAlignmentX(Component.LEFT_ALIGNMENT);
            // Uma caixa carrega o proprio rotulo; um segundo ao lado dela le
            // como duas configuracoes na mesma linha.
            if (!(control instanceof javax.swing.JCheckBox)) {
                made.add(new JLabel(Messages.get(key)));
            }

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
            return Messages.get("backtest.strategy.tno");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            change.setValue(change());
            first.setValue(first());
            second.setValue(second());
            signal.setValue(signal());
            lot.setValue(lot());
            slip.setValue((int) slip());
            gate.setSelected(gate());
        }

        @Override
        public void apply() {
            int wantedChange = (Integer) change.getValue();
            int wantedFirst = (Integer) first.getValue();
            int wantedSecond = (Integer) second.getValue();
            int wantedSignal = (Integer) signal.getValue();
            int wantedLot = (Integer) lot.getValue();
            int wantedSlip = (Integer) slip.getValue();
            boolean wantedGate = gate.isSelected();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.putInt(CHANGE, wantedChange);
                settings.putInt(FIRST, wantedFirst);
                settings.putInt(SECOND, wantedSecond);
                settings.putInt(SIGNAL, wantedSignal);
                settings.putInt(LOT, wantedLot);
                settings.putInt(SLIP, wantedSlip);
                settings.putBoolean(GATE, wantedGate);
            });
        }
    }
}
