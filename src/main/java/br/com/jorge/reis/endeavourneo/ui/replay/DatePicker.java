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

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.TextStyle;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * A date field with a calendar behind a button.
 *
 * <p>Swing ships no date picker, and the two usual substitutes are both worse: a
 * spinner makes the reader step one day at a time through a month, and a plain
 * text field makes them type a format they have to guess. Both are still here —
 * the field takes typing and the calendar takes clicking — but neither on its
 * own.</p>
 *
 * <p><b>The week starts on Sunday</b>, as asked. Java would otherwise decide
 * from the machine's locale, which means the same build shows a different grid
 * on a different computer, and a reader who has learned where Friday sits finds
 * it moved.</p>
 */
public final class DatePicker extends JPanel {

    private static final long serialVersionUID = 1L;

    private static final DateTimeFormatter TYPED = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    /** Sunday first, then round the week. */
    private static final DayOfWeek[] WEEK = {
            DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY,
    };

    private final JTextField field = new JTextField(9);

    private final JButton open = new JButton("▾");

    private transient YearMonth showing;

    private transient Runnable onChange = () -> { };

    public DatePicker(LocalDate initial) {
        super(new BorderLayout(2, 0));

        setDate(initial == null ? LocalDate.now() : initial);

        open.setFocusable(false);
        open.setMargin(new Insets(1, 4, 1, 4));
        open.addActionListener(e -> showCalendar());

        add(field, BorderLayout.CENTER);
        add(open, BorderLayout.EAST);
    }

    /** @return the date typed or chosen, or null when the text is not a date */
    public LocalDate date() {
        try {
            return LocalDate.parse(field.getText().trim(), TYPED);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public void setDate(LocalDate date) {
        field.setText(date.format(TYPED));
    }

    /** @param listener told whenever the date changes, typed or picked */
    public void onChange(Runnable listener) {
        this.onChange = listener == null ? () -> { } : listener;

        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {

            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                onChange.run();
            }
        });
    }

    public JTextField field() {
        return field;
    }

    // ------------------------------------------------------------ o calendario

    private void showCalendar() {
        LocalDate current = date() == null ? LocalDate.now() : date();

        showing = YearMonth.from(current);

        JPopupMenu popup = new JPopupMenu();

        popup.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        popup.add(month(popup, current));
        popup.show(open, 0, open.getHeight());
    }

    private JPanel month(JPopupMenu popup, LocalDate chosen) {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        JPanel head = new JPanel(new BorderLayout());
        JButton previous = arrow("◀", popup, chosen, -1);
        JButton next = arrow("▶", popup, chosen, +1);

        JLabel title = new JLabel(showing.getMonth()
                .getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + showing.getYear(),
                SwingConstants.CENTER);

        title.setFont(title.getFont().deriveFont(Font.BOLD));

        head.add(previous, BorderLayout.WEST);
        head.add(title, BorderLayout.CENTER);
        head.add(next, BorderLayout.EAST);

        JPanel grid = new JPanel(new GridLayout(0, 7, 2, 2));

        for (DayOfWeek day : WEEK) {
            JLabel label = new JLabel(
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    SwingConstants.CENTER);

            label.setEnabled(false);
            grid.add(label);
        }

        LocalDate first = showing.atDay(1);

        // How many blanks before the first of the month, counting from Sunday.
        int blanks = Math.floorMod(first.getDayOfWeek().getValue() - DayOfWeek.SUNDAY.getValue(), 7);

        for (int i = 0; i < blanks; i++) {
            grid.add(new JLabel());
        }

        for (int day = 1; day <= showing.lengthOfMonth(); day++) {
            grid.add(dayButton(popup, showing.atDay(day), chosen));
        }

        panel.add(head, BorderLayout.NORTH);
        panel.add(grid, BorderLayout.CENTER);

        return panel;
    }

    private JButton arrow(String text, JPopupMenu popup, LocalDate chosen, int months) {
        JButton button = new JButton(text);

        button.setFocusable(false);
        button.setMargin(new Insets(1, 6, 1, 6));
        button.addActionListener(e -> {
            showing = showing.plusMonths(months);

            popup.removeAll();
            popup.add(month(popup, chosen));
            popup.pack();
        });

        return button;
    }

    private JButton dayButton(JPopupMenu popup, LocalDate day, LocalDate chosen) {
        JButton button = new JButton(String.valueOf(day.getDayOfMonth()));

        button.setFocusable(false);
        button.setMargin(new Insets(2, 2, 2, 2));
        button.setPreferredSize(new Dimension(30, 24));

        boolean weekend = day.getDayOfWeek() == DayOfWeek.SATURDAY
                || day.getDayOfWeek() == DayOfWeek.SUNDAY;

        if (weekend) {
            // Dimmed rather than removed: a weekend has no session, but hiding
            // it would leave a hole in the grid and make the columns lie.
            button.setEnabled(false);
        }

        if (day.equals(chosen)) {
            button.setFont(button.getFont().deriveFont(Font.BOLD));
            button.setBorder(BorderFactory.createLineBorder(accent(), 1));
        }

        button.addActionListener(e -> {
            setDate(day);
            popup.setVisible(false);
        });

        return button;
    }

    private static Color accent() {
        Color colour = UIManager.getColor("Component.focusedBorderColor");

        return colour == null ? Color.GRAY : colour;
    }
}
