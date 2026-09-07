# B7b — a casca: janela principal, navegador, séries e configurações

Auditoria II, 07/09/2026.

## O que foi lido

Tudo, linha a linha. 18 arquivos, 5.397 linhas (conferido com `wc -l`).

| arquivo | linhas |
|---|---|
| `ui/shell/MainWindow.java` | 1.262 |
| `ui/series/SeriesWindow.java` | 581 |
| `ui/series/SegmentDialog.java` | 580 |
| `ui/shell/Navigator.java` | 396 |
| `ui/shell/StatusBar.java` | 332 |
| `ui/series/RangeBar.java` | 319 |
| `ui/series/SeriesMap.java` | 303 |
| `ui/shell/Icons.java` | 215 |
| `ui/settings/SettingsDialog.java` | 204 |
| `ui/series/Segmentable.java` | 179 |
| `ui/settings/ChartPage.java` | 165 |
| `ui/settings/AppearancePage.java` | 157 |
| `ui/shell/CollapsiblePane.java` | 150 |
| `ui/settings/GeneralPage.java` | 145 |
| `ui/shell/Console.java` | 132 |
| `ui/series/SeriesColors.java` | 125 |
| `ui/settings/ReplayPage.java` | 104 |
| `ui/settings/SettingsPage.java` | 48 |

Lidos como apoio, **não auditados**: `platform/SeriesCatalog.java` (trechos
`open`, `openUntil`, `countOf`, `has`, `ticksOf`, `fileOf`),
`platform/Settings.java` (`keysStartingWith`, `removeStartingWith`),
`platform/Segmentation.java` (`seriesIn`, `segmentIn`, `segmentsOnly`, `nameOf`),
`platform/JobService.java` (`onChange`, `notifyListeners`),
`domain/market/FoldedTicks.java` (`all`, `over`),
`ui/chart/ChartHolder.java` (`close`), `ui/chart/ChartCanvas.java` (`setSeries`),
`resources/messages.properties` e `messages_pt_BR.properties`, e os quatro
testes de `ui/shell` e `ui/series` (só para saber o que já tem rede).

Contagem: **3 ALTA, 8 MÉDIA, 13 BAIXA**.

---

# ALTA

### B7b-1. A tranca "só pelos segmentos" não vale para fonte de ticks — e é justo o dado de teste que ela existe para proteger

`MainWindow.java:590-595`

```java
if (segment == null && SeriesCatalog.has(asked)
        && br.com.jorge.reis.endeavourneo.platform.Segmentation.segmentsOnly(asked)) {
    console.write(Messages.get("console.locked", asked));
```

`SeriesCatalog.java:576-578`

```java
public static boolean has(String name) {
    return MarketFile.isSeries(fileOf(name));
}
```

**Problema.** A chave de ticks é `win/ticks/profit` — um nome, não um arquivo:
`Segmentable.java:41-46` diz literalmente "It is a key and not a file; nothing
looks for it on disk". Logo `SeriesCatalog.has("win/ticks/profit")` é sempre
`false`, e a conjunção inteira é `false`: a tranca **nunca** dispara para uma
fonte de ticks. E a tranca é oferecida para ticks: a combo da janela de séries é
carregada de `Segmentable.keys()` (`SeriesWindow.java:131`), que inclui as
chaves de ticks (`Segmentable.java:66-78`), e o checkbox escreve sem distinguir
(`SeriesWindow.java:238`): `Segmentation.setSegmentsOnly(editing, segmentsOnly.isSelected())`.

A árvore concorda com o checkbox só para séries de candle. `Navigator.seriesNode`
apaga o nome de uma série trancada (`Navigator.java:255-259`), mas
`Navigator.tickSessions` monta o nó de ticks sem consultar `segmentsOnly` nenhuma
vez (`Navigator.java:375`): `new DefaultMutableTreeNode(new Leaf(key, ...))`.

**Consequência.** O leitor marca "Só permitir os segmentos desta série" numa
fonte de ticks, o aviso de `series.lockedEmpty` até aparece quando não há
segmentos, e a caixa fica marcada ao reabrir a janela (`segmentsOnly.setSelected(
Segmentation.segmentsOnly(editing))`, `SeriesWindow.java:432`) — tudo dizendo que
a tranca pegou. Na árvore o nó abre sem rótulo de trancado, e o duplo clique abre
o export inteiro, anos de busca e anos de teste juntos. O texto do próprio bundle
promete o oposto: `series.segmentsOnly.hint = ... É a defesa contra olhar dado de
teste sem perceber.` É uma guarda que protege menos do que diz, e o modo de falha
é exatamente o que o mecanismo existe para impedir.

**Correção.** Trocar `SeriesCatalog.has(asked)` por um teste que valha para os
dois: `(Segmentable.isTicks(asked) || SeriesCatalog.has(asked))` — a variável
`ticks` já é calculada treze linhas abaixo (`MainWindow.java:603`), basta subir o
cálculo para antes da guarda. E em `Navigator.tickSessions`, aplicar o mesmo
`locked ? null : key` que `seriesNode` já aplica, com o mesmo sufixo
`navigator.locked`.

**Tentei refutar assim.** (a) Procurei um segundo ponto de bloqueio no caminho de
abertura: `open` só tem esta guarda, e `fillFromTicks` não consulta
`Segmentation`. (b) Procurei se `keys()` filtrava trancadas antes de povoar a
combo: não filtra nada, devolve `SeriesCatalog.names()` mais as chaves de ticks.
(c) Procurei se `has` teria algum tratamento especial para chave com barra:
`fileOf` → `relativeTo` só concatena `groupOf`/`scaleOf` e sufixo `.bin`,
resultando num caminho que não existe. (d) Procurei teste que cubra isto:
`grep -rn "segmentsOnly" src/test/.../ui/` não devolve nada — não há teste da
tranca em nenhum dos dois caminhos.

