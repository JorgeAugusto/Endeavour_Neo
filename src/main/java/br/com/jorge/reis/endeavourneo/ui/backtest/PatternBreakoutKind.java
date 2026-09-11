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

import br.com.jorge.reis.endeavourneo.domain.indicator.Stochastic;
import br.com.jorge.reis.endeavourneo.domain.market.CandlePattern;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.PatternBreakout;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsPage;

import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The pattern breakout, and the screen that sets it up.
 *
 * <p>One screen for the three shapes, because they are one strategy: in on the
 * break of the pattern bar, stop at the extreme of the structure, target by the
 * ratio. Which shape is a choice, not a different strategy — and having them as
 * three entries in the list would be three places to fix the day the rule
 * changes.
 */
final class PatternBreakoutKind implements StrategyKind {

    private static final String FAMILY = "strategy.breakout.family";

    private static final String REWARD = "strategy.breakout.reward";

    private static final String VALID = "strategy.breakout.valid";

    private static final String LOT = "strategy.breakout.lot";

    private static final String LATCH = "strategy.breakout.latch";

    private static final String LATCH_PERIOD = "strategy.breakout.latch.period";

    private static final String LATCH_AVERAGE = "strategy.breakout.latch.average";

    private static final String LATCH_BUY = "strategy.breakout.latch.buy";

    private static final String LATCH_SELL = "strategy.breakout.latch.sell";

    private static final String LATCH_ENTRIES = "strategy.breakout.latch.entries";

    private static final String DOUBLING = "strategy.breakout.doubling";

    private static final String DOUBLING_MOST = "strategy.breakout.doubling.most";

    /** The ratio in TENTHS: Settings stores ints, and 1,5 is not one. */
    private static final int REWARD_TENTHS = 15;

    private static final CandlePattern.Family[] SHAPES = {
            CandlePattern.Family.PFR,
            CandlePattern.Family.INSIDE,
            CandlePattern.Family.ONE_TWO_THREE,
    };

    @Override
    public String label() {
        return Messages.get("backtest.strategy.breakout");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new PatternBreakout(Timeframe.defaultZone(), family(),
                reward() / 10.0, validFor(), lot(), latch(), doubling());
    }

    @Override
    public String toString() {
        return label();
    }

    // ------------------------------------------------------------- the values

    static CandlePattern.Family family() {
        String wanted = Settings.settings().get(FAMILY, null);

        for (CandlePattern.Family each : SHAPES) {
            if (each.name().equals(wanted)) {
                return each;
            }
        }

        return CandlePattern.Family.PFR;
    }

    /** @return the ratio in tenths: 15 is 1,5 times the risk */
    static int reward() {
        return clamp(Settings.settings().getInt(REWARD, REWARD_TENTHS), 1, 500);
    }

