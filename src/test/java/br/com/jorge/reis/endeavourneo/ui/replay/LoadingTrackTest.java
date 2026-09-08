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
package br.com.jorge.reis.endeavourneo.ui.replay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import javax.swing.JProgressBar;
import javax.swing.JSlider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What sits in the scrubber's row, and when.
 *
 * <p>A slider whose handle is greyed and parked at nought says the program is
 * stuck. While a session is being read there is nothing to scrub and four
 * seconds of that picture is how an application teaches people to press the
 * button again — so a moving bar takes its place, which at least says the one
 * thing that is true.</p>
 */
@DisplayName("A pista do replay enquanto carrega")
class LoadingTrackTest {

    private ReplayPanel panel;

    /**
     * Everything here runs on the interface thread, as the transport does.
     *
     * <p>Not politeness: the look and feel's progress bar reads state from the
     * thread that changes it, and building this panel off the interface thread
     * makes it log a warning about an exception it may throw later while
     * painting. In the running program every call into refresh arrives from a
     * Swing timer or an invokeLater, so a test that called it from anywhere
     * else would be testing a situation that does not exist.</p>
     */
    private static void onEdt(Runnable action) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(action);
    }

    @BeforeEach
    void setUp() throws Exception {
        onEdt(() -> {
            panel = new ReplayPanel();
            panel.setSize(420, 260);
            panel.doLayout();
        });
    }

    /** @return the first component of a kind anywhere inside, or null */
    private static <T extends Component> T find(Container in, Class<T> kind) {
        for (Component each : in.getComponents()) {
            if (kind.isInstance(each)) {
                return kind.cast(each);
            }

            if (each instanceof Container inner) {
                T found = find(inner, kind);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private JSlider scrubber() {
        return find(panel, JSlider.class);
    }

    private JProgressBar loading() {
        return find(panel, JProgressBar.class);
    }

    /**
     * @return whether a component would actually be drawn
     *
     * <p>Its own visibility is not enough: the card layout hides the CARD, and
     * the bar sits inside a holder that keeps it at its own height. A test
     * asking only about the bar would pass while the bar was inside something
     * invisible.</p>
     */
    private boolean onScreen(Component of) {
        for (Component each = of; each != null && each != panel; each = each.getParent()) {
            if (!each.isVisible()) {
                return false;
            }
        }

        return true;
    }

    @Test
    @DisplayName("os dois existem, no mesmo lugar")
    void bothExistInOneSlot() throws Exception {
        assertNotNull(scrubber());
        assertNotNull(loading());

        // The same slot, not two stacked. What that has to buy is that the
        // transport does not change SHAPE when a session is asked for -- the
        // buttons must not move under the reader's pointer -- so that is what
        // is asserted, rather than which container each one happens to be in.
        int idle = panel.getPreferredSize().height;

        onEdt(() -> panel.showLoading(true));

        assertEquals(idle, panel.getPreferredSize().height,
                "the transport grew or shrank when it started loading");
    }

    @Test
    @DisplayName("sem sessao, quem aparece e o scrubber")
    void idleShowsTheScrubber() {
        assertTrue(onScreen(scrubber()));
        assertFalse(onScreen(loading()));
    }

    @Test
    @DisplayName("carregando, a barra toma o lugar dele")
    void loadingTakesTheSlot() throws Exception {
        onEdt(() -> panel.showLoading(true));

        assertTrue(onScreen(loading()));
        assertFalse(onScreen(scrubber()), "both were on screen at once");
        assertTrue(loading().isIndeterminate(),
                "a bar at zero says hung, which is what this replaced");
    }

    @Test
    @DisplayName("e devolve quando termina")
    void andGivesItBack() throws Exception {
        onEdt(() -> panel.showLoading(true));
        onEdt(() -> panel.showLoading(false));

        assertTrue(onScreen(scrubber()));
        assertFalse(onScreen(loading()));
    }

    @Test
    @DisplayName("escondida, a barra para de se animar")
    void hiddenItStopsAnimating() throws Exception {
        onEdt(() -> panel.showLoading(true));
        onEdt(() -> panel.showLoading(false));

        // An indeterminate bar repaints on a timer whether or not anybody can
        // see it, and a transport sitting idle all afternoon would be spending
        // one on a picture of nothing.
        assertFalse(loading().isIndeterminate());
    }
/**
     * The clock stops pushing the handle while the reader is holding it.
     *
     * <p>Every frame wrote the handle's position twenty-five times a second, and
     * a reader dragging it was writing the same handle from the other side: it
     * shook, escaped the pointer, and a drag to two in the afternoon snapped
     * back to nine in the morning. The flag that was there guarded the other
     * direction -- a programmatic move being read back as a seek -- and the
     * handle moved all the same.</p>
     */
    @Test
    @DisplayName("a alca nao e empurrada enquanto o leitor a segura")
    void theHandleIsLeftAloneWhileDragged() throws Exception {
        onEdt(() -> panel.showProgress(0.10));

        assertEquals(100, panel.handleAt(), "the handle did not follow the clock at all");

        onEdt(() -> {
            scrubber().setValueIsAdjusting(true);
            panel.showProgress(0.90);
        });

        assertEquals(100, panel.handleAt(),
                "the clock moved the handle out from under the pointer");

        // And the moment it is let go, the clock has it again -- or the fix
        // above is a handle that stops following the replay.
        onEdt(() -> {
            scrubber().setValueIsAdjusting(false);
            panel.showProgress(0.90);
        });

        assertEquals(900, panel.handleAt(), "released, the handle stopped following the clock");
    }
/**
     * The play button keeps the icon it has instead of getting a new one each frame.
     *
     * <p>{@code refresh} runs from every announce — twenty-five times a second —
     * and it used to build an icon on each pass and hand it to {@code setIcon},
     * which fires a property change, a revalidate and a repaint on the button
     * whether or not the icon is the same one it had for the last twenty frames.
     * Each painting of an icon then allocates a stroke and four arrays of its
     * own. There are two icons and they never change.</p>
     */
    @Test
    @DisplayName("o botao de play nao ganha um icone novo a cada quadro")
    void theplayButtonKeepsTheIconItHas() throws Exception {
        onEdt(() -> panel.refresh());

        javax.swing.Icon first = panel.playIcon();

        assertNotNull(first, "the play button has no icon at all");

        onEdt(() -> panel.refresh());
        onEdt(() -> panel.refresh());

        assertSame(first, panel.playIcon(),
                "a new icon was built and set on every refresh, and the button repainted "
                        + "itself for each of them");
    }
}
