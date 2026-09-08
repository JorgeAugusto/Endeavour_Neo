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
package br.com.jorge.reis.endeavourneo.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A renko built in pieces is the same renko.
 *
 * <p>This is the property the whole tick-renko rests on. A day of ticks is 4,4
 * million bars and ten days will not fit in memory at once, so the bricks are
 * built one session at a time and only the bricks are kept — 594 a day
 * against 4,4 million ticks. That is only sound if continuing from where the
 * last session stopped gives exactly what one long pass would have given.</p>
 *
 * <p>It was claimed in the javadoc before it was checked. It is checked
 * here.</p>
 */
@DisplayName("Renko continued")
class RenkoContinuedTest {

    /** Bars {@code [from, to)} of another series. */
    private static PriceSeries slice(PriceSeries whole, int from, int to) {
        return new PriceSeries() {

            @Override
            public int size() {
                return to - from;
            }

            @Override
            public long timeAt(int index) {
                return whole.timeAt(from + index);
            }

            @Override
            public double openAt(int index) {
                return whole.openAt(from + index);
            }

            @Override
            public double highAt(int index) {
                return whole.highAt(from + index);
            }

            @Override
            public double lowAt(int index) {
                return whole.lowAt(from + index);
            }

            @Override
            public double closeAt(int index) {
                return whole.closeAt(from + index);
            }

            @Override
            public double volumeAt(int index) {
                return whole.volumeAt(from + index);
            }
        };
    }

    private static void sameBricks(PriceSeries expected, PriceSeries first, PriceSeries second,
                                   String what) {
        assertEquals(expected.size(), first.size() + second.size(),
                what + ": the pieces laid a different number of bricks");

        for (int i = 0; i < expected.size(); i++) {
            PriceSeries piece = i < first.size() ? first : second;
            int at = i < first.size() ? i : i - first.size();

            assertEquals(expected.openAt(i), piece.openAt(at), 1e-9, what + ": open at " + i);
            assertEquals(expected.highAt(i), piece.highAt(at), 1e-9, what + ": high at " + i);
            assertEquals(expected.lowAt(i), piece.lowAt(at), 1e-9, what + ": low at " + i);
            assertEquals(expected.closeAt(i), piece.closeAt(at), 1e-9, what + ": close at " + i);
            assertEquals(expected.timeAt(i), piece.timeAt(at), what + ": time at " + i);
        }
    }

    @Test
    @DisplayName("built in two pieces, brick for brick the same as built in one pass")
    void thePiecesAgreeWithTheWhole() {
        // Over a seeded walk, cut in three different places, so the cut lands
        // in the middle of a run and at a turn.
        PriceSeries walk = new RandomWalkSeries(4_000, 136_000.0, 0L, 11L);
        Renko renko = new Renko(55, 2, true, false);

        PriceSeries whole = renko.apply(walk);

        assertTrue(whole.size() > 100, "the walk laid too few bricks to prove anything");

        for (int cut : new int[]{1, 1_337, 2_000, 3_999}) {
            Renko.Continued first = renko.applyFrom(slice(walk, 0, cut), null);
            Renko.Continued second =
                    renko.applyFrom(slice(walk, cut, walk.size()), first.carry());

            sameBricks(whole, first.bricks(), second.bricks(), "cut at " + cut);
        }
    }

    @Test
    @DisplayName("the tails survive the cut too, not just the bodies")
    void theTailsAgree() {
        // The tail is the one thing that depends on state carried ACROSS bars:
        // how far price ran the other way since the last brick. If that were
        // not carried, the first brick after every cut would come out bare and
        // nobody would notice until a chart looked subtly wrong.
        PriceSeries walk = new RandomWalkSeries(3_000, 136_000.0, 0L, 5L);
        Renko renko = new Renko(55, 2, true, false);

        PriceSeries whole = renko.apply(walk);

        Renko.Continued first = renko.applyFrom(slice(walk, 0, 900), null);
        Renko.Continued second = renko.applyFrom(slice(walk, 900, walk.size()), first.carry());

        int at = first.bricks().size();

        assertTrue(at > 0 && at < whole.size(), "the cut did not fall between bricks");

        // The first brick of the second piece, which is the one that carries.
        assertEquals(whole.highAt(at), second.bricks().highAt(0), 1e-9,
                "the brick after the cut lost its upper tail");
        assertEquals(whole.lowAt(at), second.bricks().lowAt(0), 1e-9,
                "the brick after the cut lost its lower tail");
    }

    @Test
    @DisplayName("continuing from nothing is beginning")
    void aNullCarryIsAStart() {
        PriceSeries walk = new RandomWalkSeries(500, 136_000.0, 0L, 3L);
        Renko renko = Renko.of(55);

        assertEquals(renko.apply(walk).size(), renko.applyFrom(walk, null).bricks().size());
    }

