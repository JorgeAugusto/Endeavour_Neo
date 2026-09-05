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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.BollingerBands;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;
import br.com.jorge.reis.endeavourneo.ui.chart.study.StudyCatalog;
import br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which indicators may be drawn on the price, and who is stopped.
 *
 * <p>An indicator on a panel scale put on the price axis is not an error
 * anything reports — it is a flat line along the floor of the chart, listed in
 * the legend and stored in the layout, saying nothing. Since a panel indicator
 * and a price indicator became the same type, the type can no longer refuse
 * it, so this is what does.</p>
 */
@DisplayName("Onde cada indicador pode ir")
class PlacementTest {

    private ChartCanvas canvas;

    @BeforeEach
    void setUp() {
        canvas = new ChartCanvas();
        canvas.setSeries(new RandomWalkSeries(200, 100.0));
    }

    @Test
    @DisplayName("o que e medido em preco vai no preco")
    void whatIsMeasuredInPrice() {
        assertTrue(new MovingAverage(9).fitsOnPrice());
        assertTrue(new BollingerBands().fitsOnPrice());
    }

    @Test
    @DisplayName("o estocastico nao vai no preco")
    void theStochasticDoesNot() {
        assertFalse(new SlowStochastic(8, 3).fitsOnPrice(),
                "nought to a hundred on the price axis is a flat line on the floor");
    }

    @Test
    @DisplayName("o grafico recusa quem nao cabe no preco")
    void theChartRefusesIt() {
        assertFalse(canvas.addOverlay(new SlowStochastic(8, 3)));

        assertTrue(canvas.overlays().isEmpty(),
                "it was refused and went onto the price anyway");
    }

    @Test
    @DisplayName("o grafico aceita quem cabe, e nada mais mudou")
    void theChartAcceptsTheOthers() {
        assertTrue(canvas.addOverlay(new MovingAverage(9)));
        assertFalse(canvas.addOverlay(null));

        assertEquals(1, canvas.overlays().size());
    }

    @Test
    @DisplayName("tudo que a lista do preco oferece cabe no preco")
    void everythingOfferedForThePrice() {
        for (OverlayCatalog.Kind kind : OverlayCatalog.kinds()) {
            int[] numbers = kind.defaults().stream().mapToInt(Integer::intValue).toArray();
            Overlay built = kind.factory().apply(numbers);

            assertNotNull(built, kind.nameKey() + " is offered and cannot be built");
            assertTrue(built.fitsOnPrice(),
                    kind.nameKey() + " is offered for the price and does not go there");
        }
    }

    @Test
    @DisplayName("nada que a lista do painel oferece cabe no preco")
    void nothingOfferedForAPanel() {
        // Not a tautology while both lists exist: the two catalogues are the
        // one place a new indicator is registered, and putting a panel
        // indicator in the wrong one is the mistake this catches.
        for (StudyCatalog.Kind kind : StudyCatalog.kinds()) {
            Overlay built = StudyCatalog.build(kind.nameKey(), kind.defaults());

            assertNotNull(built, kind.nameKey() + " is offered and cannot be built");
            assertFalse(built.fitsOnPrice(),
                    kind.nameKey() + " is offered for a panel and claims the price too");
        }
    }
}
