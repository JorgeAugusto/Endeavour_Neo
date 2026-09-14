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
package br.com.jorge.reis.endeavourneo.tools;

import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.Appearance;
import br.com.jorge.reis.endeavourneo.platform.JobService;
import br.com.jorge.reis.endeavourneo.platform.Language;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.SeriesCatalog;
import br.com.jorge.reis.endeavourneo.platform.Theme;
import br.com.jorge.reis.endeavourneo.ui.backtest.BacktestHolder;
import br.com.jorge.reis.endeavourneo.ui.chart.ChartCanvas;
import br.com.jorge.reis.endeavourneo.ui.chart.Overlay;
import br.com.jorge.reis.endeavourneo.ui.chart.OverlayCatalog;
import br.com.jorge.reis.endeavourneo.ui.chart.study.StudyStack;
import br.com.jorge.reis.endeavourneo.ui.replay.ReplayWindow;
import br.com.jorge.reis.endeavourneo.ui.series.SeriesWindow;
import br.com.jorge.reis.endeavourneo.ui.shell.CollapsiblePane;
import br.com.jorge.reis.endeavourneo.ui.shell.MainWindow;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JInternalFrame;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * Takes the pictures of the application that the README shows.
 *
 * <p>Run it, and {@code docs/img} fills with one PNG per screen: the chart
 * dressed with indicators, the backtest before and after a run, the replay, and
 * the series window. Nobody has to remember which windows to open, which
 * indicators to insert or how large to make anything — so the pictures can be
 * REMADE when a screen changes, instead of quietly ageing into a lie about what
 * the program looks like. That is the whole reason this is a file and not a
 * session of somebody clicking.
 *
 * <h2>It is not a test, and lives here anyway</h2>
 *
 * <p>It has no assertions and proves nothing. It is here because it is
 * development tooling rather than product: shipping a screenshot harness inside
 * the application would put Swing-driving code in the jar that every user
 * downloads, for a job only the repository has. Surefire does not pick it up —
 * no {@code @Test}, and the name does not match what the plugin includes.
 *
 * <h2>Run it like this</h2>
 *
 * <pre>{@code
 * mvn -o test-compile
 * java -Dendeavourneo.home=<a throwaway folder> \
 *      -cp "target/classes;target/test-classes" \
 *      br.com.jorge.reis.endeavourneo.tools.Prints
 * }</pre>
 *
 * <p><b>Point {@code endeavourneo.home} somewhere disposable.</b> Without it
 * this writes to the real settings folder, and opening and closing charts
 * REPLACES the remembered workspace — so running it would rearrange the windows
 * of whoever ran it. A screenshot tool that damages the thing it photographs is
 * not one anybody runs twice.
 *
 * <h2>What the pauses are for</h2>
 *
 * <p>Between opening something and photographing it there is a sleep, and the
 * sleeps are generous. A chart reads a forty-megabyte series off disk, folds it
 * and lays out an axis; a study stack recalculates three indicators over every
 * bar. None of that finishes by the time the call that started it returns, and a
 * picture taken too early shows an empty frame — which looks exactly like a
 * broken program in a README.
 */
public final class Prints {

    private static final int WIDTH = 1_680;

    private static final int HEIGHT = 980;

    /** How long a backtest may take before the picture is taken anyway. */
    private static final int PATIENCE = 240;

    private static final Path OUT = Path.of("docs", "img");

    private Prints() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    public static void main(String[] args) throws Exception {
        Timeframe.useZone(ZoneId.of("America/Sao_Paulo"));
        Appearance.install(Theme.DARK);

        Files.createDirectories(OUT);

        JobService jobs = new JobService();
        MainWindow[] held = new MainWindow[1];

        SwingUtilities.invokeAndWait(() -> {
            Language.install();

            MainWindow made = new MainWindow(Messages.get("app.title"), jobs);

            made.setSize(WIDTH, HEIGHT);
            made.setLocation(0, 0);
            made.setVisible(true);
            held[0] = made;
        });

        MainWindow window = held[0];

        settle(2_000);

        String series = SeriesCatalog.names().isEmpty()
                ? SeriesCatalog.defaultName() : SeriesCatalog.names().get(0);

        System.out.println("serie: " + series);

        chart(window, series);
        backtest(window);
        replay(window);
        series(window);

        System.out.println("pronto");
        System.exit(0);
    }

