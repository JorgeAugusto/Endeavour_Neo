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
package br.com.jorge.reis.endeavourneo.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Finding the bases and reading one without reading it twice.
 */
@DisplayName("SeriesCatalog")
class SeriesCatalogTest {

    @AfterEach
    void stopPointingAtTheTemporaryFolder() {
        SeriesCatalog.useFolderForTest(null);
        SeriesCatalog.forget();
    }

    @Test
    @DisplayName("o que o leitor escreve COMPLETA o que vem de fabrica")
    void whatthereaderWritesCompletesTheDefaults() {
        // The setting used to be the fallback's SUBSTITUTE. A reader who opened
        // the file to name one new market lost the built-in name of every other
        // one -- and nothing said so, because a market with no stated name falls
        // back to a guess that usually looks reasonable. The tree they were
        // trying to improve came out worse than before they touched it.
        Settings settings = Settings.settings();
        String was = settings.get("data.groups", null);

        try {
            settings.put("data.groups", "ouro-1m=ouro");

            assertEquals("ouro", SeriesCatalog.groupOf("ouro-1m"),
                    "what the reader wrote was not read at all");
            assertEquals("win", SeriesCatalog.groupOf("winfull-1m"),
                    "naming one market threw away the built-in name of every other one");

            // And what the reader writes WINS where the two meet, which is the
            // other thing this file is for.
            settings.put("data.groups", "winfull-1m=outro");

            assertEquals("outro", SeriesCatalog.groupOf("winfull-1m"),
                    "the built-in value overrode the reader's own");
        } finally {
            if (was == null) {
                settings.remove("data.groups");
            } else {
                settings.put("data.groups", was);
            }
        }
    }

