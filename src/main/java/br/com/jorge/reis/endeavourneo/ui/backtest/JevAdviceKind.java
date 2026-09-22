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
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.JevAdvice;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.JevDecisions;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.settings.SettingsPage;

import java.awt.Component;
import java.awt.FlowLayout;
import java.io.IOException;
import java.nio.file.Path;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;

/**
 * The outside model's decisions as a strategy, and the screen that points at them.
 *
 * <p>The only unusual field is the first: a PATH. This strategy does not
 * compute a signal, it obeys one that was computed elsewhere and written down,
 * so the file is its real input — as much as the price series is.
 *
 * <p>The file is read when the run is built, not held: edit it, run again, and
 * the new decisions are used. That matters because producing it is a long job
 * that can be interrupted and resumed, and a half-finished file is a perfectly
 * good thing to look at — it just covers fewer bars.
 */
final class JevAdviceKind implements StrategyKind {

    private static final String FILE = "strategy.jev.file";

    private static final String CONFIDENCE = "strategy.jev.confidence";

    private static final String TARGET = "strategy.jev.target";

    private static final String STOP = "strategy.jev.stop";

    private static final String SLIP = "strategy.jev.slip";

    private static final String LOT = "strategy.jev.lot";

    @Override
    public String label() {
        return Messages.get("backtest.strategy.jev");
    }

    @Override
    public SettingsPage page() {
        return new Page();
    }

    @Override
    public Strategy build() {
        return new JevAdvice(Timeframe.defaultZone(), decisions(),
                confidence() / 100.0, target(), stop(), slip(), lot());
    }

    /**
     * @return what the file holds, or nothing at all when it cannot be read
     *
     * <p>Nothing rather than an exception, and the reason is what the reader
     * sees: a strategy that refuses to build leaves the backtest with an error
     * dialog and no run, while one that builds with no decisions produces a run
     * with no trades — and the report says zero operations, which is the truth
     * and is legible. The console carries the reason.</p>
     */
    private static JevDecisions decisions() {
        String where = path();

        if (where == null || where.isBlank()) {
            return JevDecisions.empty();
        }

        try {
            return JevDecisions.read(Path.of(where.trim()));
        } catch (IOException | RuntimeException e) {
            System.out.println("nao consegui ler as decisoes de " + where + ": "
                    + e.getMessage());

            return JevDecisions.empty();
        }
    }

    // ------------------------------------------------------------- the values

    static String path() {
        return Settings.settings().get(FILE, "");
    }

    static int confidence() {
        return clamp(Settings.settings()
                .getInt(CONFIDENCE, (int) Math.round(JevAdvice.LEAST_CONFIDENCE * 100)), 0, 100);
    }

    static int target() {
        return clamp(Settings.settings().getInt(TARGET, (int) JevAdvice.TARGET), 5, 100_000);
    }

    static int stop() {
        return clamp(Settings.settings().getInt(STOP, (int) JevAdvice.STOP), 5, 100_000);
    }

    static int slip() {
        return clamp(Settings.settings().getInt(SLIP, (int) JevAdvice.SLIP), 0, 100_000);
    }

    static int lot() {
        return clamp(Settings.settings().getInt(LOT, 1), 1, 100);
    }

    private static int clamp(int value, int least, int most) {
        return Math.max(least, Math.min(value, most));
    }

    @Override
    public String toString() {
        return label();
    }

    /** The outside model's own screen. */
    private static final class Page implements SettingsPage {

        private final JPanel panel = new JPanel();

        private final JTextField file = new JTextField(38);

        private final JSpinner confidence =
                new JSpinner(new SpinnerNumberModel(30, 0, 100, 5));

        private final JSpinner target =
                new JSpinner(new SpinnerNumberModel((int) JevAdvice.TARGET, 5, 100_000, 5));

        private final JSpinner stop =
                new JSpinner(new SpinnerNumberModel((int) JevAdvice.STOP, 5, 100_000, 5));

        private final JSpinner slip =
                new JSpinner(new SpinnerNumberModel((int) JevAdvice.SLIP, 0, 100_000, 5));

        private final JSpinner lot =
                new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            panel.add(row("strategy.jev.file", file));
            panel.add(hint("strategy.jev.file.hint"));
            panel.add(row("strategy.jev.confidence", confidence));
            panel.add(hint("strategy.jev.confidence.hint"));
            panel.add(row("strategy.jev.target", target));
            panel.add(row("strategy.jev.stop", stop));
            panel.add(row("strategy.jev.lot", lot));
            panel.add(row("strategy.jev.slip", slip));
            panel.add(hint("strategy.jev.reading.hint"));
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
            return Messages.get("backtest.strategy.jev");
        }

        @Override
        public JComponent getComponent() {
            return panel;
        }

        @Override
        public void load() {
            file.setText(path());
            confidence.setValue(confidence());
            target.setValue(target());
            stop.setValue(stop());
            slip.setValue(slip());
            lot.setValue(lot());
        }

        @Override
        public void apply() {
            String wantedFile = file.getText() == null ? "" : file.getText().trim();
            int wantedConfidence = (Integer) confidence.getValue();
            int wantedTarget = (Integer) target.getValue();
            int wantedStop = (Integer) stop.getValue();
            int wantedSlip = (Integer) slip.getValue();
            int wantedLot = (Integer) lot.getValue();

            Settings settings = Settings.settings();

            settings.hold(() -> {
                settings.put(FILE, wantedFile);
                settings.putInt(CONFIDENCE, wantedConfidence);
                settings.putInt(TARGET, wantedTarget);
                settings.putInt(STOP, wantedStop);
                settings.putInt(SLIP, wantedSlip);
                settings.putInt(LOT, wantedLot);
            });
        }
    }
}