    // ---------------------------------------------------------------- o chart

    private static void chart(MainWindow window, String series) throws Exception {
        SwingUtilities.invokeAndWait(() -> window.open(series));
        settle(4_000);

        // ROOM FIRST, indicators after. The console holds two lines and was
        // taking four hundred pixels of the picture, and the chart opened as a
        // small internal frame in the corner of the desktop -- so the price, the
        // one thing the picture exists to show, came out as a thin band. The
        // study stack also asks whether a new study FITS, so with the frame
        // small the third one was silently refused.
        SwingUtilities.invokeAndWait(() -> makeRoom(window));
        settle(1_500);

        SwingUtilities.invokeAndWait(() -> dress(window));
        settle(5_000);

        shoot(window, "01-grafico.png");
    }

    /** Folds the console and maximises the chart's own frame. */
    private static void makeRoom(MainWindow window) {
        CollapsiblePane console = find(window.getContentPane(), CollapsiblePane.class);

        if (console != null) {
            console.setFolded(true);
        } else {
            System.out.println("NAO ACHOU o console para recolher");
        }

        JInternalFrame frame = find(window.getDesktop(), JInternalFrame.class);

        if (frame == null) {
            System.out.println("NAO ACHOU a janela interna do grafico");

            return;
        }

        try {
            frame.setMaximum(true);
        } catch (java.beans.PropertyVetoException e) {
            System.out.println("a janela interna recusou maximizar: " + e.getMessage());
        }
    }

    /**
     * Puts seven indicators on the price and three studies under it.
     *
     * <p>Seven and three because the point of the picture is the BREADTH: a
     * chart with one average on it says nothing about what was built. The
     * numbers each indicator gets are the ones the catalogue itself declares as
     * its defaults, so this does not quietly invent a configuration that the
     * insert dialog would never offer.</p>
     */
    private static void dress(MainWindow window) {
        ChartCanvas canvas = find(window.getDesktop(), ChartCanvas.class);
        StudyStack studies = find(window.getDesktop(), StudyStack.class);

        if (canvas == null) {
            System.out.println("NAO ACHOU o grafico");

            return;
        }

        List<Overlay> drawn = new ArrayList<>();

        add(drawn, "overlay.movingAverage", List.of(9));
        add(drawn, "overlay.movingAverage", List.of(21));
        add(drawn, "overlay.movingAverage", List.of(200));
        add(drawn, "overlay.bollinger", null);
        add(drawn, "overlay.pivots", null);
        add(drawn, "overlay.patterns", null);
        add(drawn, "overlay.regression", null);

        canvas.setOverlays(drawn);

        if (studies == null) {
            System.out.println("NAO ACHOU a pilha de estudos");

            return;
        }

        for (String each : List.of("study.rsi", "study.stochastic", "study.pmo")) {
            Overlay made = build(each, null);

            if (made == null) {
                System.out.println("estudo desconhecido: " + each);

                continue;
            }

            // SAID OUT LOUD when it does not appear. The stack refuses a study
            // it has no room for, and the first run of this lost the PMO that
            // way -- in silence, which read as an indicator that does not work
            // rather than a window too small.
            System.out.println("estudo " + each + ": "
                    + (studies.show(made) == null ? "RECUSADO" : "ok"));
        }
    }

    private static void add(List<Overlay> into, String key, List<Integer> parameters) {
        Overlay made = build(key, parameters);

        if (made == null) {
            System.out.println("indicador desconhecido: " + key);

            return;
        }

        into.add(made);
    }

    /** @param parameters the numbers, or null for the ones the catalogue declares */
    private static Overlay build(String key, List<Integer> parameters) {
        for (OverlayCatalog.Kind kind : OverlayCatalog.kinds()) {
            if (kind.nameKey().equals(key)) {
                return OverlayCatalog.build(key,
                        parameters == null ? kind.defaults() : parameters);
            }
        }

        return null;
    }

    // ------------------------------------------------------------- o backtest

    private static void backtest(MainWindow window) throws Exception {
        SwingUtilities.invokeAndWait(() ->
                new BacktestHolder(window.getDesktop(), window, () -> { }).show());

        settle(2_000);
        shoot(window, "02-backtest-config.png");

        AbstractButton run = findButton(window.getDesktop(), Messages.get("backtest.run"));

        if (run == null) {
            System.out.println("NAO ACHOU o botao de rodar");

            return;
        }

        SwingUtilities.invokeAndWait(run::doClick);
        waitForRows(window);
        settle(2_000);
        shoot(window, "03-backtest-resultado.png");
    }