---

### B7b-2. A janela de séries lê a série INTEIRA na thread da interface, na construção e a cada troca da combo

`SeriesWindow.java:419-434`

```java
private void load() {
    editing = String.valueOf(series.getSelectedItem());

    // The counts go blank when this comes back empty; the segments are
    // still listed and still editable, which is what this window is for.
    days = Segmentable.sessionsOf(editing);
```

`Segmentable.java:142-148`

```java
try {
    PriceSeries bars = SeriesCatalog.open(key).orElse(null);

    return bars == null || bars.size() == 0 ? new TreeSet<>() : Sessions.of(bars);
```

**Problema.** `SeriesCatalog.open(String)` — sem contagem de barras — lê o arquivo
todo, e o javadoc dele mede o custo: "six years of one-minute bars is 39 MB"
(`SeriesCatalog.java:664-665`). Depois `Sessions.of(bars)` percorre cada barra.
Isso roda em três lugares, todos na EDT: no construtor (`SeriesWindow.java:166`
`load();`), no listener da combo (`SeriesWindow.java:148-151`
`series.addActionListener(e -> { save(); load(); })`), e portanto em toda troca
de série. A regra da casa é explícita, e este mesmo repositório a enuncia trinta
arquivos adiante em `MainWindow.java:640-642`: "the interface thread is the one
thread that may not spend them".

**Consequência.** Abrir *Ferramentas → Séries* congela a aplicação inteira
enquanto lê e varre a série; trocar a série na combo congela outra vez. Sem
rodapé, sem cursor de espera, sem console: a janela simplesmente para. É o
sintoma que o javadoc do `StatusBar` chama de "long work with no visible feedback
looks like a freeze" — só que aqui não é parecer, é ser.

**Correção.** Um `SwingWorker` como o de `fillFromTicks`: abrir a janela com as
contagens em branco (o caminho já existe e está escrito — `days.isEmpty()`
produz `series.unreadable` e a tabela continua editável), e preencher `days` no
`done()`. Alternativa mais barata e ainda correta: pedir ao `SeriesCatalog` uma
lista de pregões vinda do cabeçalho/índice em vez das barras, que é o que
`Segmentable` já faz para ticks com um listing de diretório.

**Tentei refutar assim.** (a) O `SoftReference` de `SeriesCatalog.LOADED`
(linha 637) faz a segunda leitura sair de graça — mas só a segunda: a primeira,
que é a que o leitor sente, lê tudo, e o cache é solto sob pressão de memória, o
que devolve a pausa em silêncio. (b) `MainWindow` usa `open(name, bars)` com
janela, que **não** popula esse cache (linhas 675-699 não escrevem em `LOADED`),
então abrir gráficos antes não aquece nada. (c) Verifiquei se a janela é modal e
poderia ser aberta antes da EDT existir: `ModalityType.MODELESS`,
`SeriesWindow.java:126`, aberta de `MainWindow.openSeries` a partir de um item de
menu — EDT sempre. (d) Verifiquei o comentário do `daysOfTicks`
(`Segmentable.java:156-158`, "this costs nothing"): é verdadeiro, mas vale só
para o ramo de ticks; o ramo de candle é o caro, e nada o diz ali.

---

### B7b-3. Javadoc grudado no membro errado, sistematicamente — sete lugares, e dois deles descrevem uma assinatura que não é a do membro

`MainWindow.java:359-390`

```java
    /**
     * @param name a series's name
     * @param title what the window will be called, for the message if it fails
     * @return the bars to draw
     ...
     */
    /**
     * Lets a chart reach the history its window left behind.
     *
     * @param holder the chart
     ...
     */
    private void offerHistory(ChartHolder holder, String name, int loaded) {
```

**Problema.** Quando dois blocos `/** */` precedem um membro, o compilador de
javadoc fica com o **último** e descarta o resto silenciosamente. Os sete lugares:

| bloco | descreve | está colado em | membro real fica sem doc |
|---|---|---|---|
| `MainWindow:133-140` "The chart windows that are open, by name" | campo `charts` | campo `restoring` (148) | `charts` (150) |
| `MainWindow:234-242` "Opens a chart in a window of its own" | `open` | `prepareToLeave` (265) | — (`open` tem doc própria) |
| `MainWindow:243-254` "Writes down which charts are open" | `rememberCharts` | `prepareToLeave` (265) | `rememberCharts` (271) |
| `MainWindow:359-370` `@param name/@param title/@return the bars to draw` | `seriesFor` | `offerHistory` (390) | `seriesFor` (507) |
| `MainWindow:746` "@return the names of the chart windows open now" | `openCharts` | `chartNamed` (754) | `openCharts` (758) |
| `MainWindow:762` "Closes every chart; also the close all action" | `closeCharts` | campo `leaving` (764) | `closeCharts` (766) |
| `MainWindow:995-1003` "Opens the preferences dialog" + `1004-1010` "Opens the replay transport" | `openPreferences` / `openReplay` | campo `replay` (1012) | ambos (1014, 1058) |
| `MainWindow:1191` "A titled panel, standing in for an Eclipse view tab" | `titled` | `followConsoleFold` (1199) | `titled` (1214) |
| `Navigator:115-124` "A leaf that shows more than it is called" + `@param name` | record `Leaf` | `nameOf` (132) | `Leaf` (142) |
| `Icons:47-50` `@param size` / `@return the "arrange the windows" glyph: four panes in a frame` | `tile` | `candle` (52) | `tile` (148) |

