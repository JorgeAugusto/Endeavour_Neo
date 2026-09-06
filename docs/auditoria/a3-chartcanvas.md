# A3 — ui/chart: ChartCanvas

**Linhas lidas:** 2.650 de 2.650
**Achados:** 4 ALTA, 9 MÉDIA, 13 BAIXA

Arquivo auditado: `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartCanvas.java`.
Consultados para confirmar ou derrubar achados (não auditados): `Viewport`, `Overlay`,
`overlay/MovingAverage`, `overlay/BollingerBands`, `style/CandleStyle`, `RenkoSource`,
`ChartPreferences`, `ChartHolder`, `study/StudyStack`, `study/StudyPane`,
`domain/market/TickLibrary`, `domain/market/TickRenko`, `ui/replay/ReplayDrop`,
`ui/replay/ReplaySession`, `ui/shell/MainWindow`, `messages.properties`, e os testes
`ChartCanvasTest`, `ChartGeometryTest`, `TickRenkoOnChartTest`, `TimeAxisTest`.

---

## Achados ALTA

### A3-1. Zoom total de uma série de 1.050.000 barras varre a série inteira em cinco laços, com alocação por barra, a cada movimento do mouse

`ChartCanvas.java:2631`

```java
            int zoomed = (int) Math.round(visibleBars * (e.getWheelRotation() > 0 ? 1.25 : 0.8));

            visibleBars = Math.max(MINIMUM_VISIBLE_BARS, Math.min(zoomed, series.size()));
```

`ChartCanvas.java:1786`

```java
            for (int line = 0; line < lines; line++) {
                g.setColor(colours.get(line));

                int lastX = Integer.MIN_VALUE;
                int lastY = 0;

                for (int i = from; i < to; i++) {
                    double[] row = overlay.valueAt(i);
```

`ChartCanvas.java:2421`

```java
        public void mouseMoved(MouseEvent e) {
            cursor = e.getPoint();
            onCursorChanged.run();
```
(termina em `repaint();`, linha 2429)

**Problema.** Não há decimação em lugar nenhum. `visibleBars` é limitado por cima apenas
por `series.size()`, tanto na roda (2633) quanto no arrasto do eixo de tempo
(`barsForDrag`, 1472-1477: `Math.max(MINIMUM_VISIBLE_BARS, available)`), então o caso
"tudo na tela" é alcançável com meia dúzia de cliques de roda. Com toda a série visível,
um único `paintComponent` percorre a série inteira **cinco vezes**: `Viewport.of`
(Viewport.java:129), `style.paint` (CandleStyle.java:82, sem salto), `paintOverlays`
(1792), `paintTimeAxis` (1894) e `paintDayBand` (1977). Em `paintOverlays` o laço das
linhas é o EXTERNO e o das barras o interno, então `overlay.valueAt(i)` é chamado
`linhas × barras` vezes — e `MovingAverage.valueAt` devolve `new double[]{...}` a cada
chamada (MovingAverage.java:282). Com as três médias que `MainWindow` põe em todo gráfico
novo (MainWindow.java:445), são 3.150.000 arrays de um elemento por repintura. E
`mouseMoved` chama `repaint()` — a área inteira, sem retângulo sujo — a cada pixel de
movimento do mouse.

**Consequência.** O gráfico congela ao afastar o zoom numa série de um minuto, que é o
caso NORMAL declarado. Não é "fica lento": cada movimento do mouse dispara alguns milhões
de alocações e cinco varreduras de 1,05 milhão de barras na EDT.

**Correção.** Duas coisas, independentes: (a) limitar `visibleBars` por um teto ligado à
largura em pixels (nada abaixo de meio pixel por barra é legível de qualquer forma), o que
resolve os cinco laços de uma vez; (b) inverter os laços de `paintOverlays` — barra por
fora, linha por dentro — para chamar `valueAt(i)` uma vez por barra em vez de uma vez por
linha, e usar `GeneralPath` por linha em vez de `drawLine` por segmento.

**Tentei refutar assim:** procurei salto/decimação em `CandleStyle.paint` (só
`for (int i = viewport.firstBar(); i < viewport.lastBar() && i < series.size(); i++)`,
sem passo), procurei um teto de `visibleBars` em `clampFirstBar`, em `barsForDrag` e no
tratador da roda (só `MINIMUM_VISIBLE_BARS` por baixo e `series.size()` por cima), e
procurei `RepaintManager`/`repaint(x,y,w,h)` com retângulo no arquivo (só o override de
1373, que repassa a área inteira). Procurei teste que fixasse um teto: `ChartCanvasTest`
cobre `stretchForDrag`, `barsForDrag` e `niceTimeStep`, nenhum toca no número de barras
visíveis contra o tamanho da série. Nada derrubou.

---

### A3-2. `rebuildFromTicks` varre a série inteira e a árvore de arquivos de ticks na EDT antes de criar o SwingWorker

`ChartCanvas.java:1011`

```java
        java.util.List<java.time.LocalDate> onScreen = RenkoSource.sessionsIn(source);
        br.com.jorge.reis.endeavourneo.domain.market.TickSource which =
                sourceForBricks(onScreen);
```

`ChartCanvas.java:984`

```java
            try {
                if (RenkoSource.allows(source, library, false)) {
                    return each;
                }
            } finally {
                library.close();
            }
```

`ChartCanvas.java:1029`

```java
        if (!RenkoSource.allows(source, library, false)) {
```

