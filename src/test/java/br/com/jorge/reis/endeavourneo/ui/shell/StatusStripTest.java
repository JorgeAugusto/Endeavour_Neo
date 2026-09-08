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
package br.com.jorge.reis.endeavourneo.ui.shell;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the footer gives up first when the window is not wide enough.
 *
 * <p>The assertions are about ORDER and not pixels. How wide a label is depends
 * on the font the machine happens to have, so a test written against measured
 * widths would pass here and fail on the next computer — and it would be
 * testing the font rather than the rule.</p>
 */
@DisplayName("Barra de status")
class StatusStripTest {

    private static final String CHART = "WINFUT-FULL  ·  5m";

    private static final String READING = "05/09 03:47   100,42";

    private static final String MODE = "Medindo";

    private StatusBar bar;

    @BeforeEach
    void setUp() throws Exception {
        bar = new StatusBar();

        bar.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11));
        bar.say("Segmento \"Estudos\" salvo: 1.048.221 barras.");
        bar.chart(CHART, READING, MODE);

        settle();
    }

    /** The bar writes to itself through the interface thread, even from here. */
    private static void settle() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /**
     * @return the labels actually on screen, left to right
     *
     * <p>Read off the component bounds rather than from a field, because being
     * ON SCREEN is the thing under test: the layout parks what it drops at a
     * negative x rather than hiding it, so that a window growing back brings it
     * with it.</p>
     */
    private List<String> onScreen(int width) {
        bar.setSize(width, 24);
        bar.doLayout();

        List<String> found = new ArrayList<>();

        for (Component each : bar.getComponents()) {
            // WIDTH as well as position. Reading only getX() >= 0 meant a label
            // squeezed to nothing counted as being on screen: setBounds(x, y, 0,
            // h) passed every test in this file, including the one below that
            // says the message never goes. "Parked off to the left" and "here but
            // zero pixels wide" are the same thing to a reader, and were opposite
            // things to this method.
            if (each instanceof JLabel label && each.getX() >= 0 && each.getWidth() > 0
                    && !label.getText().isBlank()) {
                found.add(label.getText());
            }
        }

        return found;
    }

    @Test
    @DisplayName("numa janela larga cabe tudo")
    void wideEnoughForEverything() {
        List<String> shown = onScreen(2000);

        assertTrue(shown.contains(CHART));
        assertTrue(shown.contains(READING));
        assertTrue(shown.contains(MODE));
    }

    @Test
    @DisplayName("apertando, os campos caem da direita para a esquerda")
    void theyDropFromTheRight() {
        List<String> order = new ArrayList<>();

        // Down from wide to nothing, noting each field the first time it is
        // gone. The field nearest the job goes first: dropping the leftmost
        // instead would take away WHICH CHART this is about and leave the
        // numbers with no subject.
        for (int width = 2000; width >= 40; width -= 10) {
            List<String> shown = onScreen(width);

            for (String field : List.of(CHART, READING, MODE)) {
                if (!shown.contains(field) && !order.contains(field)) {
                    order.add(field);
                }
            }
        }

        assertEquals(List.of(MODE, READING, CHART), order,
                "the fields did not give way in the order they should");
    }

    @Test
    @DisplayName("um campo que ja caiu nao volta enquanto a janela encolhe")
    void droppingIsMonotone() {
        int before = Integer.MAX_VALUE;

        for (int width = 2000; width >= 40; width -= 10) {
            int now = onScreen(width).size();

            assertTrue(now <= before,
                    "a field came back at " + width + " px while the window was shrinking");

            before = now;
        }
    }

    @Test
    @DisplayName("a mensagem nunca some, por mais estreito que fique")
    void theMessageStays() {
        assertTrue(onScreen(120).stream().anyMatch(each -> each.startsWith("Segmento")),
                "the message was dropped, and it is the one thing this bar is for");
    }

    @Test
    @DisplayName("a mensagem tem largura de verdade, nao so uma posicao")
    void theMessageKeepsItsFloor() {
        // What the test above could not say. It asked whether the message was on
        // screen, and "on screen" was read off the x alone -- so a message
        // squeezed to zero pixels satisfied it. The bar promises the message a
        // floor of width and drops fields from the right to pay for it; that
        // floor is the thing to assert.
        for (int width : new int[]{2000, 600, 300, 160, 120}) {
            bar.setSize(width, 24);
            bar.doLayout();

            int message = -1;

            for (Component each : bar.getComponents()) {
                if (each instanceof JLabel label && label.getText().startsWith("Segmento")) {
                    message = each.getWidth();
                }
            }

            assertTrue(message > 0,
                    "at " + width + " px the message was " + message + " pixels wide, which "
                            + "is not on screen however positive its x is");
        }
    }

    @Test
    @DisplayName("campo vazio nao vira um traco, some")
    void anEmptyFieldIsNotShown() throws Exception {
        bar.chart(CHART, "", "");

        settle();

        List<String> shown = onScreen(2000);

        assertFalse(shown.contains(READING), "the pointer left and the reading stayed");
        assertFalse(shown.contains(MODE));
        assertTrue(shown.contains(CHART),
                "the chart's name went with the pointer, so the bar flickers when the "
                        + "mouse crosses the axis");
    }

    @Test
    @DisplayName("sem grafico algum, sobra so a mensagem")
    void noChartAtAll() throws Exception {
        bar.noChart();

        settle();

        assertEquals(1, onScreen(2000).size());
    }
