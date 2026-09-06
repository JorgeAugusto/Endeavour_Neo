# A4 — ui/chart: holder, layout, legenda, eixos, estilo

**Arquivos lidos integralmente:** 21 (~5.167 linhas)
**Achados:** 3 ALTA, 9 MÉDIA, 9 BAIXA

---

## Os quatro focos, respondidos

| foco | veredito |
|---|---|
| Viewport — aritmética de coordenadas | **Limpo.** `x(i)` e `barAt(x)` são inversos exatos; `y` e `priceAt` idem. Série vazia, `high == low`, largura zero e `firstBar` além do fim estão todos guardados. Não achei off-by-one nem coordenada calculada de dois jeitos. O único desconforto é de contrato, não de aritmética: `barAt` devolve índice ≥ `series.size()` na área vazia à direita — deliberado, mas é o que alimenta o A4-1 e obriga todo chamador a re-guardar. |
| ChartLayouts — o formato dá a volta? | **Dá a volta, com duas ressalvas.** Testei formato→leitura mentalmente contra `ChartLayoutTest` (5 casos de painel, inclusive três indicadores num painel e dois painéis). Linha malformada é pulada sem derrubar as outras; campo faltando, número inválido e lista de parâmetros vazia estão cobertos; índices fora de ordem ou com buracos reagrupam certo porque `formatPanes` sempre escreve a posição. O `\n` dentro do valor sobrevive ao `Settings.escape`/`Properties.load` — conferi. As ressalvas são A4-4 (altura lida da ÚLTIMA linha, o comentário diz a primeira) e A4-5 (o teto de 40 corta em silêncio, justificado por um limite que não existe). Painel sem indicador nenhum some — mas `StudyStack.restore` também o descartaria, então não é perda. |
| Código de hoje (Reordering, LayoutBar, OverlayLegend, ChartHeader) | **`Reordering` está correto** e bem testado (`LayoutOrderTest`, 6 casos) — tentei derrubar o `list.add(clamp, list.remove(from))` pela ordem de avaliação dos argumentos e não caiu (o `size()` é lido ANTES do `remove`, e o clamp é inócuo). **`LayoutBar` tem A4-6 e A4-10.** **`OverlayLegend` tem o achado mais grave da área, A4-1** — a ordem de arrasto em si está certa e é a mesma que `ChartCanvas` desenha. **`ChartHeader` está limpo** no que o `SegmentChipTest` cobre, mas é o gatilho do A4-2. |
| Sessions duplicado entre ui e domain | **Não é duplicação, e NÃO divergiram na virada de pregão.** Os dois usam exatamente `Instant.ofEpochMilli(t).atZone(zone).toLocalDate()` — mesma fronteira, dia civil na zona local. São perguntas diferentes: `ui/chart/Sessions` responde "quanto o preço andou desde o fechamento anterior", `domain/market/Sessions` responde "quais dias a série tem". Não é ALTA. Fica o A4-12 (nome colidindo, e aritmética de domínio morando em `ui`). |

---

## Achados ALTA

### A4-1. A legenda mostra "—" em vez do valor de cada indicador sempre que o mouse não está sobre o gráfico — que é o tempo todo em que o leitor está olhando para ela

`OverlayLegend.java:201`

```java
            int bar = canvas.hoveredBar();

            paintDrop(g, overlays.size());

            for (int i = 0; i < overlays.size(); i++) {
                paintRow(g, overlays.get(i), bar, i, i * ROW_HEIGHT + 2);
            }
```

O javadoc da própria classe, `OverlayLegend.java:57-59`, promete outra coisa:

```java
 * <p>The values shown are those of the bar under the cursor, falling back to the
 * last bar when the mouse is elsewhere. That fallback is what makes the list
 * useful without interaction: a glance reports where every line is right now.</p>
```

O recuo prometido existe, está pronto, e é usado pelo componente irmão — `StudyPane.java:705`:

```java
        return under >= 0 ? under : canvas.lastVisibleBar();
```

E `ChartCanvas.lastVisibleBar()` (linha 1356) diz para quem ele foi escrito:

```java
    /** @return the last bar on screen, which is what a legend reads when the mouse is away */
    public int lastVisibleBar() {
```

**Problema.** `hoveredBar()` devolve −1 quando `cursor == null` (`ChartCanvas.java:1324`), e o cursor é zerado em `mouseExited` (`ChartCanvas.java:2434`). A legenda fica ACIMA do canvas: para chegar até ela o ponteiro sai do gráfico, o cursor vira null, e `paintRow` chama `overlay.valueAt(-1)`. As duas implementações devolvem NaN nesse índice (`MovingAverage.java:282`, `BollingerBands.java:350`), e `paintRow` imprime o travessão:

```java
            String text = Double.isFinite(values[line]) ? format.format(values[line]) : "—";
```

