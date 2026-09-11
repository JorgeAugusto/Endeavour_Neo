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
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Trade;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.Viewport;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the operations happened, drawn on the price.
 *
 * <p>The number says <i>how much</i>. Only the drawing says <i>where</i> — and
 * it is looking at where that you find out the strategy only makes money in the
 * first hour of the session, or that every loss is the same gap.</p>
 *
 * <h2>Three layers, and the selected one gets all three</h2>
 *
 * <ol>
 *   <li><b>A shaded band</b> over the bars the position was open. It answers
 *       "how long was I in this" before any number does, and it makes a trade
 *       that ran through the night obvious at a glance.</li>
 *   <li><b>A dashed level line for every fill</b> — the entry, each partial
 *       entry, the stop, each target — labelled, and coloured by what the order
 *       <i>was</i> rather than by which way the position faced. See
 *       {@link TradeLevel}.</li>
 *   <li><b>Arrows and a line</b> between entry and exit, for every trade,
 *       faint except the selected one.</li>
 * </ol>
 *
 * <h2>A level line lives as long as the level did</h2>
 *
 * <p>Each line runs from the bar of <b>its own fill</b> to the end of the
 * trade, not across the screen. The previous project drew them across the whole
 * trade and wrote down why they must not cross the screen: before the entry
 * there was no stop, and after the exit it stopped being valid, so a line from
 * edge to edge suggests a permanent level and hides how long the trade lasted.
 * Starting each at its own fill carries one more fact for free — <b>a second
 * entry's price only became a level when that entry happened</b>, which is the
 * whole point of showing partials separately.</p>
 *
 * <h2>It draws under the candles, and that is why the arrows sit outside them</h2>
 *
 * <p>{@code paintUnder} is the only method handed a {@link Viewport}, and it
 * runs before the candles. Good for a band and for levels, which are meant to
 * sit behind the price. So the entry mark goes <b>below</b> the bar's low and
 * the exit mark <b>above</b> its high: outside the candle's own body, where
 * being painted first costs nothing.</p>
 */
public final class TradeMarks implements Overlay {

    private static final Color WON = new Color(38, 166, 109);

    private static final Color LOST = new Color(214, 73, 73);

    /** The shaded region, as translucent as the previous project's. */
    private static final Color BAND = new Color(0x3355AAFF, true);

    /** Faint enough to be background, strong enough to show a cluster. */
    private static final int QUIET = 70;

    private static final int ARROW = 5;

    private static final Stroke DASHED = new BasicStroke(1f, BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER, 1f, new float[] {5f, 4f}, 0f);

    private final List<Trade> trades = new ArrayList<>();

    private Trade chosen;

    private boolean visible = true;

    /**
     * @return false: these are the RUN's marks, not an indicator the reader
     *         inserted, so a layout neither saves them nor takes them away
     */
    @Override
    public boolean partOfLayout() {
        return false;
    }

    /** @param found the operations to draw; the list is copied */
    public void show(List<Trade> found) {
        trades.clear();
        trades.addAll(found);
        chosen = null;
    }

    /** @param trade the one to draw loudly, with its band and its levels */
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

        if (chosen != null && chosen.closedAt() >= from && chosen.openedAt() <= to) {
            band(g, viewport, chosen, from, to);
            levels(g, viewport, chosen, from, to);
        }

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

    // ------------------------------------------------------------------- band

    private void band(Graphics2D g, Viewport viewport, Trade trade, int from, int to) {
        int first = Math.max(trade.openedAt(), from);
        int last = Math.min(trade.closedAt(), to);

        if (first > last) {
            return;
        }

        Rectangle bounds = viewport.bounds();
        double half = viewport.barWidth() / 2;

        int left = (int) (viewport.x(first) - half);
        int width = Math.max((int) (viewport.x(last) + half) - left, 2);

        g.setColor(BAND);
        g.fillRect(left, bounds.y, width, bounds.height);
    }

    // ----------------------------------------------------------------- levels

    /**
     * One dashed line per distinct level, labelled.
     *
     * <p>Deduplicated by kind and price: a ladder that added three contracts at
     * the same price is one level, and three lines on top of each other only
     * make the dashes look solid.</p>
     */
    private void levels(Graphics2D g, Viewport viewport, Trade trade, int from, int to) {
        Map<String, Fill> distinct = new LinkedHashMap<>();

        for (Fill fill : trade.fills()) {
            distinct.putIfAbsent(TradeLevel.of(fill) + "@" + Math.round(fill.price() * 100), fill);
        }

        Stroke was = g.getStroke();
        g.setStroke(DASHED);

        Rectangle bounds = viewport.bounds();

        for (Fill fill : distinct.values()) {
            level(g, viewport, bounds, trade, fill, from, to);
        }

        g.setStroke(was);
    }

    private void level(Graphics2D g, Viewport viewport, Rectangle bounds,
                       Trade trade, Fill fill, int from, int to) {
        int y = (int) viewport.y(fill.price());

        if (y < bounds.y || y > bounds.y + bounds.height) {
            return;
        }

        // FROM ITS OWN FILL, not from the start of the trade: the level came
        // into being when the order that made it executed.
        int first = Math.max(fill.bar(), from);
        int last = Math.min(trade.closedAt(), to);

        if (first > last) {
            return;
        }

        double half = viewport.barWidth() / 2;
        int left = (int) (viewport.x(first) - half);
        int right = Math.max((int) (viewport.x(last) + half), left + 2);

        TradeLevel kind = TradeLevel.of(fill);

        g.setColor(kind.colour());
        g.drawLine(left, y, right, y);

        // The label to the RIGHT, just past the end of the segment: that is the
        // side the price axis is on, so reading "stop" and reading its price is
        // one movement of the eye. When it will not fit, it backs into the
        // chart rather than disappearing under the axis.
        String text = kind.label();
        int width = g.getFontMetrics().stringWidth(text);
        int x = right + 4;

        if (x + width > bounds.x + bounds.width) {
            x = Math.max(left, bounds.x + bounds.width - width);
        }

        g.drawString(text, x, y - 3);
    }

    // ----------------------------------------------------------------- arrows

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
