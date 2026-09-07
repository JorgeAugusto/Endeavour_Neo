# L1 — lente transversal: thread da interface e concorrência

Auditoria II, 07/09/2026. Não é uma área: é **uma pergunta em todo o `src/main`**
— o que corre na EDT que não devia, o que corre fora dela e toca componente, e
que estado é partilhado entre as duas sem guarda.

## Como foi feito

Partida de `grep`, leitura de ±40 linhas em volta de cada ocorrência, nunca do
arquivo inteiro. Padrões varridos (sempre com `--include="*.java"`, porque
`src/main/.../graphify-out/` polui todo grep):

```
SwingUtilities.invokeLater|invokeAndWait|SwingWorker|isEventDispatchThread|EventQueue.
synchronized|volatile|ConcurrentHashMap|CopyOnWrite|Atomic|ExecutorService|Executors.
new Thread|Thread.sleep|CountDownLatch|ReentrantLock|new Timer(|javax.swing.Timer
Files.|FileChannel|newInputStream|newBufferedReader|RandomAccessFile   (em ui/ e platform/)
Sessions.of(|SeriesCatalog.open|MarketFile.|countOf(|Segmentable.     (em ui/)
```

**21 ocorrências** de `invokeLater`/`SwingWorker`/`isEventDispatchThread`,
**45** de primitivas de concorrência, **8** de I/O em `ui`/`platform`, **19** de
leitura de série a partir de `ui`. Total examinado: **93 ocorrências**, em 20
arquivos.

Lido antes: `docs/auditoria/ii/00-briefing.md`, `~/.claude/skills/convencoes/SKILL.md`
e os 215 achados de `docs/auditoria/ii/00-achados.md`. **Nada aqui repete
achado das dez áreas** — as sobreposições que encontrei estão nomeadas dentro de
cada "Tentei refutar assim".

---

## ALTA

### L1-1. Trocar de idioma deixa `System.out` e `System.err` presos ao console da janela morta

`ui/shell/MainWindow.java:1031-1050`, `Launcher.java:145`, `ui/shell/Console.java:90-110`

```java
    private void relaunch() {
        prepareToLeave();
        storeLayout();
        ...
        closeCharts();
        dispose();

        br.com.jorge.reis.endeavourneo.platform.Language.install();

        // Built after the locale changes, or the title would still be the old
        // language while everything inside it was the new one.
        MainWindow fresh = new MainWindow(Messages.get("app.title"), jobs);

        fresh.setVisible(true);
    }
```

O desvio da saída padrão é feito **uma vez só**, no arranque, e nunca mais:

```java
            // Order matters: capture standard output only once the console
            // exists, otherwise the first lines are lost.
            window.getConsole().captureStandardOutput();
```

```java
    public void captureStandardOutput() {
        PrintStream stream = new PrintStream(new OutputStream() {
            private final StringBuilder pending = new StringBuilder();
            @Override
            public void write(int b) {
                ...
                    Console.this.write(pending.toString());
```

**Problema.** `captureStandardOutput()` tem **um único chamador em toda a base**
(`grep -rn captureStandardOutput src/main src/test` devolve `Launcher.java:145`
e a própria declaração). O `PrintStream` que ele instala em `System.out` e
`System.err` captura `Console.this` — o console da **primeira** janela.
`relaunch()` descarta essa janela e constrói outra, com um console novo, e não
volta a chamar o desvio. A partir daí toda a saída padrão da aplicação continua
a ser escrita no `JTextArea` de uma janela que já foi `dispose()`.

**Consequência.** Duas, e a primeira é silenciosa por definição:

1. **A partir da primeira troca de idioma, o console da aplicação fica cego para
   `System.out`/`System.err` para o resto da sessão.** Numa aplicação de janela
   a saída padrão não tem outro destino — o javadoc do próprio método diz isso
   ("standard output has nowhere else to go"). Todo `printStackTrace`, todo
   `System.err.println` de `TickLibrary` (BAIXA B1-23), todo aviso de
   `data.zone` inválida do `Launcher` desaparecem sem uma palavra. O console
   novo continua a mostrar o que é escrito por `console.write(...)` direto, o
   que faz a falha parecer parcial e portanto ainda mais difícil de ver.
2. **Toda a janela antiga fica retida para sempre.** `dispose()` liberta o peer
   nativo, não corta as referências Java: `System.out` → `PrintStream` →
   `OutputStream` anónimo → `Console.this` → `JTextArea` → cadeia de pais → o
   `MainWindow` velho, o `Navigator`, o `Console`, e todo `ChartCanvas` que
   estava aberto com a sua série. Uma raiz **estática da JVM** a segurar uma
   árvore de componentes por troca de idioma.