Piora: `onCursorChanged` é um campo de listener ÚNICO (`ChartCanvas.java:283`, `624`), já ocupado por `MainWindow.java:456`. A legenda não é avisada quando o cursor anda, e nem poderia ser sem despejar a barra de status. Ou seja, mesmo a metade "sob o cursor" só funciona por acidente de repintura.

**Consequência.** Um gráfico com três médias e uma Bollinger mostra `EMA [17] —`, `EMA [55] —`, `EMA [200] —` em praticamente toda situação em que o leitor olha para a legenda. A frase que justifica a existência da lista — "a glance reports where every line is right now" — é falsa na tela. Quando a legenda repinta por outro motivo com o cursor ainda posto, mostra o valor de uma barra que o leitor não sabe qual é.

**Correção.** `int bar = canvas.barUnderCursor(); if (bar < 0) bar = canvas.lastVisibleBar();` — a mesma linha do `StudyPane`. Melhor ainda: extrair essa escolha para um método único do canvas, para não haver uma terceira cópia. E trocar `onCursorChanged` por lista de ouvintes, senão a legenda continua não repintando ao mover o cursor.

**Tentei refutar assim:** procurei um `invokeLater`, um `Timer` ou uma repintura em cascata que desse à legenda um `bar` válido; procurei um `hoveredBar()` que devolvesse a última barra em vez de −1; procurei se `mouseExited` do canvas era anulado por algo. Nada. Depois procurei um teste: `OverlayOrderTest` cobre só a ordem de arrasto, e nenhum teste da área lê o valor pintado numa linha da legenda. E `RandomWalkSeries(200, 100.0)` nos testes nem chega a pintar.

---

### A4-2. Passar o mouse sobre o nome do instrumento varre a série inteira, convertendo cada barra em `LocalDate`, na EDT, a cada pixel

`ChartHeader.java:359-362`

```java
            setToolTipText(overTheName
                    ? SeriesSummary.html(canvas.series(), source(), canvas.periodLabel(),
                            canvas.isFromTicks())
                    : Messages.get("period.hint"));
```

Isso está dentro de `mouseMoved`. O que ele chama, `SeriesSummary.java:126-134`:

```java
        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();

            if (!day.equals(seen)) {
                seen = day;

                days++;
            }
        }
```

O custo dessa varredura está medido no gêmeo de domínio, `domain/market/Sessions.java:44-46`:

```java
 * <p>Measured on the six-year source: 824.881 bars walked in 39-102 ms to find
 * 1.494 sessions. Fine once; not fine per repaint. Whoever asks should hold the
 * answer rather than ask again.</p>
```

**Problema.** `mouseMoved` dispara dezenas de vezes por segundo, e `setToolTipText` recebe a string já construída — não é preguiçoso. Cada evento paga a varredura completa. Na série de ~1.050.000 barras de 1 minuto que é o caso NORMAL, são ~1,05 milhão de `Instant.ofEpochMilli().atZone().toLocalDate()` por movimento do ponteiro, sobre a EDT. O aviso "not fine per repaint" foi escrito e não foi seguido.

**Consequência.** Arrastar o ponteiro pelo cabeçalho congela a janela: nada mais repinta, nada mais responde a clique, e o leitor não tem como ligar a travada ao ato de passar por cima do nome. Em séries curtas de teste (200 barras) é invisível, e por isso passou.

**Correção.** Calcular o resumo uma vez por série e guardar. `SeriesSummary.html` é chamado de um só lugar; basta o `ChartHeader` guardar o texto em campo e invalidá-lo em `onSeriesChanged` (que já existe e já é usado no `ChartHolder`). Ou, mais barato ainda, só montar o tooltip quando `overTheName` MUDA de false para true, em vez de a cada movimento.

**Tentei refutar assim:** verifiquei se `hot` é pequeno o bastante para o caso ser raro — não é: `hot` cobre o nome mais o período, a maior parte da faixa de 20 px. Verifiquei se `setToolTipText` avalia preguiçosamente — não, recebe `String`. Verifiquei se `series()` no cabeçalho poderia ser um recorte curto — não: é a série do gráfico, e o `SeriesSummaryTest` inclusive testa `summary.sessions` contra a série toda. Verifiquei se havia cache em `SeriesSummary` — não há campo nenhum, a classe é utilitária pura.

---

### A4-3. `LineStyle` aloca dois vetores do tamanho das barras visíveis a cada repintura, e as barras visíveis podem ser a série inteira

`LineStyle.java:54-60`

```java
        int[] xs = new int[to - from];
        int[] ys = new int[to - from];

        for (int i = from; i < to; i++) {
            xs[i - from] = (int) Math.round(viewport.x(i));
            ys[i - from] = (int) Math.round(viewport.y(series.closeAt(i)));
        }
```

