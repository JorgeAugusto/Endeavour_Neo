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

import br.com.jorge.reis.endeavourneo.platform.Messages;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.time.LocalDate;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;

/**
 * The replay transport: pick a day, request it, then drag it onto a chart.
 *
 * <p>Requesting and watching are two steps on purpose, as in the reference
 * product. The request fixes <b>which</b> session; dragging decides <b>where</b>
 * it is drawn — and a session can be dropped on several charts at once, which is
 * the whole reason the two are separate. One replay, the same instrument at five
 * minutes and at renko, side by side, running together.</p>
 */
public final class ReplayPanel extends JPanel {

    private static final long serialVersionUID = 1L;

    private final DatePicker date = new DatePicker(LocalDate.now().minusDays(1));

    private final DatePicker until = new DatePicker(LocalDate.now().minusDays(1));

    private final JLabel chip = new JLabel();

    /**
     * Which series is replayed.
     *
     * <p>Until today the replay played a random walk seeded by the date, and
     * the chip said "WINFUT" — a name that belongs to no base here. It animated
     * invented candles, and where ticks existed it animated them over the top,
     * which is two markets in one window and nothing on screen said so.</p>
     *
     * <p>One combo for now. The segment goes beside it when segments exist;
     * this one does not have to change for that.</p>
     */
    private final javax.swing.JComboBox<String> instrument = new javax.swing.JComboBox<>();

    /**
     * Which export the ticks come from.
     *
     * <p>Chosen, never guessed. The two sources hold different things -- the
     * MetaTrader export has the bid and the ask, the Profit tape has both
     * brokers and who crossed -- and today they cover different years as well.
     * A replay that picked one on its own would be answering the one question
     * only the reader can answer.</p>
     *
     * <p>Every source is offered, with how many sessions it has for the chosen
     * market beside it. A source with none is shown saying zero rather than
     * left out: "why can I not pick Profit" is a question the picker should
     * answer, not raise.</p>
     */
    private final JComboBox<br.com.jorge.reis.endeavourneo.domain.market.TickSource> ticks =
            new JComboBox<>();

    /** How many sessions each source has, for the market now chosen. */
    private final transient java.util.Map<
            br.com.jorge.reis.endeavourneo.domain.market.TickSource, Integer> exported =
            new java.util.EnumMap<>(br.com.jorge.reis.endeavourneo.domain.market.TickSource.class);

    private final JLabel clock = new JLabel("--:--:--");

    private final JLabel ends = new JLabel();

    private final JSlider scrubber = new JSlider(0, 1000, 0);

    private final JButton request = new JButton(Messages.get("replay.request"));

    private final JButton play = new JButton();

    private final JButton back = new JButton();

    private final JButton forward = new JButton();

    /**
     * Ends the session without closing the transport.
     *
     * <p>What closing already did, minus the closing. The reader who has just
     * watched a day and wants to set up another one had to close the window and
     * open it again, which threw away everything typed into it.</p>
     */
    private final JButton stop = new JButton();

    private final JComboBox<Integer> speed = new JComboBox<>();

    private final transient Runnable refresh = this::refresh;

    private transient ReplaySession session;

    /** Guards against the scrubber answering its own programmatic move. */
    private boolean adjusting;

