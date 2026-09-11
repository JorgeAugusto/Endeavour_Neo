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
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.MarketFile;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.Segment;
import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A lista de séries da tela de backtest.
 *
 * <p>Ela não oferece arquivos: oferece o que o resto do programa chama de
 * <b>série</b> e, onde houver, o <b>segmento</b> — que é o que o controle de
 * segmentação existe para definir. O que estes testes defendem é que a lista
 * respeita as regras desse controle, e não só que ela tem itens.</p>
 */
@DisplayName("A lista de series do backtest")
class SeriesChoiceTest {

    private static final ZoneId SP = ZoneId.of("America/Sao_Paulo");

    /** Arquivos e ajustes deste teste, nunca os do leitor. */
    @TempDir
    Path settings;

    @TempDir
    Path folder;

    @BeforeEach
    void useMyOwnEverything() {
        SeriesCatalog.useSettingsForTest(settings.resolve("settings.properties"));
        Segmentation.useForTest(settings.resolve("workspace.properties"));
        Timeframe.useZone(SP);
    }

    @AfterEach
    void putItAllBack() {
        SeriesCatalog.useFolderForTest(null);
        SeriesCatalog.stopUsingTestSettings();
        SeriesCatalog.forget();
        Segmentation.stopUsingTestStore();
    }

    /** Uma barra por dia, a partir de 01/09/2020. */
    private record Daily(int days) implements PriceSeries {

        @Override
        public int size() {
            return days;
        }

        @Override
        public long timeAt(int index) {
            return ZonedDateTime.of(LocalDate.of(2020, 9, 1).plusDays(index),
                    java.time.LocalTime.of(10, 0), SP).toInstant().toEpochMilli();
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

    /**
     * Escreve uma série onde o catálogo diz que ela mora.
     *
     * <p>E não solta na pasta: uma série vive em {@code <ativo>/<escala>/}, e
     * {@code namesIn} descarta o que não estiver lá. Perguntar a
     * {@code fileOf} em vez de montar o caminho é o que impede este teste de
     * passar a testar a convenção de pastas que ele copiou.</p>
     */
    private void series(String name, int days) throws IOException {
        SeriesCatalog.useFolderForTest(folder);

        Path file = SeriesCatalog.fileOf(name);

        Files.createDirectories(file.getParent());
        MarketFile.write(file, new Daily(days), 1);

        SeriesCatalog.useFolderForTest(folder);
    }

    private static List<String> keys() {
        return SeriesChoice.available().stream().map(SeriesChoice::key).toList();
    }

    @Test
    @DisplayName("serie sem segmento aparece inteira, uma vez")
    void aSeriesWithoutSegmentsAppearsWholeAndOnce() throws IOException {
        series("winfull-1m", 10);

        assertEquals(List.of("winfull-1m"), keys(), "a lista nao ofereceu a serie inteira");
    }

    @Test
    @DisplayName("os segmentos aparecem ao lado da serie inteira")
    void theSegmentsAppearBesideTheWholeSeries() throws IOException {
        series("winfull-1m", 10);

        Segmentation.set("winfull-1m", List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 5)),
                Segment.from("teste", LocalDate.of(2020, 9, 6))));