O afastamento é limitado apenas pelo tamanho da série — `ChartCanvas.java:2633`:

```java
                visibleBars = Math.max(MINIMUM_VISIBLE_BARS, Math.min(zoomed, series.size()));
```

**Problema.** Com a série de ~1.050.000 barras e o afastamento no máximo, cada repintura aloca 2 × 4 MB = 8,4 MB e entrega ao `drawPolyline` um milhão de pontos para caber em ~1.000 pixels de largura. A repintura acontece a cada movimento do cursor (a cruz repinta o componente inteiro). Nenhum dos dois estilos reduz a amostragem quando `barWidth() < 1`: o `CandleStyle` chega a pular os corpos abaixo de 3 px (`MINIMUM_BODY_WIDTH`), mas ainda emite um `drawLine` por barra. Some-se a isso que `Viewport.of` já percorre as mesmas barras para achar a escala (`Viewport.java:129-132`), e a série é varrida três vezes por quadro.

**Consequência.** "Ver tudo" numa série real trava a janela e alimenta o coletor com 8 MB por quadro. O sintoma aparece exatamente na operação que o leitor faz primeiro ao abrir uma série longa.

**Correção.** Reduzir a amostragem quando `barWidth() < 1`: um ponto por coluna de pixel (mín/máx do que cai naquela coluna), o que também é o desenho correto — hoje um milhão de segmentos são desenhados uns por cima dos outros. E manter os dois vetores como campos reutilizados, redimensionados só quando crescem.

**Tentei refutar assim:** procurei um teto em `visibleBars` menor que o tamanho da série — as quatro atribuições (`1191`, `1206`, `1652`, `2633`) todas limitam por `series.size()`, nenhuma pela largura em pixels. Procurei se `LineStyle` só é usado em séries curtas — é um dos dois botões da barra de ferramentas (`ChartHolder.java:652`). Procurei um clipe que abreviasse o laço — o `ChartStyle` recebe o `g` já clipado, mas o clipe não impede a alocação nem o laço.

---

## Achados MÉDIA

### A4-4. O comentário de `parsePanes` diz que a altura vem da primeira linha do painel; o código a lê da última

`ChartLayouts.java:152-155` e `ChartLayouts.java:221-231`

```java
     * <p>A line per pane would need a separator inside a separator ... The height and the
     * minimised flag repeat on every line of a pane; they are read from the
     * first and the repetition costs nothing.</p>
```

```java
            if (mine != belongsTo && !gathering.isEmpty()) {
                panes.add(new ChartLayout.Pane(List.copyOf(gathering), height, minimised));
                gathering.clear();
            }

            belongsTo = mine;
            height = number(fields[3], 0);
            minimised = Boolean.parseBoolean(fields[4].trim());
```

**Problema.** A descarga do painel acontece ANTES das atribuições, então o painel fechado usa `height`/`minimised` da ÚLTIMA linha lida do grupo, não da primeira.

**Consequência.** Como `formatPanes` repete valores idênticos em todas as linhas de um painel, a ida-e-volta funciona hoje e o comentário nunca foi contestado. Custa na próxima edição: quem for mexer no formato (um arquivo editado à mão, uma migração, um painel cuja altura mude entre linhas) vai confiar na frase e ler o campo errado.

**Correção.** Ou corrigir a frase para "read from the last line of each group", ou — melhor — só atribuir `height`/`minimised` quando `mine != belongsTo`, e aí o comentário passa a ser verdade.

**Tentei refutar assim:** reli o laço três vezes procurando uma atribuição antes da descarga; procurei em `ChartLayoutTest.aPaneComesBackWhole` e `severalInOnePane` um caso com alturas diferentes na mesma linha de painel — não existe, os dois testes usam painéis de altura homogênea, que é justamente o caso que não distingue primeira de última.

---

### A4-5. `ChartLayouts` documenta um armazenamento que não usa, e corta o layout em 40 indicadores por causa de um limite que não existe

`ChartLayouts.java:29` e `ChartLayouts.java:50-51`

```java
 * <p>Stored as text in {@link Preferences}: one line per indicator, fields
 * separated by pipes.
```

```java
    /** Preferences refuses a value longer than this, so a layout has a ceiling. */
    private static final int MAX_ENTRIES = 40;
```

O armazenamento real é `Settings`, um arquivo `.properties` escrito à mão (`Settings.java:257-284`, `escape` própria em `286-319`). `ChartLayouts` não importa `Preferences` nenhum — o `{@link}` não resolve.

**Problema.** Duas afirmações falsas em cima de uma perda de dados silenciosa: `formatPanes` (linha 165) e `format` (linha 269) descartam tudo além do 40º indicador sem avisar ninguém. `java.util.prefs.Preferences` tem de fato `MAX_VALUE_LENGTH = 8192`, mas não é esse o armazenamento; o arquivo de propriedades não tem teto.

