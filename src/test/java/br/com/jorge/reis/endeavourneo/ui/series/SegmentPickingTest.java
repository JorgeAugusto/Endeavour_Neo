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
package br.com.jorge.reis.endeavourneo.ui.series;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Segment;

import java.awt.event.KeyEvent;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Choosing where a segment starts and ends, measured in sessions.
 */
@DisplayName("Escolher um segmento")
class SegmentPickingTest {

    /** Ten sessions: two working weeks, so the weekends are really missing. */
    private static List<LocalDate> tenSessions() {
        List<LocalDate> days = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 1, 5);

        while (days.size() < 10) {
            if (day.getDayOfWeek().getValue() <= 5) {
                days.add(day);
            }

            day = day.plusDays(1);
        }

        return days;
    }

    private static SeriesMap mapped() {
        SeriesMap map = new SeriesMap();

        map.showSeries(tenSessions(), List.of());

        return map;
    }

    @Test
    @DisplayName("conta pregoes, nao dias de calendario")
    void countsSessions() {
        SeriesMap map = mapped();

        // Monday to the Friday after next: eleven calendar days, ten sessions.
        assertEquals(10, map.sessionsBetween(LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 16)));
        assertEquals(5, map.sessionsBetween(LocalDate.of(2026, 1, 5),
                LocalDate.of(2026, 1, 9)));
    }

    @Test
    @DisplayName("um fim de semana inteiro nao vale nenhum pregao")
    void aWeekendIsWorthNothing() {
        assertEquals(0, mapped().sessionsBetween(LocalDate.of(2026, 1, 10),
                LocalDate.of(2026, 1, 11)));
    }

    @Test
    @DisplayName("uma data antes da serie comeca no primeiro pregao dela")
    void beforeTheSeriesStartsAtItsFirst() {
        assertEquals(10, mapped().sessionsBetween(LocalDate.of(2020, 1, 1),
                LocalDate.of(2026, 1, 16)));
    }

    @Test
    @DisplayName("um fim antes do inicio nao conta nada")
    void backwardsIsNothing() {
        assertEquals(0, mapped().sessionsBetween(LocalDate.of(2026, 1, 16),
                LocalDate.of(2026, 1, 5)));
    }

    // ------------------------------------------------------------- the handles

    private static RangeBar bar() {
        RangeBar bar = new RangeBar();

        bar.setSize(520, 30);
        bar.setRange(10, 2, 6);

        return bar;
    }

    @Test
    @DisplayName("as alcas nao se cruzam")
    void theHandlesDoNotCross() {
        RangeBar bar = bar();

        bar.setRange(10, 8, 3);

        assertTrue(bar.to() >= bar.from(),
                "the end went in front of the start, which is not a range");
    }

    @Test
    @DisplayName("nenhuma alca sai da serie")
    void neitherHandleLeavesTheSeries() {
        RangeBar bar = bar();

        bar.setRange(10, -5, 99);

        assertEquals(0, bar.from());
        assertEquals(9, bar.to(), "the last session is index nine, not ten");
    }

    @Test
    @DisplayName("o teclado move a alca que esta sendo dirigida")
    void theKeyboardDrivesOneHandle() {
        RangeBar bar = bar();
        int[] told = {0};

        bar.onChange(() -> told[0]++);

        // Space chooses which handle; it starts on the first.
        press(bar, KeyEvent.VK_RIGHT);

        assertEquals(3, bar.from(), "the start should have moved by one session");
        assertEquals(6, bar.to());
        assertEquals(1, told[0], "the change went unannounced");

        press(bar, KeyEvent.VK_SPACE);
        press(bar, KeyEvent.VK_RIGHT);

        assertEquals(3, bar.from());
        assertEquals(7, bar.to(), "space did not pass the keyboard to the other handle");
    }

    @Test
    @DisplayName("o teclado tambem nao deixa cruzar")
    void theKeyboardCannotCrossEither() {
        RangeBar bar = bar();

        for (int i = 0; i < 20; i++) {
            press(bar, KeyEvent.VK_RIGHT);
        }

        assertEquals(bar.to(), bar.from(),
                "the start ran past the end instead of stopping on it");
    }

    private static void press(RangeBar bar, int code) {
        bar.nudge(code);
    }

    @Test
    @DisplayName("o mapa e o trilho medem a mesma coisa no mesmo lugar")
    void bothMeasureTheSame() {
        // The whole promise of putting one under the other. A pixel apart is
        // enough for a handle to sit beside the edge it is moving instead of on
        // it, and that reads as a bug in the data.
        SeriesMap map = mapped();
        RangeBar bar = new RangeBar();

        map.setSize(520, 60);
        bar.setSize(520, 30);
        bar.setRange(10, 0, 9);

        for (int i = 0; i <= 10; i++) {
            assertEquals(map.edgeOf(i), bar.edgeOf(i),
                    "session " + i + " is drawn in two places");
        }
    }

    // ------------------------------------------------------------ the segments

    @Test
    @DisplayName("asking for the series window twice gives the same window, not two")
    void onlyOneSeriesWindowIsEverOpen() throws Exception {
        // This window is modeless, and Segmentation.set REPLACES a series' whole
        // list of segments. Two of them over the same series meant two lists,
        // and whichever was closed last wrote its own over the other's: a
        // segment created in one simply stopped existing when the other went
        // away, with nothing said.
        org.junit.jupiter.api.Assumptions.assumeFalse(
                java.awt.GraphicsEnvironment.isHeadless(), "no screen");

        try {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                SeriesWindow.open(null);
                SeriesWindow.open(null);
                SeriesWindow.open(null);
            });

            long open = java.util.Arrays.stream(java.awt.Window.getWindows())
                    .filter(each -> each instanceof SeriesWindow)
                    .filter(java.awt.Window::isDisplayable)
                    .count();

            assertEquals(1, open, "asking three times left " + open + " series windows open, "
                    + "each with its own copy of the segments");
        } finally {
            javax.swing.SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window each : java.awt.Window.getWindows()) {
                    if (each instanceof SeriesWindow) {
                        each.dispose();
                    }
                }
            });
        }
    }

    /** @return what the map paints, so the test can look at it rather than at itself */
    private static java.awt.image.BufferedImage painted(List<Segment> existing) {
        SeriesMap map = new SeriesMap();

        map.showSeries(tenSessions(), existing);
        map.setSize(520, 60);
        map.showFresh(new Segment("novo", LocalDate.of(2026, 1, 6),
                LocalDate.of(2026, 1, 7)), true);

        java.awt.image.BufferedImage canvas = new java.awt.image.BufferedImage(
                520, 60, java.awt.image.BufferedImage.TYPE_INT_ARGB);

        map.paint(canvas.getGraphics());

        return canvas;
    }

    @Test
    @DisplayName("o mapa desenha os segmentos que ja existem sem reclamar dos abertos")
    void anOpenEndedSegmentIsDrawnToTheEnd() {
        // THE ASSERTION HERE USED TO BE assertEquals(10, map.days().size()),
        // which is the number showSeries was just handed and which nothing in
        // the painting can change. The name promises the map DRAWS the segment
        // to the end; the test only proved that painting did not throw.
        //
        // So it looks at the ink. An open-ended segment runs to the last session,
        // and the way to say that without knowing any colour is to paint the same
        // map twice -- once with the open segment and once without -- and require
        // the right-hand end to come out different.
        Segment closed = new Segment("treino",
                LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 9));
        Segment open = Segment.from("resto", LocalDate.of(2026, 1, 12));

        java.awt.image.BufferedImage with = painted(List.of(closed, open));
        java.awt.image.BufferedImage without = painted(List.of(closed));

        int rightEnd = 520 - SeriesMap.SIDE - 2;
        int middle = 2 + 34 / 2;

        assertNotEquals(without.getRGB(rightEnd, middle), with.getRGB(rightEnd, middle),
                "the open-ended segment was not drawn at the far end of the map");

        // And it is not painting the WHOLE track: the days before it must look
        // the same either way, or this is measuring a background change.
        int beforeIt = SeriesMap.SIDE + 2;

        assertEquals(without.getRGB(beforeIt, middle), with.getRGB(beforeIt, middle),
                "adding one segment repainted the days before it as well");
    }

    @Test
    @DisplayName("editar um segmento EM DIANTE sem mexer nele nao o fecha")
    void editinganopenSegmentDoesNotCloseIt() {
        // An open segment -- no end, shown as "em diante" -- comes into the
        // dialog with its end handle parked on the last session known, because a
        // handle has to be somewhere. Building a two-date segment from that
        // CLOSED it: opening Edit, changing nothing and pressing OK turned "from
        // here on" into "up to the last session that happened to be on disk",
        // and the only sign was the table showing a date where it used to show
        // two words.
        //
        // The difference is real further down: a chart of an open segment reads
        // to the end of the file, and a closed one stops at a fixed instant. So
        // the segment kept for testing quietly stops covering everything
        // imported after the day somebody opened its dialog -- and the whole
        // point of that segment is that it goes on being the part nobody looked
        // at.
        List<LocalDate> days = tenSessions();

        assertNull(SegmentDialog.endFor(true, days.size() - 1, days),
                "an open segment whose end handle was never moved came out closed at the "
                        + "last session on disk");

        // Moving the end off the last session closes it, which is the reader
        // saying so.
        assertEquals(days.get(days.size() - 2),
                SegmentDialog.endFor(true, days.size() - 2, days),
                "the segment stayed open after the reader moved its end");

        // And a segment that arrived closed stays closed wherever its handle is,
        // including on the last session.
        assertEquals(days.get(days.size() - 1),
                SegmentDialog.endFor(false, days.size() - 1, days),
                "a closed segment was opened by being dragged to the end");
    }
}
