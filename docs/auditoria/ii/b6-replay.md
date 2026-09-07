# B6 — o transporte de replay

Segunda passada, 07/09/2026. Área: `src/main/java/br/com/jorge/reis/endeavourneo/ui/replay/`.

## O que foi lido

Todas as linhas dos 9 arquivos da área, **2.693 linhas** (`wc -l` confere):

| arquivo | linhas |
|---|---|
| `ui/replay/ReplaySession.java` | 797 |
| `ui/replay/ReplayPanel.java` | 783 |
| `ui/replay/DatePicker.java` | 293 |
| `ui/replay/ReplayFeed.java` | 286 |
| `ui/replay/ReplayIcons.java` | 189 |
| `ui/replay/ReplayDrop.java` | 95 |
| `ui/replay/ReplayPreferences.java` | 93 |
| `ui/replay/ReplayWindow.java` | 87 |
| `ui/replay/ReplayTransfer.java` | 70 |
| **total** | **2.693** |

Lidos **só como apoio para refutar** (não auditados): `domain/market/ReplaySeries.java`,
`RecordedTicks.java`, `TickLibrary.java`, `platform/Messages.java`, `platform/SeriesCatalog.java`
(trechos), `ui/chart/ChartHolder.java` (trechos), `ui/chart/ChartCanvas.java` (trechos),
`src/main/resources/messages.properties` e `messages_pt_BR.properties`, e os testes de
`src/test/java/.../ui/replay/` (1.862 linhas).

**Contagem:** 3 ALTA · 6 MÉDIA · 6 BAIXA.

---

## ALTA

### B6-1. Um replay de ticks com mais de dois pregões passa a animar com o passeio inventado, sem dizer

`ReplaySession.java:298-310`

```java
        this.preparing = feed.isTicks() && ticks.has(date);

        if (feed.isTicks()) {
            ticks.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(() -> {
                if (preparing && ticks.at(this.date) != null) {
                    preparing = false;

                    announce();
                }
            }));

            ticks.request(date);
        }
```

**Problema.** `ticks.request(date)` é chamado **uma vez só, no construtor, e só para o primeiro
dia**. `TickLibrary.request` (`domain/market/TickLibrary.java:158-165`) enfileira `day`,
`day.plusDays(1)` e `day.minusDays(1)` — nada além disso. Enquanto o relógio anda pela faixa,
ninguém volta a chamar `request`. E `TickLibrary.at` não busca em disco, por projeto:

```java
    /**
     * @return the session if it is in memory, otherwise null
     *
     * <p>Never blocks and never reads the disk. Null means "not yet", not "does
     * not exist" — ask {@link #has} for that.</p>
     */
    public TickSeries at(LocalDate day) {
```

`RecordedTicks.timedPathFor` (`domain/market/RecordedTicks.java:80-85`) responde a esse `null`
caindo no `fallback`, que é exatamente o passeio sintético montado em `ReplaySession.java:267-270`.
Some-se `TickLibrary.RESIDENT = 3` e o despejo por distância ao `focus`, que continua fixado no
primeiro dia porque `focus` só é escrito dentro de `request`.

**Consequência.** O leitor escolhe o feed de ticks — a única razão de escolhê-lo é que cada
oscilação dentro da barra *aconteceu* — pede a semana inteira do tape (são 8-9 pregões
exportados, e a janela padrão permite ~8), e a partir do **terceiro pregão** cada barra é
animada por um caminho inventado. As barras continuam reais (`foldedFromTicks` lê do arquivo,
não da biblioteca), o rótulo do feed continua dizendo "Profit", e `isRecorded()` continua
respondendo `true` — ele pergunta `ticks.has(date)`, o **primeiro** dia (`ReplaySession.java:543`).
É a mesma confusão de "quais minutos vieram de onde" que a "uma regra só" foi escrita para acabar,
reaparecendo por dentro do feed de ticks em vez de por dentro do feed de barras.

**Correção.** Pedir o dia corrente conforme o relógio anda — o `ChartCanvas` já faz isso com a
biblioteca dele (`ui/chart/ChartCanvas.java:1432-1433`: `growingFrom.request(day);
growingFrom.request(day.plusDays(1));`). Um `request(dayOfClock())` dentro de `tick()`, ou um
observador da virada de pregão, resolve; `request` já é barato quando o dia está residente
(`queue` sai fora em `at(day) != null`). E `isRecorded()` deveria perguntar sobre o dia que está
tocando, não sobre `date`.

