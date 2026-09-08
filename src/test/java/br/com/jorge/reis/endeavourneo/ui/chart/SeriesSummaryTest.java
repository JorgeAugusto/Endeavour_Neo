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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The few lines that say what a series is.
 */
@DisplayName("Series summary")
class SeriesSummaryTest {

    private static final ZoneId ZONE = ZoneId.systemDefault();

    /** Bars every minute of the trading hours, over the given dates. */
    private static PriceSeries over(int perDay, LocalDate... days) {
        long[] times = new long[days.length * perDay];
        int at = 0;

        for (LocalDate day : days) {
            long open = LocalDateTime.of(day, java.time.LocalTime.of(9, 0))
                    .atZone(ZONE).toInstant().toEpochMilli();

            for (int i = 0; i < perDay; i++) {
                times[at++] = open + i * 60_000L;
            }
        }

        return new PriceSeries() {

            @Override
            public int size() {
                return times.length;
            }

            @Override
            public long timeAt(int index) {
                return times[index];
            }

            @Override
            public double openAt(int index) {
                return 100_000;
            }

            @Override
            public double highAt(int index) {
                return 100_100;
            }

            @Override
            public double lowAt(int index) {
                return 99_900;
            }

            @Override
            public double closeAt(int index) {
                return 100_050;
            }
        };
    }

    private static String valueOf(List<String[]> rows, String label) {
        for (String[] row : rows) {
            if (row[0].equals(br.com.jorge.reis.endeavourneo.platform.Messages.get(label))) {
                return row[1];
            }
        }

        return null;
    }

    @Test
    @DisplayName("it counts sessions, not bars")
    void sessionsAreDaysNotBars() {
        // Three days of five hundred bars each is three sessions, and the
        // difference is the whole reason the line exists: 1.494 sessions and
        // 824.881 bars say different things about the same series.
        PriceSeries series = over(500,
                LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 5), LocalDate.of(2021, 1, 6));

        assertEquals(3, SeriesSummary.sessionsIn(series));

        List<String[]> rows = SeriesSummary.rowsFor(series, "winfull-1m", "1m", false);

        assertEquals("3", valueOf(rows, "summary.sessions"));

