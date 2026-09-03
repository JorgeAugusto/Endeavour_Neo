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

import br.com.jorge.reis.endeavourneo.platform.JobService;
import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

/**
 * The footer: message on the left, running job on the right.
 *
 * <p>It exists for a reason of conduct, not decoration: <b>long work with no
 * visible feedback looks like a freeze</b>. Anything taking more than a second
 * should show up here — and, since it can be cancelled, it should say so.</p>
 *
 * <p>Bound to a {@link JobService} it becomes the answer to the two questions a
 * user asks when the program seems slow: <i>what is it doing</i> and <i>how do I
 * stop it</i>. Without the second one, the first is only half an answer.</p>
 *
 * <p>Every public method may be called from any thread — they marshal
 * themselves onto the interface thread. That is deliberate: code inside a job
 * should not have to remember Swing's threading rule just to write a message.</p>
 */
public final class StatusBar extends JPanel {

    private static final long serialVersionUID = 1L;

    private final JLabel message = new JLabel(" ");

    private final JLabel jobName = new JLabel();

    private final JProgressBar progress = new JProgressBar();

    private final JButton cancel = new JButton();

    private final JPanel jobArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    private transient JobService jobs;

    public StatusBar() {
        super(new BorderLayout(8, 0));

        setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));

        progress.setPreferredSize(new Dimension(160, 14));

        cancel.setText(Messages.get("status.cancel"));
        cancel.setFocusable(false);
        cancel.addActionListener(e -> {
            if (jobs != null) {
                jobs.cancelAll();
            }
        });

        jobArea.setOpaque(false);
        jobArea.add(jobName);
        jobArea.add(progress);
        jobArea.add(cancel);
        jobArea.setVisible(false);

        add(message, BorderLayout.CENTER);
        add(jobArea, BorderLayout.EAST);
    }

    /**
     * Makes this bar follow a job service.
     *
     * <p>The service calls back on the interface thread, so the refresh below
     * may touch components directly.</p>
     */
    public void bind(JobService service) {
        this.jobs = service;

        service.onChange(this::refresh);
        refresh();
    }

    private void refresh() {
        boolean busy = jobs != null && jobs.isBusy();

        jobArea.setVisible(busy);

        if (!busy) {
            return;
        }

        var names = jobs.runningNames();
        String stage = jobs.stage();
        String label = names.size() == 1
                ? names.get(0)
                : Messages.get("status.jobs", names.size());

        jobName.setText(stage.isEmpty() ? label : label + " — " + stage);

        double fraction = jobs.fraction();

        if (fraction >= 0.0 && fraction <= 1.0) {
            progress.setIndeterminate(false);
            progress.setValue((int) Math.round(fraction * 100));
        } else {
            // No number reported: an indeterminate bar still says "alive",
            // which is the point. A bar stuck at zero says "hung".
            progress.setIndeterminate(true);
        }
    }

    /** @param text the message, or null to clear it */
    public void say(String text) {
        onEdt(() -> message.setText(text == null || text.isBlank() ? " " : text));
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }
}