Dois casos não são só deslocamento, são **descrição falsa**: o bloco de
`Icons:47-50` promete "four panes in a frame" imediatamente acima de `candle`,
que desenha uma vela; e o bloco de `MainWindow:359-370` declara `@param title`
para um método (`offerHistory`) que não tem parâmetro `title` — e declara
`@return the bars to draw` para um método `void`.

**Consequência.** É ALTA porque a convenção da casa (`~/.claude/skills/convencoes`,
§1) diz que o comentário é *o canal por onde a razão de uma decisão chega à IA
que abre o repositório depois*, e aqui o canal está ligado no membro errado. Um
leitor — humano ou modelo — que abra `candle` lê que ela desenha quatro painéis;
que abra `offerHistory` lê que ela devolve as barras a desenhar e recebe o título
da janela. É comentário que mente, e a severidade acompanha o defeito que ele
esconde: quem for mexer em `seriesFor` fica sem a única frase que explica por que
o caminho de erro devolve vazio em vez do passeio aleatório — a frase existe, mas
está pendurada em `offerHistory`.

**Correção.** Mover cada bloco para cima do membro que ele descreve. Nenhum
comentário se perde: são todos textos bons, só estão no lugar errado.

**Tentei refutar assim.** (a) Confirmei que o javadoc realmente descarta o bloco
anterior, e não os concatena — é comportamento do padrão, o último comentário de
documentação antes da declaração é o que vale. (b) Verifiquei se algum desses
blocos poderia ser um comentário de seção deliberado, do tipo cabeçalho: não —
todos usam `/** */`, não `/* */`, e todos têm sujeito singular ("Opens a chart",
"@return the names") apontando para um membro específico que existe e está sem
documentação. (c) Verifiquei os que ficaram órfãos: `charts`, `rememberCharts`,
`seriesFor`, `openCharts`, `closeCharts`, `openReplay`, `openPreferences`,
`titled`, `Leaf` e `tile` estão todos sem javadoc próprio hoje.

---

# MÉDIA

### B7b-4. O `SwingWorker` dos ticks escreve num gráfico que já foi fechado

`MainWindow.java:483-493`

```java
@Override
protected void done() {
    try {
        PriceSeries bars = get();

        holder.canvas().setSeries(
                br.com.jorge.reis.endeavourneo.domain.market.SegmentedSeries.of(
                        bars, segment, java.time.ZoneId.systemDefault()));

        console.write(Messages.get("console.seriesLoaded", name,
                String.valueOf(bars.size())));
```

**Problema.** Nada verifica se o gráfico ainda existe. O próprio comentário
acima diz quanto tempo isso leva: "1.838 MB and 8,1 s for the twenty MetaTrader
sessions, 691 MB and 4,4 s for the nine of Profit" (`MainWindow.java:639-643`).
Nesses segundos o leitor pode fechar a janela — e fecha, porque ela está vazia e
parece não ter funcionado. `ChartHolder.close()` (linha 571) desanexa os frames e
chama `onClosed.run()`, que faz `charts.remove(title)`; o objeto `holder` e seu
`canvas` continuam vivos porque o worker os captura. O mesmo vale para
`offerHistory`, que além de escrever ainda **se re-agenda** num gráfico morto:
`MainWindow.java:420-421`, `holder.canvas().growHistory(...); offerHistory(holder, name, longer.size());`

**Consequência.** Três efeitos, nenhum fatal e todos visíveis: o console anuncia
`{0}: {1} barras lidas do disco` para uma janela que não está mais lá; a série
dobrada — que para o Profit são 691 MB de ticks virando barras — fica retida na
memória até o worker terminar, junto com o canvas inteiro; e o `offerHistory`
reinstala um callback de paginação num canvas desanexado.

**Correção.** No `done()`, sair cedo se o gráfico saiu do registro:
`if (charts.get(title) != holder) { return; }` — o `title` já está no escopo do
`open`. Melhor ainda, guardar o worker no `ChartHolder` e cancelá-lo no `close()`,
que é onde a casa já cancela o resto (`canvas.releaseTicks()`, linha 584, existe
por um vazamento idêntico).

**Tentei refutar assim.** (a) Procurei uma guarda dentro de `ChartCanvas.setSeries`:
`ChartCanvas.java:810-814` só troca o campo e chama `refold()`, sem checar se está
montado. (b) Procurei se `close()` cancelaria tarefas pendentes: cancela o replay
e solta os ticks do replay, não conhece estes workers. (c) Considerei que
`SwingWorker` fosse abandonado ao fechar: não é — ele roda no pool próprio do
`SwingWorker` e o `done()` é sempre despachado.

### B7b-5. O caminho de ticks lê o export inteiro mesmo quando o que foi pedido é um segmento

`MainWindow.java:476-481`

```java
@Override
protected PriceSeries doInBackground() {
    return br.com.jorge.reis.endeavourneo.domain.market.FoldedTicks.all(
            SeriesCatalog.ticksOf(instrument), instrument, source,
            java.time.ZoneId.systemDefault());
}
```

**Problema.** `FoldedTicks.all` lista todos os dias exportados e dobra todos
(`FoldedTicks.java:145-154`), e só depois `SegmentedSeries.of(bars, segment, ...)`
descarta o que está fora do segmento. Existe `FoldedTicks.over(folder,
instrument, source, days, zone)` (linha 124), que recebe a lista de dias — é
exatamente o que falta usar. E o irônico: a mudança feita horas atrás no caminho
de candle foi precisamente parar de ler fora do segmento
(`SeriesCatalog.openUntil`, com o incidente documentado em
`SeriesCatalog.java:708-717`); o caminho novo de ticks nasceu com o defeito
simétrico.

**Consequência.** Abrir um segmento de dois pregões do tape do Profit lê os nove,
691 MB, 4,4 s pelos números do próprio comentário. Segundo a nota
`ticks-completos-custam-14-mil`, a base de ticks vai crescer por compra: com o
acervo completo isso deixa de ser segundos.

