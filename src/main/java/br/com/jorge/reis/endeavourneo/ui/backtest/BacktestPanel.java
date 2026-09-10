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

import br.com.jorge.reis.endeavourneo.domain.market.MarketFile;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.domain.trading.strategy.MovingAverageCrossing;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingWorker;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Pick a series, pick a strategy, run it, and look at what it did.
 *
 * <p>Three things share this window and each answers something the others
 * cannot. The <b>summary</b> says whether it made money. The <b>curve</b> says
 * what shape that took — one lucky year, or a slope. The <b>price chart</b>,
 * with the operations marked on it, says <i>where</i>, and it is the only one
 * that can tell you the strategy only works in the first hour of the
 * session.</p>
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

    private static final Timeframe[] SCALES = {
            Timeframe.ONE_MINUTE, Timeframe.FIVE_MINUTES, Timeframe.FIFTEEN_MINUTES,
            Timeframe.THIRTY_MINUTES, Timeframe.ONE_HOUR,
    };

    private final JTextField file = new JTextField(34);

    private final JComboBox<Timeframe> scale = new JComboBox<>(SCALES);

    private final JComboBox<String> strategy = new JComboBox<>();

    private final JSpinner fast = new JSpinner(new SpinnerNumberModel(17, 1, 999, 1));

    private final JSpinner slow = new JSpinner(new SpinnerNumberModel(34, 2, 999, 1));

    private final JSpinner contracts = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));

    private final JSpinner cost = new JSpinner(new SpinnerNumberModel(6.5, 0.0, 100.0, 0.5));

    private final JButton run = new JButton(Messages.get("backtest.run"));

    private final JLabel summary = new JLabel(Messages.get("backtest.noRun"));

    private final ChartCanvas chart = new ChartCanvas();

    private final TradeMarks marks = new TradeMarks();

    private final EquityChart curve = new EquityChart();

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
        scale.setSelectedItem(Timeframe.FIVE_MINUTES);

        file.setText(firstSeries());

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
        first.add(file);
        first.add(browse());
        first.add(new JLabel(Messages.get("backtest.scale")));
        first.add(scale);

        JPanel second = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        second.add(new JLabel(Messages.get("backtest.strategy")));
        second.add(strategy);
        second.add(new JLabel(Messages.get("backtest.fast")));
        second.add(fast);
        second.add(new JLabel(Messages.get("backtest.slow")));
        second.add(slow);
        second.add(new JLabel(Messages.get("backtest.contracts")));
        second.add(contracts);
        second.add(new JLabel(Messages.get("backtest.cost")));
        second.add(cost);
        second.add(run);

        JPanel third = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        third.add(summary);

        top.add(first);
        top.add(second);
        top.add(third);

        return top;
    }

    private JButton browse() {
        JButton button = new JButton("...");

        button.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser(new File(file.getText()).getParentFile());

            chooser.setFileFilter(new FileNameExtensionFilter(
                    Messages.get("backtest.seriesFiles"), "bin"));

            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                file.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });

        return button;
    }

    private JSplitPane body() {
        chart.addOverlay(marks);
        chart.setPreferredSize(new Dimension(900, 320));

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab(Messages.get("backtest.tab.trades"), tradeTable());
        tabs.addTab(Messages.get("backtest.tab.curve"), curve);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, chart, tabs);
        split.setResizeWeight(0.55);
        split.setBorder(BorderFactory.createEmptyBorder());

        return split;
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
        Path path = Path.of(file.getText().trim());

        if (!Files.isRegularFile(path)) {
            summary.setText(Messages.get("backtest.noSeries"));

            return;
        }

        Timeframe chosen = (Timeframe) scale.getSelectedItem();
        MovingAverageCrossing what = new MovingAverageCrossing(
                (Integer) fast.getValue(), (Integer) slow.getValue(), (Integer) contracts.getValue());
        Costs charged = new Costs((Double) cost.getValue());

        run.setEnabled(false);
        summary.setText(Messages.get("backtest.running"));

        new SwingWorker<Run, Void>() {

            @Override
            protected Run doInBackground() throws IOException {
                PriceSeries bars = chosen.apply(MarketFile.read(path));

                return new Run(bars, new Backtest(charged, (Integer) contracts.getValue())
                        .run(bars, what));
            }

            @Override
            protected void done() {
                run.setEnabled(true);

                try {
                    show(get());
                } catch (InterruptedException stopped) {
                    Thread.currentThread().interrupt();
                    summary.setText(Messages.get("backtest.failed"));
                } catch (Exception failed) {
                    // The message, not a stack trace in a label: the reader gets
                    // "no such file", and the console has the rest.
                    Throwable cause = failed.getCause() == null ? failed : failed.getCause();

                    summary.setText(Messages.get("backtest.failed") + " " + cause.getMessage());
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
        curve.show(result.equity());

        summary.setText(sentence(result));

        chart.goToEnd();
    }

    private static String sentence(Result result) {
        return String.format(
                "%,d %s   ·   %s %,.0f   ·   %s %,.0f   ·   %s %,.2f   ·   %s %.1f%%"
                        + "   ·   %s %,.0f   ·   %s %,d",
                result.count(), Messages.get("backtest.trades"),
                Messages.get("backtest.net"), result.net(),
                Messages.get("backtest.costs"), result.cost(),
                Messages.get("backtest.perTrade"), result.perTrade(),
                Messages.get("backtest.hitRate"),
                100.0 * result.wins() / Math.max(1, result.count()),
                Messages.get("backtest.drawdown"), result.drawdown(),
                Messages.get("backtest.ambiguous"), result.ambiguousBars());
    }

    private void chose(int row) {
        if (row < 0) {
            marks.highlight(null);
            curve.highlight(-1);
            chart.repaint();

            return;
        }

        int index = table.convertRowIndexToModel(row);
        Trade trade = model.at(index);

        marks.highlight(trade);
        curve.highlight(index);

        if (trade != null) {
            // Halfway through the operation, so both ends have a chance of
            // being on screen for a short one.
            chart.showBar((trade.openedAt() + trade.closedAt()) / 2);
        }
    }

    /** @return the first series file under {@code data/}, or an empty string */
    private static String firstSeries() {
        Path data = Path.of("data");

        if (!Files.isDirectory(data)) {
            return "";
        }

        try (Stream<Path> found = Files.walk(data, 4)) {
            return found.filter(Files::isRegularFile)
                    .filter(MarketFile::isSeries)
                    .findFirst()
                    .map(Path::toAbsolutePath)
                    .map(Path::toString)
                    .orElse("");
        } catch (IOException unreadable) {
            return "";
        }
    }

    /** A finished run, and the bars it ran over. */
    private record Run(PriceSeries series, Result result) {
    }
}