**Consequência.** Um leitor com muitos indicadores perde os últimos ao fechar, sem mensagem. E o próximo a mexer aqui vai procurar uma restrição de `Preferences` que não existe em lugar nenhum do projeto.

**Correção.** Trocar `{@link Preferences}` por `{@link Settings}` e ou remover o teto, ou mantê-lo com a justificativa verdadeira (uma linha de arquivo de propriedades enorme) e avisar quando cortar.

**Tentei refutar assim:** conferi se `Settings` delegava a `java.util.prefs` em algum ponto — `grep Preferences` em `Settings.java` não devolve nada; é `Properties` + escrita própria. Conferi se 40 × linha caberia mesmo em 8192 (cabe: ~60 caracteres por linha dá ~2.400), ou seja, nem sob o pressuposto falso o número faz sentido.

---

### A4-6. Remover uma aba à ESQUERDA da selecionada troca o gráfico de layout em silêncio

`LayoutBar.java:484-488`

```java
        layouts.remove(index);
        selected = Math.min(selected, layouts.size() - 1);

        ChartLayouts.save(layouts);
        ChartLayouts.remember(chartKey, layouts.get(selected).name());
```

**Problema.** A seleção é limitada, não deslocada. Com `[A, B, C]` e `selected = 1` (o leitor está em B), remover A deixa `[B, C]` e `selected = min(1, 1) = 1`, que é **C**. Em seguida `apply()` troca todos os overlays e todos os painéis.

**Consequência.** O leitor apaga uma aba que não estava usando e o gráfico troca de indicadores sozinho — o mesmo defeito que `moveLayout` (linhas 174-184) toma o cuidado explícito de evitar ao arrastar ("a chart that silently changes its indicators because a tab was moved"). Aqui o cuidado não foi replicado.

**Correção.** Fazer como `moveLayout`: guardar o `ChartLayout` selecionado antes do `remove`, e depois `selected = layouts.indexOf(wasSelected)`, caindo para `Math.min(index, size-1)` só quando o removido ERA o selecionado.

**Tentei refutar assim:** simulei os três casos (remover antes, remover o próprio, remover depois). Remover depois e remover o próprio dão resultado aceitável; remover antes erra sempre que `selected > index`. Procurei um teste — `LayoutOrderTest` só exercita `Reordering.move`, não há teste de `removeLayout`.

---

### A4-7. A linha "Contra anterior" do resumo compara com a barra anterior, e o comentário promete a comparação de pregão

`BarReadout.java:171-176`

```java
        // Against the PREVIOUS close, which is a different question from
        // close-minus-open and the one a quote screen answers. A bar can close
        // above its own open and still be down on the session, and only this
        // row says so.
        if (index > 0) {
            double previous = series.closeAt(index - 1);
```

**Problema.** "the one a quote screen answers" e "down on the session" descrevem o fechamento do PREGÃO ANTERIOR. `series.closeAt(index - 1)` é o fechamento do minuto anterior. E o cálculo certo já existe no mesmo pacote: `Sessions.previousDayClose(series, index, zone)` (`ui/chart/Sessions.java:56`), usado pelo título da janela via `Sessions.changeOnDay`.

**Consequência.** Numa série de 1 minuto a linha mostra tipicamente ±0,00% e o leitor lê "não mudou nada contra o anterior" acreditando ser a variação do dia. O título da janela, a três centímetros dali, mostra a variação de pregão de verdade — dois números com o mesmo sentido aparente e valores diferentes.

**Correção.** Ou usar `Sessions.previousDayClose` e manter o rótulo, ou trocar a chave para algo que diga "barra anterior" (`readout.fromPreviousBar`) e apagar as duas frases sobre o pregão. A primeira é o que o comentário pediu.

**Tentei refutar assim:** conferi as duas traduções — `readout.fromPrevious = From previous` / `Contra anterior`, ambíguas, nenhuma delas desmente o comentário. Conferi se `BarReadout` alguma vez recebe uma série diária (aí barra anterior = pregão anterior): recebe a série do gráfico, que na escala de análise da casa é de 5 minutos. Procurei em `MeasurementTest`/`ChartCanvasTest` um teste dessa linha — não existe.

---

### A4-8. Três javadocs órfãos descrevendo membro diferente do que está logo abaixo

`ChartHolder.java:303-318`

```java
    /**
     * The title, with how far the price is from the last session's close.
     * ...
     */
    /**
     * Gives the chart back its own data.
     * ...
     */
    public void detachReplay() {
```

`ChartPreferences.java:136-145`

