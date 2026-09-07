# B4 — moldura do gráfico: holder, eixos, legenda, estilos, medição

Auditoria II, 07/09/2026. Área B4. Primeira passada (`docs/auditoria/*.md`) e os
outros relatórios desta pasta **não foram lidos**.

## O que foi lido

`src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/`, tudo **exceto**
`ChartCanvas.java` (B3), `overlay/` e `study/` (B5). **31 arquivos, 8.198 linhas,
todas lidas por inteiro** — mais que a estimativa de ~5.200 do briefing; nada
ficou de fora.

| arquivo | linhas | | arquivo | linhas |
|---|---:|---|---|---:|
| ChartHolder.java | 934 | | OwnScale.java | 189 |
| OverlayLegend.java | 652 | | LinePen.java | 186 |
| MovingAverageDialog.java | 532 | | style/CandleStyle.java | 175 |
| LayoutBar.java | 503 | | RulerReadout.java | 172 |
| InsertOverlayDialog.java | 417 | | SeriesSummary.java | 154 |
| BollingerBandsDialog.java | 395 | | RandomWalkSeries.java | 149 |
| ChartHeader.java | 387 | | Measurement.java | 142 |
| ChartLayouts.java | 362 | | RenkoSource.java | 138 |
| Viewport.java | 273 | | OverlayCatalog.java | 137 |
| Forms.java | 271 | | style/LineStyle.java | 135 |
| BarReadout.java | 271 | | Sessions.java | 127 |
| PeriodCatalog.java | 264 | | RulerMode.java | 103 |
| ChartPreferences.java | 263 | | ChartColors.java | 102 |
| Overlay.java | 220 | | Reordering.java | 75 |
| PeriodDialog.java | 211 | | ChartStyle.java | 59 |
| ChartLayout.java | 200 | | **total** | **8.198** |

Lidos **para refutar**, fora da área: `domain/market/Sessions.java`,
`platform/Messages.java`, `platform/Settings.java` (trechos),
`src/main/resources/messages.properties` e `messages_pt_BR.properties`,
`src/test/.../LayoutOrderTest.java`, e trechos de `ChartCanvas.java`,
`overlay/MovingAverage.java`, `overlay/BollingerBands.java`.

**Contagem:** 1 ALTA, 13 MÉDIA, 17 BAIXA.

---

# ALTA

### B4-1. `LinePen.Sample` chama `setBorder` dentro do `paintComponent`: laço de repintura sem fim na EDT

`LinePen.java:163-176`

```java
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                setBorder(BorderFactory.createLineBorder(ChartColors.grid()));
```

**Problema.** `JComponent.setBorder` termina com

```java
    if (border != oldBorder) { ...; repaint(); }
```

e a comparação é por **referência**. `BorderFactory.createLineBorder(Color)` é
`return new LineBorder(color, 1);` — objeto novo a cada chamada. Logo toda
pintura agenda outra pintura, que agenda outra. O componente nunca para de se
repintar enquanto estiver visível.

**Consequência.** Toda vez que um diálogo que usa `LinePen` está aberto — e são
três: `BollingerBandsDialog` (via `LinePen`? não; via `Forms.Sample`),
`study/rsi/RsiDialog:154` e `study/stochastic/StochasticDialog` chamam
`LinePen.addTo`, que adiciona este `Sample` — a EDT fica em laço de pintura
contínuo. A janela ainda responde (o `repaint` é coalescido pelo
`RepaintManager` e os eventos passam entre uma pintura e outra), mas um núcleo
fica ocupado indefinidamente e todo repaint de gráfico atrás do diálogo disputa
a fila com ele. É exatamente o que a convenção das regras da EDT existe para
impedir.

**Correção.** Instalar a borda uma vez, no construtor de `Sample`, ou — se ela
precisa seguir o tema — instalar só quando mudou:

```java
Sample(LinePen pen) {
    this.pen = pen;
    setBorder(BorderFactory.createLineBorder(ChartColors.grid()));
}
```

**Tentei refutar assim.** (a) Procurei um `if` que evitasse a reinstalação: não
há, a chamada é incondicional. (b) Verifiquei se `BorderFactory` cacheia
`createLineBorder(Color)` — cacheia apenas alguns compartilhados
(`createEmptyBorder()`, `createRaisedBevelBorder()`); a sobrecarga com `Color`
constrói sempre. (c) Verifiquei se `setBorder` só repinta quando os *insets*
mudam — não: o `revalidate()` é que é condicional aos insets, o `repaint()` é
incondicional. (d) Procurei o mesmo padrão nos outros `Sample`
(`Forms.Sample:252`, `MovingAverageDialog.Sample:508`): nenhum chama `setBorder`
na pintura — só este. Não derrubei.

---

# MÉDIA

### B4-2. Um gráfico que reabre flutuante nunca restaura a vista: escala, estilo, zoom e posição na série são escritos e nunca lidos

`ChartHolder.java:492-493` e `ChartHolder.java:499-530`

```java
        // After the frame exists and has a size: the view is restored in terms
        // of bars across a plot, and the plot has no width until then.
        javax.swing.SwingUtilities.invokeLater(
                () -> canvas.restoreView(Settings.workspace(), "chart." + key + "."));
```

Essa linha está em `dock()`. `floatIt()` monta o quadro, o dimensiona, o
posiciona e o mostra — e **não chama `restoreView` em lugar nenhum**.

**Problema.** `close()` → `store()` → `canvas.storeView(...)` grava sempre:
`visibleBars`, `rightMargin`, `stretch`, `priceOffset`, **`period`**,
**`style`** e `fromEnd` (`ChartCanvas.java:1894-1909`). E `show()` reabre no
modo em que ficou:

```java
    public void show() {
        if (PREFS.getBoolean(key + ".floating", false)) {
            floatIt();
```

Quem trabalha com gráficos flutuantes escreve o estado toda vez e nunca o recebe
de volta.

**Consequência.** O gráfico deixado em `11R` com zoom em março de 2021 reabre em
`1m`, no fim da série, com zoom padrão e no estilo padrão. O `.width`/`.height`
voltam (são de `PREFS`, lidos por `restoredSize()`), então parece que "a janela
lembrou" — o que torna a perda do resto mais confusa, não menos.

**Correção.** Chamar o mesmo `invokeLater(... restoreView ...)` no fim de
`floatIt()`, ou tirá-lo dos dois e pô-lo num método privado que ambos chamam —
é a mesma pergunta respondida em um lugar só.

**Tentei refutar assim.** (a) `grep -rn "restoreView" src/`: três ocorrências —
a definição em `ChartCanvas:1913`, esta chamada em `ChartHolder:493` e uma no
teste `ChartViewTest:260`. Nenhuma em `floatIt`. (b) Procurei se `toggleMode()`
compensaria: `toggleMode` → `floatIt()`, mesmo caminho. (c) Procurei se
`MainWindow` restauraria por fora: `MainWindow:625` só constrói o `ChartHolder`
e chama `show()`. Não derrubei.

