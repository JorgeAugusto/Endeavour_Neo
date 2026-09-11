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
package br.com.jorge.reis.endeavourneo.ui.backtest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.MarketFile;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.TickFile;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Qual caminho de preços a rodada anda.
 *
 * <p>A regra é dele e é a certa: <b>um negócio real vale mais que um
 * plausível</b>. O que estes testes defendem é que ela é seguida nos dois
 * sentidos — a fita não é trocada por invenção, e a ausência de fita não
 * interrompe a rodada.</p>
 */
@DisplayName("De onde vem o tick da rodada")
class TickLevelTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    private static final LocalDate DIA = LocalDate.of(2026, 9, 1);

    @TempDir
    Path settings;

    @TempDir
    Path folder;

    @BeforeEach
    void useMyOwnEverything() {
        SeriesCatalog.useSettingsForTest(settings.resolve("settings.properties"));
        Segmentation.useForTest(settings.resolve("workspace.properties"));
        Timeframe.useZone(SP);
        SeriesCatalog.useFolderForTest(folder);
    }

    @AfterEach
    void putItAllBack() {
        SeriesCatalog.useFolderForTest(null);
        SeriesCatalog.stopUsingTestSettings();
        SeriesCatalog.forget();
        Segmentation.stopUsingTestStore();
    }

    /** Minutos de um pregão só, a partir das 09:00. */
    private record Minutes(int count) implements PriceSeries {

        @Override
        public int size() {
            return count;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(DIA, LocalTime.of(9, 0), SP)
                    .toInstant().toEpochMilli() + index * 60_000L;
        }

        @Override
        public double openAt(int index) {
            return 140_000 + index * 5;
        }

        @Override
        public double closeAt(int index) {
            return openAt(index) + 10;
        }

        @Override
        public double highAt(int index) {
            return closeAt(index) + 15;
        }

        @Override
        public double lowAt(int index) {
            return openAt(index) - 15;
        }
    }

    private void candles(String name) throws IOException {
        Path file = SeriesCatalog.fileOf(name);

        Files.createDirectories(file.getParent());
        MarketFile.write(file, new Minutes(20), 1);

        SeriesCatalog.useFolderForTest(folder);
    }

    /** Uma fita de {@code many} negócios, um segundo entre eles, a partir das 09:00. */
    private void tape(int many) throws IOException {
        Path file = TickSource.METATRADER.fileFor(SeriesCatalog.ticksOf("win"), "win", DIA);

        Files.createDirectories(file.getParent());

        try (TickFile.Writer writer = new TickFile.Writer(file, DIA)) {
            for (int i = 0; i < many; i++) {
                writer.add(9 * 3_600_000 + i * 1_000, 0, 0, 140_000 + (i % 7) * 5, 1, 88,
                        TickFile.Writer.mask(false, false, true, true));
            }
        }
    }

    @Test
    @DisplayName("SERIE DE FITA ANDA SOBRE A FITA, nao sobre invencao")
    void atapeIsWalkedOnTheTape() throws IOException {
        candles("winfull-1m");
        tape(600);

        TickLevel.Walked walked = TickLevel.of(
                new SeriesChoice("win/ticks/metatrader", ""), new Minutes(20), SP);

        // Um stop batido na fita FOI batido. Um batido num caminho sintetico foi
        // batido por um dos caminhos que aquele minuto poderia ter tomado. Trocar
        // o primeiro pelo segundo e jogar fora a unica evidencia real que existe.
        assertTrue(walked.real(), "a fita foi trocada por ticks inventados");
        assertEquals(600, walked.ticks(), "a rodada nao andou sobre os 600 negocios da fita");
        assertEquals(walked.ticks(), walked.series().size(),
                "a contagem mostrada no quadro nao e o tamanho do que rodou");
    }

    @Test
    @DisplayName("serie de candle anda sobre ticks sinteticos")
    void acandleSeriesIsWalkedOnAsyntheticPath() throws IOException {
        candles("winfull-1m");

        TickLevel.Walked walked = TickLevel.of(
                new SeriesChoice("winfull-1m", ""), new Minutes(20), SP);

        assertFalse(walked.real(), "uma serie de candle se disse fita");
        assertTrue(walked.ticks() > 20 * 8,
                "vinte minutos viraram so " + walked.ticks() + " ticks");
        assertEquals(walked.ticks(), walked.series().size(),
                "a contagem mostrada no quadro nao e o tamanho do que rodou");
    }

    @Test
    @DisplayName("fita sem o pregao do recorte cai no sintetico, e nao para")
    void atapeMissingTheRecortesSessionFallsBack() throws IOException {
        candles("winfull-1m");

        // A chave e de fita, e o arquivo do dia nao existe. Parar aqui seria a
        // rodada morrer por um buraco no acervo de ticks, que tem oito pregoes.
        TickLevel.Walked walked = TickLevel.of(
                new SeriesChoice("win/ticks/metatrader", ""), new Minutes(20), SP);

        assertFalse(walked.real(), "uma fita inexistente foi dada como real");
        assertTrue(walked.ticks() > 0, "a rodada ficou sem caminho nenhum");
    }

    @Test
    @DisplayName("o modo de execucao nao diz nada sobre escala")
    void theexecutionModeSaysNothingAboutScale() {
        // ELE SO DIZ COMO AS ORDENS EXECUTAM. A escala e a do grafico -- e o que
        // a estrategia le quando nao nomeia uma propria -- e as duas sao livres
        // uma da outra: qualquer escala roda dos dois jeitos.
        //
        // O caminho de ticks continua vindo das barras ARMAZENADAS, nunca das
        // agregadas, que e como a medicao feita sobre a forma de um minuto
        // continua valendo com o grafico em cinco minutos.
        assertEquals(2, Execution.values().length, "o modo de execucao ganhou uma terceira opcao");

        for (Execution each : Execution.values()) {
            assertNotNull(each.label(), each + " nao tem nome na tela");
            assertFalse(each.label().isBlank(), each + " tem nome vazio");
        }
    }
}
