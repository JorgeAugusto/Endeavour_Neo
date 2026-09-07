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
package br.com.jorge.reis.endeavourneo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import br.com.jorge.reis.endeavourneo.domain.market.Timeframe;
import br.com.jorge.reis.endeavourneo.platform.Settings;
import java.nio.file.Path;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The calendar a day is cut by, and the setting that was never written.
 *
 * <h2>A correction built and never armed</h2>
 *
 * <p>{@code data.zone} tells the domain where a day begins. The launcher READ
 * it and nothing on earth WROTE it — no {@code put}, no preferences page, no
 * default — so every machine fell through to its own clock, which is precisely
 * the defect the comment beside that line describes.</p>
 *
 * <p>It survived because the author's machine is in the market's zone: the whole
 * class of defect is invisible where the code is written and real everywhere
 * else. That is also why no test caught it — until this one, which hands the
 * launcher a settings file of its own instead of the reader's.</p>
 */
@DisplayName("O fuso do mercado por omissao")
class MarketZoneDefaultTest {

    /** A settings file nobody has touched, the way a fresh install finds one. */
    private static Settings fresh(Path folder) {
        return Settings.at(folder.resolve("settings.properties"), "a test");
    }

    @Test
    @DisplayName("uma instalacao nova cai no fuso do MERCADO, nao no da maquina")
    void afreshInstallUsesTheMarketZone(@TempDir Path folder) {
        ZoneId was = Timeframe.defaultZone();

        try {
            // Somewhere the market certainly is not, so falling through to the
            // machine cannot be mistaken for the right answer.
            Timeframe.useZone(ZoneId.of("Pacific/Auckland"));

            Launcher.useMarketZone(fresh(folder));

            assertEquals(ZoneId.of("America/Sao_Paulo"), Timeframe.defaultZone(),
                    "a fresh install did not land on the market calendar");
        } finally {
            Timeframe.useZone(was);
        }
    }

    @Test
    @DisplayName("e o valor fica escrito, para o leitor poder ve-lo e troca-lo")
    void theDefaultIsWrittenDown(@TempDir Path folder) {
        ZoneId was = Timeframe.defaultZone();

        try {
            Settings settings = fresh(folder);

            Launcher.useMarketZone(settings);

            assertEquals("America/Sao_Paulo", settings.get("data.zone", null),
                    "the default was applied and never written: a setting that "
                            + "exists only as a default is one nobody knows they have");
        } finally {
            Timeframe.useZone(was);
        }
    }

    @Test
    @DisplayName("o que o leitor escreveu ganha do padrao")
    void whatTheReaderWroteWins(@TempDir Path folder) {
        ZoneId was = Timeframe.defaultZone();

        try {
            Settings settings = fresh(folder);

            settings.put("data.zone", "Europe/Lisbon");

            Launcher.useMarketZone(settings);

            assertEquals(ZoneId.of("Europe/Lisbon"), Timeframe.defaultZone(),
                    "the default overrode a choice the reader had made");
        } finally {
            Timeframe.useZone(was);
        }
    }
}