**Tentei refutar assim.** (a) Procurei outro chamador de `request` em `src/main`: só
`ChartCanvas:1432-1433`, e essa é **outra** `TickLibrary`, a do canvas — ou seja, num replay de
faixa o renko do canvas mantém os ticks em dia e a animação da vela não, duas bibliotecas
andando em passos diferentes. (b) Verifiquei se `foldedFromTicks` aquece a biblioteca de
passagem: o javadoc de `ReplaySession.java:428-431` diz o contrário, com todas as letras — *"Read
straight from the file and NOT through the library, on purpose."* (c) Verifiquei se `at()` carrega
sob demanda: o javadoc citado acima nega. (d) Procurei um teste que cubra isso: **todas** as
sessões de feed de ticks nos testes são de um dia só — `TickSourceChoiceTest.java:158-159`
(`DAY, DAY`), `:200-201`, `:226-227`, `FirstSessionTest.java:81-83`. Nenhum monta faixa de ticks.
Nada derruba o achado.

---

### B6-2. `isEmpty()` só é verdadeiro quando não há histórico — e em produção há sempre 30 dias

`ReplaySession.java:470-480`

```java
    /**
     * @return whether the range holds no session at all
     *
     * <p>A Saturday, a holiday, or dates outside what the series covers. The
     * transport says so rather than showing a play button that would do
     * nothing — and rather than the old answer, which was to invent a session
     * that never happened.</p>
     */
    public boolean isEmpty() {
        return live.total() == 0;
    }
```

**Problema.** `ReplaySeries.total()` é `day.size()` (`domain/market/ReplaySeries.java:151-153`), e
`day` é a **concatenação inteira** — histórico + pregões jogáveis. O construtor soma primeiro os
dias de histórico (`ReplaySession.java:243-248`) e só depois os jogáveis, guardando a fronteira em
`origin`/`completed` (`:289`). Com `ReplayPreferences.DEFAULT_HISTORY = 30`, um recorte que não
contém pregão nenhum ainda produz `total() ≈ 30 × 567` barras, e `isEmpty()` responde `false`.

`ReplayPanel.java:697` é quem depende disso:

```java
        boolean nothingToPlay = ready && session.isEmpty();
```

**Consequência.** A guarda protege muito menos do que o javadoc dela promete. Pedindo um sábado,
um feriado ou uma data fora do que a série cobre, o transporte **não** diz `replay.nothing`, deixa
o botão de tocar aceso, o relógio parado no fechamento de ontem e o scrubber cravado em 100%
(`ReplaySeries.progress()` devolve `1.0` quando `day.size() - origin <= 0`). Apertar tocar não faz
nada: exatamente o "botão morto que se lê como defeito" que o resto da classe se esforça para
evitar.

**Correção.** Perguntar pela parte jogável, não pelo total: `live.total() - live.origin() == 0`
(`origin()` já é público em `ReplaySeries.java:130`), ou guardar o `playable` que o construtor já
conta em `ReplaySession.java:250-255` e nunca usa fora do `if`.

**Tentei refutar assim.** (a) Procurei a compensação no chamador: `ReplayPanel.refresh()` não tem
outra; o único caminho para `replay.nothing` é esse booleano. (b) Procurei o teste que deveria
pegar — existe, `ReplayRangeTest.java:165`: `assertTrue(weekend.isEmpty(), "the transport has to
be able to say it has nothing");`. Ele passa porque a fábrica do próprio teste é
`ReplayRangeTest.java:108`: `return new ReplaySession(base, from, to, 0);` — **historyDays = 0**.
Nenhum teste da área constrói sessão com o histórico de produção. É um teste que dá licença falsa,
o que sustenta a severidade em vez de derrubá-la. (c) Conferi se o construtor acrescenta barras em
outro lugar quando `playable == 0`: acrescenta `dayOf(date)`, que é vazio nesse caso
(`ReplaySession.java:257-262`), então nada mais infla o total. Nada derruba o achado.

---

### B6-3. `forgetEnding` não é chamado em `src/main`: o vazamento que ele foi criado para fechar continua aberto, e um teste diz que não

`ReplaySession.java:638-648`

