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

    private final ChartCanvas chart = new ChartCanvas();

    private final TradeMarks marks = new TradeMarks();

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

        series.addActionListener(e -> followTheSeries());

        followTheSeries();

        run.addActionListener(e -> start());

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
        second.add(settings);
        second.add(run);

        top.add(first);
        top.add(second);

        return top;
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
        chart.addOverlay(marks);
        chart.setPreferredSize(new Dimension(700, 320));

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, tradeTable());

        left.setResizeWeight(0.62);
        left.setBorder(BorderFactory.createEmptyBorder());
        remember(left, "backtest.split.rows", 320);

        JScrollPane quadro = new JScrollPane(result,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        quadro.setBorder(BorderFactory.createEmptyBorder());
        quadro.getVerticalScrollBar().setUnitIncrement(16);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, quadro);

        // ONE, not zero: the quadro keeps its width and the chart takes every
        // pixel the window gains. A quadro that grew with the window would push
        // the numbers apart and leave the chart no better off.
        split.setResizeWeight(1.0);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setOneTouchExpandable(true);
        remember(split, "backtest.split.quadro", -330);

        return split;
    }

    /**
     * Keeps a divider where the reader left it.
     *
     * @param fallback where it starts; negative means "that far from the end"
     */
    private static void remember(JSplitPane split, String key, int fallback) {
        Settings prefs = Settings.workspace();
        int saved = prefs.getInt(key, Integer.MIN_VALUE);

        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY,
                event -> prefs.putInt(key, split.getDividerLocation()));

        javax.swing.SwingUtilities.invokeLater(() -> {
            if (saved != Integer.MIN_VALUE && saved > 0) {
                split.setDividerLocation(saved);
            } else if (fallback < 0) {
                split.setDividerLocation(split.getWidth() + fallback);
            } else {
                split.setDividerLocation(fallback);
            }
        });
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

        MovingAverageCrossing what = new MovingAverageCrossing(
                (Integer) fast.getValue(), (Integer) slow.getValue(), lot);

        run.setEnabled(false);
        run.setText(Messages.get("backtest.running"));

        new SwingWorker<Run, Void>() {

            @Override
            protected Run doInBackground() throws IOException {
                PriceSeries bars = chosen.how().apply(picked.open());

                return new Run(bars, new Backtest(charged, lot).run(bars, what), picked.label());
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
    private record Run(PriceSeries series, Result result, String label) {
    }

    /** One entry of the scale list: what it is called, and what it does. */
    private record Scale(String key, Aggregation how) {

        @Override
        public String toString() {
            return Messages.get(key);
        }
    }
}
