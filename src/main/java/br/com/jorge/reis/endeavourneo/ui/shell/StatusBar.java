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

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The footer: the message on the left, a few fields, the running job on the right.
 *
 * <p>It exists for a reason of conduct, not decoration: <b>long work with no
 * visible feedback looks like a freeze</b>. Anything taking more than a second
 * should show up here — and, since it can be cancelled, it should say so.</p>
 *
 * <p>Bound to a {@link JobService} it becomes the answer to the two questions a
 * user asks when the program seems slow: <i>what is it doing</i> and <i>how do I
 * stop it</i>. Without the second one, the first is only half an answer.</p>
 *
 * <h2>The message is the elastic one</h2>
 *
 * <p>The fields take what they need and the message takes the rest. In a tool
 * for research what this bar says most often is the result of something the
 * reader asked for — imported, saved, measured, failed — and that sentence is
 * long. The chart's own name and scale are written in the title bar of the
 * window just above; repeating them here at the cost of the sentence would be
 * paying twice for one of them.</p>
 *
 * <h2>What drops when the window is narrow</h2>
 *
 * <p>Fields are dropped one at a time from the right, nearest the job first,
 * and the message keeps a floor of its own. <b>The job area never drops</b>:
 * it is the only part of this bar that can be the reason the program is slow,
 * and hiding it in a small window would hide the cancel button with it.</p>
 *
 * <p>Every public method may be called from any thread — they marshal
 * themselves onto the interface thread. That is deliberate: code inside a job
 * should not have to remember Swing's threading rule just to write a message.</p>
 */
public final class StatusBar extends JPanel {

    private static final long serialVersionUID = 1L;

    /** The narrowest the message is allowed to become before fields start going. */
    private static final int MESSAGE_FLOOR = 140;

    private static final int GAP = 10;

    private final JLabel message = new JLabel(" ");

    /** What chart the pointer is on: its name and its scale. */
    private final JLabel chart = new JLabel();

    /** The bar under the pointer, as time and close. */
    private final JLabel reading = new JLabel();

    /** What the chart is doing, when it is not doing the usual thing. */
    private final JLabel mode = new JLabel();

    private final JLabel jobName = new JLabel();

    private final JProgressBar progress = new JProgressBar();

    private final JButton cancel = new JButton();

    private final JPanel jobArea = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    /** The droppable ones, in the order they are shown; the last goes first. */
    private final transient List<JLabel> fields = List.of(chart, reading, mode);

    private transient JobService jobs;

    public StatusBar() {
        setLayout(new Strip());
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

        add(message);

        for (JLabel field : fields) {
            // A rule down its left edge, so the fields read as separate
            // answers rather than as one sentence that lost its verbs.
            field.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 1, 0, 0, separator()),
                    BorderFactory.createEmptyBorder(0, GAP, 0, 0)));
            field.setVisible(false);

            add(field);
        }

        add(jobArea);
    }

    private static java.awt.Color separator() {
        java.awt.Color found = UIManager.getColor("Separator.foreground");

        return found == null ? java.awt.Color.GRAY : found;
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
        revalidate();

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

    /**
     * Reports the chart the pointer is over.
     *
     * @param identity its name and scale, e.g. {@code WINFUT-FULL · 5m}
     * @param cursorReading the bar under the pointer, or empty for none
     * @param modeLabel what the chart is doing, or empty for the usual thing
     *
     * <p>An empty field is HIDDEN, not blanked. A dash where a number would be
     * is a section admitting it has nothing, and three of those is a bar
     * apologising for itself across the bottom of the window.</p>
     */
    public void chart(String identity, String cursorReading, String modeLabel) {
        onEdt(() -> {
            set(chart, identity);
            set(reading, cursorReading);
            set(mode, modeLabel);

            revalidate();
            repaint();
        });
    }

    /** Clears the chart fields, for when there is no chart under the pointer. */
    public void noChart() {
        chart("", "", "");
    }

    private static void set(JLabel label, String text) {
        boolean has = text != null && !text.isBlank();

        label.setText(has ? text : "");
        label.setVisible(has);
    }

    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
        } else {
            SwingUtilities.invokeLater(action);
        }
    }

    /**
     * The message stretching, the fields at their own width, the job pinned right.
     *
     * <p>Not a {@code BorderLayout} with a panel on each side, which is what
     * this was: that gives the side its preferred width whatever happens, so a
     * narrow window squeezes the message to nothing while the fields stay
     * whole. The decision of what to give up has to be made here, where the
     * total width is known.</p>
     */
    private final class Strip implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) {
            // Nothing: which component is which is decided by the field list.
        }

        @Override
        public void removeLayoutComponent(Component component) {
            // Nothing to forget.
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            java.awt.Insets edge = parent.getInsets();
            int wide = MESSAGE_FLOOR;
            int tall = message.getPreferredSize().height;

            for (Component each : parent.getComponents()) {
                if (each != message && each.isVisible()) {
                    wide += each.getPreferredSize().width + GAP;
                    tall = Math.max(tall, each.getPreferredSize().height);
                }
            }

            return new Dimension(wide + edge.left + edge.right, tall + edge.top + edge.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(MESSAGE_FLOOR, preferredLayoutSize(parent).height);
        }

        @Override
        public void layoutContainer(Container parent) {
            java.awt.Insets edge = parent.getInsets();
            int left = edge.left;
            int top = edge.top;
            int width = parent.getWidth() - edge.left - edge.right;
            int height = parent.getHeight() - edge.top - edge.bottom;

            // The job first: it is the one thing that never gives way.
            int forJob = jobArea.isVisible() ? jobArea.getPreferredSize().width + GAP : 0;
            List<JLabel> shown = new ArrayList<>();
            int forFields = 0;

            for (JLabel field : fields) {
                if (field.isVisible() && !field.getText().isBlank()) {
                    shown.add(field);
                    forFields += field.getPreferredSize().width + GAP;
                }
            }

            // Then drop from the right, one at a time, until the message has
            // its floor back. Dropping the leftmost instead would take away
            // WHICH CHART this is about and leave the numbers with no subject.
            while (!shown.isEmpty()
                    && width - forJob - forFields < MESSAGE_FLOOR) {
                JLabel dropped = shown.remove(shown.size() - 1);

                forFields -= dropped.getPreferredSize().width + GAP;
            }

            for (JLabel field : fields) {
                // Laid out off-screen rather than made invisible: setVisible
                // here would fight the one in set(), and a component hidden by
                // the layout could never come back when the window grew.
                field.setBounds(shown.contains(field) ? left : -10_000, top, 0, height);
            }

            message.setBounds(left, top,
                    Math.max(0, width - forJob - forFields), height);

            int at = left + Math.max(0, width - forJob - forFields);

            for (JLabel field : shown) {
                int mine = field.getPreferredSize().width + GAP;

                field.setBounds(at, top, mine, height);

                at += mine;
            }

            if (jobArea.isVisible()) {
                jobArea.setBounds(at + GAP, top,
                        Math.max(0, parent.getWidth() - edge.right - at - GAP), height);
            }
        }
    }
}