        // Through the same formatter the product uses. "1.500" is the thousands
        // separator of pt_BR; under en_US it is "1,500", and the bundle's base
        // language is English -- so the day the suite runs in the base language
        // this failed for no reason, pointing at the wrong place.
        assertEquals(java.text.NumberFormat.getInstance().format(1500),
                valueOf(rows, "summary.bars"));
    }

    @Test
    @DisplayName("the span is written in the parts that are not zero")
    void theSpanSkipsTheEmptyParts() {
        // "6 anos, 0 meses e 0 dias" reads as a form to be filled in rather
        // than as an answer.
        // FROM THE BUNDLE, not written out here. These used to be the pt_BR
        // strings copied by hand -- "6 anos" -- which pins the language the
        // suite happens to have loaded. The bundle's base language is English
        // and the project is going open source: on that day these failed for no
        // reason at all, and said "expected: 6 anos", which points at the wrong
        // place.
        String years = br.com.jorge.reis.endeavourneo.platform.Messages.get("summary.years", 6);
        String months = br.com.jorge.reis.endeavourneo.platform.Messages.get("summary.months", 2);
        String days = br.com.jorge.reis.endeavourneo.platform.Messages.get("summary.days", 3);
        String tenDays = br.com.jorge.reis.endeavourneo.platform.Messages.get("summary.days", 10);
        String noDays = br.com.jorge.reis.endeavourneo.platform.Messages.get("summary.days", 0);

        assertEquals(years, SeriesSummary.spanBetween(
                LocalDate.of(2020, 9, 1), LocalDate.of(2026, 9, 1)));
        assertEquals(years + ", " + days, SeriesSummary.spanBetween(
                LocalDate.of(2020, 9, 1), LocalDate.of(2026, 9, 4)));
        assertEquals(months + ", " + tenDays, SeriesSummary.spanBetween(
                LocalDate.of(2021, 1, 4), LocalDate.of(2021, 3, 14)));

        // And never nothing at all: one day is "0 days", not an empty line.
        assertEquals(noDays, SeriesSummary.spanBetween(
                LocalDate.of(2021, 1, 4), LocalDate.of(2021, 1, 4)));
    }

    @Test
    @DisplayName("it says whether the bricks came from ticks or from candles")
    void theSourceIsStated() {
        // The line that matters most. Measured on WINFUT, brick 55, one day:
        // 15.100 bricks from candles against 11.886 from ticks over January
        // 2021. Nothing else on screen tells the two apart.
        PriceSeries series = over(100, LocalDate.of(2021, 1, 4));

        String ticks = valueOf(SeriesSummary.rowsFor(series, "winfull-1m", "55R", true),
                "summary.source");
        String candles = valueOf(SeriesSummary.rowsFor(series, "winfull-1m", "55R", false),
                "summary.source");

        // WHICH IS WHICH, and not merely that the two differ. The line under
        // test is a ternary; inverting it keeps the answers different and makes
        // the summary say "candles" for a chart built from ticks. The test named
        // itself after "the line that matters most" and could not tell that
        // apart from the line being right.
        assertNotEquals(ticks, candles,
                "a chart built from ticks and one built from candles read the same");
        assertEquals(
                br.com.jorge.reis.endeavourneo.platform.Messages.get("summary.source.ticks"),
                ticks, "a chart built from TICKS says it was built from something else");
        assertEquals(
                br.com.jorge.reis.endeavourneo.platform.Messages.get("summary.source.candles"),
                candles, "a chart built from CANDLES says it was built from something else");
    }

    @Test
    @DisplayName("an empty series says so instead of inventing dates")
    void anEmptySeriesIsHonest() {
        List<String[]> rows = SeriesSummary.rowsFor(PriceSeries.empty(), "vazia", "1m", false);

        assertEquals("0", valueOf(rows, "summary.bars"));
        assertEquals(null, valueOf(rows, "summary.from"),
                "an empty series was given a first date");
    }

    @Test
    @DisplayName("a name with a bracket does not cut the tooltip in half")
    void theTooltipSurvivesAnAwkwardName() {
        // No instrument is called this today. One typed by the reader tomorrow
        // could be, and a tooltip that swallows half of itself is a defect
        // nobody would connect to a name.
        PriceSeries series = over(10, LocalDate.of(2021, 1, 4));
        String html = SeriesSummary.html(series, "win<b>&", "1m", false);

        assertTrue(html.contains("win&lt;b&gt;&amp;"), "the name went in raw: " + html);
        assertFalse(html.contains("win<b>&amp;"), "a tag from the name reached the tooltip");
    }
    @Test
    @DisplayName("zero por cento nao leva sinal, e o javadoc passou a dizer isso")
    void zeroCarriesNoSign() {
        // The javadoc said "always signed, including the plus", and the code
        // printed "0,00%" bare. The code is right -- a "+0,00%" claims a rise of
        // nothing -- so the sentence moved, not the output. Asserted here so the
        // next reader of that paragraph cannot "fix" it back.
        assertFalse(DayChange.formatChange(0.0).startsWith("+"),
                "zero came out claiming a direction: " + DayChange.formatChange(0.0));
        assertFalse(DayChange.formatChange(0.0).startsWith("-"),
                "zero came out claiming a direction: " + DayChange.formatChange(0.0));

        // And a real move still does carry it, or the assertions above are
        // satisfied by a formatter that signs nothing at all.
        assertTrue(DayChange.formatChange(3.04).startsWith("+"),
                "a rise lost its plus: " + DayChange.formatChange(3.04));
        assertTrue(DayChange.formatChange(-3.04).startsWith("-"),
                "a fall lost its minus: " + DayChange.formatChange(-3.04));
    }
}
