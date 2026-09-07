# L1 — EDT e concorrência

Lente transversal sobre `endeavour_neo/src` (169 arquivos Java). Uma pergunta só:
**a thread está certa, e o que foi ligado é desligado?**

## Padrões rodados

| padrão | ocorrências | lidas (±40 linhas) |
| --- | ---: | ---: |
| `\.add[A-Za-z]*Listener\(` | 86 | 12 |
| `\.remove[A-Za-z]*Listener\(` | **0** | — |
| `\.(addListener\|subscribe\|register\|onChange\|watch\|listen)\(` | 10 (produção) | 10 |
| `\.(forget\|unwatch\|unsubscribe\|unlisten)\(` | 5 (produção) | 5 |
| `new Thread\|SwingWorker\|ExecutorService\|Executors\.\|CompletableFuture\|ForkJoin` | 13 | 13 |
| `javax\.swing\.Timer\|new Timer\(` | 1 construção, 5 `stop()`, 1 `start()` | 7 |
| `synchronized\|volatile\|Atomic*` | 20 | 20 |
| `invokeLater\|invokeAndWait\|isEventDispatchThread` | 12 (`invokeAndWait`: **0**) | 12 |
| `protected void paintComponent\|public void paint(\|paintChildren` | 17 | 6 |
| `addNotify\|removeNotify` | 2 | 2 |

Nenhum arquivo foi lido inteiro. Achados que o índice das nove áreas já
registra estão na seção **Já visto pelas áreas**, com o id, e não são
reportados de novo.

---

## Achados ALTA

### L1-1 — Trocar o idioma com um replay aberto deixa a sessão viva: o `Timer` não para, os 340 MB de ticks não saem e a thread de leitura fica

**Onde:** `ui/replay/ReplayWindow.java:51 e :60-66` contra
`ui/shell/MainWindow.java:819-821`

**Trecho (ReplayWindow):**
```java
        setDefaultCloseOperation(HIDE_ON_CLOSE);

        ReplayPanel panel = new ReplayPanel();

        setContentPane(panel);

        addWindowListener(new java.awt.event.WindowAdapter() {

            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                panel.release();
            }
        });
```

**Trecho (MainWindow.relaunch):**
```java
        if (replay != null) {
            replay.dispose();
            replay = null;
        }
```

**Problema:** `release()` — o único caminho que chama `session.stop()` — está
pendurado **exclusivamente** em `windowClosing`, que é o evento do X do
sistema. `Window.dispose()` **não dispara `windowClosing`**; dispara
`windowClosed`. `relaunch()` (trocar o idioma nas preferências) chama
`replay.dispose()` direto, e a sessão nunca é parada.

O que `ReplaySession.stop()` faria e não faz (`ui/replay/ReplaySession.java:689-703`):

```java
    public void stop() {
        stopped = true;

        timer.stop();

        // Três sessões de ticks são 340 MB...
        ticks.close();
```

**Consequência:** três coisas, todas permanentes até o processo morrer:

1. O `javax.swing.Timer` de 40 ms continua rodando e chamando
   `live.advanceMarketTime()` 25 vezes por segundo sobre uma sessão que
   ninguém mais vê — até o fim do intervalo do replay, o que pode ser muitos
   minutos a 1x. Se a sessão estava pausada, o timer está parado, mas os itens
   2 e 3 valem igual.
2. `ticks.close()` nunca roda: até 340 MB de sessões de ticks residentes ficam
   presas, e o `ExecutorService` da thread `ticks` (`TickLibrary.java:93`)
   nunca é desligado.
3. Nada mais referencia o `ReplayPanel` velho, então **não existe mais nenhum
   caminho** para parar essa sessão. Uma segunda troca de idioma cria a
   segunda.

`closeCharts()` roda logo depois (`MainWindow.java:824`) e cada
`ChartHolder.close()` chama `detachReplay.run()` → `session.forget(follow)`,
então os gráficos saem da lista de observadores. Isso **piora** o quadro em vez
de melhorar: o timer segue avançando o mercado sem ninguém olhando.