    @Test
    @DisplayName("an empty piece changes nothing and hands the state straight on")
    void anEmptyPieceIsHarmless() {
        // A session the exchange barely opened -- 25/01/2021 has two ticks --
        // must not reset the ruler for the session after it.
        PriceSeries walk = new RandomWalkSeries(1_000, 136_000.0, 0L, 7L);
        Renko renko = Renko.of(55);

        Renko.Continued first = renko.applyFrom(slice(walk, 0, 500), null);
        Renko.Continued nothing = renko.applyFrom(PriceSeries.empty(), first.carry());

        assertEquals(0, nothing.bricks().size());
        assertEquals(first.carry(), nothing.carry(),
                "an empty session moved the ruler");
    }

    @Test
    @DisplayName("dois carries com os mesmos numeros sao o mesmo carry")
    void twoCarriesWithTheSameNumbersAreEqual() {
        // A record compares its components with equals, and TradeTally had
        // none, so two carries holding identical figures were different
        // carries. The one test that compared them -- anEmptyPieceIsHarmless,
        // just above -- passed for the wrong reason: applyFrom hands the SAME
        // OBJECT back on the empty path, so it was asserting identity and
        // calling it equality. Replacing that return with a freshly built carry
        // of the same values, which is an entirely reasonable refactoring,
        // broke it without anything having got worse.
        TradeTally one = new TradeTally();
        TradeTally other = new TradeTally();

        Renko.Carry left = new Renko.Carry(136_000, 1, 135_900, 136_100, 42, one);
        Renko.Carry right = new Renko.Carry(136_000, 1, 135_900, 136_100, 42, other);

        assertNotSame(one, other);
        assertEquals(left, right, "two carries with the same numbers came out different");
        assertEquals(left.hashCode(), right.hashCode());
    }

    @Test
    @DisplayName("o carry tira sua propria copia do acumulador")
    void aCarryDoesNotShareItsTally() {
        // TradeTally is mutable. A record that merely kept the reference let two
        // carries share one accumulator, and emptying it through either emptied
        // both. applyFrom copied on the way in, so the guarantee lived in the
        // caller -- which is where a caller can forget it.
        TradeTally tally = new TradeTally();
        Renko.Carry carry = new Renko.Carry(136_000, 0, 136_000, 136_000, 0, tally);

        assertNotSame(tally, carry.tally(), "the carry kept the caller's accumulator");
    }

    @Test
    @DisplayName("um carry que nao poderia ter vindo de um renko e recusado")
    void anImpossibleCarryIsRefused() {
        // There was no validation at all, and the proof that invalid states were
        // reachable sat downstream: applyFrom had to defend itself against a
        // null tally. A guard against a state the type allows is the type
        // admitting it should not.
        assertThrows(IllegalArgumentException.class,
                () -> new Renko.Carry(136_000, 2, 135_900, 136_100, 0, new TradeTally()),
                "a brick that goes two ways was accepted");

        assertThrows(IllegalArgumentException.class,
                () -> new Renko.Carry(136_000, 1, 136_100, 135_900, 0, new TradeTally()),
                "a run reaching further down than up was accepted");

        // And a null tally is a start, not a crash: it is what the first ever
        // carry of a renko looks like.
        assertEquals(new TradeTally(),
                new Renko.Carry(0, 0, 0, 0, 0, null).tally());
    }
    @Test
    @DisplayName("o volume que veio no carry nao vira NaN por um trecho sem volume")
    void volumeCarriedInIsNotLost() {
        // anyVolume was switched on only by a finite volume in THIS stretch. A
        // stretch whose bars bring none, continuing a carry that does have some,
        // answered NaN for every brick -- including the first of the batch,
        // which was finished by trades that really happened, yesterday.
        //
        // The house rule is "missing volume is NaN, never zero". This was the
        // other way round: NaN where a number existed.
        Renko renko = Renko.of(10);

        // A carry holding 500 contracts of pending volume, the way one comes
        // back from a session that ended mid-brick.
        Renko.Carry carried = new Renko.Carry(100_000, 0, 100_000, 100_000, 500,
                new TradeTally());

        PriceSeries silent = withoutVolume(100_000, 100_100);
        PriceSeries bricks = renko.applyFrom(silent, carried).bricks();

        assertTrue(bricks.size() > 0, "no brick was laid, so this proves nothing");
        assertTrue(Double.isFinite(bricks.volumeAt(0)),
                "the first brick of the batch was finished by volume the carry brought, "
                        + "and it came out NaN");
    }

    /** Bars that rise from one price to another and state no volume at all. */
    private static PriceSeries withoutVolume(double from, double to) {
        int bars = 40;

        return new PriceSeries() {

            @Override
            public int size() {
                return bars;
            }

            @Override
            public long timeAt(int index) {
                return index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return from + (to - from) * index / bars;
            }

            @Override
            public double highAt(int index) {
                return openAt(index) + 5;
            }

            @Override
            public double lowAt(int index) {
                return openAt(index) - 5;
            }

            @Override
            public double closeAt(int index) {
                return openAt(index) + 2;
            }
        };
    }
}
