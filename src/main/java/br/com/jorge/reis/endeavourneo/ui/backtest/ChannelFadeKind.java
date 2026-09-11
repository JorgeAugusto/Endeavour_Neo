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

import br.com.jorge.reis.endeavourneo.domain.indicator.Regression;
import br.com.jorge.reis.endeavourneo.domain.indicator.Stochastic;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.ChannelFade;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.StochasticLatch;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsPage;

import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The channel fade, and the screen that sets it up.
 *
 * <p>The four rungs are four rows of the same three numbers, so they are built
 * from a loop rather than written out: written out, adding a fifth rung would be
 * four edits in four places and the day one of them is missed the screen and the
 * run disagree about what the ladder is.
 */
final class ChannelFadeKind implements StrategyKind {

    private static final String SHORT = "strategy.fade.short";

    private static final String LONG = "strategy.fade.long";

    private static final String LOT = "strategy.fade.lot";

    private static final String LATCH_PERIOD = "strategy.fade.latch.period";

    private static final String LATCH_AVERAGE = "strategy.fade.latch.average";

    private static final String LATCH_BUY = "strategy.fade.latch.buy";

    private static final String LATCH_SELL = "strategy.fade.latch.sell";

    private static final String LATCH_RESET = "strategy.fade.latch.reset";

    private static final String LATCH_ENTRIES = "strategy.fade.latch.entries";

    /** Per rung: whether it is used, and its two factors in HUNDREDTHS. */
    private static final String RUNG_ON = "strategy.fade.rung.%d.on";

    private static final String RUNG_ENTRY = "strategy.fade.rung.%d.entry";

    private static final String RUNG_TARGET = "strategy.fade.rung.%d.target";

    /**
     * The factors in HUNDREDTHS: {@link Settings} stores whole numbers, and
     * 1,75 is not one. Tenths would not be enough — 1,75 is the long channel's
     * own target and rounding it to 1,8 would measure a strategy nobody asked
     * for.
     */
    private static final int HUNDREDTHS = 100;

    @Override
    public String label() {
        return Messages.get("backtest.strategy.fade");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new ChannelFade(Timeframe.defaultZone(), ladder(), lot(), latch());
    }

    @Override
    public String toString() {
        return label();
    }

    // ------------------------------------------------------------- the values

    static int shortPeriod() {
        return clamp(Settings.settings().getInt(SHORT, Regression.SHORT), 2, 5_000);
    }

