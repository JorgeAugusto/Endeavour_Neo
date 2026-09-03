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

    private final JLabel chip = new JLabel();

    private final JLabel clock = new JLabel("--:--:--");

    private final JLabel ends = new JLabel();

    private final JSlider scrubber = new JSlider(0, 1000, 0);

    private final JButton request = new JButton(Messages.get("replay.request"));

    private final JButton play = new JButton();

    private final JButton back = new JButton();

    private final JButton forward = new JButton();

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

        speed.setSelectedItem(1);
        speed.addActionListener(e -> withSession(s -> s.setSpeed((Integer) speed.getSelectedItem())));

        scrubber.addChangeListener(e -> {
            if (!adjusting && session != null) {
                session.seekFraction(scrubber.getValue() / 1000.0);
            }
        });

        refresh();
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
        left.add(labelled(Messages.get("replay.instrument"), chip));
        left.add(Box.createVerticalStrut(6));
        left.add(labelled(Messages.get("replay.date"), date));
        left.add(Box.createVerticalStrut(8));

        request.setAlignmentX(Component.LEFT_ALIGNMENT);
        left.add(request);

        row.add(left);

        return row;
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

        for (JButton button : new JButton[]{back, play, forward}) {
            button.setFocusable(false);
        }

        back.setToolTipText(Messages.get("replay.back"));
        forward.setToolTipText(Messages.get("replay.forward"));

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

    private void requestDay() {
        LocalDate day = date.date();

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

        session = new ReplaySession("WINFUT", day);

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

        chip.setText(ready ? session.instrument() : Messages.get("replay.noSession"));
        chip.setEnabled(ready);

        for (Component each : new Component[]{play, back, forward, scrubber, speed}) {
            each.setEnabled(ready);
        }

        play.setIcon(ready && session.isPlaying()
                ? ReplayIcons.pause(18) : ReplayIcons.play(18));

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
