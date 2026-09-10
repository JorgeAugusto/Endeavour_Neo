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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O espaço vazio à direita do candle mais novo.
 *
 * <p>O que estes testes defendem é uma frase dele: <b>o candle novo nasce a uns
 * 25% de recuo da borda direita</b>. Antes o gráfico guardava o ar que o leitor
 * tivesse deixado no último arraste e o repetia em cada barra que chegava —
 * então um arraste que terminasse colado na moldura colava ali todos os candles
 * seguintes, para sempre.</p>
 */
@DisplayName("Margem à direita")
class RightMarginTest {

    /** Uma série que cresce, para simular o candle nascendo. */
    private static final class Growing implements PriceSeries {

        private int count;

        private Growing(int count) {
            this.count = count;
        }

        @Override
        public int size() {
            return count;
        }

        @Override
        public long timeAt(int index) {
            return 1_600_000_000_000L + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return 100 + index % 7;
        }

        @Override
        public double highAt(int index) {
            return 101 + index % 7;
        }

        @Override
        public double lowAt(int index) {
            return 99 + index % 7;
        }

        @Override
        public double closeAt(int index) {
            return 100 + index % 7;
        }
    }

    /**
     * Devolve o ajuste ao padrão.
     *
     * <p>É um ajuste do gráfico, e portanto estático: um teste que o muda e não
     * o devolve muda o resultado do teste seguinte, e de uma classe que nem
     * sabe que ele existe. A suíte inteira roda numa JVM só.</p>
     */
    @AfterEach
    void putTheSettingBack() {
        ChartPreferences.setRightMargin(ChartPreferences.MARGIN_DEFAULT);
    }

    private static ChartCanvas showing(PriceSeries series) {
        ChartCanvas canvas = new ChartCanvas();

        canvas.setSeries(series);
        canvas.setSize(900, 500);

        return canvas;
    }

    /** @return quantas barras vazias sobram depois da última */
    private static int airOf(ChartCanvas canvas) {
        return canvas.firstVisibleBar() + canvas.visibleBarCount()
                - canvas.series().size();
    }

    private static int wanted(ChartCanvas canvas) {
        return (int) Math.round(canvas.visibleBarCount()
                * ChartPreferences.rightMargin() / 100.0);
    }

    @Test
    @DisplayName("o grafico abre com o espaco que o ajuste pede")
    void itOpensWithTheRoomTheSettingAsks() {
        ChartPreferences.setRightMargin(25);

        ChartCanvas canvas = showing(new Growing(400));

        assertEquals(wanted(canvas), airOf(canvas), "abriu com outro espaco");
        assertTrue(airOf(canvas) > 0, "abriu colado na moldura");
    }

    @Test
    @DisplayName("com o ajuste em zero o candle encosta na moldura")
    void zeroPutsItAgainstTheFrame() {
        ChartPreferences.setRightMargin(0);

        ChartCanvas canvas = showing(new Growing(400));

        assertEquals(0, airOf(canvas), "sobrou espaco com o ajuste em zero");
    }

    @Test
    @DisplayName("o candle NOVO nasce recuado, mesmo depois de um arraste ate a moldura")
    void theNewCandleIsBornWithTheMargin() {
        ChartPreferences.setRightMargin(25);

        Growing series = new Growing(400);
        ChartCanvas canvas = showing(series);

        // O arraste que encosta na borda: nao sobra ar nenhum.
        canvas.scrollTo(canvas.series().size() - canvas.visibleBarCount());

        assertEquals(0, airOf(canvas), "o arraste nao encostou na moldura");

        // E ENTAO NASCE UM CANDLE. Antes, o grafico repetia o ar que o leitor
        // tinha deixado -- zero -- e o candle novo nascia colado na moldura,
        // para sempre. Agora quem manda e o ajuste.
        series.count++;

        canvas.seriesGrew();

        assertEquals(wanted(canvas), airOf(canvas),
                "o candle novo nasceu colado na moldura");
    }

    @Test
    @DisplayName("ir ao fim tambem devolve o espaco")
    void goingToTheEndRestoresTheRoom() {
        ChartPreferences.setRightMargin(25);

        ChartCanvas canvas = showing(new Growing(400));

        canvas.scrollTo(0);
        canvas.goToEnd();

        assertEquals(wanted(canvas), airOf(canvas), "voltou ao fim sem o espaco");
    }

    @Test
    @DisplayName("mudar o ajuste mexe no grafico que ja esta no fim")
    void changingTheSettingMovesAChartAtTheEnd() {
        ChartPreferences.setRightMargin(10);

        ChartCanvas canvas = showing(new Growing(400));

        canvas.addNotify();

        int before = airOf(canvas);

        ChartPreferences.setRightMargin(40);

        int after = airOf(canvas);

        canvas.removeNotify();

        // Um ajuste cujo efeito espera o proximo candle parece quebrado para
        // quem esta olhando para ele.
        assertTrue(after > before,
                "o grafico nao acompanhou o ajuste: " + before + " -> " + after);
    }

    @Test
    @DisplayName("quem esta lendo o passado nao e arrastado para o presente")
    void aReaderInTheHistoryIsLeftAlone() {
        ChartPreferences.setRightMargin(10);

        ChartCanvas canvas = showing(new Growing(400));

        canvas.addNotify();
        canvas.scrollTo(0);

        ChartPreferences.setRightMargin(40);

        int where = canvas.firstVisibleBar();

        canvas.removeNotify();

        // Mudar um ajuste nao e' pedir para ir ao fim. Quem esta olhando marco
        // de 2021 continua la.
        assertEquals(0, where, "a janela de ajustes arrastou o grafico para o fim");
    }
}
