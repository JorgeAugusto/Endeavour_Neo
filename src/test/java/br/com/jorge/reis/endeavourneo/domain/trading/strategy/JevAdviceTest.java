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
package br.com.jorge.reis.endeavourneo.domain.trading.strategy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.trading.Backtest;
import br.com.jorge.reis.endeavourneo.domain.trading.Costs;
import br.com.jorge.reis.endeavourneo.domain.trading.Fill;
import br.com.jorge.reis.endeavourneo.domain.trading.Result;
import br.com.jorge.reis.endeavourneo.domain.trading.order.Side;

import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A estratégia que obedece a um modelo de fora: lado dele, geometria nossa. */
@DisplayName("Jev operado")
class JevAdviceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static final int PER_DAY = 120;

    private static final double WICK = 80;

    /** Uma onda mansa, para o alvo e o stop serem alcançados sem pressa. */
    private record Bars(double[] price) implements PriceSeries {

        private static Bars wave(int days) {
            double[] made = new double[days * PER_DAY];

            for (int i = 0; i < made.length; i++) {
                made[i] = 100_000 + 700 * Math.sin(i / 19.0);
            }

            return new Bars(made);
        }

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2025, 1, 6), LocalTime.of(9, 0), SP)
                    .plusDays(index / PER_DAY)
                    .plusMinutes(index % PER_DAY)
                    .toInstant().toEpochMilli();
        }

        @Override
        public double openAt(int index) {
            return price[index];
        }

        @Override
        public double highAt(int index) {
            return price[index] + WICK;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - WICK;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /**
     * Decisões inventadas, uma a cada trinta barras, alternando o lado.
     *
     * @param confidence a mesma para todas, que é o que dá ao teste do limiar
     *                   um interruptor limpo
     */
    private static JevDecisions decisions(Bars bars, double confidence) throws IOException {
        StringBuilder text = new StringBuilder("# de mentira\n").append(JevDecisions.COLUMNS)
                .append('\n');

        int side = 1;

        for (int bar = 10; bar < bars.size(); bar += 30) {
            text.append(bars.timeAt(bar)).append(';').append(side).append(';')
                    .append(side > 0 ? "0,70" : "0,30").append(';')
                    // Virgula decimal, como o arquivo de verdade.
                    .append(String.valueOf(confidence).replace('.', ',')).append('\n');

            side = -side;
        }

        return JevDecisions.read(new StringReader(text.toString()));
    }

    private static JevAdvice strategy(JevDecisions said, double least) {
        return new JevAdvice(SP, said, least, JevAdvice.TARGET, JevAdvice.STOP,
                JevAdvice.SLIP, 1);
    }

    private static Result run(Bars bars, JevAdvice what) {
        return new Backtest(Costs.NONE, 1).run(bars, bars, what, null);
    }

    private static List<Fill> openings(Result result) {
        List<Fill> made = new ArrayList<>();

        for (Fill fill : result.fills()) {
            if (!fill.verb().contains("Cover") && !fill.verb().contains("Close")) {
                made.add(fill);
            }
        }

        return made;
    }

    // --------------------------------------------------------------- o lado

    @Test
    @DisplayName("ABRE DO LADO QUE O MODELO DISSE, e só onde ele disse alguma coisa")
    void itopensOnTheSideTheModelChose() throws IOException {
        Bars bars = Bars.wave(3);
        JevDecisions said = decisions(bars, 0.8);
        Result result = run(bars, strategy(said, JevAdvice.LEAST_CONFIDENCE));

        List<Fill> opened = openings(result);

        assertFalse(opened.isEmpty(), "nao abriu posicao nenhuma");

        for (Fill fill : opened) {
            // A ordem sai no fechamento da barra decidida e executa na ABERTURA
            // da seguinte, entao a decisao esta em bar - 1.
            int side = said.at(bars.timeAt(fill.bar() - 1)).side();

            assertTrue(side != 0, "abriu na barra " + fill.bar()
                    + " sem decisao na barra anterior");

            assertEquals(side > 0 ? Side.BUY : Side.SELL, fill.side(),
                    "abriu do lado contrario ao que o modelo disse, na barra " + fill.bar());
        }
    }

    @Test
    @DisplayName("ABAIXO DO LIMIAR NÃO OPERA: confiança fraca é o mesmo que silêncio")
    void belowTheThresholdItdoesNothing() throws IOException {
        Bars bars = Bars.wave(3);

        // As MESMAS decisoes, os mesmos lados, so a confianca muda. Com 0,8
        // opera; com 0,1 nao pode abrir nada.
        assertFalse(openings(run(bars,
                strategy(decisions(bars, 0.8), 0.30))).isEmpty(),
                "com confianca alta nao operou, entao o teste abaixo nao prova nada");

        assertEquals(List.of(), openings(run(bars,
                strategy(decisions(bars, 0.1), 0.30))),
                "operou com confianca abaixo do limiar");
    }

    // ------------------------------------------------------------ a geometria

    @Test
    @DisplayName("O STOP E O ALVO SAEM DA EXECUÇÃO, e não do fechamento que decidiu")
    void thelevelsAreMeasuredFromTheFill() throws IOException {
        Bars bars = Bars.wave(3);
        Result result = run(bars, strategy(decisions(bars, 0.8), 0.30));

        int checked = 0;

        for (var trade : result.trades()) {
            List<Fill> fills = trade.fills();

            if (fills.size() < 2) {
                continue;
            }

            Fill in = fills.get(0);
            Fill out = fills.get(fills.size() - 1);

            if (!out.verb().contains("Cover")) {
                // Saiu no fim do pregao, a mercado: nao ha nivel para conferir.
                continue;
            }

            int side = in.side() == Side.BUY ? 1 : -1;
            double target = in.price() + side * JevAdvice.TARGET;
            double stop = in.price() - side * JevAdvice.STOP;

            double wanted = out.verb().contains("Stop") ? stop : target;

            // Uma ordem limitada casa no limite ou na abertura, o que for
            // melhor; um stop casa no gatilho ou na abertura, o que for pior.
            assertTrue(Math.abs(out.price() - wanted) <= JevAdvice.SLIP,
                    "a saida da barra " + out.bar() + " a " + out.price()
                            + " esta longe do nivel " + wanted
                            + " que a execucao em " + in.price() + " manda");

            checked++;
        }

        assertTrue(checked > 0, "nenhuma operacao terminou num nivel; nada foi conferido");
    }

    @Test
    @DisplayName("NADA ATRAVESSA O PREGÃO: cada dia termina zerado")
    void nothingCrossesTheSession() throws IOException {
        Bars bars = Bars.wave(3);
        Result result = run(bars, strategy(decisions(bars, 0.8), 0.30));

        int net = 0;

        for (Fill fill : result.fills()) {
            net += fill.signed();
        }

        assertEquals(0, net, "sobrou posicao aberta no fim");
    }

    // ------------------------------------------------------- a reprodutibilidade

    @Test
    @DisplayName("DUAS RODADAS IGUAIS DÃO O MESMO, que é a razão de existir o arquivo")
    void tworunsGiveExactlyTheSame() throws IOException {
        Bars bars = Bars.wave(3);

        List<Fill> first = run(bars, strategy(decisions(bars, 0.8), 0.30)).fills();
        List<Fill> again = run(bars, strategy(decisions(bars, 0.8), 0.30)).fills();

        // ISTO NAO E TRIVIAL, e e a propriedade que justifica congelar as
        // respostas do modelo num arquivo em vez de chamar a rede durante a
        // rodada. Uma chamada por barra faria estas duas listas divergirem, e
        // aí o numero publicado mudaria sozinho entre duas leituras.
        assertEquals(first.size(), again.size(), "as duas rodadas abriram contagens diferentes");

        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i).bar(), again.get(i).bar(), "barra da execucao " + i);
            assertEquals(first.get(i).side(), again.get(i).side(), "lado da execucao " + i);
            assertEquals(first.get(i).price(), again.get(i).price(), 0.0,
                    "preco da execucao " + i);
        }
    }

    // ----------------------------------------------------------- o casamento

    @Test
    @DisplayName("CONTA QUANTAS DECISÕES CASAM com as barras da série")
    void itcountsHowManyDecisionsLandOnAbar() throws IOException {
        Bars bars = Bars.wave(3);
        JevAdvice what = strategy(decisions(bars, 0.8), 0.30);

        run(bars, what);

        assertTrue(what.matchedBars() > 0, "nenhuma decisao casou com barra nenhuma");

        // UM ARQUIVO DE OUTRA ESCALA nao casa com nada, e o numero tem de dizer
        // isso: sem ele, uma exportacao de quinze minutos rodada em um minuto
        // parece uma estrategia sem sinais em vez de um arquivo que nao serve.
        JevDecisions outra = JevDecisions.read(new StringReader(
                JevDecisions.COLUMNS + "\n1;1;0,9;0,9\n2;-1;0,1;0,9\n"));
        JevAdvice perdida = strategy(outra, 0.30);

        run(bars, perdida);

        assertEquals(0, perdida.matchedBars(),
                "decisoes de outro tempo casaram com barras desta serie");
        assertEquals(List.of(), openings(run(bars, strategy(outra, 0.30))),
                "operou com decisoes que nao pertencem a esta serie");
    }
}