```java
    /**
     * @return whether a rising candle is drawn as an outline
     *
     * <p>A setting and not a drawing style of its own. Hollow-or-filled is how
     * the SAME chart is drawn; ...
     */
    public static boolean syntheticTicks() {
```

`CandleStyle.java:38-54`

```java
    /**
     * Whether rising bodies are drawn hollow.
     * ...
     */
    /**
     * Read at paint time rather than fixed at construction.
     * ...
     */
    private boolean hollow() {
```

**Problema.** Nos três casos o bloco de cima descreve outra coisa: o título da janela, o `hollowCandles()`, e um campo que deixou de existir. O de `ChartPreferences` é o pior — é o ÚNICO javadoc de `syntheticTicks()`, e diz `@return whether a rising candle is drawn as an outline` sobre um método que devolve se ticks podem ser inventados. As duas coisas são desligadas de checkboxes vizinhos.

**Consequência.** Quem for mexer no `syntheticTicks()` guiado pelo javadoc muda a coisa errada. É exatamente a regra da casa: comentário que mente vale MÉDIA no mínimo.

**Correção.** Apagar o bloco órfão em `ChartHolder` e em `CandleStyle`; mover o texto de "hollow candles" para `hollowCandles()` e escrever para `syntheticTicks()` o que ele realmente responde — o texto certo já está no campo, `ChartPreferences.java:62-76`.

**Tentei refutar assim:** confirmei que o javadoc que vale é o último antes do membro, então os blocos de cima são de fato mortos — e, no caso de `syntheticTicks()`, o único bloco é o errado. Verifiquei se `hollowCandles()` tinha javadoc próprio: não tem (linhas 159-161).

---

### A4-9. Dois comentários afirmam que o calendário só é consultado onde o dia muda; os dois consultam por barra

`SeriesSummary.java:113-117`

```java
     * <p>Walked once, asking the calendar only where the day changes. Per bar
     * it would be 825 thousand conversions on the source, on the interface
     * thread, every time the pointer crosses the name.</p>
```

`RenkoSource.java:90-97`

```java
        // Walked once, and the calendar is only asked where the day changes. A
        // conversion per bar would be 693 thousand of them on the full series, on
        // the interface thread, for one keystroke.
        LocalDate seen = null;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();
```

**Problema.** A conversão está DENTRO do laço, uma por barra. O que a comparação com `seen` evita é o `TreeSet.add`/`contains`, não a conversão. O comentário honesto é o do gêmeo de domínio, `domain/market/Sessions.java:76-78`, que diz corretamente "the set is touched 1.494 times and not 824.881".

**Consequência.** Estes dois comentários são a razão de o A4-2 existir: alguém leu "só onde o dia muda", concluiu que a varredura era barata, e a pendurou num `mouseMoved`. Um comentário falso que já causou um defeito ALTA.

**Correção.** Corrigir as duas frases para o que o `domain/market/Sessions` diz, e — nos dois casos — trocar a implementação por uma chamada a `Sessions.of(series)`, que é o mesmo laço já escrito uma vez. `RenkoSource.sessionsIn` (linha 119) já faz isso; `RenkoSource.allows`, quinze linhas acima, não.

**Tentei refutar assim:** procurei um cache dentro de `Instant.ofEpochMilli().atZone().toLocalDate()` que tornasse a conversão barata — `ZonedDateTime.toLocalDate()` faz o cálculo de regras de fuso a cada chamada; a `ZoneRules` do sistema tem cache de transições, mas a alocação de `Instant` + `ZonedDateTime` + `LocalDate` por barra permanece. Procurei se `RenkoSource.allows` era chamado uma vez só ("for one keystroke", diz o comentário) — nesse é, o que reduz o dano, mas não torna a frase verdadeira.

---

### A4-10. Renomear duas abas com o mesmo nome quebra a seleção, enquanto duplicar toma o cuidado de evitá-lo

`LayoutBar.java:443-451`

```java
    private void rename(int index) {
        String name = JOptionPane.showInputDialog(this, Messages.get("layout.namePrompt"),
                layouts.get(index).name());

        if (name == null || name.isBlank()) {
            return;
        }

        layouts.set(index, layouts.get(index).renamedTo(name.trim()));
```

Compare com `ChartLayouts.copyName` (`ChartLayouts.java:131-139`), que existe exatamente para não colidir.

**Problema.** Não há checagem de nome já usado. A seleção é recuperada por nome em `LayoutBar.indexOf` (linha 494) e por igualdade de record em `moveLayout` (linha 184) — `ChartLayout` é um record, então dois layouts de mesmo nome, mesmas entradas e mesmos painéis são `equals`. O primeiro da lista sempre vence.

**Consequência.** Depois de renomear duas abas igual, reabrir o gráfico o traz na aba errada, e arrastar uma delas move ou seleciona a outra. Silencioso.

