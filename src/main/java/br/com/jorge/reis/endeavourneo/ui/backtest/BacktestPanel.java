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

import br.com.jorge.reis.endeavourneo.domain.market.Aggregation;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Renko;
import br.com.jorge.reis.endeavourneo.domain.market.Slice;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Metrics;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.Watching;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.MovingAverageCrossing;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartPane;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;
import br.com.jorge.reis.endeavourneo.ui.chart.PeriodDialog;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Pick a series, pick a strategy, run it, and look at what it did.
 *
 * <p>Three things share this window and each answers something the others
 * cannot. The <b>quadro</b> down the right says whether it made money and
 * whether the number can be believed. The <b>table</b> is the index of what
 * happened. The <b>price chart</b>, with the operations marked on it, says
 * <i>where</i> — and it is the only one that can tell you the strategy only
 * works in the first hour of the session.</p>
 *
 * <h2>Clicking an operation takes the chart to it</h2>
 *
 * <p>The list is the index and the chart is the page. That is the loop the
 * previous project's trades panel had and the reason it got used for two years:
 * you scan for the worst loss, click it, and the chart shows you what happened
 * — instead of writing down a bar number and going to look for it.</p>
 *
 * <h2>The run does not happen on the EDT</h2>
 *
 * <p>Six years of one-minute bars is a 40 MB read, and the run itself is fast
 * only because it is simple; a strategy that is not will not be. A window frozen
 * mid-run cannot even say it is working, so the work goes to a worker and the
 * button says what is happening.</p>
 */
