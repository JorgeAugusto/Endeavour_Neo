# B3 — `ChartCanvas` inteiro

Auditoria II, segunda passada, 07/09/2026.

## O que foi lido

**A área, inteira:**

- `src/main/java/br/com/jorge/reis/endeavourneo/ui/chart/ChartCanvas.java` —
  linhas 1 a 2.983 (o arquivo todo, em sete blocos)

**Apoio, lido para tentar derrubar achado — não auditado:**

- `ui/chart/Viewport.java` (273 linhas, inteiro)
- `ui/chart/RenkoSource.java` (138 linhas, inteiro)
- `ui/chart/RulerMode.java` (103 linhas, inteiro)
- `domain/market/TickRenko.java` (linhas 146–310, `advance`/`live`)
- `domain/market/Renko.java` (trechos: `withWicks`, `apply`, `formingAt`)
- `platform/SeriesCatalog.java` (linhas 355–405, `groupOf`)
- `ui/chart/ChartHolder.java` (trechos: `attachReplay`, `detachReplay`, `dock`,
  `close`, `styleButton`)
- `ui/shell/MainWindow.java` (trechos: `offerHistory`, `fillFromTicks`, abertura
  de gráfico)
- `ui/replay/ReplayDrop.java` (linhas 70–95)
- `ui/chart/PeriodCatalog.java` (`byCode`)
- testes: `ChartCanvasTest`, `ChartViewTest`, `TickRenkoOnChartTest`,
  `HistoryPagingTest` (listagem), `DecimationTest` (uso de `barsPerColumn`)

Não foram lidos `docs/auditoria/*.md`, `docs/auditoria/ii/b1-*.md` nem
`b2-*.md`, conforme o briefing.

---

# ALTA

### B3-1. Um quadro em que nada imprimiu troca o renko de ticks pelo renko de candles

`ChartCanvas.java:1428` e `ChartCanvas.java:1337`

```java
            if (!growing.advance(day, now + 1) && fromTicks && this.series != null) {
                // NOTHING PRINTED since the last frame, so there is nothing new
                // to show. This used to rebuild the whole view anyway -- every
                // brick copied into a new series, twenty-five times a second,
                // for a chart that had not changed.
                return false;
            }
```

```java
    public void seriesGrew() {
        if (growing != null && extendBricks()) {
            // The bricks grew from the ticks themselves. Falling through to the
            // candle fold below would throw them away and replace them with a
            // renko of the replay's bars, which is the mixing this exists to
            // stop.
            ...
            return;
        }

        this.series = period.apply(source);
```

**Problema.** `extendBricks()` passou a devolver `false` em dois casos que não
são o mesmo: "não consegui estender" e "não havia nada para estender". O único
chamador de fato, `seriesGrew()`, lê `false` como o primeiro — e cai justamente
no `period.apply(source)` que o comentário logo acima diz existir para impedir.

São três portas para o mesmo estrago, e as três estão abertas:

1. **Quadro parado.** `TickRenko.advance` devolve `laid || tail`
   (`TickRenko.java:237`). Quando nenhum tijolo FECHOU no intervalo, devolve
   `false` — mesmo tendo negócios novos e mesmo tendo movido o tijolo em
   formação, que é a razão de `live()` existir. Num replay a 25 quadros por
   segundo isso é a maioria dos quadros.
2. **Rebobinar.** `ChartCanvas.java:1410` devolve `false` para sempre quando o
   replay volta para um dia anterior, e o comentário ali diz "it is built again
   from scratch". **Não é.** `rebuildFromTicks()` só é chamado de `refold()`
   (`:1484`), e `refold()` só de `setSeries`/`setPeriod`/`growHistory` — nenhum
   deles está no caminho por quadro do replay, que é `ReplayDrop.java:84 → 93`
   chamando apenas `seriesGrew()`. O gráfico fica no renko de candles até o
   replay ser desmontado.
3. **Dia sem fita.** `advance` devolve `false` quando `advancingBars == null`
   (`TickRenko.java:212-214`), e cai no mesmo lugar.

**Consequência.** O gráfico alterna, entre quadros, entre duas séries diferentes
— o renko de ticks e o renko dobrado dos candles, que `RenkoSource.java:39-44`
mede como 5% a 23% mais tijolos. A contagem de barras muda, a vista salta, e os
overlays são recalculados sobre a série errada. Pior: **`fromTicks` não é
zerado** nesse caminho de queda (`:1353-1360` não mexe no campo), então
`isFromTicks()` continua respondendo `true` e o cabeçalho continua dizendo ao
leitor que aquilo veio da fita da bolsa. É exatamente a mistura candle-com-tick
que esta trilha inteira existe para impedir, entrando pela porta que ela não
vigia — a mesma frase que o comentário de `:1176-1181` usa para outro caso.

**Correção.** Separar as duas respostas. `extendBricks()` deve devolver `true`
quando o renko crescente continua válido e apenas não teve o que acrescentar (o
`this.series` fica como está, sem cópia — que é a economia pretendida), e
`false` só quando ele tem de ser abandonado: o `catch` de `:1435` e o
rebobinamento de `:1410`. E o rebobinamento deve chamar `stopGrowing()` seguido
de `refold()`, para o "built again from scratch" que o comentário promete
passar a ser verdade.