**Correção.** Recusar o nome já usado no `rename` (ou aplicar `copyName`), e trocar `layouts.indexOf(wasSelected)` por busca por identidade.

**Tentei refutar assim:** verifiquei se `ChartLayout` sobrescreve `equals` — é um record puro, `equals` é gerado sobre `name`, `entries` e `panes`. Verifiquei se dois layouts distintos podem ter tudo igual — sim, dois vazios com o mesmo nome. Verifiquei se algum ponto normaliza nomes — não.

---

### A4-11. Cada `capture()` reescreve o arquivo de configuração inteiro 3n+1 vezes, na EDT

`ChartLayouts.java:90-109`

```java
            PREFS.put("layout." + i + ".name", layout.name());
            PREFS.put("layout." + i + ".entries", format(layout.entries()));
            PREFS.put("layout." + i + ".panes", formatPanes(layout.panes()));
```

E `Settings.put` (`Settings.java:328-336`) termina com `save()`, que reescreve o arquivo todo, ordenado (`Settings.java:257-284`).

**Problema.** `save(layouts)` faz `3 × layouts.size() + 1` gravações completas de disco. Com seis layouts, dezenove reescritas do arquivo por chamada. E `capture()` é disparado por `canvas.onOverlaysChanged` e por `body.onArrangement` (`ChartHolder.java:382, 387`) — ou seja, a cada clique no olho de um indicador e a cada painel rearranjado.

**Consequência.** Um clique numa lâmpada da legenda produz dezenas de escritas síncronas na EDT. Não é visível num SSD com poucos layouts; passa a ser assim que o arquivo cresce ou o disco é de rede.

**Correção.** Um `putAll`/`beginBatch` em `Settings` que adie o `save()` até o fim, ou um `save()` explícito chamado uma vez por `ChartLayouts.save`.

**Tentei refutar assim:** verifiquei se `Settings.save()` era preguiçoso ou debounced — é síncrono e escreve na hora. Verifiquei se `capture()` era protegido por algum "sujo?" — não é: reescreve mesmo quando nada mudou (`ChartLayout.of` sempre constrói um objeto novo).

---

### A4-12. `ui/chart/Sessions` é aritmética de domínio morando na camada de interface, com o nome de uma classe de domínio que já existe

`ui/chart/Sessions.java:41-45` e `domain/market/Sessions.java:48-52`

```java
public final class Sessions {

    private Sessions() {
        throw new AssertionError("Utility class must not be instantiated");
    }
```

(idêntico nos dois arquivos, pacotes diferentes)

**Problema.** `ui/chart/Sessions` não toca em nada de Swing: recebe `PriceSeries` e `ZoneId` e devolve `double`/`String`. É domínio. Não divergiram na virada de pregão — os dois usam `Instant.ofEpochMilli(t).atZone(zone).toLocalDate()`, mesma fronteira — mas o nome colidindo já obriga qualquer arquivo que precise das duas a usar nome qualificado, que é o que `RenkoSource.java:124` faz:

```java
                br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(series));
```

**Consequência.** Dívida de posição, e um convite à divergência futura: a próxima pessoa que precisar de "onde o dia vira" tem duas classes com o mesmo nome para escolher e nada que diga qual. É exatamente o cenário que o javadoc de `domain/market/Sessions` descreve como o que se quis evitar ("Three copies of one walk is where the third one forgets that a holiday is not a weekend").

**Correção.** Mover `previousDayClose`/`changeOnDay`/`percentChange` para `domain/market/Sessions` (ou para um `DayChange` ao lado dele) e deixar só `formatChange` na interface — esse último sim é apresentação.

**Tentei refutar assim:** comparei os dois arquivos linha a linha procurando divergência na detecção do dia (que seria ALTA) — não há: mesma expressão, mesma zona. A única diferença é que a de domínio aceita `zone == null` e a de `ui` não guarda (`ui/chart/Sessions.java:125`), mas todos os chamadores passam `ZoneId.systemDefault()`.

---

## Achados BAIXA

