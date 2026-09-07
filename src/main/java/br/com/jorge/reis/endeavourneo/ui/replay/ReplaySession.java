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

import br.com.jorge.reis.endeavourneo.domain.market.ConcatSeries;
import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;
import br.com.jorge.reis.endeavourneo.domain.market.ReplaySeries;
import br.com.jorge.reis.endeavourneo.domain.market.RecordedTicks;
import br.com.jorge.reis.endeavourneo.domain.market.Segment;
import br.com.jorge.reis.endeavourneo.domain.market.SegmentedSeries;
import br.com.jorge.reis.endeavourneo.domain.market.SyntheticTicks;
import br.com.jorge.reis.endeavourneo.domain.market.TickLibrary;
import br.com.jorge.reis.endeavourneo.domain.market.TickSeries;
import br.com.jorge.reis.endeavourneo.domain.market.TickPath;
import br.com.jorge.reis.endeavourneo.domain.market.TickSource;
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import javax.swing.Timer;

/**
 * One session being played back: the day, the clock, and who is watching.
 *
 * <p>The clock is a Swing {@link Timer}, so every advance happens on the
 * interface thread — the same thread that repaints the charts watching it. A
 * background thread here would buy nothing (advancing a counter is free) and
 * would put every chart's paint path in reach of another thread.</p>
 *
 * <p><b>Speed is a multiple of real time.</b> At 1× a one-minute bar takes one
 * minute, because that is what everyone means by a replay. See {@link #SPEEDS}
 * for why the first version got this wrong.</p>
 */
public final class ReplaySession {

    /**
     * How much faster than the market the transport can run.
     *
     * <p><b>A multiple of real time, not bars per second.</b> The first version
     * counted bars, on the argument that "16x" says nothing without knowing the
     * scale. That was wrong about what anybody expects: at 1x a one-minute bar
     * has to take one minute, and it takes one minute at every scale. Bars per
     * second was unambiguous and matched nobody's idea of a replay.</p>
     */
    public static final int[] SPEEDS = {1, 2, 5, 10, 30, 60};

    /**
     * The fastest the transport goes: <b>a minute of market per second</b>.
     *
     * <p>Sixty is a number anybody can hold: the clock on screen runs a minute
     * while a second passes, so a session of nine and a half hours takes nine
     * and a half minutes.</p>
     *
     * <p>Not a limit of the machine — measured, advancing the ticks and folding
     * the day again costs 0,03 ms against a 40 ms frame, over a thousand times
     * the headroom. It is a limit of what can be <b>seen</b>: a bar is broken
     * into about thirty-one prices, one every 1,93 s of market time, so at sixty
     * times one arrives every 32 ms and a 40 ms frame drops the odd one. That is
     * the price of the round number, and it is worth paying — past here the bar
     * stops forming and starts jumping, which is the thing the ticks exist to
     * prevent.</p>
     */
    public static final int FASTEST = 60;

    /**
     * How often the clock ticks, in milliseconds of wall time.
     *
     * <p>Fixed, and the SPEED decides how much market time each frame carries.
     * Tying the timer to the speed instead would make a slow replay stutter and
     * a fast one fire hundreds of times a second for no more movement on
     * screen.</p>
     */
    private static final int FRAME = 40;

    /** The smallest step the instrument moves in; goes with the series one day. */
    private static final double TICK = 5.0;

    /** The sessions of real ticks, at most three of them in memory. */
    private final transient TickLibrary ticks;

    /** Where the exported sessions are, for the folds that do not go through the library. */
    private final transient java.nio.file.Path tickFolder;

    /**
     * What breaks a bar into the prices inside it.
     *
     * <p>The exchange's own trades under a tick feed, the invented walk under a
     * bar feed, and nothing else decides it -- not what happens to be on disk
     * for a given day. Held rather than passed straight through so that
     * {@link #isRecorded} can answer from the wiring instead of guessing at it
     * from the same two facts the wiring was built out of.</p>
     */
    private final transient TickPath animation;