    /** A base of one bar, written the way the first Endeavour writes it. */
    private static void base(Path folder, String name, double close) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24 + 48).order(ByteOrder.BIG_ENDIAN);

        buffer.put("ENDVCNDL".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putLong(1);
        buffer.putLong(1_756_000_000_000L);
        buffer.putDouble(close);
        buffer.putDouble(close);
        buffer.putDouble(close);
        buffer.putDouble(close);
        buffer.putDouble(10);

        // Written through fileOf, because the folders are now the tree and
        // there is exactly one rule for where a series goes. A fixture that
        // built the path itself would keep passing the day that rule changed.
        SeriesCatalog.useFolderForTest(folder);

        Path file = SeriesCatalog.fileOf(name);

        Files.createDirectories(file.getParent());
        Files.write(file, buffer.array());
    }

    @Test
    @DisplayName("the bases in the folder are listed by name, sorted")
    void basesAreListed(@TempDir Path folder) throws IOException {
        base(folder, "winn-1m", 136_000);
        base(folder, "winfut-1m", 104_000);
        Files.writeString(folder.resolve("readme.txt"), "not a base");
        Files.write(folder.resolve("broken.bin"), "not a base either, but named like one"
                .getBytes(StandardCharsets.UTF_8));

        SeriesCatalog.useFolderForTest(folder);

        assertEquals(List.of("winfut-1m", "winn-1m"), SeriesCatalog.names(),
                "a file that is not a base must not be offered as one");
    }

    @Test
    @DisplayName("a base is read once and handed out again")
    void aBaseIsReadOnce(@TempDir Path folder) throws IOException {
        // Several windows on one instrument is the ordinary case, and reading
        // thirty megabytes for each of them is not.
        base(folder, "winn-1m", 136_000);
        SeriesCatalog.useFolderForTest(folder);

        PriceSeries first = SeriesCatalog.open("winn-1m").orElseThrow();
        PriceSeries again = SeriesCatalog.open("winn-1m").orElseThrow();

        assertSame(first, again, "the base was read a second time");
        assertEquals(136_000.0, first.closeAt(0), 1e-9);
    }

    @Test
    @DisplayName("asking for a base that is not there is an answer, not a failure")
    void anAbsentBaseIsEmpty(@TempDir Path folder) throws IOException {
        base(folder, "winn-1m", 136_000);
        SeriesCatalog.useFolderForTest(folder);

        Optional<PriceSeries> missing = SeriesCatalog.open("does-not-exist");

        assertTrue(missing.isEmpty());
        assertFalse(SeriesCatalog.has("does-not-exist"));
        assertTrue(SeriesCatalog.has("winn-1m"));
    }

    @Test
    @DisplayName("a retired base is not offered, and its file is left alone")
    void aRetiredBaseIsHiddenNotDeleted(@TempDir Path folder) throws IOException {
        // win-1m is the WIN adjusted by ratio: in it a point was worth R$ 0,20
        // in 2026 and R$ 0,12 in 2022, so the older years came out inflated by
        // up to 67%. Offering it beside the raw ones is how a measurement gets
        // taken on the wrong base by accident.
        base(folder, "winn-1m", 136_000);
        base(folder, "win-1m", 130_000);
        SeriesCatalog.useFolderForTest(folder);

        assertEquals(List.of("winn-1m"), SeriesCatalog.names(),
                "the retired base was offered in the listing");

        assertTrue(Files.isRegularFile(SeriesCatalog.fileOf("win-1m")),
                "the file was deleted; it was only meant to be hidden");

        // Asked for by name it still opens, so a workspace that remembers it is
        // not silently handed a different instrument.
        assertTrue(SeriesCatalog.has("win-1m"));
        assertEquals(130_000.0, SeriesCatalog.open("win-1m").orElseThrow().closeAt(0), 1e-9);
    }

    @Test
    @DisplayName("the default is the source, when it is there")
    void theDefaultIsTheSource(@TempDir Path folder) throws IOException {
        // The one series checked against the reference product, with the
        // search-and-test boundary inside it as segments. Opening a raw export
        // by default would put a chart on screen that nobody vetted.
        base(folder, "btcusdt-1m", 60_000);
        base(folder, "winn-1m", 136_000);
        base(folder, "winfull-1m", 136_000);
        SeriesCatalog.useFolderForTest(folder);

        assertEquals("winfull-1m", SeriesCatalog.defaultName());
    }

    @Test
    @DisplayName("with no base at all, the default still names something")
    void theDefaultSurvivesAnEmptyFolder(@TempDir Path folder) {
        SeriesCatalog.useFolderForTest(folder);

        assertTrue(SeriesCatalog.names().isEmpty());
        assertEquals("winfull-1m", SeriesCatalog.defaultName());
        assertFalse(SeriesCatalog.has("winfull-1m"));
    }
    @Test
    @DisplayName("the scale is read from the name, and it is the FIRST part")
    void theScaleComesFromTheName() {
        assertEquals("1m", SeriesCatalog.scaleOf("winfull-1m"));
        assertEquals("1s", SeriesCatalog.scaleOf("winfull-1s"));

        // The one that made this worth writing down. A year of minutes: taking
        // the last part would file it under a scale of "one year", which is a
        // recorte, not a scale. And 1y is not a scale code at all, so a reader
        // that scanned for the last MATCH would still get it wrong the day
        // somebody writes winfull-1m-2d.
        assertEquals("1m", SeriesCatalog.scaleOf("btcusdt-1m-1y"));
    }

    @Test
    @DisplayName("no series is ever labelled with the market's bare name")
    void noSeriesWearsTheMarketsName() {
        // THE SCALE COMES OFF WHEREVER IT IS. scaleOf reads the FIRST part that
        // is a scale, deliberately and with a test of its own just above --
        // scaleOf("btcusdt-1m-1y") is "1m" -- and displayOf took it off assuming
        // it was the suffix. For that name the two readings disagreed and the
        // scale stayed: the label came out repeating the very scale the series
        // is already hanging under, which is what displayOf exists to stop.
        assertFalse(SeriesCatalog.displayOf("btcusdt-1m-1y").contains("1M1"),
                "the scale was left in the label of a series that does not carry it as a "
                        + "suffix: " + SeriesCatalog.displayOf("btcusdt-1m-1y"));
        assertTrue(SeriesCatalog.displayOf("btcusdt-1m-1y").endsWith("-1Y"),
                "what is left over is not what tells this series from its neighbours: "
                        + SeriesCatalog.displayOf("btcusdt-1m-1y"));

        // The rule, and the reason it is a rule about LABELS rather than about
        // this market. displayOf drops the scale and the instrument prefix and
        // reads what is left beside the market's name -- and for a file named
        // after its own market nothing is left, so it used to come out as the
        // market and nothing else.
        //
        // That gave one series the market's own name while its neighbours read
        // as variants of it. In this base the one that happens to be named
        // "win" is the series adjusted by ratio, which inflates the older years
        // by up to 67%: the label put the poisoned base at the top of the tree
        // looking canonical, and the three raw ones under it looking derived.
        String market = SeriesCatalog.displayOf("winfut-1m")
                .substring(0, SeriesCatalog.displayOf("winfut-1m").indexOf('-'));

        assertNotEquals(market, SeriesCatalog.displayOf("win-1m"),
                "a series is wearing the market's own name, so it reads as the canonical "
                        + "one and every other series of that market reads as a variant");

        // What it reads instead: its own file name, which is the only thing that
        // tells it from its neighbours.
        assertEquals(market + "-WIN", SeriesCatalog.displayOf("win-1m"));

        // And the neighbours are untouched -- this changes the one case that
        // had nothing left over, not the naming.
        assertEquals(market + "-FUT", SeriesCatalog.displayOf("winfut-1m"));
        assertEquals(market + "-N", SeriesCatalog.displayOf("winn-1m"));
        assertEquals(market + "-FULL", SeriesCatalog.displayOf("winfull-1m"));

        // The exception, and the first version of this rule got it wrong: a
        // market with no name of its own in the bundle is shown by its key, so
        // appending the file name repeats the same word -- "ouro-OURO", which a
        // reader reads twice and learns nothing from. Where the two are the same
        // word there is genuinely nothing to distinguish, and the market alone
        // is the honest answer. The rule bites where they DIFFER, which is
        // exactly where one series could be mistaken for the market.
        assertEquals("ouro", SeriesCatalog.displayOf("ouro"));
    }

    @Test
    @DisplayName("a name that says no scale gets none, rather than a guess")
    void anUnnamedScaleStaysEmpty() {
        // Empty hangs the series straight off its instrument in the tree. A
        // guess would put a heading there that nothing on disk agrees with.
        assertEquals("", SeriesCatalog.scaleOf("winfull"));
        assertEquals("", SeriesCatalog.scaleOf("win-diario"));
        assertEquals("", SeriesCatalog.scaleOf(null));
    }

    @Test
    @DisplayName("scales sort coarsest first, with the ticks last")
    void coarsestFirst() {
        List<String> scales = new java.util.ArrayList<>(
                List.of(SeriesCatalog.TICKS, "1s", "1d", "", "5m", "1m", "1h"));

        scales.sort(SeriesCatalog.coarsestFirst());

        // Reading down is zooming in, which is the order the reader listed them
        // in. The unreadable one goes after even the ticks: it is not finer
        // than a tick, it is unknown, and last is where unknown belongs.
        assertEquals(List.of("1d", "1h", "5m", "1m", "1s", SeriesCatalog.TICKS, ""), scales);
    }

    @Test
    @DisplayName("a minute is sixty seconds and a tick is none")
    void secondsPerBar() {
        assertEquals(1, SeriesCatalog.secondsOf("1s"));
        assertEquals(300, SeriesCatalog.secondsOf("5m"));
        assertEquals(3_600, SeriesCatalog.secondsOf("1h"));
        assertEquals(86_400, SeriesCatalog.secondsOf("1d"));
        assertEquals(0, SeriesCatalog.secondsOf(SeriesCatalog.TICKS));
        assertEquals(-1, SeriesCatalog.secondsOf("1y"));
    }
    @Test
    @DisplayName("a new scale of a known market is not a new market")
    void aScaleIsNotAMarket() {
        // What the tree caught. The markets used to be stated by whole file
        // name, which held only while every market had one scale: winfull-1s
        // fell out of the map, derived "winfull" from its own prefix, and
        // appeared as a second WIN beside the first -- with the same label, so
        // it read as a duplicate rather than as a bug.
        assertEquals("win", SeriesCatalog.groupOf("winfull-1m"));
        assertEquals("win", SeriesCatalog.groupOf("winfull-1s"));
        assertEquals("win", SeriesCatalog.groupOf("winn-1m"));
        assertEquals("win", SeriesCatalog.groupOf("winfut-1m"));

        // And the three exports still do not become three markets, which is
        // the older mistake this map exists to prevent.
        assertEquals(SeriesCatalog.groupOf("winn-1m"), SeriesCatalog.groupOf("winfut-1m"));

        // A market nobody stated is still its own prefix.
        assertEquals("ouro", SeriesCatalog.groupOf("ouro-1m"));
    }

    /** A base of that many one-minute bars from that instant, close = index. */
    private static void bars(Path folder, String name, long from, int count) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(24 + 48 * count).order(ByteOrder.BIG_ENDIAN);

        buffer.put("ENDVCNDL".getBytes(StandardCharsets.US_ASCII));
        buffer.putInt(1);
        buffer.putInt(1);
        buffer.putLong(count);

        for (int i = 0; i < count; i++) {
            buffer.putLong(from + i * 60_000L);
            buffer.putDouble(i);
            buffer.putDouble(i);
            buffer.putDouble(i);
            buffer.putDouble(i);
            buffer.putDouble(1);
        }

        SeriesCatalog.useFolderForTest(folder);

        Path file = SeriesCatalog.fileOf(name);

        Files.createDirectories(file.getParent());
        Files.write(file, buffer.array());
    }

    @Test
    @DisplayName("a janela de um trecho antigo e lida NELE, nao no fim do arquivo")
    void aWindowCanBeAnchoredAtASegmentsEnd(@TempDir Path folder) throws IOException {
        // The defect, from a real workspace. The window is the most recent
        // bars, which is right when the chart shows the whole series -- and it
        // is what open(name, bars) still does. It is wrong for a chart of a
        // SEGMENT: the reader had one running 01/09/2020 to 11/11/2024 in a file
        // that ends in September 2026, so the hundred thousand most recent bars
        // began in December 2025 and the segment held NONE of them. The console
        // reported "100000 barras lidas do disco", the chart came up empty, and
        // nothing joined the two facts.
        //
        // The neighbouring segment was worse: it overlapped in part, so it drew
        // and looked right while quietly missing its first thirteen months.
        bars(folder, "winfull-1m", 1_000_000_000_000L, 1_000);

        SeriesCatalog.useFolderForTest(folder);

        // A stretch that ends at bar 300 of a thousand, with a window of 100.
        long endsAt = 1_000_000_000_000L + 300 * 60_000L;

        PriceSeries fromTheFile = SeriesCatalog.open("winfull-1m", 100).orElseThrow();
        PriceSeries fromTheStretch =
                SeriesCatalog.openUntil("winfull-1m", endsAt, 100).orElseThrow();

        assertEquals(100, fromTheFile.size());
        assertEquals(900.0, fromTheFile.closeAt(0), 1e-9,
                "the file window did not start at the last hundred bars");

        assertEquals(100, fromTheStretch.size(), "the stretch window came back short");
        assertEquals(200.0, fromTheStretch.closeAt(0), 1e-9,
                "the stretch window was not anchored at the stretch");
        assertEquals(299.0, fromTheStretch.closeAt(99), 1e-9,
                "the stretch window ran past the end of the stretch");

        // And the two do not overlap at all, which is the shape of the defect:
        // the reader saw a clean load and an empty chart.
        assertTrue(fromTheStretch.timeAt(99) < fromTheFile.timeAt(0),
                "the fixture does not reproduce the gap it is about");
    }

    @Test
    @DisplayName("um trecho que termina antes do arquivo comecar devolve nada, nao a cabeca dele")
    void aStretchBeforeTheFileIsEmpty(@TempDir Path folder) throws IOException {
        // Reading the head of the file instead would draw bars from outside the
        // stretch -- which is the mistake this exists to stop, wearing the
        // other face.
        bars(folder, "winfull-1m", 1_000_000_000_000L, 1_000);

        SeriesCatalog.useFolderForTest(folder);

        assertEquals(0, SeriesCatalog.openUntil("winfull-1m",
                1_000_000_000_000L - 1, 100).orElseThrow().size());
    }

    @Test
    @DisplayName("abrir a serie TODA nao mudou: continua a janela do fim do arquivo")
    void openingTheWholeSeriesIsUnchanged(@TempDir Path folder) throws IOException {
        // A series can be opened with no segment at all, and that is the common
        // case. Anchoring at the file's end is what a reader wants there.
        bars(folder, "winfull-1m", 1_000_000_000_000L, 1_000);

        SeriesCatalog.useFolderForTest(folder);

        PriceSeries window = SeriesCatalog.open("winfull-1m", 250).orElseThrow();

        assertEquals(250, window.size());
        assertEquals(750.0, window.closeAt(0), 1e-9);
        assertEquals(999.0, window.closeAt(249), 1e-9);
    }

    @Test
    @DisplayName("a serie ja em memoria nao faz a janela do trecho ignorar o fim dele")
    void theCacheDoesNotSwallowTheStretch(@TempDir Path folder) throws IOException {
        // A hole in openUntil from the hour it was written. open(name, bars) may
        // hand back the whole series when it is no bigger than the window, and
        // that reasoning is right THERE: the last N bars of a series shorter
        // than N is the series. It does not carry here. This method is asked for
        // a window ENDING somewhere, and answering with everything ignores the
        // end -- a chart of the stretch that finishes in 2024 would draw the
        // years after it too.
        //
        // And the branch was live: the launcher fills that cache for every
        // series at startup.
        bars(folder, "winfull-1m", 1_000_000_000_000L, 1_000);

        SeriesCatalog.useFolderForTest(folder);

        // The whole file, into the cache, the way the launcher puts it there.
        assertEquals(1_000, SeriesCatalog.open("winfull-1m").orElseThrow().size());

        // A stretch ending at bar 300, with a window WIDER than the file -- which
        // is what makes the cached answer look admissible.
        long endsAt = 1_000_000_000_000L + 300 * 60_000L;
        PriceSeries stretch =
                SeriesCatalog.openUntil("winfull-1m", endsAt, 2_000).orElseThrow();

        // EXCLUSIVE, which is how the caller uses it: MainWindow passes the
        // instant just past the segment's last day, so bar 300 -- whose time IS
        // the boundary -- belongs to what comes after.
        assertEquals(300, stretch.size(),
                "the window ran past the end of the stretch");
        assertEquals(299.0, stretch.closeAt(stretch.size() - 1), 1e-9,
                "the last bar is not the last bar of the stretch");
    }
}