    static int validFor() {
        return clamp(Settings.settings().getInt(VALID, PatternBreakout.VALID_FOR), 1, 500);
    }

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, 1), 1, 100);
    }

    /** @return the stochastic filter as saved, or {@link PatternBreakout.Latch#off()} */
    static PatternBreakout.Latch latch() {
        Settings settings = Settings.settings();

        if (!settings.getBoolean(LATCH, false)) {
            return PatternBreakout.Latch.off();
        }

        return new PatternBreakout.Latch(true,
                clamp(settings.getInt(LATCH_PERIOD, Stochastic.PERIOD), 1, 500),
                clamp(settings.getInt(LATCH_AVERAGE, Stochastic.AVERAGE), 1, 500),
                clamp(settings.getInt(LATCH_BUY, 20), 0, 100),
                clamp(settings.getInt(LATCH_SELL, 80), 0, 100),
                clamp(settings.getInt(LATCH_ENTRIES, 2), 1, 100));
    }

    /** @return the martingale as saved, or {@link PatternBreakout.Doubling#off()} */
    static PatternBreakout.Doubling doubling() {
        Settings settings = Settings.settings();

        if (!settings.getBoolean(DOUBLING, false)) {
            return PatternBreakout.Doubling.off();
        }

        return new PatternBreakout.Doubling(true,
                clamp(settings.getInt(DOUBLING_MOST, 2), 0,
                        PatternBreakout.Doubling.CEILING));
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The breakout's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JComboBox<CandlePattern.Family> family = new JComboBox<>(SHAPES);

        private final JSpinner reward =
                new JSpinner(new SpinnerNumberModel(1.5, 0.1, 50.0, 0.1));

        private final JSpinner valid =
                new JSpinner(new SpinnerNumberModel(PatternBreakout.VALID_FOR, 1, 500, 1));

        private final JSpinner lot = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));

        private final JCheckBox latch =
                new JCheckBox(Messages.get("strategy.breakout.latch"));

        private final JSpinner latchPeriod =
                new JSpinner(new SpinnerNumberModel(Stochastic.PERIOD, 1, 500, 1));

        private final JSpinner latchAverage =
                new JSpinner(new SpinnerNumberModel(Stochastic.AVERAGE, 1, 500, 1));

        private final JSpinner latchBuy = new JSpinner(new SpinnerNumberModel(20, 0, 100, 1));

        private final JSpinner latchSell = new JSpinner(new SpinnerNumberModel(80, 0, 100, 1));

        private final JSpinner latchEntries = new JSpinner(new SpinnerNumberModel(2, 1, 100, 1));

        private final JCheckBox doubling =
                new JCheckBox(Messages.get("strategy.breakout.doubling"));

        private final JSpinner doublingMost = new JSpinner(
                new SpinnerNumberModel(2, 0, PatternBreakout.Doubling.CEILING, 1));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            family.setRenderer(new DefaultListCellRenderer() {

                private static final long serialVersionUID = 1L;

                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value,
                        int index, boolean selected, boolean focused) {

                    super.getListCellRendererComponent(list, value, index, selected, focused);

                    if (value instanceof CandlePattern.Family shape) {
                        setText(Messages.get(shape.nameKey()));
                    }

                    return this;
                }
            });

            panel.add(row("strategy.breakout.family", family));
            panel.add(hint("strategy.breakout.family.hint"));
            panel.add(row("strategy.breakout.reward", reward));
            panel.add(hint("strategy.breakout.reward.hint"));
            panel.add(row("strategy.breakout.valid", valid));
            panel.add(hint("strategy.breakout.valid.hint"));
            panel.add(row("strategy.breakout.lot", lot));
            panel.add(hint("strategy.breakout.stop.hint"));

            // ------------------------------------------------ o filtro
            latch.setAlignmentX(Component.LEFT_ALIGNMENT);
            latch.addActionListener(e -> followTheSwitches());

            panel.add(latch);
            panel.add(hint("strategy.breakout.latch.hint"));
            panel.add(row("strategy.breakout.latch.period", latchPeriod));
            panel.add(row("strategy.breakout.latch.average", latchAverage));
            panel.add(hint("strategy.breakout.latch.scale.hint"));
            panel.add(row("strategy.breakout.latch.buy", latchBuy));
            panel.add(row("strategy.breakout.latch.sell", latchSell));
            panel.add(row("strategy.breakout.latch.entries", latchEntries));
            panel.add(hint("strategy.breakout.latch.entries.hint"));

            // ------------------------------------------------ dobrar a mao
            doubling.setAlignmentX(Component.LEFT_ALIGNMENT);
            doubling.addActionListener(e -> followTheSwitches());

            panel.add(doubling);
            panel.add(hint("strategy.breakout.doubling.hint"));
            panel.add(row("strategy.breakout.doubling.most", doublingMost));
            panel.add(hint("strategy.breakout.doubling.most.hint"));

            panel.add(Box.createVerticalGlue());
        }

        /**
         * Greys out what a switch that is off does not use.
         *
         * <p>A period and two levels that change nothing are three numbers the
         * reader has to work out are inert, and the working out happens after a
         * run came back the same as the last one.</p>
         */
        private void followTheSwitches() {
            boolean filtering = latch.isSelected();

            latchPeriod.setEnabled(filtering);
            latchAverage.setEnabled(filtering);
            latchBuy.setEnabled(filtering);
            latchSell.setEnabled(filtering);
            latchEntries.setEnabled(filtering);

            doublingMost.setEnabled(doubling.isSelected());
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
            return Messages.get("backtest.strategy.breakout");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            family.setSelectedItem(PatternBreakoutKind.family());
            reward.setValue(PatternBreakoutKind.reward() / 10.0);
            valid.setValue(PatternBreakoutKind.validFor());
            lot.setValue(PatternBreakoutKind.lot());

            Settings settings = Settings.settings();

            // READ FROM THE STORE AND NOT FROM latch(), which answers off() for
            // every field when the switch is off -- the screen would then forget
            // the period and the levels every time the box was unticked.
            latch.setSelected(settings.getBoolean(LATCH, false));
            latchPeriod.setValue(settings.getInt(LATCH_PERIOD, Stochastic.PERIOD));
            latchAverage.setValue(settings.getInt(LATCH_AVERAGE, Stochastic.AVERAGE));
            latchBuy.setValue(settings.getInt(LATCH_BUY, 20));
            latchSell.setValue(settings.getInt(LATCH_SELL, 80));
            latchEntries.setValue(settings.getInt(LATCH_ENTRIES, 2));

            doubling.setSelected(settings.getBoolean(DOUBLING, false));
            doublingMost.setValue(settings.getInt(DOUBLING_MOST, 2));

            followTheSwitches();
        }

        @Override
        public void apply() {
            Object shape = family.getSelectedItem();

            // TENTHS ON THE WAY IN AS WELL. Settings keeps whole numbers, and
            // rounding only on the way back would show a screen saying 1,55 and
            // a run using 1,5.
            int tenths = (int) Math.round((Double) reward.getValue() * 10);
            int bars = (Integer) valid.getValue();
            int many = (Integer) lot.getValue();

            boolean filtering = latch.isSelected();
            int period = (Integer) latchPeriod.getValue();
            int average = (Integer) latchAverage.getValue();
            int buy = (Integer) latchBuy.getValue();
            int sell = (Integer) latchSell.getValue();
            int entries = (Integer) latchEntries.getValue();

            boolean martingale = doubling.isSelected();
            int most = (Integer) doublingMost.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                if (shape instanceof CandlePattern.Family chosen) {
                    settings.put(FAMILY, chosen.name());
                }

                settings.putInt(REWARD, Math.max(1, tenths));
                settings.putInt(VALID, bars);
                settings.putInt(LOT, many);

                settings.putBoolean(LATCH, filtering);
                settings.putInt(LATCH_PERIOD, period);
                settings.putInt(LATCH_AVERAGE, average);
                settings.putInt(LATCH_BUY, buy);
                settings.putInt(LATCH_SELL, sell);
                settings.putInt(LATCH_ENTRIES, entries);

                settings.putBoolean(DOUBLING, martingale);
                settings.putInt(DOUBLING_MOST, most);
            });
        }
    }
}
