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
package br.com.jorge.reis.endeavourneo.platform;

/**
 * The handle a running job uses to report progress and to notice cancellation.
 *
 * <p><b>Cancellation is cooperative, and it has to be.</b> Java has no safe way
 * to stop a thread from outside: {@code Thread.stop} was deprecated because it
 * releases locks mid-update and leaves shared state torn. So a job that wants to
 * be cancellable must ask — and one that never asks simply runs to the end.</p>
 *
 * <p>Call {@link #cancelled()} once per outer loop iteration. Once per inner
 * iteration is waste; once at the very end is useless.</p>
 */
public interface Progress {

    /**
     * @param fraction from 0 to 1; anything outside that range leaves the bar
     *        INDETERMINATE, which is the honest picture for work that cannot
     *        say how far along it is
     *
     * <p>Safe to call from the job's own thread — the implementation marshals
     * to the interface thread itself. Reporting more often than the screen can
     * repaint is harmless but pointless; once per percent is plenty.</p>
     */
    void report(double fraction);

    /**
     * @return whether someone asked this job to stop
     *
     * <p>Returning true is a request, not an order. The job decides how to
     * unwind — the right answer is usually to return what it has, not to throw.</p>
     */
    boolean cancelled();

    /** @param text a short line describing the current stage, or null */
    void say(String text);
}
