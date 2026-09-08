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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Chart layout")
class ChartLayoutTest {

    @Test
    @DisplayName("the default layout actually builds its indicators")
    void theDefaultLayoutDrawsSomething() {
        // It did not, for as long as the class was renamed. The default asked for
        // "overlay.ema", the catalogue registers "overlay.movingAverage", and the
        // key survived in the bundle, in four javadocs and in a test fixture --
        // so nothing looked wrong anywhere. Entry.build handed back null,
        // ChartLayout.build dropped nulls in silence (on purpose, to tolerate a
        // layout from a later version), and applying the default gave a chart
        // with no indicator on it.
        //
        // Round-tripping the TEXT could not catch that, and did not: a key that
        // nothing registers writes and reads back perfectly. Only asking the
        // layout for its indicators, and counting them, does.
        // Counting them also catches the SECOND defect, which the first hid:
        // the three periods were one entry's parameter list, and a parameter
        // list is one indicator's settings -- period, shift, kind. It asked for
        // a single average of period 17, shifted 55 bars sideways, of kind 200.
        List<Overlay> built = ChartLayouts.defaultLayout().build();

        assertEquals(3, built.size(),
                "the default layout did not build three separate averages");

        for (Overlay each : built) {
            assertEquals("overlay.movingAverage", each.nameKey());
        }

        assertEquals(List.of(17, 55, 200),
                built.stream()
                        .map(each -> ((br.com.jorge.reis.endeavourneo.ui.chart.overlay.MovingAverage) each)
                                .period())
                        .toList(),
                "the three periods are 17, 55 and 200");
    }

    @Test
    @DisplayName("a layout survives being written and read back, exactly")
    void roundTrip() {
        // This is the whole promise of a saved layout: reopening tomorrow gives
        // back what was there yesterday. Parameters and the eye included -- a
        // hidden indicator that comes back visible is a change nobody made.
        List<ChartLayout.Entry> entries = List.of(
                new ChartLayout.Entry("overlay.movingAverage", List.of(17), true),
                new ChartLayout.Entry("overlay.movingAverage", List.of(9), false));

        List<ChartLayout.Entry> back = ChartLayouts.parse(ChartLayouts.format(entries));

        assertEquals(entries, back, "a layout changed on the way to storage and back");
    }

    @Test
    @DisplayName("a corrupt line is skipped, and the rest of the layout still loads")
    void corruptLineDoesNotLoseTheOthers() {
        // The file is not written by hand, so a bad line means something went
        // wrong elsewhere. Refusing the whole layout over one line would lose
        // the other five, which is a worse answer than losing the one.
        String text = "overlay.movingAverage|17,55|true\n"
                + "garbage\n"
                + "overlay.movingAverage|abc|true\n"
                + "overlay.movingAverage|9|false";

        List<ChartLayout.Entry> entries = ChartLayouts.parse(text);

        assertEquals(2, entries.size(), "the good lines should have survived");
        assertEquals(List.of(17, 55), entries.get(0).parameters());
        assertFalse(entries.get(1).visible());
    }

    @Test
    @DisplayName("an indicator that no longer exists is skipped, not fatal")
    void unknownKindIsSkipped() {
        // A layout saved by an older version can name an indicator that has since
        // been removed. It must not stop the chart from opening.
        ChartLayout layout = new ChartLayout("old", List.of(
                new ChartLayout.Entry("overlay.thatWentAway", List.of(5), true),
                new ChartLayout.Entry("overlay.movingAverage", List.of(20), true)));

        List<Overlay> built = layout.build();

        assertEquals(1, built.size(), "the surviving indicator should still be built");
        assertEquals(List.of(20), built.get(0).parameters());
    }

    @Test
    @DisplayName("capturing a chart and rebuilding it gives the same indicators")
    void captureAndRebuild() {
        ChartLayout captured = ChartLayout.of("mine", List.of(
                built("overlay.movingAverage", 17, 55),
                hidden("overlay.movingAverage", 200)));

        List<Overlay> rebuilt = captured.build();

        assertEquals(2, rebuilt.size(), "an indicator was lost in the round trip");
        assertTrue(rebuilt.get(0).isVisible(), "the visible one came back hidden");
        assertFalse(rebuilt.get(1).isVisible(), "the hidden one came back visible");
    }

