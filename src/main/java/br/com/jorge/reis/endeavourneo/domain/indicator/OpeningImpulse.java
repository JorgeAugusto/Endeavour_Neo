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
package br.com.jorge.reis.endeavourneo.domain.indicator;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * The opening range as the archive defines it: the FIRST FIGHT of the day.
 *
 * <p>Ported from {@code JorgeReisRangeAbertura_5m}. It is not a window of the
 * clock — that is the other definition, the ninety minutes of
 * {@code OpeningRange}, and the two are different things that share a name.
 * Here the range is a <b>movement</b>:
 *
 * <ol>
 *   <li>the session's first five-minute candle sets the direction — up when it
 *       closed at or above its open, down otherwise;</li>
 *   <li>every candle that keeps going that way widens the range;</li>
 *   <li>the <b>first candle that closes the other way ends it</b>. That candle
 *       is the other side arriving, and the fight is over.</li>
 * </ol>
 *
 * <p>With one exception, and it is the original's: if the first movement came
 * out smaller than {@link #minimumFirstMove()}, the opposite candle is absorbed
 * into the range instead of merely ending it. A three-tick skirmish is not a
 * fight, and treating it as one hands the day a range nothing will respect.
 *
 * <h2>Which definition to use, and what was measured about them</h2>
 *
 * <p>These two are not interchangeable, and the project has a number on it.
 * Measured 02/09/2026 across two raw bases, as SEPARATION — how much more the
 * side with the range earns than the side against it, per operation:
 *
 * <ul>
 *   <li>the 15-minute impulse was the only definition positive in all six
 *       cells;</li>
 *   <li>the ninety-minute clock range was <b>negative in all three cells of
 *       the blind base</b>: −3,95 / −16,40 / −1,75.</li>
 * </ul>
 *
 * <p>And the impulse is weak even where it works: the largest t of the six
 * cells is 1,27, most below 1. The conclusion recorded then was to use it as a
 * <b>side filter for a trade one would take anyway, never as a trigger of its
 * own</b>. Anything built on top of it inherits that.
 *
 * @param minimumFirstMove below which the opposite candle joins the range
 */
public record OpeningImpulse(double minimumFirstMove) {

    /** {@code TamMinPrimeiroMovimento}. In points of the instrument. */
    public static final double LEAST_FIRST_MOVE = 500;

    /** The candle the archive reads it on. */
    public static final int MINUTES = 5;

    /**
     * One session's first fight.
     *
     * @param day       the session
     * @param direction {@code +1} when the first candle was up, {@code -1} down
     * @param high      the range's top
     * @param low       its bottom
     * @param knownFrom the instant the range became a FACT — the end of the
     *                  candle that closed the fight — or {@link Long#MAX_VALUE}
     *                  if the session ended without one
     *
     * <p>{@code knownFrom} is the whole reason this record is shaped like this.
     * Until the fight ends, the range is still being widened, and a level read
     * from it is a level that is about to move. Anything downstream has to wait
     * for that instant, and having it here means the waiting cannot be
     * forgotten.</p>
     */
    public record Fight(LocalDate day, int direction, double high, double low,
                        long knownFrom) {

        /** @return whether the fight ended, which is when the range is fixed */
        public boolean known() {
            return knownFrom < Long.MAX_VALUE;
        }

        public double size() {
            return high - low;
        }
    }

    public OpeningImpulse {
        if (!(minimumFirstMove >= 0)) {
            throw new IllegalArgumentException(
                    "a smallest first move of " + minimumFirstMove + " is not a size");
        }
    }

    /** @return the impulse the archive is born with: five hundred points */
    public static OpeningImpulse standard() {
        return new OpeningImpulse(LEAST_FIRST_MOVE);
    }

    /**
     * @param bars five-minute candles — the scale the archive reads it on
     * @param zone the exchange's zone, which decides where a session begins
     * @return one fight per session, chronological
     */
    public List<Fight> of(PriceSeries bars, ZoneId zone) {
        List<Fight> made = new ArrayList<>();
        int size = bars == null ? 0 : bars.size();

        if (size == 0) {
            return made;
        }

        Building building = null;

        for (int bar = 0; bar < size; bar++) {
            LocalDate now = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();

            if (building == null || !now.equals(building.day)) {
                if (building != null) {
                    made.add(building.done());
                }

                building = new Building(now, bars.closeAt(bar) >= bars.openAt(bar) ? 1 : -1,
                        bars.highAt(bar), bars.lowAt(bar), minimumFirstMove);

                continue;
            }

            building.saw(bars.openAt(bar), bars.highAt(bar), bars.lowAt(bar),
                    bars.closeAt(bar), bars.timeAt(bar) + MINUTES * 60_000L);
        }

        made.add(building.done());

        return made;
    }

    /** One fight while it is still being built. */
    private static final class Building {

        private final LocalDate day;

        private final int direction;

        private final double floor;

        private double high;

        private double low;

        private boolean closed;

        private long knownFrom = Long.MAX_VALUE;

        private double againstHigh;

        private double againstLow;

        private Building(LocalDate day, int direction, double high, double low, double floor) {
            this.day = day;
            this.direction = direction;
            this.high = high;
            this.low = low;
            this.floor = floor;
        }

        private void saw(double open, double barHigh, double barLow, double close, long ends) {
            if (closed) {
                return;
            }

            boolean against = direction > 0 ? close < open : close > open;

            if (!against) {
                high = Math.max(high, barHigh);
                low = Math.min(low, barLow);

                return;
            }

            // THE OTHER SIDE ARRIVED, and the fight is over on this candle. It
            // is remembered whole because it may still be absorbed below.
            closed = true;
            knownFrom = ends;
            againstHigh = barHigh;
            againstLow = barLow;
        }

        private Fight done() {
            if (!closed) {
                return new Fight(day, direction, high, low, Long.MAX_VALUE);
            }

            // THE SKIRMISH THAT DOES NOT COUNT: a first movement smaller than
            // the floor takes the opposite candle INTO the range instead of
            // being ended by it. The original does this on both sides, and the
            // reason is that a range of three ticks is a level nothing will
            // respect.
            if (high - low >= floor) {
                return new Fight(day, direction, high, low, knownFrom);
            }

            return new Fight(day, direction, Math.max(high, againstHigh),
                    Math.min(low, againstLow), knownFrom);
        }
    }

    /**
     * @param bars   the series the strategy walks, at whatever scale
     * @param fights the fights, from {@link #of}
     * @param zone   the exchange's zone
     * @return per bar: {@code +1} once the range had been broken upwards,
     *         {@code -1} downwards, {@code 0} while it had not — or while the
     *         fight itself was not yet a fact
     *
     * <p>The FIRST break latches and the day keeps it. A range broken up in the
     * morning and down in the afternoon is a range that pointed up: reading the
     * latest break instead would have the compass turn round whenever the
     * market did, which is the one thing a compass must not do.</p>
     */
    public int[] brokenAt(PriceSeries bars, List<Fight> fights, ZoneId zone) {
        int size = bars == null ? 0 : bars.size();
        int[] made = new int[size];

        if (size == 0 || fights == null || fights.isEmpty()) {
            return made;
        }

        LocalDate day = null;
        Fight fight = null;
        int which = 0;
        int broke = 0;

        for (int bar = 0; bar < size; bar++) {
            LocalDate now = Instant.ofEpochMilli(bars.timeAt(bar)).atZone(zone).toLocalDate();

            if (!now.equals(day)) {
                day = now;
                broke = 0;
                fight = null;

                while (which < fights.size() && fights.get(which).day().isBefore(now)) {
                    which++;
                }

                if (which < fights.size() && fights.get(which).day().equals(now)) {
                    fight = fights.get(which);
                }
            }

            // NOTHING BEFORE THE FIGHT IS OVER. Until then the range is still
            // widening, so a break of it is a break of a level that was about to
            // move -- which is reading the future with extra steps.
            if (fight == null || !fight.known() || bars.timeAt(bar) < fight.knownFrom()) {
                continue;
            }

            if (broke == 0) {
                if (bars.highAt(bar) > fight.high()) {
                    broke = 1;
                } else if (bars.lowAt(bar) < fight.low()) {
                    broke = -1;
                }
            }

            made[bar] = broke;
        }

        return made;
    }
}
