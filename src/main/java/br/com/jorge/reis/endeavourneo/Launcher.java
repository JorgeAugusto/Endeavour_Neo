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

        JobService jobs = new JobService();

        // Closes the pool when the JVM goes down. The threads are daemons and
        // would not hold it open, but a job mid-write deserves the chance to
        // be asked to stop before the process disappears.
        Runtime.getRuntime().addShutdownHook(new Thread(jobs::close, "jobs-shutdown"));

        SwingUtilities.invokeLater(() -> {
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