**Correção.** Chamar `fresh.getConsole().captureStandardOutput()` em
`relaunch()`, logo depois de `fresh` existir e antes de `setVisible` — a mesma
ordem que o `Launcher` documenta. E, para que o `PrintStream` velho não fique a
segurar nada, `Console` devia guardar o `System.out` original no primeiro
desvio e um `releaseStandardOutput()` devia repô-lo; sem isso cada relançamento
acrescenta uma camada. O mínimo que fecha o defeito visível é a primeira linha.

**Tentei refutar assim.** (a) Procurei um segundo chamador do desvio em `src/main`
e em `src/test`: não há. (b) Procurei se `MainWindow` faz o desvio no construtor
— não faz; `getConsole()` só devolve o campo. (c) Verifiquei se `relaunch()` é
mesmo alcançável: `openPreferences()` (linha 1088-1090) compara
`Language.remembered()` antes e depois do diálogo e chama `relaunch()` quando
muda — é o caminho normal de trocar de idioma. (d) Verifiquei se o
`JobService` escapa disto: escapa, porque `FAILURES = System.err` é capturado
no carregamento da classe (`JobService.java:277`), antes do desvio — o que
significa que os relatórios de última hora vão para o terminal e **não** para o
console, mas isso é decisão deles e está documentada. (e) Conferi que não é o
mesmo que **B7b-7** ("Trocar de idioma abandona um `StatusBar` para sempre
dentro do `JobService`"): objeto diferente, mecanismo diferente (lista de
ouvintes sem remoção contra uma raiz estática da JVM), e B7b-7 não tem a
consequência de silenciar a saída. Não caiu.

---

## MÉDIA

### L1-2. O `SwingWorker` do renko de ticks adota uma `TickLibrary` num gráfico já fechado — a fuga que os dois javadoc dizem ter fechado

`ui/chart/ChartCanvas.java:1236-1250`, com `ui/chart/ChartHolder.java:571-585`

```java
                    if (replaying) {
                        // CLOSED FIRST. Two workers landing one after the other
                        // dropped the earlier library on the floor -- a reading
                        // thread and up to three sessions of ticks, held for the
                        // life of the application, once per race.
                        stopGrowing();

                        growing = built;
                        growingFrom = library;
```

A única guarda de vivacidade em `done()` é (linha 1227):

```java
                if (asked != period || askedOf != source) {
                    library.close();
                    return;
                }
```

**Problema.** `ChartHolder.close()` faz, nesta ordem, `detachReplay.run()` e
`canvas.releaseTicks()`. O `detachReplay` que `ReplayDrop.attach` instala é
`() -> session.forget(follow)` (`ui/replay/ReplayDrop.java:87-88`) — **só
cancela o observador**. Nem ele nem `close()` chamam `canvas.setTickSource(null,
false)` nem `canvas.setSeries(beforeReplay)`; quem faz isso é o método
`ChartHolder.detachReplay()` (linha 322), que `close()` **não** chama. Logo os
campos `period` e `source` do canvas ficam exatamente como estavam, e a guarda
de `done()` passa. O local `replaying` foi capturado como `true` antes do
fechamento, portanto o ramo acima corre: `stopGrowing()` não tem nada para
fechar (`releaseTicks` já correu), e a linha seguinte instala a `library` recém
construída num canvas morto. Ninguém volta a chamar `releaseTicks()`.

