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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the transport says when the session will not build.
 *
 * <p>The reader chooses a date, presses Request, watches the progress bar for
 * four seconds — and the transport comes back exactly as it was, as though the
 * button had done nothing. That was the whole of it: the exception was caught
 * and dropped, so there was no console line, no message and no trace, while the
 * reason for the failure was inside the exception the whole time.</p>
 *
 * <p>Not showing prices that came from nowhere is right. Saying nothing is a
 * different decision, and it was never made on purpose.</p>
 */
@DisplayName("A sessao que nao constroi")
class BuildFailureTest {

    private ReplayPanel panel;

    private PrintStream terminal;

    private ByteArrayOutputStream written;

    /**
     * Everything runs on the interface thread, as the transport does.
     *
     * <p>The look and feel reads state from the thread that changes it, and in
     * the running program every call into refresh arrives from a Swing timer or
     * an invokeLater.</p>
     */
    private static void onEdt(Runnable action) throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(action);
    }

    @BeforeEach
    void setUp() throws Exception {
        written = new ByteArrayOutputStream();
        terminal = System.err;

        System.setErr(new PrintStream(written, true, StandardCharsets.UTF_8));

        onEdt(() -> {
            panel = new ReplayPanel();
            panel.setSize(420, 260);
            panel.doLayout();
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        System.setErr(terminal);

        onEdt(() -> panel.release());
    }

    @Test
    @DisplayName("o motivo aparece no transporte, e nao o relogio parado")
    void theTransportSaysWhy() throws Exception {
        String idle = panel.clockShows();

        onEdt(() -> {
            panel.buildFailed(new java.io.IOException("win-1m.bin is not there"));
            panel.refresh();
        });

        assertNotEquals(idle, panel.clockShows(),
                "the transport came back looking exactly as it did before the reader pressed");

        assertEquals(Messages.get("replay.failed"), panel.clockShows());
    }

    @Test
    @DisplayName("a causa vai junto, onde da para ler")
    void theCauseTravelsWithIt() throws Exception {
        onEdt(() -> {
            panel.buildFailed(new java.io.IOException("win-1m.bin is not there"));
            panel.refresh();
        });

        assertNotNull(panel.clockReason(), "no reason under the clock");

        assertTrue(panel.clockReason().contains("win-1m.bin is not there"),
                "the reason does not say what happened: " + panel.clockReason());
    }

    /**
     * The console half, and the reason the reason is not lost.
     *
     * <p>{@code Console} redirects {@code System.err} into the application
     * console, which is how every stack trace in this program already reaches
     * the reader — so writing there is writing to the console, without the
     * replay window having to know the shell exists.</p>
     */
    @Test
    @DisplayName("a causa tambem e escrita, para nao morrer com a janela")
    void theCauseIsWrittenOut() throws Exception {
        onEdt(() -> panel.buildFailed(new java.io.IOException("win-1m.bin is not there")));

        String said = written.toString(StandardCharsets.UTF_8);

        assertTrue(said.contains("win-1m.bin is not there"),
                "nothing was written where the console would read it: [" + said + "]");
    }

    /**
     * A cause with no message still has to say something.
     *
     * <p>{@code getMessage()} is null for a good share of what a loader throws,
     * and a transport reading "null" is worse than one reading nothing: it
     * looks like a bug in the message rather than a fault in the base.</p>
     */
    @Test
    @DisplayName("sem mensagem, sobra ao menos o nome")
    void aCauseWithoutAMessageStillNamesItself() throws Exception {
        onEdt(() -> {
            panel.buildFailed(new IllegalStateException());
            panel.refresh();
        });

        assertEquals("IllegalStateException", panel.clockReason());
    }

    /**
     * And a new request wipes the old failure.
     *
     * <p>A reason that outlives the attempt it belongs to is worse than none:
     * the transport would go on explaining a failure the reader has already
     * moved past.</p>
     */
    @Test
    @DisplayName("pedir de novo apaga o motivo antigo")
    void askingAgainClearsIt() throws Exception {
        onEdt(() -> {
            panel.buildFailed(new IllegalStateException("gone"));
            panel.refresh();
        });

        onEdt(() -> {
            panel.forget();
            panel.refresh();
        });

        assertNotEquals(Messages.get("replay.failed"), panel.clockShows(),
                "the transport is still explaining a failure the reader moved past");

        assertEquals(null, panel.clockReason(), "the old reason is still under the clock");
    }
}