        assertEquals(List.of("winfull-1m", "winfull-1m#busca", "winfull-1m#teste"), keys(),
                "a lista nao trouxe os segmentos criados");
    }

    @Test
    @DisplayName("serie marcada como SO segmentos nao pode ser rodada inteira")
    void aSeriesMarkedSegmentsOnlyCannotBeRunWhole() throws IOException {
        series("winfull-1m", 10);

        Segmentation.set("winfull-1m", List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 5)),
                Segment.from("teste", LocalDate.of(2020, 9, 6))));

        Segmentation.setSegmentsOnly("winfull-1m", true);

        // A marca e como o leitor diz "esta aqui nunca se roda de ponta a
        // ponta". Uma entrada que a ignorasse tornaria a marca enfeite.
        assertEquals(List.of("winfull-1m#busca", "winfull-1m#teste"), keys(),
                "a serie inteira foi oferecida mesmo marcada como so segmentos");
    }

    @Test
    @DisplayName("serie aposentada nao aparece, nem seus segmentos")
    void aRetiredSeriesDoesNotAppearAtAll() throws IOException {
        series("winfull-1m", 10);

        Segmentation.set("winfull-1m", List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 5))));

        SeriesCatalog.setRetired(Set.of("winfull-1m"));

        assertTrue(keys().isEmpty(), "a serie aposentada continuou na lista: " + keys());
    }

    @Test
    @DisplayName("abrir um segmento traz SO as barras dele")
    void openingASegmentBringsOnlyItsBars() throws IOException {
        series("winfull-1m", 10);

        Segmentation.set("winfull-1m", List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 3))));

        PriceSeries cut = new SeriesChoice("winfull-1m#busca", "").open();
        PriceSeries whole = new SeriesChoice("winfull-1m", "").open();

        assertEquals(10, whole.size(), "a serie inteira mudou de tamanho");
        assertEquals(3, cut.size(), "o segmento trouxe " + cut.size() + " barras, e nao as tres dele");
        assertEquals(whole.closeAt(0), cut.closeAt(0), 0.0, "o segmento comecou noutro lugar");
    }

    @Test
    @DisplayName("os dias de um segmento sao SO os dele")
    void thedaysOfASegmentAreOnlyItsOwn() throws IOException {
        series("winfull-1m", 10);

        Segmentation.set("winfull-1m", List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 3))));

        java.util.NavigableSet<LocalDate> doSegmento =
                new SeriesChoice("winfull-1m#busca", "").sessions();

        java.util.NavigableSet<LocalDate> daSerie =
                new SeriesChoice("winfull-1m", "").sessions();

        assertEquals(3, doSegmento.size(), "o segmento ofereceu " + doSegmento.size() + " dias");
        assertEquals(10, daSerie.size(), "a serie inteira ofereceu " + daSerie.size() + " dias");

        // A PONTA E A DO SEGMENTO, nao a da serie. Um campo que abre no ultimo
        // dia da serie, com o segmento terminando tres dias antes, abre fora do
        // que a rodada pode usar.
        assertEquals(LocalDate.of(2020, 9, 3), doSegmento.last(),
                "a ultima data do segmento nao e a dele");
        assertTrue(!doSegmento.contains(LocalDate.of(2020, 9, 8)),
                "um dia de fora do segmento entrou na lista");
    }

    @Test
    @DisplayName("o calendario recusa um dia que o segmento nao tem")
    void thecalendarRefusesADayTheSegmentDoesNotHave() throws IOException {
        series("winfull-1m", 10);

        Segmentation.set("winfull-1m", List.of(
                new Segment("busca", LocalDate.of(2020, 9, 1), LocalDate.of(2020, 9, 3))));

        br.com.jorge.reis.endeavourneo.ui.replay.DatePicker picker =
                new br.com.jorge.reis.endeavourneo.ui.replay.DatePicker(null);

        picker.setSessions(new SeriesChoice("winfull-1m#busca", "").sessions());

        // Sem esta lista o picker cai no padrao "qualquer dia util" -- e ai ele
        // oferece dias que o segmento nao contem, e um intervalo digitado a
        // partir deles cobre nada.
        assertTrue(picker.accepts(LocalDate.of(2020, 9, 2)), "recusou um dia do proprio segmento");
        assertTrue(!picker.accepts(LocalDate.of(2020, 9, 8)),
                "aceitou um dia util que esta fora do segmento");
    }

    @Test
    @DisplayName("serie que nao le nao oferece dia nenhum, e nao estoura")
    void aseriesThatWillNotReadOffersNoDays() {
        java.util.NavigableSet<LocalDate> nada = new SeriesChoice("nao-existe-1m", "").sessions();

        assertTrue(nada.isEmpty(), "uma serie inexistente ofereceu dias");
    }

    @Test
    @DisplayName("escala em tempo se agrega; a que nao e tempo, nao")
    void whatIsMeasuredInTimeMayBeAggregated() throws IOException {
        series("winfull-1m", 10);
        series("winfull-11R", 10);

        // Agregar renko pelo relogio juntaria um tijolo de tres segundos com um
        // de quarenta minutos. A tela desliga a lista de escala nesse caso.
        assertTrue(new SeriesChoice("winfull-1m", "").measuredInTime(),
                "uma serie de minutos deixou de ser medida em tempo");
        assertFalse(new SeriesChoice("winfull-11R", "").measuredInTime(),
                "uma serie de renko aceitou ser agregada pelo relogio");
    }
}