```java
    /**
     * @param ending the one handed to {@link #whenEnded}
     *
     * <p>The watchers had a way out and the endings did not. A chart that let
     * the session go -- closed, or had the replay detached -- stayed on this
     * list until the session itself stopped, holding a reference to a window
     * that is gone and a callback that will run into it.</p>
     */
    public void forgetEnding(Runnable ending) {
        endings.remove(ending);
    }
```

`ReplayDrop.java:84-93` é o único lugar do produto que registra um `ending`:

```java
        Runnable follow = () -> holder.canvas().seriesGrew();

        holder.attachReplay(session.name() + " " + session.rangeText(),
                session.series(), () -> session.forget(follow), session.playing(),
                session.feedLabel());

        session.watch(follow);
        session.whenEnded(holder::detachReplay);
```

**Problema.** O `detach` entregue ao `ChartHolder` é `() -> session.forget(follow)` — solta **só o
observador**. O `ending` (`holder::detachReplay`) fica na lista para sempre. `forgetEnding`
existe, é público, e `grep -rn "forgetEnding" src/main` devolve **apenas a própria declaração**;
o único outro chamador em todo o repositório é `ReplayHousekeepingTest.java:79`.

**Consequência.** Dois efeitos. (1) Um gráfico fechado ou destacado continua preso pela sessão:
o `ChartHolder`, o canvas e a série que ele guardava não são coletados enquanto o replay viver.
(2) Quando a sessão para, `ReplaySession.stop()` roda esse `ending` contra a janela morta —
e `ChartHolder.close()` (`ui/chart/ChartHolder.java:571-572`) roda `detachReplay.run()` mas **não**
zera `beforeReplay`, então a guarda `if (beforeReplay == null) return;` de `detachReplay()`
(`:322-323`) não segura nada: o corpo inteiro roda, `canvas.setSeries(beforeReplay)` e `retitle()`
incluídos, numa janela que já foi embora.

O teste que deveria cobrir isso registra e desregistra ele próprio, sem passar pelo produto:

```java
            session.whenEnded(ending);
            session.forgetEnding(ending);
            session.stop();

            assertEquals(0, told.get(),
                    "the session told a chart that had already let go");
```

Ele prova que `endings.remove` funciona — coisa que nunca esteve em dúvida — e dá licença para
crer que a limpeza está feita. É a definição de teste sem dentes: quebrar `ReplayDrop` não o faz
falhar.

**Correção.** Em `ReplayDrop.attach`, passar as duas soltas no mesmo `detach`:

```java
Runnable ending = holder::detachReplay;
holder.attachReplay(..., () -> { session.forget(follow); session.forgetEnding(ending); }, ...);
session.whenEnded(ending);
```

(guardando o `ending` numa variável, porque duas referências de método `holder::detachReplay` são
objetos diferentes e `remove` não acharia a primeira). E o teste tem de ir pelo `ReplayDrop`.

**Tentei refutar assim.** (a) Procurei se o `ChartHolder` se desregistra por outro caminho:
`detachReplay` e `close` só rodam o `Runnable` que receberam; não têm referência à sessão.
(b) Procurei se `stop()` limparia antes de rodar — roda primeiro e limpa depois
(`ReplaySession.java:770-775`), que é o correto para o caso normal e não ajuda aqui. (c) Verifiquei
se um segundo `drop` no mesmo gráfico limparia: `ChartHolder.attach` (`:290-291`) roda o `detach`
anterior, que de novo só solta o observador, e ainda acrescenta **outro** `ending` à lista. Nada
derruba o achado.

---

## MÉDIA

### B6-4. Abrir o transporte faz varredura recursiva de disco na EDT, duas vezes

`ReplayPanel.java:359-369` (dentro de `top()`, chamado do construtor, na EDT)

```java
        for (ReplayFeed each : ReplayFeed.available()) {
            feed.addItem(each);
        }

        ReplayFeed remembered = ReplayFeed.read(
                br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                        .get("replay.feed", null));
```

**Problema.** `ReplayFeed.read` (`ReplayFeed.java:273`) chama `available()` de novo. E
`available()` (`:110-126`) constrói uma `TickLibrary` por mercado × por `TickSource` e chama
`exported()`, que é um `Files.walk(folder, 4)` — varredura recursiva de quatro níveis
(`TickLibrary.java:309-334`). Nada disso é lembrado: o cache `KNOWN` (`ReplayFeed.java:140`) guarda
só o resultado de `sessions()`.

