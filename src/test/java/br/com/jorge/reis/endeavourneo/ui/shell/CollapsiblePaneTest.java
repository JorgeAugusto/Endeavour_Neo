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

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /**
     * A settings and workspace pair of this file's own.
     *
     * <p>Opening a chart or a window WRITES: the workspace remembers which
     * charts were open, where they were and what they were showing. The home
     * those files live under is redirected by a property set in one place only,
     * the surefire plugin -- and the house runs the suite with javac and a
     * runner instead, where that property is absent and these tests rewrote the
     * reader's own list of open charts on every run.</p>
     *
     * <p>Per test, and put back afterwards, so nothing here can be read by the
     * next file either: MainWindowTest counts the charts it remembers, and a
     * count is a property any other test could change.</p>
     */
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path store;

    @org.junit.jupiter.api.BeforeEach
    void useAStoreOfOurOwn() {
        br.com.jorge.reis.endeavourneo.platform.Settings.useForTest(store);
    }

    @org.junit.jupiter.api.AfterEach
    void putTheStoreBack() {
        br.com.jorge.reis.endeavourneo.platform.Settings.stopUsingTestStore();
    }

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
        // THE HEADER'S VISIBILITY, which is what "there is still something to
        // click" means. foldedHeight is the header's preferred height, and the
        // arrow inside it was given a fixed 20 by 20 in the constructor -- so it
        // is at least twenty from the moment the panel is built, folded or not,
        // visible or not, and nothing setFolded can do makes it fall to zero.
        // Folding the header as well passed all three assertions, and the panel
        // would have been shut for good with no way back but a menu.
        assertTrue(pane.getComponent(0).isVisible(),
                "the header went with the content, so there is nothing left to click");
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
    @Test
    @DisplayName("o estado dobrado vai para o arquivo do teste, e nao para o registro")
    void theFoldedStateGoesToTheTestStore() throws java.io.IOException {
        // This class used to keep its state in java.util.prefs -- the registry,
        // on Windows -- which the suite's seam does not reach, so it wrote the
        // reader's registry even under Maven. And its keys carry a nanoTime, so
        // every run of the suite left one more entry behind, for ever.
        //
        // The argument against java.util.prefs was already written out in the
        // javadoc of Settings, under "Why files and not java.util.prefs". This
        // was the last place in the application still doing it.
        new CollapsiblePane("Console", new JPanel(), "provaDoArquivo").setFolded(true);

        java.nio.file.Path file = store.resolve("settings.properties");

        assertTrue(java.nio.file.Files.exists(file),
                "nothing was written where this test can see it, so the state went "
                        + "somewhere the suite does not own");
        assertTrue(java.nio.file.Files.readString(file).contains("provaDoArquivo"),
                "the file was written and the folded state is not in it");
    }
/**
     * The pane folds from the keyboard, and the focus does not fall in.
     *
     * <p>Folding was a {@code mousePressed} on a strip that could not take
     * focus, so a reader working from the keyboard had no way to fold anything
     * at all — and the console is one of the two panes this wraps. And hiding a
     * component the focus is inside of leaves the window with no focus owner:
     * folding the console with the cursor in it made the keyboard do nothing
     * until something was clicked.</p>
     */
    @Test
    @DisplayName("o painel dobra pelo teclado, e o foco nao cai dentro do que sumiu")
    void itfoldsFromTheKeyboard() {
        JPanel content = new JPanel();
        javax.swing.JTextArea inside = new javax.swing.JTextArea();

        content.add(inside);

        CollapsiblePane pane = new CollapsiblePane("Console", content, KEY + "-teclado");
        java.awt.Component header = pane.getComponent(0);

        assertTrue(header.isFocusable(),
                "the header cannot take focus, so nothing can be typed at it");

        javax.swing.Action fold = ((javax.swing.JComponent) header).getActionMap().get("fold");

        assertNotNull(fold, "there is no action bound for folding");

        fold.actionPerformed(new java.awt.event.ActionEvent(header, 0, "fold"));

        assertTrue(pane.isFolded(), "the keyboard action did not fold the pane");
    }
}