**Tentei refutar assim.** (a) Procurei outro chamador de `extendBricks()`: há
dois, `:1249` dentro de `done()` — cujo resultado é ignorado e onde `fromTicks`
ainda é `false`, então a guarda não dispara na primeira construção — e `:1338`.
(b) Procurei quem religasse os tijolos: `rebuildFromTicks` só sai de `refold`,
e `refold` não está no caminho por quadro. (c) Procurei teste que cobrisse:
`grep -rln seriesGrew src/test` acha só `TickRenkoOnChartTest`, e a única
chamada (`:475`) acontece depois de `setPeriod(ONE_MINUTE)`, quando
`stopGrowing()` já zerou `growing` — o ramo nunca é executado. (d) Verifiquei se
`advance` poderia devolver `true` sempre com o relógio andando: `TickRenko:216-222`
devolve `tail` (falso) quando `upTo <= advanced`, e `:237` devolve `laid || tail`,
que é falso quando o dobramento não fechou tijolo. Nenhuma derrubou.

---

### B3-2. O eixo de tempo e a faixa de dias constroem um `ZonedDateTime` por barra visível, a cada quadro

`ChartCanvas.java:2176` e `ChartCanvas.java:2259`

```java
        for (int i = viewport.firstBar(); i < viewport.lastBar() && i < series.size(); i++) {
            ZonedDateTime time = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone);
```

```java
        for (int i = viewport.firstBar(); i <= limit; i++) {
            java.time.LocalDate day = null;

            if (i < limit) {
                day = Instant.ofEpochMilli(series.timeAt(i)).atZone(zone).toLocalDate();
```

**Problema.** Os dois laços andam barra a barra sobre a janela inteira, sem
nenhuma redução, e cada volta aloca um `Instant` e um `ZonedDateTime`. O número
de barras visíveis não tem teto próprio: a roda clampa em `series.size()`
(`:2964`) e `barsForDrag` recebe `series.size()` como `available` (`:2889`), de
modo que "afastar tudo" põe a série carregada inteira na janela. A janela padrão
é de 100 mil barras (`ChartPreferences.window()`, usada em
`MainWindow.java:401`), e a paginação vai além disso.

O projeto já resolveu este problema uma vez e a solução está a um método de
distância: `Viewport.barsPerColumn()` (`Viewport.java:196-224`) existe
exatamente para isto e traz a medição — *"one candle repaint took 1.049 ms and
one line repaint 2.084 ms"*, que na notação do arquivo é **1,049 s e 2,084 s**.
`grep -rn barsPerColumn src/main` mostra que só `CandleStyle:90` e
`LineStyle:69` a usam. E `RenkoSource.java:91-93` escreve a regra em palavras:
*"A conversion per bar would be 693 thousand of them on the interface thread,
for one keystroke."* Aqui não é por tecla — é por quadro.

**Consequência.** Travamento da EDT num gráfico afastado com um indicador ou
sem ele. `repaint()` é chamado a cada movimento do mouse (`:2750`), a cada
evento de arrasto e a cada tique de replay; e `repaint` desta classe também
repinta todos os `followers` (`:1579-1586`), que borrowam o mesmo viewport. Duas
centenas de milhares de conversões de calendário por quadro é a mesma ordem de
grandeza que a medição do `Viewport` chama de um segundo por repintura.

**Correção.** Andar de `viewport.barsPerColumn()` em `barsPerColumn()`, como as
duas classes de estilo fazem — nos dois laços. Nenhum dos dois precisa de
resolução abaixo de um pixel: o rótulo é descartado quando encosta no anterior
(`:2198`) e a faixa é descartada quando é estreita demais para o próprio nome
(`:2338`). Em ambos os casos o trabalho por barra é jogado fora logo em seguida.

**Tentei refutar assim.** (a) Procurei um teto em `visibleBars`: só
`MINIMUM_VISIBLE_BARS` por baixo; por cima o limite é `series.size()` nos dois
caminhos de zoom. (b) Procurei uma saída antecipada nos laços que cortasse o
custo: a de `:2187` (`if (!boundary && !newDay) continue;`) acontece **depois**
da conversão, que é a linha cara. (c) Procurei um cache do `ZonedDateTime` ou um
`bucketOf` que trabalhasse em `long`: `axisBucket` recebe `ZonedDateTime` pronto
e o javadoc de `:2353-2360` explica por que ele não pode voltar a ser aritmética
de epoch — o que justifica o formato do argumento, não a conversão por barra.
(d) Conferi que `paintDayBand` é chamada de dentro de `paintTimeAxis` (`:2163`),
então os dois laços rodam no mesmo quadro, somando.

---

# MÉDIA

### B3-3. `paintOverlays` aloca um `double[]` por barra e por linha, e lê a mesma linha várias vezes

`ChartCanvas.java:2068`

```java
            for (int line = 0; line < lines; line++) {
                ...
                for (int i = from; i < to; i++) {
                    double[] row = overlay.valueAt(i);
```

**Problema.** `valueAt` aloca sempre — `MovingAverage.java:291-295` devolve
`new double[]{...}` e `BollingerBands.java:349-358` devolve `new double[3]` —, e
a chamada está no laço interno, dentro do laço de linhas. Para um indicador de
três linhas sobre N barras são `3 × N` arrays por indicador por quadro, quando
`N` bastaria. E o laço, como o B3-2, não usa `barsPerColumn()`.

