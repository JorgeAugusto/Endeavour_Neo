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
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

/**
 * The operations, one per row.
 *
 * <p>Thirteen columns, the same ones the previous project settled on — it is a
 * list that was read for two years, and the columns it ended with are the ones
 * that got used.</p>
 */
final class TradeTableModel extends AbstractTableModel {

    private static final long serialVersionUID = 1L;

    static final int INDEX = 0;
    static final int SIDE = 1;
    static final int OPENED = 2;
    static final int CLOSED = 3;
    static final int ENTRY = 4;
    static final int EXIT = 5;
    static final int POINTS = 6;
    static final int CONTRACTS = 7;
    static final int BARS = 8;
    static final int COST = 9;
    static final int NET = 10;

    /** The exchange's zone, like the chart's axis. */
    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd/MM/yy HH:mm");

    private final String[] columns;

    private final List<Trade> trades = new ArrayList<>();

    private PriceSeries series;

    TradeTableModel(String[] columns) {
        this.columns = columns.clone();
    }

    void show(List<Trade> found, PriceSeries over) {
        trades.clear();
        trades.addAll(found);
        series = over;

        fireTableDataChanged();
    }

    Trade at(int row) {
        return row < 0 || row >= trades.size() ? null : trades.get(row);
    }

    @Override
    public int getRowCount() {
        return trades.size();
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
    public Class<?> getColumnClass(int column) {
        return column == INDEX || column == CONTRACTS || column == BARS
                ? Integer.class
                : (column == SIDE || column == OPENED || column == CLOSED
                        ? String.class : Double.class);
    }

    @Override
    public Object getValueAt(int row, int column) {
        Trade trade = trades.get(row);

        return switch (column) {
            case INDEX -> row + 1;
            case SIDE -> trade.side() == Side.BUY ? "C" : "V";
            case OPENED -> when(trade.openedAt());
            case CLOSED -> when(trade.closedAt());
            case ENTRY -> trade.entryPrice();
            case EXIT -> trade.exitPrice();
            case POINTS -> trade.gross();
            case CONTRACTS -> trade.contracts();
            case BARS -> trade.bars();
            case COST -> trade.cost();
            case NET -> trade.net();
            default -> null;
        };
    }

    private String when(int bar) {
        if (series == null || bar < 0 || bar >= series.size()) {
            return String.valueOf(bar);
        }

        return ZonedDateTime.ofInstant(Instant.ofEpochMilli(series.timeAt(bar)), SP).format(WHEN);
    }
}
