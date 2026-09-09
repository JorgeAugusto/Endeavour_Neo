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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import br.com.jorge.reis.endeavourneo.platform.Settings;

import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O segundo replay, que não acontecia.
 *
 * <p>Relatado assim: <i>"ao parar o replay e mudar a série ele fica carregando
 * indefinidamente... e não completa, ou seja só funciona no primeiro replay; ao
 * fechar a janela também não está parando, é pra interromper tudo; e quando
 * abrir novamente a janela do replay voltar ao estado inicial pra escolher e
 * dar play"</i>. Três sintomas e duas causas.</p>
 *
 * <p><b>A primeira:</b> o botão de parar chamava {@code release()}, e {@code
 * release} levanta a bandeira que diz <i>"este painel foi embora e não volta"</i>
 * — a que o {@code done()} do worker lê para descartar uma sessão que chegou
 * tarde demais. Parar uma vez envenenava o transporte para o resto da vida
 * dele: a sessão seguinte era construída, descartada no instante em que
 * chegava, e o {@code return} daquele ramo pulava o {@code refresh}, deixando a
 * barra de carregamento na tela para sempre.</p>
 *
 * <p><b>A segunda:</b> a janela era {@code HIDE_ON_CLOSE} e a liberação estava
 * pendurada em {@code windowClosed}, que o X nunca dispara nesse modo. Fechar
 * escondia o transporte e deixava tudo rodando.</p>
 */
@DisplayName("O segundo replay")
class SecondReplayTest {

    private static final LocalDate DAY = LocalDate.of(2021, 1, 4);

    private static void onEdt(Runnable action) throws Exception {
        SwingUtilities.invokeAndWait(action);
    }

    /** @return the first component of a kind anywhere inside, or null */
    private static <T extends Component> T find(Container in, Class<T> kind, String tip) {
        for (Component each : in.getComponents()) {
            if (kind.isInstance(each)
                    && (tip == null || tip.equals(nameOf(each)))) {

                return kind.cast(each);
            }

            if (each instanceof Container inner) {
                T found = find(inner, kind, tip);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /** @return the tooltip or the text, whichever the control carries */
    private static String nameOf(Component each) {
        if (each instanceof javax.swing.AbstractButton button) {
            return button.getToolTipText() == null
                    ? button.getText() : button.getToolTipText();
        }

        return null;
    }

    private static javax.swing.JButton button(Container in, String key) {
        return find(in, javax.swing.JButton.class,
                br.com.jorge.reis.endeavourneo.platform.Messages.get(key));
    }

    /**
     * Deixa a fila de eventos girar até a barra de carregamento sumir.
     *
     * @return whether it went away inside the deadline
     */
    private static boolean settled(ReplayPanel panel) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

        while (System.nanoTime() < deadline) {
            AtomicReference<Boolean> busy = new AtomicReference<>();

            onEdt(() -> {
                JProgressBar bar = find(panel, JProgressBar.class, null);

                busy.set(bar != null && bar.isIndeterminate());
            });

            if (!busy.get()) {
                return true;
            }

            Thread.sleep(25L);
        }

        return false;
    }

    /** @return what the drag handle is calling itself */
    private static String chipSays(ReplayPanel panel) throws Exception {
        AtomicReference<String> said = new AtomicReference<>();

        onEdt(() -> said.set(panel.handleSays()));

        return said.get();
    }

    @Test
    @DisplayName("parar e pedir outro dia carrega o segundo, e nao para sempre")
    void stoppingDoesNotPoisonTheTransport(@TempDir Path folder) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        ReplayBase.at(folder.resolve("data"), DAY);

        Settings.workspace().put("replay.from", DAY.toString());
        Settings.workspace().put("replay.to", DAY.toString());

        AtomicReference<ReplayPanel> made = new AtomicReference<>();

        try {
            onEdt(() -> {
                ReplayPanel panel = new ReplayPanel();

                panel.setSize(460, 280);
                panel.doLayout();

                made.set(panel);
            });

            ReplayPanel panel = made.get();

            // O PRIMEIRO, que sempre funcionou.
            onEdt(() -> button(panel, "replay.request").doClick());

            assertTrue(settled(panel), "the FIRST session never finished loading");

            String nothing = br.com.jorge.reis.endeavourneo.platform.Messages
                    .get("replay.noSession");

            assertNotEquals(nothing, chipSays(panel), "the first session never arrived either");

            // PARAR. Era isto que envenenava o painel.
            onEdt(() -> button(panel, "replay.stop").doClick());

            assertEquals(nothing, chipSays(panel), "stop did not end the session");

            // E O SEGUNDO, que é o que ele relatou não acontecer.
            onEdt(() -> button(panel, "replay.request").doClick());

            assertTrue(settled(panel),
                    "the transport stayed on the loading bar for ever after stop: the branch "
                            + "that discards a session arriving at a panel that is gone "
                            + "returned before the refresh that takes the bar down");

            // E A SESSAO CHEGOU. Sem esta, a prova de dentes sai verde: a
            // barra desce assim que o refresh volta a ser chamado, e a sessao
            // segue sendo descartada na chegada. O sintoma que ele relatou e a
            // barra; o defeito e o segundo replay nao acontecer.
            assertNotEquals(nothing, chipSays(panel),
                    "the second session was built and thrown away on arrival: the stop button "
                            + "had raised the panel-is-gone flag, which is never lowered, so "
                            + "only the FIRST replay of the whole run ever worked");
        } finally {
            onEdt(() -> made.get().release());

            ReplayBase.release();
        }
    }

    @Test
    @DisplayName("fechar no X interrompe tudo e a janela some")
    void closingWithTheCrossEndsEverything(@TempDir Path folder) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        ReplayBase.at(folder.resolve("data"), DAY);

        AtomicReference<ReplayWindow> made = new AtomicReference<>();

        try {
            onEdt(() -> made.set(new ReplayWindow(null)));

            ReplayWindow window = made.get();

            assertTrue(window.isDisplayable(), "the window was never realised");

            onEdt(() -> window.dispatchEvent(new java.awt.event.WindowEvent(
                    window, java.awt.event.WindowEvent.WINDOW_CLOSING)));

            // Uma volta na fila: o dispose posta windowClosed.
            onEdt(() -> { });

            assertFalse(window.isDisplayable(),
                    "the X only hid the transport. With HIDE_ON_CLOSE the release is hung on "
                            + "windowClosed, which never fires -- so the session's timer went "
                            + "on walking the market, the tick files stayed open, and the "
                            + "charts stayed frozen on a replay nobody could reach");
        } finally {
            ReplayBase.release();
        }
    }

