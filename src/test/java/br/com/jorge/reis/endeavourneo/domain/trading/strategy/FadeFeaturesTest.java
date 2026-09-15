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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.indicator.OpeningImpulse;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Os 22 atributos: a ordem é o modelo, e nenhum deles pode olhar para a frente. */
@DisplayName("Atributos do fade")
class FadeFeaturesTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static final int DAYS = 40;

    private static final int PER_DAY = 60;

    /** O sinal mora aqui: dia 39, meia hora depois da abertura. */
    private static final int SIGNAL = 39 * PER_DAY + 30;

    /**
     * Quarenta pregões de sessenta minutos, com um passeio determinístico.
     *
     * <p>Quarenta porque a média verdadeira é de vinte sessões e o retorno de
     * vinte precisa de mais vinte antes dele: com menos, metade dos atributos
     * sai NaN e o teste passa sem ter olhado para nada.</p>
     */
    private static PriceSeries days(double tailShift) {
        double[] price = new double[DAYS * PER_DAY];

        for (int i = 0; i < price.length; i++) {
            double drift = 40.0 * i;
            double wave = 900 * Math.sin(i / 37.0) + 300 * Math.sin(i / 11.0);

            price[i] = 100_000 + drift + wave;

            // O FUTURO DO PROPRIO DIA DO SINAL, empurrado para longe. Nenhum dos
            // vinte e dois pode senti-lo.
            if (tailShift != 0 && i > SIGNAL) {
                price[i] += tailShift;
            }
        }

        return new Walk(price);
    }

    private record Walk(double[] price) implements PriceSeries {

        @Override
        public int size() {
            return price.length;
        }

        @Override
        public long timeAt(int index) {
            // Um pregao por dia civil, comecando as 09:00 e com uma barra por
            // minuto, para que o dobramento diario tenha onde cortar.
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
            return price[index] + 60;
        }

        @Override
        public double lowAt(int index) {
            return price[index] - 60;
        }

        @Override
        public double closeAt(int index) {
            return price[index];
        }
    }

    private static final OpeningImpulse.Fight FIGHT =
            new OpeningImpulse.Fight(LocalDate.of(2025, 1, 6).plusDays(39), 1,
                    101_800, 101_200, 0);

    private static OpeningRange.Session clockOf(PriceSeries bars) {
        return new OpeningRange.Session(LocalDate.of(2025, 1, 6).plusDays(39),
                39 * PER_DAY, 40 * PER_DAY - 1,
                bars.timeAt(39 * PER_DAY) + 20 * 60_000L,
                102_000, 101_000, -1, 0, Double.NaN, Double.NaN, 0);
    }

    @Test
    @DisplayName("SÃO VINTE E DOIS, nesta ordem, e ela não se ordena por alfabeto")
    void therearTwentyTwoInTheCanonicalOrder() {
        assertEquals(22, FadeFeatures.COUNT);
        assertEquals(22, FadeFeatures.NAMES.length);

        // Escrita a mao a partir da especificacao. Se alguem reordenar ou
        // ordenar por alfabeto, e esta linha que cai -- e nada mais cairia,
        // porque nos NUMEROS nao ha nada que diga qual coluna e qual.
        assertArrayEquals(new String[] {
            "minute", "family", "band",
            "ro_size_atr", "r90_size_atr", "range_ratio",
            "ro_dir", "r90_dir",
            "prior_ret1", "prior_ret5", "prior_ret20",
            "ema5s", "ema10s", "ema20s",
            "gap_atr",
            "ret5_atr", "ret15_atr", "vol15_atr", "volume_z",
            "pos_ro", "pos_r90", "vwap_z",
        }, FadeFeatures.NAMES, "a ordem das colunas mudou");
    }

    @Test
    @DisplayName("NENHUM ATRIBUTO SENTE O FUTURO, nem o do próprio dia")
    void nofeatureEverSeesTheFuture() {
        PriceSeries quiet = days(0);
        PriceSeries wild = days(9_000);

        double[] before = new FadeFeatures(quiet, SP)
                .of(SIGNAL, "range90_ext_sup_1.0", 101_500, FIGHT, clockOf(quiet));
        double[] after = new FadeFeatures(wild, SP)
                .of(SIGNAL, "range90_ext_sup_1.0", 101_500, FIGHT, clockOf(wild));

        // A FIXTURE PRECISA VALER ALGUMA COISA: com tudo NaN os dois vetores
        // seriam "iguais" e o teste aprovaria o nada.
        assertTrue(FadeFeatures.complete(before),
                "a amostra saiu incompleta: " + java.util.Arrays.toString(before));

        for (int i = 0; i < FadeFeatures.COUNT; i++) {
            assertEquals(before[i], after[i], 1e-12, "o atributo " + FadeFeatures.NAMES[i]
                    + " mudou quando o RESTO DO DIA do sinal mudou, entao ele le o futuro");
        }
    }

    @Test
    @DisplayName("A FAMÍLIA E A BANDA saem do nome da fonte")
    void thefamilyAndTheBandComeFromTheSourceName() {
        assertEquals(0, FadeFeatures.familyOf("abertura_ext_sup_0.5"));
        assertEquals(1, FadeFeatures.familyOf("range90_mid"));
        assertEquals(2, FadeFeatures.familyOf("vwap_p3"));
        assertEquals(2, FadeFeatures.familyOf(null));

        assertEquals(0, FadeFeatures.bandOf("range90_mid"));
        assertEquals(3, FadeFeatures.bandOf("vwap_p3"));
        assertEquals(-2, FadeFeatures.bandOf("vwap_m2"));
        assertEquals(0, FadeFeatures.bandOf(null));
    }

    @Test
    @DisplayName("A POSIÇÃO NOS RANGES é medida a partir do ponto médio deles")
    void thepositionIsMeasuredFromEachRangesMidpoint() {
        PriceSeries bars = days(0);
        double[] made = new FadeFeatures(bars, SP)
                .of(SIGNAL, "abertura_mid", 101_800, FIGHT, clockOf(bars));

        // A briga vai de 101.200 a 101.800: meio em 101.500, tamanho 600. Uma
        // entrada no topo dela fica meio tamanho acima do meio.
        assertEquals(0.5, made[19], 1e-12, "pos_ro");

        // O range 90 vai de 101.000 a 102.000: meio em 101.500, tamanho 1.000.
        assertEquals(0.3, made[20], 1e-12, "pos_r90");

        // E a razao entre os dois tamanhos.
        assertEquals(600.0 / 1_000.0, made[5], 1e-12, "range_ratio");
    }

    @Test
    @DisplayName("O MINUTO é o do relógio local, e não o índice da barra")
    void theminuteIsTheLocalClock() {
        PriceSeries bars = days(0);
        double[] made = new FadeFeatures(bars, SP)
                .of(SIGNAL, "vwap_m1", 101_500, FIGHT, clockOf(bars));

        // A barra 30 do pregao que abre as 09:00 e as 09:30.
        assertEquals(9 * 60 + 30, made[0], 1e-12);
    }
}
