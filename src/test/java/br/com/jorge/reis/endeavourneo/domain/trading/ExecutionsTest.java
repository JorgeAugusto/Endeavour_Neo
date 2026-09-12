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
package br.com.jorge.reis.endeavourneo.domain.trading;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * As execuções de uma rodada, escritas para serem lidas de novo.
 *
 * <p>O que se defende aqui é que o arquivo tem <b>uma linha por execução</b> e
 * não por operação — uma que entra em quatro contratos e sai de uma vez tem
 * cinco histórias e uma média, e a média esconde exatamente o que se quer
 * olhar.</p>
 */
@DisplayName("Execucoes em arquivo")
class ExecutionsTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Um pregão que sobe em degraus, para uma escada poder acontecer. */
    private record Bars(double[] price) implements PriceSeries {

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2025, 3, 4), LocalTime.of(10, 0), SP)
                    .toInstant().toEpochMilli() + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return price[index];
        }

        @Override
        public double highAt(int index) {
            return price[index] + 50;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - 50;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    /**
     * Entra em três contratos, um por barra, e sai de uma vez.
     *
     * <p>Uma escada de propósito: e ela que faz a diferenca entre uma linha por
     * operacao e uma por execucao aparecer no arquivo.</p>
     */
    private static Result escada() {
        PriceSeries bars = new Bars(new double[] {
            100_000, 100_100, 100_200, 100_300, 100_900, 100_900, 100_900});

        return new Backtest(Costs.NONE, 1).run(bars, (market, desk) -> {
            if (market.bar() < 3) {
                desk.buyAtMarket(1);
            } else if (market.bar() == 3) {
                desk.closePosition();
            }
        });
    }

    @Test
    @DisplayName("UMA LINHA POR EXECUCAO, e nao por operacao")
    void oneRowPerExecutionAndNotPerTrade() {
        Result result = escada();

        assertEquals(1, result.count(), "esperava uma operacao so");

        int giros = result.trades().get(0).steps().size();

        assertTrue(giros > 2, "a escada nao aconteceu: " + giros + " giros");

        String[] linhas = Executions.of(result, new Bars(new double[] {
            100_000, 100_100, 100_200, 100_300, 100_900, 100_900, 100_900}),
                SP, 0.20, List.of("um comentario")).split("\n");

        int dados = 0;

        for (String linha : linhas) {
            if (!linha.startsWith("#") && !linha.startsWith("operacao;")) {
                dados++;
            }
        }

        assertEquals(giros, dados,
                "o arquivo tem " + dados + " linhas de dado para " + giros + " execucoes");
    }

    @Test
    @DisplayName("A HORA VAI JUNTO, porque um numero de barra so vale dentro da rodada")
    void theclockGoesWithItBecauseAbarNumberOnlyMeansSomethingInsideTheRun() {
        PriceSeries bars = new Bars(new double[] {
            100_000, 100_100, 100_200, 100_300, 100_900, 100_900, 100_900});

        String texto = Executions.of(escada(), bars, SP, 0.20, List.of());
        String primeira = primeiraLinhaDeDado(texto);

        // A execucao da barra 1 acontece as 10:01, porque a ordem pedida no
        // fechamento da 0 executa na abertura da 1.
        assertTrue(primeira.contains("04/03/2025"),
                "a data nao saiu na linha: " + primeira);
        assertTrue(primeira.contains(";10:0"),
                "a hora nao saiu na linha: " + primeira);
    }

    @Test
    @DisplayName("o que a operacao realizou esta na linha da SAIDA, e zero nas entradas")
    void whatWasRealisedIsOnTheExitRow() {
        String texto = Executions.of(escada(), new Bars(new double[] {
            100_000, 100_100, 100_200, 100_300, 100_900, 100_900, 100_900}),
                SP, 0.20, List.of());

        String[] linhas = texto.split("\n");
        String saida = null;
        int entradas = 0;

        for (String linha : linhas) {
            if (linha.startsWith("#") || linha.startsWith("operacao;")) {
                continue;
            }

            if (linha.contains(";saida;")) {
                saida = linha;
            } else if (linha.contains(";entrada;")) {
                entradas++;

                // UMA ENTRADA REALIZA ZERO, e zero e um numero. Em branco leria
                // como "nao medido".
                assertTrue(linha.endsWith(";0,00;0,00"),
                        "a entrada nao realizou zero: " + linha);
            }
        }

        assertTrue(entradas > 0, "nao houve entrada nenhuma");
        assertFalse(saida == null, "nao houve saida");
        assertFalse(saida.endsWith(";0,00;0,00"), "a saida realizou zero: " + saida);
    }

    @Test
    @DisplayName("O QUE PRODUZIU O ARQUIVO VAI NO ARQUIVO")
    void whatProducedTheFileGoesInTheFile() {
        // Sem isso dois arquivos de duas rodadas sao indistinguiveis, e comparar
        // dois e a unica coisa que se faz com eles.
        String texto = Executions.of(escada(), null, SP, 0.20,
                List.of("serie=WINFUT", "estrategia=Fade de canal"));

        assertTrue(texto.startsWith("# serie=WINFUT\n# estrategia=Fade de canal\n"),
                "o cabecalho nao veio: " + texto.substring(0, Math.min(80, texto.length())));
    }

    private static String primeiraLinhaDeDado(String texto) {
        for (String linha : texto.split("\n")) {
            if (!linha.startsWith("#") && !linha.startsWith("operacao;")) {
                return linha;
            }
        }

        return "";
    }
}
