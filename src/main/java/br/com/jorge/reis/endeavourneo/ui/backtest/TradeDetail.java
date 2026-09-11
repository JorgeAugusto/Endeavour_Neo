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

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * One operation, taken apart: every execution that made it.
 *
 * <h2>Why the averages are not enough</h2>
 *
 * <p>The table of operations gives one entry price and one exit price, and for a
 * trade that went in once and out once that is the whole story. For one that
 * ladders into twenty contracts and leaves in six pieces it is an average of
 * twenty-six stories — and the averages hide precisely what is worth looking at,
 * which is that the first partial made money and the last two gave it back.</p>
 *
 * <p>So: a row per execution, with when, at what price, how many contracts, what
 * it left open, and what it realised in points and in reais. The <b>verb</b> is
 * a column of its own because it says <i>why</i> each exit happened — a stop, a
 * target, the end of the day — and that is the difference between a plan that
 * worked and one that ran out of time.</p>
 */
final class TradeDetail {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yy HH:mm");

    private static final Locale BRAZIL = Locale.forLanguageTag("pt-BR");

    private static final Color UP = new Color(38, 166, 109);

    private static final Color DOWN = new Color(214, 73, 73);

    private TradeDetail() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param over    the bars the trade is numbered against, for the clock
     * @param perPoint reais per point per contract
     */
    static void show(Component beside, Trade trade, int number, PriceSeries over,
                     double perPoint) {

        JTable table = new JTable(new Steps(trade, over, perPoint));

        table.setFillsViewportHeight(true);
        table.setRowHeight(Math.max(table.getRowHeight(), 20));
        table.setAutoCreateRowSorter(false);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new Cells(trade));

        JPanel body = new JPanel(new BorderLayout(0, 6));

        body.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        body.add(new JScrollPane(table), BorderLayout.CENTER);
        body.add(footer(trade, perPoint), BorderLayout.SOUTH);

        Window owner = beside == null ? null
                : javax.swing.SwingUtilities.getWindowAncestor(beside);

        // NOT MODAL. The point of opening it is to look at the chart beside it:
        // a dialog that blocks the window it is explaining is a dialog that has
        // to be closed before it can be used.
        JDialog dialog = new JDialog(owner, Messages.get("backtest.detail.title") + "  #" + number);

        dialog.setModalityType(JDialog.ModalityType.MODELESS);
        dialog.setContentPane(body);
        dialog.setSize(new Dimension(720, 320));
        dialog.setLocationRelativeTo(beside);
        dialog.setVisible(true);
    }

    private static JPanel footer(Trade trade, double perPoint) {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 14, 0));

        row.add(quiet(Messages.get("backtest.col.turned") + ": " + trade.turned()));
        row.add(quiet(Messages.get("backtest.detail.peak") + ": " + trade.contracts()));
        row.add(quiet(Messages.get("backtest.col.cost") + ": "
                + String.format(BRAZIL, "%,.0f", -trade.cost())));

        JLabel net = new JLabel(String.format(BRAZIL, "%s: %+,.0f pts  ·  %s",
                Messages.get("backtest.col.net"), trade.net(),
                String.format(BRAZIL, "R$ %,.2f", trade.net() * perPoint)));

        net.setForeground(trade.won() ? UP : DOWN);
        row.add(net);

        return row;
    }

    private static JLabel quiet(String text) {
        JLabel label = new JLabel(text);

        label.setEnabled(false);

        return label;
    }

    /** The rows: one execution each, in the order they happened. */
    private static final class Steps extends AbstractTableModel {

        private static final long serialVersionUID = 1L;

        private final transient List<Trade.Step> steps;

        private final transient PriceSeries over;

        private final double perPoint;

        private final String[] columns = {
                Messages.get("backtest.detail.when"),
                Messages.get("backtest.detail.what"),
                Messages.get("backtest.detail.side"),
                Messages.get("backtest.col.contracts"),
                Messages.get("backtest.detail.price"),
                Messages.get("backtest.detail.held"),
                Messages.get("backtest.col.points"),
                Messages.get("backtest.detail.money"),
        };

        private Steps(Trade trade, PriceSeries over, double perPoint) {
            this.steps = trade.steps();
            this.over = over;
            this.perPoint = perPoint;
        }

        @Override
        public int getRowCount() {
            return steps.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            Trade.Step step = steps.get(row);

            return switch (column) {
                case 0 -> when(step.fill().bar());
                case 1 -> TradeLevel.of(step.fill()).label();
                case 2 -> Messages.get(step.opening()
                        ? "backtest.detail.in" : "backtest.detail.out");
                case 3 -> String.valueOf(step.fill().quantity());
                case 4 -> String.format(BRAZIL, "%,.0f", step.fill().price());

                // WHAT IS STILL OPEN AFTER IT, which is the column that makes
                // the ladder readable: 4, 8, 12, 16, 20, 18, 14 tells the story
                // that a list of quantities does not.
                case 5 -> String.valueOf(step.held());
                case 6 -> step.opening() ? "—" : String.format(BRAZIL, "%+,.0f", step.points());
                default -> step.opening() ? "—"
                        : String.format(BRAZIL, "R$ %+,.2f", step.points() * perPoint);
            };
        }

        private String when(int bar) {
            if (over == null || bar < 0 || bar >= over.size()) {
                return String.valueOf(bar);
            }

            return ZonedDateTime.ofInstant(Instant.ofEpochMilli(over.timeAt(bar)), SP)
                    .format(WHEN);
        }
    }

    /** Numbers to the right, and the realised ones coloured by their sign. */
    private static final class Cells extends DefaultTableCellRenderer {

        private static final long serialVersionUID = 1L;

        private final transient List<Trade.Step> steps;

        private Cells(Trade trade) {
            this.steps = trade.steps();
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean chosen,
                                                       boolean focused, int row, int column) {

            super.getTableCellRendererComponent(table, value, chosen, focused, row, column);

            setHorizontalAlignment(column <= 2 ? SwingConstants.LEFT : SwingConstants.RIGHT);

            if (chosen || row >= steps.size()) {
                return this;
            }

            Trade.Step step = steps.get(row);

            // Only the two realised columns are coloured. Colouring the price
            // as well would put green and red on a number that is neither.
            setForeground(column >= 6 && !step.opening()
                    ? (step.points() >= 0 ? UP : DOWN)
                    : javax.swing.UIManager.getColor("Label.foreground"));

            return this;
        }
    }
}
