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

import br.com.jorge.reis.endeavourneo.domain.market.RandomWalkSeries;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.domain.market.Segment;
import br.com.jorge.reis.endeavourneo.platform.Segmentation;

import java.awt.Component;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The segment chooser in the chart's own header.
 *
 * <p>It is the second half of an answer the tree gives first: the tree says
 * which segments exist, and this says which one you are looking at and lets you
 * change it without going back there. The two have to agree, and the one thing
 * that makes them agree at a glance is the colour.</p>
 */
@DisplayName("Seletor de segmento no cabecalho")
class SegmentChipTest {

    private static final String SERIES = "winfull-1m";

    @TempDir
    private Path store;

    private ChartCanvas canvas;

    @BeforeEach
    void setUp() {
        // Its own store, or this reads the segments of whoever is running the
        // suite -- and writes over them.
        Segmentation.useForTest(store.resolve("segments.properties"));
        Segmentation.set(SERIES, List.of(
                new Segment("Estudos", LocalDate.of(2020, 9, 1), LocalDate.of(2024, 12, 31)),
                new Segment("Testes", LocalDate.of(2025, 1, 2), LocalDate.of(2026, 8, 31))));

        canvas = new ChartCanvas();
        canvas.setSeries(new RandomWalkSeries(200, 100.0));
    }

    @AfterEach
    void tearDown() {
        Segmentation.stopUsingTestStore();
    }

    private ChartHeader headerOn(String key) {
        return new ChartHeader(canvas, "WINFUT-FULL", key);
    }

    /** @return the text of every item, with a mark on the ones that are disabled */
    private static List<String> itemsOf(JPopupMenu menu) {
        List<String> found = new ArrayList<>();

        for (Component each : menu.getComponents()) {
            if (each instanceof JMenuItem item) {
                found.add(item.isEnabled() ? item.getText() : "[" + item.getText() + "]");
            }
        }

        return found;
    }

    @Test
    @DisplayName("serie sem segmentos nao ganha chip nenhum")
    void aSeriesWithNoSegments() {
        assertTrue(headerOn("win-5m").offered().isEmpty(),
                "a control that is permanently empty is one the reader learns to skip");
    }

    @Test
    @DisplayName("com replay tocando, o chip some")
    void whileAReplayIsPlaying() {
        // No hedge on MARK being empty: it is a constant, and it is not.
        // A branch neither side of the test can reach reads as though the
        // separator were configurable.
        ChartHeader header = headerOn(SERIES + Segmentation.MARK + "Estudos");

        assertFalse(header.offered().isEmpty());

        header.showing("win/ticks/profit");

        // What is playing is not this series, so offering to switch a segment
        // of it would be offering to leave without saying so.
        assertTrue(header.offered().isEmpty());
    }

    /** @return the whole-series line, as the bundle spells it */
    private static String whole() {
        return br.com.jorge.reis.endeavourneo.platform.Messages.get("chart.wholeSeries");
    }

    @Test
    @DisplayName("o menu lista a serie toda e os segmentos, com o atual desligado")
    void theMenuListsEverything() {
        ChartHeader header = headerOn(SERIES + Segmentation.MARK + "Estudos");

        // The whole-series line comes from the bundle. Written out here it
        // pinned the language the suite happens to have loaded, and the base
        // language of the bundle is English.
        assertEquals(List.of(whole(), "[Estudos]", "Testes"), itemsOf(header.menuFor()),
                "the list did not name all three, or marked the wrong one as current");
    }

    @Test
    @DisplayName("na serie toda, e ela que fica desligada")
    void onTheWholeSeries() {
        assertEquals(List.of("[" + whole() + "]", "Estudos", "Testes"),
                itemsOf(headerOn(SERIES).menuFor()));
    }

    @Test
    @DisplayName("serie trancada nao oferece a si mesma inteira")
    void aLockedSeries() {
        Segmentation.setSegmentsOnly(SERIES, true);

        // A menu that listed it and then refused would be teaching the reader
        // that the lock is advisory.
        assertEquals(List.of("[Estudos]", "Testes"),
                itemsOf(headerOn(SERIES + Segmentation.MARK + "Estudos").menuFor()));
    }

    @Test
    @DisplayName("escolher um pede a abertura pelo nome que a arvore usaria")
    void choosingOneAsksForIt() {
        ChartHeader header = headerOn(SERIES + Segmentation.MARK + "Estudos");
        List<String> asked = new ArrayList<>();

        header.onOpen(asked::add);

        JPopupMenu menu = header.menuFor();

        for (Component each : menu.getComponents()) {
            if (each instanceof JMenuItem item && "Testes".equals(item.getText())) {
                item.doClick();
            }
        }

        // The same string the tree hands over, so a chart opened here and one
        // opened there are the same chart rather than two of the same thing.
        assertEquals(List.of(SERIES + Segmentation.MARK + "Testes"), asked);
    }

    @Test
    @DisplayName("cada segmento leva a cor da posicao dele, e nao a do nome")
    void theColourFollowsThePosition() {
        JPopupMenu menu = headerOn(SERIES).menuFor();
        List<java.awt.Color> dots = new ArrayList<>();

        for (Component each : menu.getComponents()) {
            if (each instanceof JMenuItem item && item.getIcon() != null) {
                dots.add(colourOf(item));
            }
        }

        // Three items, and the two segments wear the first two colours of the
        // series window's palette. Hashing the name instead would give the same
        // segment two colours in two windows.
        assertEquals(3, dots.size());
        assertEquals(br.com.jorge.reis.endeavourneo.ui.series.SeriesColors.taken(0), dots.get(1));
        assertEquals(br.com.jorge.reis.endeavourneo.ui.series.SeriesColors.taken(1), dots.get(2));
    }

    /** @return the colour an item's dot is painted in */
    private static java.awt.Color colourOf(JMenuItem item) {
        java.awt.image.BufferedImage image =
                new java.awt.image.BufferedImage(8, 8,
                        java.awt.image.BufferedImage.TYPE_INT_ARGB);

        item.getIcon().paintIcon(item, image.getGraphics(), 0, 0);

        return new java.awt.Color(image.getRGB(4, 4), true);
    }
}