**Correção.** Filtrar os dias antes de dobrar — pegar `library.exported()`,
manter os que caem no `Segment`, e chamar `FoldedTicks.over` com essa lista.
`Segmentable.sessionsOf` já sabe produzir os dias de uma chave de ticks sem ler
um arquivo sequer.

**Tentei refutar assim.** (a) Verifiquei se `SegmentedSeries` seria preguiçoso e
não materializaria o resto: o custo já foi pago em `doInBackground`, antes dele.
(b) Verifiquei se `FoldedTicks.all` teria cache entre aberturas: não tem, cada
abertura relê. (c) O resultado é **correto** — só caro; por isso MÉDIA e não ALTA.

### B7b-6. `followConsoleFold` sobrescreve a posição que acabou de guardar, e devolve o console a uma posição de outra sessão

`MainWindow.java:1199-1212`

```java
private void followConsoleFold() {
    if (consolePane.isFolded()) {
        consoleWasAt = bottomDivider.getDividerLocation();

        consoleWasAt = PREFS.getInt(BOTTOM_DIVIDER, -1);
        bottomDivider.setDividerLocation(bottomDivider.getHeight()
                - consolePane.foldedHeight() - bottomDivider.getDividerSize());

        return;
    }

    bottomDivider.setDividerLocation(consoleWasAt > 0
            ? consoleWasAt : (int) (getHeight() * 0.68));
}
```

**Problema.** Duas atribuições seguidas a `consoleWasAt`: a primeira lê onde o
divisor **está** e a segunda a joga fora, pondo no lugar o que foi gravado no
`Preferences` no encerramento anterior. O javadoc logo acima promete o contrário
(`MainWindow.java:1192-1198`): "And puts it back where the reader had it. A fold
that reopened at some default height would cost them the size they chose every
time they peeked at the desktop".

**Consequência.** Dois modos de falha. O leve: arrastar o divisor, dobrar e
desdobrar devolve o console à altura da sessão passada, não à que a pessoa
acabou de escolher — a promessa do javadoc, quebrada. O grave: se a aplicação foi
encerrada **com o console dobrado**, `storeLayout` gravou a posição dobrada
(`MainWindow.java:1247`); na sessão seguinte, o primeiro dobrar carrega esse valor
e o desdobrar devolve o divisor à posição dobrada — o console "abre" com uns
vinte pixels de altura, e só voltar a `View → Reset layout` conserta.

**Correção.** Apagar a segunda linha. A primeira já é a certa.

**Tentei refutar assim.** (a) Procurei se `PREFS` fosse atualizado durante a
sessão a cada movimento do divisor, o que tornaria as duas linhas equivalentes:
`storeLayout()` é chamado só de `leave()` e de `relaunch()` — nunca ao arrastar.
(b) Verifiquei o arranque: `consoleWasAt` nasce `-1` (linha 125) e `restoreLayout`
não o preenche, então o **primeiro** desdobrar de uma sessão cai no default de
0,68 — é por isso que o defeito não aparece sempre, e é por isso que ele é fácil
de não reproduzir.

### B7b-7. Trocar de idioma abandona um `StatusBar` para sempre dentro do `JobService`

`MainWindow.java:192` / `MainWindow.java:1031-1049` / `JobService.java:399-402`

```java
status.bind(jobs);
```

```java
MainWindow fresh = new MainWindow(Messages.get("app.title"), jobs);
```

```java
public void onChange(Runnable listener) {
    listeners.add(listener);
}
```

**Problema.** `relaunch()` descarta a janela (dispõe o frame, fecha os gráficos,
dispõe o replay) mas nada desfaz o `bind`. `JobService.onChange` só acrescenta —
o javadoc de um irmão dele em `SeriesCatalog.java:786-788` até explica quando isso
é aceitável ("Registered once at startup and never removed") — e aqui não é uma
vez no arranque, é uma vez por troca de idioma.

**Consequência.** Cada troca de idioma deixa preso no `JobService` um `StatusBar`
morto, e com ele toda a árvore de componentes da janela anterior, que o
`dispose()` não consegue coletar. Pior que o vazamento: a cada progresso de
tarefa o `notifyListeners` roda `refresh()` também nas barras mortas —
`revalidate()`, `setText`, `setIndeterminate` em componentes fora de tela, de
graça e para sempre. A regra da casa é "todo recurso que abre thread ou arquivo é
fechado por quem o abriu"; uma inscrição em listener é o mesmo caso.

**Correção.** Dar ao `JobService` um `removeOnChange`, e chamá-lo em
`prepareToLeave()` (que é o ponto por onde `leave()` e `relaunch()` passam os
dois). Ou guardar o listener registrado e trocá-lo no lugar de acrescentar.

**Tentei refutar assim.** (a) Procurei se `listeners` fosse uma coleção de
referências fracas: é `add` numa lista comum. (b) Procurei se `JobService.close()`
limparia: fecha os handles, não mexe nos listeners — e de todo modo não é chamado
no `relaunch`. (c) Verifiquei que `relaunch` é alcançável de verdade:
`openPreferences` compara `Language.remembered()` antes e depois e chama
(`MainWindow.java:1071-1073`).

### B7b-8. O console recebe UTF-8 e o decodifica byte a byte: todo acento vindo de `System.out` sai trocado

`Console.java:91-106`

```java
PrintStream stream = new PrintStream(new OutputStream() {

    private final StringBuilder pending = new StringBuilder();

    @Override
    public void write(int b) {
        char c = (char) b;

        if (c == '\n') {
            Console.this.write(pending.toString());
```