- `ChartHolder.java:734` — `private JMenuItem item(String, Runnable)` nunca é chamado, e `import javax.swing.JMenu;` (linha 48) não é usado. Resto do menu que virou barra de ferramentas.
- `messages_pt_BR.properties:203,208,210,211` — `Serie`, `Inicio`, `Duracao`, `Pregoes` sem acento, enquanto o mesmo arquivo escreve `Cópia de {0}` e `Máxima` corretamente.
- `messages_pt_BR.properties:213-215` — `summary.years/months/days` só têm plural (`{0} anos`), então `SeriesSummary.spanBetween` imprime "1 anos, 1 meses e 1 dias". `ruler.day`/`ruler.days` têm as duas formas; `summary` não seguiu.
- `OverlayLegend.java:423` e `OverlayLegend.java:206` — o teste de acerto usa `e.getY() / ROW_HEIGHT`, e a pintura põe a linha em `i * ROW_HEIGHT + 2` com destaque em `top - 1`. Um pixel de desencontro na fronteira entre linhas.
- `OverlayLegend.java:244` — `gapAt` usa `i * ROW_HEIGHT + ROW_HEIGHT / 2` como meio da linha, um pixel acima do meio real pela mesma razão.
- `BarReadout.java:240-246` — `decimalsFor(high - low)` escolhe as casas decimais pela amplitude DA BARRA, não pelo tick do instrumento: um minuto do WIN com amplitude de 5 pontos imprime `121.500,00`, e o minuto seguinte com 15 pontos imprime `121.500`. A mesma coluna muda de formato de barra para barra.
- `RandomWalkSeries.java:36` — `<p>Delete this class the moment a real series is wired in.</p>`, e a classe está em produção em `MainWindow.java:369` e `ReplaySession.java:30`, com séries reais já ligadas.
- `OverlayLegend.java:306` e `BarReadout.java:154-155` — `new DecimalFormat(...)` e `Appearance.monospaced(11)` construídos dentro do laço de pintura, a cada movimento do cursor. Poucos objetos por quadro, mas em caminho quente e trivialmente promovíveis a constante.
- `ChartHeader.java:371-378` — `mousePressed` abre o menu de segmento em qualquer botão do mouse, inclusive o direito, que em toda outra parte deste programa abre menu de contexto.

---

## O que foi auditado e está LIMPO

| o quê | como conferi |
|---|---|
| `Viewport` — ida e volta barra↔pixel | `x(i)` = `bounds.x + (i - firstBar + 0.5) * barWidth`; `barAt(x(i))` = `firstBar + floor(i - firstBar + 0.5)` = `i`, para todo `i` visível. Coberto também por `ViewportTest.barAndPixelRoundTrip`. |
| `Viewport` — ida e volta preço↔pixel | `y` e `priceAt` são a mesma expressão resolvida para lados diferentes; `ViewportTest.priceAndPixelRoundTrip`. |
| `Viewport` — série vazia, uma barra, `high == low` | `until = min(first+count, size)` deixa o laço vazio; o guarda `!(high > low)` (linha 134) captura infinito e achatamento e inventa ±1. `ViewportTest.flatSeriesIsSafe`, `emptySlotsDoNotMoveTheScale`. |
| `Viewport` — divisão por zero via `stretch` | `factor` é filtrado por `isFinite && > 0`, e o chamador limita entre `MINIMUM_STRETCH` e `MAXIMUM_STRETCH` (`ChartCanvas.java:2617, 1648`). Não consegui produzir vão zero. |
| `Viewport` — vagas, não barras | O `count` não encolhe ao passar do fim da série, que é o defeito que o comentário das linhas 115-119 descreve; `ViewportTest.countsSlotsNotBars` e `spaceOnTheRightIsNotAZoom` provam. |
| `Reordering.move` — o off-by-one | `to = gap > from ? gap-1 : gap`. Tentei quebrar pela ordem de avaliação de `list.add(clamp, list.remove(from))`: o `list.size()` do clamp é lido ANTES do `remove` (Java avalia argumentos da esquerda para a direita), então `min(to, size-1)` continua sendo um índice válido depois da remoção — e o clamp é inócuo, já que `to ≤ size-1` sempre. `LayoutOrderTest` cobre os seis casos, incluindo soltar depois do último e pedido sem sentido. |
| `ChartLayouts` — ida e volta do formato de painéis | Simulei formato→leitura para: painel único, dois painéis, três indicadores num painel, índices com buraco, aparência vazia, parâmetro ilegível, linha com menos de cinco campos. Todos reagrupam certo. `ChartLayoutTest.aPaneComesBackWhole`, `severalInOnePane`, `anUnknownPaneIsSkipped`, `anOlderLayoutStillLoads`. |
| `ChartLayouts` — `\n` dentro de um valor de propriedade | `Settings.escape` (linha 300) converte `\n` em `\\n` e `Properties.load` desfaz. O valor multilinha sobrevive ao arquivo — este era o candidato óbvio a perda de layout e não se sustentou. |
| `ChartLayouts` — parâmetro vazio não explode | `numbers("")` devolve `List.of()`; `OverlayCatalog.build` monta `int[0]` e as fábricas guardam por `first(numbers, 9)` / `second(numbers, 3)`. Nada indexa cegamente. |
| `ChartLayouts.save` — encolher a lista | O laço `for (i = layouts.size(); i < previous; i++)` remove as três chaves da cauda; sem ele o layout apagado voltaria no lançamento seguinte. |
| Ordem da legenda = ordem de desenho | `OverlayLegend` pinta `canvas.overlays()` na ordem da lista, e o arrasto chama `canvas.moveOverlay`, que usa o mesmo `Reordering.move` sobre a lista viva (`ChartCanvas.java:776`). Não há segunda ordenação em lugar nenhum. `OverlayOrderTest` prova as duas pontas, incluindo a sobrevivência ao layout. |
| Vazamento de ouvintes em `RulerMode` e `ChartPreferences` | `listen`/`forget` são pareados em `ChartCanvas.java:1511-1519` (`addNotify`/`removeNotify`), e `RulerModeTest` verifica `listenerCount()` antes e depois. Copy-on-write, sem `synchronized`. |
| Ouvintes do canvas usados pelo `ChartHolder` | São campos de slot único, então re-registrar em `buildToolBar()` a cada docagem substitui em vez de acumular — não vaza. Conferi que nenhum outro arquivo do projeto disputa os mesmos cinco slots hoje (só `MainWindow` toma `onCursorChanged`). |
| `Measurement` — os números que o leitor lê | `bars()` inclusivo dos dois extremos, `elapsed()` sobre os instantes das duas barras (não sobre a contagem), `percent()` NaN a partir de zero, `between` devolve null fora da série — o que protege do índice inflado que `barAt` pode devolver na área vazia. `MeasurementTest` cobre os cinco. `elapsedInWords` nunca produz lista vazia (`minutes > 0` garante uma parte). |
| Chaves de `ResourceBundle` da área | Conferi as 58 chaves usadas pelos 21 arquivos contra `messages.properties` e `messages_pt_BR.properties`: todas presentes nos dois, uma vez cada. Nenhuma string de interface literal em Java. |
| Classes utilitárias | `Reordering`, `ChartLayouts`, `ChartColors`, `ChartPreferences`, `RulerMode`, `SeriesSummary`, `BarReadout`, `RulerReadout`, `RenkoSource`, `ui/chart/Sessions` — todas com construtor privado lançando `AssertionError`. |
| `CandleStyle` — o corpo de um pixel | `drawHeight = max(1, round(bottom) - first + 1)` cobre a última linha do pavio; o comentário das linhas 120-126 descreve o defeito real que o `+1` corrige (o fio de um pixel sob cada tijolo renko). Nenhuma alocação por barra no laço. |
| Renko: `RenkoSource` não lê o futuro | `allows` só olha para trás (quais dias já foram exportados); `sessionsIn` delega ao domínio. Nenhum cálculo alcança barra não fechada em nenhum dos 21 arquivos. |
| Preço ajustado | Nenhum arquivo da área supõe ajuste; `Viewport` toma a escala dos preços que a série entrega, e nenhum fator aparece. |

