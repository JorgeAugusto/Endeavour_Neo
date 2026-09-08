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
package br.com.jorge.reis.endeavourneo;

import br.com.jorge.reis.endeavourneo.platform.Appearance;
import br.com.jorge.reis.endeavourneo.platform.JobService;
import br.com.jorge.reis.endeavourneo.platform.Messages;
import br.com.jorge.reis.endeavourneo.platform.Theme;
import br.com.jorge.reis.endeavourneo.ui.shell.MainWindow;


import javax.swing.SwingUtilities;

/**
 * The entry point, and the only class allowed to see every layer.
 *
 * <p>It lives at the root rather than inside {@code platform} for a structural
 * reason: wiring the layers together necessarily means touching all of them, and
 * a class that does that inside a layer would force {@code LayerBoundaryTest} to
 * carry an exemption. Exemptions are how architecture rules rot. Put the one
 * class that legitimately breaks the rule outside the rule instead.</p>
 *
 *
 * <p>Usage: {@code Launcher [--light|--dark|--night]}</p>
 *
 * <p>The theme is remembered: next time the application opens with the same one
 * and no argument. Passing an argument switches it and stores the choice.</p>
 *
 * <p><b>Everything after the look and feel runs on the EDT.</b> That is not a
 * formality: creating Swing components off the event dispatch thread works
 * almost always and fails in ways that do not reproduce — half-painted windows,
 * a freeze on startup. The look and feel is the opposite case: it must be
 * installed BEFORE any component exists, because it only decides the appearance
 * of what is created after it.</p>
 */
public final class Launcher {

    /**
     * The calendar this market keeps, when the reader has not said otherwise.
     *
     * <p>WIN is the mini-index of B3, which trades in Sao Paulo. Every fold in
     * the program asks {@code Timeframe.defaultZone} where a day begins, and
     * without this the answer was the machine's clock -- correct in Brazil and
     * wrong everywhere else, silently, because a daily bar looks like a daily
     * bar either way.</p>
     */
    private static final String MARKET_ZONE = "America/Sao_Paulo";

    /**
     * Tells the domain which calendar a day is cut by, BEFORE anything folds.
     *
     * <p>Every fold that is not handed a zone lands on {@code
     * Timeframe.defaultZone}, and that used to be the machine's -- so a machine
     * outside Sao Paulo cut the day at the wrong hour, in silence, because a
     * daily bar looks like a daily bar either way. The domain cannot ask the
     * settings for this, so the settings tell it.</p>
     *
     * <p><b>And now it has a default.</b> This read {@code data.zone} and
     * nothing on earth wrote it: no {@code put}, no preferences page, no
     * default. The correction was built and never armed, and every machine fell
     * through to its own clock -- which is the defect the paragraph above
     * describes, still standing behind the line that describes it.</p>
     *
     * <p>Written back on first launch so the reader can SEE it and change it. A
     * setting that exists only as a default is a setting nobody knows they have,
     * and the whole reason these live in plain text is that they can be looked
     * at.</p>
     *
     * <p>Package-visible so a test can hand it a settings file of its own. On
     * the author's machine this changes nothing, which is exactly why it went
     * unnoticed for as long as it did.</p>
     */
    static void useMarketZone(br.com.jorge.reis.endeavourneo.platform.Settings from) {
        String market = from.get("data.zone", null);

        if (market == null) {
            market = MARKET_ZONE;

            from.put("data.zone", MARKET_ZONE);
        }

        if (market.isBlank()) {
            return;
        }

        try {
            br.com.jorge.reis.endeavourneo.domain.market.Timeframe.useZone(
                    java.time.ZoneId.of(market.trim()));
        } catch (java.time.DateTimeException e) {
            // A hand-edited settings file. Following the machine is the same
            // answer as before this setting existed, which is a smaller wrong
            // than refusing to start.
            System.err.println("data.zone is not a zone: " + market);
        }
    }

    private Launcher() {
        throw new AssertionError("Utility class must not be instantiated");
    }

    public static void main(String[] args) {
        Theme theme = Theme.remembered();

        for (String arg : args) {
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                Theme requested = Theme.of(name);

                // Theme.of falls back to LIGHT for anything it does not know,
                // so an unrecognised flag would silently switch the theme.
                // Only accept the value when it really matched.
                if (requested.getLabel().equalsIgnoreCase(name)) {
                    theme = requested;
                }
            }
        }

        theme.remember();

        String installed = Appearance.install(theme);

        useMarketZone(br.com.jorge.reis.endeavourneo.platform.Settings.settings());

        JobService jobs = new JobService();

        // Closes the pool when the JVM goes down. The threads are daemons and
        // would not hold it open, but a job mid-write deserves the chance to
        // be asked to stop before the process disappears.
        Runtime.getRuntime().addShutdownHook(new Thread(jobs::close, "jobs-shutdown"));

        // Off the interface thread, before anyone opens a calendar. Walking the
        // six-year source for its 1.494 sessions costs a tenth of a second, and
        // a tenth of a second is a stutter if it happens when a combo changes.
        // AND AGAIN WHENEVER THE DISK CHANGES. ReplayFeed.forget() empties the
        // calendar the moment a session is imported, a tape is exported or a
        // series is rebuilt -- and nothing refilled it, so the next change of
        // feed recomputed inside a combo listener, on the interface thread, at
        // the 39-102 ms per feed that ReplayFeed.KNOWN's own javadoc measures.
        // The class had declared it did not want that and then cleared the
        // memory without saying who would fill it.
        //
        // Here and not inside ReplayFeed: warming is background work, this is
        // where the project's background work is submitted, and a job submitted
        // here appears in the footer like every other. A static utility
        // starting a thread of its own would be a second answer to "where does
        // long work run", and one that no window can see.
        br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.whenForgotten(() ->
                jobs.submit("sessions", progress ->
                        Integer.valueOf(
                                br.com.jorge.reis.endeavourneo.ui.replay.ReplayFeed.warm())));

        jobs.submit("sessions", progress -> {
            int feeds = br.com.jorge.reis.endeavourneo.ui.replay.ReplayFeed.warm();

            // SAID PLAINLY, even though ReplayFeed.warm has just done it: it
            // opens every series and asks Sessions about each, so this loop is
            // all cache hits today -- and that is exactly why it is here. Four
            // other places want this same answer on the interface thread: the
            // renko before rebuilding, the summary tooltip on every mouse move,
            // the transport on a combo change, and the segments window. None of
            // them mentions ReplayFeed, and none would notice the day somebody
            // rewrites warm() and the 136 ms walk comes back under the pointer.
            // A performance property that holds by way of another class's
            // internals is a property nobody is guarding.
            for (String name : br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.names()) {
                try {
                    br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(
                            br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.open(name)
                                    .orElse(null));
                } catch (java.io.IOException e) {
                    // A series that will not read has no sessions to warm, and
                    // whoever opens it is the one who gets to say so.
                    continue;
                }
            }

            return Integer.valueOf(feeds);
        });

        SwingUtilities.invokeLater(() -> {
            // Before the first label is read: every window builds its text once.
            br.com.jorge.reis.endeavourneo.platform.Language.install();

            MainWindow window = new MainWindow(Messages.get("app.title"), jobs);

            // Order matters: capture standard output only once the console
            // exists, otherwise the first lines are lost.
            window.getConsole().captureStandardOutput();
            window.setVisible(true);

            window.getConsole().write(Messages.get("console.appearance", installed));
            window.getConsole().write(
                    Messages.get("console.java", System.getProperty("java.version")));
            window.getStatus().say(Messages.get("status.ready"));
        });
    }
}