**Problema.** Tudo isso corre no chamador, que é `refold()` — ou seja, na EDT, dentro de
`setSeries` e de `setPeriod`. `RenkoSource.allows` percorre `series.size()` barras fazendo
`Instant.ofEpochMilli(...).atZone(zone).toLocalDate()` (RenkoSource.java:96-108) e antes
disso chama `library.exported()`, que é um `Files.walk(folder, 4)` sobre a pasta de ticks
(TickLibrary.java:275-283). `sourceForBricks` faz isso para PROFIT e depois para
METATRADER, e a linha 1029 faz uma terceira vez. Somando `RenkoSource.sessionsIn`, são até
quatro varreduras da série completa e três varreduras de disco antes que o
`SwingWorker.execute()` da linha 1128 seja sequer criado.

**Consequência.** Trocar para renko num gráfico de 1,05 milhão de barras trava a interface
por segundos, e o comentário de `refold` (1283-1286) afirma o contrário: *"The candles
first, always. They are instant, so the chart is never blank"* — os candles são instantâneos,
mas a chamada que vem logo depois, na mesma linha de execução, não é.

**Correção.** Mover a escolha da fonte (`sourceForBricks`, `RenkoSource.allows`,
`sessionsIn`) para dentro do `doInBackground`, deixando na EDT apenas o `stopGrowing()` e
as guardas baratas de 1006-1009. O resultado já é validado em `done()`; a decisão de qual
export usar não precisa de nenhum estado da interface.

**Tentei refutar assim:** verifiquei se `refold` já corre fora da EDT (não: é chamado de
`setSeries`/`setPeriod`, e os chamadores em `MainWindow:436` e `ChartHolder:299` estão na
EDT); verifiquei se `allows` tem atalho barato — tem, `mayInvent`, mas as três chamadas
daqui passam `false` **fixo** (comentário de 1030-1032 explica por quê), então o atalho
nunca vale nesse caminho; verifiquei se `exported()` guarda o resultado em cache (não,
refaz o `Files.walk` a cada chamada). Nada derrubou.

---

### A3-3. O caminho de replay põe a série nova na tela sem recalcular os indicadores

`ChartCanvas.java:1094`

```java
                    if (replaying) {
                        growing = built;
                        growingFrom = library;

                        // The session in progress is carried in by the next
                        // frame, which is a fortieth of a second away.
                        extendBricks();
                        repaint();

                        return;
                    }
```

**Problema.** `extendBricks()` troca `this.series` por `growing.live()` (1276) — os
tijolos de tick. Os overlays ainda carregam os valores calculados em `refold()` contra a
série de CANDLES (1295-1297). Os outros dois caminhos que trocam a série recalculam:
`show()` faz o laço em 1138-1140 e `seriesGrew()` faz em 1187-1189. Só este não faz. É
exatamente o que o comentário de `refold` proíbe: *"an overlay still holding values from
the previous series would draw a line that belongs to other data, and it would look
plausible"* (1292-1294). E não há reenquadramento: `visibleBars`/`firstBar` continuam os da
série de candles, sem passar por `clampFirstBar`.

**Consequência.** A média móvel desenhada sobre os tijolos é a média dos candles indexada
por índice de tijolo — uma linha plausível e falsa. Com o replay TOCANDO, o quadro seguinte
(`seriesGrew`, ~1/40 s) conserta; com o replay PAUSADO no momento em que o worker termina —
que é o caso de arrastar um replay parado sobre um gráfico em renko — não há quadro
seguinte, e a linha errada fica na tela indefinidamente (repintar não recalcula).

**Correção.** Repetir aqui o laço `for (Overlay overlay : overlays) overlay.calculate(this.series);`
e o reenquadramento de `seriesGrew` (1191-1193) — ou, melhor, extrair um único
`adoptSeries(PriceSeries)` que os três caminhos chamem, já que os três fazem a mesma coisa
com três corpos diferentes.

**Tentei refutar assim:** procurei recálculo dentro de `extendBricks` (não há: só
`growing.advance` e a troca de `this.series`); procurei se `valueAt` fora de faixa
estouraria — não estoura, `MovingAverage.valueAt` devolve NaN fora do array
(MovingAverage.java:282) e `BollingerBands.valueAt` também (BollingerBands.java:350), então
não é exceção na pintura, é número errado desenhado; verifiquei se o Timer do replay salva
(`ReplaySession` usa `javax.swing.Timer`, ReplaySession.java:275) — salva enquanto toca, e
é justamente por isso que o defeito só é permanente com o replay parado. Não derrubou.

---

### A3-4. A guarda contra resultado atrasado só olha o período — trocar a série deixa uma construção velha sobrescrever a que está na tela

`ChartCanvas.java:1084`

```java
            protected void done() {
                if (asked != period) {
                    library.close();

                    return;
                }
```

`ChartCanvas.java:1044`

```java
        boolean replaying = playing != null;
```

**Problema.** O único critério de descarte é `asked != period`. `ChartHolder.attachReplay`
chama `canvas.setSeries(live)` (ChartHolder.java:299) e `detachReplay` chama
`canvas.setSeries(beforeReplay)` (ChartHolder.java:330) — ambos **sem mexer no período**.
Se o gráfico já está em renko, o worker disparado pelo `setPeriod` anterior ainda pode
estar em voo quando o replay é acoplado: seu `done()` vê `asked == period`, não sabe que a
série mudou, e chama `show(bricks)` (1114) trocando a série ao vivo do replay por tijolos
históricos. A mesma captura vale para `replaying`, lido em 1044 no momento do envio e nunca
reconferido em `done()`: um worker enviado durante o replay que termina depois de
`detachReplay` reinstala `growing`/`growingFrom` para um replay que já acabou.