**Consequência.** Alocação por elemento em laço de pintura, que a convenção da
casa proíbe por escrito. Todo gráfico abre com três médias
(`MainWindow.java:668-672`), então isto é o caso comum e não o excepcional: 3
indicadores × 1 linha × N barras. Afastado, N é a série carregada.

**Correção.** Trocar a ordem dos laços — buscar `row` uma vez por barra e
percorrer as linhas dentro — e andar de `barsPerColumn()`. O ideal seria
`Overlay` expor `valueAt(int bar, double[] into)`, mas a troca de ordem já
divide por `lines` sem mexer na interface.

**Tentei refutar assim.** Procurei se alguma implementação devolvia um array
guardado: as quatro (`MovingAverage`, `BollingerBands`, `RelativeStrength`,
`SlowStochastic`) constroem no `return`. Procurei se `from`/`to` já vinham
reduzidos: `:2045-2046` os tira direto de `viewport.firstBar()`/`lastBar()`.

---

### B3-4. O atalho de dígito é do WINDOW, e com dois gráficos abertos ele abre a janela de período do gráfico errado

`ChartCanvas.java:497`

```java
            getInputMap(WHEN_IN_FOCUSED_WINDOW)
                    .put(javax.swing.KeyStroke.getKeyStroke(digit), "period" + typed);
```

com o javadoc logo acima:

```
     * <p>Bound for the whole window rather than the focused component: the chart
     * is the window, and asking the reader to click it first before a shortcut
     * works is the kind of thing that makes shortcuts go unused.
```

**Problema.** "The chart is the window" não é verdade neste aplicativo: os
gráficos são `JInternalFrame` dentro de um `JDesktopPane` único
(`ChartHolder.java:445,473`, `MainWindow.java:115`), e o `MainWindow` chega a
arrumá-los em grade. Cada canvas registra o mesmo atalho em
`WHEN_IN_FOCUSED_WINDOW`, e o `KeyboardManager` do Swing percorre os registrados
do último para o primeiro e para no primeiro que consome. Quem atende é o
gráfico registrado por último — não o que está à frente, não o que tem foco.

