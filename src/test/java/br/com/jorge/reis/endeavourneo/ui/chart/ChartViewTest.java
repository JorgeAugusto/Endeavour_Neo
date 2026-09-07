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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the reader was looking, and what puts them back there.
 *
 * <p>The gestures themselves are not driven here — real mouse events through a
 * component are slow and flaky, and this file follows {@code ChartCanvasTest} in
 * pulling the part that can be wrong out of the listener. What cannot be pulled
 * out is read from the source, the way {@code TickLibraryClosingTest} reads it.</p>
 */
@DisplayName("A vista do grafico")
class ChartViewTest {

    private static final Path CANVAS = Path.of("src", "main", "java", "br", "com", "jorge",
            "reis", "endeavourneo", "ui", "chart", "ChartCanvas.java");

    @Test
    @DisplayName("a barra sob o cursor fica sob o cursor, e a fracao e a do GRAFICO")
    void theBarUnderTheCursorStays() {
        // The plot is the component less the price axis, and the fraction used
        // to divide by the whole width -- about sixty pixels more. At 640 wide
        // the fraction came out a tenth too small, so the anchored bar slid left
        // at every step of zoom, against the comment that promises it stays.
        //
        // Cursor at the right edge of a 578-wide plot inside a 640-wide
        // component, zooming to 100 bars: the anchor has to end up at the right
        // edge of the window, which means the window starts a hundred bars
        // before it.
        assertEquals(400, ChartCanvas.firstBarForZoom(500, 578, 578, 100),
                "the bar under the cursor did not stay under it");

        // The middle of the plot leaves half the window on each side.
        assertEquals(450, ChartCanvas.firstBarForZoom(500, 289, 578, 100));

        // And the left edge puts the anchor first.
        assertEquals(500, ChartCanvas.firstBarForZoom(500, 0, 578, 100));
    }

    @Test
    @DisplayName("dividir pela largura do componente desloca a ancora")
    void dividingByTheComponentSlides() {
        // The defect, stated as the difference it makes. Same cursor, same zoom:
        // the plot fraction is 1,0 and the component fraction is 0,903, and the
        // window lands ten bars off. Every notch of the wheel adds another ten.
        int right = ChartCanvas.firstBarForZoom(500, 578, 578, 100);
        int wrong = ChartCanvas.firstBarForZoom(500, 578, 640, 100);

        assertTrue(wrong > right, "the fixture does not reproduce the slide");
        assertEquals(10, wrong - right,
                "the slide is not the size the defect says it is");
    }

    @Test
    @DisplayName("o botao DIREITO nao arrasta o grafico")
    void therightButtonDoesNotPan() {
        // installContextMenu registers a second adapter for the popup trigger,
        // and JPopupMenu.show does not stop the event reaching the first one:
        // the right button armed the pan on its way to the menu.
        //
        // THIS USED TO READ THE SOURCE. It matched the word isLeftMouseButton
        // inside the first 700 characters of each handler -- comments included
        // -- which says nothing about where the guard sits or what it protects.
        // Moving the jump-to-end block ABOVE the guard puts the defect back in
        // its most visible form and leaves the word exactly where it was; so
        // does reducing the guard to an if without a return. And in the other
        // direction it cried wolf: rewriting the guard as e.getButton() !=
        // BUTTON1 -- the same thing -- made it fail against correct code.
        //
        // Driving the handlers needs no window. The listeners are reachable
        // through getMouseListeners, which is what the component itself hands
        // to the toolkit, and a MouseEvent has a public constructor.
        ChartCanvas canvas = sized(1_000);

        canvas.scrollTo(300);

        int was = canvas.firstVisibleBar();

        press(canvas, java.awt.event.MouseEvent.BUTTON3, 400, 200);
        drag(canvas, 200, 200);

        assertEquals(was, canvas.firstVisibleBar(),
                "the right button armed the drag: on the way to the context menu the "
                        + "reader loses their place");

        // And the left one does, or the assertion above is satisfied by a chart
        // that cannot pan at all.
        press(canvas, java.awt.event.MouseEvent.BUTTON1, 400, 200);
        drag(canvas, 200, 200);

        assertNotEquals(was, canvas.firstVisibleBar(),
                "the left button no longer pans either: the fixture proves nothing");
    }

    @Test
    @DisplayName("dois cliques com o botao DIREITO nao recentram o grafico")
    void adoubleRightClickDoesNotRecentre() {
        // Two clicks put scale, slide and position back. On the right button
        // that meant the reader threw their zoom away on the way to the menu.
        ChartCanvas canvas = sized(1_000);

        canvas.scrollTo(300);

        int was = canvas.firstVisibleBar();

        click(canvas, java.awt.event.MouseEvent.BUTTON3, 2, 400, 200);

        assertEquals(was, canvas.firstVisibleBar(),
                "a double right click recentred the chart, throwing the zoom away");

        click(canvas, java.awt.event.MouseEvent.BUTTON1, 2, 400, 200);

        assertNotEquals(was, canvas.firstVisibleBar(),
                "a double LEFT click no longer recentres: the fixture proves nothing");
    }

