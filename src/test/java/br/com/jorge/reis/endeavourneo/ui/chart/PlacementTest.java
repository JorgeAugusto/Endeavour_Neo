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

import br.com.jorge.reis.endeavourneo.domain.market.RandomWalkSeries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.ui.chart.overlay.BollingerBands;
import br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage;
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
    @DisplayName("a lista e uma so, e cada um responde por si")
    void oneListAndEachAnswersForItself() {
        java.util.Map<String, Boolean> answers = new java.util.HashMap<>();

        for (OverlayCatalog.Kind kind : OverlayCatalog.kinds()) {
            int[] numbers = kind.defaults().stream().mapToInt(Integer::intValue).toArray();
            Overlay built = kind.factory().apply(numbers);

            assertNotNull(built, kind.nameKey() + " is offered and cannot be built");

            answers.put(kind.nameKey(), built.fitsOnPrice());
        }

        // One list holding both kinds is the whole point: before this, which
        // list an indicator was in decided where it could go, and a moving
        // average in a panel was impossible to even ask for.
        assertEquals(Boolean.TRUE, answers.get("overlay.movingAverage"));
        assertEquals(Boolean.TRUE, answers.get("overlay.bollinger"));
        assertEquals(Boolean.FALSE, answers.get("study.stochastic"));
        assertEquals(Boolean.FALSE, answers.get("study.rsi"));
    }

    @Test
    @DisplayName("um layout nao consegue por um indicador de painel no preco")
    void aLayoutCannotSmuggleOneIn() {
        // One catalogue means a stored layout CAN name a panel indicator among
        // the price ones -- a file edited by hand, or one written before the
        // two lists became one.
        canvas.setOverlays(java.util.List.of(
                new MovingAverage(9), new SlowStochastic(8, 3), new MovingAverage(21)));

        assertEquals(2, canvas.overlays().size(),
                "the stochastic came in with the averages and is a flat line on the floor");

        for (Overlay each : canvas.overlays()) {
            assertTrue(each.fitsOnPrice());
        }
    }

    @Test
    @DisplayName("os tres destinos sao distintos e um deles e sempre o certo")
    void thePlacements() {
        Overlay any = new MovingAverage(9);

        InsertOverlayDialog.Placement price =
                new InsertOverlayDialog.Placement(any, null, true);
        InsertOverlayDialog.Placement fresh =
                new InsertOverlayDialog.Placement(any, null, false);

        assertTrue(price.onPrice());
        assertFalse(price.inNewPane(), "on the price is not a pane of its own");

        assertTrue(fresh.inNewPane());
        assertFalse(fresh.onPrice());
    }

    @Test
    @DisplayName("o catalogo reconstroi pelo nome, e devolve nulo para o que nao tem")
    void buildingByName() {
        Overlay back = OverlayCatalog.build("study.stochastic", java.util.List.of(21, 5));

        assertNotNull(back);
        assertEquals(java.util.List.of(21, 5), back.parameters());

        // A workspace written by a later version can name an indicator this
        // one lacks; refusing to open the chart would turn one missing line
        // into a lost window.
        assertEquals(null, OverlayCatalog.build("study.doesNotExist", java.util.List.of(9)));
    }
}