final class BacktestPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    /** Below this the quadro is a column of cut-off numbers, not a quadro. */
    private static final int LEAST_QUADRO = 220;

    /** And below this there is no chart left to look at. */
    private static final int LEAST_CHART = 320;

    /** What it is born at, and what it comes back to until the reader drags it. */
    private static final int WANTED_QUADRO = 330;

    /** What remembers "as stored", which is the one scale with no period code. */
    private static final String STORED = "asStored";

    /**
     * The scales a run may be read at, from the catalogue the chart reads.
     *
     * <p>The first is "as stored", and it is the default: a series already kept
     * at five minutes does not need aggregating, and a series not measured in
     * time must not be — the list is disabled entirely for one of those. Every
     * other entry comes from {@link PeriodCatalog#common()}, so this window and
     * the chart offer the same scales, spelled the same way, and the "…" button
     * beside the list reaches everything else through the same dialog the
     * indicator screens use.</p>
     *
     * <h2>Renko is a scale here, like any other</h2>
     *
     * <p>It answers the same question the others answer — <b>what is a decision
     * bar</b>. {@link Renko} is an {@link Aggregation} precisely so that it can
     * stand where a {@link Timeframe} stands.</p>
     *
     * <p>What the run does NOT take from the catalogue is the forming brick. The
     * period box builds its bricks with {@code withForming(true)} so the live
     * edge keeps moving while the minute runs, and a forming brick is one that
     * <b>can still change</b>: asking a strategy to decide on it is asking it to
     * decide on a bar that has not closed. See {@link #settled}.</p>
     */
    static Scale[] scales() {
        List<Scale> made = new ArrayList<>();

        made.add(Scale.stored());

        for (PeriodCatalog.Choice each : PeriodCatalog.common()) {
            made.add(settled(each));
        }

        return made.toArray(new Scale[0]);
    }

    /**
     * The same period, with the forming brick off.
     *
     * <p>The catalogue builds every renko for the CHART, where the brick still
     * being built has to be drawn or the live edge stops moving. A run must not
     * see it: it is a bar that has not closed, and a strategy asked to decide on
     * it is deciding on a bar the next trade can still change underneath it.</p>
     *
     * <p>Timeframes come through untouched — there is no such thing as a forming
     * five minutes here, because the aggregation only emits bars that ended.</p>
     */
    static Scale settled(PeriodCatalog.Choice choice) {
        if (!(choice.aggregation() instanceof Renko renko)) {
            return new Scale(choice);
        }

        return new Scale(new PeriodCatalog.Choice(choice.code(), choice.description(),
                renko.withForming(false)));
    }

    private final JComboBox<SeriesChoice> series = new JComboBox<>();

    private final JComboBox<Scale> scale = new JComboBox<>(scales());

    /** Opens the period dialog, for the scales the short list does not carry. */
    private final JButton more = ellipsis();

    private final JComboBox<Execution> how = new JComboBox<>(Execution.values());

    private final JComboBox<Slice> slice = new JComboBox<>(Slice.values());

    private final br.com.jorge.reis.endeavourneo.ui.replay.DatePicker from =
            new br.com.jorge.reis.endeavourneo.ui.replay.DatePicker(null);

    private final br.com.jorge.reis.endeavourneo.ui.replay.DatePicker to =
            new br.com.jorge.reis.endeavourneo.ui.replay.DatePicker(null);

    private final JLabel between = new JLabel(Messages.get("backtest.between"));

    private final JComboBox<StrategyKind> strategy = new JComboBox<>();

    private final JButton settings = gear();

    private final JButton run = new JButton(Messages.get("backtest.run"));

    /**
     * The way to a scale the short list does not carry.
     *
     * <p>The list holds the fourteen periods worth showing before anything is
     * typed; the catalogue builds any minute count and any brick from 3R to
     * 101R. Rather than a second, longer list here — which is exactly the
     * divergence this window just stopped having — the button opens {@link
     * PeriodDialog}, the same screen the eight indicator dialogs open, and the
     * period it returns is added to the list and selected.</p>
     */
    private JButton ellipsis() {
        JButton button = new JButton("…");

        button.setToolTipText(Messages.get("backtest.scale.more"));
        button.setFocusable(false);
        button.addActionListener(e -> askForAperiod());

        return button;
    }

    /**
     * Puts back the scale a workspace was saved on.
     *
     * <p>Through the CATALOGUE and not only through the list, which is the whole
     * point of storing a period code. A run left on 37R — typed into the dialog,
     * never one of the fourteen listed — used to reopen on "as stored", quietly,
     * and quietly is the problem: a window that comes back on the wrong scale
     * looks exactly like one that came back right.</p>
     *
     * <p>A code the catalogue no longer knows leaves the default alone rather
     * than guessing at something near it.</p>
     */
    private void restoreTheScale(String wanted) {
        if (wanted == null || STORED.equals(wanted)) {
            return;
        }

        PeriodCatalog.Choice known = PeriodCatalog.byCode(wanted);

        if (known != null) {
            use(settled(known));
        }
    }

    private void askForAperiod() {
        PeriodCatalog.Choice picked = PeriodDialog.ask(
                javax.swing.SwingUtilities.getWindowAncestor(this), null);

        if (picked == null) {
            return;
        }

        use(settled(picked));
    }

    /**
     * Selects that scale, adding it to the list when it is not already there.
     *
     * <p>Matched by CODE and not by the object: a {@link Timeframe} is built
     * fresh on every call of the catalogue, so two entries meaning five minutes
     * are equal in every way that matters and equal in none that {@code equals}
     * can see. Comparing objects would file a second "5m" under the first one
     * every time.</p>
     */
    private void use(Scale wanted) {
        for (int i = 0; i < scale.getItemCount(); i++) {
            if (scale.getItemAt(i).id().equals(wanted.id())) {
                scale.setSelectedIndex(i);

                return;
            }
        }

        scale.addItem(wanted);
        scale.setSelectedItem(wanted);
    }

    /**
     * The door to Configurações > Backtest, next to the button that needs it.
     *
     * <p>THE STRATEGY'S screen, not the backtest's. What a round trip costs and
     * how many contracts an order gets are the same for every run and live in
     * Configurações; what a crossing's periods are belongs to the crossing, and
     * the next strategy will have something else entirely. The gear sits beside
     * the strategy list because it opens whatever is selected in it.</p>
     */
    private JButton gear() {
        JButton button = new JButton("⚙");

        button.setToolTipText(Messages.get("backtest.strategy.settings"));
        button.setFocusable(false);
        button.addActionListener(e -> openStrategySettings());

        return button;
    }

    private void openStrategySettings() {
        StrategyKind kind = (StrategyKind) strategy.getSelectedItem();

        if (kind == null) {
            return;
        }

        br.com.jorge.reis.endeavourneo.ui.settings.SettingsDialog.show(
                javax.swing.SwingUtilities.getWindowAncestor(this),
                java.util.List.of(kind.page()));
    }

    private final ResultPanel result = new ResultPanel();

    private final JScrollPane quadro = new JScrollPane(result,
            JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

    private final JSplitPane sides = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);

    private final JToggleButton showQuadro =
            new JToggleButton(Messages.get("backtest.quadro"), true);

    /** Whether the divider has been put where it belongs, which needs a width. */
    private boolean placed;

    private final ChartCanvas chart = new ChartCanvas();

    /**
     * The chart as a whole, and not just its price.
     *
     * <p>It was a bare {@link ChartCanvas}, and that is why a stochastic could
     * not be inserted here: a study lives in a panel of its OWN under the price,
     * and there was no stack of panels to put it in. Indicators drawn on the
     * price went in, indicators with their own scale silently had nowhere to
     * go.</p>
     *
     * <p>The same {@code ChartPane} the chart window uses — not a second
     * assembly beside it. It carries the studies, the legend, the layout bar and
     * the way in for new indicators, and it is keyed separately so the backtest
     * keeps its own layouts rather than sharing a chart window's.</p>
     */
    private final ChartPane pane = new ChartPane(chart, "backtest.chart");

    private final TradeMarks marks = new TradeMarks();

    private final StrategyCurves curves = new StrategyCurves();

    private final TradeTableModel model = new TradeTableModel(new String[] {
            Messages.get("backtest.col.index"), Messages.get("backtest.col.side"),
            Messages.get("backtest.col.opened"), Messages.get("backtest.col.closed"),
            Messages.get("backtest.col.entry"), Messages.get("backtest.col.exit"),
            Messages.get("backtest.col.points"), Messages.get("backtest.col.contracts"),
            Messages.get("backtest.col.turned"), Messages.get("backtest.col.bars"),
            Messages.get("backtest.col.cost"), Messages.get("backtest.col.net"),
    });

    private final JTable table = new JTable(model);

    /**
     * How far the run has got, and what it is doing.
     *
     * <p>Determinate only for the part that can be counted. Opening the series,
     * cutting the recorte and walking the ticks are steps that cannot say how
     * far along they are, and a bar that <b>pretends</b> to know is worse than
     * one that says it does not: the reader learns to distrust the number.</p>
     */
    private final JProgressBar progress = new JProgressBar(0, 100);

    /** What the bar says it is doing, kept so the percentage can join it. */
    private transient String stage;

    /**
     * Asked by the run, set by the button.
     *
     * <p>Volatile because two threads share it and nothing else does: the
     * interface thread writes it when the reader presses the button, and the
     * worker reads it a few times a second from inside the engine.</p>
     */
    private volatile boolean stopping;

    /** The run in flight, or null when there is none. */
    private transient SwingWorker<Run, String> worker;

    /** The bars on screen: the recorte at the chosen scale. */
    private transient PriceSeries running;

    /**
     * The bars the run executed on, which the fills are numbered against.
     *
     * <p>The same object as {@link #running} whenever the run executed on the
     * chart's own candles, and four and a half million tick bars when it did
     * not.</p>
     */
    private transient PriceSeries walked;

    BacktestPanel() {
        super(new BorderLayout(0, 6));

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        for (StrategyKind kind : StrategyKind.available()) {
            strategy.addItem(kind);
        }

        for (SeriesChoice choice : SeriesChoice.available()) {
            series.addItem(choice);
        }

        slice.setRenderer(new javax.swing.DefaultListCellRenderer() {

            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean selected, boolean focused) {
                super.getListCellRendererComponent(list, value, index, selected, focused);

                if (value instanceof Slice which) {
                    setText(Messages.get(which.key()));
                }

                return this;
            }
        });

        slice.setSelectedItem(Slice.ALL);
        how.setSelectedItem(Execution.TICKS);

        // ANTES do restorePicks, e nao depois. O padrao tem de estar posto para
        // que a preferencia guardada tenha o que sobrescrever -- invertido, era
        // o padrao que sobrescrevia a preferencia, e o recorte escolhido nunca
        // voltava. Um ajuste que so e lido depois de reabrir a janela falha
        // calado e parece que funciona.
        restorePicks();

        series.addActionListener(e -> {
            followTheSeries();
            rememberPicks();
        });

        slice.addActionListener(e -> {
            followTheSlice();
            rememberPicks();
        });

        how.addActionListener(e -> {
            followTheSeries();
            rememberPicks();
        });

        from.onChange(this::rememberPicks);
        to.onChange(this::rememberPicks);

        scale.addActionListener(e -> rememberPicks());
        strategy.addActionListener(e -> rememberPicks());

        followTheSeries();
        followTheSlice();

        // ONE BUTTON, TWO STATES. A separate Cancel sitting greyed out most
        // of the time is a control that spends its life saying nothing; the
        // button that started the run is the one the hand is already on.
        run.addActionListener(e -> {
            if (worker == null) {
                start();
            } else {
                stop();
            }
        });

        showQuadro.setToolTipText(Messages.get("backtest.quadro.hint"));
        showQuadro.setFocusable(false);
        showQuadro.addActionListener(e -> toggleQuadro());

        progress.setFocusable(false);

        add(commands(), BorderLayout.NORTH);
        add(body(), BorderLayout.CENTER);
    }

    // ------------------------------------------------------------------ tela

    private JPanel commands() {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));

        JPanel first = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        first.add(new JLabel(Messages.get("backtest.series")));
        first.add(series);
        first.add(new JLabel(Messages.get("backtest.execution")));
        first.add(how);
        first.add(new JLabel(Messages.get("backtest.scale")));
        first.add(scale);
        first.add(more);
        first.add(new JLabel(Messages.get("backtest.slice")));
        first.add(slice);
        first.add(between);
        first.add(from);
        first.add(to);

        JPanel second = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        second.add(new JLabel(Messages.get("backtest.strategy")));
        second.add(strategy);
        second.add(settings);
        second.add(Box.createHorizontalStrut(8));
        second.add(showQuadro);
        second.add(run);

        top.add(first);
        top.add(second);
        top.add(pace());

        return top;
    }

    /**
     * The progress bar: under the controls, directly over the chart.
     *
     * <p>It stays in the layout whether a run is happening or not. Hiding it
     * between runs would move the chart up and down by its own height every
     * time the reader pressed Run, and a chart that jumps is a chart you lose
     * your place in — so what changes is what the bar SAYS, not whether it is
     * there.</p>
     */
    private JPanel pace() {
        JPanel row = new JPanel(new BorderLayout()) {

            private static final long serialVersionUID = 1L;

            @Override
            public java.awt.Dimension getMaximumSize() {
                // A BoxLayout on the Y axis stretches a child up to its maximum,
                // and a JPanel's maximum is unbounded in BOTH directions -- so
                // without this the bar would swallow every pixel the command bar
                // did not use, which on a tall window is most of it.
                return new java.awt.Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };

        row.setBorder(BorderFactory.createEmptyBorder(1, 6, 3, 6));
        row.add(progress, BorderLayout.CENTER);

        return row;
    }

    /** Puts the bar to work, saying what it cannot yet measure. */
    private void say(String what) {
        stage = what;

        progress.setStringPainted(true);
        progress.setString(what);
    }

    private void reached(int percent) {
        progress.setIndeterminate(false);
        progress.setValue(percent);
        progress.setString(stage == null ? percent + "%" : stage + " — " + percent + "%");
    }

    /**
     * Asks the run to stop, which is all anyone can do.
     *
     * <p>Java has no safe way to stop a thread from outside, so the engine asks
     * — a few times a second — and unwinds itself. The button goes dead in the
     * meantime rather than saying "Cancelar" to someone who already pressed it.
     */
    private void stop() {
        stopping = true;

        run.setEnabled(false);
        say(Messages.get("backtest.cancelling"));
    }

    /** Leaves the bar saying so, instead of quietly emptying it. */
    private void gaveUp() {
        stage = null;

        progress.setIndeterminate(false);
        progress.setValue(0);
        progress.setStringPainted(true);
        progress.setString(Messages.get("backtest.cancelled"));
    }

    /**
     * Empties the bar.
     *
     * <p>The string goes with it rather than staying at "100%": a full bar left
     * over from the last run, above a chart showing that run, reads as a run
     * still happening.</p>
     */
    private void idle() {
        stage = null;

        progress.setIndeterminate(false);
        progress.setStringPainted(false);
        progress.setString(null);
        progress.setValue(0);
    }

    /**
     * Puts the command bar back the way it was left.
     *
     * <p>Run twice on the same series and the second run should be one button
     * press, not four. And the window comes back with the application now, so
     * coming back empty would undo exactly what that is for.</p>
     *
     * <p>Silently skips anything that is no longer there — a series that was
     * retired, or a segment that was deleted, leaves the list on its first
     * entry rather than on nothing.</p>
     */
    private void restorePicks() {
        Settings prefs = Settings.workspace();
        String wanted = prefs.get("backtest.pick.series", null);

        for (int i = 0; wanted != null && i < series.getItemCount(); i++) {
            if (series.getItemAt(i).key().equals(wanted)) {
                series.setSelectedIndex(i);

                break;
            }
        }

        restoreTheScale(prefs.get("backtest.pick.scale", null));

        String wantedHow = prefs.get("backtest.pick.execution", null);

        for (Execution each : Execution.values()) {
            if (each.name().equals(wantedHow)) {
                how.setSelectedItem(each);

                break;
            }
        }

        String wantedSlice = prefs.get("backtest.pick.slice", null);

        for (Slice each : Slice.values()) {
            if (each.name().equals(wantedSlice)) {
                slice.setSelectedItem(each);

                break;
            }
        }

        String wantedStrategy = prefs.get("backtest.pick.strategy", null);

        for (int i = 0; wantedStrategy != null && i < strategy.getItemCount(); i++) {
            if (strategy.getItemAt(i).label().equals(wantedStrategy)) {
                strategy.setSelectedIndex(i);

                break;
            }
        }
    }

    private void rememberPicks() {
        Settings prefs = Settings.workspace();

        prefs.hold(() -> {
            SeriesChoice picked = (SeriesChoice) series.getSelectedItem();
            Scale chosen = (Scale) scale.getSelectedItem();

            if (picked != null) {
                prefs.put("backtest.pick.series", picked.key());
            }

            if (chosen != null) {
                prefs.put("backtest.pick.scale", chosen.id());
            }

            StrategyKind kind = (StrategyKind) strategy.getSelectedItem();

            if (kind != null) {
                prefs.put("backtest.pick.strategy", kind.label());
            }

            Slice which = (Slice) slice.getSelectedItem();

            if (which != null) {
                prefs.put("backtest.pick.slice", which.name());
            }

            Execution mode = (Execution) how.getSelectedItem();

            if (mode != null) {
                prefs.put("backtest.pick.execution", mode.name());
            }
        });
    }

    /**
     * Keeps the scale list honest about the series just chosen.
     *
     * <p>A renko series has no clock to aggregate by, so the list is disabled
     * and snapped back to "as stored" — rather than left showing 5m over bars
     * that are not minutes.</p>
     */
    private void followTheSeries() {
        SeriesChoice chosen = (SeriesChoice) series.getSelectedItem();

        // THE EXECUTION MODE DOES NOT TOUCH THE SCALE. It used to turn the list
        // off in tick mode, on the grounds that the tick path was measured on
        // the shape of a MINUTE -- which is true, and is answered by building
        // the path from the stored bars rather than from the aggregated ones.
        // The scale is the chart's, and the chart is his to set.
        //
        // What still turns it off is a series with no clock to aggregate by: a
        // renko brick is not a length of time.
        boolean byTheClock = chosen != null && chosen.measuredInTime();

        scale.setEnabled(byTheClock);
        more.setEnabled(byTheClock);

        if (!byTheClock) {
            scale.setSelectedIndex(0);
        }

        offerTheSessionsOf(chosen);
    }

    /**
     * Shows the two date fields only when there is a date to pick.
     *
     * <p>Two boxes that do nothing for twelve of the thirteen slices are two
     * boxes the reader has to learn to ignore.</p>
     */
    private void followTheSlice() {
        boolean byHand = slice.getSelectedItem() == Slice.CHOSEN;

        between.setVisible(byHand);
        from.setVisible(byHand);
        to.setVisible(byHand);

        revalidate();
        repaint();
    }

    /**
     * Tells the date fields which days the chosen entry actually has.
     *
     * <p>The calendar greys out every day that is not one of them — that part
     * was already built. What was missing is the list: it was read from the
     * series NAME, and a segment's name carries a {@code #} that the catalog
     * cannot open, so the answer was empty and the fields fell back to "any
     * weekday". They offered days the segment does not contain, and a range
     * typed from them covered nothing.</p>
     *
     * <h2>Off the interface thread</h2>
     *
     * <p>The first read of six years of one-minute bars is most of a second.
     * Done here, that is the window freezing every time the reader opens the
     * series list — and a window that freezes while you browse is a window you
     * stop browsing.</p>
     *
     * <h2>The answer that arrives last is not always the one wanted</h2>
     *
     * <p>Two quick changes start two loads, and they can finish in either
     * order. Each carries the key it was asked for, and an answer for a series
     * that is no longer selected is dropped rather than applied.</p>
     */
    private void offerTheSessionsOf(SeriesChoice chosen) {
        if (chosen == null) {
            offer(new java.util.TreeSet<>());

            return;
        }

        String asked = chosen.key();

        new SwingWorker<java.util.NavigableSet<java.time.LocalDate>, Void>() {

            @Override
            protected java.util.NavigableSet<java.time.LocalDate> doInBackground() {
                return chosen.sessions();
            }

            @Override
            protected void done() {
                SeriesChoice now = (SeriesChoice) series.getSelectedItem();

                if (now == null || !asked.equals(now.key())) {
                    return;
                }

                try {
                    offer(get());
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException failed) {
                    offer(new java.util.TreeSet<>());
                }
            }
        }.execute();
    }

    /**
     * Hands the days to both fields and puts them on the ends.
     *
     * <p>ALWAYS on the ends, and not only when what is typed has gone invalid.
     * Choosing another series is choosing another stretch of history: leaving a
     * date from the previous one, valid by coincidence, is how a run silently
     * covers the wrong months.</p>
     */
    private void offer(java.util.NavigableSet<java.time.LocalDate> days) {
        from.setSessions(days);
        to.setSessions(days);

        if (days.isEmpty()) {
            return;
        }

        // QUIETLY: putting a value back is not the reader choosing it, and
        // saving it again from here is a write that can outlive the window.
        from.setQuietly(days.first());
        to.setQuietly(days.last());
    }

    /**
     * The chart over the table on the left, the quadro down the right.
     *
     * <p>Both dividers are draggable and both remember where they were left. The
     * quadro takes 330 pixels off the price chart, which in a small docked window
     * is a lot — so it is the reader who decides, and the decision survives the
     * window being closed.</p>
     */
    private JSplitPane body() {
        // AS CURVAS PRIMEIRO, as marcas por cima: as marcas sao o assunto e
        // as linhas sao o porque. Na ordem inversa uma media passaria por cima
        // da seta que ela explica.
        chart.addOverlay(curves);
        chart.addOverlay(marks);
        pane.setPreferredSize(new Dimension(700, 320));

        JPanel graph = new JPanel(new BorderLayout());

        // THE LAYOUT BAR UNDERNEATH, which is where the chart window puts it.
        // Above the chart it sat between the command bar and the price, where
        // it reads as another row of controls for the run rather than as the
        // chart's own tabs.
        graph.add(pane, BorderLayout.CENTER);
        graph.add(pane.layouts(), BorderLayout.SOUTH);

        JSplitPane rows = new JSplitPane(JSplitPane.VERTICAL_SPLIT, graph, tradeTable());

        rows.setResizeWeight(0.62);
        rows.setBorder(BorderFactory.createEmptyBorder());
        remember(rows, "backtest.split.rows");

        quadro.setBorder(BorderFactory.createEmptyBorder());
        quadro.getVerticalScrollBar().setUnitIncrement(16);

        sides.setLeftComponent(rows);
        sides.setRightComponent(quadro);

        // ONE, not zero: the quadro keeps its width and the chart takes every
        // pixel the window gains. A quadro that grew with the window would push
        // the numbers apart and leave the chart no better off.
        sides.setResizeWeight(1.0);
        sides.setBorder(BorderFactory.createEmptyBorder());

        // AT THE FIRST LAYOUT, and not in an invokeLater. The pane has no width
        // until it is laid out, and the first version worked the position out
        // before that: getWidth() was zero, the location came out negative,
        // Swing clamped it, and the quadro opened over the whole window. A
        // component listener fires when there IS a width, which is the only
        // moment the arithmetic means anything.
        sides.addComponentListener(new java.awt.event.ComponentAdapter() {

            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                if (!placed) {
                    putTheDividerBack();
                }
            }
        });

        sides.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> keepTheWidth());

        return sides;
    }

    /**
     * Hides the quadro, or brings it back the width it had.
     *
     * <p>Written by hand rather than with {@code setOneTouchExpandable}, which
     * is what was here and is what the reader hit: its two arrows send the
     * divider to the two <b>extremes</b>, so the click that brings the quadro
     * back gives it the whole window instead of the width it had. Hiding a
     * panel and restoring it are not the same gesture as dragging a divider all
     * the way over, and the built-in control cannot tell them apart.</p>
     */
    private void toggleQuadro() {
        boolean wanted = showQuadro.isSelected();

        quadro.setVisible(wanted);
        sides.setDividerSize(wanted ? new JSplitPane().getDividerSize() : 0);

        sides.revalidate();

        if (wanted) {
            javax.swing.SwingUtilities.invokeLater(this::putTheDividerBack);
        }
    }

    /**
     * Where the divider goes, in pixels from the left.
     *
     * <p>Computed from the width the pane HAS, and only once it has one. The
     * first version worked it out inside an {@code invokeLater} that could run
     * before the pane was laid out: {@code getWidth()} was zero, the location
     * came out negative, Swing clamped it to nothing, and the quadro opened
     * over the whole window. That is the same symptom from the other
     * direction.</p>
     */
    private void putTheDividerBack() {
        int width = sides.getWidth();

        if (width <= 0) {
            // NOT LAID OUT YET, and the flag stays down. A resize event can
            // arrive with no width -- a pane inside a collapsed split, a window
            // opening -- and marking it placed there would spend the one chance
            // this has to run: the divider would never be set at all, and the
            // quadro would open over the whole window. Which is the defect this
            // whole method exists to have fixed.
            return;
        }

        placed = true;

        sides.setDividerLocation(Math.max(LEAST_CHART, width - quadroWidth()));
    }

    private int quadroWidth() {
        return Math.max(LEAST_QUADRO,
                Settings.workspace().getInt("backtest.quadro.width", WANTED_QUADRO));
    }

    /**
     * Writes down how wide the quadro is, when that is a width worth keeping.
     *
     * <p>Every guard here earned its place. The divider reads {@code -1} until
     * the pane is laid out, and {@code getWidth() - (-1)} is one pixel WIDER
     * than the whole pane: stored, it came back as "the quadro wants 1169 of
     * 1168", the divider was clamped to its minimum, and the quadro filled the
     * window. That is the defect the reader saw, and it was written by the line
     * that was supposed to remember his preference.</p>
     */
    private void keepTheWidth() {
        if (!placed || !quadro.isVisible()) {
            return;
        }

        // WRITTEN AS IT COMES. There were two guards here on the way in --
        // against a span of zero, and against a width outside the two minimums
        // -- and neither could be made to fire: the divider cannot be dragged
        // past a component's minimum size, so Swing refuses the absurd drag
        // before this line ever sees it, and the pane with no width is stopped
        // by the flag above.
        //
        // What protects the screen is the guard on the way OUT, in
        // quadroWidth() and in putTheDividerBack(), and that one is provable:
        // a workspace file with a silly number in it still opens a sane window.
        // Two guards where one can fire is one guard and one decoration.
        Settings.workspace().putInt("backtest.quadro.width",
                sides.getWidth() - sides.getDividerLocation());
    }

    /**
     * Keeps a divider where the reader left it.
     *
     * <p>Nothing is written while either side is too small to be a side. That
     * guard is the other half of the collapse defect: a panel driven to an
     * extreme wrote the extreme down, so the position survived the restart and
     * the window came back broken.</p>
     *
     * <p>And nothing is set at birth unless something was saved. With no
     * location, the split honours the preferred sizes of its two halves — which
     * is exactly the layout wanted, arrived at without any arithmetic that can
     * run before the pane has a width.</p>
     */
    private void remember(JSplitPane split, String key) {
        Settings prefs = Settings.workspace();
        int saved = prefs.getInt(key, Integer.MIN_VALUE);

        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, event -> {
            int where = split.getDividerLocation();
            int span = split.getOrientation() == JSplitPane.HORIZONTAL_SPLIT
                    ? split.getWidth() : split.getHeight();

            if (quadro.isVisible() && where >= LEAST_CHART && span - where >= LEAST_QUADRO) {
                prefs.putInt(key, where);
            }
        });

        if (saved > 0) {
            javax.swing.SwingUtilities.invokeLater(() -> split.setDividerLocation(saved));
        }
    }

    private JScrollPane tradeTable() {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setReorderingAllowed(false);

        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {

            private static final long serialVersionUID = 1L;

            @Override
            public Component getTableCellRendererComponent(JTable owner, Object value,
                    boolean selected, boolean focused, int row, int column) {
                Component c = super.getTableCellRendererComponent(
                        owner, value, selected, focused, row, column);

                setHorizontalAlignment(RIGHT);

                if (value instanceof Double number) {
                    setText(String.format("%,.1f", number));
                }

                return c;
            }
        };

        for (int i = 0; i < model.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setCellRenderer(right);
        }

        // DOIS CLIQUES ABREM OS GIROS. A tabela da uma entrada e uma saida, que
        // para uma operacao que entrou uma vez e saiu uma vez e a historia toda
        // -- e para uma que subiu em cinco lotes e saiu em seis pedacos e uma
        // media de vinte e seis historias.
        table.addMouseListener(new java.awt.event.MouseAdapter() {

            @Override
            public void mouseClicked(java.awt.event.MouseEvent clicked) {
                if (clicked.getClickCount() == 2) {
                    detail(table.rowAtPoint(clicked.getPoint()));
                }
            }
        });

        table.getSelectionModel().addListSelectionListener(event -> {
            if (event.getValueIsAdjusting()) {
                return;
            }

            chose(table.getSelectedRow());
        });

        return new JScrollPane(table);
    }

    // ------------------------------------------------------------------ rodar

    private void start() {
        SeriesChoice picked = (SeriesChoice) series.getSelectedItem();

        if (picked == null) {
            result.clear();

            return;
        }

        Scale chosen = (Scale) scale.getSelectedItem();
        boolean tickMode = how.getSelectedItem() == Execution.TICKS;

        // READ AT THE MOMENT OF THE RUN, not held from when the window opened.
        // The settings dialog can have been through twice since then, and a run
        // charged yesterday's cost is the kind of wrong that looks ordinary.
        int lot = BacktestPreferences.contracts();
        Costs charged = BacktestPreferences.costs();

        StrategyKind kind = (StrategyKind) strategy.getSelectedItem();

        if (kind == null) {
            result.clear();

            return;
        }

        // BUILT NOW, from whatever its own screen last saved -- the dialog can
        // have been through twice since this window opened. And typed as
        // Strategy, so the instanceof further down means "does this one show
        // its working?" rather than a question the compiler already knows the
        // answer to.
        Strategy what = kind.build();

        stopping = false;

        run.setText(Messages.get("backtest.cancel"));

        progress.setIndeterminate(true);
        say(Messages.get("backtest.stage.opening"));

        worker = new SwingWorker<>() {

            @Override
            protected Run doInBackground() throws IOException {
                // THE RECORTE IS CUT BEFORE THE SCALE. Aggregating six years
                // to five minutes and then throwing away all but a week is six
                // years of work for a week of bars -- and the last coarse bar of
                // the cut would be built from minutes the run then never sees.
                PriceSeries cut = cutTo(picked.open());

                publish(Messages.get(tickMode
                        ? "backtest.stage.ticks" : "backtest.stage.running"));

                // THE TICKS COME FROM THE STORED BARS, never from the scaled
                // ones. The path was measured on the shape of a minute; walking
                // one inside a five-minute bar would be applying those
                // statistics where they were never measured. This is what lets
                // the chart be read at any scale while the execution stays what
                // it is.
                //
                // After the recorte, though, and never before it: fifteen
                // hundred ticks a minute is not a cost to pay for a stretch
                // nobody asked for.
                TickLevel.Walked ticks = tickMode
                        ? TickLevel.of(picked, cut, Timeframe.defaultZone())
                        : null;

                // THE SCALE MAKES THE DECISION BARS -- the ones the chart draws
                // and the ones the strategy reads.
                //
                // AND A BRICK IS BUILT FROM THE TICKS, where a minute is built
                // from the stored bars. Not a preference: a renko brick closes
                // the moment a trade prints past the level, so several of them
                // can close inside one minute -- and built from minutes they
                // would all carry that minute's timestamp. The engine lines the
                // decision series up against the executed one BY TIME, so a
                // cluster of bricks sharing an instant collapses: the strategy
                // is never asked on the earlier ones, and the orders of one
                // would meet the tick of another. Built from the ticks, each
                // brick closes on the tick that closed it and has that tick's
                // own instant.
                PriceSeries decided = chosen.how()
                        .apply(chosen.isRenko() && ticks != null ? ticks.series() : cut);

                TickLevel.Walked walked = ticks != null
                        ? ticks : new TickLevel.Walked(decided, false, 0);

                publish(Messages.get("backtest.stage.running"));

                // SIX DECIMAL PLACES OF A PERCENT THROWN AWAY, on purpose: the
                // worker only fires its listeners when the whole number changes,
                // so seventeen million bars move the screen a hundred times and
                // not seventeen million.
                Result produced = new Backtest(charged, lot).run(walked.series(), decided,
                        what, new Watching() {

                            @Override
                            public void at(int reached, int many) {
                                setProgress((int) Math.min(100L,
                                        100L * reached / Math.max(1, many)));
                            }

                            @Override
                            public boolean cancelled() {
                                return stopping;
                            }
                        });

                return new Run(decided, walked.series(), produced, picked.label(),
                        what instanceof br.com.jorge.reis.endeavourneo.domain.trading.Plotted shown
                                ? shown.curves() : java.util.Map.of(),
                        tickMode, walked.real(), walked.ticks());
            }

            @Override
            protected void process(java.util.List<String> stages) {
                say(stages.get(stages.size() - 1));
            }

            @Override
            protected void done() {
                worker = null;

                run.setEnabled(true);
                run.setText(Messages.get("backtest.run"));

                if (stopping) {
                    // WHAT COMES BACK FROM A CANCELLED RUN IS NOT SHOWN. It is
                    // half a run: the curves stop in the middle, the drawdown is
                    // of a stretch nobody asked for, and buy-and-hold is of the
                    // whole one. The chart keeps what it had, and the bar says
                    // what happened.
                    gaveUp();

                    return;
                }

                idle();

                try {
                    show(get());
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    failed(Messages.get("backtest.failed"));
                } catch (Exception failed) {
                    // The message, not a stack trace in a label: the reader gets
                    // "no such file", and the console has the rest.
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();

                    failed(Messages.get("backtest.failed") + " " + cause.getMessage());
                    cause.printStackTrace();
                }
            }
        };

        // THE PERCENTAGE ARRIVES AS A PROPERTY, which is how SwingWorker hands
        // it over -- already on the interface thread, and only when the whole
        // number changed. Reading it here rather than calling the panel from
        // inside doInBackground is what keeps the worker from touching Swing at
        // all.
        worker.addPropertyChangeListener(changed -> {
            if ("progress".equals(changed.getPropertyName())) {
                reached((Integer) changed.getNewValue());
            }
        });

        worker.execute();
    }

    private void show(Run finished) {
        running = finished.series();

        Result result = finished.result();
        List<Trade> trades = result.trades();

        walked = finished.walked();

        chart.setSeries(running);

        // BEFORE ANYTHING READS THEM. A study still holding the values of the
        // previous run would draw a shape that never happened -- on a chart
        // whose candles are already the new ones.
        pane.recalculate();

        curves.show(finished.curves());

        marks.show(trades);
        marks.highlight(null);

        model.show(trades, running);

        this.result.show(finished.result(), Metrics.of(finished.result(), running),
                finished.label(), howItRan(finished));

        chart.goToEnd();
    }

    /**
     * Applies the recorte to the series the reader picked.
     *
     * <p>To the series ALREADY cut to its segment, which is what makes the rule
     * free: a recorte lives inside a segment and never spans two, and there is
     * no way here to ask for the other thing.</p>
     */
    private PriceSeries cutTo(PriceSeries whole) {
        Slice which = (Slice) slice.getSelectedItem();

        if (which == null) {
            return whole;
        }

        return which.handPicked()
                ? Slice.between(whole, from.date(), to.date(), Timeframe.defaultZone())
                : which.cut(whole, Timeframe.defaultZone());
    }

    /**
     * @return what the quadro shows under "executado", which is evidence and not
     *         decoration: a stop hit on the tape WAS hit, and one hit on a
     *         synthetic path was hit by one of the paths that minute could have
     *         taken
     */
    private static String howItRan(Run finished) {
        if (!finished.tickMode()) {
            return Messages.get("backtest.execution.ohlc");
        }

        return Messages.get(finished.realTicks()
                ? "backtest.ticks.real" : "backtest.ticks.synthetic")
                + String.format("  (%,d)", finished.ticks());
    }

    /** Says it out loud and empties the quadro, so no stale number is read. */
    private void failed(String why) {
        result.clear();

        JOptionPane.showMessageDialog(this, why, Messages.get("backtest.title"),
                JOptionPane.WARNING_MESSAGE);
    }

    /** Opens one operation taken apart, or does nothing when there is none. */
    private void detail(int row) {
        Trade trade = model.at(row < 0 ? -1 : table.convertRowIndexToModel(row));

        if (trade == null) {
            return;
        }

        TradeDetail.show(this, trade, row + 1, running,
                BacktestPreferences.pointValue());
    }

    private void chose(int row) {
        if (row < 0) {
            marks.highlight(null);
            result.highlight(-1);
            chart.repaint();

            return;
        }

        int index = table.convertRowIndexToModel(row);
        Trade trade = model.at(index);

        marks.highlight(trade);

        if (trade != null) {
            result.highlight(trade.closedAt());

            // Halfway through the operation, so both ends have a chance of
            // being on screen for a short one.
            chart.showBar((trade.openedAt() + trade.closedAt()) / 2);
        }
    }

    /**
     * A finished run: the bars it ran over, what it produced, and what to call it.
     *
     * @param series the decision bars — what the chart draws and what the
     *               strategy read
     * @param walked the executed bars, which the fills are numbered against; the
     *               same object as {@code series} when the run executed on the
     *               chart's own candles
     */
    private record Run(PriceSeries series, PriceSeries walked, Result result, String label,
                       java.util.Map<String, double[]> curves,
                       boolean tickMode, boolean realTicks, int ticks) {
    }

    /**
     * One entry of the scale list.
     *
     * <p>A thin wrapper over {@link PeriodCatalog.Choice} and deliberately
     * nothing more: the periods this program offers are defined <b>once</b>, in
     * the catalogue the chart's period box reads, and this window fills its list
     * from there. It used to hold an array of its own — five entries against the
     * catalogue's fourteen — which is why a run could not be put on a renko, and
     * why the same scale could have been spelled two ways in two windows.</p>
     *
     * <p>The one entry the catalogue cannot supply is the first: <b>as
     * stored</b>. It is not a period at all, it is the absence of one, and a
     * chart always has a period while a run need not aggregate anything.</p>
     *
     * @param chosen the period, or null for "as stored"
     */
    record Scale(PeriodCatalog.Choice chosen) {

        /** The bars as the series keeps them, aggregated by nothing. */
        static Scale stored() {
            return new Scale(null);
        }

        Aggregation how() {
            return chosen == null ? Aggregation.none() : chosen.aggregation();
        }

        /** @return whether decision bars here are bricks rather than lengths of time */
        boolean isRenko() {
            return how() instanceof Renko;
        }

        /**
         * @return what remembers this choice across a restart
         *
         * <p>The CODE, never {@link #toString()}. The printed name is
         * translated, so a workspace saved in Portuguese would reopen on the
         * default in English — and the code is also what {@link
         * PeriodCatalog#byCode} reads, which is what lets a period typed into
         * the dialog come back after a restart instead of only the six that
         * happen to be listed.</p>
         */
        String id() {
            return chosen == null ? STORED : chosen.code();
        }

        @Override
        public String toString() {
            // The catalogue's own words, so a scale reads the same here as in
            // the period box: "11R - 50 pts", "5m - 5 minutos".
            return chosen == null
                    ? Messages.get("backtest.asStored")
                    : chosen.title() + " - " + chosen.description();
        }
    }
}