**Problema.** O `PrintStream` é construído com `StandardCharsets.UTF_8` (linha
106), portanto entrega **bytes UTF-8** ao `OutputStream`. O `write(int b)` faz
`(char) b`, que é decodificação Latin-1. Todo caractere fora do ASCII vira dois
caracteres errados: `ã` (0xC3 0xA3) chega como `Ã£`.

**Consequência.** Qualquer `System.out.println` com acento aparece no console
como mojibake. A interface é pt-BR e cheia de acentos —
`console.locked = {0} está trancada: abra um segmento dela.` sairia certo (esse
vai por `Console.write` direto), mas tudo que passa pela captura de saída padrão,
que é ligada de verdade em `Launcher.java:145`
(`window.getConsole().captureStandardOutput();`), sai corrompido. É texto errado
na tela, e do tipo que faz o leitor duvidar do arquivo em vez do console.

**Correção.** Trocar o charset do `PrintStream` para `ISO_8859_1` — aí byte e
`char` coincidem e o `(char) b` passa a estar certo — ou, melhor, acumular bytes
num `ByteArrayOutputStream` e decodificar a linha inteira com
`new String(bytes, UTF_8)` ao ver `\n`.

**Tentei refutar assim.** (a) Verifiquei se o método era código morto:
`Launcher.java:145` chama. (b) Verifiquei se algum decorador entre `PrintStream` e
o `OutputStream` faria a decodificação: não há — o `PrintStream` codifica e chama
`write(int)` byte a byte. (c) Testei o raciocínio no sentido inverso: se o
`PrintStream` tivesse sido criado sem charset, herdaria o do console do sistema
(cp1252 no Windows), e aí `(char) b` estaria quase certo — a passagem explícita
de `UTF_8` é que introduz a discordância.

### B7b-9. O javadoc do `Navigator` diz que uma sessão de ticks não abre nada, quatro linhas antes do código que a faz abrir

`Navigator.java:325`

```java
     * <p>They open nothing: a tick session is what a series is replayed FROM,
     * not a chart of its own.</p>
     */
    private static DefaultMutableTreeNode tickSessions(String instrument) {
```

`Navigator.java:369-375`

```java
            // NAMED, so it opens. It used to be a leaf that opened nothing, on
            // the argument that "a tick session is what a chart is REPLAYED
            // from, not a chart". That was wrong: ...
            DefaultMutableTreeNode found = new DefaultMutableTreeNode(new Leaf(key,
```

**Problema.** A mudança das últimas horas reverteu a decisão e escreveu um
comentário excelente explicando por quê — mas deixou o javadoc antigo intacto no
método de cima, afirmando o oposto com as mesmas palavras que o comentário novo
cita para refutar. São duas respostas para a mesma pergunta em dois lugares, e a
errada é a que aparece no javadoc gerado.

**Consequência.** É o caso que a convenção da casa classifica como "comentário
que mente", e a severidade acompanha o que ele esconde: quem ler só o javadoc
conclui que a árvore não abre ticks, e não vai procurar o defeito B7b-1 (a
tranca que não pega em ticks) porque acredita que ticks não abrem de todo jeito.

**Correção.** Apagar esse parágrafo do javadoc de `tickSessions(String)` e pôr no
lugar a frase do comentário de baixo: o export tem toda impressão de todo pregão,
que é mais do que o arquivo de candle tem.

**Tentei refutar assim.** Verifiquei se `tickSessions(String)` e
`tickSessions(Path, String)` seriam caminhos diferentes, um que abre e outro que
não: a primeira só delega para a segunda (`Navigator.java:328`), então o javadoc
descreve exatamente o código que o desmente.

### B7b-10. "A leaf with no name — a tick session, a message — opens nothing": a folha de *Estudos* abre um gráfico

`Navigator.java:89-91` e `Navigator.java:132-140`

```java
                // The NAME, never the label: the tree shows "winn-1m . busca"
                // and the rest of the program only knows "winn-1m". A leaf with
                // no name -- a tick session, a message -- opens nothing.
```

```java
    static String nameOf(DefaultMutableTreeNode node) {
        Object held = node.getUserObject();

        if (held instanceof Leaf entry) {
            return entry.name();
        }

        return node.isLeaf() ? String.valueOf(held) : null;
    }
```

**Problema.** A promessa vale só para nós embrulhados em `Leaf`. O nó de estudos
não é (`Navigator.java:193`):
`studies.add(new DefaultMutableTreeNode(Messages.get("document.untitled")));`
— o `userObject` é uma `String`, `isLeaf()` é verdadeiro, e `nameOf` devolve o
próprio rótulo traduzido. Duplo clique em "Sem título" chama
`MainWindow.open("Sem título")`, que não acha série nem ticks e cai no
`SeriesCatalog.defaultName()` (`MainWindow.java:604`).

**Consequência.** Clicar num item da seção *Estudos* abre um gráfico da série
padrão — nada a ver com estudo nenhum. O comentário garante que isso não
acontece, e por isso ninguém procura. (A metade de "a tick session" da mesma
frase também deixou de ser verdade; ver B7b-9.)

**Correção.** Devolver `null` quando o `userObject` não é `Leaf` — os únicos nós
que devem abrir já carregam `Leaf`. E atualizar as duas frases.

