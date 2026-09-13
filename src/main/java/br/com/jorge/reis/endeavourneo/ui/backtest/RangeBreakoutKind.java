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
import java.time.LocalTime;
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
 * The Range 90, and the screen that sets it up.
 *
 * <p>The target used to be off this screen on purpose: it is 1,5R or 2R and the
 * walk-forward selector chooses between them every morning out of the sessions
 * before it, so offering the number here would let the reader pick it after
 * seeing the result — the one thing the selector exists to prevent.</p>
 *
 * <p>It is here now, and behind the switch that says why. <b>Turning the
 * selector off makes every session that breaks a traded session, at one fixed
 * target</b> — which is a different strategy, not a tuned one. §6 exists
 * because the range operated blind lost money on this series, so a curve read
 * with the box ticked is a curve of the range WITHOUT its only filter, and a
 * target chosen by looking at that curve is chosen in hindsight. The controls
 * are here to let that be seen, not to be left switched off.</p>
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

    private static final String CLOSE_AT = "strategy.range90.closeAt";

    private static final String EVERY_DAY = "strategy.range90.everyDay";

    private static final String TREND = "strategy.range90.trend";

    /**
     * The fixed target in HUNDREDTHS of R.
     *
     * <p>{@link Settings} stores whole numbers, and 1,5 is not one. Hundredths
     * rather than tenths so that a quarter of R — which is what {@code ARM_R}
     * already deals in — can be written without a rounding of its own.</p>
     */
    private static final String TARGET = "strategy.range90.target";

    private static final int HUNDREDTHS = 100;

    /**
     * The deadline, kept as MINUTES PAST MIDNIGHT.
     *
     * <p>Not as "17:45". {@code Settings} stores strings, and a time written as
     * text has to be parsed back — which means picking a locale and a separator
     * for a value nobody outside this class ever reads. A whole number of
     * minutes has one spelling everywhere.</p>
     */
    private static int minutesOf(LocalTime when) {
        return when.getHour() * 60 + when.getMinute();
    }

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
        return new RangeBreakout(Timeframe.defaultZone(), lot(), cap(), window(),
                formation(), closeAt(), !everyDay(), target(), trend());
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

    /** @return the time everything is out by, whatever the session does after */
    static LocalTime closeAt() {
        int minutes = clamp(Settings.settings()
                .getInt(CLOSE_AT, minutesOf(RangeBreakout.CLOSE_AT)), 0, 24 * 60 - 1);

        return LocalTime.of(minutes / 60, minutes % 60);
    }

    /**
     * @return whether the daily average filters the day and chooses its side
     *
     * <p>OFF by default, and that is a decision rather than an oversight: the
     * filter is in the specification, and every Range 90 number ever published
     * here was measured with it ON.</p>
     */
    static boolean trend() {
        return Settings.settings().getBoolean(TREND, false);
    }

    /** @return whether the selector is off, and every session that breaks is taken */
    static boolean everyDay() {
        return Settings.settings().getBoolean(EVERY_DAY, false);
    }

    /** @return the fixed target in R, used only while {@link #everyDay()} */
    static double target() {
        return clamp(Settings.settings().getInt(TARGET,
                (int) Math.round(RangeBreakout.TARGET * HUNDREDTHS)),
                1, 100 * HUNDREDTHS) / (double) HUNDREDTHS;
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

        private final JCheckBox trend =
                new JCheckBox(Messages.get("strategy.range90.trend"));

        private final JCheckBox everyDay =
                new JCheckBox(Messages.get("strategy.range90.everyDay"));

        /**
         * The fixed target, in R.
         *
         * <p>A quarter of R per click: the strategy already measures the arm
         * and the partial in quarters, so the target moving in the same step
         * keeps every number on this screen on one grid.</p>
         */
        private final JSpinner target = new JSpinner(
                new SpinnerNumberModel(RangeBreakout.TARGET, 0.25, 100.0, 0.25));

        /**
         * The deadline, as a clock.
         *
         * <p>A {@code SpinnerDateModel} on {@code MINUTE} rather than a typed
         * field: there is nothing to validate, nothing to parse, and no way to
         * leave it saying something that is not a time.</p>
         */
        private final JSpinner closeAt =
                new JSpinner(new javax.swing.SpinnerDateModel(
                        new java.util.Date(), null, null, java.util.Calendar.MINUTE));

        private Page() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));

            closeAt.setEditor(new JSpinner.DateEditor(closeAt, "HH:mm"));

            panel.add(row("strategy.range90.formation", formation));
            panel.add(hint("strategy.range90.formation.hint"));
            panel.add(row("strategy.range90.window", window));
            panel.add(hint("strategy.range90.window.hint"));
            panel.add(row("strategy.range90.closeAt", closeAt));
            panel.add(hint("strategy.range90.closeAt.hint"));
            panel.add(row("strategy.range90.lot", lot));
            panel.add(row("strategy.range90.cap", cap));
            panel.add(hint("strategy.range90.lot.hint"));

            panel.add(row("strategy.range90.trend", trend));
            panel.add(hint("strategy.range90.trend.hint"));
            panel.add(row("strategy.range90.everyDay", everyDay));
            panel.add(hint("strategy.range90.everyDay.hint"));
            panel.add(row("strategy.range90.target", target));
            panel.add(hint("strategy.range90.target.hint"));
            panel.add(Box.createVerticalGlue());

            // THE TARGET ONLY MEANS SOMETHING WITH THE SELECTOR OFF. Left
            // enabled it would read as the target of every run, and a reader
            // who sets it to 2,0 and gets 1,5 trades would be right to think
            // the screen lied to him.
            everyDay.addActionListener(event -> target.setEnabled(everyDay.isSelected()));
        }

        private static JPanel row(String key, JComponent control) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

            row.setAlignmentX(Component.LEFT_ALIGNMENT);
            // A CHECKBOX CARRIES ITS OWN LABEL, and a second one beside it
            // reads as two different settings on one line.
            if (!(control instanceof JCheckBox)) {
                row.add(new JLabel(Messages.get(key)));
            }

            row.add(control);

            return row;
        }

        /**
         * @return that time today, which is all a clock spinner ever shows
         *
         * <p>The date half is carried because {@code SpinnerDateModel} deals in
         * {@code Date} and nothing else; it is never read back.</p>
         */
        private static java.util.Date dateOf(LocalTime when) {
            return java.util.Date.from(when.atDate(java.time.LocalDate.now())
                    .atZone(java.time.ZoneId.systemDefault()).toInstant());
        }

        private static LocalTime timeOf(java.util.Date when) {
            return when.toInstant().atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                    .withSecond(0).withNano(0);
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
            closeAt.setValue(dateOf(closeAt()));
            trend.setSelected(trend());
            everyDay.setSelected(everyDay());
            target.setValue(target());
            target.setEnabled(everyDay());
        }

        @Override
        public void apply() {
            int wantedLot = (Integer) lot.getValue();
            int wantedCap = (Integer) cap.getValue();
            int wantedWindow = (Integer) window.getValue();
            int wantedFormation = (Integer) formation.getValue();
            int wantedClose = minutesOf(timeOf((java.util.Date) closeAt.getValue()));
            boolean wantedEveryDay = everyDay.isSelected();
            boolean wantedTrend = trend.isSelected();
            int wantedTarget = (int) Math.round(
                    ((Number) target.getValue()).doubleValue() * HUNDREDTHS);

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
                settings.putInt(CLOSE_AT, wantedClose);
                settings.putBoolean(EVERY_DAY, wantedEveryDay);
                settings.putBoolean(TREND, wantedTrend);
                settings.putInt(TARGET, Math.max(1, wantedTarget));
            });
        }
    }
}
