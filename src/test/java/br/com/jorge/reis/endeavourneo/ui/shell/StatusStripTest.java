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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
            if (each instanceof JLabel label && each.getX() >= 0
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
}