    @Test
    @DisplayName("o botao DIREITO na insignia de ir-para-o-fim nao vai para o fim")
    void therightButtonDoesNotJumpToTheEnd() {
        // THE ORDER of the guard, not only its presence. Move the jump block
        // above it -- which looks like nothing, and which the test that read the
        // source could not see, because the word isLeftMouseButton stayed inside
        // the same 700 characters -- and the right button throws the reader to
        // the end of the series on its way to the context menu.
        //
        // The badge sits at the top right of the PLOT, which is the component
        // less the price strip: 800 - 62 wide, a 26-pixel square 14 from each
        // edge, so its middle is at (711, 27).
        ChartCanvas canvas = sized(1_000);

        canvas.scrollTo(300);

        press(canvas, java.awt.event.MouseEvent.BUTTON3, 711, 27);

        assertEquals(300, canvas.firstVisibleBar(),
                "the right button pressed the jump badge and threw the reader to the "
                        + "end of the series");

        // And the left one does jump, which is also what proves the badge is
        // where this test says it is.
        press(canvas, java.awt.event.MouseEvent.BUTTON1, 711, 27);

        assertNotEquals(300, canvas.firstVisibleBar(),
                "the badge is not at these coordinates, so nothing above was tested");
    }

    /** @return a canvas with a size, so the strips are where the pixels say they are */
    private static ChartCanvas sized(int count) {
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSize(800, 600);
        canvas.setSeries(bars(count));

        return canvas;
    }

    private static void press(ChartCanvas canvas, int button, int x, int y) {
        java.awt.event.MouseEvent event = new java.awt.event.MouseEvent(canvas,
                java.awt.event.MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                0, x, y, 1, false, button);

        for (java.awt.event.MouseListener each : canvas.getMouseListeners()) {
            each.mousePressed(event);
        }
    }

    private static void click(ChartCanvas canvas, int button, int times, int x, int y) {
        java.awt.event.MouseEvent event = new java.awt.event.MouseEvent(canvas,
                java.awt.event.MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(),
                0, x, y, times, false, button);

        for (java.awt.event.MouseListener each : canvas.getMouseListeners()) {
            each.mouseClicked(event);
        }
    }

    private static void drag(ChartCanvas canvas, int x, int y) {
        java.awt.event.MouseEvent event = new java.awt.event.MouseEvent(canvas,
                java.awt.event.MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                0, x, y, 0, false, java.awt.event.MouseEvent.NOBUTTON);

        for (java.awt.event.MouseMotionListener each : canvas.getMouseMotionListeners()) {
            each.mouseDragged(event);
        }
    }

    @Test
    @DisplayName("o renko e aceito quando os pregoes estao na FITA, nao so no MetaTrader")
    void theTapeCountsAsRecordedTicks(@org.junit.jupiter.api.io.TempDir Path folder)
            throws IOException {
        // Two answers to one question. sourceForBricks tries the Profit tape
        // first, and says why: it is the trades themselves rather than a quote
        // stream. The guard the dialog asks only looked at MetaTrader, so an
        // instrument whose sessions exist only on the tape was refused by a
        // guard the builder beside it would have satisfied -- and told there are
        // no recorded ticks for the sessions on screen, which is false.
        java.time.LocalDate day = java.time.LocalDate.of(2026, 9, 1);
        Path ticks = folder.resolve("win").resolve("ticks");

        try (br.com.jorge.reis.endeavourneo.domain.market.TapeFile.Writer writer =
                     new br.com.jorge.reis.endeavourneo.domain.market.TapeFile.Writer(
                             br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT
                                     .fileFor(ticks, "win", day), day)) {

            writer.add(9 * 3_600_000, 179_385, 500, 85, 262,
                    br.com.jorge.reis.endeavourneo.domain.market.Aggressor.BUYER);
            writer.broker(85, "BTG");
            writer.broker(262, "MIRAE");
        }

        br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.useFolderForTest(folder);

        boolean was = ChartPreferences.syntheticTicks();

        try {
            // OFF, because that is the setting in which this guard acts at all.
            ChartPreferences.setSyntheticTicks(false);

            ChartCanvas canvas = new ChartCanvas();

            canvas.setInstrument("win-1m");
            canvas.setSeries(oneSessionOn(day));

            assertTrue(canvas.renkoAllowed(),
                    "the session is on the tape and the renko was refused anyway");
        } finally {
            ChartPreferences.setSyntheticTicks(was);
            br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.useFolderForTest(null);
        }
    }