**Consequência.** Cada abertura da janela paga `2 × mercados × 2` varreduras de diretório na
thread da interface. E o javadoc de `KNOWN` (`:131-138`) promete o contrário — *"listing an
export's sessions costs about 50 [ms] ... So it is remembered"* — sobre um caminho que não é este.
Comentário que descreve uma proteção que não cobre o código ao lado.

**Correção.** Chamar `available()` uma vez e procurar o lembrado na lista já obtida; e lembrar
`available()` no mesmo mapa que `forget()` já limpa.

**Tentei refutar assim.** Verifiquei se `warm()` (`:205-217`) aqueceria isto — não: ele *chama*
`available()`, não o memoriza; só memoriza `sessions()`. Verifiquei se `exported()` tem cache
interno — não tem, monta `List<LocalDate>` do zero a cada chamada.

### B6-5. Nada reaquece o calendário depois de `forget()`; a próxima troca de feed paga o custo na EDT

`ReplayFeed.java:229-231` e `ReplayPanel.java:406`

```java
    public static void forget() {
        KNOWN.clear();
    }
```
```java
        java.util.NavigableSet<LocalDate> days = picked.sessions();
```

**Problema.** `followFeed()` roda na EDT (ouvinte do combo, `ReplayPanel.java:371`, e também do
construtor, `:372`). Depois de `SeriesCatalog.forget()` — que agora chama `ReplayFeed.forget()`
por `whenForgotten` — o `computeIfAbsent` de `sessions()` recalcula ali mesmo: `SeriesCatalog.open`
mais `Sessions.of` sobre a série inteira. O próprio javadoc mede: *"walking the six-year source for
its 1.494 sessions costs 39-102 ms"*, e diz que por isso `warm` preenche *"at startup off the
interface thread"*. Só que o esvaziamento não reagenda o `warm`.

**Consequência.** Importar um pregão ou reconstruir uma série faz a próxima troca de combo
congelar até um décimo de segundo por feed. Não é travamento, é dívida que a própria classe já
declarou não querer.

**Correção.** No ouvinte de `whenForgotten`, além de limpar, reenfileirar `warm()` no mesmo
executor de segundo plano que o `Launcher` usa (`Launcher.java:110`).

**Tentei refutar assim.** Verifiquei se o `Launcher` reaquece em algum outro momento — chama
`warm()` uma vez, no arranque. Verifiquei se `followFeed` roda fora da EDT — não, é ouvinte de
`JComboBox`.

### B6-6. As datas lembradas atropelam a correção que `followFeed` acabou de fazer

`ReplayPanel.java:226-230`, depois de `top()` (linha 164) já ter chamado `followFeed()`

```java
        LocalDate from = readDate("replay.from", LocalDate.now().minusDays(1));

        date.setDate(from);
        until.setDate(keepInWindow(from, readDate("replay.to", from),
                ReplayPreferences.windowDays()));
```

**Problema.** `followFeed()` promete, no javadoc (`:390-397`), mover as datas quando elas não
podem mais ser tocadas: *"Switching from six years of minutes to a tape of eight sessions leaves
both pickers holding a day that feed has never heard of; landing on the feed's LAST session is
where the reader was going anyway"*. No construtor ele roda **antes** dessas três linhas, que
gravam as datas lembradas por cima sem nenhuma consulta a `picked.sessions()`.

**Consequência.** Reabrindo o transporte num feed de ticks de 9 pregões com `replay.from` lembrado
de 2021, o calendário fica todo cinza e o campo segura uma data que aquele feed nunca teve. O
`settle` não corrige: ele só encaixa o `until` na janela em relação ao `date`
(`ReplayPanel.java:206-213`), sem olhar o conjunto jogável. `requestDay` também não confere.
Junto com B6-2, o resultado é pedir a sessão, receber um transporte com botão aceso e morto, e
nada explicando por quê.

**Correção.** Ou aplicar `followFeed()` depois de restaurar as datas, ou passar cada data lembrada
por `picker.accepts(...)` antes de escrevê-la.

**Tentei refutar assim.** Reli a ordem do construtor três vezes: `add(top(), NORTH)` na 164
(que chama `followFeed()` na 372), `onChange` na 215-216, `setDate` na 228-230. Procurei um
`followFeed()` posterior — não há. Procurei validação em `requestDay` (`:541-568`): confere só
"data ilegível" e "fim antes do começo".

### B6-7. Quatro javadoc colados no membro errado em `ReplaySession`, e o primeiro deles mente