    /** Waits for the operations table to have rows, up to {@link #PATIENCE}. */
    private static void waitForRows(MainWindow window) throws Exception {
        for (int second = 0; second < PATIENCE; second++) {
            int[] rows = new int[1];

            SwingUtilities.invokeAndWait(() -> {
                JTable table = find(window.getDesktop(), JTable.class);

                rows[0] = table == null ? 0 : table.getRowCount();
            });

            if (rows[0] > 0) {
                System.out.println("backtest: " + rows[0] + " linhas em " + second + "s");

                return;
            }

            Thread.sleep(1_000);
        }

        System.out.println("backtest nao produziu linhas em " + PATIENCE + "s");
    }

    // --------------------------------------------------------------- o replay

    private static void replay(MainWindow window) throws Exception {
        ReplayWindow[] held = new ReplayWindow[1];

        SwingUtilities.invokeAndWait(() -> {
            held[0] = new ReplayWindow(window);
            held[0].setSize(1_400, 900);
            held[0].setLocation(40, 30);
            held[0].setVisible(true);
        });

        settle(3_000);

        // THE SESSION HAS TO BE ASKED FOR. Opened and photographed at once, the
        // replay is an empty frame with a date in it -- which says nothing about
        // what the replay does. This presses the button the reader presses.
        AbstractButton request = findButton(held[0], Messages.get("replay.request"));

        if (request == null) {
            System.out.println("NAO ACHOU o botao de requisitar do replay");
        } else {
            SwingUtilities.invokeAndWait(request::doClick);
            settle(25_000);
        }

        shoot(held[0], "04-replay.png");
        SwingUtilities.invokeAndWait(held[0]::dispose);
    }

    // ---------------------------------------------------------------- series

    private static void series(MainWindow window) throws Exception {
        SwingUtilities.invokeAndWait(() -> SeriesWindow.open(window));
        settle(2_500);

        // SeriesWindow keeps the open one in a private field, so the live list
        // of windows is what there is to ask.
        Window sheet = null;

        for (Window each : Window.getWindows()) {
            if (each instanceof SeriesWindow && each.isShowing()) {
                sheet = each;
            }
        }

        if (sheet == null) {
            System.out.println("NAO ACHOU a janela de series");

            return;
        }

        shoot(sheet, "05-series.png");

        Window closing = sheet;

        SwingUtilities.invokeAndWait(closing::dispose);
    }

    // ---------------------------------------------------------------- a foto

    /**
     * Paints the window into an image, rather than capturing the screen.
     *
     * <p>{@code Robot.createScreenCapture} would photograph whatever is in front
     * of the window and would clip on a display smaller than the size asked for
     * here. Painting asks the window to draw itself, which depends on nothing
     * outside the program.</p>
     */
    private static void shoot(Window of, String name) throws Exception {
        BufferedImage[] made = new BufferedImage[1];

        SwingUtilities.invokeAndWait(() -> {
            BufferedImage image = new BufferedImage(Math.max(1, of.getWidth()),
                    Math.max(1, of.getHeight()), BufferedImage.TYPE_INT_RGB);
            Graphics2D pen = image.createGraphics();

            of.paint(pen);
            pen.dispose();

            made[0] = image;
        });

        File file = OUT.resolve(name).toFile();

        ImageIO.write(made[0], "png", file);

        System.out.println("gravou " + file + "  "
                + made[0].getWidth() + "x" + made[0].getHeight());
    }

    // -------------------------------------------------------------- a procura

    private static <T> T find(Component in, Class<T> what) {
        if (what.isInstance(in)) {
            return what.cast(in);
        }

        if (in instanceof Container box) {
            for (Component each : box.getComponents()) {
                T found = find(each, what);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static AbstractButton findButton(Component in, String text) {
        if (in instanceof AbstractButton button && text.equals(button.getText())) {
            return button;
        }

        if (in instanceof Container box) {
            for (Component each : box.getComponents()) {
                AbstractButton found = findButton(each, text);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static void settle(int millis) throws Exception {
        Thread.sleep(millis);

        SwingUtilities.invokeAndWait(() -> { });
    }
}
