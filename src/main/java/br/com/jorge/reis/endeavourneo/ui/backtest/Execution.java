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

import br.com.jorge.reis.endeavourneo.platform.Messages;

/**
 * How finely a run is executed — the Profit's own choice, with its own names.
 *
 * <p>The Profit puts {@code OHLC} and {@code Tick a Tick} side by side in the
 * execution panel, and it is the right thing to copy: they answer the one
 * question a bar cannot, and the answer changes the result.</p>
 *
 * <h2>It says how orders fill, and nothing else</h2>
 *
 * <p><b>It is not a scale.</b> The scale is the chart's — it is what the strategy
 * reads when it does not name one of its own, so an EMA 17 on a five-minute
 * chart is seventeen five-minute bars. This says how finely the orders that
 * strategy leaves standing are filled, and the two are free of each other: any
 * scale can be run either way.</p>
 *
 * <p>What actually differs: inside one bar the high and the low both happened,
 * and OHLC does not say in which order. A stop and a target that both sit inside
 * it are then settled by a <b>rule</b> — ours takes the stop, because it is the
 * conservative side — and at most one cover fills per bar, so the lots of a
 * scaled-out position leave one bar at a time. Walking a tick path instead, the
 * question is never asked: whichever price came first came first, and several
 * lots can come out inside the same bar.</p>
 *
 * <p>So the honest reading is not "ticks are more accurate". It is: <b>OHLC
 * gives you a result plus a tie-break, and ticks give you a result plus an
 * invented path whose statistics were measured.</b> Both are approximations;
 * they are approximations of different things, which is why the choice is on
 * screen rather than decided here.</p>
 *
 * <h2>Ticks cost about fifteen hundred bars per minute</h2>
 *
 * <p>Measured on his series: a session of 563 minutes becomes 986.840 ticks, a
 * week 4,5 million, a month 17 million. That is why the recorte is picked before
 * the run and not after.</p>
 */
enum Execution {

    /**
     * One chance to fill per bar of the chart.
     *
     * <p>Which is also one <b>operation</b> per bar: a position that would have
     * been scaled out in three pieces inside one candle comes out over three
     * candles here.</p>
     */
    OHLC("backtest.execution.ohlc"),

    /**
     * The inside of every bar, walked print by print.
     *
     * <p>This is what a robot actually meets, which is why it is the default:
     * the live thing fills tick by tick, and a backtest that can only fill once
     * per candle is measuring a constraint the market does not have.</p>
     *
     * <p>The path is always built from the scale the series is <b>stored</b> at,
     * never from the aggregated bars: {@code SyntheticTicks} was measured against
     * the shape of a <b>minute</b>, and generating one inside a five-minute bar
     * would apply those statistics where they were never measured. So the chart
     * can be read at any scale without the ticks under it changing at all.</p>
     */
    TICKS("backtest.execution.ticks");

    private final String key;

    Execution(String key) {
        this.key = key;
    }

    /** @return the name the screen shows */
    String label() {
        return Messages.get(key);
    }

    @Override
    public String toString() {
        return label();
    }
}