**Consequência.** Com dois ou mais gráficos abertos, digitar `5` muda o período
de um gráfico que o leitor não está olhando, e não muda o que ele está. O
gráfico visado não dá sinal nenhum. É o mesmo erro que o próprio
`installControlToggle` cita ao se justificar ("Otherwise every chart on screen
would flip together"), do outro lado.

**Correção.** Registrar em `WHEN_ANCESTOR_OF_FOCUSED_COMPONENT` e dar foco ao
canvas no `mousePressed`; ou manter o binding de janela e, na ação, desviar para
o `JInternalFrame` selecionado do desktop antes de abrir o diálogo.

**Tentei refutar assim.** (a) Procurei uma guarda que desabilitasse o canvas
inativo: `grep -rn "canvas.setEnabled" src/main` não acha nada, e o despacho do
Swing só exige `isShowing() && isEnabled()`, que todo gráfico docado satisfaz.
(b) Procurei se o comentário estava certo quanto a roubar dígitos de um campo de
texto: está — `WHEN_IN_FOCUSED_WINDOW` só é consultado depois dos mapas do
componente com foco. Essa metade do comentário fica de pé; a que diz "the chart
is the window" não.

---

### B3-5. Control sozinho alterna o modo uma vez por gráfico na tela — com dois, não alterna nada

`ChartCanvas.java:547`

```java
                // Only the window this canvas is in. Otherwise every chart on
                // screen would flip together.
                if (isShowing() && javax.swing.SwingUtilities.getWindowAncestor(this) != null
                        && javax.swing.SwingUtilities.getWindowAncestor(this).isActive()) {
                    toggleMode();
                }
```

**Problema.** Cada canvas instala o seu próprio `KeyEventDispatcher`
(`:531`, `:562`), e todos devolvem `false` — ou seja, nenhum consome o evento. O
mesmo `KEY_RELEASED` do Control passa por todos. `toggleMode()` chama
`RulerMode.toggle()`, que é um booleano **global**
(`RulerMode.java:78-80`, `:53`). Com N gráficos docados na janela ativa, uma
soltura de Control faz N inversões do mesmo booleano.

**Consequência.** Com dois gráficos docados — o arranjo que o `MainWindow`
oferece em grade — o atalho não faz **nada**. Com três, faz. O comportamento
depende da paridade da contagem de janelas, e nada na tela diz isso. E o
comentário que justifica a guarda mente duas vezes: o motivo alegado ("every
chart on screen would flip together") deixou de existir quando o modo virou
global (ver o javadoc de `setMode`, `:1822-1825`, e o de `RulerMode`, `:28-32`),
e o efeito real é o oposto do prometido.

**Correção.** Um despachante só para a aplicação, dono do gesto, ao lado de
`RulerMode` — não um por canvas. Ou, se ficar aqui, consumir o evento
(`event.consume()` e devolver `true`) para que só o primeiro atue.

**Tentei refutar assim.** (a) Conferi que `RulerMode.set` não é idempotente por
chamador: `:65-67` só ignora quando o valor pedido já é o atual, e o `toggle`
sempre pede o oposto do que acabou de escrever. (b) Conferi que os despachantes
de outras janelas ficam de fora: a guarda `isActive()` os corta, então o defeito
é dos gráficos docados na MESMA janela, que é o caso comum. (c) Procurei um
`removeKeyEventDispatcher` que deixasse só um vivo: `:556-566` registra um por
canvas e remove só o próprio.

---

### B3-6. `restoreView` remonta um renko sem passar pela guarda que `askForPeriod` respeita

`ChartCanvas.java:1913`

```java
        PeriodCatalog.Choice period = PeriodCatalog.byCode(from.get(prefix + "period", null));

        if (period != null) {
            setPeriod(period.aggregation(), period.title(), period.code());
        }
```

**Problema.** `renkoAllowed()` (`:1316-1318`) é a única leitura de
`ChartPreferences.syntheticTicks()` em toda a interface do gráfico
(`grep -rn syntheticTicks src/main`), e ela só é consultada de `askForPeriod`
(`:972-984`). `setPeriod` não a consulta. Restaurar um espaço de trabalho é um
caminho direto para `setPeriod`, e portanto para `refold()`, `rebuildFromTicks()`
retornando cedo em `:1144-1150`, e `series = period.apply(source)` — o renko
dobrado dos candles.

**Consequência.** O leitor que desligou "preencher ticks que faltam" — a
configuração cuja única função é "prefiro ser avisado a ver um gráfico que não é
o que diz" — recebe, em toda abertura do aplicativo, exatamente o gráfico que a
configuração recusa, sem uma palavra. A guarda protege o diálogo e só o diálogo.
`setWicks` (`:939-946`) entra pela mesma porta, embora ali o período já seja
renko.

**Correção.** Mover a decisão para `setPeriod` — ou para `refold`, junto de
`rebuildFromTicks` — e deixar `askForPeriod` apenas escolher a mensagem. Onde
não há janela para avisar (restauração), a saída honesta é cair para o período
anterior e escrever no console.

**Tentei refutar assim.** (a) Procurei se `period.apply` respeitasse a
configuração por dentro: `Renko.apply` (`Renko.java:324`) não conhece
`ChartPreferences` — o domínio não importa interface, corretamente, e por isso a
decisão TEM de ficar aqui. (b) Procurei uma segunda checagem em `ChartHolder`
antes do `restoreView` de `:493`: não há. (c) Verifiquei se `renkoAllowed`
quebraria com `instrument == null` (o `rebuildFromTicks` protege, ela não):
`SeriesCatalog.groupOf(null)` devolve `""` (`:372-375`), então não estoura —
esse achado eu derrubei e ele não entra.

---

### B3-7. `restoreView` mede a posição contra uma série que ainda não chegou, e `refold` depois zera tudo

`ChartCanvas.java:1928`

```java
        visibleBars = Math.max(MINIMUM_VISIBLE_BARS,
                Math.min(from.getInt(prefix + "visibleBars", visibleBars),
                        Math.max(MINIMUM_VISIBLE_BARS, series.size())));
        ...
        firstBar = clampFirstBar(series.size() - Math.max(0, fromEnd));
```

**Problema.** Os dois valores são clampados contra `series.size()`. Para um
gráfico cuja série é lida em segundo plano — o gráfico DE um export de ticks,
`MainWindow.fillFromTicks`, que diz de si mesmo "EMPTY NOW, FILLED IN THE
BACKGROUND" e leva 4,4 s a 8,1 s — a série vale zero quando o `invokeLater` de
`ChartHolder.java:493` roda o `restoreView`. `visibleBars` desaba para
`MINIMUM_VISIBLE_BARS`, `firstBar` para 0. Segundos depois o `done()` do worker
chama `setSeries`, que chama `refold()`, e como `wasSize == 0` o atalho de
`:1492` não pega: `visibleBars` volta a `DEFAULT_VISIBLE_BARS` e a vista ao fim
da série.

**Consequência.** Zoom, posição, período e estilo guardados são perdidos em
silêncio para toda essa família de gráficos — o oposto exato do que o javadoc de
`storeView` promete em `:1889-1892` ("Reopening a chart that comes back at the
default zoom is a chart that has to be set up again every morning").

**Correção.** `restoreView` guardar o pedido quando `series.size() == 0` e
reaplicá-lo no primeiro `refold` que trouxer barras — ou o holder chamar
`restoreView` depois do `setSeries`, o que é decisão da área dele, mas a
fragilidade é desta: o método não tem defesa nenhuma contra ser chamado cedo.

**Tentei refutar assim.** (a) Verifiquei a ordem no caminho comum (não-ticks):
`MainWindow:650` chama `setSeries` de forma síncrona e o `restoreView` é
`invokeLater`, então ali chega com a série pronta — o defeito é só do caminho
assíncrono, e por isso é MÉDIA e não ALTA. (b) Procurei um segundo
`restoreView` depois que os ticks chegam: `grep -rn restoreView src/main` acha
uma chamada só, em `ChartHolder:493`, dentro de `dock()`. **De passagem, para a
área do `ChartHolder`:** `floatIt()` não chama `restoreView` nenhuma vez, então
um gráfico que reabre flutuando nunca restaura nada.

---

### B3-8. Trocar o estilo pelo `restoreView` não avisa ninguém, e o arquivo conhece os estilos pelo nome

`ChartCanvas.java:1900` e `:1920`

```java
        into.put(prefix + "style", style instanceof LineStyle ? "line" : "candle");
```

```java
        if ("line".equals(from.get(prefix + "style", "candle"))) {
            setStyle(new LineStyle());
        }
```

**Problema.** Três coisas na mesma dupla de linhas.

Primeira: o javadoc de classe (`:66-69`) diz **"a new style is a new class and
this file does not change"**. Estas duas linhas nomeiam os dois estilos
existentes; um terceiro exigiria editar este arquivo, e enquanto não for editado
volta como candle sem aviso. O comentário mais destacado da classe descreve algo
que a própria classe já não cumpre.

Segunda: a restauração é assimétrica. `"line"` chama `setStyle`; `"candle"` não
chama nada. Num canvas que já está em linha — reabrir docado depois de flutuar,
que passa por `dock()` outra vez — o valor guardado "candle" não o traz de volta.

Terceira: `setStyle` (`:1517-1523`) não tem ouvinte. Há `onScaleChanged`,
`onSeriesChanged`, `onModeChanged`, `onOverlaysChanged`, `onOverlaysRedrawn`,
`onCursorChanged` — e nenhum para o estilo. O `ChartHolder` guarda a resposta
dele próprio num campo (`ChartHolder.java:160`, `private String styleChoice =
"candle";`) e monta os botões com ele (`:732`), então depois de um `restoreView`
para linha o botão marcado continua sendo o de candle. Duas respostas para a
pergunta "que estilo é este gráfico", em dois lugares, e a que aparece na barra
é a errada.

**Correção.** O estilo dizer o próprio código (`ChartStyle.code()`) e um catálogo
resolvê-lo, como `PeriodCatalog` já faz para o período; restaurar os dois lados
do `if`; e acrescentar `onStyleChanged`, para o `ChartHolder` parar de manter uma
segunda cópia da resposta.

**Tentei refutar assim.** (a) Conferi que só existem dois estilos hoje
(`grep -rn "implements ChartStyle"`: `CandleStyle`, `LineStyle`) — o que faz o
`instanceof` funcionar hoje e não muda o fato de o javadoc prometer o contrário.
(b) Procurei um `setStyle` no caminho do `restoreView` que sincronizasse a barra:
`grep -rn "setStyle" src/main` acha `ChartHolder:736`, que é o clique do botão —
a seta aponta só nesse sentido.

---

### B3-9. `rebuildFromTicks` faz a mesma pergunta até três vezes, na EDT, cada uma percorrendo a série inteira

`ChartCanvas.java:1141`

```java
        java.util.List<java.time.LocalDate> onScreen = RenkoSource.sessionsIn(source);
        br.com.jorge.reis.endeavourneo.domain.market.TickSource which = sourceForBricks();
```

e, dez linhas abaixo, a terceira:

```java
        if (!RenkoSource.allows(source, library, false)) {
```

**Problema.** `sourceForBricks()` (`:1081-1112`) abre uma `TickLibrary` para
PROFIT e outra para METATRADER e chama `RenkoSource.allows(source, library,
false)` em cada uma. `allows` percorre `series.size()` inteiro
(`RenkoSource.java:96`). Voltando com a resposta, `:1164` abre uma terceira
biblioteca e faz a mesma pergunta de novo, para a fonte que acabou de ser
escolhida por ela. Tudo isso é síncrono, antes do `SwingWorker` começar, na EDT
— somado ao `RenkoSource.sessionsIn(source)` de `:1141`, que é uma quarta
varredura da mesma série.

**Consequência.** Até quatro passagens sobre a série carregada (100 mil barras
por padrão) mais três listagens de diretório, na thread da interface, a cada
`refold()` — isto é, a cada troca de período, a cada `setSeries`, a cada página
de histórico que chega. **De passagem, para quem auditar `RenkoSource`:** o
comentário de `:91-93` diz *"the calendar is only asked where the day changes"*,
e o código de `:96-97` converte `Instant → ZonedDateTime → LocalDate` em **toda**
barra — só a consulta ao `Set` é que é pulada. O comentário esconde exatamente o
custo que promete ter evitado.

**Correção.** `sourceForBricks()` devolver a fonte **e** a biblioteca já aberta
(ou a `TickLibrary` que serviu), em vez de mandar o chamador reconstruir e
reperguntar; e tirar as varreduras do caminho síncrono, levando-as para dentro do
`doInBackground` que já existe logo abaixo.

**Tentei refutar assim.** (a) Verifiquei se `renkoAllowed()` também paga isso a
cada chamada: `:1317` curto-circuita em `ChartPreferences.syntheticTicks()`, que
é `true` por omissão (`ChartPreferences.java:77`), então na configuração padrão
ela não chega a `sourceForBricks()`. Isso derruba a metade do achado que eu
tinha escrito primeiro; o que sobra, e sobra inteiro, é o de
`rebuildFromTicks`, que chama sem condição. (b) Conferi que as bibliotecas são
fechadas: `:1102-1108` fecha no `finally` e `:1168` fecha antes de sair —
vazamento não há.

---

### B3-10. `cursorReading()` constrói o viewport duas vezes, e é chamado a cada movimento do mouse

`ChartCanvas.java:637`

```java
        int bar = hoveredBar();
        ...
        return java.time.Instant.ofEpochMilli(series.timeAt(bar))
                ...
                + "   " + formatFor(gridStep(viewport())).format(series.closeAt(bar));
```

**Problema.** `hoveredBar()` (`:1530-1536`) chama `viewport()`; a linha do
retorno chama `viewport()` outra vez. Cada `Viewport.of` percorre as barras
visíveis para achar máxima e mínima (`Viewport.java:129-132`). E `formatFor`
constrói um `DecimalFormat` novo (`:2599-2611`). O caminho é
`Mouse.mouseMoved → onCursorChanged.run() → MainWindow.report → cursorReading()`.

**Consequência.** Duas varreduras da janela visível por movimento do mouse, além
da que a repintura do mesmo evento já faz. Numa janela afastada isso é o custo do
B3-2 pago mais duas vezes, para escrever uma linha de rodapé.

**Correção.** Construir o viewport uma vez e passar adiante — `plotViewport()`
já existe e devolve exatamente isso.

**Tentei refutar assim.** Procurei um cache do viewport: o javadoc de classe
(`:71-73`) diz explicitamente que ele é reconstruído a cada pintura, e explica
por quê — decisão deliberada e boa. Ela não justifica construí-lo duas vezes
dentro de um mesmo método.

---

# BAIXA

### B3-11. O javadoc de `clampFirstBar` diz "meia tela"; a constante diz três quartos, e diz que meia foi recusada

`ChartCanvas.java:1650` contra `:88-93`

```java
     * <p>Past the end by up to half a screen, which is what makes room on the
     * right. More than that and the price would be off the edge; less and there
     * is no room to watch a bar form.</p>
```

```java
    private static final double AIR_RIGHT = 0.75;
```

cujo próprio javadoc diz *"Three quarters. Half was not enough to feel like
control"*. Os dois comentários estão a 1.550 linhas um do outro e se contradizem
frontalmente. Quem ler o de baixo primeiro "corrige" a constante de volta para o
valor que já foi medido e recusado.

**Correção.** Trocar "half a screen" por "three quarters of a screen — see
`AIR_RIGHT`".

**Tentei refutar assim.** Conferi que o cálculo usa a constante e não um 0,5
literal: `:1655`, `visibleBars * AIR_RIGHT`. É o comentário que está velho, não o
código.

---

### B3-12. `mouseReleased` é o único tratador sem conferência de botão, e o teste não olha para ele

`ChartCanvas.java:2861`

```java
        @Override
        public void mouseReleased(MouseEvent e) {
            scalingFrom = -1;
            timingFrom = -1;
            grabbedAt = -1;
            rulerBar = -1;
        }
```

`ChartViewTest.java:107` confere `isLeftMouseButton` em `mousePressed` e
`mouseClicked` — e só nesses dois:

```java
        for (int at : new int[]{pressed, clicked}) {
```

**Problema.** Soltar o botão direito durante um arrasto do esquerdo cancela o
arrasto em curso (pan, escala, tempo ou régua). É o caso restante da mesma
família que os outros dois tratadores acabaram de fechar, e o teste que guarda a
regra dá licença para ele por só nomear dois dos três.

**Correção.** Ou conferir o botão também aqui, ou — melhor, porque soltar o
botão sempre deve encerrar o gesto do botão que o iniciou — guardar qual botão
armou o arrasto. E o teste passar a percorrer os três nomes.

**Tentei refutar assim.** Verifiquei se o `measurement` era apagado (que seria o
defeito grave que os outros dois fecharam): não é — `mouseReleased` zera
`rulerBar`, não `measurement`. O estrago fica no arrasto em curso, e por isso é
BAIXA.

---

### B3-13. Três guardas que não guardam

- `ChartCanvas.java:1428` — `&& this.series != null`: o campo nasce
  `PriceSeries.empty()` (`:232`) e nunca recebe `null` (`setSeries` protege em
  `:811`). A condição é sempre verdadeira.
- `ChartCanvas.java:2318` — a série é desreferenciada na linha **anterior** ao
  teste de nulidade:
  ```java
        int last = Math.min(viewport.lastBar(), series.size()) - 1;

        if (series == null || last <= first) {
  ```
- `ChartCanvas.java:2210` — o ramo não faz nada:
  ```java
            if (newDay && !ChartPreferences.periodLine()) {
                ...
                g.setStroke(was);
            } else {
  ```
  `was` acabou de ser lido de `g` em `:2208` e nada mudou o traço entre as duas
  linhas. Efeito colateral do ramo: com a linha de período desligada, nenhuma
  vertical é desenhada na virada de dia **nem quando `verticalGrid()` está
  ligada** — a grade fica com um buraco exatamente onde o leitor mais espera uma
  linha.

**Correção.** Apagar as duas primeiras; na terceira, inverter a condição e
deixar o ramo da grade vertical valer também na virada de dia.

**Tentei refutar assim.** Para `:2210`, procurei uma alteração de traço entre
`:2208` e `:2216`: não há nenhuma instrução entre elas além do `if`.

---

### B3-14. A variável local `replaying` esconde o campo `replaying`, que acabou de nascer

`ChartCanvas.java:1187`

```java
        boolean replaying = playing != null;
```

O campo `replaying` foi criado em `:1025` justamente porque `playing != null`
**não** responde "há um replay" — o javadoc dele explica isso em cinco linhas e
nomeia o estrago que a confusão causou. Vinte linhas abaixo, uma local com o
mesmo nome guarda de novo a expressão que o campo veio substituir.

Neste ponto do método os dois valores coincidem (o caminho já retornou em
`:1144` quando `which == null`, e um replay de barras chega ali com `which ==
null`), então **não é defeito hoje**. É uma armadilha: quem editar o corpo do
`SwingWorker` e escrever `replaying` vai pegar a local, com a semântica antiga, e
o compilador não vai dizer nada.

**Correção.** Renomear a local para `keepGrowing` — que é o que ela decide
(`:1183-1186`) — ou usar o campo.

**Tentei refutar assim.** Enumerei os três estados possíveis de
(`replaying`, `playing`) e conferi que em todos os que chegam a `:1187` a local
vale o mesmo que o campo. Por isso BAIXA e não MÉDIA.

---

### B3-15. Javadoc de `storeView` com dois `@param` na mesma linha

`ChartCanvas.java:1887`

```java
     * @param into where to write, and @param prefix what to write it under
```

Só o primeiro é uma etiqueta de bloco; o segundo vira texto corrido dentro da
descrição de `into`. `prefix` fica sem documentação e a ferramenta avisa. Duas
linhas resolvem.

---

### B3-16. Duas portas públicas para a mesma resposta, e um apelido sem função

- `hoveredBar()` (`:1530`) e `barUnderCursor()` (`:1558`) têm o mesmo javadoc
  palavra por palavra e o segundo é `return hoveredBar();`. Duas entradas
  públicas para uma pergunta.
- `:1141` e `:1173`:
  ```java
        java.util.List<java.time.LocalDate> onScreen = RenkoSource.sessionsIn(source);
        ...
        java.util.List<java.time.LocalDate> days = onScreen;
  ```
  `days` existe só para ser capturada pelo `SwingWorker`, o que `onScreen` — já
  efetivamente final — faria sozinha.

---

### B3-17. `setPeriod` compara períodos por identidade, e o atalho nunca pega para renko

`ChartCanvas.java:907`

```java
        if (newPeriod == null || newPeriod == period) {
            return;
        }
```

`Renko` e `Timeframe` não sobrescrevem `equals` (`grep -n "public boolean
equals"` nos dois: nada), e `PeriodCatalog.byCode` monta objetos novos a cada
chamada. Escolher de novo o período que já está na tela dispara um `refold()`
completo — e com ele o `rebuildFromTicks()` do B3-9 e um `SwingWorker`. Não
produz resultado errado; produz o trabalho todo de novo.

**Tentei refutar assim.** Verifiquei o caso que mais me preocupava,
`setWicks(show)` com o valor que já está ligado: `Renko.withWicks` devolve
`this` quando nada muda (`Renko.java:192-194`), então esse caminho está
protegido. Sobra a escolha do mesmo período pelo diálogo e pela restauração.

---

### B3-18. Alocação por quadro no caminho de pintura

Não é por elemento — por isso BAIXA, e por isso separado do B3-2 e do B3-3 —
mas é a cada repintura, e a repintura acontece a cada movimento do mouse:

- `:2019`, `:2578`, `:2657`, `:2678`, `:2487` — um `BasicStroke` novo por
  quadro cada (o de `:2657` traz também um `float[]`), enquanto `BOUNDARY`
  (`:191`) mostra que a classe já sabe guardar traço em constante
- `:2124`, `:2452`, `:2686` — três `DecimalFormat` por quadro, cada um com um
  `DecimalFormatSymbols.getInstance`
- `:2121`, `:2165`, `:2246`, `:2449` — quatro `deriveFont` por quadro
- `plotBounds()` (`:1609`) constrói um `Rectangle` a cada chamada e é chamada
  muitas vezes por quadro; `Viewport` copia o retângulo mais uma vez no
  construtor (`Viewport.java:67`)

O caminho de `gridStep(viewport)` é percorrido quatro vezes por quadro
(`:2016`, `:2110`, `:2452`, `:2686`) para produzir o mesmo número.

---

### B3-19. O javadoc de classe lista cinco camadas; `paintComponent` pinta dez

`ChartCanvas.java:58-64` desenha a ordem como `background · grid · style · axes ·
crosshair`. `paintComponent` (`:1989-1997`) faz grid, style, **overlays**,
priceAxis, timeAxis, **lastPrice**, crosshair, **jumpButton**, **ruler**,
**readout**. Como o parágrafo seguinte diz que "a ordem é o desenho", a lista
incompleta é a descrição de uma decisão que mudou e não foi reescrita — e as
quatro camadas que faltam são justamente as que se sobrepõem entre si.

---

# LIMPO

O que eu conferi e está certo, e como conferi.

**Índices de barra contra o `Viewport`.** `Viewport.lastBar()` é *"one past the
last visible bar"* (`Viewport.java:178-181`). Conferi os sete lugares que o
usam, um a um: `paintOverlays:2046` (`i < to`, exclusivo — certo),
`paintTimeAxis:2176` (`i < lastBar()`, certo), `paintDayBand:2249-2262`
(sentinela `i <= limit` com leitura só em `i < limit`, certo),
`axisSpeaksInDays:2318` (`min(...) - 1`, último índice válido, certo),
`timeStep:2395` (idem), `paintLastPrice:2434` (idem), `lastVisibleBar:1564`
(`min(size-1, first+visible-1)`, certo). Nenhum lê uma barra que não existe e
nenhum deixa a última de fora.

**A aritmética do zoom da roda.** Refiz `firstBarForZoom` (`:1736-1740`) contra
`Viewport.x` e `Viewport.barAt` (`:234`, `:245`): com `x(i) = (i - firstBar +
0,5) · largura/barras`, manter a barra sob o cursor exige `firstBar' = anchor -
x·barras/larguraDoPlot`, que é exatamente o que o método faz. O
`plotBounds().width` passado em `:2966` é o da área de desenho, não o do
componente — que era o defeito antigo, e `ChartViewTest:56-77` o tranca com
números de dois lados (o certo e o errado, com a diferença de dez barras
nomeada). Teste com dentes.

**A posição guardada como distância do fim.** `storeView:1909` grava
`size - firstBar`; `restoreView:1939` devolve `size - fromEnd`. Refiz a conta do
teste (`ChartViewTest:238-264`) na mão: 1.000 barras em `firstBar=300` grava 700;
1.566 barras devolvem 866 — que é a mesma barra de antes contada do fim. O
padrão quando a chave falta (`:1937`, `visibleBars - rightMargin`) reproduz a
fórmula antiga de "abre no fim" (`size - visibleBars + rightMargin`), então
espaço de trabalho velho não quebra.

**O atalho de `refold` que preserva a vista.** `:1492-1503` só dispara com
`wasSize > 0 && series.size() == wasSize`. Procurei um caso em que ele
preservasse a vista errada: `setSeries` num canvas recém-criado tem
`wasSize == 0` e cai fora; `growHistory` muda o tamanho por definição e depois
reescreve `firstBar` em `:881` de qualquer modo; a alternância de caldas passa
por `Renko.withWicks`, que não muda a contagem de tijolos. Não achei caso ruim.

**Fechamento da `TickLibrary`.** Segui as quatro saídas de `rebuildFromTicks`:
`:1168` (ticks não estão lá), `:1229` (período ou fonte mudaram), `:1255`
(gráfico comum, dobra e solta), `:1270` (a leitura falhou); e o caminho de
replay, onde a biblioteca vira `growingFrom` e é fechada por `stopGrowing()`
(`:1063-1070`), chamado de `setTickSource`, do próximo `rebuildFromTicks` e do
`releaseTicks()` que o `ChartHolder.close()` invoca (`ChartHolder:584`). O
`sourceForBricks` fecha as duas de sondagem no `finally` (`:1102-1108`). Nenhuma
saída larga biblioteca aberta.

**A não-remoção em `removeNotify`.** `:1784-1787` explica por que
`stopGrowing()` NÃO está ali: redocar e desdocar passam por `removeNotify`, e
matar o renko a cada gesto de janela seria o defeito. Conferi que
`ChartHolder.close()` (`:584`) é o dono do fechamento e que ele é o único
caminho de fecho de verdade. Correto, e o comentário é verdadeiro.

**Texto de tela.** As seis únicas cadeias que chegam ao olho do leitor saem do
bundle: `:463`, `:469`, `:478`, `:663`, `:979`, `:980`
(`grep -n "Messages.get" ChartCanvas.java`). Os cinco `drawString` restantes
imprimem número formatado ou data formatada por `DateTimeFormatter` — nada
literal. Nenhum `setToolTipText` neste arquivo.

**Domínio não importa interface.** Os imports de `:20-51` vão todos no sentido
certo: `ui.chart` importando `domain.market`, nunca o contrário.

**A zona morta do arrasto vertical.** `:2931-2934`: dentro dos seis pixels
`slack` é 0 e `priceOffset` recebe `grabbedOffset` inalterado, então
`isAutomaticScale()` (`:615-617`) continua verdadeiro e a barra de ferramentas
não desmarca. É o defeito que o comentário descreve, e a correção fecha.

**`axisBucket` no fuso e não em UTC.** `:2366-2380`: o passo semanal recua até a
segunda-feira local em vez de dividir o epoch por sete — que é a armadilha que o
javadoc descreve (1970-01-01 foi quinta) e que a memória do projeto registra em
"Agregação por fuso". A faixa de dias logo abaixo (`:2263`) usa o mesmo fuso,
então as duas linhas do eixo concordam dentro de uma repintura.

**Passar o nível, não alcançá-lo, e a grade absoluta do renko.** Conferi que
`ChartCanvas` não decide nada disso: ele delega a `period.apply(source)` e a
`TickRenko`. Não há nenhuma aritmética de tijolo neste arquivo — o que é a
divisão certa, e é o motivo de o B3-1 ser sobre qual série vai à tela e não sobre
como o tijolo é assentado.

**Leitura do futuro no replay.** `clockNow()` (`:1374-1381`) lê
`ReplaySeries.clock()`, não o carimbo da barra — e o javadoc explica por quê (o
carimbo é o início do balde, e lê-lo congelaria o renko dentro do minuto). O
`now + 1` passado a `advance` (`:1428`) inclui o negócio impresso exatamente no
instante do relógio, não os posteriores: `TickRenko` conta com `countUntil(when)`
(`:216`). Não há leitura do futuro aqui.