**Consequência.** O gráfico do replay pisca entre três séries diferentes (tijolos
históricos, tijolos ao vivo, renko de candles pela queda em `seriesGrew`:1200) — que é a
mistura candle/tick que a classe inteira existe para impedir, e a mesma família do defeito
já corrigido e documentado em 997-1003 (*"go to renko and it stops, and then it will not go
back to minutes"*).

**Correção.** Marcar a geração: um `int rebuildGeneration` incrementado em `stopGrowing()`
(que já é chamado no começo de todo `rebuildFromTicks`), capturado junto de `asked`, e
comparado em `done()`. Isso cobre período, série, instrumento e replay de uma vez, em vez
de uma comparação por dimensão. Reler `playing != null` dentro de `done()` em vez de usar
a cópia de 1044.

**Tentei refutar assim:** procurei outra guarda em `show()` (não há: só o clamp de
`firstBar`); procurei se `setSeries` cancela o worker (`refold` chama `stopGrowing()` via
`rebuildFromTicks`, mas `stopGrowing` só zera `growing`/`growingFrom` — não toca no worker
em voo nem no seu `done()`); li o teste que cobre este ponto, `TickRenkoOnChartTest`
:313 *"changing the period drops the build that was already running"* — cobre a troca de
PERÍODO, e só ela; nenhum teste troca a série com o período parado. Não derrubou.

---

## Achados MÉDIA

### A3-5. A guarda do diálogo só conhece o export METATRADER; o construtor tenta PROFIT primeiro

`ChartCanvas.java:1154`

```java
    private boolean renkoAllowed() {
        return RenkoSource.allows(series,
                new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                        br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.ticksOf(
                                RenkoSource.rootOf(instrument)),
                        RenkoSource.rootOf(instrument),
                        br.com.jorge.reis.endeavourneo.domain.market.TickSource.METATRADER),
                ChartPreferences.syntheticTicks());
```

contra `ChartCanvas.java:976`

```java
        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource each
                : new br.com.jorge.reis.endeavourneo.domain.market.TickSource[]{
                    br.com.jorge.reis.endeavourneo.domain.market.TickSource.PROFIT,
                    br.com.jorge.reis.endeavourneo.domain.market.TickSource.METATRADER}) {
```

**Problema.** Duas respostas para a mesma pergunta. `sourceForBricks` procura a fita do
Profit primeiro — e o comentário de 962-965 diz por quê: *"the tape first, because it is
the trades themselves rather than a quote stream"*. `renkoAllowed`, que decide se o diálogo
aceita ou recusa, só olha METATRADER. Um instrumento cujos pregões existem apenas na fita
do Profit é recusado por uma guarda que teria sido atendida pelo construtor. Além disso a
`TickLibrary` criada aqui nunca é fechada, ao contrário da de `sourceForBricks`, que fecha
em `finally` (988-990).

**Consequência.** Com `syntheticTicks` desligado — que é a configuração em que esta guarda
existe para agir — o leitor recebe a mensagem `chart.renkoNeedsTicks` ("Não há ticks
gravados para todos os pregões na tela"), que é falsa: há, na fita. O renko é recusado sem
recurso.

**Correção.** `renkoAllowed()` deve perguntar `sourceForBricks(RenkoSource.sessionsIn(source)) != null`
— a mesma função que o construtor usa — e fechar o que abrir.

**Tentei refutar assim:** conferi se `syntheticTicks` sendo `true` por omissão
(ChartPreferences.java:77) torna o caminho inalcançável — não torna: com `mayInvent` a
`allows` devolve `true` e a guarda some, mas o defeito é justamente no modo em que ela age,
e o comentário de 891-894 diz que esse modo é uma escolha explícita do leitor. Conferi se
`series` e `source` dariam dias diferentes (não relevante: a divergência é o export, não a
série). Não derrubou.

### A3-6. Nenhum tratador de mouse confere o botão: o clique direito apaga a medição e recentra o gráfico

`ChartCanvas.java:2480`

```java
            if (mode == Mode.MEASURE && !onAxis(e.getX()) && !onTimeAxis(e.getY())) {
                Viewport viewport = viewport();

                rulerBar = viewport.barAt(e.getX());
                rulerPrice = viewport.priceAt(e.getY());
                measurement = null;
```

`ChartCanvas.java:2518`

```java
            if (e.getClickCount() == 2 && !onAxis(e.getX()) && !onTimeAxis(e.getY())
                    && (jumpBounds() == null || !jumpBounds().contains(e.getPoint()))) {
                centreChart();
            }
```

**Problema.** `installContextMenu` registra um segundo `MouseAdapter` (432) que abre o menu
em `isPopupTrigger`, mas o `Mouse` interno recebe o mesmo `mousePressed` e não pergunta qual
botão foi. Em modo de medição, abrir o menu de contexto zera `measurement`. Fora dele, o
clique direito ainda arma `grabbedAt`/`grabbedY`, e o duplo clique com o botão direito
recentra o gráfico.

**Consequência.** O leitor mede um trecho, clica com o direito para inserir um indicador, e
a medição some sem nada dizer que sumiu.

**Correção.** `if (!javax.swing.SwingUtilities.isLeftMouseButton(e)) return;` no início de
`mousePressed` e de `mouseClicked` do `Mouse`.

**Tentei refutar assim:** procurei guarda de botão em `Mouse` (não há nenhuma menção a
`isLeftMouseButton`, `getButton` ou `BUTTON1` no arquivo inteiro); conferi se o
`MouseAdapter` do menu consome o evento (não consome — `JPopupMenu.show` não impede a
entrega ao outro listener registrado no mesmo componente); conferi `MeasurementTest` e
`RulerModeTest` — testam a aritmética e o interruptor global, não o gesto. Não derrubou.

### A3-7. O zoom da roda ancora pela largura do componente, não pela do gráfico

`ChartCanvas.java:2628`

```java
            int anchor = viewport().barAt(e.getX());
            double share = (e.getX() - 0.0) / Math.max(1, getWidth());
```

**Problema.** `viewport().barAt(x)` mapeia sobre `plotBounds().width`, que é
`getWidth() - AXIS_WIDTH` (1403). A fração usada para recolocar `firstBar` divide pela
largura TOTAL. São 62 pixels de diferença: a 640 de largura, a fração fica 10% menor do que
deveria. O comentário logo acima (2625-2627) afirma *"The bar under the cursor stays under
the cursor"*, e ela não fica.

**Consequência.** A cada nível de zoom a barra sob o cursor escorrega para a esquerda; o
erro se acumula porque o leitor gira a roda várias vezes seguidas. É precisamente o efeito
que o comentário diz ser *"the single thing that most makes a chart feel wrong"*.

**Correção.** `double share = e.getX() / (double) Math.max(1, plotBounds().width);` — e um
teste estático da recolocação, como já existe para `barsForDrag`.

**Tentei refutar assim:** verifiquei se `Viewport.bounds.x` seria diferente de zero e
compensaria (não: `plotBounds()` devolve `new Rectangle(0, 0, ...)`); verifiquei se
`clampFirstBar` mascara o erro (não — só limita as pontas). Não derrubou.

### A3-8. `storeView` diz guardar "how far along" e não guarda a posição

`ChartCanvas.java:1627`

```java
    public void storeView(br.com.jorge.reis.endeavourneo.platform.Settings into, String prefix) {
        into.putInt(prefix + "visibleBars", visibleBars);
        into.putInt(prefix + "rightMargin", rightMargin);
```

`ChartCanvas.java:1657`

```java
        firstBar = clampFirstBar(series.size() - visibleBars + rightMargin);
```

**Problema.** O javadoc (1617-1625) promete *"how far in, how far along, how stretched, how
slid"*. `firstBar` não é escrito, e `restoreView` o recalcula sempre a partir do FIM da
série.

**Consequência.** Um gráfico deixado em março de 2021 reabre na última barra. O próprio
javadoc diz que isso é o que torna a persistência necessária — *"Reopening a chart that
comes back at the default zoom is a chart that has to be set up again every morning"* — e é
metade do que acontece.

**Correção.** Guardar a distância do fim (`series.size() - firstBar`) e não `firstBar` cru,
porque a série cresce entre uma sessão e outra; restaurar por essa distância, ainda passando
por `clampFirstBar`.

**Tentei refutar assim:** procurei `firstBar` em `Settings` no resto do projeto (nenhuma
outra classe grava a posição do canvas); conferi se `rightMargin` faria as vezes — não faz,
é só o ar depois da última barra, e o comentário de 2642-2645 diz que ele é zero quando o
leitor está no passado, que é exatamente o caso que se quer restaurar. Não derrubou.

### A3-9. Três regras diferentes de arredondamento de preço, e o rodapé usa a que o próprio arquivo diz que não pode existir

`ChartCanvas.java:643`

```java
        return java.time.Instant.ofEpochMilli(series.timeAt(bar))
                .atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM HH:mm"))
                + "   " + new java.text.DecimalFormat("#,##0.00",
                        java.text.DecimalFormatSymbols.getInstance(java.util.Locale.getDefault()))
                        .format(series.closeAt(bar));
```

**Problema.** O eixo e a etiqueta do cursor usam `formatFor(gridStep(viewport))`
(1842, 2131, 2365), cujo comentário diz *"Same steps as the grid, so every line has its
number... Computing them twice from the same rule would eventually drift apart when one of
the two is edited"* (1820-1824). `cursorReading` é a terceira cópia, com duas casas fixas —
e já divergiu. O padrão de data também é uma quarta forma, ao lado de `CLOCK`, `DAY` e
`CURSOR_TIME`.

**Consequência.** No WIN, que anda de ponto em ponto, o eixo escreve `127.500` e o rodapé
escreve `127.500,00` para o mesmo preço. O comentário de 2363-2364 diz o que isso parece:
*"two labels of the same price that disagree in the last digit read as a bug in the chart"*.

**Correção.** `cursorReading()` deve usar `formatFor(gridStep(viewport()))` e uma constante
`DateTimeFormatter` ao lado das outras quatro.

**Tentei refutar assim:** conferi se `formatFor` é acessível dali (é `private static` no
mesmo arquivo); conferi se o rodapé quer mesmo duas casas por alguma decisão registrada
(nada no arquivo diz isso — o javadoc de `formatFor`, 2273-2276, diz o contrário: casas
fixas ou imprimem `177.600,00` num índice ou arredondam um par de moedas até a
inutilidade). Não derrubou.

### A3-10. Sete javadocs órfãos, cada um documentando o membro errado

`ChartCanvas.java:87`

```java
    /** Flatter than this and the candles are a line; taller and they leave the screen. */
    /**
     * How far past the last bar the window may go, as a share of its width.
```

`ChartCanvas.java:236`

```java
    /**
     * Told when the overlays change in a way that should be WRITTEN DOWN.
     ...
    /**
     * The panes drawn from this chart's viewport.
```

**Problema.** Em Java só o último bloco antes do membro vale. Há sete casos: 87 (texto de
um limite de esticamento, colado em `AIR_RIGHT`), 236 (texto de `onOverlaysChanged`, colado
em `followers`), 487 (*"Control on its own toggles the mode"*, colado em `installDigits` —
`installControlToggle`, em 528, ficou sem nenhum), 567 (`@param newSeries`, colado em
`setOverlays(List<Overlay> replacements)`), 906 (*"Builds the renko from the exchange's own
ticks"*, colado no campo `playing`), 1212 (*"@return whether the growing renko could be
carried"*, colado em `clockNow()`) e 1593 (duas linhas de javadoc em `resetStretch`).

**Consequência.** A convenção da casa é que o comentário explica o porquê; um comentário
grudado no membro errado explica o porquê de outra coisa. `setOverlays` documenta um
parâmetro `newSeries` que não existe, e a decisão medida sobre a construção assíncrona dos
tijolos aparece na documentação de um campo.

**Correção.** Apagar o bloco órfão onde ele é resto de recorte (87, 236, 567, 1593) e mover
para o membro certo onde ele é documentação viva (487 → `installControlToggle`, 906 →
`rebuildFromTicks`, 1212 → `extendBricks`).

**Tentei refutar assim:** confirmei em cada caso que existe um segundo `/** ... */` entre o
primeiro e o membro (é o que torna o primeiro inerte), e que o membro que o texto descreve
está em outro lugar do arquivo — por exemplo `rebuildFromTicks` em 996, sem javadoc nenhum.
Não derrubou.

### A3-11. A `TickLibrary` do replay vaza: sobrescrita sem fechar, e nunca fechada ao fechar o gráfico

`ChartCanvas.java:1094`

```java
                    if (replaying) {
                        growing = built;
                        growingFrom = library;
```

`ChartCanvas.java:1516`

```java
    @Override
    public void removeNotify() {
        RulerMode.forget(followRuler);
        ChartPreferences.forget(followDrawing);

        super.removeNotify();
    }
```

**Problema.** `growingFrom = library` atribui direto. Se dois workers pousam, o segundo
descarta a biblioteca do primeiro sem `close()`. E `removeNotify` — que já é o lugar onde
este arquivo desfaz o que `addNotify` fez — não chama `stopGrowing()`, então um gráfico
fechado no meio de um replay deixa a biblioteca aberta. `TickLibrary.close()` faz
`loader.shutdownNow()` e `forget()`, que descarta até `RESIDENT = 3` sessões de tick
residentes (TickLibrary.java:61, 202-206).

**Consequência.** Retenção de até três sessões de ticks por gráfico fechado — e de um
`ExecutorService` de fato, se alguém tiver chamado `request()` naquela biblioteca. O padrão
correto está no próprio arquivo, em `stopGrowing` (949-956) e no `finally` de 988-990.

**Correção.** `stopGrowing()` antes de atribuir em 1095-1096, e `stopGrowing()` em
`removeNotify` antes do `super`.

**Tentei refutar assim:** procurei outro `close()` no caminho (só 989, 1033, 1106 e 1121, e
nenhum deles cobre a sobrescrita nem o fechamento do gráfico); conferi se `stopGrowing`
seria chamado por `setTickSource(null)` no fechamento — `ChartHolder.detachReplay` chama,
mas só quando o replay TERMINA, não quando a janela do gráfico é fechada com o replay
tocando. Não derrubou.

### A3-12. Ligar e desligar os rabos do renko joga fora o zoom e a posição do leitor

`ChartCanvas.java:856`

```java
    public void setWicks(boolean show) {
        if (period instanceof br.com.jorge.reis.endeavourneo.domain.market.Renko renko) {
            // The label is carried across: turning the tails off does not change
            // which period this is, and the title must not start saying
            // something else because a switch was flipped.
            setPeriod(renko.withWicks(show), periodLabel, periodCode);
```

`ChartCanvas.java:1298`

```java
        this.visibleBars = Math.min(DEFAULT_VISIBLE_BARS, Math.max(1, this.series.size()));
        this.rightMargin = birthMargin();
        this.firstBar = clampFirstBar(this.series.size() - visibleBars + rightMargin);
```

**Problema.** `setWicks` passa por `setPeriod`, que chama `refold()`, que reenquadra tudo
para os 120 candles padrão. O comentário defende que o rótulo é carregado justamente para
que nada mude além dos rabos — e a única coisa que não muda é o rótulo.

**Consequência.** O leitor, olhando um trecho de 2021 com 400 tijolos na tela, liga os
rabos e volta para os últimos 120 tijolos do fim da série.

**Correção.** `refold()` deve preservar `visibleBars`/`rightMargin`/`firstBar` quando a
série resultante tem o mesmo tamanho da anterior, ou `setWicks` deve reenquadrar de volta
(guardar os três e reaplicar), da mesma forma que `show()` guarda `wasFromRight` (1133).

**Tentei refutar assim:** verifiquei se ligar rabos muda o número de tijolos (não muda: é
`Renko.withWicks`, decoração da barra, e o teste `TickRenkoOnChartTest` compara contagens
por tamanho de tijolo, não por rabo); verifiquei se algum chamador restaura a vista depois
de `setWicks` (não). Não derrubou.

### A3-13. O caminho de extensão faz leitura de disco bloqueante na EDT e copia a série inteira a cada quadro

`ChartCanvas.java:1264`

```java
            growing.advance(day, now + 1);
```

`ChartCanvas.java:1276`

```java
        this.series = growing.live();
```

**Problema.** `extendBricks()` é chamado de `seriesGrew()`, que o `ReplaySession` dispara
de um `javax.swing.Timer` — na EDT. `TickRenko.advance` chama `library.load(day)` sempre
que o dia vira (TickRenko.java:183), e `TickLibrary.load` é documentado assim:
*"Blocks. Never call it from the interface thread: a session is a fifth of a second, which
is long enough to be seen as a freeze"* (TickLibrary.java:160-162). Ninguém chama
`request()` nessa biblioteca, então não há prefetch para salvar o caso. E `growing.live()`
chama `bricks()`, que reconstrói `double[][]`, `long[]`, um `BitSet` clonado e outro
`long[]` sobre TODOS os tijolos (TickRenko.java:340-354), a cada quadro.

**Consequência.** Um engasgo de ~0,2 s na virada de cada pregão do replay, e uma cópia
O(n) da série de tijolos trinta vezes por segundo. O comentário de 929-933 mede o caminho de
extensão em *"0,018 ms a frame -- 0,1% of one"* e não contabiliza nem a leitura de sessão
nem a cópia — a medição continua verdadeira para o `advance` e falsa para o quadro.

**Correção.** Chamar `library.request(day)` e `request(day.plusDays(1))` quando o replay
entra num dia, para que a leitura aconteça na thread `ticks`; e guardar o `live()` num
campo, invalidado só quando `advance` devolve `true`.

**Tentei refutar assim:** conferi se `advance` já é chamado fora da EDT em algum caminho
(o único chamador é `extendBricks`, e os dois chamadores dele — `seriesGrew` e o `done()`
do worker — estão na EDT); conferi se `bricks()` guarda cache (não: constrói tudo a cada
chamada); conferi se o número de tijolos é pequeno o bastante para não importar — o próprio
comentário de 933 fala em 1.090 tijolos de uma sessão, mas o renko é acumulado por
`add(each)` sobre todos os dias na tela (1072-1078), então cresce com o recorte. Não
derrubou.

---

## Achados BAIXA

- `ChartCanvas.java:1323` e `:1351` — `hoveredBar()` e `barUnderCursor()` são dois métodos
  públicos com o mesmo corpo e o mesmo javadoc; um dos dois deveria sumir.
- `ChartCanvas.java:2038` — `if (series == null || last <= first)` confere `series` DEPOIS
  de tê-lo desreferenciado em `series.size()` na linha 2036; a guarda é morta.
- `ChartCanvas.java:2327` — `paintCrosshair` não tem a guarda `onAxis`/`onTimeAxis` que
  `paintReadout` tem (2189): com o ponteiro na faixa de preços a cruz salta para a última
  barra visível e a etiqueta afirma um preço que o ponteiro não está apontando.
- `ChartCanvas.java:1322` — o javadoc de `hoveredBar` promete `-1` *"when the mouse is
  elsewhere"*, mas só devolve `-1` com `cursor == null`; `Viewport.barAt` grampeia
  (Viewport.java:222), então sobre o eixo devolve uma barra.
- `ChartCanvas.java:188` — o javadoc de `JUMP_MARGIN` diz *"from the bottom-right corner"*,
  e `jumpBounds` (1436) põe o botão em cima, com um comentário dizendo *"Top right, not
  bottom"*. Um dos dois está errado desde a mudança.
- `ChartCanvas.java:1781` — `paintOverlays` usa `overlay.stroke()` para todas as linhas;
  `Overlay.strokes()` (Overlay.java:169), que existe para dar um traço por linha, é honrado
  por `StudyPane` (StudyPane.java:797) e ignorado aqui. Hoje nenhum indicador de preço
  sobrescreve `strokes()`, então o defeito é latente.
- `ChartCanvas.java:244` — o javadoc de `followers` justifica o `final` com *"a subclass's
  fields would be assigned"*, numa classe declarada `final` em 75.
- `ChartCanvas.java:2540` — `onCursorChanged.run();` está fora da indentação do bloco.
- `ChartCanvas.java:824` — `setPeriod` compara com `==`; `new Renko(55, 2)` duas vezes são
  instâncias diferentes e refazem todo o `refold` (com o reenquadramento de A3-12).
- `ChartCanvas.java:614` — `isAutomaticScale()` compara `double` com `==`; funciona porque
  os valores são atribuídos exatos, mas é frágil e não é a convenção do resto do projeto.
- `ChartCanvas.java:2131`, `:2278` — `formatFor` aloca um `DecimalFormat` novo em cada
  pintura, três vezes por quadro (eixo, última cotação, etiqueta do cursor); um mapa por
  passo resolveria.
- `ChartCanvas.java:2112` — `paintLastPrice` mostra o fechamento da última barra VISÍVEL, e
  o javadoc chama isso de *"what is it worth now"*; rolado para 2021, a etiqueta de cotação
  atual mostra um preço de 2021.
- `ChartCanvas.java:1318` — `getStyle()` usa prefixo `get` onde todo o resto do arquivo usa
  o nome nu (`series()`, `period()`, `overlays()`, `instrument()`).

---

## Desempenho no caminho de pintura

**Por repintura, sempre (`paintComponent`, 1694):**

| o quê | linha | custo |
|---|---|---|
| `viewport()` reconstruído | 1705 → Viewport.java:129 | O(barras visíveis) |
| `style.paint` | 1708 → CandleStyle.java:82 | O(barras visíveis), sem decimação |
| `paintOverlays` | 1792 | O(barras × linhas), **um `double[]` alocado por barra e por linha** |
| `paintTimeAxis` | 1894 | O(barras visíveis), um `ZonedDateTime` por barra (1895) |
| `paintDayBand` | 1977 | O(barras visíveis), outro `LocalDate` por barra (1981) |
| `gridStep`/`formatFor` | 1842, 2131, 2365 | três `DecimalFormat` por quadro |
| `paintGrid`, `paintPriceAxis` | 1741, 1845 | O(altura / GRID_SPACING) — bem comportados |

O comentário de 71-73 (*"one pass over the visible bars — a few hundred"*) só vale enquanto
o leitor não afastar o zoom: `visibleBars` é limitado por `series.size()` e nada mais, então
"algumas centenas" pode ser 1.050.000. Ver A3-1.

**Por repintura, mas escondido:** `mouseMoved` (2429) repinta o componente inteiro a cada
pixel de movimento — não há retângulo sujo, e o override de `repaint` (1373) ainda propaga
para todos os `followers`, que repintam seus próprios painéis.

**Varredura da série inteira, fora da pintura, na EDT:** `rebuildFromTicks` (1011-1029) faz
até quatro passagens completas sobre `source` com conversão de calendário e até três
`Files.walk` da pasta de ticks, antes de criar o worker — ver A3-2. `renkoAllowed` (1154)
faz mais uma, quando `syntheticTicks` está desligado.

**Por quadro de replay:** `extendBricks` reconstrói toda a série de tijolos em
`growing.live()` (1276) e pode ler uma sessão do disco na EDT (1264) — ver A3-13.

**O que está bem:** `Viewport.of` toma a escala só das barras que EXISTEM na janela
(Viewport.java:127), sem varrer a série toda; `overlay.calculate` roda nas trocas de série e
não na pintura (1295); `paintGrid`/`paintPriceAxis` iteram por linha de grade e não por
barra; nada no caminho de pintura é `synchronized` e nenhum getter lido pela pintura o é.

---

## O que sairia daqui

O arquivo tem 2.650 linhas porque acumulou cinco responsabilidades além de desenhar preço.
Critério de recorte usado: **sai o que pode ser testado sem janela, e sai o que guarda
estado que o caminho de pintura não lê.**

1. **O eixo de tempo e a faixa de dias** — `paintTimeAxis`, `paintDayBand`, `drawDay`,
   `timeStep`, `niceTimeStep`, `axisSpeaksInDays`, `TIME_STEPS`, `TIME_SPACING`,
   `TIME_HEIGHT`, `DAY_HEIGHT`, `CLOCK`, `DAY`, `DAY_OF_MONTH`, `MONTH`, `BOUNDARY`
   (~200 linhas, 1856-2103). Já existe um `TimeAxisTest` no pacote de testes para um
   `TimeAxis` que não existe no de produção. É o recorte mais óbvio do arquivo.
2. **O eixo de preço e a grade** — `paintGrid`, `paintPriceAxis`, `paintLastPrice`,
   `gridStep`, `niceStep`, `formatFor`, `AXIS_WIDTH`, `GRID_SPACING` (~120 linhas). Sairia
   junto com A3-9: uma classe com uma só regra de arredondamento faz o defeito ser
   impossível em vez de corrigido.
3. **A construção de tijolos a partir de ticks** — `playing`, `growing`, `growingFrom`,
   `setTickSource`, `stopGrowing`, `sourceForBricks`, `rebuildFromTicks`, `extendBricks`,
   `clockNow`, `dayOfClock`, `renkoAllowed`, `show` (~250 linhas, 906-1280). É a **única**
   parte do arquivo com threads, com recurso a fechar e com estado de ciclo de vida — e é
   de onde vêm A3-2, A3-3, A3-4, A3-5, A3-11 e A3-13, seis dos treze achados. Um
   `BrickFeed` com um contador de geração e um `close()` resolveria a família inteira, e
   seria testável com um `TempDir` sem canvas.
4. **A vista persistida** — `storeView`, `restoreView`, `readDouble` (~50 linhas). Um record
   `ChartView(visibleBars, fromEnd, rightMargin, stretch, priceOffset, periodCode, style)`
   com leitura tolerante, testado sem janela; A3-8 é um campo que faltou no record.
5. **O modo e a régua** — `Mode`, `mode`, `rulerBar`, `rulerPrice`, `measurement`,
   `controlAlone`, `installControlToggle`, `applyMode`, `cursorForMode`, `paintRuler`
   (~150 linhas). Guarda estado que a pintura só lê no fim, e é o que A3-6 quebra.

Ficaria um `ChartCanvas` de umas 800 linhas: viewport, camadas, cursor, gestos e a lista de
overlays — que é o que o javadoc da classe (53-73) já promete que ele seja.

---

## O que foi auditado e está LIMPO

| o quê | como conferi |
|---|---|
| Leitura do futuro | O canvas nunca calcula indicador: passa `series` a `overlay.calculate` (589, 752, 1139, 1188, 1296) e lê `valueAt`. `OwnScale` não é citado no arquivo — a regra da escala maior mora inteira nos indicadores. Nenhuma indexação `i+1` ou `i + n` no arquivo. |
| Base crua | Nenhuma menção a ajuste, fator, split ou dividendo. `source` é o que chega de `SegmentedSeries` (MainWindow:436) e `period.apply(source)` é a única transformação (1287). |
| Renko: caixa e régua | O canvas não implementa renko; delega a `Renko`/`TickRenko` (1006, 1064) e só decide de qual export vêm os ticks. A régua atravessa pregões porque `TickRenko.carry` é reusado em `fold` — o canvas não a reinicia em lugar nenhum. |
| `synchronized` no caminho de pintura | `grep` por `synchronized` no arquivo: nenhuma ocorrência. Nenhum getter lido por `paintComponent` é sincronizado. |
| `Graphics` criado e descartado | `paintComponent` faz `graphics.create()` (1695) e `g.dispose()` num `finally` (1717-1719). É o único `create()` do arquivo. |
| Exceção engolida na pintura | Não há `catch` dentro de `paintComponent` nem nos métodos `paint*` — nada é silenciado ali. O único `catch` de pintura adjacente é o de `done()` (1115), fora da EDT de desenho, e ele documenta a escolha. |
| Vazamento de listener global | `addNotify` registra `followRuler` e `followDrawing`; `removeNotify` remove os dois (1508-1522), e ambos são campos `final` guardados exatamente para isso (380, 383). O `KeyEventDispatcher` é removido antes de ser adicionado e removido de novo quando o componente deixa de ser exibível (554-564). |
| Lista de `followers` | `follow` (1382) e `unfollow` (1388) são simétricos, e `StudyStack` chama os dois — `follow` em StudyStack:126, `unfollow` em StudyStack:262 e :418. Nenhum caminho adiciona sem remover. |
| Texto de interface | Todo texto visível passa por `Messages.get` (461, 467, 476, 659, 896-897) e as quatro chaves existem em `messages.properties` e em `messages_pt_BR.properties`. As únicas cadeias literais são chaves de `Settings` (`"line"`, `"candle"`) e nomes de ação (`"period" + typed`). |
| Busca de parâmetro por chave | `storeView`/`restoreView` usam `prefix + "nome"` (1628-1657); nada é lido por posição. |
| Divisão por zero e NaN na escala | `Viewport.of` inventa faixa quando `high == low` (Viewport.java:134-142); `niceStep` devolve 1.0 para `target` não positivo e para NaN pela forma `!(target > 0.0)` (2299-2302), então os laços `while (price <= ...)` de 1741 e 1845 não podem ser infinitos; `clampOffset` e `readDouble` rejeitam não finitos (1675, 1686). |
| Largura ou altura zero | `plotBounds()` força mínimo 1 nas duas dimensões (1403-1404); `paintComponent` desiste com `getWidth() < 2 || getHeight() < 2` (1701); `gridStep` divide por `Math.max(1, getHeight())` (2295). |
| Série vazia e barra única | `paintComponent` desiste com `series.size() == 0` (1701); `hoveredBar`, `jumpBounds`, `mouseWheelMoved` e `mouseDragged` têm a mesma guarda (1324, 1427, 2609, 2570); `timeStep` e `axisSpeaksInDays` devolvem cedo quando `last <= first` (2076, 2038); `paintDayBand` com um só dia fecha o vão no índice `limit` (1977-2013). |
| `this` escapando do construtor | O construtor só registra listeners internos e chama `setCursor`; `Mouse` é interno mas não é publicado fora. `followers` e os `Runnable` de acompanhamento são inicializados no campo, antes do corpo. |
| Estado tocado por mais de uma thread | O único trabalho fora da EDT é o `doInBackground` de 1061-1081, que só toca variáveis locais (`built`, `days`, `library`) e a `TickLibrary`, que se sincroniza sozinha (TickLibrary.java:137, 190, 239). Todo o resto — replay por `javax.swing.Timer`, `done()`, mouse, teclado — é EDT. |

---

## Observações sobre a área

O arquivo é honesto sobre o que já custou: quase todo comentário longo registra um defeito
que aconteceu de verdade (997-1003, 2587-2594, 2024-2028, 116-119 no `Viewport`). Essa é a
melhor parte dele, e é também por isso que os comentários órfãos (A3-10) e os que já não
batem com o código (A3-2, A3-7, A3-13) pesam mais aqui do que pesariam noutro lugar: quem
lê este arquivo confia no comentário.

Os quatro ALTA não são independentes. Três deles (A3-2, A3-3, A3-4) vivem no mesmo bloco de
250 linhas — a construção de tijolos a partir de ticks — que entrou depois, é a única parte
com threads e ciclo de vida, e é a que não tem lugar próprio. Corrigir os três dentro do
`ChartCanvas` é possível; movê-los para uma classe com contador de geração e `close()` faz
com que a família toda deixe de ser possível. O quarto (A3-1) é de outra natureza: é a
promessa do javadoc da classe (*"a few hundred"*) que nunca foi imposta em lugar nenhum, e
o remédio é um teto, não uma reescrita.

Nada foi encontrado contra as três regras de domínio medidas — futuro, base crua e renko. O
canvas delega as três, e delega direito.
