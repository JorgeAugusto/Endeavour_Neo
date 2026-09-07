# L3 — lente transversal: persistência e recursos

Auditoria II, 07/09/2026. Uma pergunta em todo o código, não uma área.

**A pergunta.** O que abre e não fecha; o que grava e não sabe se conseguiu; o
que guarda e nunca solta; a chave escrita e nunca lida, ou lida e nunca escrita;
o `catch` que engole e devolve o valor de "está tudo bem".

## Como foi feito

Partiu de `grep` e leu **±40 linhas em volta de cada ocorrência**, nunca o
arquivo inteiro. Ocorrências examinadas, por padrão:

| padrão | ocorrências | onde |
|---|---|---|
| `FileChannel.open` / `Files.newBufferedWriter` / `Files.createDirectories` | 18 | `MarketFile`, `TickFile`, `TapeFile`, `Settings` |
| `Files.walk` / `Files.list` / `newDirectoryStream` | 2 | `TickLibrary:317`, `SeriesCatalog:530` |
| `new Thread` / `Executors.` / `Timer(` | 6 | `TickLibrary`, `JobService`, `Launcher`, `ReplaySession` |
| `static final Map` / `static Map` / `Soft`-`WeakReference` / `LinkedHashMap` | 24 | `Sessions`, `SeriesCatalog`, `TickLibrary`, `ReplayFeed`, `Segmentation`, `Settings` |
| `addListener` / `listen(` / `onChange` / `whenForgotten` / `register` | 21 | `ChartPreferences`, `RulerMode`, `ChartCanvas`, `JobService`, `SeriesCatalog`, `ReplayFeed`, `TickLibrary`, `Appearance` |
| `.put(` / `.get(` / `.putInt(` / `.getBoolean(` sobre `Settings` | 71 | 14 arquivos |
| `Double.parseDouble` / `Integer.parseInt` / `Boolean.parseBoolean` | 20 | 12 arquivos |
| `catch (IOException|Exception)` seguido de `return` de sucesso | 8 | 4 arquivos |
| `.close()` / `AutoCloseable` / `try (` | 23 | 9 arquivos |

**Total examinado: 193 ocorrências.** Lidos por inteiro apenas
`platform/Segmentation.java` (277 linhas) e os trechos de decisão de
`Settings`, `SeriesCatalog.open*`, `TickLibrary`, `TickFile.Writer`,
`TapeFile.Writer` e `ChartCanvas.rebuildFromTicks`.

Confrontado com os 215 achados de `00-achados.md` antes de escrever. O que as
dez áreas já viram **não está aqui**, e o que é claramente de concorrência ou de
fuso ficou para as lentes L1 e L2.

**Nota de estado.** A árvore de trabalho já traz as correções dos commits
`e541da7` e `30e30f7`, que fecharam parte das ALTA da segunda passada. Alguns
achados do índice — B7a-2, B7a-3, B7a-4 — descrevem um código que já não existe.
Tudo abaixo foi lido na árvore de 07/09/2026, com `git status` conferido.

---

## ALTA

### L3-1. Uma conversão recusada deixa no disco um pregão curto que se parece com um inteiro — e destruiu o que estava lá

`domain/market/TapeFile.java:293-303` (o mesmo em `domain/market/TickFile.java:219-232`)

```java
        public Writer(Path file, LocalDate date) throws IOException {
            this.file = file;
            this.date = date;

            Files.createDirectories(file.toAbsolutePath().getParent());

            this.channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

            writeHeader(0);
        }
```

`domain/market/ProfitTrades.java:140-147` (o mesmo em
`domain/market/MetaTraderTicks.java:138-145`)

```java
        } finally {
            // Only reached when something threw: the normal path closed it
            // above. Without this a failed conversion leaves a file open and a
            // header still claiming zero trades.
            if (writer != null) {
                writer.close();
            }
        }
```

**Problema.** São dois defeitos que se somam, e o comentário do `finally`
descreve o segundo ao contrário.