    public ReplayPanel() {
        setLayout(new BorderLayout(0, 8));
        setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));

        add(top(), BorderLayout.NORTH);
        add(transport(), BorderLayout.CENTER);

        request.addActionListener(e -> requestDay());
        stop.addActionListener(e -> stopSession());
        play.addActionListener(e -> withSession(ReplaySession::toggle));
        back.addActionListener(e -> withSession(s -> {
            s.pause();
            s.step(-10);
        }));
        forward.addActionListener(e -> withSession(s -> {
            s.pause();
            s.step(10);
        }));

        for (int each : ReplaySession.SPEEDS) {
            speed.addItem(each);
        }

        // The speed the reader left it at. Everything else in this window is
        // remembered, and having one control reset itself on every launch reads
        // as a bug rather than as a default.
        speed.setSelectedItem(rememberedSpeed());

        speed.addActionListener(e -> br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .put("replay.speed", String.valueOf(speed.getSelectedItem())));
        speed.addActionListener(e -> withSession(s -> s.setSpeed((Integer) speed.getSelectedItem())));

        // The end follows the start rather than waiting to be refused: moving
        // the start past the end is somebody choosing a later day, not somebody
        // asking for a backwards range.
        Runnable settle = () -> {
            LocalDate corrected = keepInWindow(date.date(), until.date(),
                    ReplayPreferences.windowDays());

            if (corrected != null && !corrected.equals(until.date())) {
                until.setDate(corrected);
            }
        };

        date.onChange(settle);
        until.onChange(settle);

        scrubber.addChangeListener(e -> {
            if (!adjusting && session != null) {
                session.seekFraction(scrubber.getValue() / 1000.0);
            }
        });

        // The dates come back as they were left: this window is opened to
        // continue yesterday's work more often than to start something new.
        LocalDate from = readDate("replay.from", LocalDate.now().minusDays(1));

        date.setDate(from);
        until.setDate(keepInWindow(from, readDate("replay.to", from),
                ReplayPreferences.windowDays()));

        refresh();
    }

    /**
     * @return the speed left in the workspace, or the slowest
     *
     * <p>One times, when nothing was remembered: a replay that starts at sixty
     * on a reader who did not ask for it has gone past the thing they opened it
     * to look at before they can react.</p>
     */
    private static int rememberedSpeed() {
        String stored = br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .get("replay.speed", null);

        if (stored == null) {
            return 1;
        }

        try {
            int wanted = Integer.parseInt(stored.trim());

            for (int each : ReplaySession.SPEEDS) {
                if (each == wanted) {
                    return each;
                }
            }
        } catch (NumberFormatException e) {
            // A hand-edited file, or one from a version with other speeds.
        }

        return 1;
    }

    /** @return a date remembered in the workspace, or the fallback */
    private static LocalDate readDate(String key, LocalDate fallback) {
        try {
            String stored = br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                    .get(key, null);

            return stored == null ? fallback : LocalDate.parse(stored);
        } catch (java.time.format.DateTimeParseException e) {
            // A hand-edited file. Yesterday is a better answer than a dialog.
            return fallback;
        }
    }

    /** Ends whatever is playing and gives every chart following it back. */
    /**
     * Ends the session and hands every chart its own data back.
     *
     * <p>Exactly what closing does, minus the closing. Which is the point: the
     * reader who has watched a day and wants another one used to have to close
     * the transport and open it again, throwing away everything typed into
     * it.</p>
     */
    private void stopSession() {
        release();
    }

    public void release() {
        if (session != null) {
            session.forget(refresh);
            session.stop();
            session = null;

            refresh();
        }
    }

    // ------------------------------------------------------------- the pieces

    private JPanel top() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        chip.setOpaque(true);
        chip.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        chip.setFont(chip.getFont().deriveFont(Font.BOLD));
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        chip.setToolTipText(Messages.get("replay.dragHint"));

        // The chip is the handle: the session is carried to a chart by dragging
        // its name, which is how the reference product does it and is the only
        // gesture that says "this data, that window" without a dialog listing
        // every open chart.
        chip.setTransferHandler(new Handler());
        chip.addMouseListener(new java.awt.event.MouseAdapter() {

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (session != null) {
                    chip.getTransferHandler().exportAsDrag(chip, e, TransferHandler.COPY);
                }
            }
        });

        JPanel left = new JPanel();

        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        for (String each : br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.names()) {
            instrument.addItem(each);
        }

        String rememberedSeries = br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .get("replay.series", null);

        instrument.setSelectedItem(rememberedSeries != null
                && br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.has(rememberedSeries)
                ? rememberedSeries
                : 
                br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.defaultName());

        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource each
                : br.com.jorge.reis.endeavourneo.domain.market.TickSource.values()) {
            ticks.addItem(each);
        }

        ticks.setRenderer(new javax.swing.DefaultListCellRenderer() {

            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list,
                    Object value, int index, boolean chosen, boolean focused) {
                super.getListCellRendererComponent(list, value, index, chosen, focused);

                if (value instanceof br.com.jorge.reis.endeavourneo.domain.market.TickSource
                        source) {
                    setText(Messages.orElse("navigator.tickSource." + source.key(), source.key())
                            + " (" + exported.getOrDefault(source, 0) + ")");
                }

                return this;
            }
        });

        String rememberedTicks = br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .get("replay.ticks", null);

        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource each
                : br.com.jorge.reis.endeavourneo.domain.market.TickSource.values()) {
            if (each.name().equals(rememberedTicks)) {
                ticks.setSelectedItem(each);
            }
        }

        countExports();
        instrument.addActionListener(e -> countExports());

        left.add(labelled(Messages.get("replay.series"), instrument));
        left.add(labelled(Messages.get("replay.ticks"), ticks));
        left.add(labelled(Messages.get("replay.instrument"), chip));
        left.add(Box.createVerticalStrut(6));
        left.add(labelled(Messages.get("replay.from"), date));
        left.add(Box.createVerticalStrut(6));
        left.add(labelled(Messages.get("replay.to"), until));
        left.add(Box.createVerticalStrut(8));

        request.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(request);

        row.add(left);

        return row;
    }

    /**
     * Counts what each source has for the market now chosen.
     *
     * <p>Recounted when the market changes, because the answer is per market:
     * the tape of one instrument says nothing about another.</p>
     */
    private void countExports() {
        Object chosen = instrument.getSelectedItem();

        if (chosen == null) {
            return;
        }

        java.nio.file.Path folder = br.com.jorge.reis.endeavourneo.platform.SeriesCatalog
                .ticksOf(ReplaySession.rootOf(String.valueOf(chosen)));

        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource each
                : br.com.jorge.reis.endeavourneo.domain.market.TickSource.values()) {
            br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library =
                    new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                            folder, ReplaySession.rootOf(String.valueOf(chosen)), each);

            try {
                exported.put(each, library.exported().size());
            } finally {
                library.close();
            }
        }

        ticks.repaint();
    }

    private static JPanel labelled(String text, Component field) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));

        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(text);

        label.setPreferredSize(new Dimension(56, label.getPreferredSize().height));
        row.add(label);
        row.add(field);

        return row;
    }

    private JPanel transport() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        JPanel clocks = new JPanel(new BorderLayout());

        clock.setFont(br.com.jorge.reis.endeavourneo.platform.Appearance.monospaced(15));
        ends.setHorizontalAlignment(SwingConstants.RIGHT);
        ends.setEnabled(false);

        clocks.add(clock, BorderLayout.WEST);
        clocks.add(ends, BorderLayout.EAST);

        JPanel buttons = new JPanel(new GridLayout(1, 0, 4, 0));

        back.setIcon(ReplayIcons.back(16));
        play.setIcon(ReplayIcons.play(18));
        forward.setIcon(ReplayIcons.forward(16));

        stop.setIcon(ReplayIcons.stop(14));
        stop.setToolTipText(Messages.get("replay.stop"));

        for (JButton button : new JButton[]{back, play, forward, stop}) {
            button.setFocusable(false);
        }

        back.setToolTipText(Messages.get("replay.back"));
        forward.setToolTipText(Messages.get("replay.forward"));

        buttons.add(stop);
        buttons.add(back);
        buttons.add(play);
        buttons.add(forward);

        JPanel bottom = new JPanel(new BorderLayout(8, 0));

        bottom.add(buttons, BorderLayout.CENTER);

        JPanel rate = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));

        rate.add(new JLabel(Messages.get("replay.speed")));
        rate.add(speed);
        bottom.add(rate, BorderLayout.EAST);

        panel.add(clocks, BorderLayout.NORTH);
        panel.add(scrubber, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        return panel;
    }

    // ------------------------------------------------------------ the actions

    /**
     * @param maxDays how long the window may be, counting both ends
     * @return the end date, brought into range
     *
     * <p>Two corrections in one place, because they are the same question asked
     * from either side: an end BEFORE the start is moved up to it, and an end
     * too far AFTER is pulled back to the limit. Neither is refused -- both are
     * somebody dragging a date, not somebody asking for something impossible,
     * and a field that quietly settles where it may be is kinder than a dialog
     * saying no.</p>
     *
     * <p>Null when either is unreadable: somebody is still typing, and moving a
     * field under a cursor is worse than leaving it alone for a moment.</p>
     */
    static LocalDate keepInWindow(LocalDate from, LocalDate to, int maxDays) {
        if (from == null || to == null) {
            return null;
        }

        if (to.isBefore(from)) {
            return from;
        }

        LocalDate furthest = from.plusDays(Math.max(1, maxDays) - 1L);

        return to.isAfter(furthest) ? furthest : to;
    }

    private void requestDay() {
        LocalDate day = date.date();
        LocalDate last = until.date();

        if (day != null && last != null && last.isBefore(day)) {
            // Said where the mistake is rather than in a dialog. A range that
            // ends before it starts is a typo, not an error worth a window.
            until.field().setToolTipText(Messages.get("replay.badRange"));
            until.field().setBackground(new java.awt.Color(255, 235, 230));

            return;
        }

        until.field().setBackground(javax.swing.UIManager.getColor("TextField.background"));
        until.field().setToolTipText(null);

        if (day == null) {
            // Said in place rather than in a dialog: the field is right there,
            // and a dialog to report a typo in a date is a dialog too many.
            date.field().setToolTipText(Messages.get("replay.badDate"));
            date.field().setBackground(new java.awt.Color(255, 235, 230));

            return;
        }

        date.field().setBackground(javax.swing.UIManager.getColor("TextField.background"));
        date.field().setToolTipText(null);

        if (session != null) {
            session.forget(refresh);
            session.stop();
        }

        br.com.jorge.reis.endeavourneo.platform.Settings workspace =
                br.com.jorge.reis.endeavourneo.platform.Settings.workspace();

        workspace.put("replay.from", day.toString());
        workspace.put("replay.to", (last == null ? day : last).toString());

        String chosen = instrument.getSelectedItem() == null
                ? br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.defaultName()
                : String.valueOf(instrument.getSelectedItem());

        workspace.put("replay.series", chosen);

        br.com.jorge.reis.endeavourneo.domain.market.TickSource source =
                ticks.getSelectedItem() == null
                        ? br.com.jorge.reis.endeavourneo.domain.market.TickSource.METATRADER
                        : (br.com.jorge.reis.endeavourneo.domain.market.TickSource)
                                ticks.getSelectedItem();

        workspace.put("replay.ticks", source.name());

        session = new ReplaySession(chosen, day, last == null ? day : last,
                ReplayPreferences.historyDays(), source);

        session.watch(refresh);
        refresh();
    }

    private void withSession(java.util.function.Consumer<ReplaySession> what) {
        if (session != null) {
            what.accept(session);
        }
    }

    private void refresh() {
        boolean ready = session != null;

        // The first session's ticks are read before play is offered, and the
        // transport says so. Offering a play button that starts on invented
        // ticks, and then swaps them for the real ones a quarter of a second
        // later, would be a difference nobody could see and everybody would
        // inherit.
        boolean waiting = ready && session.isPreparing();

        // A range the base has no session for: a Saturday, a holiday, dates
        // outside what it covers. Said out loud, because the alternative the
        // reader sees is a play button that does nothing -- and the alternative
        // this program used to offer was a session that never happened.
        boolean nothingToPlay = ready && session.isEmpty();

        chip.setText(ready ? session.instrument() : Messages.get("replay.noSession"));
        chip.setEnabled(ready);

        for (Component each : new Component[]{play, back, forward, scrubber, speed}) {
            each.setEnabled(ready && !waiting && !nothingToPlay);
        }

        // Stop is enabled whenever there IS a session, playing or paused or
        // even one with nothing to play: it is the way out of any of them.
        stop.setEnabled(ready);

        // FROZEN while a session exists. Changing the series or the dates
        // underneath a running replay would leave the transport describing one
        // thing and the charts showing another, and nothing would say which was
        // which. Stop unfreezes them, which is what stop is for.
        for (Component each : new Component[]{instrument, ticks, date, until, request}) {
            each.setEnabled(!ready);
        }

        play.setIcon(ready && session.isPlaying()
                ? ReplayIcons.pause(18) : ReplayIcons.play(18));

        if (waiting) {
            clock.setText(Messages.get("replay.loading"));
            ends.setText("");
            chip.setEnabled(true);

            return;
        }

        if (nothingToPlay) {
            clock.setText(Messages.get("replay.nothing"));
            ends.setText(session.rangeText());

            return;
        }

        clock.setText(ready ? session.clockText() : "--:--:--");
        ends.setText(ready ? session.endText() : "");

        if (ready) {
            adjusting = true;
            scrubber.setValue((int) Math.round(session.progress() * 1000));
            adjusting = false;
        }
    }

    /** Carries the session to whatever chart it is dropped on. */
    private final class Handler extends TransferHandler {

        private static final long serialVersionUID = 1L;

        @Override
        public int getSourceActions(javax.swing.JComponent from) {
            return COPY;
        }

        @Override
        protected java.awt.datatransfer.Transferable createTransferable(
                javax.swing.JComponent from) {
            return session == null ? null : new ReplayTransfer(session);
        }
    }
}
