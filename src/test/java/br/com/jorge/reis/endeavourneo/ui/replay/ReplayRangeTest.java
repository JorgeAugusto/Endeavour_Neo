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

    /** The base these tests play; see ReplayBase. */
    @org.junit.jupiter.api.io.TempDir
    static java.nio.file.Path folder;

    private static String base;

    @org.junit.jupiter.api.BeforeAll
    static void writeTheBase() throws java.io.IOException {
        base = ReplayBase.at(folder, MONDAY);
    }

    @org.junit.jupiter.api.AfterAll
    static void putTheFolderBack() {
        ReplayBase.release();
    }

    /** @return every date picker in the transport, in the order they were added */
    private static java.util.List<DatePicker> pickersIn(java.awt.Container where) {
        java.util.List<DatePicker> found = new java.util.ArrayList<>();

        for (java.awt.Component each : where.getComponents()) {
            if (each instanceof DatePicker picker) {
                found.add(picker);
            } else if (each instanceof java.awt.Container inside) {
                found.addAll(pickersIn(inside));
            }
        }

        return found;
    }

    @Test
    @DisplayName("typing a date past the window corrects it instead of throwing")
    void correctingTheEndDoesNotThrow() throws Exception {
        // The correction was made from inside the very notification that
        // announced the change: both pickers report through a DocumentListener,
        // and setDate writes to that same document. Swing answers that with
        // IllegalStateException("Attempt to mutate in notification"), thrown on
        // the interface thread the moment somebody typed a date outside the
        // window -- and, quietly, the windowDays ceiling was then never applied
        // by that field at all.
        //
        // The throw reaches this test because invokeAndWait carries it back.
        ReplayPanel[] panel = new ReplayPanel[1];

        javax.swing.SwingUtilities.invokeAndWait(() -> panel[0] = new ReplayPanel());

        java.util.List<DatePicker> pickers = pickersIn(panel[0]);

        assertTrue(pickers.size() >= 2,
                "the fixture is wrong: the transport has " + pickers.size() + " date pickers");

        DatePicker from = pickers.get(0);
        DatePicker until = pickers.get(1);

        javax.swing.SwingUtilities.invokeAndWait(() -> from.setDate(MONDAY));
        javax.swing.SwingUtilities.invokeAndWait(() -> until.setDate(MONDAY.plusYears(3)));

        // The correction now happens in the next event, so drain it.
        javax.swing.SwingUtilities.invokeAndWait(() -> { });

        assertTrue(until.date() != null && !until.date().isAfter(
                        MONDAY.plusDays(ReplayPreferences.windowDays())),
                "three years past the start was left standing: " + until.date());
    }

    private static ReplaySession over(LocalDate from, LocalDate to) {
        return new ReplaySession(base, from, to, 0);
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
    void weekendOnlyHasNothingToPlay() {
        // It used to have something: the replay answered every date with a
        // random walk, so a Saturday came back with a full session that never
        // happened. The old comment here said an empty transport with no reason
        // given is the worst answer -- and it is, but the fix is to GIVE the
        // reason, not to invent a market.
        LocalDate saturday = MONDAY.plusDays(5);
        ReplaySession weekend = over(saturday, saturday.plusDays(1));

        assertEquals(0, barsOf(weekend),
                "a Saturday was replayed; the base has no session on one");
        assertTrue(weekend.isEmpty(), "the transport has to be able to say it has nothing");
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
        // THE DATES AS THE WINDOW WRITES THEM, and the preposition out of the
        // bundle. This used to build the string in Java with the Portuguese
        // " a " hard coded, so the English build titled a chart
        // "WINFUT 2026-08-31 a 2026-09-04" -- and in ISO, which was a third
        // date format beside the dd/MM/yyyy of the picker and the dd/MM of the
        // end label.
        assertEquals("31/08/2026", over(MONDAY, MONDAY).rangeText());
        assertEquals(br.com.jorge.reis.endeavourneo.platform.Messages.get(
                        "replay.range", "31/08/2026", "04/09/2026"),
                over(MONDAY, MONDAY.plusDays(4)).rangeText());

        // And the preposition really comes from the bundle, in whatever
        // language is loaded: the two dates with something between them.
        String range = over(MONDAY, MONDAY.plusDays(4)).rangeText();

        assertTrue(range.startsWith("31/08/2026") && range.endsWith("04/09/2026")
                && range.length() > "31/08/202604/09/2026".length(), range);
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
        // A CEILING WITH NOTHING UNDER IT was what stood here:
        //
        //   int tenYears = barsOf(over(MONDAY, MONDAY.plusYears(10)));
        //   assertTrue(tenYears <= MOST_SESSIONS * oneDay, ...);
        //
        // The fixture holds a handful of days, so ten years OF THE FIXTURE is a
        // handful too, far under any cap. Deleting MOST_SESSIONS from the
        // product left that assertion true and this test green. The cap is a
        // property of the RANGE, not of how much data is on disk, so it is asked
        // of the range.
        assertEquals(ReplaySession.MOST_SESSIONS,
                ReplaySession.sessionsIn(MONDAY, MONDAY.plusYears(10)).size(),
                "ten years was not pulled back to the cap");
        assertEquals(1, ReplaySession.sessionsIn(MONDAY, MONDAY).size(),
                "one day is one session");
        assertEquals(5, ReplaySession.sessionsIn(MONDAY, MONDAY.plusDays(4)).size(),
                "Monday to Friday is five sessions");
        assertEquals(5, ReplaySession.sessionsIn(MONDAY, MONDAY.plusDays(6)).size(),
                "the weekend was counted as sessions");
    }
}