`ReplaySession.java:316-331`

```java
    /**
     * @return the bars of that session
     *
     * <p><b>Synthetic, and seeded by the date</b> so the same day always replays
     * the same way — a replay that changed under the reader between two runs
     * would be useless for comparing decisions. Replaced the moment a real
     * loader exists; the rest of this class does not care which it gets.</p>
     */
    /**
     * @return the sessions before that date, oldest first
     ...
    private static List<LocalDate> sessionsBefore(LocalDate date, int howMany) {
```

**Problema.** O primeiro bloco descreve um método que não existe mais, e afirma o oposto do que a
classe faz hoje: o javadoc de `dayOf` (`:378-390`) conta que até 03/09/2026 o replay animava
velas **inventadas**, e que isso foi corrigido. O bloco órfão continua dizendo "Synthetic, and
seeded by the date" — e está grudado em `sessionsBefore`. Mesmo padrão em outros três pontos:

- `:511-526` — dois javadoc (um de `isRecorded`, um de `playing`) empilhados sobre `feedLabel()`;
  `playing()` (`:530`) fica sem nenhum.
- `:740-757` — o javadoc de `stop()` (*"Ends the session and hands every chart back to itself...
  The charts are told BEFORE the watchers are dropped"*) colado em `isStopped()`; `stop()`
  (`:761`) fica sem javadoc.
- `:165-170` — `@param instrument` / `@param date` de um construtor colados na constante
  `MOST_SESSIONS`, onde `@param` nem é tag válida.

E em `ReplayPanel.java:278-287`, dois javadoc empilhados sobre `stopSession()`, enquanto
`release()` — que é público e é o que a janela chama — não tem nenhum.

**Consequência.** É a convenção nº 1 da casa invertida: o comentário existe para ser o contexto de
quem lê depois, e aqui ele aponta para o membro errado e afirma um comportamento que foi removido.
Quem ler `sessionsBefore` acredita que o replay é sintético.

**Correção.** Apagar o bloco órfão de `:316-323` e mover os outros três para os membros que
descrevem.

**Tentei refutar assim.** Confirmei que `RandomWalkSeries` não é mais usado (só o import,
`:31`), ou seja o bloco realmente descreve código morto. Confirmei que o javadoc de `dayOf`
(`:380-386`) diz o contrário do bloco órfão, então não é redundância inofensiva: são duas
respostas opostas para a mesma pergunta, no mesmo arquivo.

### B6-8. A janela do replay é medida em dias de calendário e documentada em pregões

`ReplayPreferences.java:48-56` e `ReplayPanel.java:536`

```java
    /**
     * How many days a single replay may span.
     *
     * <p>Ten. A replay is watched, and watching is the slow way to look at a
     * market: ten sessions at sixty times real time is an hour and a half of
     * sitting there. ...</p>
     */
    public static final int DEFAULT_WINDOW = 10;
```
```java
        LocalDate furthest = from.plusDays(Math.max(1, maxDays) - 1L);
```

**Problema.** `keepInWindow` conta **dias de calendário**. `ReplaySession.sessionsIn` (`:362-376`),
do outro lado do mesmo ajuste, conta **pregões** — pula sábado e domingo, como o javadoc do
construtor diz (*"Weekends are skipped, so 'Monday to Monday' is six sessions and not eight"*).
Segunda + 9 dias corridos chega na quarta da semana seguinte: **8 pregões**, não dez.

**Consequência.** O ajuste chamado "quantos dias um replay pode cobrir" entrega 20% menos do que a
justificativa medida no javadoc afirma, e as duas pontas do mesmo número contam unidades
diferentes. O teste que cobre a janela (`ReplayRangeTest.java:103-105`) usa
`MONDAY.plusDays(windowDays())` — frouxo o bastante para passar com qualquer das duas leituras.

**Correção.** Ou contar pregões nos dois lados (caminhar com `sessionsIn` a partir de `from` e
pegar o `maxDays`-ésimo), ou corrigir o javadoc para dizer "dias corridos" e refazer a conta da
hora e meia.

**Tentei refutar assim.** Procurei uma conversão em algum lugar entre o `windowDays()` e o
`keepInWindow` — os únicos chamadores são `ReplayPanel.java:208` e `:229-230`, ambos passando o
valor cru.

### B6-9. Uma sessão construída depois de a janela ser descartada fica viva e nunca é fechada

`ReplayPanel.java:600-629`

```java
            @Override
            protected void done() {
                building = false;

                try {
                    session = get();

                    adopt(session);
```

**Problema.** `done()` adota o resultado sem perguntar se o painel ainda está em uso. `release()`
pode ter rodado no meio da construção — e roda: `ReplayWindow.java:71-73` chama `panel.release()`
em `windowClosed`, que segundo o comentário logo acima (`:63-66`) é o caminho que
`MainWindow.relaunch` percorre **a cada troca de idioma**. `release()` põe `session = null`, e
`done()` põe de volta uma `ReplaySession` nova, com a `TickLibrary` dela aberta, sobre um painel
que ninguém mais vê e que nunca vai chamar `stop()`.

Detalhe do mesmo lugar: `requestDay` (`:569-572`) para a sessão antiga mas **não** anula o campo,
então durante a construção `session` aponta para uma sessão já parada.

**Consequência.** Recurso que vaza — o `ExecutorService` de carga e as sessões residentes de tick
(o próprio comentário de `stop()` diz que três delas são 340 MB) ficam pendurados até o fim do
processo. É estreito (exige fechar/trocar idioma nos ~4 s da construção), mas é o único caminho da
área em que um `AutoCloseable` aberto aqui não é fechado por quem abriu.

**Correção.** Um campo `released` marcado em `release()` e conferido em `done()`; se estiver
marcado, `get().stop()` em vez de adotar. E anular `session` em `requestDay` logo após parar a
antiga.

**Tentei refutar assim.** Procurei um `cancel(true)` no worker ou um guarda de janela — não há
referência ao worker depois do `execute()`. Verifiquei se `stop()` seria chamado de outro lugar:
os únicos chamadores são `release()` e `requestDay`, ambos pelo campo que acabou de ser anulado.

---

## BAIXA

### B6-10. Código morto que ainda promete um carregador que já existe

`ReplaySession.java:31`, `:132-135`

```java
import br.com.jorge.reis.endeavourneo.ui.chart.RandomWalkSeries;
...
    /** The trading day this stands in for, until a real loader exists. */
    private static final LocalTime OPEN = LocalTime.of(9, 0);

    private static final int MINUTES = 565;
```

Nenhum dos três é usado (`grep` na área só acha as declarações). O javadoc de `OPEN` diz "until a
real loader exists" e o carregador existe desde 03/09, como o javadoc de `dayOf` (`:380-386`)
conta. `MINUTES = 565` é o número mágico que `endText()` foi corrigido para não usar (`:711-719`).
Apagar os três.

### B6-11. `DatePicker.setDate(null)` estoura, embora o construtor trate null

`DatePicker.java:114-116`

```java
    public void setDate(LocalDate date) {
        field.setText(date.format(TYPED));
    }
```

O construtor (`:95`) faz `initial == null ? LocalDate.now() : initial` — a classe já decidiu qual é
a resposta para null e o método público não a aplica. Hoje nenhum chamador passa null
(`keepInWindow` devolve null, mas o único uso, `ReplayPanel.java:229`, garante os dois argumentos
não nulos), então é dívida e não defeito.

### B6-12. Cor de erro fixa no código, metade seguindo o tema e metade não

`ReplayPanel.java:549` e `:561`

```java
            until.field().setBackground(new java.awt.Color(255, 235, 230));
```

Rosa claro fixo, enquanto o desfazer lê do tema (`:554`, `:566`:
`UIManager.getColor("TextField.background")`). Num tema escuro o campo de erro vira uma placa
clara. Compare com `HANDLE_GROUND` (`:75`), que é fixa **de propósito** e explica por quê — aqui
não há explicação, e o caso é o oposto: um campo de texto é justamente o que o tema sabe pintar.

### B6-13. Alocação por pintura nos glifos do transporte

`ReplayIcons.java:143` e `:176`

```java
                g.setStroke(new BasicStroke(1.4f));
...
            return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(), 90);
```

Mais os dois `int[]` que cada `fillPolygon` monta (`:44`, `:73-74`, `:81-82`, `:108-115` — oito
arrays só no `drag`). É caminho de pintura, e os ícones são repintados a cada `refresh()`, que o
relógio dispara 25 vezes por segundo. Nenhum deles varia: `BasicStroke`, a cor apagada e os
polígonos de tamanho fixo podem ser constantes por tamanho.

### B6-14. O calendário fala o idioma da máquina, não o da aplicação

`DatePicker.java:205-206` e `:218-219`

```java
        JLabel title = new JLabel(showing.getMonth()
                .getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + showing.getYear(),
...
                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
```

`Messages.setLocale` (`platform/Messages.java:141-153`) troca só o `ResourceBundle`; não mexe em
`Locale.getDefault()` — e o comentário dele explica que a ausência de fallback para o locale
padrão foi justamente o conserto de um defeito parecido. Trocando para inglês, o transporte fica
em inglês e o calendário continua com "setembro 2026" e as iniciais D S T Q Q S S. Texto de tela
decidido fora do bundle. O certo é `Messages.getLocale()` nas duas linhas.

### B6-15. `isRecorded()` não tem chamador no produto

`ReplaySession.java:534-544`. É público, tem o javadoc mais explícito da "uma regra só", e
`grep -rn "isRecorded" src/main` só encontra a declaração — os únicos chamadores estão em
`FirstSessionTest`. A regra existe no código e é observável só por teste; na tela quem responde é
`feedLabel()`. Ou o transporte usa isso para dizer ao leitor de onde vem a animação, ou o método é
API só de teste e devia ser pacote-visível como `residentTicks()` e `residentAt()` já são
(`:494`, `:507`). Ver também B6-1: do jeito que está, ele responde sobre `date` e não sobre o
pregão que está tocando.

---

## LIMPO

O que foi conferido e está certo, e como.

**Chaves de bundle — todas existem, nas duas línguas.** Extraí as 17 chaves usadas na área
(`replay.title, series, instrument, from, to, request, stop, back, forward, speed, noSession,
loading, nothing, badDate, badRange, dragHint, range`) e conferi contra
`src/main/resources/messages.properties` (linhas 237-281) e `messages_pt_BR.properties`. Todas
presentes. `rangeText()` (`ReplaySession.java:593-605`) passou mesmo pelo bundle:
`replay.range = {0} to {1}` / `= {0} a {1}`, e `Messages.get(String, Object...)`
(`platform/Messages.java:77-78`) usa `MessageFormat`, então os dois `{0}`/`{1}` são substituídos.
As chaves de `ReplayFeed.label()` (`navigator.ticks`, `navigator.tickSource.*`, `navigator.scale.*`)
existem (linhas 48-58) e ainda passam por `Messages.orElse`, que tem queda limpa. **Não achei texto
de tela em português ou inglês cravado no código** — os únicos literais são `"--:--:--"`,
`"▾"`, `"◀"`, `"▶"` (glifos, não frases) e as chaves de `Settings`.

**A "uma regra só" está fechada no sentido que o pedido apontou: ticks reais não entram num feed
de barras.** Segui os três caminhos por onde poderiam entrar. (1) A animação:
`ReplaySession.java:288`, `this.animation = feed.isTicks() ? new RecordedTicks(ticks, path) :
path;` — é a única coisa entregue à `ReplaySeries` (`:289`), e `path` (`:268-270`) só consulta
`SyntheticTicks`. Não há caminho que devolva `RecordedTicks` sob feed de barras. (2) A resposta:
`isRecorded()` (`:542-544`) testa `animation instanceof RecordedTicks`, ou seja pergunta à ligação
e não aos dois fatos com que a ligação foi montada — o teste que antes passava com a linha
revertida agora falharia. (3) O renko do gráfico: `playing()` (`:530-532`) devolve `null` sob feed
de barras, e `ChartHolder.attachReplay` (`ui/chart/ChartHolder.java:280-285`) chama
`canvas.setTickSource(ticks, true)` com o segundo argumento fixo em `true` — o comentário ali diz
que é para o canvas não ir buscar fonte de tick por conta própria. Os três fecham. O furo que
achei (B6-1) é o inverso: um feed de **ticks** que passa a animar com o inventado.

**O relógio tem um dono só, e é a EDT.** `ReplaySession` tem um único `Timer`
(`:312-313`, `FRAME = 40`, `setCoalesce(true)`), criado e parado só de `toggle`/`pause`/`stop`/
`tick`. `tick()` (`:778-788`) chama `advanceMarketTime(FRAME * speed)` e para o timer quando
`finished()`. O único outro caminho que mexe no estado é a interface (`step`, `seekFraction`,
`toggle`), e o único callback de outra thread — `ticks.onLoaded` (`:301`) — pula para a EDT com
`invokeLater` antes de tocar em qualquer coisa, com `preparing` marcado `volatile` (`:122`) e
justificado no javadoc. `announce()` (`:790-796`) itera sobre **cópia** da lista, então um
observador que se solta durante o aviso não quebra o laço; o mesmo em `stop()` (`:770`). Conferi
que `advance(bars)` e `seek(revealed() + bars)` em `step()` (`:678-686`) são simétricos, porque
`ReplaySeries.advance` também parte de `size()` (`domain/market/ReplaySeries.java:356-358`) — a
assimetria aparente não é defeito.

**Trabalho pesado da construção da sessão está fora da EDT, e o transporte diz que está.**
`requestDay` (`ReplayPanel.java:594-629`) marca `building`, chama `refresh()` — que desliga tudo e
troca o scrubber pela barra indeterminada (`:668-684`, `showLoading`) — e constrói a
`ReplaySession` dentro de um `SwingWorker.doInBackground`. `showLoading` (`:760-765`) só anima a
barra enquanto ela está à mostra, o que é o cuidado certo com um `JProgressBar` indeterminado.
Os problemas de EDT que achei (B6-4, B6-5) estão em outros caminhos, não neste.

**`DatePicker.onChange` realmente parou de empilhar.** `:149-178`: a troca do `Runnable` é
incondicional e a instalação do `DocumentListener` está atrás da guarda `listening`, que é campo
de instância. Um `DatePicker` acumula no máximo um listener por toda a vida. O teste
correspondente (`ReplayHousekeepingTest.java:123-150`) compara um picker configurado uma vez com
outro configurado três vezes em vez de assertar um número fixo — tem dentes, porque `setText`
dispara remove+insert e um número cravado esconderia isso.

**`ReplayFeed.forget` agora é chamado do produto.** `followTheCatalog()` (`ReplayFeed.java:181-192`)
registra `SeriesCatalog.whenForgotten(ReplayFeed::forget)` uma vez só, atrás de `following`
(`volatile`), a partir de `warm()`, que o `Launcher.java:110` roda no arranque. O javadoc do
`forget` conta com todas as letras que antes era chamado só de teste. Confirmado por `grep`: o
registro existe em `src/main`. O que sobra é B6-5, que é o reaquecimento, não o esvaziamento.

**Fechamento de recursos.** `ReplayFeed.available()` (`:112-125`) e `sessions()` (`:151-160`)
abrem `TickLibrary` e fecham em `finally` — conferido nos dois. `ReplaySession.stop()` (`:761-776`)
fecha `ticks`. `ReplayIcons.Painted.paintIcon` (`:137-151`) descarta o `Graphics2D` derivado em
`finally`. O único vazamento que achei é o de B6-9, que é uma corrida, e o de B6-3, que é de
referência e não de descritor.

**`ReplayWindow` e o ciclo de vida da janela.** `HIDE_ON_CLOSE` com `release()` pendurado em
`windowClosed` e não em `windowClosing` (`:68-74`) está certo e o comentário explica por quê:
`dispose()` da troca de idioma dispara só `closed`. Conferi que `ChartHolder`/`MainWindow` não têm
outro caminho para largar o painel.

**`ReplayTransfer`.** `javaJVMLocalObjectMimeType` é a escolha certa para carregar um objeto vivo
com timer e observadores; `getTransferData` confere o flavor antes de devolver; a construção do
flavor falha em tempo de classe com mensagem própria (`:43-50`). Sem achado.

**`ReplayPanel.adopt`.** A empurrada da velocidade (`:647-650`) está certa e o javadoc explica o
defeito que a motivou. Conferi que `chosenSpeed()` cai em 1 quando o combo está vazio, e que
`setSpeed` (`ReplaySession.java:621-625`) prende entre 1 e `FASTEST`.

**`keepInWindow`.** A lógica em si (`ReplayPanel.java:527-539`) está correta para dias corridos:
fim antes do começo sobe até o começo, fim longe demais volta para o limite, null quando qualquer
um é ilegível. O achado B6-8 é sobre a **unidade**, não sobre o cálculo. E o `settle`
(`:206-213`) com `invokeLater` resolve de verdade o `IllegalStateException` de mutação dentro da
notificação, com teste que o pega (`ReplayRangeTest.java:82-105`, que só passa porque
`invokeAndWait` carrega o throw de volta).