**Correção:** `ReplayWindow.dispose()` sobrescrito chamando `panel.release()`
antes de `super.dispose()` — assim os dois caminhos de fechamento fazem a mesma
coisa, que é exatamente a lição já escrita em `ChartHolder.java:462-464`
("Through close(), so the X and the programmatic path do the same things in the
same order"). Alternativa equivalente: `relaunch()` chamar `panel.release()`
antes do `dispose()`.

**Tentei refutar:** procurei `release()` em todo `src/main` — só aparece em
`ReplayPanel.java:277` (via `stopSession`, o botão) e em
`ReplayWindow.java:64`. Procurei `windowClosed`/`removeNotify`/`dispose()`
sobrescrito em `ReplayWindow` — a classe tem 78 linhas e não tem nenhum dos
três. Conferi `prepareToLeave()` (`MainWindow.java:246-250`): só grava a lista
de gráficos. Conferi `exit()` (`MainWindow.java:1041-1053`): também não toca no
replay, mas ali termina em `System.exit(0)` e o processo leva tudo junto — por
isso o achado é sobre `relaunch()`, o único caminho que descarta a janela **e
continua vivo**.

---

### L1-2 — O relatório de falha que o `JobService` existe para não perder é entregue à EDT pelo *shutdown hook*, e some

**Onde:** `Launcher.java:83` + `platform/JobService.java:390-394` +
`platform/JobService.java:141-146` + `ui/shell/Console.java:90-109`

**Trecho (Launcher):**
```java
        Runtime.getRuntime().addShutdownHook(new Thread(jobs::close, "jobs-shutdown"));
```

**Trecho (JobService):**
```java
    @Override
    public void close() {
        for (Handle<?> handle : unclaimed) {
            handle.reportIfUnclaimed();
        }
```
```java
        synchronized void reportIfUnclaimed() {
            if (settled && !delivered && error != null) {
                delivered = true;

                System.err.println("job \"" + name + "\" failed and nobody handled it:");
                error.printStackTrace();
            }
        }
```

**Trecho (Console):**
```java
    public void captureStandardOutput() {
        PrintStream stream = new PrintStream(new OutputStream() {
            ...
                if (c == '\n') {
                    Console.this.write(pending.toString());
```
e `Console.write` (`:69-75`) marshaliza tudo com
`SwingUtilities.invokeLater`.

**Problema:** `captureStandardOutput()` (chamado em `Launcher.java:99`)
redireciona `System.err` para um fluxo que **enfileira na EDT**. A partir daí,
tudo que `reportIfUnclaimed()` imprime vira um `invokeLater`. Mas
`reportIfUnclaimed()` só roda dentro de `close()`, e `close()` só é chamado do
*shutdown hook*, isto é, quando a JVM já está terminando: `System.exit(0)`
(`MainWindow.java:1053`) ou `EXIT_ON_CLOSE` (`MainWindow.java:166`). A JVM não
espera a EDT drenar sua fila para terminar.

**Consequência:** a mensagem `job "..." failed and nobody handled it:` e o
`printStackTrace` do único erro que ninguém tratou são postados numa fila que
não vai mais ser processada. O javadoc da classe (`JobService.java:44-53`) diz
que ela existe porque `SwingWorker` engole a exceção do
`doInBackground`; nesta configuração o `JobService` engole a dela do mesmo
jeito, só que no último instante e sem deixar rastro nem no terminal.

**Correção:** `reportIfUnclaimed()` deve escrever no `System.err` **original**,
guardado por `Console.captureStandardOutput()` antes de trocá-lo, e não no
capturado. Ou `close()` ser chamado também do caminho de saída normal
(`MainWindow.exit()` / `windowClosing`), antes do `dispose()`, enquanto a EDT
ainda roda — o *hook* fica só como rede para um `kill`.

**Tentei refutar:** procurei outro chamador de `jobs.close()`: só
`Launcher.java:83`. Procurei se `Console` guarda o `System.err` anterior: não
guarda — `System.setOut(stream); System.setErr(stream);` (`:108-109`) e nenhum
campo. Verifiquei se `PrintStream` com `autoFlush=true` mudaria algo: não, o
`flush` chega no `OutputStream` anônimo, que só age em `write(int)` e só
quando vê `'\n'`; o `Console.write` que ele chama já é o `invokeLater`.
Verifiquei se a EDT poderia ser um *shutdown hook* também: não é, e hooks
rodam em paralelo sem ordem garantida entre si.

---

## Achados MÉDIA

### L1-3 — A EDT trava no cadeado de um `ConcurrentHashMap` que o job de partida segura por 40-100 ms por chave

**Onde:** `ui/replay/ReplayFeed.java:149-171` (`sessions()`) chamado da EDT em
`ui/replay/ReplayPanel.java:395`, contra `ui/replay/ReplayFeed.java:183-192`
(`warm()`) chamado do job de fundo em `Launcher.java:88-89`

**Trecho:**
```java
    public NavigableSet<LocalDate> sessions() {
        return KNOWN.computeIfAbsent(saved(), key -> {
            if (isTicks()) {
                TickLibrary library =
                        new TickLibrary(SeriesCatalog.ticksOf(instrument), instrument, source);
                ...
            try {
                return br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(
                        SeriesCatalog.open(series).orElse(null));
```
```java
        jobs.submit("sessions", progress ->
                Integer.valueOf(br.com.jorge.reis.endeavourneo.ui.replay.ReplayFeed.warm()));
```

**Problema:** `ConcurrentHashMap.computeIfAbsent` executa a função de mapeamento
**segurando o cadeado do bin**. Aqui a função abre a série do disco e a percorre
— o próprio javadoc logo acima mede isso em "39-102 ms" por chave. `warm()`
roda essa função para **todas** as chaves numa thread do `JobService`, iniciada
em `Launcher.java:88`, antes mesmo de a EDT existir. `followFeed()` na EDT
(`ReplayPanel.java:395`) chama `sessions()` para a chave escolhida.

**Consequência:** abrir o transporte enquanto o `warm` ainda roda bloqueia a EDT
até o job terminar aquela chave — e, por colisão de hash no mesmo bin, também
para uma chave que já estaria pronta. O comentário promete o oposto ("so the
first calendar opens instantly"). Não é travamento eterno, mas é uma janela
congelada por causa de uma otimização feita para evitar exatamente isso.

**Correção:** computar fora do cadeado — `get`, e se for nulo calcular e depois
`putIfAbsent`. O custo é um recálculo raro em corrida; o ganho é a EDT nunca
esperar disco de outra thread.

**Tentei refutar:** conferi que `KNOWN` é `ConcurrentHashMap`
(`ReplayFeed.java:140`) e não `synchronizedMap` — é, e o comportamento de
`computeIfAbsent` segurando o bin é documentado. Conferi se `warm()` roda antes
da janela: `Launcher.java:88` vem antes do `SwingUtilities.invokeLater` da
linha 91, então as duas se sobrepõem por construção. Conferi se `followFeed()`
poderia estar fora da EDT: é chamado de `feed.addActionListener`
(`ReplayPanel.java:360`) e do construtor do painel, ambos EDT. **Adjacente a
A6-11**, que reporta que o cache nunca é esvaziado e que o conjunto escapa
mutável — coisa diferente desta.

---

### L1-4 — Um arquivo de ticks corrompido mata a thread de leitura sem avisar, e o replay fica com o play morto para sempre

**Onde:** `domain/market/TickLibrary.java:213-227` contra
`ui/replay/ReplaySession.java:263-271` e `:590-595`

**Trecho:**
```java
        loader.execute(() -> {
            try {
                if (has(day)) {
                    keep(day, source.read(fileFor(day)));
                    whenLoaded.run();
                }
            } catch (IOException e) {
                whenLoaded.run();
            } finally {
                loading.remove(day);
            }
        });
```
```java
        this.preparing = ticks.has(date);

        ticks.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(() -> {
            if (preparing && ticks.at(this.date) != null) {
                preparing = false;
```
```java
    public void toggle() {
        if (preparing) {
            return;
        }
```

**Problema:** o `catch` cobre só `IOException`. Um arquivo truncado ou com
cabeçalho inválido faz `source.read` lançar `RuntimeException` —
`BufferedUnderflowException`, `NumberFormatException`,
`ArrayIndexOutOfBoundsException` são todas plausíveis para um binário de tape.
Ela escapa do `catch`, o `finally` limpa `loading`, e `whenLoaded.run()`
**nunca é chamado**. `preparing` fica `true` para sempre.

**Consequência:** a sessão foi construída, o dia existe (`has(date)` era true),
mas o replay nunca fica jogável: `toggle()` retorna na primeira linha e o botão
fica desabilitado. O rastro é um *stack trace* do manipulador padrão de exceção
não capturada — que, com o `Console` instalado, só aparece se a EDT ainda
roda. Contraste com o cuidado explícito de `JobService.java:283-292`, que caça
`Throwable` justamente por esse motivo.

**Correção:** `catch (Exception e)` (ou `Throwable`) em vez de `IOException`, e
chamar `whenLoaded.run()` em qualquer falha — que é o que o próprio comentário
do `catch` diz que deveria acontecer: "falls back to synthetic ticks for that
day".

**Tentei refutar:** procurei um segundo caminho que limpasse `preparing`: só
existe o de `ReplaySession.java:267`. Procurei um *timeout* ou um estado de
desistência: não há. Verifiquei o caso `has(day) == false`: aí `whenLoaded`
também não roda, mas `preparing` já nasce `false` na linha 263, então esse
caminho está correto — o defeito é só o da exceção não-IO. Verifiquei que o
executor de thread única se recupera: recupera, cria outra thread; o que não se
recupera é o estado do replay. **Parente de A1-9**, que reporta
`TickLibrary.exported()` engolindo `IOException`, mas em outro método e com
outra consequência.

---

### L1-5 — `Navigator.tickSessions` é o único lugar que cria uma `TickLibrary` sem `try/finally close()`

**Onde:** `ui/shell/Navigator.java:350-352`

**Trecho:**
```java
            List<java.time.LocalDate> days =
                    new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                            folder, instrument, source).exported();
```

**Problema:** a instância nem sequer recebe nome. Todos os outros seis sítios de
construção fecham no `finally` — `ReplayFeed.java:114-123` e `:152-159`,
`Segmentable.java:132-142`, `ChartCanvas.java:980-990`. Este não. Cada
`TickLibrary` carrega um `Executors.newSingleThreadExecutor`
(`TickLibrary.java:93`) que fica sem `shutdown`.

**Consequência:** `Navigator.treeModel()` é reconstruída em cada mudança de
série e em cada troca de idioma (`MainWindow.java:833`, e ver A7b-20), e cada
reconstrução deixa duas `TickLibrary` por instrumento penduradas. `exported()`
não submete tarefa, então nenhuma thread nasce — o vazamento é do
`ThreadPoolExecutor` e da entrada de finalização, não de threads vivas. Por isso
é MÉDIA e não ALTA. Mas quebra a invariante que o resto do arquivo mantém, e
basta um `exported()` passar a submeter para virar vazamento de thread.

**Correção:** o mesmo `try/finally` dos outros cinco sítios.

**Tentei refutar:** li `TickLibrary.exported()` (`:275`) para ver se ele fecha
sozinho — não fecha, e `close()` só aparece nas chamadas externas. Verifiquei
se `Navigator` guarda a biblioteca em campo para fechar depois: não guarda.
Confirmei que `newSingleThreadExecutor` só cria a thread na primeira tarefa —
por isso a severidade não é ALTA.

---

### L1-6 — `ChartCanvas.renkoAllowed()` abre uma `TickLibrary` e nunca a fecha, no meio de um diálogo modal

**Onde:** `ui/chart/ChartCanvas.java:1154-1162`, chamado de `:890`

**Trecho:**
```java
    private boolean renkoAllowed() {
        return RenkoSource.allows(series,
                new br.com.jorge.reis.endeavourneo.domain.market.TickLibrary(
                        br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.ticksOf(
                                RenkoSource.rootOf(instrument)),
                        RenkoSource.rootOf(instrument),
                        br.com.jorge.reis.endeavourneo.domain.market.TickSource.METATRADER),
                ChartPreferences.syntheticTicks());
    }
```

**Problema:** mesma espécie de L1-5, e no mesmo arquivo que já sabe fazer
certo: `sourceForBricks` (`:980-990`), quinze linhas acima, fecha no `finally`.
Aqui não há sequer variável. Uma por tentativa de mudar o período para renko.

**Consequência:** vazamento lento, proporcional ao número de vezes que o leitor
abre a janela de período. Nenhum efeito visível.

**Correção:** extrair para variável local e fechar no `finally`, como
`sourceForBricks`.

**Tentei refutar:** conferi se `RenkoSource.allows` fecha a biblioteca que
recebe — não fecha; `sourceForBricks` e `rebuildFromTicks` fecham por fora, o
que prova que a responsabilidade é do chamador. **Irmão de A3-11**
(`ChartCanvas.java:1094`, a biblioteca do replay), mas em outro método e sem
nenhuma tentativa de fechamento — A3-11 descreve uma sobrescrita sem fechar,
esta nunca fecha em caminho nenhum.

---

### L1-7 — O retorno de um job roda com o monitor do `Handle` na mão quando `whenDone` é chamado depois de o job já ter terminado

**Onde:** `platform/JobService.java:114-121`, `:165` e `:382-388`

**Trecho:**
```java
        public synchronized Handle<T> whenDone(Consumer<T> action) {
            this.onDone = action;

            deliver();

            return this;
        }
```
```java
        private synchronized void deliver() {
            ...
            onEdt(() -> handler.accept(result));
        }
```
```java
    private static void onEdt(Runnable action) {
        if (SwingUtilities.isEventDispatchThread()) {
            action.run();
```

**Problema:** o encadeamento `submit(...).whenDone(...)` é feito na EDT
(`MainWindow.java:888`). Se o trabalho já terminou nesse instante — coisa
comum para um job curto — `deliver()` cai no ramo `action.run()` e o
`Consumer` do chamador executa **dentro do `synchronized (handle)`**. Esse
`Consumer` é código de interface: `console.write`, `status.say`, e nada impede
que um dia seja um `JOptionPane` modal, que abre um segundo laço de eventos
enquanto segura o monitor.

**Consequência:** hoje é latente. Com um diálogo modal no callback vira
travamento: a thread do pool fica bloqueada em `settle()`
(`JobService.java:150`, também `synchronized`) até o leitor fechar o diálogo, e
`close()` no *shutdown* fica atrás dela.

**Correção:** montar a entrega dentro do bloco sincronizado e executá-la fora
dele — capturar `handler` e `result` (o código já faz isso), sair do
`synchronized`, e só então chamar `onEdt`.

**Tentei refutar:** verifiquei se `onEdt` sempre enfileira: não — o ramo
`isEventDispatchThread()` executa em linha, e é justamente o ramo que o
encadeamento na EDT toma. Verifiquei se `deliver()` poderia não estar
sincronizado: está, o modificador é do próprio método. **Distinto de A7a-1
(Error escapando) e A7a-2 (cancelado não avisa)**, que são sobre o conteúdo da
entrega, não sobre o cadeado em que ela acontece.

---

## Achados BAIXA

### L1-8 — Cada `report()` e cada `say()` de um job posta um `invokeLater` que revalida o rodapé

**Onde:** `platform/JobService.java:320-338` e `:375-380`, com
`ui/shell/StatusBar.java:157-160`

**Trecho:**
```java
            @Override
            public void report(double value) {
                fraction = value;
                notifyListeners();
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
```java
    private void refresh() {
        boolean busy = jobs != null && jobs.isBusy();

        jobArea.setVisible(busy);
        revalidate();
```

**Problema:** o job de exemplo (`MainWindow.java:869-887`) faz `say` + `report`
a cada volta: 200 `invokeLater` em quatro segundos, cada um com um
`revalidate()` de contêiner. Um job real que reporte por barra numa série de um
milhão inundaria a fila da EDT.

**Correção:** engolir/coalescer — só notificar quando a fração mudar de forma
perceptível (por exemplo, 1%), ou marcar sujo e agendar um único `invokeLater`
pendente. Sem isso, a barra de progresso custa mais que o trabalho.

**Tentei refutar:** procurei coalescência em `notifyListeners` ou em
`StatusBar.refresh` — não há guarda nenhuma; `revalidate()` é chamado
incondicionalmente, antes até do `if (!busy) return`. **Relacionado a A7a-6**
(etapa e fração são um par único para o serviço inteiro), que é sobre o dado, e
não sobre a frequência.

### L1-9 — `TickLibrary.at()` é `synchronized` e está no caminho do quadro de replay

**Onde:** `domain/market/TickLibrary.java:136-140`, com o javadoc de `:79-81`

**Trecho:**
```java
     * <p>Access is synchronised on the map itself. It is touched by the loader
     * thread and by whoever is drawing, which are never the same thread.</p>
```
```java
    public TickSeries at(LocalDate day) {
        synchronized (resident) {
            return resident.get(day);
        }
    }
```

**Problema:** a regra da casa é "nada `synchronized` no caminho de pintura", e o
comentário admite que quem desenha entra aqui — `at()` é chamado por
`RecordedTicks.pathFor` durante `live.advanceMarketTime()`, no tique do `Timer`
da EDT, e por `ChartCanvas.extendBricks()`.

**Correção:** trocar o `LinkedHashMap` sincronizado por um
`ConcurrentHashMap` mais um campo separado para a política de despejo, ou por
um `Map` imutável trocado por referência volátil.

**Tentei refutar:** medi o tamanho da seção crítica antes de reportar. O
`source.read()` — a parte cara — acontece **fora** do cadeado
(`TickLibrary.java:216`, o `keep` recebe o resultado já lido), e `keep()`
(`:245-268`) segura o cadeado apenas para um `put` e uma varredura de no
máximo `RESIDENT = 3` entradas. Na prática a EDT nunca espera de forma
mensurável. Por isso é BAIXA e não MÉDIA: é violação de regra, não defeito
medido.

### L1-10 — O javadoc de `ReplayWindow` diz que a sessão sobrevive ao fechamento; o ouvinte logo abaixo a mata

**Onde:** `ui/replay/ReplayWindow.java:34` contra `:60-66`

**Trecho:**
```java
 * <p>It is hidden rather than disposed when closed, so the session survives:
```
```java
            public void windowClosing(java.awt.event.WindowEvent e) {
                panel.release();
            }
```

**Problema:** `HIDE_ON_CLOSE` de fato não descarta a janela, mas o
`windowClosing` chama `release()`, que para a sessão e devolve os dados a cada
gráfico. O que sobrevive é o painel, não a sessão. O comentário de `:57-59`
descreve o comportamento certo; o de `:34` descreve o contrário.

**Correção:** apagar a oração "so the session survives" de `:34`.

**Tentei refutar:** li `ReplayPanel.release()` (`:280-288`) para ver se ele
preserva algo da sessão: `session.stop()` e `session = null`. Não sobrevive
nada.

---

## Listeners: o balanço

Registros de **vida longa** — aqueles em que o objeto que guarda a referência
vive mais que o que a entrega. Os 84 `addXListener` restantes são todos de um
componente sobre um widget que ele mesmo criou e que morre junto com ele
(diálogos, itens de menu, botões); não estão na tabela porque não podem
sobreviver ao ouvinte.

| registro (fonte de vida longa) | entra em | sai em | veredito |
| --- | --- | --- | --- |
| `RulerMode.LISTENERS` (estático) | `ChartCanvas.java:1511` (`addNotify`) | `ChartCanvas.java:1518` (`removeNotify`) | **par completo** |
| `ChartPreferences.LISTENERS` (estático) | `ChartCanvas.java:1512` (`addNotify`) | `ChartCanvas.java:1519` (`removeNotify`) | **par completo** |
| `JobService.listeners` | `StatusBar.java:153` (`bind`) | **não existe API de saída** | **A7a-7** |
| `ReplaySession.watchers` | `ReplayDrop.java:90` | `ChartHolder.detachReplay`/`close` → `ReplayDrop.java:87` | **par completo** |
| `ReplaySession.watchers` | `ReplayPanel.java:613` (`done()` do worker) | `ReplayPanel.java:282` e `:559` | par completo, **mas nenhum roda no `relaunch` — L1-1** |
| `ReplaySession.endings` | `ReplayDrop.java:91` (`whenEnded`) | **não existe API de saída** | **A6-10** |
| `TickLibrary.whenLoaded` (fatia única) | `ReplaySession.java:265` | implícito: a biblioteca é privada da sessão e morre em `stop()` | par completo, **mas `stop()` não roda no `relaunch` — L1-1** |
| `DatePicker.onChange` | `ReplayPanel.java:204-205`, `SegmentDialog.java:170-172` | — | campo e ouvinte têm a mesma vida; nada a remover |
| `javax.swing.Timer` (`ReplaySession.java:275`) | `start()` em `:606` | `stop()` em `:598`, `:613`, `:692`, `:712` | quatro saídas, **nenhuma alcançada no `relaunch` — L1-1** |
| `JMenu` da janela (`MainWindow.java:742`) | `:760`, um `ActionListener` por gráfico, a cada abertura | `menu.removeAll()` em `:746` | **par completo** |
| `LayoutBar` abas (`:273`, `:334-335`) | por aba | as abas são recriadas, não reaproveitadas | **par completo** |

`removeXListener` aparece **zero** vezes em toda a base. Verifiquei uma a uma
que isso é correto para os 84 casos de widget próprio: todos os alvos são
componentes criados no mesmo método e descartados junto com o painel ou
diálogo.

---

## Já visto pelas áreas

Encontrei estes pontos de forma independente. Estão no índice; não são meus.

| ponto | id |
| --- | --- |
| `JobService.onChange` sem desinscrição; troca de idioma vaza a janela inteira, e `notifyListeners` chama `refresh()` em `StatusBar` descartada | **A7a-7** |
| Depois da troca de idioma, `System.out`/`System.err` seguem apontando para o `Console` da janela destruída (`captureStandardOutput` só é chamado em `Launcher.java:99`) | **A7b-15** |
| `ReplayDrop`/`whenEnded`: gráfico fechado nunca sai da lista de "avisar quando acabar", e `ChartHolder.detachReplay()` roda num suporte já fechado (a guarda `beforeReplay == null` não dispara, porque `close()` chama o `Runnable`, não o método) | **A6-10** |
| `ReplayFeed.KNOWN` nunca é esvaziado em produção | **A6-11** |
| `Error` escapa do `try` do pool e o job é entregue como SUCESSO com `null` | **A7a-1** |
| `isCancelled()` volta a mentir `false` porque o `finally` remove da lista `cancelled` | **A7a-2** |
| `stage`/`fraction` são um par único do serviço inteiro | **A7a-6** |
| `platform/Messages` é estático não-volátil trocado em execução e lido de threads de job — exatamente o que a corrida entre `Language.install()` (EDT, `Launcher.java:93`) e `ReplayFeed.warm()` (thread de job, `Launcher.java:88`) produz | **A7a-18** |
| `TickLibrary`: mapa dito "em ordem de uso" mas em ordem de inserção; `exported()` engole `IOException` | **A1-6**, **A1-9** |
| A biblioteca de ticks do replay em `ChartCanvas` vaza (sobrescrita sem fechar) | **A3-11** |
| A guarda do `done()` do worker de renko só olha o período — trocar a série deixa uma construção velha vencer. Confirmei o irmão: `stopGrowing()` fecha a biblioteca, e o `done()` em voo reatribui `growingFrom = library` depois, ressuscitando um renko crescente com um replay já encerrado. Mesmo defeito, mesma linha | **A3-4** |
| Varredura pesada dentro de manipulador de evento / na EDT (cinco sítios) | **A3-2**, **A4-2**, **A5-4**, **A6-5**, **A7b-3**, **A6-12**, **A7a-12**, **A7b-20** |
| `SeriesCatalog.open()` é verifica-depois-age entre duas threads | **A7a-13** |
| `MainWindow.java:200` deixa um `restoreCharts` na fila para uma janela já descartada | **A7b-22** |
| `LinePen`: `setBorder` dentro de `paintComponent` | **A5-8** |

---

## O que está LIMPO

Conferido, e certo. Cada item diz **como** foi conferido.

- **`invokeAndWait` não existe.** `grep -rn "invokeAndWait" src/main` → zero
  ocorrências. O risco de travar a EDT chamando-a de si mesma não existe nesta
  base.

- **`ChartCanvas` desfaz o que liga.** `addNotify` (`:1508-1513`) registra em
  `RulerMode` e `ChartPreferences`; `removeNotify` (`:1517-1521`) chama
  `forget` nos dois, com os `Runnable` guardados em campo justamente para isso
  (`:379` e `:382`). É o único par add/remove completo da base sobre objeto
  estático, e está correto nos dois sentidos: `JFrame.dispose()` e a remoção do
  contêiner disparam `removeNotify` em toda a árvore.

- **`RulerMode` e `ChartPreferences` usam `CopyOnWriteArrayList`** (`:51` e
  `:54`) para as listas de ouvintes, então iterar durante uma remoção não
  lança. Os campos estáticos (`on`, `periodLine`, `verticalGrid`,
  `horizontalGrid`, `syntheticTicks`, `hollowCandles`) não são `volatile`, e eu
  fui atrás de um leitor fora da EDT: os quinze sítios de leitura são
  `paintComponent` de `ChartCanvas`, `CandleStyle.paint`, as páginas de
  preferências e a *lambda* de `ReplaySession.java:255`. Essa última é avaliada
  em `RecordedTicks.pathFor`, chamada de `live.advanceMarketTime()`, que só
  roda no tique do `Timer` — EDT. Nenhum leitor de fundo. **Limpo.**

- **`Renko` é imutável** (`domain/market/Renko.java:98-104`: `brick`,
  `reversal`, `wicks`, `forming`, todos `private final`, e `withForming`/
  `withWicks` devolvem instância nova). Por isso o mesmo objeto `period` ser
  lido pela EDT em `seriesGrew()` e pelo `doInBackground` do worker de renko
  (`ChartCanvas.java:1057-1074`) não é compartilhamento mutável.

- **`TickRenko` é fortemente mutável** (`:59-92`) mas tem um dono por vez: é
  construído inteiro dentro do `doInBackground` e só publicado no `done()`, que
  roda na EDT — o `SwingWorker` fornece a relação *happens-before*. Depois disso
  só a EDT o toca (`extendBricks`). **Limpo.**

- **Nenhum `doInBackground` toca componente.** Os dois únicos —
  `ChartCanvas.java:1060` e `ReplayPanel.java:591` — foram lidos por inteiro. O
  primeiro constrói `TickRenko` sobre `TickLibrary`, ambos de domínio. O
  segundo constrói `ReplaySession`, que **cria** um `javax.swing.Timer`
  (`ReplaySession.java:275`) fora da EDT — mas `Timer` é documentadamente
  seguro para thread, o `start()` só acontece depois na EDT, e nenhum
  `JComponent` é tocado. **Limpo.**

- **`JobService.onEdt`** (`:382-388`) e **`Console.onEdt`** (`:124-130`) e
  **`StatusBar.onEdt`** (`:225-231`) marshalizam sozinhos, sem confiar no
  chamador, exatamente como a regra pede. `Console.write`, `Console.clear` e
  `StatusBar.say` são públicos e seguros de qualquer thread. **Limpo.**

- **`JobService`: `stage` e `fraction` são `volatile`** (`:245`, `:247`),
  escritos das threads do pool e lidos pela EDT em
  `StatusBar.refresh`. As quatro listas (`running`, `cancelled`, `listeners`,
  `unclaimed`) são `CopyOnWriteArrayList`. A thread é nomeada e daemon
  (`:252-261`), o `Throwable` é capturado (`:283-292`) e a falha não reclamada é
  guardada em vez de descartada. A disciplina de threads da classe está certa;
  os defeitos que restam são de conteúdo (A7a-1, A7a-2, A7a-6) e de cadeado
  (L1-7), não de visibilidade.

- **`SeriesCatalog.LOADED` é `ConcurrentHashMap<String, SoftReference<...>>`**
  (`:99-100`) e `folder` é `volatile` (`:113`). O `warm()` de fundo e a EDT
  batem no mesmo mapa e ele não corrompe. O defeito que sobra é a leitura dupla
  (A7a-13), não a estrutura.

- **`TickLibrary.loading` é `ConcurrentHashMap.newKeySet()`** (`:85`) e
  `whenLoaded`/`focus` são `volatile` (`:101`, `:103`). A leitura de disco
  acontece fora do `synchronized`. O `onLoaded` de fatia única poderia
  sobrescrever silenciosamente o observador de outra sessão — fui verificar e
  **não pode**: cada `ReplaySession` constrói a sua própria `TickLibrary`
  (`ReplaySession.java:209`), nunca compartilhada. **Refutado.**

- **`ReplayFeed.KNOWN`, `available()` e `warm()` não tocam Swing.**
  `available()` monta `List<ReplayFeed>` a partir de `SeriesCatalog` e
  `TickLibrary`; nenhum `JComponent` no caminho, apesar de a classe morar em
  `ui/replay`. Rodar isso na thread de job é legítimo.

- **O menu Janela não acumula ouvintes.** `MainWindow.java:742-763`: o
  `menuSelected` faz `menu.removeAll()` (`:746`) antes de recriar os itens, e
  cada `JMenuItem` novo leva o seu `ActionListener` novo. Este era o candidato
  mais provável a "listener registrado e nunca removido" em componente
  reaproveitado, e está correto.

- **`ChartHolder` fecha pelo mesmo caminho nos dois modos.** O
  `InternalFrameAdapter` (`:459-468`) e o `WindowAdapter` (`:514-520`) chamam
  ambos `close()`, com o comentário dizendo por quê. `detachFromDocked` e
  `detachFromFloating` chamam `dispose()` (`:602`, `:616`), então
  `removeNotify` corre em toda a árvore e os `forget` de `ChartCanvas`
  disparam. É exatamente o desenho que falta em `ReplayWindow` (L1-1).

- **Só existe um `javax.swing.Timer` na base** (`ReplaySession.java:275`), com
  `setCoalesce(true)`, e ele tem quatro `stop()` cobrindo pausa, fim de
  intervalo, fim do dia (`tick()`, `:712`) e encerramento. O desenho está certo;
  o defeito é um chamador que não alcança nenhum dos quatro (L1-1).

- **`Appearance.install`** roda na thread `main` em `Launcher.java:76`, antes de
  qualquer componente existir — o caso aceito para `UIManager.setLookAndFeel`.
  Nas trocas posteriores (`AppearancePage`, via `SettingsDialog`) roda na EDT.
  **Limpo.**

- **`repaint()` e `revalidate()` de fora da EDT** não são problema: as duas são
  documentadamente seguras para thread. Verifiquei que nenhum `setText`,
  `setVisible`, `setEnabled` ou `add`/`remove` de componente aparece em
  caminho de fundo.
