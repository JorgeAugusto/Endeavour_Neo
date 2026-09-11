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
package br.com.jorge.reis.endeavourneo.domain.market;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O recorte: o trecho que UMA rodada usa.
 *
 * <p>Contado em <b>pregões</b>, e não em dias de calendário. O projeto anterior
 * contava durações, e isso se lê bem até "1 dia" cair num domingo: a rodada
 * cobre barra nenhuma, não reporta nada, e o leitor lê "a estratégia não
 * operou".</p>
 */
@DisplayName("O recorte")
class SliceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /**
     * Pregões de 03/09/2020 em diante, pulando fim de semana.
     *
     * <p>Com {@code bars} barras por pregão — e o penúltimo com UMA barra só,
     * porque um pregão curto antes de feriado existe e é o caso que quebra
     * quem conta barras em vez de contar viradas de dia.</p>
     */
    private record Sessions(int days, int bars) implements PriceSeries {

        private LocalDate dayOf(int session) {
            LocalDate day = LocalDate.of(2020, 9, 3);
            int walked = 0;

            while (walked < session) {
                day = day.plusDays(1);

                if (day.getDayOfWeek().getValue() <= 5) {
                    walked++;
                }
            }

            return day;
        }

        private int barsIn(int session) {
            return session == days - 2 ? 1 : bars;
        }

        @Override
        public int size() {
            int total = 0;

            for (int session = 0; session < days; session++) {
                total += barsIn(session);
            }

            return total;
        }

        @Override
        public long timeAt(int index) {
            int session = 0;
            int left = index;

            while (left >= barsIn(session)) {
                left -= barsIn(session);
                session++;
            }

            return ZonedDateTime.of(dayOf(session), LocalTime.of(10, 0), SP)
                    .plusMinutes(5L * left).toInstant().toEpochMilli();
        }

        @Override
        public double openAt(int index) {
            return 100 + index;
        }

        @Override
        public double highAt(int index) {
            return 101 + index;
        }

        @Override
        public double lowAt(int index) {
            return 99 + index;
        }

        @Override
        public double closeAt(int index) {
            return 100 + index;
        }
    }

    private static java.util.Set<LocalDate> dias(PriceSeries series) {
        java.util.Set<LocalDate> days = new java.util.TreeSet<>();

        for (int bar = 0; bar < series.size(); bar++) {
            days.add(Instant.ofEpochMilli(series.timeAt(bar)).atZone(SP).toLocalDate());
        }

        return days;
    }

    private static int sessionsIn(PriceSeries series) {
        java.util.Set<LocalDate> days = new java.util.HashSet<>();

        for (int bar = 0; bar < series.size(); bar++) {
            days.add(Instant.ofEpochMilli(series.timeAt(bar)).atZone(SP).toLocalDate());
        }

        return days.size();
    }

    @Test
    @DisplayName("um dia e o ultimo pregao, e nao as ultimas vinte e quatro horas")
    void adayIsTheLastSession() {
        Sessions serie = new Sessions(30, 10);
        PriceSeries cortada = Slice.DAY.cut(serie, SP);

        assertEquals(1, sessionsIn(cortada), "um dia trouxe mais de um pregao");
        assertEquals(10, cortada.size(), "um dia nao trouxe as dez barras do ultimo pregao");
    }

    @Test
    @DisplayName("uma semana sao cinco pregoes, mesmo com um deles curto")
    void aweekIsFiveSessionsEvenWithAShortOne() {
        // O penultimo pregao tem UMA barra. Quem contasse barras traria cinco
        // pregoes em alguns lugares da serie e seis noutros, sem dizer nada.
        Sessions serie = new Sessions(30, 10);
        PriceSeries cortada = Slice.WEEK.cut(serie, SP);

        assertEquals(5, sessionsIn(cortada), "uma semana nao deu cinco pregoes");
        assertEquals(10 + 10 + 10 + 1 + 10, cortada.size(),
                "a semana nao somou as barras dos cinco pregoes");
    }

    @Test
    @DisplayName("recorte maior que a serie traz a serie inteira")
    void asliceLongerThanTheSeriesBringsAllOfIt() {
        Sessions serie = new Sessions(4, 10);

        assertEquals(serie.size(), Slice.YEAR.cut(serie, SP).size(),
                "um ano numa serie de quatro pregoes perdeu barras");
    }

    @Test
    @DisplayName("tudo e a propria serie, sem copia nem corte")
    void allIsTheSeriesItself() {
        Sessions serie = new Sessions(10, 5);

        assertSame(serie, Slice.ALL.cut(serie, SP), "tudo devolveu outra coisa");
    }

    @Test
    @DisplayName("datas escolhidas sem as datas devolve a serie, que e a resposta inofensiva")
    void chosenWithoutDatesHandsTheSeriesBack() {
        Sessions serie = new Sessions(10, 5);

        // Sozinha ela nao define recorte nenhum. Cortar por um palpite seria
        // pior que nao cortar.
        assertSame(serie, Slice.CHOSEN.cut(serie, SP), "as datas escolhidas cortaram sem datas");
        assertTrue(Slice.CHOSEN.handPicked(), "as datas escolhidas nao se declaram assim");
    }

    @Test
    @DisplayName("entre duas datas, as duas entram")
    void betweenTwoDatesBothAreIncluded() {
        Sessions serie = new Sessions(20, 10);

        // A DATA DO TERCEIRO PREGAO, e nao a de dois dias depois: o terceiro
        // dia de calendario a partir de uma quinta e um sabado, que nao e
        // pregao nenhum. Foi assim que este teste falhou na primeira vez, e o
        // errado era ele.
        java.util.List<LocalDate> pregoes = new java.util.ArrayList<>(
                new java.util.TreeSet<>(dias(serie)));

        LocalDate primeiro = pregoes.get(0);
        LocalDate terceiro = pregoes.get(2);

        PriceSeries cortada = Slice.between(serie, primeiro, terceiro, SP);

        assertEquals(3, sessionsIn(cortada), "o intervalo nao trouxe os tres pregoes");
    }

    @Test
    @DisplayName("um intervalo que termina antes de comecar devolve a serie")
    void arangeThatEndsBeforeItStartsHandsTheSeriesBack() {
        Sessions serie = new Sessions(10, 5);
        LocalDate dia = LocalDate.of(2020, 9, 10);

        assertSame(serie, Slice.between(serie, dia, dia.minusDays(3), SP),
                "um intervalo de tras para frente cortou alguma coisa");
    }

    @Test
    @DisplayName("O RECORTE NAO ATRAVESSA O SEGMENTO")
    void therecorteDoesNotCrossTheSegment() {
        Sessions inteira = new Sessions(40, 10);

        LocalDate comeco = Instant.ofEpochMilli(inteira.timeAt(0)).atZone(SP).toLocalDate();

        // O segmento e a PRIMEIRA metade da serie. O recorte pedido e "1 ano",
        // que e maior que ela inteira.
        PriceSeries segmento = SegmentedSeries.of(inteira,
                new Segment("busca", comeco, comeco.plusDays(27)), SP);

        PriceSeries recorte = Slice.YEAR.cut(segmento, SP);

        // A regra do vocabulario: um recorte vive DENTRO de um segmento e nunca
        // atravessa dois -- atravessar mistura busca e teste sem ninguem notar.
        // Aqui ela sai de graca, porque o recorte se aplica ao que o segmento ja
        // deixou passar.
        assertTrue(recorte.size() <= segmento.size(),
                "o recorte trouxe mais barras do que o segmento tem");
        assertEquals(segmento.closeAt(segmento.size() - 1),
                recorte.closeAt(recorte.size() - 1), 0.0,
                "o recorte terminou fora do segmento");
    }

    @Test
    @DisplayName("todo recorte tem nome de tela")
    void everySliceHasAScreenName() {
        for (Slice each : Slice.values()) {
            assertTrue(each.key() != null && each.key().startsWith("slice."),
                    each + " nao tem chave de texto");
        }

        assertEquals(13, Slice.values().length,
                "a lista de recortes mudou de tamanho e este teste nao soube");
    }
}
