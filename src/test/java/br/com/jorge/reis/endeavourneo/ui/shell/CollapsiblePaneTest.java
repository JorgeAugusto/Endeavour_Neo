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
package br.com.jorge.reis.endeavourneo.ui.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.swing.JLabel;
import javax.swing.JPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A panel that folds away, leaving its caption.
 */
@DisplayName("Collapsible pane")
class CollapsiblePaneTest {

    private static final String KEY = "test-pane-" + System.nanoTime();

    @Test
    @DisplayName("dobrado, o conteudo some e a legenda fica")
    void foldingHidesTheContentAndKeepsTheCaption() {
        // Folded, not closed. A panel that vanished entirely would need a menu
        // to come back, and the reader who folded the log to see one more
        // candle would have to remember where that menu was.
        JPanel content = new JPanel();

        content.add(new JLabel("conteudo"));

        CollapsiblePane pane = new CollapsiblePane("Console", content, KEY);

        assertFalse(pane.isFolded(), "nasceu dobrado");
        assertTrue(content.isVisible());

        pane.setFolded(true);

        assertTrue(pane.isFolded());
        assertFalse(content.isVisible(), "o conteudo continua visivel");
        assertTrue(pane.foldedHeight() > 0,
                "dobrado ele nao mede nada, entao nao sobra legenda pra clicar");

        pane.setFolded(false);

        assertTrue(content.isVisible(), "desdobrar nao trouxe o conteudo de volta");
    }

    @Test
    @DisplayName("avisa quem o colocou num divisor, e so quando muda")
    void itReportsEveryChangeAndOnlyChanges() {
        // It does not move itself: whoever put it in a split pane moves the
        // divider. A component that resized its own container would work in
        // exactly the arrangement it was written for and quietly not in
        // any other.
        CollapsiblePane pane = new CollapsiblePane("Console", new JPanel(), KEY + "-b");
        int[] told = {0};

        pane.onToggle(() -> told[0]++);

        pane.setFolded(true);
        pane.setFolded(true);

        assertEquals(1, told[0], "folding twice reported twice");

        pane.setFolded(false);

        assertEquals(2, told[0]);
    }

    @Test
    @DisplayName("lembra entre um arranque e outro")
    void itRemembers() {
        String key = KEY + "-c";

        new CollapsiblePane("Console", new JPanel(), key).setFolded(true);

        assertTrue(new CollapsiblePane("Console", new JPanel(), key).isFolded(),
                "reabriu desdobrado depois de ter sido dobrado");
    }
}