**Tentei refutar assim.** (a) Procurei se a seção *Estudos* teria algum
tratamento no `MainWindow`: `open` não distingue, o comentário de linha 578-582
até descreve o efeito ("Workspaces written before there was a series hold names
like 'Sem título'") — só que ali é sobre reparar workspace antigo, não sobre um
nó que a árvore cria hoje. (b) Verifiquei o `NavigatorTreeTest`: testa nós de
série e de ticks, nenhum teste toca o nó de estudos.

### B7b-11. Editar um segmento "em diante" fecha-o na última sessão em disco, sem dizer

`SegmentDialog.java:567-572`

```java
    private Segment current() {
        String chosenName = name.getText().isBlank()
                ? Messages.get("series.newName") : name.getText().trim();

        return new Segment(chosenName, days.get(range.from()), days.get(range.to()));
    }
```

`SegmentDialog.java:156-158`

```java
        from = new DatePicker(start.from());
        to = new DatePicker(start.isOpenEnded() ? sessions.get(sessions.size() - 1)
                : start.to());
```

**Problema.** Um segmento aberto (`to() == null`, mostrado como "em diante" na
tabela, `SeriesWindow.java:574-575`) entra no diálogo com o `to` preenchido pela
última sessão conhecida. `current()` sempre constrói um `Segment` de duas datas
— não há caminho que devolva um segmento aberto. Abrir *Editar*, não mexer em
nada e clicar em *Salvar* troca "em diante" por uma data fixa.

**Consequência.** O segmento deixa de crescer. Amanhã a série ganha pregões e
eles ficam fora dele — e é exatamente esse segmento aberto que costuma ser o de
teste, o que tem de acompanhar o dado novo. Do lado do gráfico a diferença é
real e silenciosa: `MainWindow.endOf` devolve `Long.MAX_VALUE` para o aberto e um
instante fixo para o fechado (`MainWindow.java:445-450`), o que muda a janela
lida do arquivo. Nada avisa; a tabela só passa a mostrar uma data onde mostrava
"em diante".

**Correção.** Uma caixa "em diante" no diálogo, ou preservar a abertura quando o
handle final está na última sessão e o segmento chegou aberto — e, de qualquer
modo, dizer na frase do rodapé que o segmento está aberto, já que
`Segment.isOpenEnded` é consultado por todo o resto do programa.

**Tentei refutar assim.** (a) Procurei um controle de "sem fim" no diálogo:
`fields()` (linhas 336-370) tem nome e dois `DatePicker`, mais nada. (b) Procurei
se `SeriesWindow.openSelected` reconstruiria a abertura ao salvar:
`model.replace(row, segment)` grava o que o diálogo devolveu, sem tocar em
`to()`. (c) Procurei se `Segment` teria um construtor que interpretasse a última
sessão como aberto: `SeriesWindow.suggested()` usa `Segment.from(nome, data)`
para criar aberto, e o diálogo nunca chama esse construtor.

---

# BAIXA

### B7b-12. `seriesFor` recebe um `title` que nunca usa — e o javadoc órfão diz que ele é usado

`MainWindow.java:507`

```java
    private PriceSeries seriesFor(String name, String title,
            br.com.jorge.reis.endeavourneo.domain.market.Segment segment) {
```

O corpo inteiro (linhas 509-556) usa só `name`; as três mensagens de console e a
do rodapé passam `name`. E o bloco de javadoc que descreve este método — hoje
pendurado em `offerHistory`, ver B7b-3 — diz `@param title what the window will
be called, for the message if it fails`, que é falso. **Correção:** remover o
parâmetro e a linha do javadoc. **Tentei refutar:** procurei uso indireto por
captura em lambda dentro do método; não há lambda ali.

### B7b-13. `uniqueTitleOf` é código morto

`MainWindow.java:558-569`. Busca em todo `src/` (`grep -rn "uniqueTitleOf"`)
devolve só a própria declaração. Nasceu para responder "que título o gráfico
recebeu", pergunta que `open` passou a responder devolvendo o título
(`MainWindow.java:695`). **Correção:** apagar.

### B7b-14. Quatro imports e um campo sem uso em `SeriesWindow`

`SeriesWindow.java:20,24,31,32` (`PriceSeries`, `SeriesCatalog`, `IOException`,
`Instant`) e `SeriesWindow.java:76`:

```java
    private static final ZoneId ZONE = ZoneId.systemDefault();
```

Verificado por contagem de ocorrências: cada um aparece exatamente uma vez no
arquivo, na própria declaração. O `ZONE` é resíduo de quando a janela trabalhava
em barras e não em dias — o javadoc do campo `days` (linhas 101-110) documenta
justamente essa mudança. **Correção:** apagar os cinco.

### B7b-15. Combo vazia grava um segmento sob a chave literal `"null"`

`SeriesWindow.java:420`

```java
        editing = String.valueOf(series.getSelectedItem());
```

Sem nenhuma série e sem nenhuma fonte de ticks, `getSelectedItem()` é `null` e
`String.valueOf` devolve a **string** `"null"`. A guarda de `save()` —
`if (editing != null)`, linha 437 — não pega isso, e `Segmentation.set("null",
...)` escreve chaves com esse nome no arquivo de configuração. **Correção:**
`Object picked = series.getSelectedItem(); editing = picked == null ? null :
String.valueOf(picked);`. **Tentei refutar:** conferi se a combo pode mesmo ficar
vazia — `Segmentable.keys()` devolve lista vazia quando não há série nem ticks,
que é o estado de máquina nova, o mesmo que `navigator.noSeries` já contempla.

### B7b-16. Os gráficos voltam fora de ordem a partir do décimo

`Settings.java:382-395` ordena as chaves com `Collections.sort` (lexicográfica),
e `MainWindow.restoreCharts` (linha 320) consome nessa ordem. `chart.open.10.series`
vem antes de `chart.open.2.series`. **Consequência:** com dez ou mais gráficos
abertos, a numeração "(2)" muda de dono no arranque seguinte, e com ela a
geometria lembrada por título. **Correção:** ordenar pelo índice numérico
extraído da chave, ou gravar com dois dígitos.

### B7b-17. O fundo do desktop é cinza-escuro fixo, contra a regra que o próprio pacote defende

`MainWindow.java:168`

```java
        desktop.setBackground(java.awt.Color.DARK_GRAY);
```

`Icons.java:33-36` argumenta contra exatamente isto ("a PNG exported for the light
theme becomes a dark smudge on the night one"), e `SeriesColors` inteiro existe
para ler do tema. E como `Color.DARK_GRAY` não é `UIResource`,
`SwingUtilities.updateComponentTreeUI` (`AppearancePage.java:124`) não o
substitui ao trocar de tema. **Correção:** `UIManager.getColor("Desktop.background")`
com queda para o valor fixo.

### B7b-18. Texto de tela fora do bundle em `SegmentDialog`

`SegmentDialog.java:560-562`

```java
        if (days.isEmpty()) {
            return "0,0%";
        }
```

Vírgula decimal codificada no fonte, num projeto cuja regra é que texto de
interface nunca sai do `ResourceBundle` (e cujo formato correto viria do
`String.format` da linha seguinte, que segue o locale). **Correção:**
`String.format("%.1f%%", 0.0)`.

### B7b-19. Em modo somente-leitura o botão de fechar tem dois listeners que fazem a mesma coisa

`SegmentDialog.java:430-441`: o listener registrado em `create` já chama
`dispose()` para todo modo; dentro do `if (readOnly)` um segundo
`create.addActionListener(e -> dispose())` é somado. Inofensivo — `dispose()` é
idempotente — mas dá a entender que o primeiro não cobre o caso.

### B7b-20. O diálogo de preferências nunca é disposto quando fechado pelo X

`SettingsDialog.java:104-106`

```java
    public static void show(Window owner, List<SettingsPage> pages) {
        new SettingsDialog(owner, pages).setVisible(true);
    }
```

Sem `setDefaultCloseOperation(DISPOSE_ON_CLOSE)`, fechar pela barra de título só
esconde: o diálogo e seu peer nativo ficam. Cada abertura cria um novo.
**Correção:** uma linha no construtor. (Cancel e OK chamam `dispose()`; só o X
escapa.)

### B7b-21. Condição morta em `AppearancePage.apply`

`AppearancePage.java:111`

```java
        if (chosen == Theme.remembered() && !buttons.isEmpty()) {
```

`buttons` é preenchido no construtor a partir de `Theme.values()` e nunca
esvaziado — a segunda metade é sempre verdadeira. Ou existe uma razão (um enum
vazio?) e ela precisa estar num comentário, ou some.

### B7b-22. `RangeBar.move` codifica o par num inteiro com um multiplicador mágico

`RangeBar.java:222` e `230`

```java
        int before = from * 100000 + to;
```

Funciona porque nenhuma série chega a 21.474 pregões (o limite de estouro), mas o
número não tem origem escrita e o estouro seria silencioso. **Correção:**
comparar os dois campos, que é mais barato e não estoura.

### B7b-23. `SeriesColors.taken` é `public` no meio de irmãos de pacote

`SeriesColors.java:102` é `public static Color taken(int at)`, enquanto `free`,
`rule`, `faint`, `fresh`, `clash` e `dark` são todos de pacote. Os dois chamadores
(`SeriesMap`, `SegmentDialog`) estão no mesmo pacote. **Correção:** reduzir para
pacote, ou dizer por que este é diferente.

### B7b-24. O campo `leaving` é declarado no meio dos métodos, sob o javadoc de outro membro

`MainWindow.java:762-766`

```java
    /** Closes every chart; also the "close all" action. */
    /** True from the moment the window starts closing, so the list stops moving. */
    private transient boolean leaving;

    public void closeCharts() {
```

Além do javadoc trocado (contado em B7b-3), o campo está fora da região de
campos, o que faz `rememberCharts` (linha 272) ler `leaving` quinhentas linhas
antes da declaração.

### B7b-25. `relaunch` refaz à mão os passos de `leave()`, na ordem trocada

`MainWindow.java:1032-1040` faz `prepareToLeave(); storeLayout(); ...
closeCharts();` enquanto `leave()` (linha 226-230) faz `prepareToLeave();
closeCharts(); storeLayout();`. Hoje é indiferente — fechar gráfico não move
divisor —, mas o javadoc de `leave()` (linhas 211-224) conta em detalhe o
prejuízo da última vez em que dois caminhos de saída divergiram, e este é um
terceiro caminho já divergindo. **Correção:** `relaunch` chamar `leave()`.

---

# LIMPO

O que confirmei estar certo, e como.

- **As chaves de bundle do caminho novo existem nos dois idiomas.** Conferi com
  `grep` no `messages.properties` e no `messages_pt_BR.properties`:
  `console.readingTicks` (linha 383 nos dois), `console.locked` (357),
  `navigator.locked` (356), `navigator.ticks` (48), `navigator.tickSessions` (49),
  `navigator.tickSource.metatrader`/`.profit` (50-51), `series.segmentsOnly`,
  `.hint` e `series.lockedEmpty` (225-227), `segment.viewTitle`/`.close`
  (361-362), `segment.editTitle`/`.save` (352-353). Nenhuma cai no marcador
  `!chave!`, e o `MainWindowTest.everyStringIsTranslated` cobre menus.

- **A janela de um gráfico de segmento está de fato ancorada no fim do segmento.**
  Segui `MainWindow.seriesFor` (linhas 521-525) até `SeriesCatalog.openUntil`
  (719-744) e conferi `endOf` (445-450): `to().plusDays(1).atStartOfDay(...)` é
  exclusivo, o que inclui o dia inteiro do fim, como o javadoc promete; segmento
  aberto vira `Long.MAX_VALUE`, e `countUntil` sobre isso devolve o total, ou
  seja, o caminho aberto degenera exatamente no `open(name, bars)` antigo. O
  atalho de cache de `openUntil` (linha 728) devolve a série inteira ignorando o
  `upTo`, mas isso é inofensivo porque `MainWindow` sempre embrulha em
  `SegmentedSeries.of(loaded, segment, zone)` (linhas 650-652), que recorta por
  data.

- **O caminho de ticks não fica sem série nem com título errado.** `FoldedTicks.all`
  nunca devolve `null` — `over` termina em `parts.isEmpty() ? PriceSeries.empty()
  : ConcatSeries.of(parts)` (`FoldedTicks.java:136`) — então o `get()` do
  `done()` não pode dar NPE. O título vem de `uniqueTitle(ticks || has(asked) ?
  series : name)` (`MainWindow.java:618`), que para ticks preserva o nome pedido
  com o `#segmento`; o rótulo vem de `Segmentable.labelOf` (linhas 621-624), que
  monta "WIN · Ticks · Profit" a partir do bundle; e o instrumento entregue ao
  canvas é a metade de mercado da chave (linhas 634-636), que é o que ele precisa
  para achar as sessões `winfut-2021-01-04.bin`. `Segmentable.instrumentOf` e
  `sourceOf` são consistentes com `keyOfTicks` — decodificam pelo mesmo separador
  `TICKS`, declarado uma vez (`Segmentable.java:59`).

- **A restauração de gráficos não se autodestrói.** Li o trio
  `rememberCharts`/`prepareToLeave`/`restoreCharts` procurando o defeito que os
  comentários dizem já ter existido (a lista reescrita enquanto é lida):
  `restoreCharts` copia a lista inteira antes de abrir qualquer coisa (linha 318),
  `restoring` bloqueia a reescrita durante o laço (272-280), e `leaving` bloqueia
  na saída. `charts.get(null)` — que acontece quando `open` devolve `null` por
  série trancada — não lança, `LinkedHashMap` aceita chave nula em `get`. Está
  certo.

- **O `StatusBar` pode ser chamado de qualquer thread, como o javadoc promete.**
  Verifiquei os dois lados: `say`, `chart` e `noChart` passam por `onEdt`
  (`StatusBar.java:226-232`), e o javadoc de `bind` afirma que o serviço chama de
  volta na EDT — confirmei em `JobService.notifyListeners` (linha 430-436), que
  encaminha por `SwingUtilities.invokeLater`. O comentário é verdadeiro.

- **`TickLibrary` é fechado em toda travessia.** Os dois lugares que a abrem
  fecham: `Navigator.tickSessions` com try-with-resources (linhas 356-360) e
  `Segmentable.daysOfTicks` com `try/finally` (151-163). Os comentários que
  explicam o vazamento antigo — "dropped with its reading thread still alive, once
  per tick source, every time the tree was rebuilt" — descrevem código que hoje
  está correto.

- **A aritmética do ladrilho e das duas escalas está certa.** Fiz `tileBounds`
  (`MainWindow.java:813-855`) à mão para 1, 2, 3, 4 e 5 janelas: `rows` nunca
  passa de `count` (porque `round(sqrt(n)) <= n` para `n >= 1`), então `columns`
  nunca é zero e não há divisão por zero; a última coluna e a última linha
  absorvem o resto, e o teste `tilingLeavesNoGapsAndNoOverlap` afirma a área
  exata. E conferi a promessa que `SeriesMap` e `RangeBar` fazem uma à outra:
  `SeriesMap.x` (linha 122) e `RangeBar.edgeOf` (190) dividem ambos pelo número de
  sessões e somam o mesmo `SIDE`, que é uma constante compartilhada
  (`RangeBar.java:68`) — as duas escalas coincidem de verdade.

- **O diálogo de segmento não entra em laço.** `followHandles` e `followDates`
  chamam-se em cruz; o campo `echoing` (`SegmentDialog.java:100`, usado em 466-477
  e 494-501) corta o retorno nos dois sentidos, e é reposto em `finally`, então
  uma exceção no meio não deixa o par travado.

- **Uma série que não lê desenha nada, e não preços inventados.** `seriesFor`
  devolve `PriceSeries.empty()` no `catch` (`MainWindow.java:547`) e só cai no
  `RandomWalkSeries` quando não existe série nenhuma (555). O
  `MainWindowTest.anUnreadableSeriesDrawsNothing` prova isso com um arquivo
  corrompido de verdade, contando as barras desenhadas — teste com dentes.

- **As páginas de configuração separam `load` de `apply` como o contrato manda.**
  Li as quatro: nenhuma escreve fora do `apply`, o que é o que dá sentido ao
  Cancelar, e todas releem no `load` em vez de guardar o estado da construção — o
  motivo está comentado em `GeneralPage.java:129-131` e `ChartPage.java:148-149`.
  `SettingsDialog.applyAll` percorre a lista na ordem, e `AppearancePage` é a
  única com efeito colateral global (`updateComponentTreeUI` em todas as janelas),
  o que está certo e explicado.

- **`Console.trim` não perde a última linha nem lança.** `getLineEndOffset(TRIM_BLOCK)`
  só é chamado depois de `getLineCount() > LINE_CAP` com `TRIM_BLOCK` cinco vezes
  menor que o teto, e o `catch (BadLocationException)` cai para `setText("")`.
  O corte em blocos de mil, e não a cada escrita, está comentado com a razão.

- **Não achei leitura do futuro nem violação de camada nesta área.** Nenhum dos
  18 arquivos importa `domain` de forma invertida (a dependência anda só de `ui`
  para `domain`), nenhum lê barra não fechada, e nenhum decide nada sobre renko ou
  agregação — a casca não calcula indicador, ela só monta janela. Também não achei
  alocação por elemento em laço quente: os `paintComponent` de `SeriesMap` e
  `RangeBar` alocam `Color` e `BasicStroke` por pintura, não por elemento, e a
  pintura acontece por gesto do leitor, não por barra.