    static int longPeriod() {
        return clamp(Settings.settings().getInt(LONG, Regression.LONG), 2, 5_000);
    }

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, 1), 1, 100);
    }

    /**
     * @return the rungs that are switched on, with the saved factors
     *
     * <p>An empty ladder would be a strategy that cannot trade, and a screen
     * with every box unticked is a mistake rather than an instruction — so the
     * four he specified come back instead.</p>
     */
    static List<ChannelFade.Rung> ladder() {
        Settings settings = Settings.settings();
        List<ChannelFade.Rung> made = new ArrayList<>();

        for (int i = 0; i < ChannelFade.LADDER.size(); i++) {
            ChannelFade.Rung born = ChannelFade.LADDER.get(i);

            if (!settings.getBoolean(String.format(RUNG_ON, i), true)) {
                continue;
            }

            int entry = settings.getInt(String.format(RUNG_ENTRY, i),
                    (int) Math.round(born.entry() * HUNDREDTHS));
            int target = settings.getInt(String.format(RUNG_TARGET, i),
                    (int) Math.round(born.target() * HUNDREDTHS));

            made.add(new ChannelFade.Rung(
                    born.period() == Regression.SHORT ? shortPeriod() : longPeriod(),
                    Math.max(1, entry) / (double) HUNDREDTHS,
                    Math.max(1, target) / (double) HUNDREDTHS));
        }

        return made.isEmpty() ? ChannelFade.LADDER : made;
    }

    static StochasticLatch.Settings latch() {
        Settings settings = Settings.settings();

        return new StochasticLatch.Settings(true,
                clamp(settings.getInt(LATCH_PERIOD, Stochastic.PERIOD), 1, 500),
                clamp(settings.getInt(LATCH_AVERAGE, Stochastic.AVERAGE), 1, 500),
                clamp(settings.getInt(LATCH_BUY, 20), 0, 100),
                clamp(settings.getInt(LATCH_SELL, 80), 0, 100),
                clamp(settings.getInt(LATCH_RESET,
                        (int) StochasticLatch.Settings.RESET), 0, 100),
                clamp(settings.getInt(LATCH_ENTRIES, 2), 1, 100));
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The fade's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JSpinner shortPeriod =
                new JSpinner(new SpinnerNumberModel(Regression.SHORT, 2, 5_000, 1));

        private final JSpinner longPeriod =
                new JSpinner(new SpinnerNumberModel(Regression.LONG, 2, 5_000, 1));

        private final JSpinner lot = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));

        private final List<JCheckBox> rungOn = new ArrayList<>();

        private final List<JSpinner> rungEntry = new ArrayList<>();

        private final List<JSpinner> rungTarget = new ArrayList<>();

        private final JSpinner latchPeriod =
                new JSpinner(new SpinnerNumberModel(Stochastic.PERIOD, 1, 500, 1));

        private final JSpinner latchAverage =
                new JSpinner(new SpinnerNumberModel(Stochastic.AVERAGE, 1, 500, 1));

        private final JSpinner latchBuy = new JSpinner(new SpinnerNumberModel(20, 0, 100, 1));

        private final JSpinner latchSell = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1));

        private final JSpinner latchReset = new JSpinner(new SpinnerNumberModel(
                (int) StochasticLatch.Settings.RESET, 0, 100, 1));

        private final JSpinner latchEntries = new JSpinner(new SpinnerNumberModel(2, 1, 100, 1));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(row("strategy.fade.short", shortPeriod));
            panel.add(row("strategy.fade.long", longPeriod));
            panel.add(hint("strategy.fade.scale.hint"));
            panel.add(row("strategy.fade.lot", lot));

            panel.add(hint("strategy.fade.ladder.hint"));

            for (int i = 0; i < ChannelFade.LADDER.size(); i++) {
                ChannelFade.Rung born = ChannelFade.LADDER.get(i);

                JCheckBox on = new JCheckBox(Messages.get(
                        born.period() == Regression.SHORT
                                ? "strategy.fade.rung.short" : "strategy.fade.rung.long"));

                JSpinner entry = new JSpinner(new SpinnerNumberModel(born.entry(),
                        0.01, 50.0, 0.25));
                JSpinner target = new JSpinner(new SpinnerNumberModel(born.target(),
                        0.01, 50.0, 0.25));

                rungOn.add(on);
                rungEntry.add(entry);
                rungTarget.add(target);

                JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

                line.setAlignmentX(Component.LEFT_ALIGNMENT);
                line.add(on);
                line.add(new JLabel(Messages.get("strategy.fade.rung.entry")));
                line.add(entry);
                line.add(new JLabel(Messages.get("strategy.fade.rung.target")));
                line.add(target);

                panel.add(line);
            }

            panel.add(hint("strategy.fade.nostop.hint"));

            panel.add(row("strategy.fade.latch.period", latchPeriod));
            panel.add(row("strategy.fade.latch.average", latchAverage));
            panel.add(row("strategy.fade.latch.buy", latchBuy));
            panel.add(row("strategy.fade.latch.sell", latchSell));
            panel.add(row("strategy.fade.latch.reset", latchReset));
            panel.add(row("strategy.fade.latch.entries", latchEntries));
            panel.add(hint("strategy.fade.latch.hint"));

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
            JLabel hint = new JLabel(Messages.orElse(key, ""));

            hint.setAlignmentX(Component.LEFT_ALIGNMENT);
            hint.setBorder(BorderFactory.createEmptyBorder(4, 4, 8, 0));
            hint.setEnabled(false);

            return hint;
        }

        @Override
        public String getTitle() {
            return Messages.get("backtest.strategy.fade");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            Settings settings = Settings.settings();

            shortPeriod.setValue(ChannelFadeKind.shortPeriod());
            longPeriod.setValue(ChannelFadeKind.longPeriod());
            lot.setValue(ChannelFadeKind.lot());

            for (int i = 0; i < rungOn.size(); i++) {
                ChannelFade.Rung born = ChannelFade.LADDER.get(i);

                rungOn.get(i).setSelected(settings.getBoolean(String.format(RUNG_ON, i), true));
                rungEntry.get(i).setValue(settings.getInt(String.format(RUNG_ENTRY, i),
                        (int) Math.round(born.entry() * HUNDREDTHS)) / (double) HUNDREDTHS);
                rungTarget.get(i).setValue(settings.getInt(String.format(RUNG_TARGET, i),
                        (int) Math.round(born.target() * HUNDREDTHS)) / (double) HUNDREDTHS);
            }

            latchPeriod.setValue(settings.getInt(LATCH_PERIOD, Stochastic.PERIOD));
            latchAverage.setValue(settings.getInt(LATCH_AVERAGE, Stochastic.AVERAGE));
            latchBuy.setValue(settings.getInt(LATCH_BUY, 20));
            latchSell.setValue(settings.getInt(LATCH_SELL, 80));
            latchReset.setValue(settings.getInt(LATCH_RESET,
                    (int) StochasticLatch.Settings.RESET));
            latchEntries.setValue(settings.getInt(LATCH_ENTRIES, 2));
        }

        @Override
        public void apply() {
            int curto = (Integer) shortPeriod.getValue();
            int longo = (Integer) longPeriod.getValue();
            int contratos = (Integer) lot.getValue();

            boolean[] ligado = new boolean[rungOn.size()];
            int[] entrada = new int[rungOn.size()];
            int[] alvo = new int[rungOn.size()];

            for (int i = 0; i < rungOn.size(); i++) {
                ligado[i] = rungOn.get(i).isSelected();
                entrada[i] = (int) Math.round((Double) rungEntry.get(i).getValue() * HUNDREDTHS);
                alvo[i] = (int) Math.round((Double) rungTarget.get(i).getValue() * HUNDREDTHS);
            }

            int period = (Integer) latchPeriod.getValue();
            int average = (Integer) latchAverage.getValue();
            int buy = (Integer) latchBuy.getValue();
            int sell = (Integer) latchSell.getValue();
            int reset = (Integer) latchReset.getValue();
            int entries = (Integer) latchEntries.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.putInt(SHORT, curto);
                settings.putInt(LONG, longo);
                settings.putInt(LOT, contratos);

                for (int i = 0; i < ligado.length; i++) {
                    settings.putBoolean(String.format(RUNG_ON, i), ligado[i]);
                    settings.putInt(String.format(RUNG_ENTRY, i), entrada[i]);
                    settings.putInt(String.format(RUNG_TARGET, i), alvo[i]);
                }

                settings.putInt(LATCH_PERIOD, period);
                settings.putInt(LATCH_AVERAGE, average);
                settings.putInt(LATCH_BUY, buy);
                settings.putInt(LATCH_SELL, sell);
                settings.putInt(LATCH_RESET, reset);
                settings.putInt(LATCH_ENTRIES, entries);
            });
        }
    }
}
