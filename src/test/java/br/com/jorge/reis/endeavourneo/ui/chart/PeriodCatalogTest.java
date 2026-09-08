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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
    @DisplayName("renko vai de 3R a 101R")
    void renkoBounds() {
        // The bottom moved when the sizing was corrected to the reference
        // product's formula, (n x tick) - tick. 2R is one tick by that formula,
        // and a one-tick brick lays one on every price change -- the tape drawn
        // as boxes. So the smallest offered is 3R, which is two ticks.
        assertFalse(offersRenko("1"),
                "a one-tick brick lays one on every price change, which is the tape as boxes");
        assertFalse(offersRenko("2"), "2R e um tijolo de um tick");
        assertTrue(offersRenko("3"));
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
        // The half of the name that says a zero SEARCHES. Without this the
        // loop below runs zero times over an empty list and passes -- and a
        // guard in forText that answered List.of() for anything starting with a
        // zero, which is a plausible wrong way to stop a zero-minute scale
        // being built, would empty the list on the first keystroke of "10" or
        // "30" with this test still green.
        assertFalse(PeriodCatalog.forText("0").isEmpty(),
                "typing a zero offered nothing at all");

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
        // 6R is (6 x 5) - 5 = 25 points, not 30. This test used to say 30 and
        // was right about the code and wrong about the product: it pinned the
        // off-by-one-tick that made every renko here disagree with Profit.
        assertTrue(six.get(1).description().contains("25 pts"),
                "6R mede 25 pontos, e a lista tem que dizer isso: "
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
    @Test
    @DisplayName("nR mede um tick a MENOS que o nome sugere, como no Profit")
    void theBrickIsOneTickSmallerThanItsName() {
        // The reference product's own formula:
        //
        //     tamanho = (n x tick) - tick
        //
        // On the mini index a tick is five points, so 5R is (5 x 5) - 5 = 20 --
        // four ticks, not five. This program computed n x tick and was one tick
        // too big at every size. Nothing on screen would have shown it: a
        // 55-point renko is a perfectly good chart, it is simply not the one
        // "11R" asks for, and every count taken from it disagreed with Profit
        // by an amount nobody could see.
        assertEquals(20.0, PeriodCatalog.brickOf(5), 1e-9, "5R deveria medir 20 pontos");
        assertEquals(50.0, PeriodCatalog.brickOf(11), 1e-9, "11R deveria medir 50 pontos");
        assertEquals(10.0, PeriodCatalog.brickOf(3), 1e-9, "3R deveria medir 10 pontos");

        // And it reaches the chart, not just the arithmetic.
        PeriodCatalog.Choice eleven = PeriodCatalog.byCode("11R");

        assertNotNull(eleven, "11R nao esta no catalogo");
        assertTrue(eleven.aggregation() instanceof Renko,
                "11R nao produziu um renko");
        assertEquals(50.0, ((Renko) eleven.aggregation()).brick(), 1e-9,
                "o renko de 11R nao foi construido com 50 pontos");
        assertTrue(eleven.title().contains("50"),
                "o titulo nao diz o tamanho certo: " + eleven.title());
    }

    @Test
    @DisplayName("2R nao e oferecido, porque seria um tijolo de um tick")
    void theOneTickBrickIsNotOffered() {
        // By the formula, 2R is (2 x 5) - 5 = 5 points -- one tick, a brick on
        // every price change. That is the tick tape drawn as boxes, which is
        // the one thing renko exists not to be.
        assertEquals(5.0, PeriodCatalog.brickOf(2), 1e-9);
        assertNull(PeriodCatalog.byCode("2R"), "2R foi oferecido");
        assertNotNull(PeriodCatalog.byCode("3R"), "3R deveria ser o menor oferecido");
    }

    @Test
    @DisplayName("a lista de escalas fala o idioma escolhido, nao portugues fixo")
    void thescalesListFollowsTheChosenLanguage() {
        // Convention nine: screen text never lives in the code here. Every
        // description in this list was a Portuguese literal -- "1 dia",
        // "3 horas (180 minutos)", "11R (renko 10 pts)" -- and the scales list
        // is the one a reader opens most often, so choosing English did nothing
        // to it.
        java.util.Locale was = br.com.jorge.reis.endeavourneo.platform.Messages.getLocale();

        try {
            br.com.jorge.reis.endeavourneo.platform.Messages.setLocale(java.util.Locale.ROOT);

            // The day, the hour and the renko: one of each shape the list draws.
            assertEquals("1 day", PeriodCatalog.byCode("D1").description());
            assertEquals("1 hour (60 minutes)", PeriodCatalog.byCode("1h").description(),
                    "the hour row is not coming from the bundle");
            assertEquals("11R (renko 50 pts)", PeriodCatalog.byCode("11R").description(),
                    "the renko row is not coming from the bundle");

            br.com.jorge.reis.endeavourneo.platform.Messages.setLocale(
                    java.util.Locale.forLanguageTag("pt-BR"));

            assertEquals("1 dia", PeriodCatalog.byCode("D1").description(),
                    "the description did not follow the language: it is a literal in the "
                            + "code and reads the same whatever is chosen");
        } finally {
            br.com.jorge.reis.endeavourneo.platform.Messages.setLocale(was);
        }
    }
}