/**
     * The cancel button stays on the bar, however narrow the window gets.
     *
     * <p>The class header calls the job area the one part of this bar that never
     * drops -- "it is the only part of this bar that can be the reason the
     * program is slow, and hiding it in a small window would hide the cancel
     * button with it". Two things broke that at the same pixel:
     * {@code minimumLayoutSize} declared only the message's floor, so the window
     * could be narrowed past the job area's own width; and the job area was then
     * given less than it needs, whereupon its flow layout wrapped its LAST
     * component -- the cancel button -- onto a line the bar is not tall enough
     * to show.</p>
     *
     * <p>A real service and a real job, held on a latch: what makes the area
     * appear is a job actually running, and a fixture that set the flag by hand
     * would be asking about the fixture.</p>
     */
    @Test
    @DisplayName("apertando ate o osso, o botao de cancelar continua na barra")
    void thecancelButtonSurvivesANarrowWindow() throws Exception {
        java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch started =
                new java.util.concurrent.CountDownLatch(1);

        try (br.com.jorge.reis.endeavourneo.platform.JobService jobs =
                new br.com.jorge.reis.endeavourneo.platform.JobService()) {

            jobs.submit("dobrando seis anos de minutos", progress -> {
                started.countDown();
                hold.await();

                return null;
            });

            assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS),
                    "the job never started");

            SwingUtilities.invokeAndWait(() -> bar.bind(jobs));
            settle();

            java.awt.Container area = null;

            for (Component each : bar.getComponents()) {
                if (each instanceof java.awt.Container held && !(each instanceof JLabel)
                        && held.getComponentCount() == 3) {
                    area = held;
                }
            }

            assertNotNull(area, "the job area is not on the bar while a job is running");

            int wanted = area.getPreferredSize().width;

            // Narrower than the job area needs all by itself.
            bar.setSize(wanted / 2, 24);
            bar.doLayout();

            assertTrue(area.getWidth() >= wanted,
                    "the job area was squeezed to " + area.getWidth() + " of the "
                            + wanted + " it needs, and the cancel button wraps out of sight");

            assertTrue(bar.getMinimumSize().width >= wanted,
                    "the bar tells the window it may be narrower than the job area");

            hold.countDown();
        }
    }
/**
     * An icon that cannot be drawn is refused where it is asked for.
     *
     * <p>A size of zero gives {@code graphics.create(x, y, 0, 0)} and an icon
     * nobody can see; a null glyph throws on the first PAINT, far from the call
     * that asked for it and on the thread that was drawing.</p>
     */
    @Test
    @DisplayName("um icone de tamanho zero e recusado onde e pedido")
    void aniconOfNoSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Icons.candle(0));
        assertThrows(IllegalArgumentException.class, () -> Icons.line(-3));

        // And an ordinary size still answers, or the guard refuses everything.
        assertNotNull(Icons.candle(16));
    }
}