### B4-3. O javadoc de `syntheticTicks()` é o de `hollowCandles()` — e `hollowCandles()` fica sem nenhum

`ChartPreferences.java:136-146` e `ChartPreferences.java:215-217`

```java
    /**
     * @return whether a rising candle is drawn as an outline
     *
     * <p>A setting and not a drawing style of its own. Hollow-or-filled is how
     * the SAME chart is drawn; putting it beside "candles" and "line" in a list
     * made it look like a third kind of chart, and the reader had to know that
     * two of the three were the same thing.</p>
     */
    public static boolean syntheticTicks() {
        return syntheticTicks;
    }
```

```java
    public static boolean hollowCandles() {
        return hollowCandles;
    }
```

**Problema.** Javadoc grudado no membro errado. O texto descreve corpo de vela
oco; o método devolve se ticks ausentes podem ser inventados — que é regra de
domínio medida, e cujo javadoc verdadeiro está no **campo**, em
`ChartPreferences.java:62-76` ("brick 55 gives 9.718 bricks from one-minute
candles and 8.076 from the exchange's own ticks").

**Consequência.** Quem lê `syntheticTicks()` — pessoa ou IA — recebe a
explicação de um interruptor de aparência para um interruptor que decide se um
renko é comparável a um do Profit. A severidade acompanha o defeito que o
comentário esconde: é uma regra de domínio.

**Correção.** Mover o bloco para `hollowCandles()` e dar a `syntheticTicks()`
um `@return` que aponte para o javadoc do campo.

**Tentei refutar assim.** Procurei se `syntheticTicks` teria virado também o
interruptor de velas ocas: são dois campos independentes (`SYNTHETIC` linha 46,
`HOLLOW` linha 48), dois pares de acessores, dois `PREFS.putBoolean`. Não
derrubei.

### B4-4. `RenkoSource.allows` converte data por barra na EDT, com um comentário dizendo que não converte

`RenkoSource.java:91-108`

```java
        // Walked once, and the calendar is only asked where the day changes. A
        // conversion per bar would be 693 thousand of them on the full series, on
        // the interface thread, for one keystroke.
        LocalDate seen = null;

        for (int i = 0; i < series.size(); i++) {
            LocalDate day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();

            if (day.equals(seen)) {
                continue;
            }
```

**Problema.** A conversão está **dentro** do laço e roda em todas as barras; só
o `exported.contains` é que é pulado. É literalmente o defeito que
`SeriesSummary.java:110-119` diz ter encontrado e corrigido em outro lugar:

> "its comment claimed to ask the calendar 'only where the day changes' — which
> the code did not do: the conversion sat inside the loop and ran on all 824.881
> bars, and only the COUNTER moved on a change."

E a resposta cacheada existe **no mesmo arquivo**, doze linhas abaixo:
`RenkoSource.sessionsIn` (linha 119) delega a `domain.market.Sessions.of`, que
lembra.

**Consequência.** 39-102 ms na EDT (número medido no javadoc de
`domain.market.Sessions`) a cada avaliação de `allows` — que acontece quando o
leitor digita um período de renko, com `syntheticTicks` desligado.

**Correção.**

```java
for (LocalDate day : sessionsIn(series)) {
    if (!exported.contains(day)) {
        return false;
    }
}
return true;
```

**Tentei refutar assim.** (a) Verifiquei se `Instant.atZone().toLocalDate()` é
barato o bastante para não importar: o próprio projeto mediu 39-102 ms para essa
travessia. (b) Verifiquei se `allows` roda fora da EDT: os chamadores estão em
`ChartCanvas` no caminho de `askForPeriod`/teclado, tudo EDT. (c) Verifiquei se
o cache de `Sessions.of` não serviria por causa do fuso: `allows` usa
`ZoneId.systemDefault()`, o mesmo que `Sessions.of(series)` usa. Não derrubei.

### B4-5. Texto de tela em português dentro do código, em `PeriodCatalog`

`PeriodCatalog.java:224-251`

```java
    private static String describe(Timeframe frame) {
        int minutes = frame.minutes();

        if (minutes == 0) {
            return "1 dia";
        }
        ...
            return hours + (hours == 1 ? " hora" : " horas")
                    + " (" + minutes + " minutos)";
        }

        return minutes + " minutos";
```

e `PeriodCatalog.java:147-148` / `194-195`:

```java
            choices.add(new Choice(number + "R",
                    number + "R (renko " + trim(brickOf(number)) + " pts)",
```

**Problema.** Convenção 9: no `endeavour_neo` o texto de interface **nunca** fica
no código. Essas cadeias vão para a tela: `PeriodDialog.Row:202` desenha
`choice.description()` em cada linha da lista de escalas.

**Consequência.** A lista de períodos é a única parte da interface que não
traduz. Numa aplicação que vai para open source com o bundle base em inglês, um
leitor de outra língua vê "1 dia", "1 semana", "1 mês", "renko 50 pts".

**Correção.** Chaves `period.day`, `period.week`, `period.month`,
`period.minutes`, `period.hours`, `period.renko` no bundle, com `{0}` para os
números.

**Tentei refutar assim.** (a) Confirmei que `description()` chega à tela e não é
só rótulo interno: `PeriodDialog.java:202`,
`setText(String.format("%-6s   %s", choice.code(), choice.description()))`. (b)
Procurei chaves `period.*` já existentes no bundle: só `period.title`,
`period.prompt` e `period.hint` — nenhuma cobre isto. Não derrubei.

### B4-6. Apagar um layout à esquerda do selecionado troca o gráfico de layout em silêncio

`LayoutBar.java:471-492`

```java
        layouts.remove(index);
        selected = Math.min(selected, layouts.size() - 1);

        ChartLayouts.save(layouts);
        ChartLayouts.remember(chartKey, layouts.get(selected).name());

        apply();
```

**Problema.** `selected` é um índice, e apagar um item antes dele desloca tudo.
Com `[A, B, C]` e o leitor em **B** (`selected = 1`), apagar **A** (`index = 0`)
deixa `[B, C]`; `Math.min(1, 1) = 1` → `selected` passa a apontar para **C**.
`apply()` então troca os indicadores do gráfico.

O próprio arquivo diz, duas vezes, que isso não pode acontecer —
`LayoutBar.java:181-183`:

```java
        // The selection follows the LAYOUT and not the index. Dragging the tab
        // you are looking at must leave you looking at it; the alternative is a
        // chart that silently swaps its indicators because a tab was moved.
```

`moveLayout` protege a identidade do layout selecionado; `removeLayout` não.

**Consequência.** O leitor apaga um layout que não estava usando e o gráfico
troca de indicadores sozinho, sem aviso e sem nada na tela dizendo por quê.

**Correção.** O mesmo padrão de `moveLayout`: guardar o `ChartLayout`
selecionado antes do `remove` e reencontrá-lo com `indexOf` depois, caindo para
`Math.min(...)` só quando foi o próprio selecionado que saiu.

**Tentei refutar assim.** (a) Verifiquei se `apply()` seria inofensivo: ele faz
`canvas.setOverlays(layouts.get(selected).build())` e `studies.restore(...)` —
troca de verdade. (b) Verifiquei se `capture()` corromperia o layout errado
depois: não, `apply()` deixa o gráfico coerente com o novo `selected`, então o
próximo `capture` grava a coisa certa no lugar certo. O dano é a troca em
silêncio, não corrupção. (c) Verifiquei o caso em que o apagado é o próprio
selecionado, ou está depois dele: nesses dois o `Math.min` acerta. Não derrubei
o caso do apagado-antes.

### B4-7. `ChartLayouts.save` grava o arquivo uma vez por chave, na EDT, e o javadoc promete uma atomicidade que não existe

`ChartLayouts.java:80-110`

```java
    /**
     * Replaces the whole set.
     *
     * <p>All of them at once rather than one at a time: the tabs are reordered,
     * removed and renamed together, and writing entry by entry would leave the
     * stored count disagreeing with the stored names if anything failed halfway.</p>
     */
    public static void save(List<ChartLayout> layouts) {
        ...
            PREFS.put("layout." + i + ".name", layout.name());
            PREFS.put("layout." + i + ".entries", format(layout.entries()));
            PREFS.put("layout." + i + ".panes", formatPanes(layout.panes()));
```

**Problema.** `Settings.put` termina em `save()` — `platform/Settings.java:341-349`:

```java
    public void put(String key, String value) {
        if (value == null) { values.remove(key); } else { values.setProperty(key, value); }

        save();
    }
```

e a classe documenta a decisão: *"Written on every change rather than at exit."*
Portanto `ChartLayouts.save` com cinco layouts faz **16 gravações do arquivo
inteiro**, uma por chave, e o armazenamento passa por todos os estados
intermediários que o javadoc afirma evitar. `LayoutBar.capture()` chama isso a
cada mudança de indicador (`ChartHolder.java:385`) e a cada rearranjo de painel
(`ChartHolder.java:390`).

**Consequência.** Arrastar um painel ou mexer num indicador dispara 3N+1 escritas
de disco na thread de interface. E a promessa de atomicidade é falsa: uma falha
no meio deixa exatamente o `count` discordando dos nomes.

**Correção.** Ou dar ao `Settings` um modo em lote (`putAll` com um `save()` no
fim) — ele já tem `removeStartingWith`, que documenta "writing once at the end",
o precedente existe — ou fazer `capture()` marcar sujo e gravar num
`invokeLater` coalescido.

**Tentei refutar assim.** (a) Verifiquei se `Settings.save()` seria um no-op sem
mudança: não, `put` chama `save()` incondicionalmente depois de `setProperty`.
(b) Verifiquei se `capture()` é raro: está ligado a `onOverlaysChanged` e
`onArrangement`, os dois eventos mais comuns do gráfico. (c) Verifiquei se
`removeStartingWith` já resolveria: existe e grava uma vez, mas `save` não o usa.
Não derrubei.

### B4-8. `MovingAverageDialog` carrega cópias privadas de tudo o que `Forms` existe para não duplicar

`Forms.java:47-55`

```java
/**
 * The pieces an indicator's settings dialog is built from.
 *
 * <p>Here rather than in one dialog because there are now two of them, and a
 * second copy of the layout is how two dialogs of the same product end up
 * looking subtly unlike each other — different label spacing, a control a few
 * pixels shorter. The reader notices that without being able to say what is
 * wrong.</p>
 */
```

`MovingAverageDialog.java:290-346, 410-427, 430-495, 498-525` reimplementa,
palavra por palavra, `form()`, `group()`, `field()`, `named()`, `Swatch`,
`Sample` e o renderizador de estilos de linha. O arquivo **não importa `Forms`
nem uma vez**.

**Problema.** Cinco chamadores usam `Forms` (`BollingerBandsDialog`, `LinePen`,
`RsiDialog`, `StochasticDialog`, e o próprio `LinePen` para os renderizadores);
um não usa. A classe criada para impedir a divergência é contornada justamente
pelo diálogo mais antigo — e o javadoc de `LinePen.java:45-52` registra que essa
divergência **já aconteceu uma vez**: *"two dialogs carrying private copies of
the same list renderers and showing `ARITHMETIC` where the other showed
'Aritmética'"*.

**Consequência.** Duas respostas para a mesma pergunta. Toda melhoria em `Forms`
— espaçamento, altura mínima, tratamento de tema — passa ao largo do diálogo da
média móvel, que é o mais usado.

**Correção.** Apagar os sete membros privados e chamar `Forms`. Nenhum deles
diverge hoje, então a troca é mecânica.

**Tentei refutar assim.** (a) Comparei linha a linha `MovingAverageDialog.field`
(319-346) com `Forms.field` (91-118): idênticos, inclusive o comentário sobre o
`JTextField("X")`. (b) Verifiquei se `MovingAverageDialog.Sample` precisa ser
diferente por ler campos da instância: `Forms.Sample` recebe três `Supplier`
exatamente para esse caso, e `BollingerBandsDialog:132-139` o usa assim. (c)
Verifiquei se `Forms` é acessível: é `public final` no mesmo pacote. Não
derrubei.

### B4-9. `ChartColors` aloca um `Color` e consulta o `UIManager` por elemento desenhado

`ChartColors.java:72-84`

```java
    public static Color grid() {
        Color base = foreground();

        return new Color(base.getRed(), base.getGreen(), base.getBlue(), dark() ? 38 : 30);
    }

    public static Color up() {
        return dark() ? new Color(0x4CAF50) : new Color(0x1B7F3B);
    }
```

`style/CandleStyle.java:119-120`, dentro do laço de colunas:

```java
            g.setColor(gap ? ChartColors.untraded()
                    : rising ? ChartColors.up() : ChartColors.down());
```

`OverlayLegend.java:284-286`, uma vez por linha da legenda:

```java
        Color ink = overlay.isVisible()
                ? ChartColors.foreground()
                : fade(ChartColors.foreground(), 110);
```

**Problema.** Cada `up()`/`down()`/`untraded()` constrói um `Color` novo e chama
`dark()`, que faz `UIManager.getColor("Panel.background")`. Numa área de 900
pixels são ~900 alocações e ~900 consultas ao `UIManager` por repintura — mais
as de `grid()` e `background()`. A convenção proíbe alocação por elemento em
laço quente, e este é o laço quente do produto: o próprio `Viewport.java:206`
mede 1.049 ms por repintura de velas.

**Consequência.** Lixo e trabalho proporcionais à largura da tela em cada quadro,
num caminho já medido como o mais caro do programa.

**Correção.** As cores não mudam dentro de uma pintura. Ou içar as três para
variáveis locais antes do laço em `CandleStyle.paint` — correção mínima, sem
tocar em `ChartColors` — ou memoizar por tema em `ChartColors`, invalidando
quando o look-and-feel troca. O javadoc da classe justifica *ler no momento da
pintura*, não *alocar por elemento*: as duas coisas são separáveis.

**Tentei refutar assim.** (a) Verifiquei se `UIManager.getColor` é barato o
suficiente: é uma busca em tabela com fallback pela cadeia de defaults — barato
por chamada, não por 900 chamadas por quadro somadas às alocações. (b)
Verifiquei se o compilador escaparia a alocação: `g.setColor` guarda a
referência no `Graphics2D`, o objeto escapa. (c) Verifiquei se o laço é mesmo
quente: `CandleStyle.paint` é chamado a cada repintura, e `ChartCanvas.repaint`
é disparado por movimento de mouse, replay e rolagem. Não derrubei.

### B4-10. A régua mostra o mesmo número duas vezes, com dois arredondamentos e dois rótulos

`RulerReadout.java:111-118`

```java
        rows.add(new String[]{Messages.get("ruler.change"),
                sign + price.format(difference) + Messages.get("ruler.points"), mood});

        if (Double.isFinite(percent)) {
            rows.add(new String[]{"", sign + price.format(percent) + "%", mood});
        }

        rows.add(new String[]{Messages.get("ruler.difference"), plain.format(difference)});
```

com `price = format(2)` e `plain = format(0)` (linhas 103-104), e no bundle:

```
ruler.change = Change
ruler.difference = Difference
```

**Problema.** `difference` é a mesma variável nas duas linhas. "Change" a mostra
com duas casas mais " pts"; "Difference" a mostra arredondada a zero casas. São
dois rótulos distintos para uma grandeza só, e os dois podem discordar na tela:
uma medição de 12,50 pontos aparece como `+12,50 pts` e como `13`.

**Consequência.** A caixa da régua é a resposta a "quanto isto vale"; ela dá duas
respostas, uma delas arredondada, sem dizer que são a mesma. Num instrumento cujo
tick é 5 pontos isso é imediatamente visível.

**Correção.** Decidir qual das duas é a pergunta. Se "Difference" pretendia ser
outra coisa — a diferença **em ticks**, digamos, que seria útil — calcular isso;
senão, apagar a linha.

**Tentei refutar assim.** (a) Reli procurando um `measurement.difference()`
diferente para a segunda linha: é a mesma chamada, `double difference =
measurement.difference();` na linha 100, usada nas duas. (b) Procurei nos
`messages_pt_BR.properties` se os rótulos revelariam intenções diferentes:
"Variação" e "Diferença" — igualmente sinônimos. (c) Verifiquei se `plain` teria
alguma unidade implícita: `format(0)` é só `#,##0`. Não derrubei.

### B4-11. `Pane.build()` ignora `visible`; `Entry.build()` o respeita — duas respostas para "como um Entry vira um Overlay"

`ChartLayout.java:79-94` contra `ChartLayout.java:112-136`

```java
        public List<...Overlay> build() {
            for (Entry entry : entries) {
                ...Overlay study = OverlayCatalog.build(entry.kindKey(), entry.parameters());

                if (study != null) {
                    study.applyAppearance(entry.appearance());
                    found.add(study);
                }
            }
```

```java
        public Overlay build() {
            for (OverlayCatalog.Kind kind : OverlayCatalog.kinds()) {
                ...
                Overlay overlay = kind.factory().apply(values);

                overlay.applyAppearance(appearance);
                overlay.setVisible(visible);
```

**Problema.** Três caminhos fazem a mesma transformação — `Pane.build`,
`Entry.build` e `OverlayCatalog.build` — e divergem: o de painel **não** chama
`setVisible`. E o formato dos painéis nem sequer grava a visibilidade:
`ChartLayouts.formatPanes:199-211` escreve `pane|kind|params|height|minimised|appearance`,
sem campo de visível, e `parsePanes:254` reconstrói sempre com `true`
fixo.

**Consequência.** Um indicador de painel escondido pelo olho da legenda volta
visível na próxima abertura, sem que nada diga que a escolha foi descartada. O
javadoc de `OverlayLegend.java:52-55` diz que essa alternância existe justamente
para que "experimentar não custe nada" — custa uma reabertura.

**Correção.** Acrescentar o campo de visibilidade ao formato de painel (o
próprio arquivo já tem o padrão de campo opcional no fim, `format:316-318`) e
fazer `Pane.build` chamar `setVisible`. Ou, melhor, `Entry.build` e `Pane.build`
delegarem a um único método.

**Tentei refutar assim.** (a) Procurei se `StudyStack.restore` reaplicaria a
visibilidade por fora: `LayoutBar.apply:394` chama `studies.restore(panes)` com
os `Pane` crus, sem informação de visível. (b) Verifiquei se `Entry.build` e
`OverlayCatalog.build` são realmente o mesmo laço: são — mesmo `for` sobre
`kinds()`, mesma conversão de `List<Integer>` para `int[]`, mesma `factory()`.
Não derrubei.

### B4-12. A legenda constrói um `DecimalFormat` por linha, a cada pintura

`OverlayLegend.java:323-338`

```java
        g.setFont(Appearance.monospaced(11));

        FontMetrics mono = g.getFontMetrics();
        DecimalFormat format = new DecimalFormat("#,##0.###",
                DecimalFormatSymbols.getInstance(Locale.getDefault()));
```

`paintRow` é chamado uma vez por indicador em `paintComponent:225-227`, e
`paintComponent` roda a cada movimento do mouse sobre o gráfico (o construtor do
`ChartHolder:192-195` liga `canvas.onOverlaysRedrawn` a `legend.repaint()`).

**Problema.** `new DecimalFormat(pattern, symbols)` compila o padrão a cada
chamada. Num gráfico com uma dúzia de indicadores são doze compilações de padrão
por quadro, além do `getFont().deriveFont(11f)` (linha 273) e das alocações de
`Rectangle` (301, 306, 311) e de `Color` em `fade`/`hoverTint`. Alocação por
elemento em laço de pintura.

**Consequência.** Custo por quadro proporcional ao número de indicadores, num
componente que repinta com o mouse.

**Correção.** Um `DecimalFormat` estático por `Locale`, reusado — ou um campo da
legenda, reconstruído só quando o `Locale` muda. Idem para a fonte derivada.

**Tentei refutar assim.** (a) Verifiquei se `DecimalFormat` teria de ser por
linha por causa de casas variáveis: o padrão é fixo, `"#,##0.###"`, igual em
todas as linhas. (b) Verifiquei se a legenda repinta pouco: `readAt()` lê
`canvas.hoveredBar()`, ou seja, ela existe para mudar com o mouse. (c)
`DecimalFormat` não é thread-safe, mas tudo aqui é EDT — um campo resolve. Não
derrubei.

### B4-13. `ChartLayouts.copyName` entra em laço infinito quando a chave do bundle falta

`ChartLayouts.java:131-139`

```java
    public static String copyName(List<String> existing, String name) {
        String candidate = Messages.get("layout.copyOf", name);

        while (existing.contains(candidate)) {
            candidate = Messages.get("layout.copyOf", candidate);
        }

        return candidate;
    }
```

**Problema.** A saída do laço depende de `layout.copyOf` conter `{0}`. Se a
chave sumir, `Messages.get` devolve `"!layout.copyOf!"`
(`platform/Messages.java:60-66`), `MessageFormat.format` sobre um texto sem
espaço reservado devolve o mesmo texto, `candidate` para de mudar e o laço nunca
termina — na EDT, dentro de `LayoutBar.duplicate`.

**Consequência.** Travamento total da interface a partir de uma chave de tradução
ausente, que é exatamente o caso que `Messages` foi desenhado para tornar
inofensivo ("one forgotten string in a translation should not stop the
application from opening").

**Correção.** Limitar o laço — `for (int n = 2; existing.contains(candidate) && n
< 100; n++) candidate = base + " (" + n + ")";` — ou verificar que o resultado
mudou antes de repetir.

**Tentei refutar assim.** (a) Confirmei que a chave existe hoje nos dois bundles
com `{0}` (`messages.properties:184`, `messages_pt_BR.properties:184`) — por
isso não é ALTA. (b) Verifiquei se `Messages.get(key, args)` trataria a chave
ausente antes do `MessageFormat`: não, ele formata o `"!key!"` normalmente
(linha 78). (c) Verifiquei se `existing` poderia estar vazio e o laço nem
começar: `LayoutBar.duplicate:421-426` monta a lista com todos os nomes, então
o laço começa sempre que o nome-cópia já existe. Não derrubei.

### B4-14. `styleChoice` é uma segunda resposta para "qual estilo", e discorda do canvas depois de `restoreView`

`ChartHolder.java:159-160` e `ChartHolder.java:725-742`

```java
    /** Which drawing style the buttons should show as chosen. */
    private String styleChoice = "candle";
```

```java
        entry.setSelected(key.equals(styleChoice) || key.endsWith(styleChoice));
```

**Problema.** `ChartCanvas` já sabe o estilo — tem `getStyle()` — e
`restoreView` o restaura a partir do workspace (`ChartCanvas.java:1920-1922`).
Mas `styleChoice` nasce sempre em `"candle"` e nunca é sincronizado. Em `dock()`
a barra é montada (linha 447, `header()`) **antes** do `invokeLater` que restaura
a vista (linha 492).

**Consequência.** Um gráfico deixado em linha reabre desenhando linha com o botão
de **velas** marcado. O primeiro clique em "Line" parece não fazer nada — o
canvas já está em linha —, o que é a forma mais confusa possível de um controle
falhar.

Vale notar que o `||  key.endsWith(styleChoice)` é uma guarda que protege menos
do que parece: ela existe só para casar o valor inicial `"candle"` com a chave
`"chart.style.candle"`, e mistura dois vocabulários para uma identidade só.

**Correção.** Perguntar ao canvas: `entry.setSelected(canvas.getStyle().nameKey().equals(key))`,
e apagar o campo.

**Tentei refutar assim.** (a) Verifiquei se `restoreView` dispara algo que
remarque os botões: dispara `onScaleChanged`, que só mexe no botão de escala
automática (`ChartHolder:696`). (b) Verifiquei se a barra é reconstruída depois
da restauração: `header()` só é chamado em `dock()` e `floatIt()`, ambos antes.
(c) Verifiquei se `ChartCanvas.getStyle()` existe e é público: existe,
`ChartCanvas.java:1525`. Não derrubei.

---

# BAIXA

### B4-15. Quatro javadoc grudados no membro errado

Além de B4-3, o mesmo padrão — dois blocos de javadoc empilhados, e o compilador
descarta o primeiro — aparece em:

- `ChartHolder.java:306-322`: o bloco *"The title, with how far the price is from
  the last session's close"* descreve `title()` e está colado em
  `detachReplay()`. `title()` (linha 339) fica sem documentação; a explicação de
  por que a variação percentual é omitida numa série de um dia só se perde.
- `Overlay.java:83-105`: o bloco *"@return how the line is drawn: thickness and
  dash pattern"* é de `stroke()` e está colado em `paintUnder()`. `stroke()`
  (linha 109) fica sem javadoc.
- `style/CandleStyle.java:38-54`: o bloco *"Whether rising bodies are drawn
  hollow"* documenta um **campo que não existe mais** e está colado no método
  `hollow()`.
- `ChartLayout.java:48-62`: o javadoc do record `Pane(entries, height, minimised)`
  traz `@param kindKey`, `@param parameters` e `@param appearance` — parâmetros
  do construtor secundário, não componentes do record. `javadoc` reclama de
  `@param` inexistente e `@param entries` fica faltando.

**Correção.** Separar os blocos e ligá-los ao membro certo.

**Tentei refutar assim.** Confirmei a regra da linguagem: quando dois comentários
de documentação precedem um membro, só o **último** é o javadoc daquele membro;
o anterior é descartado. Nos quatro casos o texto descartado é o que carrega a
razão. Não derrubei.

### B4-16. Código morto

- `ChartHolder.java:744-751`: `private JMenuItem item(String, Runnable)` — nenhum
  chamador (`grep -n "item(" ChartHolder.java` devolve só a definição). Os
  imports `javax.swing.JMenu` (linha 48) e `javax.swing.JMenuItem` (49) existem
  só por causa dele; `JMenu` já está sem uso mesmo com o método.
- `MovingAverageDialog.java:527-531`: `glue()`, `@SuppressWarnings("unused")`,
  com javadoc explicando por que existe.
- `OverlayCatalog.java:20`: `import ...overlay.MovingAverage;` não usado — a
  linha 67 escreve o nome totalmente qualificado.
- `OwnScale.java:23`: `import br.com.jorge.reis.endeavourneo.ui.chart.PeriodCatalog;`
  — import do próprio pacote.

**Tentei refutar assim.** Procurei uso por reflexão ou por nome em recurso:
nenhum destes aparece em `messages*.properties` nem em teste. Não derrubei.

### B4-17. `ChartLayouts`: o javadoc nomeia uma classe que o arquivo não usa, e o teto de 40 entradas perdeu a origem

`ChartLayouts.java:29` e `ChartLayouts.java:50-51`

```java
 * <p>Stored as text in {@link Preferences}: one line per indicator, fields
```

```java
    /** Preferences refuses a value longer than this, so a layout has a ceiling. */
    private static final int MAX_ENTRIES = 40;
```

`Preferences` não é importado (`{@link}` não resolve) e o armazenamento é
`Settings.settings()` — arquivo de texto, cuja classe documenta explicitamente
ter saído do `java.util.prefs` (*"Why files and not `java.util.prefs`"*,
`platform/Settings.java`). O limite de 8 KB por valor que justificava o 40 não
existe mais, e `format:293` / `formatPanes:189-191` truncam em silêncio.

**Consequência.** Um layout com mais de 40 indicadores perde os excedentes ao
gravar, sem aviso, por causa de um limite que já não se aplica.

**Correção.** Corrigir o javadoc para `Settings` e ou remover o teto ou dar-lhe
uma justificativa nova e medida.

**Tentei refutar assim.** Verifiquei se `Settings` tem algum limite próprio de
tamanho: `put`/`save` escrevem `Properties` num arquivo, sem teto. Não derrubei.

### B4-18. `parsePanes` lê altura e minimizado da **última** linha do painel; o javadoc diz "da primeira"

`ChartLayouts.java:172-179` e `ChartLayouts.java:245-256`

```java
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
```

`height` e `minimised` são sobrescritos por cada linha do grupo; a descarga do
painel ocorre antes da sobrescrita da linha seguinte, então valem os da
**última** linha do painel. Inofensivo enquanto os valores repetem — é o
comentário que está errado, e é ele que diz ao próximo leitor que a repetição
não importa.

### B4-19. A guarda de `Reordering.move` só está correta por causa da ordem de avaliação dos argumentos, e nada diz isso

`Reordering.java:71`

```java
        list.add(Math.max(0, Math.min(to, list.size() - 1)), list.remove(from));
```

**Problema.** `to` pode legitimamente valer `n-1` (soltar depois do último). Se
o `remove` acontecesse antes, `list.size()-1` seria `n-2` e o item cairia uma
posição antes do fim. Funciona porque Java avalia os argumentos da esquerda para
a direita: o `Math.min` vê o tamanho **anterior** à remoção. Uma "limpeza" que
extraia o elemento primeiro —

```java
T item = list.remove(from);
list.add(Math.max(0, Math.min(to, list.size() - 1)), item);   // quebra
```

— quebra a coisa toda, silenciosamente, para todos os três chamadores
(`LayoutBar`, `StudyStack`, `ChartCanvas.moveOverlay`).

**Correção.** A guarda é desnecessária: `move` já rejeitou `gap > list.size()` na
linha 61, então `to` está sempre em `[0, n-1]`, que é exatamente a faixa válida
para o `add` pós-remoção. `list.add(to, list.remove(from));` basta. Se ficar,
merece um comentário dizendo de que depende.

**Tentei refutar assim.** Isto **começou como um achado ALTA** — parecia que
arrastar para o fim errava por um. Foi derrubado assim: tracei
`move(["a","b","c","d"], 1, 4)` supondo remoção primeiro e obtive
`["a","c","b","d"]`; o `LayoutOrderTest.droppingPastTheEnd` (linha 72) espera
`["a","c","d","b"]` e passa. Reli a JLS §15.12.4.2 e a ordem de avaliação salva
o cálculo. O defeito não existe; a fragilidade, sim, e é o que fica registrado.

### B4-20. `LineStyle` deixa o antialiasing ligado quando a dica anterior era nula

`style/LineStyle.java:124-133`

```java
        Object previous = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        ...
        if (previous != null) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, previous);
        }
```

O comentário logo acima diz "Antialiasing **only here**". Com `previous == null`
a dica fica ligada para todas as camadas desenhadas depois no mesmo
`Graphics2D`. Guarda que protege menos do que diz. Raro na prática (o
`Graphics2D` costuma devolver `VALUE_ANTIALIAS_DEFAULT`, não `null`), mas o
caminho existe. Correção: `g.setRenderingHint(KEY_ANTIALIASING, previous == null
? RenderingHints.VALUE_ANTIALIAS_DEFAULT : previous)`.

### B4-21. O comentário de "contra anterior" promete a comparação de pregão e entrega a da barra anterior

`BarReadout.java:171-177`

```java
        // Against the PREVIOUS close, which is a different question from
        // close-minus-open and the one a quote screen answers. A bar can close
        // above its own open and still be down on the session, and only this
        // row says so.
        if (index > 0) {
            double previous = series.closeAt(index - 1);
```

Um quote screen compara com o fechamento do **pregão anterior** — que é o que
`ui.chart.Sessions.previousDayClose` calcula e o que o título do gráfico usa. Aqui
o que se lê é o fechamento da **barra anterior**. Em 5m — a escala de análise do
projeto — a barra anterior fecha praticamente onde esta abriu, então a linha
repete quase o mesmo número da linha "Variação", e não diz nada sobre o dia. Só
num gráfico diário as duas perguntas coincidem. Correção: ou chamar
`Sessions.previousDayClose(series, index, zone)`, ou corrigir o comentário e o
rótulo.

### B4-22. Duas classes chamadas `Sessions`

`ui/chart/Sessions.java` (fechamento do dia anterior, variação percentual) e
`domain/market/Sessions.java` (quais dias a série cobre, com cache). Como a
primeira sombreia a segunda dentro do pacote, `SeriesSummary.java:122` precisa do
nome totalmente qualificado — e o javadoc logo acima diz *"Asked of `Sessions`,
which remembers"*, referência que, lida de dentro deste pacote, aponta para a
classe errada. `RenkoSource.java:124` tem o mesmo problema. Renomear uma das
duas (`DayChange` para a de interface, digamos) resolve.

### B4-23. A linha sob o ponteiro na legenda não é a linha destacada

`OverlayLegend.java:443` contra `OverlayLegend.java:226` e `281`

```java
            int row = e.getY() / ROW_HEIGHT;
```

```java
                paintRow(g, overlays.get(i), bar, i, i * ROW_HEIGHT + 2);
```

```java
            g.fillRect(0, top - 1, getWidth(), ROW_HEIGHT);
```

A pintura desloca as linhas em `+2` e a faixa de destaque começa em
`i*ROW_HEIGHT + 1`; a conta do mouse ignora o deslocamento. Nos dois pixels do
topo de cada linha o destaque e a área sensível discordam. `gapAt:264` tem o
mesmo desalinhamento. Correção: uma constante `TOP = 2` usada nos dois lados.

### B4-24. `formatChange` diz "sempre com sinal" e imprime 0,00% sem sinal

`Sessions.java:105-122`

```java
     * <p>Always signed, including the plus: a bare "3,04%" beside an instrument
     * name reads as a quantity rather than as a move.</p>
     */
    public static String formatChange(double percent) {
        ...
        return (percent > 0 ? "+" : percent < 0 ? "-" : "")
                + format.format(Math.abs(percent)) + "%";
```

Com `percent == 0.0` sai `"0,00%"`, sem sinal — o caso exato que o javadoc diz
não existir. Ou o zero também leva `+`, ou o javadoc diz "quando houve
movimento".

### B4-25. `BarReadout` e `RulerReadout` carregam quatro auxiliares idênticos

`format(int)`, `colourOf(String)`, `withAlpha(Color, float)` e a montagem da
caixa (`place`, medição de larguras, moldura) estão duplicados entre
`BarReadout.java:82-145, 240-270` e `RulerReadout.java:55-97, 149-171`. As
constantes `PADDING` e `ALPHA = 0.94f` também. Os dois desenham a mesma caixa; a
única diferença real é onde ela se ancora. Duas respostas para a mesma pergunta,
e ambas usam a mesma sentinela de texto `"up"/"down"/"flat"` onde caberia um
`enum`.

### B4-26. `OwnScale.map` e `OwnScale.smooth` repetem o mesmo ponteiro corrente

`OwnScale.java:112-123` e `OwnScale.java:146-155` — as oito linhas do avanço do
ponteiro (`while (closed + 1 < coarse.size() - 1 && ...)` mais o caso inicial)
são idênticas. É a aritmética que o javadoc da classe chama de "a armadilha que
este projeto já pagou uma vez"; tê-la em dois lugares é duas chances de errar a
próxima correção. Extrair um `advance(fine, coarse, closed, i)`.

### B4-27. `Forms.field` constrói um `JTextField` descartável por linha só para medir uma altura

`Forms.java:113` (e a cópia em `MovingAverageDialog.java:341`)

```java
        int floor = new JTextField("X").getPreferredSize().height;
```

Um componente Swing completo, criado e jogado fora, por linha de formulário. O
valor não depende da linha. Um `static` calculado uma vez (ou lido de
`UIManager`) faz o mesmo.

### B4-28. `InsertOverlayDialog` injeta texto do bundle em HTML sem escapar, onde `SeriesSummary` escapa

`InsertOverlayDialog.java:328`

```java
        JLabel reason = new JLabel("<html><body style='width:250px'>" + why + "</body></html>");
```

`why` vem de `Messages.get("overlay.where.notOnPrice", kind.label())`, com o
nome do indicador dentro. `SeriesSummary.java:146-149` resolve o mesmo problema
com um `escape`, e explica por quê: *"A name typed by the reader tomorrow could
contain any of them, and a tooltip that swallows half of itself is a defect
nobody would connect to a name."* Duas respostas para a mesma pergunta, e a mais
frouxa é a que fica na frente do leitor.

### B4-29. `Kind.minimum`/`maximum` só são obedecidos pelo diálogo, não pelo caminho de carga

`OverlayCatalog.java:45-52` documenta *"@param minimum the lowest any parameter
may be"*, mas `OverlayCatalog.build` (120-136) e `ChartLayout.Entry.build`
(112-136) entregam os números direto à `factory()`, sem consultá-los. Só
`InsertOverlayDialog.showParametersFor:248-249` os aplica, via
`SpinnerNumberModel`. Guarda que protege menos do que diz. O dano é contido
porque cada indicador se defende de novo — `MovingAverage:154`,
`this.period = Math.max(1, period)` — mas isso é a terceira resposta para a
mesma pergunta.

### B4-30. `RandomWalkSeries`: a instrução de apagar está velha e a promessa de determinismo é meia

`RandomWalkSeries.java:36`

```java
 * <p>Delete this class the moment a real series is wired in.</p>
```

Séries reais estão ligadas há tempo (`SeriesCatalog`, `MarketService`), e a
classe continua em uso — `MainWindow.java:555` e `ui/replay/ReplaySession.java`.
A instrução, hoje, é falsa. E `RandomWalkSeries.java:33-34` diz *"The seed is
fixed so the same picture comes back every run"*, mas o construtor de dois
argumentos (linha 57) usa `System.currentTimeMillis()` como instante da primeira
barra: os preços repetem, os horários não. Ou o javadoc muda, ou a classe sai.

### B4-31. "Usar período próprio" pode ficar marcado sem período escolhido

`MovingAverageDialog.java:378` e `BollingerBandsDialog.java:355`

```java
        average.setOwnPeriod(ownPeriod.isSelected() ? periodCode : null);
```

Marcar a caixa sem clicar em "Escolher..." deixa `periodCode` nulo, e o
indicador volta a seguir a escala do gráfico com a caixa marcada. `refreshPeriod`
habilita o botão mas nada exige que ele seja usado, e o `OK` aceita. Ou o `OK`
recusa a combinação, ou marcar a caixa abre o `PeriodDialog` na hora.

---

# LIMPO

O que foi conferido e está certo, e como.

**`Viewport`** — a aritmética de `x`, `barAt`, `y` e `priceAt` fecha: `of()`
garante `high > low` antes de qualquer divisão (linhas 134-142, incluindo o caso
de nenhuma barra) e `barCount >= 1` (linha 120), então `barWidth` e `span` nunca
são zero. `barsPerColumn` devolve no mínimo 1. A varredura de preço percorre só
`min(first + count, series.size())` — os *slots* vazios à direita não puxam a
escala, que era a armadilha óbvia.

**`Viewport.barAt` devolvendo índice fora da série** — investiguei como possível
ALTA. `barAt` limita a `lastBar() - 1`, e `lastBar()` conta *slots*, que passam
do fim da série de propósito. `ChartCanvas.hoveredBar:1530` devolve isso sem
limitar de novo. Verifiquei todos os consumidores: `BarReadout.paint:83` testa
`index >= series.size()`; `ChartCanvas.cursorReading:640` testa o mesmo;
`overlay/MovingAverage.valueAt:294` testa `at < values.length`;
`overlay/BollingerBands.valueAt:350` testa `bar >= middle.length`;
`OverlayLegend.readAt` só repassa a quem já se defende. Todos protegidos —
**refutado**.

**`OwnScale.smooth` sobre um vetor não preenchido** — investiguei se `smooth`
poderia deixar zeros onde devia haver NaN, já que ele faz `continue` em vez de
escrever. Conferi os cinco chamadores: `overlay/MovingAverage:373-376`,
`overlay/BollingerBands:403-410` e `study/rsi/RelativeStrength:356-359` chamam
`map` imediatamente antes; `study/stochastic/SlowStochastic:506-507` chama só
`map`. Nenhum chama `smooth` sozinho — **refutado**.

**`OwnScale.indexOfClosed` e a regra da escala maior** — a busca binária nunca
devolve a última barra grossa (a condição exige `middle + 1 < coarse.size()`), e
`map`/`smooth` param em `coarse.size() - 1`. As três rotinas concordam: só barra
grossa **fechada**. `smooth` interpola entre `slow[closed-1]` e `slow[closed]`,
ancorando a rampa em `coarse.timeAt(closed + 1)` — o instante em que
`slow[closed]` passou a ser conhecível. Não lê o futuro.

**`Reordering.move`** — conferido contra `LayoutOrderTest`, que tem dentes:
`draggingRight`, `draggingLeft`, `droppingPastTheEnd`, `droppingWhereItAlreadyIs`
(as duas formas de "no próprio lugar") e `nonsenseIsRefused` (índice negativo,
grande demais, gap negativo, gap grande demais). Os casos de borda estão
cobertos e as asserções comparam listas inteiras, não tamanhos. Só a fragilidade
de B4-19 fica.

**`PeriodCatalog.brickOf`** — `(name - 1) * TICK` com `TICK = 5.0`: 11R = 50
pontos, 5R = 20. Bate com a regra de domínio ("a caixa é `(n−1) × tick`") e com
o mínimo de 3R justificado (2R seria um tick, o tape desenhado em caixas).
`SMALLEST_BRICK`/`LARGEST_BRICK` são respeitados no único lugar que constrói
renko a partir de número digitado (`forText:146`).

**`CandleStyle` agregando colunas** — quando `barsPerColumn() > 1`, a coluna leva
a abertura da primeira barra, o fechamento da última, a máxima e a mínima de
todas (linhas 96-115); e `gap &= Untraded.at(series, k)` só pinta cinza quando
**todas** as barras da coluna são intocadas, que é o que o comentário promete.
`drawHeight = max(1, round(bottom) - first + 1)` cobre a última linha do pavio —
conferido contra o caso do tijolo de renko, cuja mínima é o próprio fechamento.

**`LineStyle` agregando colunas** — dois pontos por coluna (mínima e máxima dos
fechamentos, na ordem em que o mercado os alcançou, via `fellFirst`), e os
vetores dimensionados exatamente: `columns = ceil((to-from)/step)`, vetor
`columns*2` quando `step > 1` e `columns` quando é 1. Contei as escritas do laço
contra o tamanho alocado — não estoura e não sobra.

**`Sessions.previousDayClose`** — anda para trás a partir da barra, devolve `NaN`
(nunca zero, nunca um número inventado) quando a barra pertence ao primeiro dia
da série. `percentChange` devolve `NaN` quando a referência é zero ou não
finita, com a razão escrita. Coerente com a regra "ausente é NaN, nunca zero".

**`Measurement`** — `between` devolve `null` quando qualquer das duas barras está
fora da série, `bars()` conta inclusivo (o que o leitor contou na tela),
`percent()` devolve `NaN` a partir de zero, `elapsedInWords` omite as unidades
zeradas e junta a última com "e". Tudo pelo bundle (`ruler.day`, `ruler.hour`,
`ruler.minute`, com singular e plural separados).

**`ChartHolder.sizeToRemember` e `opensMaximised`** — as duas foram extraídas
como `static` testáveis, e a lógica fecha: maximizado grava `getNormalBounds()`,
não os limites correntes; limites inutilizáveis (`width <= 40`) não gravam nada,
deixando o tamanho de nascimento assumir. `opensMaximised` faz o lembrado ganhar
da contagem, como o javadoc diz.

**`ChartHolder.restoredLocation`** — a posição guardada só é honrada se algum
dispositivo gráfico ainda cobre a barra de título (`onSomeScreen`, linhas
837-848), o que resolve o monitor desconectado. O deslocamento em cascata usa
`opened++ % 8`, limitado.

**`ChartHolder.attach`/`detachReplay`** — a segunda queda de replay desinscreve
a primeira (`detachReplay.run()` na linha 291) e `beforeReplay` só é gravado na
primeira vez, então fechar o replay devolve a série original e não uma outra
sessão. `close()` chama `canvas.releaseTicks()`, com o comentário registrando o
vazamento que isso corrigiu.

**Ouvintes do canvas** — verifiquei se reconstruir a barra de ferramentas a cada
`dock()`/`floatIt()` acumularia ouvintes: `ChartCanvas.onScaleChanged`,
`onOverlaysChanged`, `onOverlaysRedrawn`, `onSeriesChanged` e `onInsertWanted`
são campos `Runnable` de posição única, com o *setter* substituindo (linhas
620-700). Cada nova barra substitui a anterior; nada acumula. **Refutado** como
vazamento.

**`RulerMode` e `ChartPreferences`** — `CopyOnWriteArrayList` para os ouvintes,
com `listen`/`forget` emparelhados e um `listenerCount()` de pacote existindo só
para o teste de vazamento. `window()` limita na saída **e** na entrada, com a
razão escrita (arquivo editado à mão).

**`ChartLayouts.parse`/`format`** — o quarto campo (`appearance`) é acrescentado
no fim e lido só se existir, então uma linha escrita por versão anterior carrega.
Linha malformada é pulada, não derruba o layout inteiro. Conferi que `save`
apaga o rabo além do novo fim (linhas 103-107), senão um layout removido
voltaria na próxima abertura.

**`ChartHeader.offered()` e `menuFor()`** — ambos de visibilidade de pacote
justamente para serem testáveis sem tela, e o replay zera a oferta
(`showing == null ? ... : List.of()`), que é a decisão certa: o que está tocando
não é esta série. A cor do segmento vem da **posição** na lista
(`SeriesColors.taken(i)`), igual à janela de séries — mesmo segmento, mesma cor
nos dois lugares.

**`SeriesSummary.sessionsIn`** — delega ao `domain.market.Sessions`, que cacheia
por série com `WeakHashMap`, sincroniza só o mapa (nunca a travessia) e cresce
só na cauda quando a série cresceu. O custo restante por passagem de mouse é a
cópia defensiva de um `TreeSet` de ~1.494 datas, quando só o `size()` é usado —
mensurável, mas microssegundos; não abri achado.

**`InsertOverlayDialog`** — a ordem importa e está certa: `showKind` chama
`showParametersFor` (que reconstrói os *spinners*) antes de `showDestinationsFor`
(que constrói a amostra a partir deles). `selectFor` marca o índice **antes** de
escrever os valores, então os *spinners* já existem. O destino que não aceita o
indicador fica desabilitado com a razão embaixo, e a seleção cai sempre num
habilitado (linha 307) — o botão "Inserir" nunca fica sem efeito.

**`PeriodDialog`** — modal, `DISPOSE_ON_CLOSE`, `chosen` lido depois do
`setVisible`; setas movem a lista com o cursor parado na caixa; `Enter` toma a
primeira. `refresh` reseleciona o índice 0 depois de limpar o modelo, então
`Enter` nunca age sobre nada.

**Bundle** — conferi que todas as chaves usadas nesta área existem nos dois
arquivos e que os `{0}` batem com os argumentos passados: `readout.*`,
`summary.*`, `ruler.*`, `layout.*`, `chart.*`, `overlay.*`, `period.*`. A única
divergência entre os dois idiomas que encontrei é ortográfica no
`messages_pt_BR.properties` ("Serie", "Duracao", "Pregoes", "Inicio" sem acento),
e a convenção 9 manda o inglês na base e o português traduzido — não é o mesmo
caso das cadeias sem acento do `endeavour`. Não abri achado por não ter certeza
de que é acidente.