    @Test
    @DisplayName("copying a copy stacks the prefix instead of colliding")
    void copyNamesDoNotCollide() {
        // Silly names, and honest ones: they record that the layout came from
        // another, which is what the reader needs when two charts look almost
        // alike.
        List<String> taken = new java.util.ArrayList<>(List.of("Clean"));
        String first = ChartLayouts.copyName(taken, "Clean");

        taken.add(first);

        String second = ChartLayouts.copyName(taken, "Clean");

        assertFalse(first.equals(second), "the second copy reused the first one's name");

        // WHAT IT IS, not "anything not already used". The second half of this
        // used to be `second.contains(first) || !taken.contains(second)` -- and
        // the right-hand side is true of every unused name there is, so a
        // copyName that answered a random string satisfied it.
        assertTrue(second.startsWith(first),
                "the second copy does not say which layout it came from: " + second);
        assertFalse(taken.contains(second), "the second copy took a name already in use");
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 5,
            threadMode = org.junit.jupiter.api.Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("nomear uma copia TERMINA, mesmo com dez copias e com a chave faltando")
    void naminganewCopyAlwaysEnds() {
        // The old loop's only exit was the candidate CHANGING, and it changed
        // because the bundle entry carries a {0}. Take that entry away --
        // Messages.get then answers "!layout.copyOf!" and MessageFormat over a
        // text with no placeholder gives back the same text -- and the candidate
        // stops moving. The loop never ends, on the interface thread, from the
        // duplicate button: a missing translation freezing the whole
        // application, which is the exact case Messages exists to make harmless.
        //
        // THE MISSING ENTRY IS HANDED IN, because it cannot be reached through
        // the bundle: the entry is on the classpath and every locale falls back
        // to it. A phrase that answers the same thing every time IS the missing
        // entry, exactly.
        //
        // A Timeout is half the assertion. There is no way to say "this returns"
        // except to bound the time it may take.
        List<String> taken = new java.util.ArrayList<>(List.of("Clean"));

        for (int i = 0; i < 10; i++) {
            String next = ChartLayouts.copyName(taken, "Clean", of -> "!layout.copyOf!");

            assertFalse(taken.contains(next), "the name given is already in use: " + next);

            taken.add(next);
        }

        assertEquals(11, taken.size(), "ten copies did not produce ten distinct names");

        // And the ordinary path still reads like a name.
        assertTrue(ChartLayouts.copyName(List.of(), "Clean").contains("Clean"),
                "the copy is no longer named after what it was copied from");
    }

    private static Overlay built(String key, int... periods) {
        return new ChartLayout.Entry(key, boxed(periods), true).build();
    }

    private static Overlay hidden(String key, int... periods) {
        return new ChartLayout.Entry(key, boxed(periods), false).build();
    }

    private static List<Integer> boxed(int... values) {
        List<Integer> list = new java.util.ArrayList<>(values.length);

        for (int value : values) {
            list.add(value);
        }

        return list;
    }

    @Test
    @DisplayName("um painel volta com os ajustes, a altura e o estado dele")
    void aPaneComesBackWhole() {
        br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic study =
                new br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic(21, 5);

        study.setShowsAverage(false);
        study.setBuyLevel(15);
        study.setSellLevel(85);
        study.setColour(new java.awt.Color(0x123456));

        ChartLayout.Pane stored = new ChartLayout.Pane(study.nameKey(), study.parameters(),
                study.appearance(), 140, true);

        String text = ChartLayouts.formatPanes(java.util.List.of(stored));
        java.util.List<ChartLayout.Pane> back = ChartLayouts.parsePanes(text);

        assertEquals(1, back.size());
        assertEquals(140, back.get(0).height());
        assertTrue(back.get(0).minimised());

        br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic rebuilt =
                (br.com.jorge.reis.endeavourneo.ui.chart.study.stochastic.SlowStochastic)
                        back.get(0).build().get(0);

        assertEquals(21, rebuilt.period());
        assertEquals(5, rebuilt.average());
        assertEquals(15.0, rebuilt.buyLevel());
        assertEquals(85.0, rebuilt.sellLevel());
        assertEquals(new java.awt.Color(0x123456), rebuilt.colour());
        assertFalse(rebuilt.showsAverage(), "the average came back on after being turned off");
    }

    @Test
    @DisplayName("um layout gravado antes dos paineis ainda carrega")
    void anOlderLayoutStillLoads() {
        // The whole reason the panes went in a key of their own.
        assertTrue(ChartLayouts.parsePanes("").isEmpty());
        assertTrue(ChartLayouts.parsePanes(null).isEmpty());
        assertTrue(ChartLayout.empty("qualquer").panes().isEmpty());
    }

    @Test
    @DisplayName("um indicador que esta versao nao tem e pulado, nao explode")
    void anUnknownPaneIsSkipped() {
        java.util.List<ChartLayout.Pane> panes = ChartLayouts.parsePanes(
                "0|study.doNotExist|9|100|false|");

        assertEquals(1, panes.size());
        assertTrue(panes.get(0).build().isEmpty());
        assertTrue(new ChartLayout("x", java.util.List.of(), panes).studies().isEmpty());
    }

    @Test
    @DisplayName("tres indicadores num painel voltam juntos, na ordem, e em dois paineis")
    void severalInOnePane() {
        ChartLayout.Pane first = new ChartLayout.Pane(java.util.List.of(
                new ChartLayout.Entry("study.stochastic", java.util.List.of(8, 3), true, ""),
                new ChartLayout.Entry("study.stochastic", java.util.List.of(21, 5), true, ""),
                new ChartLayout.Entry("study.stochastic", java.util.List.of(14, 3), true, "")),
                150, false);

        ChartLayout.Pane second = new ChartLayout.Pane(java.util.List.of(
                new ChartLayout.Entry("study.stochastic", java.util.List.of(9, 3), true, "")),
                90, true);

        java.util.List<ChartLayout.Pane> back = ChartLayouts.parsePanes(
                ChartLayouts.formatPanes(java.util.List.of(first, second)));

        assertEquals(2, back.size(), "the two panes came back as one, or as three");

        assertEquals(java.util.List.of(8, 21, 14),
                back.get(0).entries().stream().map(e -> e.parameters().get(0)).toList(),
                "the indicators of a pane came back in a different order");
        assertEquals(150, back.get(0).height());
        assertFalse(back.get(0).minimised());

        assertEquals(1, back.get(1).entries().size());
        assertEquals(90, back.get(1).height());
        assertTrue(back.get(1).minimised(), "the second pane lost the first one's flags");
    }
    @Test
    @DisplayName("a altura de um painel vem da PRIMEIRA linha dele, como o formato promete")
    void thepaneHeightComesFromItsFirstLine() {
        // The format's own javadoc says the height and the minimised flag "are
        // read from the first and the repetition costs nothing" -- and the
        // parser assigned them on every line, so the LAST one won. The writer
        // puts the same values on all of them, so the two agreed until somebody
        // edited the file by hand, which is the reason this format is plain
        // text at all.
        String written = "0|overlay.ema|9|140|false|\n"
                + "0|overlay.ema|21|999|true|";

        java.util.List<ChartLayout.Pane> panes = ChartLayouts.parsePanes(written);

        assertEquals(1, panes.size(), "the two lines did not land in one pane");
        assertEquals(140, panes.get(0).height(),
                "the height came from the last line of the pane, not the first");
        assertFalse(panes.get(0).minimised(),
                "the minimised flag came from the last line of the pane, not the first");
    }
    @Test
    @DisplayName("os records de layout tiram a propria copia da lista que recebem")
    void thelayoutRecordsCopyWhatTheyAreGiven() {
        // A record promises that what it holds does not change under it, and
        // these held the CALLER's list: whoever built one could go on adding to
        // what it was made of. Nobody did, and that is discipline rather than
        // type -- the same lesson Renko.Carry already learned about its mutable
        // tally.
        java.util.List<Integer> numbers = new java.util.ArrayList<>(java.util.List.of(9));
        ChartLayout.Entry entry = new ChartLayout.Entry("overlay.ema", numbers, true);

        numbers.add(21);

        assertEquals(java.util.List.of(9), entry.parameters(),
                "the entry is holding the caller's list and changed with it");

        java.util.List<ChartLayout.Entry> entries =
                new java.util.ArrayList<>(java.util.List.of(entry));
        ChartLayout.Pane pane = new ChartLayout.Pane(entries, 140, false);
        ChartLayout layout = new ChartLayout("Um", entries, java.util.List.of(pane));

        entries.clear();

        assertEquals(1, pane.entries().size(), "the pane emptied with the caller's list");
        assertEquals(1, layout.entries().size(), "the layout emptied with the caller's list");
    }

    @Test
    @DisplayName("a especie do catalogo tambem")
    void thecatalogueKindCopiesToo() {
        java.util.List<Integer> defaults = new java.util.ArrayList<>(java.util.List.of(9));
        OverlayCatalog.Kind kind = new OverlayCatalog.Kind("overlay.ema", defaults, 1, 2_000,
                numbers -> null);

        defaults.add(21);

        assertEquals(java.util.List.of(9), kind.defaults(),
                "the kind is holding the caller's list and changed with it");
    }
/**
     * An indicator with no parameters at all survives the round trip.
     *
     * <p>{@code format} writes {@code kind||true} for one, and the reader used
     * to answer "empty" for both "no numbers" and "a number I cannot read" —
     * then drop the entry for either. So an indicator without a number was
     * written correctly and lost on the way back in, in silence. The pane
     * reader, on the same field, kept it: the same layout survived one path and
     * died in the other.</p>
     *
     * <p>Nothing in the program has no parameters today; all four indicators
     * carry one. It was a trap set for the fifth.</p>
     */
    @Test
    @DisplayName("um indicador sem parametro sobrevive a ida e volta")
    void anindicatorWithNoParametersSurvives() {
        List<ChartLayout.Entry> entries = List.of(
                new ChartLayout.Entry("overlay.vwap", List.of(), true, ""),
                new ChartLayout.Entry("overlay.movingAverage", List.of(17), true, ""));

        List<ChartLayout.Entry> back = ChartLayouts.parse(ChartLayouts.format(entries));

        assertEquals(2, back.size(),
                "the indicator with no parameters vanished on the way back: " + back);
        assertEquals("overlay.vwap", back.get(0).kindKey());
        assertEquals(List.of(), back.get(0).parameters());
        assertEquals(List.of(17), back.get(1).parameters());
    }

    /**
     * And a number that will not read still takes its entry with it.
     *
     * <p>The other half of the same rule: empty is a valid answer, unreadable is
     * not. Building the indicator with its defaults would put a shape on the
     * chart the reader never chose.</p>
     */
    @Test
    @DisplayName("um numero ilegivel continua levando a entrada dele embora")
    void anunreadableNumberStillDropsItsEntry() {
        List<ChartLayout.Entry> back = ChartLayouts.parse(
                "overlay.movingAverage|abc|true\noverlay.movingAverage|55|true");

        assertEquals(1, back.size(), "the unreadable entry came through: " + back);
        assertEquals(List.of(55), back.get(0).parameters());
    }
/**
     * The built-in layout keeps its identity across a change of language.
     *
     * <p>A layout is identified by its NAME — in the list, in each chart's
     * selection, and in the file — and the built-in one is called
     * {@code Messages.get("layout.default")}. So changing the language renamed
     * it: the chart had "Padrão" saved as its selection while the list now
     * answered "Default", the search failed, and the chart came back silently on
     * the first tab with the reader's choice thrown away. A layout they had
     * captured kept a Portuguese name inside an English interface, for good.</p>
     *
     * <p>What goes into the file is a mark that is not a language.</p>
     */
    @Test
    @DisplayName("o layout padrao guarda uma marca, e nao o nome traduzido")
    void thedefaultLayoutIsStoredByAmarkAndNotByItsName() {
        java.util.Locale was = java.util.Locale.getDefault();

        try {
            br.com.jorge.reis.endeavourneo.platform.Messages.setLocale(
                    java.util.Locale.forLanguageTag("pt-BR"));

            String inPortuguese = ChartLayouts.defaultLayout().name();
            String written = ChartLayouts.stored(inPortuguese);

            assertNotEquals(inPortuguese, written,
                    "the translated name went into the file, so it stops matching the "
                            + "moment the language changes");

            br.com.jorge.reis.endeavourneo.platform.Messages.setLocale(
                    java.util.Locale.forLanguageTag("en"));

            String inEnglish = ChartLayouts.defaultLayout().name();

            assertNotEquals(inPortuguese, inEnglish, "the fixture is not testing anything: "
                    + "the two languages call the layout the same thing");

            assertEquals(inEnglish, ChartLayouts.restored(written),
                    "the layout saved in one language was not found in the other, so the "
                            + "chart came back on whichever tab happened to be first");

            // And an ordinary name travels untouched, or the mark would swallow
            // the reader's own layouts.
            assertEquals("Meu estudo", ChartLayouts.restored(ChartLayouts.stored("Meu estudo")));
        } finally {
            br.com.jorge.reis.endeavourneo.platform.Messages.setLocale(was);
        }
    }
}