*Primeiro:* o construtor abre com `TRUNCATE_EXISTING` e escreve o cabeçalho
**antes de a primeira linha do export ter sido lida**. O arquivo do pregão que já
estava no disco — completo, conferido, talvez a única cópia — é apagado no
instante em que o conversor decide que aquele dia começou. Se a linha seguinte do
CSV for a que faz o conversor recusar tudo, o dia bom já não existe.

*Segundo:* o `finally` não deixa "um cabeçalho ainda dizendo zero". `close()`
chama `flush()`, `writeBrokers()` e depois `writeHeader(count)` com a **contagem
real do que foi escrito até ali** (`TapeFile.java:366-378`). O arquivo fica
internamente **coerente**: `TapeFile.read` confere `channel.size()` contra
`HEADER + count × RECORD` e passa; `TickFile.sessionOf` devolve a data; a
biblioteca lista o dia como exportado. Nada distingue esse arquivo de um pregão
inteiro — nem o programa, nem o leitor.

Os três testes que exercem a recusa param no `assertThrows` e não olham para o
disco:

`src/test/.../TapeFileTest.java:226-227`

```java
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> ProfitTrades.convert(csv, folder.resolve("ticks"), "win", null));
```

E o comentário de `anOversizeTradeIsRefused` (linhas 235-239) diz que "um campo
que satura em silêncio é exatamente a perda que este formato existe para evitar
— por isso ele avisa". Ele avisa na exceção e cala no arquivo.

**Consequência.** Perda de dado, das duas formas que a lente procura. Um export
com uma linha ruim no meio: (a) apaga o pregão que já existia, e (b) põe no lugar
um pregão que termina onde o erro estava, sem marca nenhuma. Como o formato de
ticks é a fonte do renko de ticks e do replay, um dia curto vira um pregão que
"acabou às 11h" — e a regra de casa diz que renko e candle têm de concordar sobre
o que já aconteceu.

**Correção.** Escrever para `<arquivo>.parcial` e só então `Files.move(...,
ATOMIC_MOVE)` sobre o definitivo, no `close()` bem-sucedido; no caminho de
exceção, apagar o parcial. É a mesma disciplina que o próprio `close()` já usa
para a contagem — adiar o compromisso até saber a resposta — levada um nível
acima. Alternativa mais barata, se a atômica incomodar: no `finally`, apagar o
arquivo em vez de o fechar com a contagem parcial.

**Tentei refutar assim.** (a) Procurei um `Files.delete`, um `.tmp`, um
`.partial` ou um `Files.move` em qualquer lugar de `src/main`: o único
`Files.delete` do repositório não existe — `grep -rn "Files.delete\|Files.move"
src/main` não devolve nada. (b) Procurei uma guarda no `TickSource`/`TickLibrary`
que recusasse um pregão curto: `TickLibrary.has` só compara a data do cabeçalho
(`TickLibrary.java:135-137`), e `TickFile.read` só compara o tamanho contra a
contagem declarada — que o `close()` acabou de acertar. (c) Procurei se o
`Session` devolvido avisa: `finish()` só é chamado no caminho normal
(`MetaTraderTicks.java:148-166`), então o pregão parcial **não aparece na lista
devolvida nem no `progress`** — o chamador não fica sabendo que ele existe. (d)
Tentei derrubar por não haver chamador de produção: `convert` só é chamado por
`TapeFileTest` e `TickFileTest` hoje. **Isso baixa a probabilidade, não a
gravidade** — é a porta por onde os dados entram, e o `endeavour` de referência
já a usa por linha de comando. Não caiu.

---

## MÉDIA

### L3-2. `data.zone` é lida no arranque e escrita por ninguém: a guarda contra o relógio da máquina só arma à mão

`Launcher.java:84-97`

```java
        String market = br.com.jorge.reis.endeavourneo.platform.Settings.settings()
                .get("data.zone", null);

        if (market != null && !market.isBlank()) {
            try {
                br.com.jorge.reis.endeavourneo.domain.market.Timeframe.useZone(
                        java.time.ZoneId.of(market.trim()));
```

**Problema.** `grep -rn "data.zone" src/main` devolve **duas** ocorrências: esta
leitura e a mensagem de erro três linhas abaixo. Não há `put("data.zone", ...)`
em lugar nenhum, não há página no `SettingsDialog` que a escreva, e não há
constante de omissão no código. O `fallback` é `null`, e `null` significa "siga a
máquina".

