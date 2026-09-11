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
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Metrics;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.Strategy;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.MovingAverageCrossing;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.IOException;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
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

    /**
     * The scales a run may be read at.
     *
     * <p>The first is "as stored", and it is the default. A series already kept
     * at five minutes does not need aggregating, and a renko one must not be:
     * the list is disabled entirely when the series is not measured in time.</p>
     */
    private static final Scale[] SCALES = {
            new Scale("backtest.asStored", Aggregation.none()),
            new Scale("backtest.scale.5m", Timeframe.FIVE_MINUTES),
            new Scale("backtest.scale.15m", Timeframe.FIFTEEN_MINUTES),
            new Scale("backtest.scale.30m", Timeframe.THIRTY_MINUTES),
            new Scale("backtest.scale.1h", Timeframe.ONE_HOUR),
    };

    private final JComboBox<SeriesChoice> series = new JComboBox<>();

    private final JComboBox<Scale> scale = new JComboBox<>(SCALES);

    private final JComboBox<String> strategy = new JComboBox<>();

    private final JSpinner fast = new JSpinner(new SpinnerNumberModel(17, 1, 999, 1));

    private final JSpinner slow = new JSpinner(new SpinnerNumberModel(34, 2, 999, 1));

    private final JButton settings = gear();

    private final JButton run = new JButton(Messages.get("backtest.run"));

    /**
     * The door to Configurações > Backtest, next to the button that needs it.
     *
     * <p>The same page the menu opens and the same stored values — this one just
     * saves the walk. A reader about to press Run is exactly the reader who
     * wants to check what a round trip is being charged.</p>
     */
    private JButton gear() {
        JButton button = new JButton("⚙");

        button.setToolTipText(Messages.get("settings.backtest"));
        button.setFocusable(false);
        button.addActionListener(e -> br.com.jorge.reis.endeavourneo.ui.settings.SettingsDialog
                .show(javax.swing.SwingUtilities.getWindowAncestor(this),
                        java.util.List.of(
                                new br.com.jorge.reis.endeavourneo.ui.settings.BacktestPage())));

        return button;
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

    private final TradeMarks marks = new TradeMarks();

    private final StrategyCurves curves = new StrategyCurves();

    private final TradeTableModel model = new TradeTableModel(new String[] {
            Messages.get("backtest.col.index"), Messages.get("backtest.col.side"),
            Messages.get("backtest.col.opened"), Messages.get("backtest.col.closed"),
            Messages.get("backtest.col.entry"), Messages.get("backtest.col.exit"),
            Messages.get("backtest.col.points"), Messages.get("backtest.col.contracts"),
            Messages.get("backtest.col.bars"), Messages.get("backtest.col.cost"),
            Messages.get("backtest.col.net"),
    });

    private final JTable table = new JTable(model);

    private transient PriceSeries running;

    BacktestPanel() {
        super(new BorderLayout(0, 6));

        setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        strategy.addItem(Messages.get("backtest.strategy.crossing"));

        for (SeriesChoice choice : SeriesChoice.available()) {
            series.addItem(choice);
        }

        restorePicks();

        series.addActionListener(e -> {
            followTheSeries();
            rememberPicks();
        });

        scale.addActionListener(e -> rememberPicks());
        fast.addChangeListener(e -> rememberPicks());
        slow.addChangeListener(e -> rememberPicks());

        followTheSeries();

        run.addActionListener(e -> start());

        showQuadro.setToolTipText(Messages.get("backtest.quadro.hint"));
        showQuadro.setFocusable(false);
        showQuadro.addActionListener(e -> toggleQuadro());

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
        first.add(new JLabel(Messages.get("backtest.scale")));
        first.add(scale);

        JPanel second = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        second.add(new JLabel(Messages.get("backtest.strategy")));
        second.add(strategy);
        second.add(new JLabel(Messages.get("backtest.fast")));
        second.add(fast);
        second.add(new JLabel(Messages.get("backtest.slow")));
        second.add(slow);
        second.add(Box.createHorizontalStrut(8));
        second.add(showQuadro);
        second.add(settings);
        second.add(run);

        top.add(first);
        top.add(second);

        return top;
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

        String wantedScale = prefs.get("backtest.pick.scale", null);

        for (int i = 0; wantedScale != null && i < scale.getItemCount(); i++) {
            if (scale.getItemAt(i).key().equals(wantedScale)) {
                scale.setSelectedIndex(i);

                break;
            }
        }

        fast.setValue(prefs.getInt("backtest.pick.fast", 17));
        slow.setValue(prefs.getInt("backtest.pick.slow", 34));
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
                prefs.put("backtest.pick.scale", chosen.key());
            }

            prefs.putInt("backtest.pick.fast", (Integer) fast.getValue());
            prefs.putInt("backtest.pick.slow", (Integer) slow.getValue());
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
        boolean byTheClock = chosen != null && chosen.measuredInTime();

        scale.setEnabled(byTheClock);

        if (!byTheClock) {
            scale.setSelectedIndex(0);
        }
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
        chart.setPreferredSize(new Dimension(700, 320));

        JSplitPane rows = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, tradeTable());

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
                if (!placed && sides.getWidth() > 0) {
                    placed = true;

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
            // Not laid out yet. Leaving it alone is right: with no location
            // set, the split honours the preferred widths, which is where the
            // quadro wanted to be anyway.
            return;
        }

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

        int span = sides.getWidth();
        int where = sides.getDividerLocation();

        if (span <= 0 || where < LEAST_CHART) {
            return;
        }

        int width = span - where;

        if (width >= LEAST_QUADRO && width <= span - LEAST_CHART) {
            Settings.workspace().putInt("backtest.quadro.width", width);
        }
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

        // READ AT THE MOMENT OF THE RUN, not held from when the window opened.
        // The settings dialog can have been through twice since then, and a run
        // charged yesterday's cost is the kind of wrong that looks ordinary.
        int lot = BacktestPreferences.contracts();
        Costs charged = BacktestPreferences.costs();

        // Declarada como Strategy, e nao como a classe concreta: e assim que o
        // instanceof abaixo quer dizer alguma coisa -- "esta estrategia mostra
        // o que fez?" -- em vez de ser uma pergunta cuja resposta o compilador
        // ja sabe. A segunda estrategia que entrar aqui nao muda esta linha.
        Strategy what = new MovingAverageCrossing(
                (Integer) fast.getValue(), (Integer) slow.getValue(), lot);

        run.setEnabled(false);
        run.setText(Messages.get("backtest.running"));

        new SwingWorker<Run, Void>() {

            @Override
            protected Run doInBackground() throws IOException {
                PriceSeries bars = chosen.how().apply(picked.open());

                Result produced = new Backtest(charged, lot).run(bars, what);

                return new Run(bars, produced, picked.label(),
                        what instanceof br.com.jorge.reis.endeavourneo.domain.trading.Plotted shown
                                ? shown.curves() : java.util.Map.of());
            }

            @Override
            protected void done() {
                run.setEnabled(true);
                run.setText(Messages.get("backtest.run"));

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
        }.execute();
    }

    private void show(Run finished) {
        running = finished.series();

        Result result = finished.result();
        List<Trade> trades = result.trades();

        chart.setSeries(running);

        curves.show(finished.curves());
        marks.show(trades);
        marks.highlight(null);

        model.show(trades, running);

        this.result.show(finished.result(), Metrics.of(finished.result(), running),
                finished.label());

        chart.goToEnd();
    }

    /** Says it out loud and empties the quadro, so no stale number is read. */
    private void failed(String why) {
        result.clear();

        JOptionPane.showMessageDialog(this, why, Messages.get("backtest.title"),
                JOptionPane.WARNING_MESSAGE);
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

    /** A finished run: the bars it ran over, what it produced, and what to call it. */
    private record Run(PriceSeries series, Result result, String label,
                       java.util.Map<String, double[]> curves) {
    }

    /** One entry of the scale list: what it is called, and what it does. */
    private record Scale(String key, Aggregation how) {

        @Override
        public String toString() {
            return Messages.get(key);
        }
    }
}