**Consequência.** Uma thread `ticks` daemon viva e até três sessões de ticks
residentes — o próprio `TickLibrary.close()` diz "hundreds of megabytes" e
`ReplaySession` mede 340 MB para três — seguras até o processo terminar, por
gráfico fechado durante uma reconstrução. É textualmente a fuga que os
comentários de `ChartCanvas.releaseTicks()` ("Until this existed, the library
survived the chart — a reading thread and up to three sessions of ticks, for the
life of the application, per chart ever closed with a replay on it") e de
`ChartHolder.close()` afirmam ter fechado; a afirmação é verdadeira só para o
caso em que nenhum worker está em voo. A janela não é estreita: o próprio
javadoc de `rebuildFromTicks` justifica o worker por o trabalho ser lento, e
`ReplayPanel` mede "five sessions of past fold into candles in about four
seconds".

**Correção.** Marcar o canvas como largado — um `boolean released` posto por
`releaseTicks()` — e testá-lo em `done()` antes do ramo `replaying`, fechando a
`library` quando estiver posto. Um `boolean` basta: `done()` corre sempre na
EDT, como `releaseTicks()`.

**Tentei refutar assim.** (a) Procurei uma guarda a mais em `done()`: são só as
duas comparações da linha 1227. (b) Testei se `close()` altera `source` por
outra via — segui `detachReplay` até `ReplayDrop.java:87` e confirmei que é
`session.forget(follow)`, que só mexe na lista de observadores da sessão. (c)
Testei se `isDisplayable()`/`removeNotify` fechariam: `removeNotify` (linha
1795) documenta explicitamente que **não** chama `stopGrowing()`, de propósito,
por causa do re-parenting. (d) Conferi que não é **B7b-4**: aquele é o
`SwingWorker` de `MainWindow` (linhas 404 e 474), escreve série num canvas
morto e o seu relatório mede o custo em MB de série; este é outro arquivo, outro
worker, e o que fica retido é um `ExecutorService` vivo mais a biblioteca de
ticks. Não caiu.

### L1-3. A árvore do navegador é construída inteira na EDT — varredura recursiva mais uma abertura de arquivo por sessão de ticks exportada

`ui/shell/Navigator.java:62` e `340-352`, chegando a
`domain/market/TickLibrary.java:309-317` e `domain/market/TickFile.java:133`

```java
    public Navigator() {
        super(new BorderLayout());

        tree = new JTree(treeModel());
```

```java
        for (br.com.jorge.reis.endeavourneo.domain.market.TickSource source
                : br.com.jorge.reis.endeavourneo.domain.market.TickSource.values()) {
            List<java.time.LocalDate> days;
            ...
            try (br.com.jorge.reis.endeavourneo.domain.market.TickLibrary library =
                         new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                                 folder, instrument, source)) {
                days = library.exported();
            }
```

e o que `exported()` faz por arquivo encontrado:

```java
        // Four: the source, the year, the month, the file.
        try (var files = Files.walk(folder, 4)) {
            files.filter(...)
                    .forEach(file -> {
                        LocalDate day = source.sessionIn(file);
```

que desce a `TickFile.sessionOf`:

```java
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
```

**Problema.** `treeModel()` é chamado do **construtor** do `Navigator`, que o
construtor do `MainWindow` corre na EDT, e outra vez de
`MainWindow.java:1072` (`navigator.setModel(Navigator.treeModel())`), também na
EDT, sempre que a janela de séries muda alguma coisa. Ele faz, tudo em linha:
`SeriesCatalog.names()` — que é `Files.walk(folder, 3)` mais um
`Files.isRegularFile` por nome (`SeriesCatalog.java:530` e `555`) — e depois,
por instrumento, **um `Files.walk(folder, 4)` por valor de `TickSource`**, cada
um abrindo um `FileChannel` e lendo o cabeçalho de **cada** arquivo de ticks
encontrado. Dois `TickSource` hoje, portanto a mesma pasta é percorrida duas
vezes por instrumento.

**Consequência.** A construção da janela principal e cada rebuild da árvore
bloqueiam a EDT por tantas aberturas de arquivo quantas sessões exportadas
existirem. Hoje são poucas dezenas (`docs`: 20 sessões MetaTrader, 9 de tape do
Profit) e a espera passa por arranque; a fonte completa de ticks já está prevista
no plano do projeto, e cada sessão nova acrescenta um `open`+`read` de cabeçalho
ao caminho síncrono. É exatamente o padrão que o `Launcher` já resolveu para o
calendário do replay — aquecer num job de fundo — e que a árvore não usa.

**Correção.** Construir o modelo num `JobService.submit` e instalá-lo com
`setModel` no `whenDone` (que já entrega na EDT), mostrando a raiz e um nó
"a carregar" enquanto isso. O `Launcher` já tem o job "sessions": a listagem de
ticks pode ser aquecida ali, num mapa análogo ao `ReplayFeed.KNOWN`.

**Tentei refutar assim.** (a) Confirmei que `treeModel()` não é chamado de fora
da EDT: os dois chamadores são `Navigator.java:62` (construtor, dentro do
construtor de `MainWindow`, que corre no `invokeLater` do `Launcher`) e
`MainWindow.java:1072` (callback de `SeriesWindow.open`, EDT). (b) Confirmei que
`sessionIn` lê mesmo o arquivo, e não só o nome — `TickFile.sessionOf` abre um
`FileChannel`. (c) Conferi que não é **B6-4** ("Abrir o transporte faz varredura
recursiva de disco na EDT, duas vezes"): aquele é o `ReplayPanel`; este é o
`Navigator`, outro arquivo e outro momento (arranque da janela principal). (d)
Conferi que não é **B1-8**, que acusa o comentário de `Segmentable.exported()`
de mentir sobre o custo — ali o achado é o comentário, aqui é a thread. Não caiu.

### L1-4. `SeriesCatalog.open(name)` é testa-e-age sobre o cache, e o job de aquecimento corre em paralelo com a EDT

`platform/SeriesCatalog.java:636-653`

```java
    public static Optional<PriceSeries> open(String name) throws IOException {
        SoftReference<PriceSeries> held = LOADED.get(name);
        PriceSeries cached = held == null ? null : held.get();

        if (cached != null) {
            return Optional.of(cached);
        }

        Path file = fileOf(name);
        ...
        PriceSeries series = MarketFile.read(file);

        LOADED.put(name, new SoftReference<>(series));
```

**Problema.** `LOADED` é `ConcurrentHashMap`, mas a sequência `get` → miss →
`read` → `put` não é atómica. E há mesmo mais de uma thread a passar por aqui:
o `Launcher` (`Launcher.java:105-133`) submete o job "sessions", que abre **cada
série** com este método numa thread do pool, enquanto a EDT constrói a janela e
o leitor pode abrir a janela de séries (`Segmentable.java:143`, `SeriesCatalog.open(key)`)
ou o worker do transporte pode construir uma sessão (`ReplaySession.java:457`,
`baseSeries()`), ambos com o mesmo método.

**Consequência.** Duas threads que falham o cache no mesmo nome leem o mesmo
arquivo duas vezes — o javadoc do vizinho mede "six years of one-minute bars is
39 MB" — e existem, no pico, **duas cópias vivas** da mesma série no heap, numa
classe cujo javadoc se justifica por não querer "dying with a heap it refused to
let go of". Pior de graça: `Sessions.ANSWERED` é chaveado na **instância** da
série, portanto a segunda cópia paga outra vez a caminhada de 39-102 ms que o
job de arranque existe para evitar. Nada fica errado; fica pago duas vezes,
exatamente no minuto mais carregado da aplicação.

**Correção.** `LOADED.compute(name, ...)` ou um `Map<String, Object>` de
trancas por nome. Um `computeIfAbsent` **não** serve aqui sem cuidado: a função
de mapeamento faria uma leitura de 39 MB a segurar a tranca do bin, que é o que
o javadoc do `ConcurrentHashMap` pede para não se fazer — a tranca por nome é a
resposta certa.

**Tentei refutar assim.** (a) Verifiquei que as threads existem mesmo:
`Launcher.java:105` submete o job antes do `invokeLater`, e o job chama
`SeriesCatalog.open(name)` no laço da linha 123. (b) Verifiquei que o caminho da
EDT usa mesmo o `open(String)` de arquivo inteiro e não só a janela:
`Segmentable.java:143` e `ReplayFeed.java:164` usam. (c) Verifiquei se
`SoftReference` esconde o problema — não: `get()` devolver não-nulo é o caminho
feliz; o problema é quando **os dois** devolvem nulo. (d) Conferi que não é
**B7a-1** (`openUntil` ignora `upTo` quando está em cache): aquele é sobre a
janela ancorada e devolve dado errado; este é sobre a corrida e devolve dado
certo pago duas vezes. Não caiu.

### L1-5. Cada relatório de progresso de um job posta um evento na EDT, sem coalescência

`platform/JobService.java:377-396` e `430-436`

```java
    private Progress progressFor(Handle<?> handle) {
        return new Progress() {

            @Override
            public void report(double value) {
                fraction = value;
                notifyListeners();
            }
            ...
            @Override
            public void say(String text) {
                stage = text == null ? "" : text;
                notifyListeners();
            }
        };
    }
```

```java
    private void notifyListeners() {
        onEdt(() -> {
            for (Runnable listener : listeners) {
                listener.run();
            }
        });
    }
```

**Problema.** `report` e `say` são o que o trabalho chama para dizer onde vai, e
cada chamada empurra um `invokeLater` para a fila da EDT. Não há
temporizador, não há "só se passaram 50 ms", não há bandeira "já há um pedido
pendente". O ouvinte é `StatusBar.refresh` (`StatusBar.java:102`), que faz
`revalidate()`/`repaint()`.

**Consequência.** Um trabalho que reporte por unidade de trabalho — e a
aplicação lê "824.881 bars" de uma vez, número do próprio `Sessions.java:100`
— enfileira um evento por unidade. A EDT passa a gastar mais tempo a redesenhar
a barra de estado do que o utilizador tem eventos para processar, e o resultado
é a janela lenta que a classe existe para evitar ("trabalho longo sem retorno
visível parece travamento" é a convenção 2 desta casa, e isto é o outro extremo
da mesma régua). O único trabalho de hoje que reporta é o de amostra
(`MainWindow.runSampleJob`, 100 relatórios com `Thread.sleep(40)` entre eles) —
cujo próprio javadoc diz "Delete it once there is real work to run", o que
significa que o trabalho real está por vir e vai encontrar esta porta aberta.

**Correção.** Coalescer: guardar um `AtomicBoolean pending` e só postar quando
ele passar de `false` a `true`, limpando-o dentro do `Runnable` da EDT. É a
mesma técnica que `ReplaySession` já usa com `timer.setCoalesce(true)`
(`ReplaySession.java:313`).

**Tentei refutar assim.** (a) Procurei coalescência dentro de `StatusBar`: o
`refresh()` desenha sempre, sem comparar com o que já está escrito. (b) Procurei
um limitador dentro de `Progress` ou de `submit`: não existe. (c) Verifiquei se
`onEdt` já filtra chamadas repetidas: não — é só o `isEventDispatchThread ?
run : invokeLater` (linha 438-444). (d) Conferi que não é **B7a-12** (`stage` e
`fraction` são do serviço e dois jobs sobrepõem-se): aquele é sobre **qual**
texto aparece; este é sobre **quantas vezes** ele é reenviado. Não caiu.

### L1-6. `ChartPreferences` e `RulerMode` são estado global mutável sem `volatile`, e são lidos fora da EDT

`ui/chart/ChartPreferences.java:56-79`, `ui/chart/RulerMode.java:53`, lido em
`ui/replay/ReplaySession.java:269` a partir de `ui/replay/ReplayPanel.java:600-607`

```java
    private static boolean periodLine = PREFS.getBoolean(PERIOD_LINE, false);
    ...
    private static boolean syntheticTicks = PREFS.getBoolean(SYNTHETIC, true);

    private static boolean hollowCandles = PREFS.getBoolean(HOLLOW, true);
```

```java
    private static boolean on = PREFS.getBoolean(KEY, false);
```

e o leitor fora da EDT:

```java
        TickPath path = (bars, index) ->
                br.com.jorge.reis.endeavourneo.ui.chart.ChartPreferences.syntheticTicks()
                        ? invented.pathFor(bars, index) : null;
```

construído dentro de

```java
            @Override
            protected ReplaySession doInBackground() {
                return new ReplaySession(chosen, first, end,
```

**Problema.** Os campos são escritos exclusivamente na EDT (`ChartPage.apply`,
`GeneralPage.apply`, `ChartCanvas.applyMode`) e lidos, além da EDT, de threads
de trabalho: o `TickPath` acima é construído no `doInBackground` do
`ReplayPanel` e depois avaliado a cada frame. Nenhum é `volatile`. É a mesma
falta que **B7a-22** reporta para `Messages.bundle` e `Segmentation.store`, e
que a área de plataforma comparou com `SeriesCatalog.folder`, que é `volatile`
(linha 117) — mas esses dois campos são de `platform` e estes dois são de
`ui/chart`, e nenhum dos dez relatórios os toca.

**Consequência.** Sem barreira de memória, uma sessão de replay construída logo
depois de o leitor desligar "ticks sintéticos" pode continuar a animar com o
passeio inventado, ou o inverso — e é uma diferença que o comentário de
`ChartPreferences.syntheticTicks` diz valer 27% na densidade do renko. Dano
improvável na prática (o `invokeLater`/`SwingWorker` no meio fornece a barreira
quase sempre), corrida real. O comentário da linha 264-266 de `ReplaySession`
promete precisamente o contrário — "consulted through the setting, not
captured, so turning it off takes effect on a replay already open" — e essa
promessa é a que a falta de `volatile` não garante.

**Correção.** `volatile` nos seis campos. Não há caminho quente a ler estes
booleanos por elemento; o custo é nulo.

**Tentei refutar assim.** (a) Confirmei que o construtor da `ReplaySession`
corre mesmo fora da EDT: `ReplayPanel.java:600-607`, `doInBackground()`. (b)
Procurei um `synchronized` ou uma cópia sob barreira em qualquer dos dois
arquivos: só a `CopyOnWriteArrayList` dos ouvintes, que não cobre os
`boolean`. (c) Verifiquei que **B7a-22** nomeia só `Messages.java:50` e
`Segmentation.java:70` — não estes. Não caiu.

### L1-7. O javadoc de `ReplaySession.preparing` descreve a thread errada, e o `Timer` nasce fora da EDT

`ui/replay/ReplaySession.java:116-122`, `298` e `312`

```java
    /**
     * True until the first session's ticks are in memory.
     *
     * <p>Volatile: set on the interface thread, read by it too, but written
     * from a callback that starts on the loader's thread before it hops over.
     * </p>
     */
    private volatile boolean preparing;
```

```java
        this.preparing = feed.isTicks() && ticks.has(date);
```

```java
        this.timer = new Timer(FRAME, e -> tick());
```

**Problema.** As três linhas estão no construtor, e o construtor corre no
`doInBackground()` do `SwingWorker` de `ReplayPanel.java:600` — nunca na EDT. O
javadoc diz "set on the interface thread"; a atribuição inicial é feita numa
thread do pool do `SwingWorker`. E `new Timer(...)`, que é um componente do
pacote `javax.swing`, é construído nessa mesma thread, contra a regra da casa
("Componente Swing só na EDT. Criar fora funciona quase sempre e falha de forma
que não se reproduz" — convenção 8).

**Consequência.** O campo é `volatile`, portanto o dano de memória está coberto
por acidente e não pelo motivo escrito. O que fica errado é o **contrato
documentado**: quem ler este javadoc a seguir vai concluir que o construtor da
`ReplaySession` corre na EDT — e é exatamente a conclusão que faria alguém
acrescentar aqui uma leitura pesada "porque já estamos no fundo", ou tirar o
`volatile` "porque é tudo EDT". O briefing é explícito: comentário que mente é
achado, de severidade igual ao defeito que esconde.

**Correção.** Reescrever o parágrafo para o que é verdade: *escrito na thread do
worker que constrói a sessão, escrito outra vez a partir do callback do
carregador depois de saltar para a EDT, lido pela EDT* — e dizer que é isso que
obriga ao `volatile`. Mover a construção do `Timer` para o `done()` do
`ReplayPanel` (ou para `adopt`), onde já se está na EDT.

**Tentei refutar assim.** (a) Confirmei que não há um segundo construtor
chamado da EDT: `grep "new ReplaySession("` em `src/main` devolve só
`ReplayPanel.java:602`, dentro do `doInBackground`. (b) Verifiquei se
`javax.swing.Timer` é declarado seguro fora da EDT — não é: a documentação diz
que os seus *listeners* correm na EDT, não que a construção seja livre, e a
convenção da casa é mais restritiva que a documentação de qualquer forma. (c)
Conferi que **B6-7** ("Quatro javadoc colados no membro errado em
`ReplaySession`, e o primeiro deles mente") é sobre javadoc **órfão** — javadoc
colado no membro errado; este está colado no membro certo e diz uma coisa falsa
sobre a thread. Confirmei que nenhum dos quatro que B6-7 nomeia é este. Não caiu.

---

## BAIXA

### L1-8. `TickLibrary.at()` é `synchronized` e está no caminho da animação

`domain/market/TickLibrary.java:145-149`, chamado de
`domain/market/RecordedTicks.java:59` e `82`

```java
    public TickSeries at(LocalDate day) {
        synchronized (resident) {
            return resident.get(day);
        }
    }
```

**Problema.** A convenção 8 é literal: *"Nada `synchronized` no caminho de
pintura. [...] Getter lido por repaint usa campo `volatile` e **não** é
sincronizado."* `RecordedTicks.timedPathFor` chama `at()` uma vez por barra
animada, na EDT, a cada frame do replay; e o próprio javadoc de `resident`
(linha 89-91) diz "It is touched by the loader thread and by whoever is
drawing", ou seja, sabe que está a pôr um monitor no caminho de desenho.

**Consequência.** Pequena e limitada, e por isso BAIXA: o carregador só segura o
monitor durante o `put` e o laço de despejo, que percorre no máximo `RESIDENT`
chaves — a leitura de disco (`source.read`) está corretamente **fora** do
`synchronized`. O que se perde é a garantia da regra: a próxima pessoa a
acrescentar trabalho dentro de `keep()` congela a animação sem que nada no
código a avise.

**Correção.** Ou trocar `resident` por um `ConcurrentHashMap` com o despejo
feito sob uma tranca separada, ou — mais barato — acrescentar ao javadoc de
`keep()` a frase que falta: *nada de leitura ou de laço não limitado aqui
dentro; este monitor é pedido pela EDT a cada frame.*

**Tentei refutar assim.** (a) Confirmei que a leitura pesada está fora do bloco:
`queue()` faz `source.read(...)` como argumento de `keep(...)`, avaliado antes
de entrar no `synchronized`. (b) Confirmei que `RecordedTicks` corre mesmo na
EDT: é o `animation` da `ReplaySeries`, consultado pelo `tick()` do
`javax.swing.Timer`. (c) Procurei nos dez relatórios uma menção a este monitor:
**B2-10** fala de `TickRenko` correr na EDT, não deste `synchronized`. Não caiu,
mas é pequeno de propósito.

### L1-9. `JobService.deliver()` corre o handler dentro do próprio `synchronized`

`platform/JobService.java:189-243`

```java
        private synchronized void deliver() {
            ...
                onEdt(handler);
```

**Problema.** `deliver()` é `synchronized` no `Handle` e chama `onEdt`, que
quando já se está na EDT corre a ação **em linha**. O caminho normal é esse:
`jobs.submit(...).whenDone(...)` na EDT sobre um job que já terminou entra em
`whenDone` (sincronizado), chama `deliver()`, e corre o `Consumer` do chamador
— que abre gráficos, escreve no console, mexe na barra de estado — tudo com o
monitor do `Handle` na mão.

**Consequência.** Não há impasse: quem mais pega este monitor é a thread do
pool em `settle()`, e ela nunca espera pela EDT. O custo é uma thread do pool
poder ficar bloqueada em `settle()` durante o tempo que o handler da EDT levar.
Nada visível hoje.

**Correção.** Copiar handler e valor para locais, sair do bloco sincronizado, e
só então chamar `onEdt`. O código já copia (`Consumer<T> handler = onDone;`),
falta apenas a chamada estar fora.

**Tentei refutar assim.** Procurei um caminho em que a EDT fique à espera de
alguém que espere por este monitor — não existe: `settle()` é chamado do
`finally` do trabalho, e as outras entradas (`whenDone`, `whenFailed`,
`whenStopped`, `reportIfUnclaimed`) nunca bloqueiam. Por isso é BAIXA e não
MÉDIA. Não caiu como defeito, caiu como gravidade.

### L1-10. `TickLibrary.queue` deixa escapar `RejectedExecutionException` e o dia trancado em `loading`

`domain/market/TickLibrary.java:242-262`

```java
    private void queue(LocalDate day) {
        if (day == null || at(day) != null || !loading.add(day)) {
            return;
        }

        loader.execute(() -> {
```

**Problema.** `loading.add(day)` acontece **antes** de `loader.execute`. Se o
executor já foi desligado (`close()` chama `loader.shutdownNow()`),
`execute` lança `RejectedExecutionException` na thread de quem chamou — a EDT,
via `request()` — e o `finally` que limparia `loading` nunca corre, porque nunca
houve tarefa.

**Consequência.** Hoje é inalcançável por sorte: o único `request()` de produção
sobre uma biblioteca que pode estar fechada é `ChartCanvas.java:1432`, e está
sob `if (growingFrom != null)`, e `stopGrowing()` fecha e anula na mesma
instrução da EDT. Fica como armadilha: o dia trancado em `loading` faz `queue`
recusar em silêncio para sempre, e a exceção sobe crua para um tratador de
evento.

**Correção.** `if (loader.isShutdown()) { return; }` no topo, e envolver o
`execute` num `try`/`catch (RejectedExecutionException)` que faça
`loading.remove(day)`.

**Tentei refutar assim.** (a) Enumerei todos os chamadores de `request()`:
`ChartCanvas.java:1432-1433` e `ReplaySession.java:309` (no construtor, antes de
qualquer `stop()`). Ambos guardados hoje — por isso BAIXA e não MÉDIA. (b)
Conferi que **B1-9** ("`TickLibrary.close()` corre com o carregador") é sobre a
tarefa **já submetida** voltar a encher `resident` depois do `close`; esta é
sobre a submissão que é **recusada**. Não caiu.

---

## LIMPO

O que conferi e está certo, e como.

**`onEdt` nos três lugares que o têm.** `JobService.java:438-444`,
`Console.java:174-180` e `StatusBar.java:226-232` implementam o mesmo padrão
`isEventDispatchThread() ? run : invokeLater` e todos os métodos públicos que
tocam componente passam por ele (`Console.write`, `Console.clear`,
`StatusBar.say`, `StatusBar.chart`, `StatusBar.refresh`). Conferi cada método
público destas três classes contra a lista: nenhum toca componente sem passar
pelo `onEdt`. É a convenção 8 ("Método público chamado de dentro de
`SwingWorker` deve se encaminhar sozinho") cumprida à letra.

**Fecho de recursos por quem os abre.** Segui as sete construções de
`TickLibrary` em `src/main`: `Navigator.java:340` (try-with-resources),
`ReplayFeed.java:152` (`finally`), `ChartCanvas.java:1099` (`finally`),
`ChartCanvas.java:1157` (documentada, com dono explícito — é a que o L1-2 mostra
poder falhar numa corrida, e só nessa), `ReplaySession.java:225` (fechada em
`stop()`, linha 768). O `ExecutorService` do `JobService` é fechado em `close()`
com `awaitTermination(2, SECONDS)` e um gancho de encerramento
(`Launcher.java:104`); as suas threads são nomeadas e não-daemon por escolha, a
do `TickLibrary` é daemon por escolha, e ambas as escolhas estão justificadas
nos javadoc. Não encontrei `Executors.` nem `new Thread` em mais lugar nenhum
de `src/main`.

**I/O na EDT: varri todos os `Files.`/`FileChannel` de `ui/` e `platform/`.**
São oito ocorrências. `Settings.java:158/166/311/313` (coberto por B7a-10),
`SeriesCatalog.java:526/530/555` (coberto por B7a-17/18, e a thread está no L1-3
por outra razão) e `Navigator.java:341` (L1-3). Não há mais nenhum caminho de
disco escondido em `ui/`: as leituras de série passam todas por
`SeriesCatalog`/`MarketFile`, que estão em `platform`/`domain`.

**Os dois caminhos de leitura pesada que já estão fora da EDT, e estão certos.**
`MainWindow.fillFromSeries`/`fillFromTicks` (linhas 404 e 474) e
`ChartCanvas.rebuildFromTicks` (linha 1200) fazem a leitura no
`doInBackground` e só escrevem no `done()`; o `ReplayPanel` (linha 600)
constrói a sessão no fundo e congela os controlos com `building = true` antes.
Verifiquei que a guarda de `building` fecha mesmo a porta a um segundo pedido:
`refresh()` (linha 665-680) desabilita `request`, `feed`, `date`, `until`,
`play`, `stop` e o resto, de forma síncrona, antes de o `execute()` acontecer —
portanto a EDT não chega a processar um segundo clique. Foi a hipótese "dois
`SwingWorker` de replay em voo deixam uma sessão órfã de 340 MB" que tentei
provar e **não** consegui: não há como disparar o segundo.

**`Sessions.ANSWERED`.** É `Collections.synchronizedMap(new WeakHashMap<>())` e
a tranca nunca é segurada durante a caminhada — confirmei lendo
`Sessions.java:128-165`: o `get`, o `put` e a cópia são as únicas operações
sobre o mapa, e o laço de 824.881 barras está fora de qualquer bloco
sincronizado. Testei também a hipótese "a chave é um record, logo duas séries
distintas partilham entrada": `SeriesMerge.Joined` (`SeriesMerge.java:127`) é
mesmo um `record` que implementa `PriceSeries`, mas os seus componentes têm
`hashCode` de identidade e a entrada guarda a zona e o tamanho, portanto uma
partilha entre dois `Joined` iguais devolve a resposta certa. Refutei o meu
próprio achado.

**`Console.captureStandardOutput` e o `StringBuilder pending` partilhado.**
Testei se ele precisa de `synchronized`: não precisa, porque
`System.out` e `System.err` apontam para **o mesmo** `PrintStream`, e o
`PrintStream` serializa `write` na sua própria tranca. Resta o entrelaçamento de
`print` sem `\n` vindo de threads diferentes, que é cosmético e cai dentro do
que **B7b-8** já descreve para esta mesma classe anónima. Não reportei.

**`ReplayFeed.KNOWN`.** `computeIfAbsent` com uma função de mapeamento de
39-102 ms é contra a recomendação do `ConcurrentHashMap`, mas verifiquei que a
função não escreve no próprio mapa (só devolve), portanto não há impasse; e o
custo na EDT do cache frio já é **B6-5**. Não reportei.

**Escrita concorrente de `Settings`.** Enumerei os chamadores de `put`/`putInt`/
`putBoolean`: todos na EDT (`MainWindow`, `ChartHolder`, `ReplayPanel`,
`ChartPage`, `RulerMode`, `ChartPreferences`) exceto `Theme.remember()`, que
corre na thread `main` do `Launcher` **antes** do `invokeLater` que constrói a
janela. Nenhuma sobreposição possível, portanto não há duas escritas do arquivo
inteiro ao mesmo tempo. O gancho de encerramento (`jobs::close`) não escreve
configurações. A não-atomicidade da escrita é **B7a-10**.

**Estado estático mutável, varrido inteiro.** Encontrei nove campos:
`Timeframe.zone` (`volatile`, escrito só no arranque antes do pool),
`SeriesCatalog.folder` (`volatile`), `ReplayFeed.following` (`volatile`, com o
testa-e-age de **B7a-24**), `JobService.stage`/`fraction` (`volatile`, com
**B7a-12**), `Messages.bundle` e `Segmentation.store` (**B7a-22**), e
`ChartPreferences`×5 + `RulerMode.on` (L1-6). As listas estáticas de ouvintes
(`ChartPreferences.LISTENERS`, `RulerMode.LISTENERS`, `SeriesCatalog.FORGETFUL`)
são `CopyOnWriteArrayList`; conferi que as duas primeiras têm `forget()` e que
`ChartCanvas` o chama em `removeNotify` (linhas 1796-1797), portanto não há fuga
de gráfico fechado por essa via — o que sobra é `FORGETFUL`, que não tem
remoção mas está documentado como "registered once at startup and never
removed", e cujo problema de exceção/thread é **B7a-5**.

**`Thread.sleep` e `.join()`.** Uma única ocorrência de `Thread.sleep` em
`src/main` (`MainWindow.java:1116`), dentro do trabalho de amostra, numa thread
do pool. Zero `.join()`, zero `invokeAndWait`, zero `CountDownLatch`, zero
`ReentrantLock`, zero `java.util.Timer`. Um único `javax.swing.Timer`
(`ReplaySession.java:312`), com `setCoalesce(true)`.
