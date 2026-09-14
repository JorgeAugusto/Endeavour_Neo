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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import br.com.jorge.reis.endeavourneo.domain.indicator.LastClosed;
import br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import java.time.ZoneId;
import java.util.List;

/**
 * The side both opening ranges agree on, or none.
 *
 * <p>Two ranges, and they are different animals that share a name:
 *
 * <ul>
 *   <li>the <b>ninety-minute</b> one is a window of the CLOCK — high and low of
 *       the first ninety minutes, and the first break of it;</li>
 *   <li>the <b>first fight</b> is a MOVEMENT — the opening impulse, closed by
 *       the first candle that goes the other way. See
 *       {@link OpeningImpulse}.</li>
 * </ul>
 *
 * <p>The gate opens when both have been broken AND both point the same way. A
 * day where only one has spoken has no side; a day where they disagree has two,
 * which is the same as none.
 *
 * <h2>Why it lives here and not inside a strategy</h2>
 *
 * <p>Because two of them use it. It was written inside {@code AlignedPatterns}
 * and the second caller would have meant a second copy — and two copies of one
 * rule drift in silence: one strategy says the day was long and the other says
 * it was flat, and both look right on their own. It is the same reason the
 * stochastic and the PMO moved into {@code domain.indicator}.
 *
 * <h2>What was measured about it</h2>
 *
 * <p>Measured 02/09/2026 across two raw bases, as separation per operation. The
 * fifteen-minute impulse was the only definition positive in all six cells; the
 * <b>ninety-minute clock range was negative in all three cells of the blind
 * base</b> — −3,95 / −16,40 / −1,75. Even the impulse is weak: the largest t of
 * the six is 1,27. The conclusion recorded then was to use it as a side filter
 * for a trade one would take anyway, <b>never as a trigger of its own</b>.
 *
 * <p>So this class is exactly the sanctioned use for one half of it and the
 * refused use for the other. Whatever is measured with it on must be read
 * knowing that.
 */
public final class RangeGate {

    /** The clock range's formation, in minutes: the ninety of the name. */
    public static final int FORMATION = 90;

    /**
     * Which of the two ranges have to agree.
     *
     * <p>Four modes and not a boolean, because the question "which half of the
     * gate is doing the work" is the first one anybody asks of a filter made of
     * two things, and a boolean cannot answer it.</p>
     */
    public enum Mode {

        /** No gate: both sides are traded. */
        OFF,

        /** Both ranges broken and pointing the same way. */
        BOTH,

        /** Only the ninety-minute clock range — the one the measurement refused. */
        CLOCK,

        /** Only the first fight — the definition the measurement approved. */
        FIGHT
    }

    private RangeGate() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    /**
     * @param decided the bars the strategy decides on
     * @param stored  the bars as stored, which the five-minute view comes from
     * @param zone    the exchange's zone, which decides where a session begins
     * @return per decision bar: {@code +1} when both ranges point up,
     *         {@code -1} down, {@code 0} while they do not agree
     */
    public static int[] of(PriceSeries decided, PriceSeries stored, ZoneId zone) {
        return of(decided, stored, zone, Mode.BOTH);
    }

    /**
     * @param mode which of the two ranges have to agree
     * @see #of(PriceSeries, PriceSeries, ZoneId)
     */
    public static int[] of(PriceSeries decided, PriceSeries stored, ZoneId zone,
                           Mode mode) {
        int size = decided == null ? 0 : decided.size();
        int[] made = new int[size];

        if (size == 0 || mode == null || mode == Mode.OFF) {
            return made;
        }

        PriceSeries five = Timeframe.ofMinutes(OpeningImpulse.MINUTES)
                .apply(stored == null ? decided : stored, zone);

        OpeningImpulse impulse = OpeningImpulse.standard();
        int[] fight = mode == Mode.CLOCK
                ? null : impulse.brokenAt(decided, impulse.of(five, zone), zone);
        int[] clock = mode == Mode.FIGHT
                ? null : brokeTheClockRange(decided, five, zone);

        // ONLY WHAT THE MODE ASKED FOR IS COMPUTED. Folding to five minutes and
        // walking an indicator over six years is not work to do for an answer
        // nobody reads.
        for (int bar = 0; bar < size; bar++) {
            made[bar] = switch (mode) {
                case CLOCK -> clock[bar];
                case FIGHT -> fight[bar];
                case BOTH -> fight[bar] != 0 && fight[bar] == clock[bar] ? fight[bar] : 0;
                default -> 0;
            };
        }

        return made;
    }

    /**
     * The ninety-minute range's first break, spread onto the run's bars.
     *
     * <p>Computed on five-minute candles and then spread by
     * {@link LastClosed}, so the answer at a decision bar is what a COMPLETED
     * five-minute candle had said — never one still forming.</p>
     */
    private static int[] brokeTheClockRange(PriceSeries decided, PriceSeries five, ZoneId zone) {
        double[] onFive = new double[five.size()];

        // THE SESSION ALREADY KNOWS. OpeningRange records the FIRST break --
        // which bar and which side -- and it is audited code with tests of its
        // own. Working it out again here would be a second implementation of
        // one rule, and the two would drift in silence.
        for (OpeningRange.Session each : OpeningRange.of(five, zone, FORMATION)) {
            if (each.side() == 0 || each.breakBar() < 0) {
                continue;
            }

            for (int bar = each.breakBar(); bar <= each.last() && bar < five.size(); bar++) {
                onFive[bar] = each.side();
            }
        }

        double[] spread = LastClosed.spread(decided, five, onFive);
        int[] made = new int[decided.size()];

        for (int bar = 0; bar < made.length; bar++) {
            made[bar] = Double.isNaN(spread[bar]) ? 0 : (int) Math.round(spread[bar]);
        }

        return made;
    }
}