    /** Ten one-minute bars, all on that date. */
    private static br.com.jorge.reis.endeavourneo.domain.market.PriceSeries oneSessionOn(
            java.time.LocalDate day) {
        long open = day.atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli() + 9 * 3_600_000L;

        return new br.com.jorge.reis.endeavourneo.domain.market.PriceSeries() {

            @Override
            public int size() {
                return 10;
            }

            @Override
            public long timeAt(int index) {
                return open + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return 179_000 + index;
            }

            @Override
            public double highAt(int index) {
                return 179_000 + index;
            }

            @Override
            public double lowAt(int index) {
                return 179_000 + index;
            }

            @Override
            public double closeAt(int index) {
                return 179_000 + index;
            }
        };
    }

    /** A series of that many one-minute bars, closing at their own index. */
    private static br.com.jorge.reis.endeavourneo.domain.market.PriceSeries bars(int count) {
        return new br.com.jorge.reis.endeavourneo.domain.market.PriceSeries() {

            @Override
            public int size() {
                return count;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return index;
            }

            @Override
            public double highAt(int index) {
                return index;
            }

            @Override
            public double lowAt(int index) {
                return index;
            }

            @Override
            public double closeAt(int index) {
                return index;
            }
        };
    }

    @Test
    @DisplayName("o grafico reabre onde o leitor estava, e nao no fim da serie")
    void theChartReopensWhereItWasLeft() {
        // storeView promises "how far in, how far along, how stretched, how
        // slid" and wrote every one of those but the position: a chart left in
        // March 2021 came back on the last bar. Its own javadoc says why that
        // matters -- a chart that reopens at the default is a chart that has to
        // be set up again every morning, and setting it up is most of the work.
        ChartCanvas left = new ChartCanvas();

        left.setSeries(bars(1_000));
        left.scrollTo(300);

        br.com.jorge.reis.endeavourneo.platform.Settings into =
                br.com.jorge.reis.endeavourneo.platform.Settings.workspace();

        left.storeView(into, "chartViewTest.");

        // Overnight the series grew by a session of minutes. The position is
        // stored as the distance from the END for exactly this reason: a raw
        // index would point somewhere else every morning.
        ChartCanvas reopened = new ChartCanvas();

        reopened.setSeries(bars(1_566));
        reopened.restoreView(into, "chartViewTest.");

        assertEquals(1_566 - (1_000 - 300), reopened.firstVisibleBar(),
                "the chart did not reopen where it was left");
    }

    @Test
    @DisplayName("ligar e desligar as caldas do renko nao mexe no zoom nem na posicao")
    void togglingTheWicksKeepsTheView() {
        // The tails are Renko.withWicks -- decoration of a bar, same brick count
        // either side of the switch. Flipping it went through setPeriod into
        // refold, which re-framed the chart as if the series were new: the
        // reader lost their zoom and their place to a switch that changes how a
        // brick is drawn.
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(bars(2_000));
        canvas.setPeriod(new br.com.jorge.reis.endeavourneo.domain.market.Renko(10, 2, true),
                "10R", "10R");
        canvas.scrollTo(40);

        int was = canvas.firstVisibleBar();

        canvas.setWicks(false);

        assertEquals(was, canvas.firstVisibleBar(),
                "turning the tails off moved the reader");

        canvas.setWicks(true);

        assertEquals(was, canvas.firstVisibleBar(),
                "turning the tails back on moved the reader");
    }

    @Test
    @DisplayName("o replay pede o pregao antes de precisar dele, e nao redesenha o que nao mudou")
    void theReplayAsksAheadAndRedrawsOnlyWhatMoved() throws IOException {
        // TickRenko.advance calls load(), which reads the session from disk when
        // it is not resident -- ninety megabytes, on the interface thread, at
        // the instant the replay crosses midnight. And live() rebuilt the whole
        // brick series on every frame, twenty-five times a second, for a chart
        // that had not changed.
        String source = Files.readString(CANVAS, StandardCharsets.UTF_8);
        int at = source.indexOf("private boolean extendBricks() {");

        assertTrue(at > 0, "extendBricks is not where this looks");

        String body = source.substring(at, at + 2_600);

        assertTrue(body.contains("growingFrom.request(day)"),
                "the session is not asked for before advance goes looking");
        assertTrue(body.contains("growingFrom.request(day.plusDays(1))"),
                "the next session is never asked for, so midnight blocks");
        assertTrue(body.contains("if (!growing.advance(day, now + 1)"),
                "the answer from advance is thrown away and the view rebuilt anyway");
    }

    @Test
    @DisplayName("a biblioteca de ticks nunca e trocada sem ser fechada")
    void theTickLibraryIsClosedBeforeItIsReplaced() throws IOException {
        // Two workers landing one after the other dropped the earlier library on
        // the floor: a reading thread and up to three sessions of ticks -- 340
        // MB -- held for the life of the application, once per race.
        String source = Files.readString(CANVAS, StandardCharsets.UTF_8);
        int at = source.indexOf("growingFrom = library;");

        assertTrue(at > 0, "the assignment this is about is gone");

        String before = source.substring(Math.max(0, at - 600), at);

        assertTrue(before.contains("stopGrowing();"),
                "the library is assigned without closing what was there");
    }
}
