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
import javax.swing.UIManager;

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
     * The handle's own ground, the same in both themes.
     *
     * <p><b>Fixed, and deliberately not from the theme.</b> Everything else on
     * this panel follows the look and feel, because everything else is a
     * control the look and feel knows how to draw. This is a plate with a name
     * stamped on it, and a plate reads as a plate by being one colour
     * regardless of what surrounds it -- which is why the reference product's
     * is black on both of its themes too.</p>
     *
     * <p>The first version washed the panel colour with yellow instead. On the
     * light theme it was a gentle highlight; on the night one it was a stain,
     * and it never looked like the same object twice.</p>
     */
    private static final java.awt.Color HANDLE_GROUND = java.awt.Color.BLACK;

    /** White, because the ground is black. The arrow follows it; see ReplayIcons. */
    private static final java.awt.Color HANDLE_INK = java.awt.Color.WHITE;

    /**
     * What is replayed: a bar series, or a market's ticks from one export.
     *
     * <p>ONE list, where there were two. Asking separately for a series and a
     * tick source left the only question that matters unanswered -- what is on
     * screen. "winfull-1m" plus "Profit" meant minute bars out of the candle
     * file, animated inside by the tape where it existed and by an invented
     * walk where it did not, and nothing said so. Here the choice is the
     * answer: pick the minutes and minutes play; pick a tape and every bar on
     * screen is folded from trades that printed.</p>
     */
    private final JComboBox<ReplayFeed> feed = new JComboBox<>();

    private final JLabel clock = new JLabel("--:--:--");

    private final JLabel ends = new JLabel();

    private final JSlider scrubber = new JSlider(0, 1000, 0);

    /**
     * What stands in the scrubber's place while there is nothing to scrub.
     *
     * <p><b>Indeterminate, and honestly so.</b> The session is built in one
     * call that reports nothing on the way -- the tick library, then every
     * past day folded out of it -- so there is no fraction to show. A moving
     * bar with no number still says the one thing that matters here, which is
     * that the program is alive; a slider sitting at zero with the handle
     * greyed says the opposite, and four seconds of that is how an application
     * teaches people to press the button again.</p>
     *
     * <p>Making it a real percentage means threading a callback down through
     * the session's construction. Worth doing, and it is not this change.</p>
     */
    private final javax.swing.JProgressBar loading = new javax.swing.JProgressBar();

    /**
     * Holds whichever of the two belongs in that row right now.
     *
     * <p>The same slot, not two stacked: the transport is a fixed shape and a
     * row that appeared and disappeared would move the buttons under the
     * reader's pointer every time a session was asked for.</p>
     */
    private final JPanel track = new JPanel(new java.awt.CardLayout());

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

    /**
     * True while a session is being read.
     *
     * <p>A tick feed folds every past day out of its own ticks -- four seconds
     * for five of them -- and that happens off this thread. Without the flag
     * the window looks idle while it works, and idle is what a reader presses
     * again.</p>
     */
    private boolean building;

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
        // AFTER the notification, never inside it. Both pickers report a change
        // from a DocumentListener, and setDate writes to that same document --
        // so correcting "Até" while being told "Até" changed is Swing's
        // "Attempt to mutate in notification", an IllegalStateException thrown
        // on the interface thread the moment somebody typed a date outside the
        // window. Which also meant the correction never happened and the
        // windowDays ceiling was never applied by that field at all.
        //
        // invokeLater puts the write in the next event, when the document is no
        // longer being read. The reader sees the same thing: a field that fixes
        // itself as they type.
        Runnable settle = () -> javax.swing.SwingUtilities.invokeLater(() -> {
            LocalDate corrected = keepInWindow(date.date(), until.date(),
                    ReplayPreferences.windowDays());

            if (corrected != null && !corrected.equals(until.date())) {
                until.setDate(corrected);
            }
        });

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

        // AND THEN THE FEED HAS THE LAST WORD. followFeed runs from top(),
        // higher up in this constructor, and moves the pickers onto a day the
        // chosen feed actually has -- and these three lines then wrote the
        // remembered dates straight over that answer, without asking the feed
        // anything. Reopening the transport on a tape of nine sessions with a
        // date remembered from 2021 left the calendar all grey and the field
        // holding a day that feed has never heard of. Nothing downstream
        // corrected it: settle only fits `until` to `date`, and requestDay
        // checks "unreadable" and "end before start" and nothing else.
        //
        // Last, because the remembered dates are the reader's preference and the
        // feed's sessions are a fact. A preference that cannot be honoured is
        // moved to the nearest thing that can.
        followFeed();

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

    /**
     * Whether this panel has been let go of.
     *
     * <p>Set on the way out and never cleared: a released panel is not reused,
     * it is replaced. What it guards is the session still being built when the
     * window closed -- see the worker's {@code done}.</p>
     */
    private transient boolean released;

    /** Ends whatever is playing and gives every chart following it back. */
    public void release() {
        released = true;

        if (session != null) {
            session.forget(refresh);
            session.stop();
            session = null;

            refresh();
        }
    }

    // ------------------------------------------------------------- the pieces

    /**
     * Puts the market's name on the handle, or takes it off.
     *
     * @param ready whether there is a session to carry
     *
     * <p>Everything the handle says goes on and off together. With no session
     * there is nothing to drag, so the arrow and the wash go with the name --
     * a control that looks draggable and is not is worse than a plain
     * label.</p>
     */
    private void dressChip(boolean ready) {
        chip.setText(ready ? session.name() : Messages.get("replay.noSession"));
        chip.setEnabled(ready);
        chip.setIcon(ready ? ReplayIcons.drag(12) : null);
        chip.setBackground(ready ? HANDLE_GROUND : UIManager.getColor("Panel.background"));
        chip.setForeground(ready ? HANDLE_INK : UIManager.getColor("Label.foreground"));
        chip.setCursor(Cursor.getPredefinedCursor(
                ready ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
    }

    private JPanel top() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

        chip.setOpaque(true);
        chip.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        chip.setFont(chip.getFont().deriveFont(Font.BOLD));
        chip.setToolTipText(Messages.get("replay.dragHint"));

        // A LABEL THAT BEHAVES LIKE A CONTROL HAS TO LOOK LIKE ONE. This one is
        // the handle -- the session is carried to a chart by dragging its name
        // -- and until now it looked exactly like the words beside it. Three
        // marks say so, and not one of them is a sentence: the four-way arrow,
        // the move cursor, and a ground of its own.
        // The arrow itself goes on and off with the session; see dressChip.
        chip.setHorizontalTextPosition(SwingConstants.LEADING);
        chip.setIconTextGap(8);
        chip.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));

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
        // ASKED ONCE. Each round of available() opens a TickLibrary per market
        // per export and calls exported(), which is a recursive four-level
        // directory walk -- and this used to pay for two of them on the
        // interface thread, because read() went and asked again.
        java.util.List<ReplayFeed> feeds = ReplayFeed.available();

        for (ReplayFeed each : feeds) {
            feed.addItem(each);
        }

        ReplayFeed remembered = ReplayFeed.read(
                br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                        .get("replay.feed", null), feeds);

        if (remembered != null) {
            feed.setSelectedItem(remembered);
        }

        feed.addActionListener(e -> followFeed());
        followFeed();

        left.add(labelled(Messages.get("replay.series"), feed));
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
     * Points the calendars at what the chosen feed can actually play.
     *
     * <p>And moves the dates when they no longer can be. Switching from six
     * years of minutes to a tape of eight sessions leaves both pickers holding
     * a day that feed has never heard of; landing on the feed's LAST session is
     * where the reader was going anyway, and it is one click instead of
     * hunting through a calendar that is almost entirely grey.</p>
     */
    private void followFeed() {
        Object chosen = feed.getSelectedItem();

        if (!(chosen instanceof ReplayFeed picked)) {
            return;
        }

        java.util.NavigableSet<LocalDate> days = picked.sessions();

        // Empty means the feed could not be read at all. Leaving the calendars
        // open is better than greying every day of a fault the reader cannot
        // see: the transport already says "nothing to play" when asked.
        java.util.NavigableSet<LocalDate> playable = days.isEmpty() ? null : days;

        date.setSessions(playable);
        until.setSessions(playable);

        if (playable == null) {
            return;
        }

        LocalDate last = playable.last();

        if (date.date() == null || !playable.contains(date.date())) {
            date.setDate(last);
        }

        if (until.date() == null || !playable.contains(until.date())) {
            until.setDate(last);
        }
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
        loading.setIndeterminate(true);
        loading.setPreferredSize(new java.awt.Dimension(
                scrubber.getPreferredSize().width, 12));

        // Centred at its own height rather than filling the row. The card it
        // shares gives a card the whole area, and a progress bar told to fill
        // forty pixels draws a forty-pixel block -- next to the slider it
        // replaces, that reads as a different control, not the same one busy.
        JPanel held = new JPanel(new java.awt.GridBagLayout());

        held.setOpaque(false);
        held.add(loading);

        track.add(scrubber, "scrubber");
        track.add(held, "loading");

        panel.add(track, BorderLayout.CENTER);
        panel.add(bottom, BorderLayout.SOUTH);

        return panel;
    }

    // ------------------------------------------------------------ the actions

    /**
     * @param maxDays how many SESSIONS the window may hold, counting the first
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
     *
     * <p><b>Sessions, and this used to count calendar days.</b> The other end of
     * the same number counts sessions -- ReplaySession.sessionsIn skips
     * Saturday and Sunday, and the constructor's javadoc says so out loud --
     * and the setting's own justification is written in sessions: "ten sessions
     * at sixty times real time is an hour and a half". Monday plus nine
     * calendar days is the Wednesday after, which is eight sessions: the
     * setting delivered 20% less than the paragraph explaining it claimed.</p>
     */
    static LocalDate keepInWindow(LocalDate from, LocalDate to, int maxDays) {
        if (from == null || to == null) {
            return null;
        }

        if (to.isBefore(from)) {
            return from;
        }

        LocalDate furthest = ReplaySession.endOfWindow(from, maxDays);

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

            // AND FORGOTTEN. Stopping it left the field pointing at a session
            // that is over, so everything between here and the new one's arrival
            // was talking to a dead clock.
            session = null;
        }

        br.com.jorge.reis.endeavourneo.platform.Settings workspace =
                br.com.jorge.reis.endeavourneo.platform.Settings.workspace();

        workspace.put("replay.from", day.toString());
        workspace.put("replay.to", (last == null ? day : last).toString());

        ReplayFeed chosen = feed.getSelectedItem() == null
                ? ReplayFeed.of(br.com.jorge.reis.endeavourneo.platform.SeriesCatalog
                        .defaultName())
                : (ReplayFeed) feed.getSelectedItem();

        workspace.put("replay.feed", chosen.saved());

        // Off the interface thread, because building a tick feed READS. Five
        // sessions of past fold into candles in about four seconds, measured on
        // the exported tape, and four seconds of frozen window is how an
        // application teaches people not to press a button.
        //
        // The controls stay frozen and the clock says so, which is the same
        // state the first session's ticks already put the transport in.
        building = true;
        refresh();

        LocalDate first = day;
        LocalDate end = last == null ? day : last;

        new javax.swing.SwingWorker<ReplaySession, Void>() {

            @Override
            protected ReplaySession doInBackground() {
                return new ReplaySession(chosen, first, end,
                        ReplayPreferences.historyDays(),
                        br.com.jorge.reis.endeavourneo.platform.SeriesCatalog
                                .ticksOf(chosen.instrument()));
            }

            @Override
            protected void done() {
                building = false;

                try {
                    ReplaySession built = get();

                    if (released) {
                        // NOBODY IS WATCHING THIS ANY MORE. release() ran while
                        // this was building -- the window closing does it, and
                        // so does every change of language, which closes and
                        // rebuilds the whole shell. Adopting here put a live
                        // session, with its tick library open, onto a panel that
                        // is gone and will never call stop(): the loader's
                        // thread and up to three resident sessions of ticks,
                        // 340 MB, held until the process ends.
                        //
                        // It is a narrow window -- the four seconds a session
                        // takes to build -- and it is the one path in this area
                        // where something AutoCloseable is opened and not closed
                        // by whoever opened it.
                        built.stop();

                        return;
                    }

                    session = built;

                    adopt(session);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException e) {
                    // A session that will not build leaves the transport with
                    // none, which it already knows how to show. Better than a
                    // window of prices that came from nowhere.
                    session = null;
                }

                refresh();
            }
        }.execute();
    }

    /**
     * Hands a freshly built session what the transport is already showing.
     *
     * <p><b>The speed has to be pushed, not waited for.</b> The combo is filled
     * from the workspace before its listeners are attached, so restoring the
     * reader's choice fires no event; and a session is constructed with no
     * speed argument, so it starts at 1x. The only code that ever called
     * setSpeed was the combo's listener, which fires only when the reader
     * changes the selection by hand. Every session after the first therefore
     * crawled at 1x while the control beside it read 60, and the reader's answer
     * to that -- touching the combo -- was the one thing that made it work.</p>
     *
     * <p>Package-private so a test can run this exact step; it is the step that
     * was missing.</p>
     */
    void adopt(ReplaySession fresh) {
        fresh.setSpeed(chosenSpeed());
        fresh.watch(refresh);
    }

    /** @return the speed the transport is showing right now */
    int chosenSpeed() {
        Object selected = speed.getSelectedItem();

        return selected instanceof Integer chosen ? chosen : 1;
    }

    private void withSession(java.util.function.Consumer<ReplaySession> what) {
        if (session != null) {
            what.accept(session);
        }
    }

    private void refresh() {
        boolean ready = session != null;

        if (building) {
            // Everything off, and the clock says why. The selections stay
            // frozen for the same reason they freeze while playing: changing
            // the feed underneath a session being read would leave the
            // transport describing one thing and the charts showing another.
            for (Component each : new Component[]{
                    feed, date, until, request, play, back, forward, scrubber, speed, stop}) {
                each.setEnabled(false);
            }

            dressChip(false);
            showLoading(true);
            clock.setText(Messages.get("replay.loading"));
            ends.setText("");

            return;
        }

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

        dressChip(ready);

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
        for (Component each : new Component[]{feed, date, until, request}) {
            each.setEnabled(!ready);
        }

        play.setIcon(ready && session.isPlaying()
                ? ReplayIcons.pause(18) : ReplayIcons.play(18));

        showLoading(waiting);

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

    /**
     * Puts the moving bar in the scrubber's place, or takes it away.
     *
     * <p>The bar is only ANIMATED while it is the one showing. An
     * indeterminate progress bar repaints itself on a timer whether or not
     * anybody can see it, and a transport sitting idle all afternoon would be
     * spending a timer on a picture of nothing.</p>
     *
     * <p>Package-visible so a test can put the transport in both states. The
     * state it cannot reach otherwise is the one that matters -- getting there
     * for real means building a session off a folder of ticks, and a test that
     * did that would be testing the reader, not the swap.</p>
     */
    void showLoading(boolean busy) {
        loading.setIndeterminate(busy);

        ((java.awt.CardLayout) track.getLayout())
                .show(track, busy ? "loading" : "scrubber");
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