O comentário imediatamente acima diz por que a linha existe:

```java
        // BEFORE anything folds a bar. Every fold that is not handed a zone
        // lands on Timeframe.defaultZone, and until this line that was always
        // the machine's -- so a machine outside São Paulo cut the day at the
        // wrong hour, in silence, because a daily bar looks like a daily bar
        // either way.
```

A correção foi construída e não foi armada: numa instalação nova o arquivo
`settings.properties` não tem a chave, e o comportamento é exatamente o que o
comentário descreve como o defeito.

**Consequência.** Dívida com data marcada. Hoje é benigno — a máquina do leitor
está em São Paulo, e a máquina certa dá a resposta certa por acidente. Passa a
ser resultado errado no primeiro dos três casos previsíveis: o projeto ir para
open source (é o plano declarado nas convenções), a máquina mudar de fuso, ou o
relógio do sistema ser posto em UTC. E é a categoria de defeito que a casa já
mediu e nomeou — o javadoc de `Sessions.java:63` cita `data.zone` pelo nome.

**Correção.** Ou uma constante de omissão no código (`America/Sao_Paulo`, com o
comentário dizendo que é o mercado que este programa lê, não o lugar onde a
máquina está), ou uma linha na página de preferências. A primeira é mais barata e
fecha o caso; a segunda é a que o leitor esperaria de um terminal.

**Tentei refutar assim.** (a) Procurei a escrita em `src/test` também, caso a
suíte a criasse e eu estivesse a ver o arquivo do leitor já povoado: só
`SessionsTest.java:296` e `MarketZoneTest.java:48` a mencionam, ambos em
comentário. (b) Procurei um `useZone` fora do `Launcher`: não há outro chamador
de produção. (c) Perguntei se isto é da lente L2 (tempo/futuro): o defeito de
fuso em si é B1-1 e é dela; **o que reporto é a chave lida e nunca escrita**, que
é a pergunta desta lente. Não caiu.

---

### L3-3. Apagar antes de escrever deixa o arquivo, por um instante, sem os segmentos — e sem a lista de gráficos

`platform/Segmentation.java:133-147`

```java
    public static void set(String series, List<Segment> segments) {
        Settings workspace = store();

        workspace.removeStartingWith(PREFIX + series + ".");

        for (int at = 0; at < segments.size(); at++) {
            Segment each = segments.get(at);

            workspace.put(keyOf(series, at, "name"), each.name());
            workspace.put(keyOf(series, at, "from"), each.from().toString());
```

`ui/shell/MainWindow.java:284-292`

```java
        workspace.removeStartingWith("chart.open.");

        int at = 0;

        for (java.util.Map.Entry<String, ChartHolder> each : charts.entrySet()) {
            workspace.put("chart.open." + at + ".series", seriesOf(each.getKey()));
            workspace.put("chart.open." + at + ".period", each.getValue().canvas().periodCode());
```

**Problema.** `removeStartingWith` grava o arquivo inteiro
(`Settings.java:433-441`), e cada `put` seguinte grava o arquivo inteiro outra
vez (`Settings.java:378-386`). A sequência no disco é: *primeiro sem nada*,
depois com um campo, depois com dois. Três segmentos custam **dez** reescritas
completas, e nove delas são estados que o leitor nunca pediu.

Se qualquer uma das gravações do meio falhar — disco cheio, home em rede que
piscou, o próprio processo a ser fechado — o arquivo fica no estado em que a
falha o apanhou. A falha é engolida (`Settings.java:326-331`), então nada é dito.

Isto não é o B4-7 nem o B7a-10. B4-7 é sobre `ChartLayouts.save` e sobre um
javadoc que promete atomicidade; B7a-10 é sobre o custo do `put` na EDT. O que
está aqui é a **ordem**: apagar primeiro põe o arquivo, por um intervalo real,
num estado pior do que qualquer um dos dois que se queria — e o que se apaga é a
fronteira busca/teste, que é a coisa mais cara de reconstruir neste repositório
inteiro.

