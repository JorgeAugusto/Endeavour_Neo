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
        assertTrue(second.contains(first) || !taken.contains(second),
                "the second copy has to be distinguishable from the first");
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
}
