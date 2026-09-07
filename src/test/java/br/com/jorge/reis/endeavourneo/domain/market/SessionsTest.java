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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.NavigableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which days a series holds, and the answer being kept.
 *
 * <p>The walk costs 27 ms on the six-year source and the class asked its callers,
 * in its own javadoc, to hold the answer rather than ask again. Four callers did
 * not — the renko before rebuilding, the summary tooltip on every mouse move, the
 * transport on every combo change, and the segments window — so the answer is
 * kept here instead.</p>
 *
 * <p>A cache has four ways of being wrong, and there is a test for each: a stale
 * answer after the series grew, a stale answer after it shrank, one caller's
 * edit reaching another caller's answer, and two zones sharing one answer. The
 * fifth way is being slower than not caching, and that one is measured rather
 * than asserted — a test that watches a clock fails on a loaded machine.</p>
 */
@DisplayName("Sessions of a series")
class SessionsTest {

    private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

    /**
     * Bars every ten minutes from 09:00, for as many days as asked.
     *
     * <p>The size is read from the array on every call, so the same object can
     * grow and shrink the way a replay's series does.</p>
     */
    private static PriceSeries days(int[] bars, int perDay) {
        return new PriceSeries() {

            @Override
            public int size() {
                return bars[0];
            }

            @Override
            public long timeAt(int index) {
                return LocalDateTime.of(2026, 9, 1, 9, 0)
                        .plusDays(index / perDay)
                        .plusMinutes(10L * (index % perDay))
                        .atZone(SAO_PAULO).toInstant().toEpochMilli();
            }

            @Override
            public double openAt(int index) {
                return 100.0;
            }

            @Override
            public double highAt(int index) {
                return 101.0;
            }

            @Override
            public double lowAt(int index) {
                return 99.0;
            }

            @Override
            public double closeAt(int index) {
                return 100.0;
            }
        };
    }

    @BeforeEach
    void startClean() {
        Sessions.forget();
    }

    @Test
    @DisplayName("the days are the days, and a holiday is simply absent")
    void theDaysAreTheDays() {
        int[] size = {12};

        NavigableSet<LocalDate> found = Sessions.of(days(size, 4), SAO_PAULO);

        assertEquals(List.of(LocalDate.of(2026, 9, 1),
                        LocalDate.of(2026, 9, 2),
                        LocalDate.of(2026, 9, 3)),
                List.copyOf(found));
    }

    @Test
    @DisplayName("a series that GREW reports the day it grew into")
    void growingIsNotStale() {
        // The replay's case, and the one a cache gets wrong: it appends bars as
        // it plays, and an answer kept from before the append would leave the
        // day being played out of the list of days that exist.
        int[] size = {8};
        PriceSeries series = days(size, 4);

        assertEquals(2, Sessions.of(series, SAO_PAULO).size());

        size[0] = 12;

        assertEquals(3, Sessions.of(series, SAO_PAULO).size(),
                "the third day was played and the answer still says two");
        assertTrue(Sessions.of(series, SAO_PAULO).contains(LocalDate.of(2026, 9, 3)),
                "the day it grew into is missing");
    }

    @Test
    @DisplayName("a series that SHRANK drops the days that left")
    void shrinkingIsNotStale() {
        // The reader drags the replay backwards. Growing can be answered by
        // walking only the tail; shrinking cannot -- dates have to leave, and
        // there is no way to know which without looking again.
        int[] size = {12};
        PriceSeries series = days(size, 4);

        assertEquals(3, Sessions.of(series, SAO_PAULO).size());

        size[0] = 4;

        assertEquals(List.of(LocalDate.of(2026, 9, 1)),
                List.copyOf(Sessions.of(series, SAO_PAULO)),
                "seeking backwards left days in the answer that are no longer there");
    }

    @Test
    @DisplayName("one caller editing what it got does not edit everyone else's answer")
    void theAnswerIsACopy() {
        // The kept set must never be handed out. A caller that clears what it
        // received -- or sorts it, or adds to it -- would be editing the answer
        // every other caller is about to be given.
        int[] size = {12};
        PriceSeries series = days(size, 4);

        NavigableSet<LocalDate> mine = Sessions.of(series, SAO_PAULO);

        mine.clear();
        mine.add(LocalDate.of(1999, 1, 1));

        assertEquals(3, Sessions.of(series, SAO_PAULO).size(),
                "clearing the set one caller was given emptied the kept answer");
    }

    @Test
    @DisplayName("two zones are two answers, not one")
    void theZoneIsPartOfTheQuestion() {
        // Kiritimati and not Tokyo, which the first draft of this test used and
        // proved nothing with: Sao Paulo is UTC-3 and Tokyo UTC+9, twelve hours,
        // so 09:00 here is 21:00 there and the DATE is the same. Kiritimati is
        // UTC+14, seventeen hours ahead, and 09:00 here is 02:00 tomorrow there.
        // A fixture that cannot tell the two answers apart cannot tell whether
        // the cache kept them apart either.
        int[] size = {12};
        PriceSeries series = days(size, 4);

        NavigableSet<LocalDate> here = Sessions.of(series, SAO_PAULO);
        NavigableSet<LocalDate> far = Sessions.of(series, ZoneId.of("Pacific/Kiritimati"));

        assertNotEquals(List.copyOf(here), List.copyOf(far),
                "the zone made no difference, so the answer was reused across zones");

        // And asking again in the first zone still answers in the first zone.
        assertEquals(List.copyOf(here), List.copyOf(Sessions.of(series, SAO_PAULO)),
                "the second zone overwrote the first one's answer");
    }

    @Test
    @DisplayName("no series and no bars are an empty answer, not a fault")
    void nothingIsAnEmptyAnswer() {
        int[] none = {0};

        assertTrue(Sessions.of(null, SAO_PAULO).isEmpty());
        assertTrue(Sessions.of(days(none, 4), SAO_PAULO).isEmpty());
    }
}
