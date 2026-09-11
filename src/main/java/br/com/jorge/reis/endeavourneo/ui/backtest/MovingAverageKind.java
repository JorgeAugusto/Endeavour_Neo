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

import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.Average;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.MovingAverageCrossing;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsPage;

import java.awt.Component;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/**
 * The crossing of two averages, and the screen that sets it up.
 *
 * <p>Its three parameters live here and nowhere else: two periods and the kind
 * of average. They used to sit on the command bar, which only worked while
 * there was one strategy — the bar would have to grow a row for every strategy
 * ever added and show the wrong row most of the time.</p>
 */
final class MovingAverageKind implements StrategyKind {

    private static final String FAST = "strategy.crossing.fast";

    private static final String SLOW = "strategy.crossing.slow";

    private static final String KIND = "strategy.crossing.kind";

    /** His periods, from every port done from the Profit so far. */
    private static final int DEFAULT_FAST = 17;

    private static final int DEFAULT_SLOW = 34;

    @Override
    public String label() {
        return Messages.get("backtest.strategy.crossing");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        int fast = fastPeriod();
        int slow = slowPeriod();

        return new MovingAverageCrossing(fast, slow,
                BacktestPreferences.contracts(), kindOfAverage());
    }

    @Override
    public String toString() {
        return label();
    }

    // ------------------------------------------------------------- the values

    static int fastPeriod() {
        return clamp(Settings.settings().getInt(FAST, DEFAULT_FAST), 1, 998);
    }

    static int slowPeriod() {
        // ALWAYS SLOWER THAN THE FAST ONE, whatever is in the file. The strategy
        // refuses the other way round, and it refuses by throwing -- which from
        // the reader's side is a Run button that does nothing and says nothing.
        return clamp(Settings.settings().getInt(SLOW, DEFAULT_SLOW), fastPeriod() + 1, 999);
    }

    static Average kindOfAverage() {
        return Average.SIMPLE.name().equals(Settings.settings().get(KIND, null))
                ? Average.SIMPLE : Average.EXPONENTIAL;
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    /** The crossing's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JSpinner fast = new JSpinner(new SpinnerNumberModel(DEFAULT_FAST, 1, 998, 1));

        private final JSpinner slow = new JSpinner(new SpinnerNumberModel(DEFAULT_SLOW, 2, 999, 1));

        private final JComboBox<Average> kind = new JComboBox<>(Average.values());

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            kind.setRenderer(new javax.swing.DefaultListCellRenderer() {

                private static final long serialVersionUID = 1L;

                @Override
                public Component getListCellRendererComponent(javax.swing.JList<?> list,
                        Object value, int index, boolean selected, boolean focused) {
                    super.getListCellRendererComponent(list, value, index, selected, focused);

                    if (value instanceof Average average) {
                        setText(average.label() + " — " + Messages.get(
                                average == Average.SIMPLE
                                        ? "strategy.average.simple" : "strategy.average.exponential"));
                    }

                    return this;
                }
            });

            panel.add(row("strategy.crossing.fast", fast));
            panel.add(row("strategy.crossing.slow", slow));
            panel.add(hint("strategy.crossing.periods.hint"));
            panel.add(row("strategy.crossing.average", kind));
            panel.add(hint("strategy.crossing.average.hint"));
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
            return Messages.get("backtest.strategy.crossing");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            fast.setValue(fastPeriod());
            slow.setValue(slowPeriod());
            kind.setSelectedItem(kindOfAverage());
        }

        @Override
        public void apply() {
            int wantedFast = (Integer) fast.getValue();
            int wantedSlow = (Integer) slow.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.putInt(FAST, wantedFast);

                // KEPT SLOWER, here as well as on the way out. Saving 34 and 17
                // the wrong way round and correcting it only on the way back
                // would show the reader a screen that says one thing and a run
                // that does another.
                settings.putInt(SLOW, Math.max(wantedSlow, wantedFast + 1));
                settings.put(KIND, String.valueOf(kind.getSelectedItem()));
            });
        }
    }
}
