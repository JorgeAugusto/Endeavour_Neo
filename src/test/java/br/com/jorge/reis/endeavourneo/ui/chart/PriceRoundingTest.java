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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How many decimals a price is written with, and who decides.
 *
 * <h2>Three numbers the reader looks at all day, and no test of any of them</h2>
 *
 * <p>The price-axis labels, the tag beside the cursor and the footer all round
 * through {@code formatFor(gridStep(...))}. That rule had no test of its value
 * anywhere in the suite: what existed was a test that read the SOURCE of {@code
 * cursorReading} and asserted that the words {@code formatFor(gridStep(}
 * appeared in it. Every way of getting the rounding wrong that does not move a
 * comma inside {@code cursorReading} passed -- and the rounding does not live
 * in {@code cursorReading}, it lives in {@code formatFor} and {@code
 * niceStep}, thirty lines away and until now untouched by any test.</p>
 *
 * <p><b>One correction to the report that asked for this file.</b> It named
 * {@code step >= 1.0} to {@code step > 1.0} as a one-character break. It is
 * not: for any step of one or more, {@code -log10(step)} is zero or negative,
 * so {@code ceil} of it is zero or negative and the decimals come out zero
 * either way. The two spellings are the same function. The real breaks are the
 * {@code ceil} -- {@code floor} in its place gives one decimal place where a
 * series moving in hundredths needs two, and draws it flatter than it is -- and
 * the cap of six.</p>
 *
 * <p>So the rule is measured here by what it produces, and the footer by what
 * it writes on screen.</p>
 */
@DisplayName("Arredondamento do preco")
class PriceRoundingTest {

    @Test
    @DisplayName("as casas decimais saem do passo da grade, e o passo 1,0 nao tem nenhuma")
    void thedecimalsComeFromTheGridStep() {
        // Fixed decimals cannot be right, and formatFor says so: they either
        // print 177.600,00 on an index or round a currency pair away.
        assertEquals(0, ChartCanvas.formatFor(500).getMinimumFractionDigits(),
                "a step of 500 asked for decimals");

        // A grid step of exactly one is a whole number of points, which is
        // every WIN chart at every ordinary zoom.
        assertEquals(0, ChartCanvas.formatFor(1.0).getMinimumFractionDigits(),
                "a grid step of exactly 1,0 printed a decimal place");

        assertEquals(1, ChartCanvas.formatFor(0.5).getMinimumFractionDigits());

        // ROUNDED UP, and this is the assertion that catches the rounding being
        // got wrong: five hundredths need TWO places, and a floor in place of
        // the ceiling gives one -- which draws a series that moves in
        // hundredths flatter than it is, on the axis and under the chart.
        assertEquals(2, ChartCanvas.formatFor(0.05).getMinimumFractionDigits(),
                "a step of five hundredths needs two places, not one");

        // And it stops somewhere: six, because a format with fifty decimals is
        // not a label.
        assertEquals(6, ChartCanvas.formatFor(1e-12).getMinimumFractionDigits(),
                "the number of decimals is not capped");
    }

    @Test
    @DisplayName("o passo da grade e 1, 2 ou 5 vezes uma potencia de dez")
    void thegridStepIsOneTwoOrFive() {
        assertEquals(1.0, ChartCanvas.niceStep(0.7), 1e-12);
        assertEquals(2.0, ChartCanvas.niceStep(1.3), 1e-12);
        assertEquals(5.0, ChartCanvas.niceStep(4.9), 1e-12);
        assertEquals(10.0, ChartCanvas.niceStep(5.1), 1e-12);
        assertEquals(500.0, ChartCanvas.niceStep(430), 1e-12);

        // A span of nothing still has to answer something drawable.
        assertEquals(1.0, ChartCanvas.niceStep(0), 1e-12);
        assertEquals(1.0, ChartCanvas.niceStep(-3), 1e-12);
    }

    @Test
    @DisplayName("o rodape escreve o preco com as casas do eixo, nao com duas fixas")
    void thefooterRoundsLikeTheAxis() {
        // On an index the grid step is whole points, so the footer must write
        // whole points. It used to have two decimal places hard coded -- a
        // third copy of the rule, already drifted from the axis and from the
        // cursor tag -- and printed 177.600,00 under every chart.
        ChartCanvas canvas = charting(177_000, 40);

        hover(canvas, 400, 200);

        String reading = canvas.cursorReading();

        assertFalse(reading.isEmpty(), "the footer said nothing at all");

        String price = reading.substring(reading.lastIndexOf(' ') + 1);

        assertEquals(-1, price.indexOf(decimalPoint()),
                "the footer wrote decimals on an index whose grid step is whole "
                        + "points: " + reading);

        // And it is the price of the bar under the cursor, not of some other
        // bar: without this the assertion above is satisfied by any whole
        // number at all.
        assertEquals(177_000L + 40L * canvas.hoveredBar(),
                Long.parseLong(price.replaceAll("[^0-9]", "")),
                "the footer is not showing the close of the bar under the cursor: " + reading);
    }

    @Test
    @DisplayName("e escreve casas quando o passo da grade e menor que um")
    void thefooterKeepsTheDecimalsThatMatter() {
        // The other half of the same rule, and the reason fixed places are
        // wrong in BOTH directions: a series that moves in hundredths would be
        // rounded to a flat line by a format with none.
        ChartCanvas canvas = charting(1.10, 0.0004);

        hover(canvas, 400, 200);

        String reading = canvas.cursorReading();
        String price = reading.substring(reading.lastIndexOf(' ') + 1);

        assertTrue(price.indexOf(decimalPoint()) > 0,
                "a series moving in ten-thousandths was written with no decimals at "
                        + "all, which draws it as a flat line: " + reading);
    }

    /** @return the character this machine writes a decimal point with */
    private static char decimalPoint() {
        return java.text.DecimalFormatSymbols
                .getInstance(java.util.Locale.getDefault()).getDecimalSeparator();
    }

    /** @return a canvas of 400 bars starting at that price and stepping by that much */
    private static ChartCanvas charting(double from, double step) {
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSize(800, 600);
        canvas.setSeries(new PriceSeries() {

            @Override
            public int size() {
                return 400;
            }

            @Override
            public long timeAt(int index) {
                return 1_756_000_000_000L + index * 60_000L;
            }

            @Override
            public double openAt(int index) {
                return from + index * step;
            }

            @Override
            public double highAt(int index) {
                return from + index * step;
            }

            @Override
            public double lowAt(int index) {
                return from + index * step;
            }

            @Override
            public double closeAt(int index) {
                return from + index * step;
            }
        });

        return canvas;
    }

    /** Puts the pointer somewhere on the plot, which is what makes the footer speak. */
    private static void hover(ChartCanvas canvas, int x, int y) {
        MouseEvent event = new MouseEvent(canvas, MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, x, y, 0, false, MouseEvent.NOBUTTON);

        for (MouseMotionListener each : canvas.getMouseMotionListeners()) {
            each.mouseMoved(event);
        }
    }
}
