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

/**
 * What a round trip costs, in points of the index.
 *
 * <p>Measured, not arbitrated. The previous project spent a long time on a cost
 * of 12,5 points that had been picked rather than counted; the measurement came
 * out at <b>6,5 points for a complete operation of one contract</b>, and every
 * conclusion drawn against the old number has to be re-read against this one.
 * A strategy that clears 8 points per trade is a loser under the guess and a
 * winner under the measurement.</p>
 *
 * <h2>It is charged per contract, and half at a time</h2>
 *
 * <p>His robots do not trade one contract. They open a ladder and give it back
 * in pieces, so a cost charged once per "operation" would undercharge a trade
 * that entered with three and exited in three parts. Half the round trip is
 * charged on every contract that changes hands, in or out — which adds up to
 * exactly the round trip for a contract that goes in once and out once, and to
 * the honest number for every other shape.</p>
 *
 * <h2>The dangerous setting is not zero</h2>
 *
 * <p>A backtest run with no cost at all looks obviously wrong, and someone
 * notices. The one that does damage is a cost that is <i>almost</i> right — the
 * measurement applied to a series it was not measured on. <b>6,5 points is a
 * number for the raw series</b>; the adjusted series inflates old years by up to
 * 67%, and a cost in points of an inflated price is a different cost. There is
 * no flag here that can enforce that; the report has to say which series it ran
 * on.</p>
 *
 * @param pointsPerRoundTrip points charged for one contract in and out again
 */
public record Costs(double pointsPerRoundTrip) {

    /** The measured cost of a round trip on the raw WIN series: 6,5 points. */
    public static final Costs MEASURED = new Costs(6.5);

    /** No cost at all — for a test that wants to read the gross figure. */
    public static final Costs NONE = new Costs(0);

    public Costs {
        if (!Double.isFinite(pointsPerRoundTrip) || pointsPerRoundTrip < 0) {
            throw new IllegalArgumentException("a round trip cannot cost " + pointsPerRoundTrip + " points");
        }
    }

    /**
     * What one fill costs.
     *
     * @param contracts how many changed hands
     * @return points, always positive; the caller subtracts
     */
    public double ofFill(int contracts) {
        return pointsPerRoundTrip / 2 * contracts;
    }

    /** @return whether this is the measured cost of the raw series */
    public boolean measured() {
        return pointsPerRoundTrip == MEASURED.pointsPerRoundTrip;
    }
}
