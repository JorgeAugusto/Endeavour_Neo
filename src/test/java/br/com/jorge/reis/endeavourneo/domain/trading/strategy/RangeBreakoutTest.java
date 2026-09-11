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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A Range 90 sobre pregões construídos à mão, onde a resposta é sabida.
 *
 * <p>O que estes testes defendem não é o resultado — a estratégia já está
 * refutada na base cega dele. É a <b>máquina</b>: o livro de lotes, os stops
 * individuais, o teto de contratos, o seletor que autoriza o dia, e a regra que
 * mais importa de todas — <b>nada atravessa a noite</b>.</p>
 */
@DisplayName("Range 90")
class RangeBreakoutTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Minutos por pregão: 90 de formação e mais 60 para o dia acontecer. */
    private static final int PER_DAY = 150;

    private static final int FORMATION = 90;

    /** O alcance da formação, em pontos. O R sai dele: 405. */
    private static final int RANGE = 400;

    /** O primeiro pregão que o seletor libera, com esta forma de dia. */
    private static final int FIRST_TRADED = 40;

    /**
     * As cinco formas de dia que estes testes precisam.
     *
     * <ul>
     *   <li>{@code UP} rompe e ziguezagueia para cima, armando pullback atrás de
     *       pullback até bater o alvo global;</li>
     *   <li>{@code QUIET} nunca chega no gatilho;</li>
     *   <li>{@code LATE} só rompe quarenta minutos depois da formação;</li>
     *   <li>{@code HOLD} rompe e sobe devagar, sem pullback e <b>sem alcançar o
     *       alvo</b> — é o único que chega vivo no fim do pregão, e por isso o
     *       único que prova que nada atravessa a noite;</li>
     *   <li>{@code GAP} salta de uma vez por cima da parcial e do alvo, o que
     *       faz a parcial matar a OCO e o alvo executar no minuto seguinte
     *       <b>na abertura</b>, longe do nível pedido.</li>
     * </ul>
     */
    private enum Shape { UP, QUIET, LATE, HOLD, GAP }

    /**
     * Pregões de 150 minutos a partir de 02/01/2024, um por dia útil.
     *
     * <p>A formação vai de {@code base} a {@code base + 400}, o rompimento de
     * alta acontece no minuto 90 e o preço sobe até bater qualquer um dos dois
     * alvos. Os fechamentos diários sobem cinquenta pontos por dia, então a
     * inclinação da EMA 10 é positiva e só a compra é permitida.
     */
    private record Days(int count, Shape[] shapes) implements PriceSeries {

        @Override
        public int size() {
            return count * PER_DAY;
        }

        private int day(int index) {
            return index / PER_DAY;
        }

        private int minute(int index) {
            return index % PER_DAY;
        }

        private double base(int index) {
            return 100_000 + day(index) * 50;
        }

        private Shape shape(int index) {
            return shapes == null ? Shape.UP : shapes[day(index) % shapes.length];
        }

        @Override
        public long timeAt(int index) {
            LocalDate when = LocalDate.of(2024, 1, 1).plusDays(day(index) / 5 * 7L
                    + day(index) % 5);

            return ZonedDateTime.of(when, LocalTime.of(9, 0), SP).toInstant().toEpochMilli()
                    + minute(index) * 60_000L;
        }

        @Override
        public double openAt(int index) {
            int m = minute(index);

            return m == 0 ? base(index) + 200 : closeAt(index - 1);
        }

        @Override
        public double closeAt(int index) {
            int m = minute(index);
            double base = base(index);

            if (m < FORMATION) {
                return base + 200;
            }

            return base + run(index, m);
        }

        /**
         * Onde o preço está, em pontos acima da base, depois da formação.
         *
         * <p>Um ziguezague que sobe: três minutos por perna — o topo, um
         * fechamento mais baixo que arma o pullback, e um novo topo que o
         * confirma. É o que faz a máquina de lotes trabalhar; um caminho que só
         * sobe nunca arma pullback nenhum, e o teto de contratos jamais é
         * exercido.
         */
        private double run(int index, int m) {
            Shape shape = shape(index);

            if (shape == Shape.QUIET) {
                return 200;
            }

            int after = m - FORMATION - (shape == Shape.LATE ? 40 : 0);

            if (after < 0) {
                return 200;
            }

            if (shape == Shape.HOLD) {
                // Sobe devagar e para bem abaixo do alvo de 2R, que fica em 1215.
                return 400 + Math.min(after, 10) * 40;
            }

            if (shape == Shape.GAP && after >= 9) {
                // Ziguezague até três lotes, e aí um salto só por cima da
                // parcial e do alvo ao mesmo tempo. Com UM lote a confusão do
                // alvo se resolve sozinha — o lote zera de qualquer jeito; o
                // fantasma só aparece quando sobram lotes para herdar a saída.
                return 1400;
            }

            int leg = after / 3;
            int phase = after % 3;

            // Fase 2 já é o topo da perna seguinte: sem essa ultrapassagem a
            // máxima do minuto de confirmação não supera a do anterior, e a
            // confirmação do §10.2 nunca acontece.
            // Pernas de 120: pequenas o bastante para caberem cinco pullbacks
            // antes do alvo global de 2R, que é o que faz a posição chegar aos
            // vinte contratos e o teto ser mesmo exercido.
            return 400 + (phase == 2 ? leg + 1 : leg) * 120 - (phase == 1 ? 120 : 0);
        }

        @Override
        public double highAt(int index) {
            int m = minute(index);
            double base = base(index);
            double top = Math.max(openAt(index), closeAt(index));

            // O minuto 10 da formação é quem faz a máxima do range.
            return m == 10 ? base + RANGE : top + 5;
        }

        @Override
        public double lowAt(int index) {
            int m = minute(index);
            double base = base(index);
            double bottom = Math.min(openAt(index), closeAt(index));

            // E o minuto 20, a mínima.
            return m == 20 ? base : bottom - 5;
        }
    }

    private static Result run(PriceSeries series, int cap) {
        return new Backtest(Costs.NONE, RangeBreakout.LOT)
                .run(series, new RangeBreakout(SP, RangeBreakout.LOT, cap,
                        RangeBreakout.ENTRY_WINDOW, FORMATION));
    }

    private static LocalDate dayOf(PriceSeries series, Fill fill) {
        return Instant.ofEpochMilli(series.timeAt(fill.bar())).atZone(SP).toLocalDate();
    }

    @Test
    @DisplayName("NADA ATRAVESSA A NOITE: toda sessao termina zerada")
    void nothingIsCarriedOvernight() {
        // HOLD alternado com UP: o dia que sobe devagar nunca bate o alvo nem o
        // stop, entao ele chega vivo no ultimo minuto -- e e o unico que pode
        // provar alguma coisa aqui. Num fixture onde todo dia bate o alvo no
        // meio da tarde, a posicao ja estava zerada de qualquer jeito.
        PriceSeries series = new Days(60, new Shape[] {Shape.UP, Shape.HOLD});
        Result result = run(series, RangeBreakout.CAP);

        int position = 0;
        LocalDate day = null;

        for (Fill fill : result.fills()) {
            LocalDate now = dayOf(series, fill);

            if (day != null && !now.equals(day)) {
                // A regra inteira da estrategia e intradiaria. Uma posicao que
                // atravessa a noite corre o gap de abertura, que e o unico risco
                // que ela nunca aceitou -- e nao apareceria em metrica nenhuma.
                assertEquals(0, position, "o pregao " + day + " terminou com posicao aberta");
            }

            day = now;
            position += fill.signed();
        }

        assertEquals(0, position, "o ultimo pregao terminou com posicao aberta");
        assertEquals(0, result.openAtTheEnd(), "a varredura acabou com posicao aberta");
        assertTrue(result.fills().size() > 10, "nao houve execucao nenhuma para julgar");
    }

    @Test
    @DisplayName("O TETO DE CONTRATOS E RESPEITADO, e mexer nele muda a posicao")
    void theceilingHolds() {
        PriceSeries series = new Days(60, null);

        int folgado = mostHeld(series, RangeBreakout.CAP);
        int apertado = mostHeld(series, 8);
        int minimo = mostHeld(series, 4);

        // NUNCA ACIMA DO TETO. Nao "exatamente o teto": as parciais tiram dois
        // contratos de cada lote, entao a posicao sobe em degraus de quatro menos
        // dois e nao encosta no numero redondo -- exigir a igualdade seria
        // testar a aritmetica do fixture em vez do limite.
        assertTrue(folgado <= RangeBreakout.CAP, "o teto de vinte foi furado: " + folgado);
        assertTrue(apertado <= 8, "o teto de oito foi furado: " + apertado);
        assertTrue(minimo <= 4, "o teto de quatro foi furado: " + minimo);

        // E ele morde mesmo: apertar o teto aperta a posicao. Sem isso, um teto
        // que nunca chegasse a ser alcancado passaria nos tres testes acima.
        assertTrue(folgado > apertado, "apertar o teto de vinte para oito nao mudou nada: "
                + folgado + " e " + apertado);
        assertTrue(apertado > minimo, "apertar o teto de oito para quatro nao mudou nada: "
                + apertado + " e " + minimo);
        assertTrue(folgado >= 16, "a posicao mal passou de um lote: " + folgado);
        assertEquals(RangeBreakout.LOT, minimo,
                "com o teto no tamanho do lote, nenhum pullback deveria acrescentar nada");
    }

    private static int mostHeld(PriceSeries series, int cap) {
        int position = 0;
        int most = 0;

        for (Fill fill : run(series, cap).fills()) {
            position += fill.signed();
            most = Math.max(most, Math.abs(position));
        }

        return most;
    }

    @Test
    @DisplayName("o seletor so libera o dia quando ha historico para julga-lo")
    void thedayIsOnlyAuthorisedOnceThereIsAhistory() {
        PriceSeries series = new Days(60, null);
        RangeBreakout strategy = new RangeBreakout(SP);

        strategy.start(series);

        // §6: vinte pregoes recentes, vinte amostras estruturais. Antes disso o
        // seletor nao tem o que pontuar, e um dia liberado sem pontuacao e um
        // dia escolhido por nada.
        assertTrue(Double.isNaN(strategy.targetFor(10)),
                "o decimo pregao foi liberado sem historico para julga-lo");
        assertTrue(Double.isNaN(strategy.targetFor(30)),
                "o trigesimo pregao foi liberado com menos de vinte amostras estruturais");
        assertEquals(2.0, strategy.targetFor(50), 0.0,
                "com os dois alvos ganhando, o desempate nao foi o 2R");
    }

    @Test
    @DisplayName("O DIA ACABA QUANDO A POSICAO ACABA, mesmo com o alvo saindo em gap")
    void thedayEndsWhenThePositionDoes() {
        // GAP: a parcial e o alvo ficam alcancaveis no MESMO minuto. As
        // coberturas sao uma OCO, entao a parcial executa e a perna do alvo
        // morre; o minuto seguinte abre ja acima do alvo, e a ordem reemitida
        // executa NA ABERTURA -- longe do nivel pedido.
        //
        // Reconhecer o alvo pelo preco fazia o livro nao entender essa saida,
        // ficar com lotes fantasmas, e seguir o pregao inteiro armando pullback
        // e abrindo lote novo sobre contratos que ja tinham ido embora.
        PriceSeries series = new Days(60, new Shape[] {Shape.GAP});
        Result result = run(series, RangeBreakout.CAP);

        assertFalse(result.fills().isEmpty(), "o pregao de gap nao executou nada");

        int position = 0;
        boolean closed = false;
        LocalDate day = null;

        for (Fill fill : result.fills()) {
            LocalDate now = dayOf(series, fill);

            if (!now.equals(day)) {
                day = now;
                position = 0;
                closed = false;
            }

            assertFalse(closed, "o pregao " + now + " continuou operando depois de zerar");

            position += fill.signed();
            closed = position == 0;
        }
    }

    @Test
    @DisplayName("dia sem rompimento nao entra, e dia que rompe tarde tambem nao")
    void aquietDayAndAlateBreakAreBothLeftAlone() {
        // QUIET nunca chega no gatilho; LATE so rompe quarenta minutos depois da
        // formacao, e a janela e de trinta.
        assertTrue(run(new Days(60, new Shape[] {Shape.QUIET}), RangeBreakout.CAP)
                .fills().isEmpty(), "um pregao que nunca rompeu o range gerou execucao");

        assertTrue(run(new Days(60, new Shape[] {Shape.LATE}), RangeBreakout.CAP)
                .fills().isEmpty(), "um rompimento fora da janela de trinta minutos foi aceito");
    }

    @Test
    @DisplayName("a EMA de baixa nao deixa comprar o rompimento de alta")
    void adowntrendRefusesTheUpsideBreak() {
        // Os fechamentos diarios CAEM aqui, entao a inclinacao da EMA 10 e
        // negativa e o unico lado permitido e a venda -- e o range so rompe para
        // cima. Sem o filtro, sessenta pregoes de compra.
        PriceSeries falling = new Falling(60);

        assertTrue(run(falling, RangeBreakout.CAP).fills().isEmpty(),
                "comprou o rompimento de alta com a EMA 10 diaria caindo");
    }

    /** Os mesmos pregões, com a base descendo em vez de subir. */
    private record Falling(Days rising) implements PriceSeries {

        private Falling(int count) {
            this(new Days(count, null));
        }

        /** Desce cem por dia sobre uma base que sobe cinquenta: sobra menos cinquenta. */
        private double flip(int index) {
            return -2.0 * (index / PER_DAY) * 50;
        }

        @Override
        public int size() {
            return rising.size();
        }

        @Override
        public long timeAt(int index) {
            return rising.timeAt(index);
        }

        @Override
        public double openAt(int index) {
            return rising.openAt(index) + flip(index);
        }

        @Override
        public double highAt(int index) {
            return rising.highAt(index) + flip(index);
        }

        @Override
        public double lowAt(int index) {
            return rising.lowAt(index) + flip(index);
        }

        @Override
        public double closeAt(int index) {
            return rising.closeAt(index) + flip(index);
        }
    }

    @Test
    @DisplayName("NENHUM LOTE ENTRA antes de a operacao ter andado 0,25R")
    void nolotIsAddedBeforeThetradeHasRun() {
        PriceSeries series = new Days(60, null);
        Result result = run(series, RangeBreakout.CAP);

        // O R e o alcance do range mais um tick, porque a entrada sai na maxima
        // mais um tick e o stop e a minima. A adicao executa no extremo do
        // minuto que ARMOU o pullback, e esse minuto so arma depois de a
        // excursao ter alcancado 0,25R -- entao toda adicao nasce dali para
        // cima. Sem esse piso o pullback arma no primeiro fechamento mais baixo,
        // que na pratica e o minuto seguinte a entrada, e a estrategia vira uma
        // maquina de empilhar lote em cima de si mesma.
        double r = RANGE + OpeningRange.TICK;
        double least = r + RangeBreakout.ARM_R * r;

        int adds = 0;
        LocalDate day = null;
        boolean first = true;

        for (Fill fill : result.fills()) {
            LocalDate now = dayOf(series, fill);

            if (!now.equals(day)) {
                day = now;
                first = true;
            }

            if (!"BuyStop".equals(fill.verb())) {
                continue;
            }

            if (first) {
                first = false;

                continue;
            }

            adds++;

            double base = 100_000 + (fill.bar() / PER_DAY) * 50.0;

            assertTrue(fill.price() >= base + least,
                    "um lote entrou em " + (fill.price() - base)
                            + " acima da base, antes dos " + least + " que 0,25R exige");
        }

        assertTrue(adds > 0, "nenhum lote foi acrescentado, entao este teste nao provou nada");
    }

    @Test
    @DisplayName("o lote inicial sai no gatilho, e o range mais um tick")
    void thefirstLotEntersAtTheTrigger() {
        PriceSeries series = new Days(60, null);
        Result result = run(series, RangeBreakout.CAP);

        Fill first = result.fills().get(0);
        double base = 100_000 + FIRST_TRADED * 50;

        assertEquals("BuyStop", first.verb(), "a primeira execucao nao foi o gatilho de compra");
        assertEquals(RangeBreakout.LOT, first.quantity(),
                "o lote inicial nao tem os contratos configurados");
        assertEquals(base + RANGE + OpeningRange.TICK, first.price(), 1e-9,
                "a entrada nao saiu na maxima do range mais um tick");
    }

    @Test
    @DisplayName("um lote larga metade de si uma vez, e so uma")
    void alotShedsHalfOfItselfOnceAndOnlyOnce() {
        PriceSeries series = new Days(60, null);
        Result result = run(series, RangeBreakout.CAP);

        // CONTADO POR PREGAO, e comparado com os lotes daquele pregao. Casar o
        // preco exato da parcial nao serve: uma ordem limitada executa no melhor
        // entre o nivel e a abertura, entao a segunda parcial de um mesmo lote
        // sai num preco que o nivel nao reconhece -- e foi assim que a primeira
        // versao deste teste deixou passar o lote largando metade duas vezes.
        int worst = 0;
        int shed = 0;
        LocalDate day = null;
        int lots = 0;
        int limits = 0;

        for (Fill fill : result.fills()) {
            LocalDate now = dayOf(series, fill);

            if (!now.equals(day)) {
                worst = Math.max(worst, limits - lots);
                day = now;
                lots = 0;
                limits = 0;
            }

            if ("BuyStop".equals(fill.verb())) {
                lots++;
            }

            if ("SellToCoverLimit".equals(fill.verb())) {
                limits++;
                shed++;
            }
        }

        worst = Math.max(worst, limits - lots);

        assertTrue(shed > 0, "nenhum lote largou metade de si");

        // UMA PARCIAL POR LOTE, no maximo, mais o alvo global que fecha o resto:
        // as saidas limitadas de um pregao nunca passam de um a mais que os
        // lotes dele. Sem a marca de ja ter largado, o mesmo lote solta metade,
        // depois metade do que sobrou, e o backtest inventa saidas que nao
        // existem.
        assertTrue(worst <= 1,
                "um pregao teve " + worst + " saidas limitadas a mais que os lotes abertos");
    }

    @Test
    @DisplayName("as linhas desenhadas acompanham o range do proprio dia")
    void thelinesFollowTheDaysOwnRange() {
        PriceSeries series = new Days(60, null);
        RangeBreakout strategy = new RangeBreakout(SP);
        Result ignored = new Backtest(Costs.NONE, RangeBreakout.LOT).run(series, strategy);

        assertFalse(ignored.fills().isEmpty(), "a varredura nao executou nada");

        double[] high = strategy.curves().get("Range alta");
        double[] low = strategy.curves().get("Range baixa");

        assertEquals(series.size(), high.length, "a curva nao cobre a serie toda");

        int bar = 41 * PER_DAY + 100;
        double base = 100_000 + 41 * 50;

        assertEquals(base + RANGE, high[bar], 1e-9, "a linha de alta nao e a do range do dia");
        assertEquals(base, low[bar], 1e-9, "a linha de baixa nao e a do range do dia");
    }
}