    /**
     * True until the first session's ticks are in memory.
     *
     * <p>Volatile: set on the interface thread, read by it too, but written
     * from a callback that starts on the loader's thread before it hops over.
     * </p>
     */
    private volatile boolean preparing;

    /** The series being replayed, read on first use. */
    private transient PriceSeries series;

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final DateTimeFormatter DAY_AND_CLOCK =
            DateTimeFormatter.ofPattern("dd/MM HH:mm:ss");

    /** The trading day this stands in for, until a real loader exists. */
    private static final LocalTime OPEN = LocalTime.of(9, 0);

    private static final int MINUTES = 565;

    private final String instrument;

    /**
     * What is playing: a bar series, or one market's ticks from one export.
     *
     * <p>The whole point of naming it in one place. A replay used to be told a
     * series AND a tick source, which left "what is on screen" unanswerable:
     * minute bars out of the candle file, animated inside by real ticks where
     * they existed and by an invented walk where they did not. Now the choice
     * IS the answer.</p>
     */
    private final transient ReplayFeed feed;

    private final LocalDate date;

    private final LocalDate until;

    private final ReplaySeries live;

    private final transient List<Runnable> watchers = new ArrayList<>();

    /** Told when this session ends, so each chart can take itself back. */
    private final transient List<Runnable> endings = new ArrayList<>();

    private final Timer timer;

    private int speed = 1;

    /**
     * @param instrument what is being replayed
     * @param date the session
     */
    /** How many sessions may be played in one go, so a typo cannot ask for a decade. */
    public static final int MOST_SESSIONS = 250;

    public ReplaySession(String instrument, LocalDate date) {
        this(instrument, date, ReplayPreferences.historyDays());
    }

    public ReplaySession(String instrument, LocalDate date, int historyDays) {
        this(instrument, date, date, historyDays);
    }

    /**
     * @param instrument what is being replayed
     * @param date the first session to play
     * @param until the last session to play, inclusive
     * @param historyDays how many sessions before the first to show already drawn
     *
     * <p>A range and not a day: watching one session tells you what that session
     * did, and a week tells you whether the thing you saw happens. Weekends are
     * skipped, so "Monday to Monday" is six sessions and not eight.</p>
     */
    public ReplaySession(String instrument, LocalDate date, LocalDate until, int historyDays) {
        this(ReplayFeed.of(instrument), date, until, historyDays,
                br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.ticksOf(
                        rootOf(instrument)));
    }

    /**
     * @param tickFolder where the exported sessions are
     *
     * <p>Package-visible so a test can point at a folder it wrote itself.
     * Without the seam the only way to test the waiting is to depend on which
     * months happen to be exported on the machine running the suite.</p>
     */
    ReplaySession(String instrument, LocalDate date, LocalDate until, int historyDays,
                  java.nio.file.Path tickFolder) {
        this(ReplayFeed.of(instrument), date, until, historyDays, tickFolder);
    }

