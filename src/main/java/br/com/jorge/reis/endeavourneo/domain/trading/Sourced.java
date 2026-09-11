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
package br.com.jorge.reis.endeavourneo.domain.trading;

import br.com.jorge.reis.endeavourneo.domain.market.PriceSeries;

/**
 * A strategy that also reads the bars the scale was built FROM.
 *
 * <p>{@link Strategy#start} hands over the decision bars and nothing else, which
 * is right for almost everything: a strategy reads the chart it is being run on.
 * But an indicator can be pinned to a scale of its own — "the stochastic of
 * eight on one minute" — and that reading has to survive the chart being put on
 * five minutes, or on renko, where there is no minute anywhere in the series.
 *
 * <h2>Why the source and not "one minute"</h2>
 *
 * <p>Because one minute is not always available. What exists is the series as it
 * is <b>stored</b>, cut to the recorte, and everything coarser is built from it.
 * A strategy that wants minutes aggregates them itself, which is a no-op on a
 * base already kept at one minute and the only honest answer on one that is not.
 *
 * <h2>It is not the executed series</h2>
 *
 * <p>In tick mode the run executes against a tick path built from these same
 * bars, and that path is a different thing again: it exists so orders fill at
 * prices inside a bar, not so indicators can be read on it. An indicator over
 * synthetic ticks would be reading the random walk, not the market.
 *
 * <p>Handed over before {@link Strategy#start}, so a strategy may use it there.
 */
@FunctionalInterface
public interface Sourced {

    /**
     * @param source the bars as stored, cut to the recorte — never null
     */
    void sourcedFrom(PriceSeries source);
}
