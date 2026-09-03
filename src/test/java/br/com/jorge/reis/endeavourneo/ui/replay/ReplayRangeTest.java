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
package br.com.jorge.reis.endeavourneo.ui.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Replaying more than one session.
 *
 * <p>Watching one session tells you what that session did; a week tells you
 * whether the thing you saw happens.</p>
 */
@DisplayName("Replay over a range")
class ReplayRangeTest {

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 31);

    private static ReplaySession over(LocalDate from, LocalDate to) {
        return new ReplaySession("WINFUT", from, to, 0);
    }

    /** @return how many bars the whole playable stretch holds */
    private static int barsOf(ReplaySession session) {
        session.seekFraction(1.0);

        return session.series().size();
    }

    @Test
    @DisplayName("the fixture really starts on a Monday")
    void theFixtureIsRight() {
        assertEquals(DayOfWeek.MONDAY, MONDAY.getDayOfWeek());
    }

    @Test
    @DisplayName("a week of sessions is five days, not seven")
    void weekendsAreSkipped() {
        // Monday to Sunday. Saturday and Sunday are skipped rather than
        // generated and hidden: an empty Saturday in the middle would put a
        // boundary in the concatenation that no bar ever lands on.
        int oneDay = barsOf(over(MONDAY, MONDAY));
        int wholeWeek = barsOf(over(MONDAY, MONDAY.plusDays(6)));

        assertEquals(5 * oneDay, wholeWeek, "a week must be five sessions");
    }

    @Test
    @DisplayName("Friday to Monday is two sessions")
    void acrossAWeekend() {
        LocalDate friday = MONDAY.plusDays(4);

        assertEquals(2 * barsOf(over(MONDAY, MONDAY)), barsOf(over(friday, friday.plusDays(3))));
    }

    @Test
    @DisplayName("a range that ends before it starts plays the one day asked for")
    void backwardsRangeIsOneDay() {
        // Refusing would leave the reader with an empty transport and no reason
        // given; the panel says where the typo is instead.
        assertEquals(barsOf(over(MONDAY, MONDAY)), barsOf(over(MONDAY, MONDAY.minusDays(5))));
    }

    @Test
    @DisplayName("a range of weekend only still plays the day asked for")
    void weekendOnlyStillPlays() {
        LocalDate saturday = MONDAY.plusDays(5);

        assertTrue(barsOf(over(saturday, saturday.plusDays(1))) > 0,
                "an empty transport with no reason given is the worst answer");
    }

    @Test
    @DisplayName("the clock carries the date only when there is more than one day")
    void theClockSaysWhichDay() {
        // On a single session the date would be the same six characters all the
        // way through, taking room from the one part that moves.
        assertFalse(over(MONDAY, MONDAY).isRange());
        assertEquals(8, over(MONDAY, MONDAY).clockText().length(), "expected HH:mm:ss");

        ReplaySession week = over(MONDAY, MONDAY.plusDays(4));

        assertTrue(week.isRange());
        assertTrue(week.clockText().length() > 8, "a range has to say which day: "
                + week.clockText());
    }

    @Test
    @DisplayName("the title says the range, not just the first day")
    void theTitleSaysTheRange() {
        assertEquals("2026-08-31", over(MONDAY, MONDAY).rangeText());
        assertEquals("2026-08-31 a 2026-09-04", over(MONDAY, MONDAY.plusDays(4)).rangeText());
    }

    @Test
    @DisplayName("the end date is kept at or after the start, never before")
    void theEndNeverPrecedesTheStart() {
        // Moving the start past the end is somebody choosing a later day, not
        // somebody asking for a backwards range, so the end follows rather than
        // being refused.
        assertEquals(MONDAY.plusDays(4), ReplayPanel.keepInWindow(MONDAY, MONDAY.plusDays(4), 10),
                "a valid range must be left alone");
        assertEquals(MONDAY, ReplayPanel.keepInWindow(MONDAY, MONDAY, 10),
                "one day is a valid range");
        assertEquals(MONDAY, ReplayPanel.keepInWindow(MONDAY, MONDAY.minusDays(3), 10),
                "the end fell before the start and was not brought forward");

        // Half-typed dates are left alone: moving a field under a cursor is
        // worse than leaving it wrong for a moment.
        assertNull(ReplayPanel.keepInWindow(null, MONDAY, 10));
        assertNull(ReplayPanel.keepInWindow(MONDAY, null, 10));
    }

    @Test
    @DisplayName("the window cannot be longer than the limit, counting both ends")
    void theWindowIsCapped() {
        // Ten days means the tenth day is the last one that fits, not the
        // eleventh: an off-by-one here is the difference between "ten days" and
        // "ten days plus one".
        assertEquals(MONDAY.plusDays(9), ReplayPanel.keepInWindow(MONDAY, MONDAY.plusDays(9), 10),
                "the tenth day has to fit inside a ten-day window");
        assertEquals(MONDAY.plusDays(9), ReplayPanel.keepInWindow(MONDAY, MONDAY.plusDays(30), 10),
                "a range past the limit was not pulled back");
        assertEquals(MONDAY, ReplayPanel.keepInWindow(MONDAY, MONDAY.plusDays(30), 1),
                "a window of one day is one day");
    }

    @Test
    @DisplayName("a mistyped year asks for a capped number of sessions, not a decade")
    void thereIsACap() {
        int oneDay = barsOf(over(MONDAY, MONDAY));
        int tenYears = barsOf(over(MONDAY, MONDAY.plusYears(10)));

        assertTrue(tenYears <= ReplaySession.MOST_SESSIONS * oneDay,
                "the cap did not hold: " + tenYears / oneDay + " sessions");
    }
}
