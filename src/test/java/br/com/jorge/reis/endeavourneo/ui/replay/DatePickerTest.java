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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O campo de data: o que ele avisa, e o que ele guarda calado.
 *
 * <p>A diferença entre o leitor <b>escolher</b> uma data e o programa
 * <b>devolver</b> uma. O ouvinte do documento não distingue os dois — ele vê
 * texto mudando dos dois jeitos — e foi por isso que um painel restaurando o
 * intervalo salvo o salvava de novo na mesma hora. Parecia inofensivo, porque o
 * valor era o mesmo; não era, porque a restauração pode chegar de uma carga em
 * segundo plano que termina depois de a janela fechar.</p>
 */
@DisplayName("O campo de data")
class DatePickerTest {

    private static final LocalDate DIA = LocalDate.of(2026, 9, 10);

    @Test
    @DisplayName("escolher uma data avisa")
    void choosingADateTellsSomeone() {
        DatePicker picker = new DatePicker(DIA);
        AtomicInteger avisos = new AtomicInteger();

        picker.onChange(avisos::incrementAndGet);
        picker.setDate(DIA.plusDays(1));

        assertTrue(avisos.get() > 0, "mudar a data nao avisou ninguem");
        assertEquals(DIA.plusDays(1), picker.date(), "a data nao mudou");
    }

    @Test
    @DisplayName("DEVOLVER uma data nao avisa, e mesmo assim a data muda")
    void puttingADateBackTellsNobody() {
        DatePicker picker = new DatePicker(DIA);
        AtomicInteger avisos = new AtomicInteger();

        picker.onChange(avisos::incrementAndGet);
        picker.setQuietly(DIA.plusDays(1));

        assertEquals(0, avisos.get(), "devolver a data avisou " + avisos.get() + " vez(es)");
        assertEquals(DIA.plusDays(1), picker.date(), "a data nao mudou");
    }

    @Test
    @DisplayName("o silencio e so daquela vez: a proxima escolha volta a avisar")
    void thesilenceIsOnlyForThatOneCall() {
        DatePicker picker = new DatePicker(DIA);
        AtomicInteger avisos = new AtomicInteger();

        picker.onChange(avisos::incrementAndGet);
        picker.setQuietly(DIA.plusDays(1));
        picker.setDate(DIA.plusDays(2));

        // Um silencio que nao acaba e pior que nenhum: o campo ficaria mudo
        // para sempre depois da primeira restauracao, e a escolha do leitor
        // deixaria de ser guardada.
        assertTrue(avisos.get() > 0, "o campo ficou mudo depois de uma restauracao");
    }

    @Test
    @DisplayName("so aceita dia que a serie tem, quando lhe dizem quais sao")
    void itonlyAcceptsDaysTheSeriesHas() {
        DatePicker picker = new DatePicker(DIA);

        assertTrue(picker.accepts(LocalDate.of(2026, 9, 11)),
                "sem lista de dias, recusou uma sexta-feira");

        picker.setSessions(new TreeSet<>(java.util.List.of(DIA)));

        assertTrue(picker.accepts(DIA), "recusou o unico dia que existe");
        assertTrue(!picker.accepts(LocalDate.of(2026, 9, 11)),
                "aceitou um dia util que a serie nao tem");
    }
}
