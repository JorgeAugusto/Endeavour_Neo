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
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the operations happened, drawn on the price.
 *
 * <p>The number says <i>how much</i>. Only the drawing says <i>where</i> — and
 * it is looking at where that you find out the strategy only makes money in the
 * first hour of the session, or that every loss is the same gap.</p>
 *
 * <h2>It draws under the candles, and that is why the arrows sit outside them</h2>
 *
 * <p>{@code paintUnder} is the only method handed a {@link Viewport}, and it
 * runs before the candles. So the entry mark goes <b>below</b> the bar's low and
 * the exit mark <b>above</b> its high: outside the candle's own body, where
 * being painted first costs nothing. An arrow placed on the close would be
 * hidden by the candle that produced it.</p>
 *
 * <h2>One is highlighted, the rest are quiet</h2>
 *
 * <p>Four thousand operations drawn at full strength is a wall. The selected one
 * is drawn thick and with its result written beside it; the others are faint
 * enough to give context without competing.</p>
 */
public final class TradeMarks implements Overlay {

    private static final Color WON = new Color(38, 166, 109);

    private static final Color LOST = new Color(214, 73, 73);

    /** Faint enough to be background, strong enough to show a cluster. */
    private static final int QUIET = 70;

    private static final int ARROW = 5;

    private final List<Trade> trades = new ArrayList<>();

    private Trade chosen;

    private boolean visible = true;

    /** @param found the operations to draw; the list is copied */
    public void show(List<Trade> found) {
        trades.clear();
        trades.addAll(found);
        chosen = null;
    }

    /** @param trade the one to draw loudly, or {@code null} for none */
    public void highlight(Trade trade) {
        chosen = trade;
    }

    @Override
    public String nameKey() {
        return "backtest.marks";
    }

    @Override
    public List<Integer> parameters() {
        return List.of();
    }

    @Override
    public List<Color> colours() {
        // No line per bar: everything this draws, it draws in paintUnder. An
        // empty list is how an overlay says "I have no series", and the canvas
        // then asks valueAt for nothing.
        return List.of();
    }

    @Override
    public double[] valueAt(int bar) {
        return new double[0];
    }

    @Override
    public void calculate(PriceSeries series) {
        // Nothing to compute: the trades arrive already made, from a run.
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public void setVisible(boolean wanted) {
        visible = wanted;
    }

    @Override
    public boolean fitsOnPrice() {
        return true;
    }

    @Override
    public void paintUnder(Graphics2D g, Viewport viewport, int from, int to) {
        if (!visible || trades.isEmpty()) {
            return;
        }

        Object was = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        for (Trade trade : trades) {
            if (trade.closedAt() < from || trade.openedAt() > to) {
                continue;
            }

            draw(g, viewport, trade, trade == chosen);
        }

        if (was != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, was);
        }
    }

    private void draw(Graphics2D g, Viewport viewport, Trade trade, boolean loud) {
        Color base = trade.won() ? WON : LOST;
        Color colour = loud ? base : new Color(base.getRed(), base.getGreen(), base.getBlue(), QUIET);

        double xIn = viewport.x(trade.openedAt());
        double xOut = viewport.x(trade.closedAt());
        double yIn = viewport.y(trade.entryPrice());
        double yOut = viewport.y(trade.exitPrice());

        g.setColor(colour);
        g.setStroke(new BasicStroke(loud ? 2.0f : 1.0f));
        g.drawLine((int) xIn, (int) yIn, (int) xOut, (int) yOut);

        // The entry points the way the position was opened; the exit points the
        // other way. A reader can tell a long from a short without the legend.
        boolean long_ = trade.side() == Side.BUY;

        g.fill(arrow(xIn, yIn + (long_ ? ARROW * 2 : -ARROW * 2), long_));
        g.fill(arrow(xOut, yOut + (long_ ? -ARROW * 2 : ARROW * 2), !long_));

        if (loud) {
            g.drawString(String.format("%+,.0f", trade.net()), (int) xOut + 6, (int) yOut - 6);
        }
    }

    /** @param up whether the arrow points up */
    private static Path2D arrow(double x, double y, boolean up) {
        Path2D path = new Path2D.Double();
        int tip = up ? -ARROW : ARROW;

        path.moveTo(x, y + tip);
        path.lineTo(x - ARROW, y - tip);
        path.lineTo(x + ARROW, y - tip);
        path.closePath();

        return path;
    }
}
