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
package br.com.jorge.reis.endeavourneo.domain.market;

import java.io.IOException;

/**
 * The file was read, and it is not one of this program's.
 *
 * <h2>Why a type and not just a message</h2>
 *
 * <p>{@code isSeries}, {@code isTicks} and {@code isTape} answer a boolean, and
 * they used to answer it by catching every {@code IOException} the reader could
 * throw. Two very different things came out as the same {@code false}: a file
 * that is somebody else's — which is the ordinary answer, and worth no words —
 * and a file of ours that could not be READ, because the network disk went away
 * or the permission changed. The second one made a series disappear from the
 * listing with nothing said, which is the failure these formats spend all their
 * refusals to avoid.</p>
 *
 * <p>So the readers throw this for "not ours" — wrong mark, a version this does
 * not read, a header that does not describe a file — and a plain {@code
 * IOException} for everything that went wrong while reading. The predicates
 * answer false to both and say something about the second.</p>
 *
 * <p>It IS an {@code IOException}: every caller that only wants "it did not
 * read" goes on catching what it always caught.</p>
 */
final class NotOurs extends IOException {

    private static final long serialVersionUID = 1L;

    NotOurs(String message) {
        super(message);
    }
}
