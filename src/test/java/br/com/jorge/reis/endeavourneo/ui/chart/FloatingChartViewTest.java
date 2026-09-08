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
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.awt.GraphicsEnvironment;
import javax.swing.JDesktopPane;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A chart left floating comes back where it was left.
 *
 * <h2>Written on every close, read on none of them</h2>
 *
 * <p>{@code close} always stores the view -- bars across the plot, the margin,
 * the stretch, the slide, the period, the style and the position measured from
 * the end of the series -- and {@code show} reopens the chart in whichever mode
 * it was last left in. Only the docking path read any of it back.</p>
 *
 * <p>So a reader who works with floating charts wrote the whole state every time
 * and got none of it back: a chart left on 11R zoomed into March 2021 came back
 * on 1m, at the end of the series, at the default zoom. The window's own size
 * and position DID come back, because those are read straight from the
 * preferences -- which made losing the rest more confusing, not less.</p>
 */
@DisplayName("A vista de um grafico flutuante")
class FloatingChartViewTest {

    /** A series of that many one-minute bars, closing at their own index. */
    private static PriceSeries bars(int count) {
        return new PriceSeries() {

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
    @DisplayName("um grafico deixado flutuante reabre onde o leitor estava")
    void afloatingChartReopensWhereItWasLeft() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        String name = "floatingChartViewTest-1m";

        try {
            int[] left = new int[1];

            SwingUtilities.invokeAndWait(() -> {
                ChartHolder holder = holderFor(name);

                holder.floatIt();
                holder.canvas().setSeries(bars(1_000));
                holder.canvas().scrollTo(300);

                left[0] = holder.canvas().firstVisibleBar();

                holder.close();
            });

            // The frame is laid out and the view restored on a later event, so
            // the reader's position is only back once the queue has drained.
            int[] came = new int[1];

            SwingUtilities.invokeAndWait(() -> {
                ChartHolder holder = holderFor(name);

                holder.show();
                holder.canvas().setSeries(bars(1_000));

                came[0] = -1;

                SwingUtilities.invokeLater(() -> {
                    came[0] = holder.canvas().firstVisibleBar();

                    holder.close();
                });
            });

            SwingUtilities.invokeAndWait(() -> { });

            assertEquals(left[0], came[0],
                    "the chart came back at the end of the series: a floating chart wrote "
                            + "its view on every close and never had it read back");
        } finally {
            br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                    .removeStartingWith("chart.floatingChartViewTest_1m.");
        }
    }

    private static ChartHolder holderFor(String name) {
        return new ChartHolder(name, name, new JDesktopPane(), null, () -> { });
    }
}
