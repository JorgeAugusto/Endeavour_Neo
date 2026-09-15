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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Os nove contrafactuais por oportunidade: o que cada par alvo/stop teria dado. */
@DisplayName("Contrafactuais do fade")
class FadeOutcomesTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Barras montadas à mão: abertura, máxima, mínima e fechamento, uma a uma. */
    private record Bars(double[][] ohlc) implements PriceSeries {

        @Override
        public int size() {
            return ohlc.length;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2025, 1, 6), LocalTime.of(9, 0), SP)
                    .toInstant().toEpochMilli() + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return ohlc[index][0];
        }

        @Override
        public double highAt(int index) {
            return ohlc[index][1];
        }

        @Override
        public double lowAt(int index) {
            return ohlc[index][2];
        }

        @Override
        public double closeAt(int index) {
            return ohlc[index][3];
        }
    }

    private static final FadeOutcomes WHAT = FadeOutcomes.standard();

    /** O dinheiro que a especificação manda: dois contratos a R$0,20, menos R$2. */
    private static double money(double entry, double exit, int side) {
        return (exit - entry) * side * 0.20 * 2 - 2.0;
    }

    @Test
    @DisplayName("SÃO NOVE, na ordem da especificação e com o alvo variando mais devagar")
    void therearNineActionsInTheSpecifiedOrder() {
        assertEquals(9, FadeOutcomes.ACTIONS.length);

        // A ORDEM E CARREGADA: a nona saida da rede e o nono par, e nada nos
        // numeros diz isso. Trocar a ordem faz cada peso treinado passar a
        // nomear um par diferente, em silencio.
        String[] wanted = {"T100_S150", "T100_S250", "T100_S350",
            "T150_S150", "T150_S250", "T150_S350",
            "T200_S150", "T200_S250", "T200_S350"};

        for (int i = 0; i < wanted.length; i++) {
            assertEquals(wanted[i], FadeOutcomes.ACTIONS[i].toString(),
                    "a acao " + i + " nao e a que a especificacao poe ali");
        }
    }

    @Test
    @DisplayName("A BARRA DO SINAL NÃO CONTA, por mais que ela toque o alvo")
    void thesignalBarIsNotEvaluated() {
        // A barra 0 tem o sinal E alcanca o alvo de todos os nove pares. As
        // seguintes nao alcancam nada. Se a barra do sinal fosse avaliada, os
        // nove sairiam no alvo; como ela nao e, os nove terminam no fechamento
        // da sessao.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_900, 100_000, 100_000},
            {100_000, 100_010, 99_990, 100_000},
            {100_000, 100_010, 99_990, 100_005},
        });

        FadeOutcomes.Outcome[] made = WHAT.of(bars, 0, 2, 1, 100_000);

        for (int action = 0; action < made.length; action++) {
            assertEquals(2, made[action].exitBar(), "a acao " + action
                    + " saiu na barra " + made[action].exitBar()
                    + ", e nao no fim da sessao: a barra do sinal foi avaliada");

            assertEquals(money(100_000, 100_005, 1), made[action].money(), 1e-9);
        }
    }

    @Test
    @DisplayName("NO ALVO, ao preço que a ordem limitada daria")
    void ittakesTheTargetAtTheLimitOrTheOpen() {
        // Alvo de 100 pontos: 100.100. A barra 1 abre em 100.050 e alcanca
        // 100.200, entao a saida e no LIMITE. A barra 2 nao chega a ser olhada.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_000, 100_000, 100_000},
            {100_050, 100_200, 100_040, 100_150},
            {100_150, 100_160, 100_140, 100_150},
        });

        FadeOutcomes.Outcome first = WHAT.of(bars, 0, 2, 1, 100_000)[0];

        assertFalse(first.stopped(), "saiu no stop numa barra que so subiu");
        assertEquals(1, first.exitBar());
        assertEquals(money(100_000, 100_100, 1), first.money(), 1e-9);
        assertEquals(1, first.minutes());
    }

    @Test
    @DisplayName("NUM GAP A FAVOR, ao preço da ABERTURA e não ao do alvo")
    void agapPastTheTargetFillsAtTheOpen() {
        // A barra 1 abre em 100.300, ja acima do alvo de 100.100. Executar no
        // alvo inventaria duzentos pontos que nunca existiram.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_000, 100_000, 100_000},
            {100_300, 100_400, 100_290, 100_350},
        });

        FadeOutcomes.Outcome first = WHAT.of(bars, 0, 1, 1, 100_000)[0];

        assertEquals(money(100_000, 100_300, 1), first.money(), 1e-9);
    }

    @Test
    @DisplayName("O STOP GANHA O EMPATE quando a barra alcança os dois")
    void thestopWinsAtie() {
        // Alvo 100.100 e stop 99.850, os dois dentro da barra 1.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_000, 100_000, 100_000},
            {100_000, 100_500, 99_500, 100_000},
        });

        FadeOutcomes.Outcome first = WHAT.of(bars, 0, 1, 1, 100_000)[0];

        assertTrue(first.stopped(), "a barra alcancou os dois e nao saiu no stop");
        assertEquals(money(100_000, 99_850, 1), first.money(), 1e-9);
    }

    @Test
    @DisplayName("SEM TOCAR NADA, sai no último fechamento da sessão")
    void untouchedItLeavesAtTheSessionsLastClose() {
        Bars bars = new Bars(new double[][] {
            {100_000, 100_000, 100_000, 100_000},
            {100_000, 100_010, 99_990, 100_000},
            {100_000, 100_010, 99_990, 100_007},
            {100_007, 100_900, 99_100, 100_500},
        });

        // A sessao termina na barra 2; a barra 3 existe e alcancaria tudo, e
        // justamente por isso esta aqui: ela nao pode ser olhada.
        FadeOutcomes.Outcome first = WHAT.of(bars, 0, 2, 1, 100_000)[0];

        assertEquals(2, first.exitBar());
        assertEquals(money(100_000, 100_007, 1), first.money(), 1e-9);
        assertEquals(2, first.minutes());
    }

    @Test
    @DisplayName("A VENDA É O ESPELHO: alvo embaixo, stop em cima")
    void sellingIsTheMirror() {
        // Vendido em 100.000: alvo 99.900, stop 100.150.
        Bars bars = new Bars(new double[][] {
            {100_000, 100_000, 100_000, 100_000},
            {99_950, 99_960, 99_800, 99_900},
        });

        FadeOutcomes.Outcome first = WHAT.of(bars, 0, 1, -1, 100_000)[0];

        assertFalse(first.stopped(), "a venda saiu no stop numa barra que so caiu");
        assertEquals(money(100_000, 99_900, -1), first.money(), 1e-9);
        assertTrue(first.money() > 0, "a venda que caiu cem pontos nao ganhou dinheiro");
    }

    @Test
    @DisplayName("A UTILIDADE PENALIZA A PERDA EM 15% E O TEMPO EM 0,08")
    void theutilityPenalisesLossesAndTime() {
        FadeOutcomes.Outcome won = new FadeOutcomes.Outcome(10, 5, 1, false);
        FadeOutcomes.Outcome lost = new FadeOutcomes.Outcome(-10, 5, 1, true);

        // Conferido a mao, dos dois lados do max(-P, 0): ganhando, o termo da
        // perda e zero e so o tempo e o incentivo agem.
        assertEquals(10 - 0.08 * 5 + 1.0, FadeOutcomes.utility(won), 1e-9);
        assertEquals(-10 - 1.5 - 0.08 * 5 + 1.0, FadeOutcomes.utility(lost), 1e-9);

        // E o incentivo fixo existe para inclinar a decisao para OPERAR: sem
        // ele, uma oportunidade de resultado nulo e curta teria utilidade
        // negativa e a rede se absteria de tudo.
        assertTrue(FadeOutcomes.utility(new FadeOutcomes.Outcome(0, 1, 1, false)) > 0,
                "uma operacao de resultado zero e um minuto ficou com utilidade negativa");
    }
}
