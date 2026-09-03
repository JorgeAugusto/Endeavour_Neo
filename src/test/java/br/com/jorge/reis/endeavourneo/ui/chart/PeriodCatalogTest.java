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
package br.com.jorge.reis.endeavourneo.ui.chart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Renko;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What typing a number is allowed to offer.
 *
 * <p>The bounds matter more than they look: an offer the reader can pick and
 * that then draws nothing useful is discovered by clicking, never by reading.</p>
 */
@DisplayName("Period catalog")
class PeriodCatalogTest {

    private static boolean offersRenko(String typed) {
        return PeriodCatalog.forText(typed).stream()
                .anyMatch(choice -> choice.aggregation() instanceof Renko);
    }

    private static boolean offersMinutes(String typed) {
        return PeriodCatalog.forText(typed).stream()
                .anyMatch(choice -> choice.aggregation() instanceof Timeframe);
    }

    @Test
    @DisplayName("renko runs from two ticks to a hundred and one")
    void renkoBounds() {
        assertFalse(offersRenko("1"),
                "a one-tick brick lays one on every price change, which is the tape as boxes");
        assertTrue(offersRenko("2"));
        assertTrue(offersRenko("101"));
        assertFalse(offersRenko("102"));
        assertFalse(offersRenko("500"));
    }

    @Test
    @DisplayName("minutes run from one to a month, whatever number is typed")
    void minuteBounds() {
        assertTrue(offersMinutes("1"));

        // Seven is a perfectly good scale, and the enum this replaced could not
        // express it: typing 7 used to offer renko and nothing else.
        assertTrue(offersMinutes("7"), "an odd number of minutes has to be offered too");
        assertTrue(offersMinutes("43200"), "a month of minutes is the far end");
        assertFalse(offersMinutes("43201"), "past a month there is nothing to fold into");
    }

    @Test
    @DisplayName("typing a zero searches, and never builds a zero-minute scale")
    void zeroSearchesRatherThanBuilds() {
        // "0" is somebody halfway through typing "10" or "30", so the list
        // searching by name is right. What it must never do is offer a period
        // of no length at all.
        for (PeriodCatalog.Choice choice : PeriodCatalog.forText("0")) {
            assertNotNull(choice.aggregation());
            assertFalse(choice.code().startsWith("0"),
                    "a period of zero was offered: " + choice.code());
        }
    }

    @Test
    @DisplayName("a number that is both offers both, minutes first")
    void bothWhereBothFit() {
        List<PeriodCatalog.Choice> six = PeriodCatalog.forText("6");

        assertEquals(2, six.size());
        assertTrue(six.get(0).aggregation() instanceof Timeframe,
                "minutes come first: it is what a bare number usually means");
        assertTrue(six.get(1).aggregation() instanceof Renko);
        assertTrue(six.get(1).description().contains("30 pts"),
                "six ticks is thirty points, and the list has to say so: "
                        + six.get(1).description());
    }

    @Test
    @DisplayName("every offer builds something -- none is a dead end")
    void nothingIsADeadEnd() {
        // The whole reason for the bounds. A row that can be picked and then
        // produces nothing is found by clicking, never by reading.
        for (String typed : new String[]{"", "1", "2", "5", "7", "60", "101", "200", "ren"}) {
            for (PeriodCatalog.Choice choice : PeriodCatalog.forText(typed)) {
                assertNotNull(choice.aggregation(), "dead row for " + typed + ": "
                        + choice.description());
            }
        }
    }

    @Test
    @DisplayName("the list before anything is typed runs from a minute to a month")
    void theOpeningList() {
        List<PeriodCatalog.Choice> all = PeriodCatalog.forText("");

        assertEquals(Timeframe.ONE_MINUTE, all.get(0).aggregation());
        assertTrue(all.stream().anyMatch(c -> c.aggregation() == Timeframe.MONTHLY),
                "the month is the far end and has to be in the list");
        assertTrue(all.stream().anyMatch(c -> c.aggregation() instanceof Renko));
    }

    @Test
    @DisplayName("renko offered from the list is drawn with its forming brick")
    void listedRenkoAnimates() {
        // Off in the domain, on for the chart. If the catalogue forgot to ask,
        // renko would go back to standing still between bricks.
        PeriodCatalog.forText("6").stream()
                .filter(choice -> choice.aggregation() instanceof Renko)
                .forEach(choice -> assertTrue(((Renko) choice.aggregation()).hasForming(),
                        "the chart's renko has nothing that moves"));
    }

    @Test
    @DisplayName("a scale outside the bounds is not built at all")
    void outsideIsNull() {
        assertNull(Timeframe.ofMinutes(0));
        assertNull(Timeframe.ofMinutes(-5));
        assertNull(Timeframe.ofMinutes(Timeframe.MOST_MINUTES + 1));
        assertEquals(Timeframe.FIVE_MINUTES, Timeframe.ofMinutes(5),
                "a known scale must come back as the shared one, not a copy");
    }
}