    /** @return the menu item with that label, anywhere in the bar */
    private static javax.swing.JMenuItem menuItem(javax.swing.JMenuBar bar, String key) {
        String label = br.com.jorge.reis.endeavourneo.platform.Messages.get(key);

        for (int menu = 0; menu < bar.getMenuCount(); menu++) {
            javax.swing.JMenu each = bar.getMenu(menu);

            for (int item = 0; item < each.getItemCount(); item++) {
                javax.swing.JMenuItem found = each.getItem(item);

                if (found != null && label.equals(found.getText())) {
                    return found;
                }
            }
        }

        return null;
    }

    /** @return the replay transport currently on screen, or null */
    private static ReplayWindow openTransport() {
        for (java.awt.Window each : java.awt.Window.getWindows()) {
            if (each instanceof ReplayWindow found && found.isDisplayable()) {
                return found;
            }
        }

        return null;
    }

    @Test
    @DisplayName("reabrir o replay da uma janela nova, no estado inicial")
    void reopeningStartsOver(@TempDir Path folder) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no graphics environment");

        ReplayBase.at(folder.resolve("data"), DAY);

        AtomicReference<br.com.jorge.reis.endeavourneo.ui.shell.MainWindow> shell =
                new AtomicReference<>();

        try (br.com.jorge.reis.endeavourneo.platform.JobService jobs =
                new br.com.jorge.reis.endeavourneo.platform.JobService()) {

            onEdt(() -> shell.set(
                    new br.com.jorge.reis.endeavourneo.ui.shell.MainWindow("test", jobs)));
            onEdt(() -> { });

            try {
                onEdt(() -> menuItem(shell.get().getJMenuBar(), "action.replay").doClick());

                ReplayWindow one = openTransport();

                assertNotNull(one, "the Replay menu opened no transport");

                onEdt(() -> one.dispatchEvent(new java.awt.event.WindowEvent(
                        one, java.awt.event.WindowEvent.WINDOW_CLOSING)));
                onEdt(() -> { });

                onEdt(() -> menuItem(shell.get().getJMenuBar(), "action.replay").doClick());

                ReplayWindow two = openTransport();

                assertNotNull(two, "reopening opened nothing");

                assertNotSame(one, two,
                        "the shell handed back the window it had closed. A disposed frame "
                                + "shown again comes back with its components, its listeners "
                                + "and whatever the reader had typed -- the opposite of what "
                                + "closing the transport is supposed to mean");
            } finally {
                onEdt(() -> shell.get().dispose());
            }
        } finally {
            ReplayBase.release();
        }
    }
}