    /**
     * @param feed what to play: a bar series, or a market's ticks from an export
     *
     * <p>The one constructor that does the work. Everything else names a feed
     * for it.</p>
     */
    ReplaySession(ReplayFeed feed, LocalDate date, LocalDate until, int historyDays,
                  java.nio.file.Path tickFolder) {
        this.feed = feed;
        this.instrument = feed.isTicks() ? feed.instrument() : feed.series();
        this.date = date;
        this.until = until == null || until.isBefore(date) ? date : until;

        // Before the days, because a tick feed builds its days OUT of the
        // library: every bar on screen is folded from the trades that printed,
        // the sessions before the chosen one included.
        this.tickFolder = tickFolder;
        this.ticks = new TickLibrary(tickFolder, feed.instrument(),
                feed.isTicks() ? feed.source() : TickSource.METATRADER);

        // The days before, already drawn, so the chart does not open on an empty
        // screen -- and so the first decision of the session is taken with the
        // same context the reader would have had that morning.
        //
        // On a tick feed these come from the TICKS too, folded into minute
        // candles and kept as a copy. Nothing about them is animated -- a day
        // already over is a day to look at, and the copy is what lets its
        // ninety megabytes of ticks go the moment the fold is done.
        //
        // Drawing them from the candle file instead would be cheaper and wrong
        // in a way nobody would see: it is a different measurement of the same
        // hours, and the chart would be showing two of them side by side.
        List<PriceSeries> parts = new ArrayList<>();
        int before = 0;

        for (LocalDate day : sessionsBefore(date, historyDays)) {
            PriceSeries session = dayOf(day);

            parts.add(session);
            before += session.size();
        }

        int playable = 0;

        for (LocalDate day : sessionsIn(date, this.until)) {
            parts.add(dayOf(day));
            playable++;
        }

        if (playable == 0) {
            // A range holding no session at all -- a single Saturday, say. The
            // day asked for is played anyway: refusing would leave the reader
            // with an empty transport and no reason given.
            parts.add(dayOf(date));
        }

        // The synthetic walk is consulted through the setting, not captured, so
        // turning it off takes effect on a replay already open instead of on
        // the next one.
        SyntheticTicks invented = new SyntheticTicks(TICK, date.toEpochDay());
        TickPath path = (bars, index) ->
                br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.syntheticTicks()
                        ? invented.pathFor(bars, index) : null;

        // ONE RULE, and it is the feed. A tick feed animates from the exchange's
        // own trades; a bar feed animates from the invented walk, on every day,
        // including the twenty that happen to have been exported.
        //
        // It used to reach for the ticks under a bar feed too -- real where the
        // day was on disk, invented where it was not -- so which one a minute
        // was drawn from depended on what had been imported, and the reader had
        // to remember. Worse, the export it reached for was hard-wired to
        // MetaTrader, so the nine sessions of Profit tape were never used by it
        // while the renko in the same window preferred exactly those. Two panels
        // of one window off two different sources, with nothing saying so.
        // KEPT, so that what is asked about the animation is asked of the
        // animation itself. isRecorded() used to answer from the feed and the
        // file -- the same two facts this line reads -- so a test of the rule
        // could pass with this line reverted. It did, and that is how the
        // toothless test was found.
        this.animation = feed.isTicks() ? new RecordedTicks(ticks, path) : path;
        this.live = new ReplaySeries(ConcatSeries.of(parts), before, before, animation);

        // The FIRST session is waited for, and nothing else is. Everywhere else
        // a quarter-second of synthetic path is better than a quarter-second of
        // frozen animation -- but here the reader is already standing still,
        // waiting to press play, and starting on invented ticks without saying
        // so would be a lie told in the one moment it is easy to avoid.
        //
        // Nothing to wait for under a bar feed: it is not going to use them.
        this.preparing = feed.isTicks() && ticks.has(date);

        if (feed.isTicks()) {
            ticks.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(() -> {
                if (preparing && ticks.at(this.date) != null) {
                    preparing = false;

                    announce();
                }
            }));

            ticks.request(date);
        }

