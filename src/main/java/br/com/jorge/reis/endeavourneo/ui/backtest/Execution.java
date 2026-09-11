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
 * <h2>What actually differs</h2>
 *
 * <p>Inside one minute the high and the low both happened, and OHLC does not say
 * in which order. A stop and a target that both sit inside that minute are then
 * settled by a <b>rule</b> — ours takes the stop, because it is the conservative
 * side — and the rule decides part of every result it touches. Walking a tick
 * path instead, the question never gets asked: whichever price came first, came
 * first.</p>
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
     * One bar, one decision, one chance to fill.
     *
     * <p>The scale list means something here: a run can be read at five minutes
     * or at an hour.</p>
     */
    OHLC("backtest.execution.ohlc"),

    /**
     * The inside of every minute, walked.
     *
     * <p>Only over the scale the series is stored at. The tick path was measured
     * against the shape of a <b>minute</b>, and generating one inside a
     * five-minute bar would be applying those statistics to something they were
     * never measured on — so the scale list is turned off rather than quietly
     * ignored.</p>
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

    /** @return whether a scale other than the stored one can be chosen */
    boolean allowsAnotherScale() {
        return this == OHLC;
    }

    @Override
    public String toString() {
        return label();
    }
}