---

## Observações sobre a área

**Os três ALTA têm a mesma raiz.** Nenhum deles é um erro de aritmética; todos os três são custo, e todos os três são invisíveis nos testes porque os testes usam `RandomWalkSeries(200, 100.0)`. Duzentas barras escondem uma varredura de um milhão. Um teste que construísse uma série sintética de ~1.000.000 de barras e cronometrasse `SeriesSummary.html` e uma repintura de `LineStyle` pegaria A4-2 e A4-3 de uma vez — e é barato, porque `RandomWalkSeries` já sabe fazer a série.

**O A4-1 e o A4-9 são o mesmo tipo de acidente visto de dois lados.** No A4-1 o comentário descreve um comportamento que nunca foi escrito; no A4-9 descreve uma otimização que não está lá. Nos dois casos o comentário estava certo em intenção e alguém confiou nele. A regra da casa — comentário explica POR QUÊ — está sendo seguida com disciplina rara nesta base; o custo dela é que o comentário passa a ser lido como especificação, e aí um comentário falso vale mais caro do que valeria em código sem comentário nenhum. Os cinco casos que encontrei (A4-4, A4-5, A4-7, A4-8, A4-9) merecem uma passada dedicada.

**O `Viewport` está sólido.** Foi onde procurei com mais desconfiança e onde menos achei — a decisão de manter a conversão num só lugar, imutável, e de contar VAGAS em vez de barras é o que faz `barAt`, `x`, `y` e `priceAt` fecharem sem exceção. O único preço dela é que `barAt` devolve índices além do fim, e todo chamador precisa saber disso; `Measurement.between`, `BarReadout.paint`, `cursorReading` e `barUnder` guardam, mas `OverlayLegend` não guardava — e é assim que o A4-1 aparece.

**Fase de desenvolvimento.** Nada aqui pesou compatibilidade de layout salvo: o leitor do formato antigo foi removido de propósito e o `.endvnet`/`.properties` pode quebrar. Os achados de persistência (A4-5, A4-6, A4-11) são sobre perda de trabalho DENTRO de uma sessão, não sobre migração.