**Consequência.** Perda da segmentação de uma série, ou da lista de gráficos
abertos, numa janela pequena mas real. A segmentação é o registo de qual pedaço
da série é de busca e qual é de teste; perdê-la em silêncio é perder a única
defesa contra olhar o teste sem notar — que é o que a própria classe diz existir
para evitar (`Segmentation.java:206-210`).

**Correção.** Um `Settings.replaceStartingWith(prefix, Map<String,String>)`, que
faça `removeIf` e `putAll` no mapa em memória e grave **uma vez** no fim. Os dois
chamadores passam a fazer uma escrita em vez de dez, e o estado intermédio deixa
de existir. Resolve de passagem metade do custo apontado por B7a-10 e por B4-7.

**Tentei refutar assim.** (a) Procurei se `removeStartingWith` adia a gravação:
`Settings.java:437-440` — `if (changed) { save(); }`, grava. (b) Procurei se há
um `beginBatch`/`endBatch` em `Settings`: não há; a classe só tem `put`, `get`,
`remove`, `keysStartingWith` e `removeStartingWith`, e todas as que escrevem
chamam `save()`. (c) Procurei se `rememberCharts` é chamado dentro de um bloco
protegido: é chamado de `leave()` via `storeLayout` e do `whenChanged` de cada
gráfico (`MainWindow.java:266`), sem proteção. Não caiu.

---

### L3-4. O aquecimento do arranque prende TODA série inteira num cache estático — e a janela de 100.000 barras então lê uma segunda cópia

`Launcher.java:122-131`

```java
            for (String name : br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.names()) {
                try {
                    br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(
                            br.com.jorge.reis.endeavourneo.platform.SeriesCatalog.open(name)
                                    .orElse(null));
```

`platform/SeriesCatalog.java:636-654`

```java
    public static Optional<PriceSeries> open(String name) throws IOException {
        SoftReference<PriceSeries> held = LOADED.get(name);
        ...
        PriceSeries series = MarketFile.read(file);

        LOADED.put(name, new SoftReference<>(series));
```

**Problema.** `SeriesCatalog.open(String)` — sem contagem de barras — lê o
arquivo **inteiro** e guarda-o em `LOADED`. O aquecimento do arranque faz isso
para toda série do catálogo, e `ReplayFeed.warm()` (`ReplayFeed.java:205-216` →
`sessions()` → `SeriesCatalog.open(series)`, linha 164) faz o mesmo antes dele.
O valor devolvido é descartado nos dois; o que fica é a entrada no mapa estático.

O javadoc de `open(String, int)` diz, na mesma classe, por que a janela existe:

```java
     * <p><b>A window, anchored to the right.</b> What a reader opens a chart to
     * see is the recent end of it; six years of one-minute bars is 39 MB read to
     * draw a screen that shows a month.
```

Os 39 MB que a janela existe para não ler já estão lidos quando a primeira janela
abre. E pior: para uma série **maior** que a janela, o atalho de cache de
`open(name, bars)` não pega (`whole.size() <= bars` é falso), então o gráfico lê
uma segunda cópia de 4,8 MB — ficam as duas em memória.