        this.timer = new Timer(FRAME, e -> tick());
        this.timer.setCoalesce(true);
    }

    /**
     * @return the bars of that session
     *
     * <p><b>Synthetic, and seeded by the date</b> so the same day always replays
     * the same way — a replay that changed under the reader between two runs
     * would be useless for comparing decisions. Replaced the moment a real
     * loader exists; the rest of this class does not care which it gets.</p>
     */
    /**
     * @return the sessions before that date, oldest first
     *
     * <p>Weekends are skipped rather than generated and hidden: an empty
     * Saturday in the middle would put a gap in the concatenation that no bar
     * lands on, and the chart would draw a day that never traded.</p>
     */
    private static List<LocalDate> sessionsBefore(LocalDate date, int howMany) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate walking = date;

        while (days.size() < Math.max(0, howMany)) {
            walking = walking.minusDays(1);

            if (walking.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && walking.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                days.add(walking);
            }
        }

        java.util.Collections.reverse(days);

        return days;
    }

    /**
     * @return the trading days from one date to another, inclusive
     *
     * <p>Capped, so a mistyped year asks for two hundred and fifty sessions
     * rather than sixty thousand. Weekends are skipped rather than generated and
     * hidden: an empty Saturday in the middle would put a boundary in the
     * concatenation that no bar lands on.</p>
     *
     * <p>Package-private so the cap can be asked about directly. Asked through a
     * built session instead, it cannot be: the answer is then bounded by how
     * much data the fixture happens to hold, and a fixture of a few days is far
     * under any cap however broken the cap is.</p>
     */
    static List<LocalDate> sessionsIn(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();

        for (LocalDate walking = from;
                !walking.isAfter(to) && days.size() < MOST_SESSIONS;
                walking = walking.plusDays(1)) {

            if (walking.getDayOfWeek() != java.time.DayOfWeek.SATURDAY
                    && walking.getDayOfWeek() != java.time.DayOfWeek.SUNDAY) {
                days.add(walking);
            }
        }

        return days;
    }

    /**
     * @return that session's bars, from the series being replayed
     *
     * <p>Until 03/09/2026 this returned a random walk seeded by the date, with
     * a comment saying it would be replaced when a real loader existed. The
     * loader had existed for a while, and nobody noticed: the replay animated
     * INVENTED candles, and on the twenty days that have ticks it animated the
     * exchange's real ticks over the top of them. Two markets in one window,
     * with nothing on screen saying so.</p>
     *
     * <p>An empty series when the series has no such session — a Saturday, a
     * holiday, a day outside its range. The caller draws nothing for it rather
     * than a day that never traded.</p>
     */
    private PriceSeries dayOf(LocalDate day) {
        if (feed.isTicks()) {
            return foldedFromTicks(day);
        }

        PriceSeries whole = baseSeries();

        if (whole == null || whole.size() == 0) {
            return PriceSeries.empty();
        }

        return SegmentedSeries.of(whole, new Segment(instrument, day, day),
                br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone());
    }

    /**
     * @return a session as minute candles, built from its own ticks
     *
     * <p><b>Every day of a tick feed, not only the ones already over.</b> The
     * first version handed the day being played to the chart as one bar per
     * TRADE, and that is not a scale a chart can draw: five million bars where
     * five hundred belong. Worse, it looked like nothing was happening --
     * {@code Timeframe.apply} takes a shortcut at one minute and hands the
     * source straight back, so 13.229 trades got drawn inside the first minute
     * of screen and the reader saw a flat line.</p>
     *
     * <p>Folded, the day is 567 bars. The trades are still what moves it: the
     * ticks of the session being played stay resident and animate the bar that
     * is forming, which is the whole difference between this and reading the
     * candle file — every bar here came from a print, and so did every wiggle
     * inside the last one.</p>
     *
     * <p>A day already over is not animated at all, so it does not need its
     * ticks after the candles exist — and 567 bars are twenty-seven kilobytes
     * where the ticks were ninety megabytes.</p>
     *
     * <p>Read straight from the file and NOT through the library, on purpose.
     * The library caches, and a cache is the one thing that would keep alive
     * exactly what this method exists to let go: one session is held at a time,
     * and it is garbage before the next is read.</p>
     *
     * <p>Minute candles and not the ticks themselves, because at one minute
     * there is nothing to argue about — a minute folded from the trades IS that
     * minute. The 8%-to-27% disagreement that makes candles and ticks
     * incomparable is a renko effect, and it comes from reading a bar as "the
     * high, then the low". A bar is not read that way here.</p>
     */
    private PriceSeries foldedFromTicks(LocalDate day) {
        // Delegated since a chart can be opened ON an export: the fold had been
        // written here first and copying it into FoldedTicks would leave two
        // answers to "what is a session, as bars".
        return br.com.jorge.reis.endeavourneo.domain.market.FoldedTicks.day(
                tickFolder, feed.instrument(), ticks.source(), day, br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone());
    }

    /**
     * @return the whole series, read once for this session
     *
     * <p>Held for as long as the replay lives. It is the same object {@link
     * br.com.jorge.reis.endeavourneo.platform.SeriesCatalog} hands to every chart, so
     * this costs nothing beyond the reference.</p>
     */
    private PriceSeries baseSeries() {
        if (series == null) {
            try {
                series = br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.open(instrument)
                        .orElse(PriceSeries.empty());
            } catch (java.io.IOException e) {
                // A series that will not read leaves an empty replay, which the
                // transport shows as a session with no bars. Better than a
                // window of prices that came from nowhere.
                series = PriceSeries.empty();
            }
        }

        return series;
    }

    /**
     * @return whether the range holds no session at all
     *
     * <p>A Saturday, a holiday, or dates outside what the series covers. The
     * transport says so rather than showing a play button that would do
     * nothing — and rather than the old answer, which was to invent a session
     * that never happened.</p>
     */
    public boolean isEmpty() {
        // THE PLAYABLE PART, not the total. total() is the whole concatenation
        // -- the history days the chart opens with, and then the sessions to
        // play -- so with the default thirty days of history a range holding no
        // session at all still counted some seventeen thousand bars and this
        // answered false. The transport never said "no session in that range",
        // and left the play button lit on a range with nothing to play.
        //
        // The test that should have caught it passes a range with no history at
        // all, which is the one shape where the two answers agree.
        return live.total() - live.origin() == 0;
    }

    /** @return whether the first session's ticks are still being read */
    public boolean isPreparing() {
        return preparing;
    }

    /**
     * @return how many tick sessions this replay is holding
     *
     * <p>Package-visible for the test that proves stopping gives them back.
     * Three of them are 340 MB, so "it was released" has to be something a test
     * can actually see, not something the code merely claims.</p>
     */
    int residentTicks() {
        return ticks.residentCount();
    }

    /**
     * @return the ticks held for that day, or null if they are not in yet
     *
     * <p>Beside the one that counts them, and for the sibling reason: counting
     * proves the loading finished, and this proves WHICH file it finished
     * loading. Both sources can hold the same session of the same market, so a
     * replay handed the wrong one would still play -- with another market's
     * worth of numbers and nothing on screen to say so.</p>
     */
    TickSeries residentAt(LocalDate day) {
        return ticks.at(day);
    }

    /** @return whether this day is replayed from the exchange's own ticks */
    /**
     * @return the export being played, or null when bars are
     *
     * <p>So the chart's bricks come from the same export the transport is
     * playing. Working it out again in the chart would let it answer a question
     * this already answered -- and answer it differently.</p>
     */
    /**
     * @return how the feed being played names itself
     *
     * <p>For the chart's own header, which otherwise goes on naming the series
     * the chart was opened with -- "winfull-1m" while every bar on screen came
     * from the Profit tape.</p>
     */
    public String feedLabel() {
        return feed.label();
    }

    public TickSource playing() {
        return feed.isTicks() ? feed.source() : null;
    }

    /**
     * @return whether this replay is animating the exchange's own trades
     *
     * <p>A question about the FEED, not about what happens to be on disk. A bar
     * feed answers no even on the twenty days that were exported: it plays the
     * candle series, and the path inside each bar is the invented walk. One
     * rule, so a reader never has to remember which minutes came from where.</p>
     */
    public boolean isRecorded() {
        return animation instanceof RecordedTicks && ticks.has(date);
    }

    /**
     * @return the market the tick files are named after
     *
     * <p>The MARKET, not the export. The chart may be showing {@code
     * winfull-1m}, {@code winn-1m} or {@code winfut-1m}, and the ticks of a
     * given day are the same ticks for all three: they are what the exchange
     * printed, not what one export happened to stitch. So the sessions are
     * {@code win-2021-01-04.bin} and the lookup goes through the same declared
     * mapping the tree groups by.</p>
     *
     * <p>The first version cut the name at the first dash, which gave {@code
     * winfut} — right by accident while the only series with ticks was called
     * that, and wrong the moment the source was renamed.</p>
     */
    static String rootOf(String instrument) {
        return br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.groupOf(instrument);
    }

    /**
     * @return the market as it is NAMED, which is not how it is stored
     *
     * <p>See {@link br.com.jorge.reis.endeavourneo.platform.Messages#market}.
     * A feed of bars answers with its series name, which is already a name a
     * reader recognises.</p>
     */
    public String name() {
        return br.com.jorge.reis.endeavourneo.platform.Messages.market(instrument);
    }

    public String instrument() {
        return instrument;
    }

    public LocalDate date() {
        return date;
    }

    public LocalDate until() {
        return until;
    }

    /** @return true when more than one session is being played */
    public boolean isRange() {
        return !until.equals(date);
    }

    /** @return the range as the chart title writes it */
    public String rangeText() {
        // THROUGH THE BUNDLE, and in the same date format as the rest of the
        // window. This built the string in Java with the Portuguese preposition
        // hard coded, so the English build titled a chart "WINFUT 2026-08-31 a
        // 2026-09-04" -- and in a third date format, next to dd/MM/yyyy in the
        // picker and dd/MM in the end label.
        String from = date.format(DatePicker.TYPED);

        return isRange()
                ? br.com.jorge.reis.endeavourneo.platform.Messages.get(
                        "replay.range", from, until.format(DatePicker.TYPED))
                : from;
    }

    /** @return the series to hand a chart; it grows as the clock runs */
    public PriceSeries series() {
        return live;
    }

    public boolean isPlaying() {
        return timer.isRunning();
    }

    public int speed() {
        return speed;
    }

    /** @param multiple how many times faster than the market to run */
    public void setSpeed(int multiple) {
        this.speed = Math.max(1, Math.min(multiple, FASTEST));

        announce();
    }

    /** @param watcher told after every advance, so it can redraw */
    public void watch(Runnable watcher) {
        if (watcher != null) {
            watchers.add(watcher);
        }
    }

    public void forget(Runnable watcher) {
        watchers.remove(watcher);
    }

    /**
     * @param ending the one handed to {@link #whenEnded}
     *
     * <p>The watchers had a way out and the endings did not. A chart that let
     * the session go -- closed, or had the replay detached -- stayed on this
     * list until the session itself stopped, holding a reference to a window
     * that is gone and a callback that will run into it.</p>
     */
    public void forgetEnding(Runnable ending) {
        endings.remove(ending);
    }

    public void toggle() {
        if (preparing) {
            // Nothing, and the button is disabled anyway. Belt and braces: a
            // keyboard shortcut or a restored state could reach this.
            return;
        }

        if (timer.isRunning()) {
            timer.stop();
        } else {
            // Pressing play at the close starts the day again rather than doing
            // nothing: a dead button on a finished session reads as a bug.
            if (live.finished()) {
                live.seek(live.origin());
            }

            timer.start();
        }

        announce();
    }

    public void pause() {
        timer.stop();
        announce();
    }

    /** @param bars a nudge, forwards or back, while paused */
    public void step(int bars) {
        if (bars >= 0) {
            live.advance(bars);
        } else {
            live.seek(live.revealed() + bars);
        }

        announce();
    }

    public void seekFraction(double fraction) {
        live.seekFraction(fraction);
        announce();
    }

    public double progress() {
        return live.progress();
    }

    /**
     * @return the session clock, as the reader would have seen it at the time
     *
     * <p>The date comes along only when more than one session is being played.
     * On a single day it would be the same six characters all the way through,
     * taking room from the one part that moves.</p>
     */
    public String clockText() {
        java.time.ZonedDateTime at =
                Instant.ofEpochMilli(live.clock()).atZone(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone());

        return isRange() ? at.format(DAY_AND_CLOCK) : at.toLocalTime().format(CLOCK);
    }

    /**
     * @return where the replay is going, so the transport shows the far end
     *
     * <p><b>Read off the session, not assumed.</b> It used to be nine in the
     * morning plus a fixed number of minutes, which says the same thing for a
     * full day, a half day before a holiday, and a session the exchange stopped
     * early. The number was invented, and the fixture that tested it was written
     * to match the invention — so the two agreed and neither was ever compared
     * with a market.</p>
     */
    public String endText() {
        java.time.LocalTime close = Instant.ofEpochMilli(live.end())
                .atZone(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone()).toLocalTime();

        if (!isRange()) {
            return close.format(DateTimeFormatter.ofPattern("HH:mm"));
        }

        return until.format(DateTimeFormatter.ofPattern("dd/MM"))
                + " " + close.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    /** @param ending run when the session ends, to give a chart back its data */
    public void whenEnded(Runnable ending) {
        if (ending != null) {
            endings.add(ending);
        }
    }

    /**
     * Ends the session and hands every chart back to itself.
     *
     * <p>The charts are told BEFORE the watchers are dropped: a chart that took
     * itself back would otherwise still be subscribed to a clock that no longer
     * runs, and would sit there waiting for a tick that never comes.</p>
     */
    /**
     * @return whether this session has been stopped for good
     *
     * <p>Apart from paused. A paused session is still a session: the chart is
     * still showing its day and pressing play carries on from where it was.
     * A stopped one is over, the charts have their own data back, and the
     * transport is free to be set up for another one.</p>
     */
    public boolean isStopped() {
        return stopped;
    }

    private boolean stopped;

    public void stop() {
        stopped = true;

        timer.stop();

        // Three sessions of ticks are 340 MB. Holding them after the replay is
        // over would be the one place this design leaks.
        ticks.close();

        for (Runnable ending : new ArrayList<>(endings)) {
            ending.run();
        }

        endings.clear();
        watchers.clear();
    }

    private void tick() {
        live.advanceMarketTime((long) FRAME * speed);

        askAhead();

        if (live.finished()) {
            // The day is over. Stopping here rather than letting the timer run
            // on an unchanging series keeps the play button honest.
            timer.stop();
        }

        announce();
    }

    /**
     * Asks for the session the clock is on, and the one after it.
     *
     * <p><b>The library only queues three days around what it is asked for</b>,
     * and it was asked once, in the constructor, for the first day. From the
     * third session of a range onwards nothing had ever requested the file, and
     * {@code at} does not read the disk by design -- so the animation quietly
     * fell back to the invented walk while {@code isRecorded} went on saying the
     * exchange's own trades were on screen.</p>
     *
     * <p>Cheap when the day is already resident: {@code request} drops out at
     * {@code at(day) != null}. The chart's own renko does the same thing with
     * its own library, one floor down.</p>
     */
    private void askAhead() {
        if (!feed.isTicks()) {
            // A bar feed animates from the invented walk by choice, and asking
            // for files it will not read is work for nothing.
            return;
        }

        LocalDate playing = Instant.ofEpochMilli(live.clock())
                .atZone(br.com.jorge.reis.endeavourneo.domain.market.Timeframe.defaultZone())
                .toLocalDate();

        ticks.request(playing);
        ticks.request(playing.plusDays(1));
    }

    private void announce() {
        // A copy, because a watcher that detaches itself while being told would
        // otherwise change the list underneath the loop.
        for (Runnable watcher : new ArrayList<>(watchers)) {
            watcher.run();
        }
    }
}