O `openUntil` já reconhece o facto por escrito (`SeriesCatalog.java:730`: *"The
launcher fills that cache for every series at startup, so the branch was live"*),
mas como uma armadilha a evitar, não como um custo a pagar. Ninguém somou.

**Consequência.** No arranque, `N × tamanho da série` de heap, para responder uma
pergunta — quais dias esta série tem — que precisa apenas dos carimbos de tempo.
Com a base de referência (WIN 1m, seis anos, 824.881 barras) é 39 MB por série; o
catálogo tem mais de uma. São `SoftReference`, então o coletor recupera antes de
estourar: por isso é MÉDIA e não ALTA. Mas até recuperar, o programa segura
memória para nada, e cada recuperação é uma releitura de 39 MB no próximo `open`.

**Correção.** `Sessions.of` só precisa de `timeAt(i)`. Ou um
`MarketFile.sessionsIn(Path)` que percorra os carimbos direto do canal e não
construa `ArraySeries` nenhuma, ou — mais barato de escrever — o aquecimento usar
uma variante de `open` que **não** povoe `LOADED`.

**Tentei refutar assim.** (a) Perguntei se as duas passagens são a mesma:
`Launcher.java:113` chama `ReplayFeed.warm()` e depois o laço próprio; o
comentário nas linhas 114-121 diz que a repetição é deliberada e que "hoje são
todos acertos de cache" — o que confirma que a primeira passagem já leu tudo.
(b) Perguntei se `SoftReference` torna isto inofensivo: torna-o não-vazamento,
não torna-o barato — o arranque continua a ler todos os arquivos inteiros do
disco. (c) Procurei se `MainWindow` usa a versão com janela: usa,
`MainWindow.java:408` e `522` (`open(name, wanted)` / `openUntil`), o que prova
que a janela é o caminho pretendido e que o aquecimento a contorna. Não caiu.

---

### L3-5. As chaves por gráfico nunca são podadas: os dois `.properties` só crescem, e cada `put` reescreve o que cresceu

`ui/chart/ChartHolder.java:861-864`

```java
        PREFS.putInt(key + ".x", floating.getX());
        PREFS.putInt(key + ".y", floating.getY());
        PREFS.putInt(key + ".width", floating.getWidth());
        PREFS.putInt(key + ".height", floating.getHeight());
```

**Problema.** Cada gráfico deixa, com a sua chave, um punhado de entradas
permanentes espalhadas pelos dois arquivos: `.x`, `.y`, `.width`, `.height`,
`.floating`, `.maximised` (`ChartHolder`), `chart.<key>.visibleBars`,
`.rightMargin`, `.stretch`, `.priceOffset`, `.period`, `.style`, `.fromEnd`
(`ChartCanvas.storeView`, linhas 1910-1924), `<key>.collapsed`
(`OverlayLegend:183`), `<key>.folded` (`CollapsiblePane:133`) e
`selected.<key>` (`ChartLayouts:118`) — dezasseis chaves por gráfico.

`grep -rn "removeStartingWith\|PREFS.remove" src/main` devolve **quatro**
resultados: `Segmentation:136`, `ChartLayouts:104-106` (só o rabo da lista de
layouts) e `MainWindow:284` (só o prefixo `chart.open.`). Nada apaga as
dezasseis. Um gráfico fechado, uma série renomeada, um segmento apagado — as
chaves ficam para sempre.

Isso é normalmente inofensivo, e aqui não é, por causa de B7a-10: **cada `put`
reescreve o arquivo inteiro, na EDT**. O custo de cada gravação é proporcional ao
que se acumulou, e o que se acumulou nunca diminui. Arrastar um gráfico flutuante
dispara quatro gravações completas de um arquivo que cresce com o histórico de
uso do leitor.

**Consequência.** Dívida que se paga em milissegundos na EDT e que aumenta
sozinha. Também torna o arquivo ilegível para a pessoa — e a razão declarada de
usar texto sob a home em vez do registo do Windows é justamente que um humano
possa abri-lo, ver e apagar (`Settings.java:58-62`).

**Correção.** Em `MainWindow.rememberCharts`, depois de escrever
`chart.open.*`, apagar as chaves de todo `key` que não está na lista — um
`removeStartingWith` por chave órfã, ou o `replaceStartingWith` proposto em L3-3
aplicado ao conjunto todo. Requer que os prefixos por gráfico partilhem um
prefixo comum, o que hoje não acontece: `chart.<key>.` do canvas contra `<key>.`
puro do holder.

**Tentei refutar assim.** (a) Procurei uma poda no arranque ou na saída:
`restoreLayout`/`storeLayout` (`MainWindow.java:1246-1266`) só leem e escrevem as
quatro chaves da janela. (b) Procurei se `Settings` tem limite de tamanho ou de
número de chaves: não tem. (c) Perguntei se B7b-16 ("os gráficos voltam fora de
ordem a partir do décimo") já cobre isto: não — aquele é sobre a ordenação
alfabética de `chart.open.10` contra `chart.open.2`, e é sobre as chaves que
**são** podadas. Não caiu.

---

## BAIXA

### L3-6. Um export corrompido vira "não há export", em silêncio, e o gráfico troca os ticks reais pelo passeio inventado

`domain/market/TickLibrary.java:247-261`

```java
        loader.execute(() -> {
            try {
                if (has(day)) {
                    keep(day, source.read(fileFor(day)));
                    whenLoaded.run();
                }
            } catch (IOException e) {
                // A session that will not read is not a reason to stop the
                // replay: the path falls back to synthetic ticks for that day,
                // which is exactly what happens for every day with no export.
                whenLoaded.run();
            } finally {
                loading.remove(day);
            }
        });
```

**Problema.** `has(day)` respondeu **sim** — o cabeçalho está lá e a data bate —
e a leitura falhou logo a seguir. Isso não é "não há export": é "há e está
partido", que é uma terceira coisa, e as duas primeiras já se distinguem sem esta
linha. O `catch` colapsa as três em uma, e o `whenLoaded.run()` a seguir diz ao
observador exatamente o que diria se a sessão tivesse chegado inteira.

O `TickSeries` nunca entra em `resident`, `at(day)` continua a devolver `null`, e
o caminho do renko cai para os ticks sintéticos. O que o leitor vê é um renko
construído com o passeio de `SyntheticTicks` — parâmetros medidos em nove
pregões — apresentado com a mesma aparência de um renko de tape.

**Consequência.** Um número na tela que não veio do mercado, sem uma palavra. É
menos grave que B1-2 (`FoldedTicks.day`, que devolve série vazia) porque aqui o
dia **desaparece do renko de ticks** em vez de aparecer vazio, e porque o mesmo
efeito já é o comportamento correto para um dia sem export. Mas é a mesma família
e o mesmo silêncio, e é o par natural de L3-1: um pregão parcial escrito hoje é
um `IOException` daqui a um mês, e este é o `catch` que o vai comer.

**Correção.** O domínio não escreve na tela e não deve — mas pode dizer. Um
segundo aviso (`whenFailed`, ou um argumento no `whenLoaded`) que o `ReplaySession`
encaminhe para o console: o rodapé existe exatamente para isto, e uma linha no
log é o preço de saber que o gráfico mudou de fonte.

**Tentei refutar assim.** (a) Procurei se o chamador distingue: `ReplaySession`
regista o observador em `ReplaySession.java:301` com um único `Runnable` que só
faz `invokeLater` de um repaint — não recebe nem pode receber a distinção. (b)
Procurei se algo mais adiante nota que o dia não carregou:
`RenkoSource.allows(source, library, false)` pergunta se os ticks **existem** —
e existem, porque `has` olha o cabeçalho. (c) Perguntei se isto é o B1-2: não,
arquivo e método diferentes, e a consequência é a troca de fonte em vez da série
vazia. Fica em BAIXA por sobreposição de família. Não caiu.

---

### L3-7. Os dois níveis do estocástico são a única grandeza gravada sem faixa, e a classe irmã diz por escrito por que isso é errado

`ui/chart/study/stochastic/SlowStochastic.java:172-182`

```java
    public void setBuyLevel(double value) {
        buy = value;
    }
    ...
    public void setSellLevel(double value) {
        sell = value;
    }
```

Alimentados diretamente da linha guardada no layout, `SlowStochastic.java:407-408`:

```java
        setBuyLevel(readDouble(at(fields, 12), buy));
        setSellLevel(readDouble(at(fields, 13), sell));
```

E `readDouble` (linhas 455-459) só protege contra texto que não é número:

```java
            return text == null ? fallback : Double.parseDouble(text);
```

**Problema.** `NaN`, `Infinity`, `-3` e `1e300` atravessam. Todos os vizinhos
neste mesmo caminho de restauro têm faixa: `setWidth`, `setAverageWidth` e
`setLevelWidth` fazem `Math.max(0.5f, value)` (linhas 254-272); `ChartCanvas`
tem `readDouble(from, key, fallback, least, most)` com mínimo e máximo
(`ChartCanvas.java:1967-1975`); e `BollingerBands.clampDeviation` traz o javadoc
que descreve exatamente este caso:

```java
     * <p>Negative would put the upper band below the lower one and turn the
     * fill inside out; NaN would erase both bands with no message. Neither is
     * reachable from the dialog, and both are reachable from a hand-edited
     * layout file.</p>
```

A frase vale palavra por palavra para os níveis do estocástico, e é o único
lugar da restauração de indicadores onde ela não foi aplicada.

**Consequência.** Uma linha de nível que não desenha, ou que desenha fora do
painel, sem mensagem — e o leitor procura no lugar errado, porque o diálogo
continua a mostrar o número que digitou da última vez. Pequeno, e do tipo que
custa uma tarde quando acontece.

**Correção.** `buy = Double.isFinite(value) ? Math.max(0, Math.min(100, value))
: buy;` nos dois. A escala do estocástico é 0–100 por definição, então a faixa
não é escolha: é o domínio.

**Tentei refutar assim.** (a) Procurei uma limitação depois, no desenho:
`StudyPane` desenha o nível pela escala do estudo sem conferir finitude — e é o
mesmo painel que B5-6 já acusa de ignorar `isVisible()` nos níveis. (b) Procurei
se o diálogo limita: limita, e é por isso que isto é BAIXA — só um arquivo
editado à mão ou escrito por uma versão anterior chega aqui, que é precisamente a
condição que o javadoc do `BollingerBands` nomeia como real. (c) Confirmei que o
irmão `RelativeStrength` não tem níveis com o mesmo problema:
`RelativeStrength.java:268` só restaura largura, que é limitada. Não caiu.

---

## LIMPO

O que foi conferido e está certo, e **como**.

**Todo canal de arquivo é fechado por quem o abre.** As 18 aberturas de
`FileChannel`/`Writer` em `src/main`: quinze estão em `try (…)` de recurso
(`MarketFile:81,88,112,164,230,278`; `TickFile:101,112,133,148`;
`TapeFile:128`; `Settings:313`), e as três restantes são os campos `channel` dos
dois `Writer`, fechados em `close()` dentro de um `finally`
(`TickFile.java:280-285`, `TapeFile.java:372-378`) — o `finally` garante o fecho
mesmo quando a reescrita do cabeçalho falha. Os dois `Files.walk`
(`TickLibrary:317`, `SeriesCatalog:530`) estão em try-with-resources, que é o
erro clássico deste método e não foi cometido.

**O par ouvinte/esquecimento dos dois registos globais fecha.**
`ChartPreferences.LISTENERS` e `RulerMode.LISTENERS` são estáticos e só têm um
registante: `ChartCanvas.addNotify` (linhas 1789-1790) e `removeNotify` (1796-97).
Os dois `Runnable` são campos finais (`ChartCanvas.java:382` e `385`), não
lambdas construídas na hora — então o `remove` por igualdade encontra o que o
`add` pôs. Conferi que re-parentar entre acoplado e flutuante passa pelos dois, o
que o javadoc afirma e o código cumpre. `RulerModeTest:98-116` mede a contagem
antes e depois.

**`Sessions.ANSWERED` não vaza, e conferi a razão certa.** É
`WeakHashMap<PriceSeries, Answer>`, e a armadilha destes mapas é o valor segurar
a chave. `Answer` é `record Answer(ZoneId, int, NavigableSet<LocalDate>)`
(`Sessions.java:86`) — nenhum campo aponta para a série. Conferi também que
`ArraySeries` (`ArraySeries.java:32`) não redefine `equals`/`hashCode`, então a
chave é por identidade e uma série que cresce não muda de balde.

**O `SwingWorker` do renko de ticks entrega a posse da biblioteca em todas as
saídas.** `ChartCanvas.rebuildFromTicks` (linhas 1152-1276): guarda mudada →
`library.close()`; caminho sem replay → `library.close()` antes de usar o
resultado; `catch` → `library.close()`; caminho com replay → passa para
`growingFrom`, que `stopGrowing()` fecha. Tentei construir o vazamento por
corrida — fechar o gráfico enquanto um `doInBackground` está a correr, para que
`done()` guarde a biblioteca num canvas já morto. **Não fecha:**
`ChartHolder.close()` chama `detachReplay.run()` (linha 572) *antes* de
`canvas.releaseTicks()` (584), e desanexar o replay troca o `source` — que é
exatamente o que a guarda `askedOf != source` compara (o comentário das linhas
1174-1181 diz que foi acrescentada por causa disto). O caminho sem replay fecha
sozinho. Vazamento refutado.

**Os três formatos binários carregam versão e recusam a que não conhecem.**
`MarketFile.java:66/236/305`, `TickFile.java:74/302/336`,
`TapeFile.java:94/144/426` — um `int` de versão escrito no cabeçalho e conferido
na leitura. É o contraste que dá peso a B7a-8: os arquivos binários têm o que os
`.properties` não têm.

**`SeriesCatalog.forget()` tem chamador de produção.** O índice diz que não
(B7a-4), e na árvore de hoje tem: `MainWindow.java:1070`. O gancho
`whenForgotten` é registado uma vez por `ReplayFeed.followTheCatalog`
(`ReplayFeed.java:185-193`, protegido pela bandeira `following`) e leva
`ReplayFeed::forget`, que limpa `KNOWN`. Corrigido entre a corrida da área B7a e
esta.

**A restauração da vista do gráfico limita tudo o que lê.**
`ChartCanvas.restoreView` (1928-1958): `stretch` e `priceOffset` passam por
`readDouble(…, least, most)` e por `Double.isFinite`; `visibleBars` fica entre
`MINIMUM_VISIBLE_BARS` e o tamanho da série; `rightMargin` e `fromEnd` levam
`Math.max(0, …)`; `firstBar` passa por `clampFirstBar`. É o padrão que L3-7
mostra em falta no estocástico, e aqui está completo.

**`TickLibrary` tem teto e o teto funciona.** `keep` (linhas 271-291) descarta
enquanto `resident.size() > RESIDENT` (3), escolhendo o dia mais distante do foco
— não o menos usado, e o javadoc explica que a escolha é deliberada porque um
replay anda para a frente. O `close()` é `shutdownNow()` mais `forget()`, e a
`ExecutorService` usa thread daemon (linhas 103-109), então uma pré-leitura a
meio nunca impede a aplicação de fechar.

**A posição de uma janela flutuante é conferida contra os monitores que
existem.** `ChartHolder.restoredLocation` (817-828) só honra `x`/`y` guardados se
`onSomeScreen(x, y)` — que percorre os `GraphicsDevice` de verdade. É o caso
"valor sem faixa" mais provável de todos (segundo monitor desligado) e está
tratado, com o javadoc a dizer por quê.

**Nenhum ouvinte de Swing é registado em objeto de vida mais longa que o seu.**
As dez ocorrências de `addWindowListener`/`addChangeListener`/`addHierarchy…`
(`BollingerBandsDialog:161,163`, `ChartCanvas:556`, `ChartHolder:517`,
`LinePen:86`, `MovingAverageDialog:406`, `ReplayPanel:218`, `ReplayWindow:68`,
`SeriesWindow:172`, `MainWindow:202`) são todas `this.add…` ou sobre um
componente que o próprio construtor criou e possui. Nenhuma se pendura num
registo estático nem num componente de outro dono.

**O `Segmentation.of` não entra em laço infinito**, que era a minha suspeita ao
ler o `continue` dentro do `catch` de um `for (int at = 0; ; at++)`: o `continue`
executa a atualização `at++`, então uma data inválida salta a entrada e avança.
Uma entrada **ausente** para o laço; uma entrada **má** é saltada. Conferido nas
linhas 109-129.

**O `MARK` dos segmentos aguenta um nome com `#` dentro.** `nameOf` junta
`série + "#" + segmento`, e `seriesIn`/`segmentIn` cortam no **primeiro** `#`
(`Segmentation.java:166,180`). Como a série não tem `#`, o primeiro é sempre o
separador e o resto — mesmo com `#` no meio — volta inteiro para a comparação por
nome. Tentei quebrar com `winfull-1m#busca#2` e não quebra.
