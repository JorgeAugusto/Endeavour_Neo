# A6 — replay

**Lido integralmente** (9 arquivos de produção, 2.519 linhas):
`ReplayPanel.java` (745), `ReplaySession.java` (725), `DatePicker.java` (270),
`ReplayFeed.java` (253), `ReplayIcons.java` (189), `ReplayDrop.java` (95),
`ReplayPreferences.java` (93), `ReplayWindow.java` (79), `ReplayTransfer.java` (70).

**Testes lidos integralmente** (9 arquivos, 1.515 linhas):
`TickSourceChoiceTest` (303), `ReplayRangeTest` (182), `ReplaySessionTest` (178),
`LoadingTrackTest` (173), `FirstSessionTest` (159), `ReplayBase` (152),
`PlayableDaysTest` (149), `ReplayHandleTest` (129), `ReplayEndsTest` (90).

**Lido só para confirmar contrato ou chamador** (não auditado):
`ReplaySeries.java`, `RecordedTicks.java`, `TickLibrary.java`, `ChartHolder.java`
(attach/detachReplay), `ChartCanvas.java` (setTickSource, seriesGrew),
`MainWindow.java` (openReplay, ReplayDrop.enable), `Launcher.java` (warm),
`Messages.java`, `Settings.java`, `SeriesCatalog.java`, `TickSource.java`,
`messages.properties`, `messages_pt_BR.properties`.

**O que foi conferido:** o ciclo de vida inteiro (pedir, tocar, pausar, passo a
passo, arrastar a alça, parar, trocar de dia sem fechar, fechar a janela); quem
avança o replay e em que thread; o que a interface mostra quando o motor trava;
o formato salvo em `Settings` e a ida-e-volta de cada preferência; o calendário
contra dias sem pregão, feriados, bordas e fuso; o arrasto e a queda no gráfico;
a aritmética da velocidade; e cada um dos nove testes com a pergunta "que mutação
do código este teste NÃO pegaria?".

---

## Achados ALTA

### A6-1 — a velocidade escolhida nunca chega na sessão nova

**Onde:** `ReplayPanel.java:186-190`, `ReplayPanel.java:589-618`, `ReplaySession.java:148`

**Trecho:**
```java
// ReplayPanel.java:186
speed.setSelectedItem(rememberedSpeed());

speed.addActionListener(e -> br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
        .put("replay.speed", String.valueOf(speed.getSelectedItem())));
speed.addActionListener(e -> withSession(s -> s.setSpeed((Integer) speed.getSelectedItem())));
```
```java
// ReplayPanel.java:600, dentro do SwingWorker
protected void done() {
    building = false;

    try {
        session = get();

        session.watch(refresh);
    } catch (InterruptedException e) {
```
```java
// ReplaySession.java:148
private int speed = 1;
```

**Problema:** a velocidade só é levada à sessão pelo `ActionListener` do combo, ou
seja, **apenas quando o leitor mexe no combo**. `done()` não chama
`session.setSpeed(...)` em momento nenhum, e toda `ReplaySession` nasce com
`speed = 1`. Como o combo é lembrado entre execuções (`rememberedSpeed()`, e
`setSelectedItem` no construtor não dispara `ActionListener`), o estado normal
depois de reabrir o programa é: combo dizendo 60, sessão andando a 1.

**Consequência:** o leitor deixa o transporte em 60×, fecha o programa, reabre,
escolhe o dia e aperta Requisitar. O combo mostra **60** e o replay anda a **1×** —
nove horas e meia de pregão em nove horas e meia de relógio, em vez de nove
minutos e meio. Nada na tela diz que o número mostrado não é o número em uso. O
contorno é invisível: tem de clicar no combo e reescolher o mesmo valor (clicar no
item já selecionado dispara `actionPerformed`, `setSelectedItem` não).

**Correção:** em `done()`, logo depois de `session = get()`, aplicar o que o
transporte já mostra: `session.setSpeed((Integer) speed.getSelectedItem())`. Ou
passar a velocidade ao construtor de `ReplaySession`, que é onde o resto do estado
inicial já é decidido.

**Tentei refutar:** (a) procurei `setSpeed` no repositório inteiro — só existem a
definição em `ReplaySession.java:573`, a chamada do listener em
`ReplayPanel.java:190` e três chamadas em `ReplaySessionTest`. Não há outro lugar
que sincronize. (b) verifiquei se `JComboBox.setSelectedItem` dispara
`actionPerformed` — dispara só quando o item muda *e* já há listener; aqui o
`setSelectedItem` é a linha 186 e os listeners entram em 188/190. (c) verifiquei
se `refresh()` sincroniza — `refresh()` só *lê* de `session`, nunca escreve.
Nenhuma das três derrubou.

---

### A6-2 — o campo "Até" se corrige dentro da própria notificação do documento, e estoura

**Onde:** `ReplayPanel.java:195-205`, `DatePicker.java:107-109`, `DatePicker.java:138-154`

**Trecho:**
```java
// ReplayPanel.java:195
Runnable settle = () -> {
    LocalDate corrected = keepInWindow(date.date(), until.date(),
            ReplayPreferences.windowDays());

    if (corrected != null && !corrected.equals(until.date())) {
        until.setDate(corrected);
    }
};

date.onChange(settle);
until.onChange(settle);
```
```java
// DatePicker.java:107
public void setDate(LocalDate date) {
    field.setText(date.format(TYPED));
}
```
```java
// DatePicker.java:141
@Override
public void insertUpdate(javax.swing.event.DocumentEvent e) {
    onChange.run();
}
```

**Problema:** `settle` está registrado como `DocumentListener` do **próprio campo
"Até"** (linha 205). Quando o leitor termina de digitar uma data que precisa ser
corrigida — uma data anterior ao início, ou além de `windowDays` — `settle` roda
dentro de `insertUpdate` do documento de `until` e chama `until.setDate(...)`, que
é `field.setText(...)`, que é `AbstractDocument.replace` → `writeLock()`. O
`writeLock` de um documento que está notificando lança
`IllegalStateException("Attempt to mutate in notification")`. É a proteção do
próprio Swing contra exatamente isso.

Pelo campo "De" não estoura — ali `settle` muda um documento **diferente** — e é
por isso que o defeito passa despercebido em metade dos caminhos.

**Consequência:** o leitor digita `01/01/2020` em "Até" com "De" em setembro de
2026. O último caractere digitado joga uma `IllegalStateException` na EDT: um
rastro de pilha no console e **a correção nunca acontece**. O campo fica com a
data invertida, e a única coisa que segura o erro depois disso é o `if` de
`requestDay()` (linha 534), que pinta o campo de rosa. Ou seja: o recurso que
existe justamente para "assentar o campo em vez de dizer não" (o javadoc de
`keepInWindow`, linhas 502-514) não funciona no campo onde ele mais importa — e o
teto de `windowDays`, que é a defesa contra pedir 250 pregões, também não é
aplicado por esse caminho.

**Correção:** adiar a correção para fora da notificação:
`SwingUtilities.invokeLater(() -> until.setDate(corrected))` dentro de `settle`, ou
mover o `settle` do "Até" para um `FocusListener`/`ActionListener` do campo em vez
do `DocumentListener`. `invokeLater` é o mínimo e não muda o desenho.

**Tentei refutar:** (a) chequei se `keepInWindow` devolve `null` durante a digitação
parcial — devolve, e é isso que salva os caracteres intermediários; mas o
caractere que **completa** uma data válida-e-fora-de-faixa passa pelo `null` e
chega no `setDate`. (b) chequei se `setDate` é chamado com o mesmo texto (o `if`
`!corrected.equals(until.date())` evitaria) — não é: por definição só entra ali
quando o valor tem de mudar. (c) chequei se `JTextField` usa `PlainDocument` com
algum filtro que adie a notificação — não usa; e o `notifyingListeners` de
`AbstractDocument.fireInsertUpdate` está ativo durante a chamada. (d) chequei a
construção (linhas 215-219) — ali `date.setDate` mexe no documento do `until`,
que é outro documento, e não estoura. Nenhuma derrubou.

---

### A6-3 — o replay fica "tocando" para sempre quando não há caminho para a barra (lado da interface)

**Onde:** `ReplaySession.java:706-716`, `ReplayPanel.java:679-706`
(motor confirmado em `ReplaySeries.java:173`, área A2)

**Trecho:**
```java
// ReplaySession.java:706
private void tick() {
    live.advanceMarketTime((long) FRAME * speed);

    if (live.finished()) {
        // The day is over. Stopping here rather than letting the timer run
        // on an unchanging series keeps the play button honest.
        timer.stop();
    }

    announce();
}
```
```java
// ReplayPanel.java:679
play.setIcon(ready && session.isPlaying()
        ? ReplayIcons.pause(18) : ReplayIcons.play(18));
```

**Problema:** confirmado o achado da A2. Em `ReplaySeries.advanceMarketTime`, quando
`startForming()` devolve `false` porque `ticks.pathFor(...)` veio `null` — o que
acontece quando não há ticks gravados **e** o leitor desligou os ticks sintéticos
(`ChartPreferences.syntheticTicks()`, consultado em `ReplaySession.java:255`) —, o
código faz `owed = 0; return;` **sem avançar `completed`**. E `finished()` é
`completed >= day.size() && path == null`, que continua `false`.

Do lado da interface a conta é esta: `tick()` nunca vê `finished()`, então
`timer.stop()` nunca roda; `isPlaying()` continua `true`; `refresh()` desenha o
ícone de **pausa** (linha 679-680), ou seja, o transporte diz "estou tocando"; o
relógio (`clockText()`, linha 699) e o scrubber (linha 704) ficam parados no mesmo
valor; e `announce()` continua chamando os 25 `refresh()` por segundo e os
`seriesGrew()` de cada gráfico, para sempre, sem nada mudar.

**Consequência:** o leitor aperta play, o relógio fica congelado em 09:00:00, o
botão continua mostrando "pausa", nenhuma mensagem aparece, e a única saída é o
botão Parar. Uma CPU inteira gasta refazendo a mesma dobra 25 vezes por segundo,
indefinidamente. O transporte não tem **nenhum** detector de parada: não existe
em `ReplayPanel` nada que compare o relógio de um `refresh()` com o do anterior.

**Correção:** duas metades. No motor (A2): `startForming()` deve avançar
`completed` quando não há caminho — a barra aparece inteira, que é a "honest
picture" que o próprio comentário de `ReplaySeries.java:196-200` promete e não
entrega. Na interface (aqui): `tick()` deve reparar que o relógio não andou e
parar o `Timer` dizendo por quê — `Messages.get("replay.stalled")`, chave nova, na
mesma linha do `clock` que hoje mostra `replay.nothing`. As duas: sem a segunda, um
motor futuro que trave por outro motivo trava a interface do mesmo jeito.

**Tentei refutar:** (a) procurei um caminho em `ReplayPanel` que perceba a
imobilidade — `refresh()` só reflete estado, não compara com o anterior;
`building`, `waiting` (`isPreparing`) e `nothingToPlay` (`isEmpty`) são os três
estados que o transporte sabe dizer, e nenhum é este. (b) chequei se
`nothingToPlay` cobre — `isEmpty()` é `live.total() == 0`, e aqui `total()` é o
dia inteiro, diferente de zero. (c) chequei se o `Timer` tem `setRepeats(false)` ou
algum guarda — não tem; só `setCoalesce(true)` (linha 276). (d) chequei se
`syntheticTicks()` falso é alcançável pela interface — é uma preferência do
gráfico, portanto sim. Nenhuma derrubou.

---

### A6-4 — "carregando ticks..." não tem saída quando a leitura falha

**Onde:** `ReplaySession.java:263-273`, `ReplayPanel.java:653`, `ReplayPanel.java:682-690`

**Trecho:**
```java
// ReplaySession.java:263
this.preparing = ticks.has(date);

ticks.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(() -> {
    if (preparing && ticks.at(this.date) != null) {
        preparing = false;

        announce();
    }
}));

ticks.request(date);
```
```java
// ReplayPanel.java:684
if (waiting) {
    clock.setText(Messages.get("replay.loading"));
    ends.setText("");
    chip.setEnabled(true);

    return;
}
```

**Problema:** `preparing` só volta a `false` numa condição conjunta: o aviso tem de
chegar **e** `ticks.at(date)` tem de ser não-nulo. Em `TickLibrary.queue` (linhas
208-223, fora da área) há dois caminhos em que isso nunca acontece:

1. a leitura lança `IOException` — o `catch` chama `whenLoaded.run()`, mas
   `resident` continua vazio, então `ticks.at(date)` é `null` e o `if` não entra;
2. o arquivo existia quando o construtor perguntou (`ticks.has(date)` foi `true`,
   linha 263) e sumiu, foi renomeado ou ficou ilegível antes de a tarefa rodar —
   o `if (has(day))` de dentro da tarefa dá falso e `whenLoaded.run()` **nem é
   chamado**.

Não há timeout, nem contador de tentativas, nem estado de falha. `isPreparing()`
fica `true` para sempre.

**Consequência:** o transporte fica em "carregando ticks..." indefinidamente, com
play, back, forward, scrubber e velocidade desabilitados (linha 663-665) e com os
campos de feed e data **também** congelados (linha 675-677, porque `ready` é
verdadeiro). A única coisa clicável é Parar. Um arquivo de tape corrompido — que é
justamente o caso que a A2 já viu na leitura — deixa o replay assim, sem uma
palavra dizendo o que houve.

**Correção:** dar ao `preparing` uma saída de falha. O mínimo é o próprio
`whenLoaded` fechar a espera quando `ticks.has(date)` já não é verdade ou quando a
tentativa terminou sem resultado, e a interface mostrar `replay.tickError` em vez
de `replay.loading`. O `TickLibrary` precisa dizer "tentei e não deu" em vez de só
"chegou alguma coisa".

**Tentei refutar:** (a) chequei se o `request(date)` enfileira o dia anterior e o
seguinte e se algum deles destrava — destrava não: o `if` exige
`ticks.at(this.date)`, o dia exato. (b) chequei se `toggle()` sai da espera —
`toggle()` retorna sem fazer nada quando `preparing` (linha 591-595). (c) chequei
se `FirstSessionTest.settle` cobre — cobre só o caminho feliz, com um deadline de
5 s no *teste*, não no produto. (d) chequei se `stop()` limpa — limpa a sessão
inteira, que é a saída de emergência, não um tratamento. Nenhuma derrubou.

---

### A6-5 — varredura da série inteira dentro do `ActionListener` do combo

**Onde:** `ReplayPanel.java:360-361`, `ReplayPanel.java:388-402`, `ReplayFeed.java:149-172`

**Trecho:**
```java
// ReplayPanel.java:360
feed.addActionListener(e -> followFeed());
followFeed();
```
```java
// ReplayPanel.java:395
java.util.NavigableSet<LocalDate> days = picked.sessions();
```
```java
// ReplayFeed.java:150
return KNOWN.computeIfAbsent(saved(), key -> {
    if (isTicks()) {
        ...
    }

    try {
        return br.com.jorge.reis.endeavourneo.domain.market.Sessions.of(
                SeriesCatalog.open(series).orElse(null));
```

**Problema:** `sessions()` abre a série e **percorre todas as barras**
(`Sessions.of` itera de 0 a `size()-1` convertendo cada carimbo em `LocalDate`).
O próprio javadoc da classe diz, sobre o método que faz isso em lote: *"**Never on
the interface thread.**"* (`ReplayFeed.java:176-178`). Mas o caminho de cache-miss
do `sessions()` roda exatamente aí: dentro do `ActionListener` do combo, na EDT.

O `warm()` do `Launcher.java:88-89` fecha o caso comum, e não fecha estes:

- uma série importada depois do arranque aparece no combo — `available()` relê
  `SeriesCatalog.names()` a cada chamada — mas **não** está em `KNOWN`, porque
  `ReplayFeed.forget()` não é chamado de lugar nenhum em `src/main` (ver A6-11).
  Escolher essa série no combo varre 1 milhão de barras na EDT;
- se o transporte abre antes de o `warm()` terminar, `computeIfAbsent` na mesma
  chave **bloqueia** a EDT até a tarefa de fundo acabar aquela chave.

É a regra da casa, literal: varredura de série inteira em manipulador de evento é
ALTA. E 1 milhão de barras é o caso normal, não o extremo.

**Consequência:** o combo trava a janela inteira ao mudar de série — décimos de
segundo hoje, e proporcional ao arquivo depois. O leitor vê o transporte
congelado sem nada dizendo por quê, que é a mesma coisa que o `building`/`loading`
foi construído para evitar em `requestDay()` e que aqui foi esquecido.

**Correção:** o `followFeed()` deve fazer o que o `requestDay()` já faz — mandar
para um `SwingWorker` e mostrar o estado de espera —, ou `sessions()` deve devolver
`null` num miss e disparar o cálculo em segundo plano, deixando o calendário no
estado "qualquer dia útil" (que o `DatePicker` já sabe representar) até a resposta
chegar.

**Tentei refutar:** (a) medi a intenção contra o código: o javadoc do `warm()` diz
que ele existe para "the first calendar opens instantly", o que confirma que o
autor sabia do custo — mas o `sessions()` não ficou protegido, ficou apenas
esquentado. (b) chequei se `Launcher` espera o `warm()` antes de deixar abrir o
transporte — não espera; é um `jobs.submit` solto, e o menu já está disponível.
(c) chequei se o `KNOWN` é populado por outro caminho na EDT antes — só pelo
próprio `sessions()`. Nenhuma derrubou.

---

### A6-6 — teste que compara um valor consigo mesmo

**Onde:** `ReplaySessionTest.java:144-153`

**Trecho:**
```java
@Test
@DisplayName("dragging to the far end finishes the day")
void seekingToTheEnd() {
    ReplaySession replay = session();

    replay.seekFraction(1.0);

    assertEquals(1.0, replay.progress());
    assertEquals(replay.series().size(), replay.series().size());
}
```

**Problema:** a segunda asserção é uma tautologia. `assertEquals(x, x)` passa com
qualquer implementação de `size()`, inclusive uma que devolva sempre zero,
sempre `Integer.MAX_VALUE`, ou um valor aleatório desde que estável dentro da
chamada. Não afirma nada. O `@DisplayName` promete "finishes the day", que é
justamente o que ficou sem verificação: **ninguém checa que arrastar até a ponta
revela o dia inteiro**, nem que `finished()` passou a ser verdadeiro.

**Consequência:** licença falsa. Uma mutação em `seekFraction` que revele
`size()-1` barras em vez de `size()` — o clássico off-by-one da ponta — passa por
este teste; `progress()` devolve `(size()-origin)/playable`, que com uma barra a
menos daria 0,998 e **falharia** na primeira asserção, sim — mas uma mutação em
`clamp` que trave o topo em `origin` (replay que não revela nada) faz `progress()`
devolver 1.0 quando `playable <= 0` e passa nas duas.

**Correção:** trocar por o que a segunda linha quis dizer:
`assertEquals(replay.series().total(), replay.series().size())` — ou, já que
`total()` não é exposto por `ReplaySession`, comparar contra a contagem de barras
que o `barsOf` de `ReplayRangeTest` já usa, e acrescentar
`assertTrue(replay.series() instanceof ReplaySeries s && s.finished())`.

**Tentei refutar:** procurei se algum outro teste da área cobre "arrastar até a
ponta revela o dia inteiro". `ReplayRangeTest.barsOf` (linhas 62-66) usa
`seekFraction(1.0)` seguido de `size()`, mas usa o resultado como **unidade de
medida** (`5 * oneDay == wholeWeek`), nunca compara com o total real do arquivo —
uma mutação que revelasse metade de cada dia manteria todas as razões e passaria.
Não derrubou.

---

### A6-7 — o teto de `MOST_SESSIONS` nunca é exercido pelo teste que diz exercê-lo

**Onde:** `ReplayRangeTest.java:173-181`, com `ReplayBase.java:70-84`

**Trecho:**
```java
// ReplayRangeTest.java:173
@Test
@DisplayName("a mistyped year asks for a capped number of sessions, not a decade")
void thereIsACap() {
    int oneDay = barsOf(over(MONDAY, MONDAY));
    int tenYears = barsOf(over(MONDAY, MONDAY.plusYears(10)));

    assertTrue(tenYears <= ReplaySession.MOST_SESSIONS * oneDay,
            "the cap did not hold: " + tenYears / oneDay + " sessions");
}
```
```java
// ReplayBase.java:70
LocalDate day = around.minusDays(20);

for (int i = 0; i < 40; i++, day = day.plusDays(1)) {
```

**Problema:** duas coisas erradas ao mesmo tempo, e cada uma sozinha já bastaria.

Primeira: **é um teto puro** (`<=`), exatamente o padrão que esta auditoria já
pegou duas vezes. O defeito que interessa aqui é de piso *e* de teto — o teto
precisa segurar, mas também precisa **ser alcançado**, senão a garantia é
vazia.

Segunda, e pior: **o fixture não tem dados para chegar perto do teto**.
`ReplayBase.at` escreve 40 dias corridos em torno da data, o que dá cerca de 28
pregões. `barsOf(over(MONDAY, MONDAY.plusYears(10)))` percorre 250 dias no
`sessionsIn`, mas só ~24 deles caem dentro do arquivo; o resto devolve
`PriceSeries.empty()`. Então `tenYears / oneDay ≈ 20`, e a asserção compara 20
contra 250. Passa por folga de mais de dez vezes — e passaria com folga se
`MOST_SESSIONS` valesse 1.000, ou se **o `&& days.size() < MOST_SESSIONS` do
`sessionsIn` (`ReplaySession.java:324`) fosse simplesmente apagado**. Essa é a
mutação: remover o teto inteiro do produto não quebra o teste que existe para
prová-lo.

**Consequência:** licença falsa sobre a única proteção contra um ano digitado
errado pedir sessenta mil pregões. E a proteção importa: `A6-2` mostra que o
`keepInWindow`, a outra defesa, não chega a rodar no campo "Até".

**Correção:** testar o corte onde ele acontece, não pela contagem de barras.
Tornar `sessionsIn` visível ao pacote e afirmar
`assertEquals(MOST_SESSIONS, sessionsIn(MONDAY, MONDAY.plusYears(10)).size())` —
igualdade, não teto, e independente do tamanho do fixture.

**Tentei refutar:** (a) contei os pregões do fixture: `around.minusDays(20)` mais
40 dias corridos = 40 dias, menos 5 ou 6 fins de semana ≈ 28 dias úteis, dos
quais os que ficam **depois** de `MONDAY` são ~20. (b) chequei se `dayOf` para um
dia fora do arquivo devolve algo não vazio — `SegmentedSeries.of` com um segmento
sem barras devolve vazio, e o `parts.add` acrescenta uma série de tamanho zero.
(c) chequei se algum outro teste toca `MOST_SESSIONS` — só este
(`grep` no repositório inteiro). Nenhuma derrubou.

---

## Achados MÉDIA

### A6-8 — o "até onde vai" do transporte é inventado, e o fixture foi feito para casar com a invenção

**Onde:** `ReplaySession.java:117-120`, `ReplaySession.java:651-659`, `ReplayBase.java:48-55`

**Trecho:**
```java
// ReplaySession.java:117
/** The trading day this stands in for, until a real loader exists. */
private static final LocalTime OPEN = LocalTime.of(9, 0);

private static final int MINUTES = 565;
```
```java
// ReplaySession.java:652
public String endText() {
    if (!isRange()) {
        return OPEN.plusMinutes(MINUTES - 1L).format(DateTimeFormatter.ofPattern("HH:mm"));
    }
```
```java
// ReplayBase.java:55
private static final int MINUTES = 565;
```

**Problema:** `endText()` é uma constante: 09:00 + 564 minutos = **18:24**, sempre,
para qualquer série e qualquer dia. O comentário do `OPEN` ainda diz *"until a
real loader exists"* — e o loader existe desde 03/09/2026, como o javadoc de
`dayOf` (linhas 336-348) narra em detalhe. `dayOf` lê a série de verdade; `endText`
continua no mundo anterior.

O que fecha o círculo é o fixture: `ReplayBase` foi escrito com o **mesmo** 565 e a
**mesma** abertura às 09:00, com o comentário "A test that asserts the clock reads
the close is asserting against THIS". Então
`ReplaySessionTest.theClockIsTheSessionsClock` (linha 140) compara `endText()` com
`clockText()` e casa — porque os dois lados são a mesma constante escrita duas
vezes, não porque o transporte leu o dado.

**Consequência:** num pregão que fechou mais cedo (véspera de feriado, dia de
circuit breaker, meio pregão de 30/12), ou numa série de 5 minutos, o transporte
diz que o replay vai até 18:24 e o relógio para antes. Num intervalo cujo último
dia é sábado ou feriado, o `endText()` anuncia uma data em que nada será tocado.

**Correção:** `endText()` deve ler `live` — o carimbo da última barra do trecho
jogável — em vez de somar constantes. E o fixture deve parar de espelhar a
constante: um dia mais curto no fixture faz o teste ganhar dentes.

**Tentei refutar:** procurei se `MINUTES`/`OPEN` são usados em algum lugar que os
justifique como configuração — são usados só em `endText()` (duas vezes, linhas
654 e 658). E procurei se o `ends` da interface é corrigido depois — não é;
`ReplayPanel.java:700` copia `session.endText()` cru. Não derrubou.

---

### A6-9 — a cor de erro é fixa e some no tema escuro

**Onde:** `ReplayPanel.java:537-538`, `ReplayPanel.java:549-550`

**Trecho:**
```java
until.field().setToolTipText(Messages.get("replay.badRange"));
until.field().setBackground(new java.awt.Color(255, 235, 230));
```
```java
date.field().setToolTipText(Messages.get("replay.badDate"));
date.field().setBackground(new java.awt.Color(255, 235, 230));
```

**Problema:** um rosa quase branco, cravado no código, nos dois lugares. A volta ao
normal usa `UIManager.getColor("TextField.background")` (linhas 543 e 555), ou
seja: o autor sabia que a cor certa vem do tema, e só a **de erro** ficou fora.
No tema escuro do FlatLaf a tinta do campo é clara — texto claro sobre fundo
`(255,235,230)` é ilegível. A própria classe tem um bloco de doze linhas
(`HANDLE_GROUND`, linhas 61-75) explicando por que uma cor fixa naquele caso é
deliberada e por que a primeira tentativa, que virou "a stain" no tema noturno,
foi desfeita. Este caso é a mesma mancha, no campo em que o leitor tem de ler o
que digitou.

**Consequência:** ao errar a data no tema escuro, o campo fica branco e o texto
some. O leitor vê um campo em branco onde estava a data dele.

**Correção:** uma constante derivada do tema — misturar
`UIManager.getColor("Component.error.borderColor")` com o fundo do campo, ou usar
`Component.error.focusedBorderColor` como **borda** em vez de trocar o fundo.

**Tentei refutar:** conferi se o FlatLaf devolve algo diferente por tema para
`TextField.background` — devolve, e é por isso que a linha de reset funciona nos
dois. Não derrubou.

---

### A6-10 — um gráfico fechado nunca sai da lista de "avisar quando acabar"

**Onde:** `ReplayDrop.java:84-93`, `ReplaySession.java:661-666`

**Trecho:**
```java
// ReplayDrop.java:84
Runnable follow = () -> holder.canvas().seriesGrew();

holder.attachReplay(session.name() + " " + session.rangeText(),
        session.series(), () -> session.forget(follow), session.playing(),
        session.feedLabel());

session.watch(follow);
session.whenEnded(holder::detachReplay);
```
```java
// ReplaySession.java:662
public void whenEnded(Runnable ending) {
    if (ending != null) {
        endings.add(ending);
    }
}
```

**Problema:** os **watchers** têm como sair (`forget`, e o `detach` passado ao
`attachReplay` faz exatamente isso). Os **endings** não têm: `ReplaySession` não
expõe nenhum `forgetEnding`, e `ChartHolder.close()` (linha 569) só roda o
`detachReplay`, que desfaz o *watcher*. Então cada gráfico em que a sessão já foi
solta fica preso ao `endings` até a sessão parar.

Duas consequências, uma certa e uma latente:

- **certa:** o gráfico fechado continua alcançável a partir da sessão viva — o
  `ChartHolder`, o `ChartCanvas` e a série que ele guardava em `beforeReplay`. Numa
  sessão com histórico de 30 dias isso é o gráfico inteiro segurado por um
  `Runnable`. E, ao parar, `detachReplay()` roda sobre um gráfico já fechado:
  `canvas.setSeries(beforeReplay)` e `retitle()` num componente descartado.
- **latente:** se dois `ReplaySession` chegarem a existir ao mesmo tempo, parar a
  **antiga** arranca o gráfico da **nova** — porque o `endings` da antiga ainda
  aponta para o mesmo `holder`, e `ChartHolder.attach` guarda `beforeReplay` "only
  the FIRST time". Hoje isso é inalcançável pela interface (o botão Requisitar
  fica desabilitado enquanto existe sessão, `ReplayPanel.java:675-677`), mas é uma
  costura que só o acaso segura.

**Correção:** um `forgetEnding(Runnable)` em `ReplaySession`, e o `detach` passado
ao `attachReplay` desfazendo os dois: `() -> { session.forget(follow);
session.forgetEnding(ending); }` — guardando o `ending` numa variável em vez de
passar a referência de método direto, que é irrepetível.

**Tentei refutar:** (a) chequei `ChartHolder.close()` — roda `detachReplay.run()`,
que é o desfazer do watcher, e mais nada. (b) chequei se `stop()` limpa antes de
rodar — `endings.clear()` acontece **depois** do laço (linha 702), então o
`detachReplay()` do gráfico morto roda mesmo. (c) chequei se a queda repetida no
mesmo gráfico multiplica watchers — não multiplica, o `attach` desfaz o anterior;
mas multiplica endings, e o segundo é inofensivo porque `detachReplay()` sai cedo
com `beforeReplay == null`. Só a parte dos endings sobreviveu.

---

### A6-11 — o cache de dias jogáveis nunca é esvaziado em produção, e o conjunto escapa mutável

**Onde:** `ReplayFeed.java:140`, `ReplayFeed.java:149-172`, `ReplayFeed.java:195-198`

**Trecho:**
```java
private static final Map<String, NavigableSet<LocalDate>> KNOWN = new ConcurrentHashMap<>();
```
```java
/** Drops what is remembered, for when the series on disk change. */
public static void forget() {
    KNOWN.clear();
}
```

**Problema:** duas coisas no mesmo lugar.

1. `forget()` **não é chamado de lugar nenhum em `src/main`** — só de
   `PlayableDaysTest` (linhas 48 e 144). O javadoc diz "for when the series on
   disk change", e é exatamente esse momento que não existe. Importar um pregão
   novo, exportar um tape novo, ou reconstruir a série: o calendário continua
   cinzento nos dias novos até o programa ser reaberto. E o cache é chaveado só
   por `saved()` (`"series:winfull-1m"`), sem nada do arquivo — nem tamanho, nem
   data de modificação.
2. `sessions()` devolve o `NavigableSet` **do cache**, sem cópia nem
   `unmodifiableNavigableSet`. `ReplayPanel.followFeed` passa essa referência viva
   para dois `DatePicker` (linhas 402-403), que a guardam em campo. Qualquer
   `remove`/`add` a partir dali corrompe o cache global. `PlayableDaysTest`
   ainda **fixa** esse contrato: `assertTrue(first == again, ...)` (linha 142).

**Correção:** chamar `forget()` onde o catálogo é invalidado (`SeriesCatalog.forget`
já é o ponto), e devolver `Collections.unmodifiableNavigableSet(...)` — o teste de
identidade vira um teste de igualdade, que é o que ele quer dizer de qualquer
jeito.

**Tentei refutar:** procurei chamadas de `forget` no repositório inteiro
(`grep -rn "ReplayFeed.forget"`) — dois resultados, ambos em teste. E procurei se
`DatePicker.setSessions` copia — `this.sessions = days`, sem cópia
(`DatePicker.java:120-122`). Não derrubou.

---

### A6-12 — abrir o transporte varre o disco duas vezes na EDT

**Onde:** `ReplayPanel.java:348`, `ReplayPanel.java:352-354`, `ReplayFeed.java:96-129`

**Trecho:**
```java
for (ReplayFeed each : ReplayFeed.available()) {
    feed.addItem(each);
}

ReplayFeed remembered = ReplayFeed.read(
        br.com.jorge.reis.endeavourneo.platform.Settings.workspace()
                .get("replay.feed", null));
```

**Problema:** `ReplayFeed.read(String)` percorre `available()` de novo
(`ReplayFeed.java:240`). E `available()` não é barato: chama
`SeriesCatalog.names()`, que é um `Files.walk` com `MarketFile::isSeries`
**abrindo o cabeçalho de cada arquivo**; e depois, para cada mercado × cada
`TickSource`, cria uma `TickLibrary` e chama `exported()`, que é outro
`Files.walk` com `source.sessionIn(file)` **abrindo o cabeçalho de cada arquivo de
tick**. Tudo isso duas vezes, no construtor do `ReplayPanel`, que roda no
`ActionListener` do menu (`MainWindow.openReplay`).

Hoje o acervo de ticks tem oito sessões e isso é rápido. A nota do projeto diz que
o acervo completo custa R$ 14 mil e é o passo seguinte; com ele, isso é uma
travada visível toda vez que a janela é aberta.

**Correção:** chamar `available()` uma vez e procurar o lembrado na lista já obtida
(`for (ReplayFeed each : feeds) if (each.saved().equals(stored))`), e mandar a
lista inteira para um `SwingWorker` com o combo desabilitado, no mesmo padrão que
`requestDay()` já usa.

**Tentei refutar:** chequei se `SeriesCatalog.names()` tem cache — não tem, é
`namesIn(folder())` direto (`SeriesCatalog.java:206-208`). E chequei se
`MarketFile.isSeries` só olha o nome — não, abre o arquivo. Não derrubou.

---

### A6-13 — a sessão que não constrói não diz nada a ninguém

**Onde:** `ReplayPanel.java:606-616`

**Trecho:**
```java
} catch (java.util.concurrent.ExecutionException e) {
    // A session that will not build leaves the transport with
    // none, which it already knows how to show. Better than a
    // window of prices that came from nowhere.
    session = null;
}
```

**Problema:** a exceção é engolida inteira — não vai para o console da aplicação
(que existe: `MainWindow.getConsole().write(...)`), não vira mensagem, não deixa
rastro. O comentário defende **não mostrar preços inventados**, que é certo, e
conclui daí que não se deve dizer nada, que não segue.

**Consequência:** o leitor escolhe a data, aperta Requisitar, vê a barra de
progresso por quatro segundos e o transporte volta exatamente ao estado anterior,
como se o botão não tivesse funcionado. Sem nenhuma pista do motivo — e a pista
existe, está dentro do `ExecutionException`.

**Correção:** escrever a causa no console e pôr o motivo no `clock`, do jeito que
`replay.nothing` já faz para o caso "nenhum pregão nesse recorte". Chave nova,
`replay.failed`.

**Tentei refutar:** procurei um `UncaughtExceptionHandler` ou um log que pegasse
isso — o `get()` embrulha a causa e o `catch` a descarta antes de qualquer
handler; e o console da aplicação captura `System.out`
(`Launcher.java:97`), para onde nada é escrito aqui. Não derrubou.

---

### A6-14 — o javadoc da janela promete o contrário do que o código faz

**Onde:** `ReplayWindow.java:34-37`, `ReplayWindow.java:56-66`

**Trecho:**
```java
 * <p>It is hidden rather than disposed when closed, so the session survives:
 * closing the transport by mistake in the middle of a replay would otherwise
 * throw away the day and the position in it.</p>
```
```java
addWindowListener(new java.awt.event.WindowAdapter() {

    @Override
    public void windowClosing(java.awt.event.WindowEvent e) {
        panel.release();
    }
});
```

**Problema:** o javadoc da classe diz que fechar preserva a sessão *"so the session
survives: closing the transport by mistake in the middle of a replay would
otherwise throw away the day and the position in it"*. O `windowClosing` chama
`panel.release()`, que faz `session.stop()` — para o timer, devolve os ticks,
solta todos os gráficos e zera o campo. Ou seja: fechar por engano no meio de um
replay **joga fora exatamente o dia e a posição** que o javadoc diz estarem
protegidos. `HIDE_ON_CLOSE` preserva o que foi *digitado*, não a sessão.

**Consequência:** para o leitor, nada quebra — o comportamento é defensável e o
comentário logo acima do listener (linhas 56-59) o justifica bem. O que quebra é o
próximo leitor do código: dois textos na mesma classe dizendo o contrário um do
outro, e o de cima é o que aparece no javadoc gerado.

**Correção:** corrigir o javadoc da classe — o que sobrevive ao fechar é o
formulário (feed, datas, velocidade), não a sessão.

**Tentei refutar:** chequei se `windowClosing` deixa de disparar em `HIDE_ON_CLOSE`
— dispara antes de esconder; é o `windowClosed` que não dispara sem `dispose()`.
Não derrubou.

---

### A6-15 — arrastar o scrubber com o replay tocando: o relógio disputa a alça

**Onde:** `ReplayPanel.java:207-211`, `ReplayPanel.java:702-706`

**Trecho:**
```java
scrubber.addChangeListener(e -> {
    if (!adjusting && session != null) {
        session.seekFraction(scrubber.getValue() / 1000.0);
    }
});
```
```java
if (ready) {
    adjusting = true;
    scrubber.setValue((int) Math.round(session.progress() * 1000));
    adjusting = false;
}
```

**Problema:** o `back` e o `forward` pausam antes de mexer
(`ReplayPanel.java:170-177`: `s.pause(); s.step(-10);`). O scrubber não. Com o
replay tocando, cada frame do `Timer` reescreve a posição da alça 25 vezes por
segundo enquanto o leitor a arrasta: cada `ChangeEvent` do arrasto busca uma
posição, o `announce()` seguinte chama `refresh()`, e o `refresh()` empurra a alça
de volta para onde o relógio acha que está.

**Consequência:** a alça treme e escapa do ponteiro; o leitor arrasta para 14:00 e
ela volta para 09:03. Contorno: pausar antes de arrastar — que é o que os outros
dois botões fazem por conta própria.

**Correção:** `session.pause()` antes do `seekFraction`, como em `back`/`forward`.
Ou não escrever no scrubber dentro do `refresh()` enquanto
`scrubber.getValueIsAdjusting()`.

**Tentei refutar:** chequei se o `adjusting` cobre — cobre a recursão (o
`setValue` programático não vira busca), não a disputa (o `setValue` acontece do
mesmo jeito, e move a alça). Não derrubou.

---

### A6-16 — `DatePicker.onChange` troca o ouvinte e **acrescenta** outro

**Onde:** `DatePicker.java:135-155`

**Trecho:**
```java
public void onChange(Runnable listener) {
    this.onChange = listener == null ? () -> { } : listener;

    field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
```

**Problema:** o método parece um *setter* (`this.onChange = ...`) mas registra um
`DocumentListener` novo a cada chamada. Chamado duas vezes, o mesmo `Runnable`
roda duas vezes por tecla digitada, para sempre — não há como remover.

Hoje `ReplayPanel` chama uma vez por picker (linhas 204 e 205), então não dói.
Mas o método é público, a assinatura promete substituição, e o efeito é acúmulo.
Combinado com o A6-2, uma segunda chamada dobra também a exceção.

**Correção:** registrar o `DocumentListener` **uma vez**, no construtor, e deixar
`onChange` como campo puro.

**Tentei refutar:** contei as chamadas (`grep "onChange("`): duas, uma por picker.
Não derrubou o defeito, só o alcance atual — por isso MÉDIA e não ALTA.

---

### A6-17 — `replay.speed` está definido duas vezes no bundle, com significados diferentes

**Onde:** `messages.properties:243` e `:256`; `messages_pt_BR.properties:243` e `:256`

**Trecho:**
```properties
243: replay.speed = bars/s
...
256: replay.speed = speed
```
```properties
243: replay.speed = barras/s
...
256: replay.speed = velocidade
```

**Problema:** `Properties` mantém a última, em silêncio. Vale "speed"/"velocidade",
que é a certa — mas a primeira, `bars/s`, é o **modelo errado** que o javadoc de
`ReplaySession.SPEEDS` (linhas 55-63) descreve como o erro da primeira versão:
*"Bars per second was unambiguous and matched nobody's idea of a replay."* Ela ficou
para trás na linha 243 e volta a valer se alguém reordenar o arquivo, ou se um
`.properties` for lido por uma ferramenta que fique com a primeira.

Achado único da varredura de chaves duplicadas nos dois bundles.

**Correção:** apagar a linha 243 dos dois arquivos. E, junto, `replay.date` (241) e
`replay.ticks` (239) não são usadas por nenhum `.java` — chaves mortas.

**Tentei refutar:** rodei a checagem de duplicatas nos dois bundles; `replay.speed`
é a única. E confirmei qual das duas o `ResourceBundle` entrega. Não derrubou.

---

### A6-18 — texto de interface montado em Java, em português, dentro de `ReplaySession`

**Onde:** `ReplaySession.java:554-557`

**Trecho:**
```java
/** @return the range as the chart title writes it */
public String rangeText() {
    return isRange() ? date + " a " + until : date.toString();
}
```

**Problema:** `" a "` é a preposição portuguesa, cravada em Java. O resultado vai
para a interface por dois caminhos: o `ends` do transporte
(`ReplayPanel.java:694`) e o **título do gráfico**
(`ReplayDrop.java:86`: `session.name() + " " + session.rangeText()`). Na versão em
inglês do programa o título do gráfico fica `WINFUT 2026-08-31 a 2026-09-04`.

E o formato da data também: `LocalDate.toString()` dá ISO (`2026-08-31`) enquanto
o resto do transporte usa `dd/MM/yyyy` (`DatePicker.TYPED`) e `dd/MM`
(`endText`). Três formatos de data na mesma janela.

`ReplayRangeTest.theTitleSaysTheRange` (linha 137) trava o literal:
`assertEquals("2026-08-31 a 2026-09-04", ...)`.

**Correção:** `Messages.get("replay.range", from, to)` com um `{0} a {1}` no
bundle pt-BR e `{0} to {1}` no inglês, e as datas formatadas pelo mesmo `TYPED` do
resto da janela.

**Tentei refutar:** procurei uma chave de bundle que já fizesse isso —
`replay.inTitle = Replay {0}` existe e **não é usada por nenhum `.java`**, o que
sugere que este caminho já foi por ali e voltou. Não derrubou.

---

### A6-19 — o fuso do sistema decide de que dia é cada barra, em quatro lugares

**Onde:** `ReplaySession.java:253-256`, `:361-362`, `:410-411`, `:645-646`

**Trecho:**
```java
// :361 -- recorte do dia dentro da série de barras
return SegmentedSeries.of(whole, new Segment(instrument, day, day),
        ZoneId.systemDefault());
```
```java
// :410 -- dobra dos ticks em candles de um minuto
                    ticks.source().read(file)),
        ZoneId.systemDefault());
```
```java
// :253 -- e, por omissão, o RecordedTicks: o construtor de dois argumentos
this.live = new ReplaySeries(ConcatSeries.of(parts), before, before,
        new RecordedTicks(ticks, (bars, index) ->
```

**Problema:** o projeto não tem nenhum fuso de mercado declarado — `grep` por
`ZoneId.of`, `Sao_Paulo` e `marketZone` em `src/main` não devolve nada. Tudo é
`systemDefault()`. Isso é dívida do projeto inteiro, não desvio desta área; o que
**é** desta área é a assimetria que ela cria no replay:

- os dias que o calendário oferece para um feed de ticks vêm do **nome do
  arquivo** (`TickLibrary.exported` → `source.sessionIn`), que não tem fuso;
- o dia que a `ReplaySession` de fato recorta da série de barras vem de
  `systemDefault()` (linha 361);
- e o dia que o `RecordedTicks` procura na biblioteca para animar a barra vem de
  `Instant.ofEpochMilli(...).atZone(systemDefault())`, porque a `ReplaySession`
  usa o construtor de dois argumentos (linha 254) e não passa fuso nenhum.

Numa máquina fora do UTC-3 — um portátil que voltou de viagem com o relógio ainda
no fuso de lá, ou um servidor em UTC — as três respostas discordam: o calendário
oferece o dia 4, o recorte devolve barras do dia 3 e do 4, e a busca de ticks para
a barra das 09:00 procura o arquivo do dia errado e não acha, caindo no caminho
sintético em silêncio. A memória do projeto já registra o custo desse tipo de
erro (agregação por fuso, W1 de quinta a quarta).

**Correção:** um `Zones.MARKET` num lugar só, e as quatro chamadas passando ele. O
`RecordedTicks` já tem o construtor de três argumentos preparado para receber.

**Tentei refutar:** (a) procurei um fuso canônico no projeto — não existe, então
isto não é desvio de convenção local, é dívida; por isso MÉDIA e não ALTA. (b)
chequei se `Timeframe.ONE_MINUTE.fold` é sensível ao fuso — para baldes de um
minuto só um fuso de offset fracionário mudaria o resultado, então o `:410` é o
mais fraco dos quatro. (c) chequei se `SegmentedSeries.of` normaliza — recebe o
fuso e usa. Sobreviveram (a) parcialmente e (b) inteiro, e é por isso que o achado
é MÉDIA com a ressalva escrita.

---

## Achados BAIXA

### A6-20 — dois ícones novos por frame, e um `setIcon` que repinta o botão 25 vezes por segundo

**Onde:** `ReplayPanel.java:679-680`

```java
play.setIcon(ready && session.isPlaying()
        ? ReplayIcons.pause(18) : ReplayIcons.play(18));
```

`refresh()` é chamado de todo `announce()`, ou seja, de todo frame do `Timer`.
Cada passagem aloca um `Painted` novo e chama `setIcon`, que dispara
`firePropertyChange` + `revalidate` + `repaint` no botão — mesmo quando o ícone é
o mesmo dos últimos vinte frames. E cada pintura aloca um `BasicStroke` e quatro
arrays (`ReplayIcons.java:96-116`, `:143`). São dois ícones constantes: guardar
`PLAY` e `PAUSE` em campos estáticos e só trocar quando `isPlaying()` mudar.

### A6-21 — javadocs empilhados que ficam presos no membro errado

**Onde:** `ReplaySession.java:150-156`, `:279-294`, `:481-497`, `:668-687`;
`ReplayPanel.java:267-276`

```java
// ReplaySession.java:150
/**
 * @param instrument what is being replayed
 * @param date the session
 */
/** How many sessions may be played in one go, so a typo cannot ask for a decade. */
public static final int MOST_SESSIONS = 250;
```

Cinco blocos de javadoc órfãos, cada um com um bloco novo logo abaixo; o
compilador de javadoc fica com o último e descarta o primeiro. O efeito prático:
o javadoc que descreve `stop()` ("Ends the session and hands every chart back...")
está anexado a `isStopped()` (linha 683), e `stop()` (linha 689) e `playing()`
(linha 500) e `isRecorded()` (linha 504) ficaram **sem** documentação nenhuma. Em
`ReplaySession.java:150` há `@param` numa constante. São restos de edições que não
apagaram o texto antigo.

Junto: `private boolean stopped;` está declarado na linha 687, **entre dois
métodos**, quando todos os outros campos da classe estão no topo.

### A6-22 — `isStopped()` não é usado por ninguém

**Onde:** `ReplaySession.java:675-685`

Método público com onze linhas de javadoc explicando a diferença entre pausado e
parado. `grep` no repositório: zero chamadas, inclusive nos testes. Ou é a guarda
que falta em `ReplayDrop.attach` (soltar uma sessão já parada num gráfico hoje é
possível e não é verificado) — e aí deveria ser usado —, ou é código morto.

### A6-23 — comentários que narram o código

**Onde:** `DatePicker.java:205`, `ReplayPanel.java:147`

```java
// How many blanks before the first of the month, counting from Sunday.
int blanks = Math.floorMod(first.getDayOfWeek().getValue() - DayOfWeek.SUNDAY.getValue(), 7);
```

O comentário diz o que a linha diz, com o nome da variável já dizendo. A
convenção da casa é que comentário explica o porquê. São poucos — o resto do
pacote é exemplar nisso — mas estes dois são o quê.

### A6-24 — `chip.setEnabled(true)` redundante

**Onde:** `ReplayPanel.java:687`

```java
if (waiting) {
    clock.setText(Messages.get("replay.loading"));
    ends.setText("");
    chip.setEnabled(true);
```

`dressChip(ready)` já rodou na linha 661 com `ready == true` (é pré-condição de
`waiting`) e já fez `chip.setEnabled(true)` na linha 304. A linha não muda nada e
sugere ao leitor que muda.

### A6-25 — `stopSession()` é um invólucro de uma linha com dois javadocs

**Onde:** `ReplayPanel.java:267-278`

```java
/** Ends whatever is playing and gives every chart following it back. */
/**
 * Ends the session and hands every chart its own data back.
 * ...
 */
private void stopSession() {
    release();
}
```

Dois javadocs (um órfão), oito linhas de texto, para delegar a `release()` — que é
público, tem a mesma semântica e é o que o `ReplayWindow` já chama. O terceiro
javadoc com o mesmo conteúdo está em `ReplaySession.java:668-674`, também órfão.
Ou o botão chama `release` direto, ou o javadoc mora num lugar só.

### A6-26 — o comentário do teste de velocidade diz 50, o código diz 60

**Onde:** `ReplaySessionTest.java:166-169`

```java
// Capped where the animation stops showing everything: a bar is about
// thirty-one prices, one every 1,93 s of market time, so fifty times is
// one price per 40 ms frame. Faster skips prices, and the bar starts
// forming in jumps again -- which is what the ticks exist to prevent.
replay.setSpeed(1000);
assertEquals(ReplaySession.FASTEST, replay.speed());
```

O comentário conclui "fifty times"; `FASTEST` é 60. O javadoc de `FASTEST`
(`ReplaySession.java:66-81`) faz a mesma conta e assume a diferença: *"at sixty
times one arrives every 32 ms and a 40 ms frame drops the odd one. That is the
price of the round number"*. O comentário do teste é a versão anterior do
raciocínio, e diz um número que o produto não usa. Junto: o `@DisplayName` do
teste é *"speed is bars per second, and never zero"* — e "bars per second" é
exatamente o modelo que `SPEEDS` documenta como o erro corrigido.

---

## Os nove testes, um a um

Para cada um: **que mutação do código este teste NÃO pegaria?**

### 1. `ReplaySessionTest` (178 linhas, 7 testes)

- `startsAtTheOpen`, `steppingBack`, `watchersHear`, `theSameDayRepeats`: com
  dentes. `watchersHear` é o melhor da área — conta antes e depois do `forget`, e
  a mensagem *"a chart that let go was told anyway"* nomeia a mutação.
- `seekingToTheEnd`: **sem dentes** — ver A6-6. Tautologia.
- `theClockIsTheSessionsClock`: pega o relógio ficar em 1970 ou virar "agora".
  **Não pega** `endText()` ser uma constante inventada (A6-8), porque o fixture foi
  construído com as mesmas constantes.
- `speed`: pega `setSpeed` não travar o piso nem o teto. **Não pega** três coisas:
  (a) `tick()` ignorar `speed` por completo — nada aqui liga velocidade a
  movimento; (b) a velocidade escolhida no combo nunca chegar na sessão (A6-1),
  porque o teste chama `setSpeed` direto; (c) `SPEEDS` perder o valor 60 — a
  asserção é `offered <= FASTEST`, um teto, e `{1}` sozinho passaria. É o padrão
  "só afirma um teto" de novo, aqui em grau menor.

**Mutação que o arquivo inteiro não pega:** trocar `FRAME * speed` por `FRAME` em
`ReplaySession.tick()`. Todos os sete continuam verdes, e o transporte anda a 1×
em qualquer posição do combo.

### 2. `ReplayRangeTest` (182 linhas, 8 testes)

- `weekendsAreSkipped`, `acrossAWeekend`, `backwardsRangeIsOneDay`,
  `weekendOnlyHasNothingToPlay`: com dentes, e o último é o melhor — compara
  contra zero e contra `isEmpty()`, cobrindo o retorno da era do random walk.
- `theEndNeverPrecedesTheStart` e `theWindowIsCapped`: bons — igualdade, com o
  off-by-one do décimo dia explicitamente coberto.
- `theClockSaysWhichDay`: `assertEquals(8, ...length())` e
  `assertTrue(length() > 8)`. O segundo é frouxo: `dd/MM HH:mm:ss` tem 14, e
  qualquer formato de 9 caracteres passaria.
- `theTitleSaysTheRange`: trava o literal `" a "` (A6-18) — o teste **defende** o
  defeito.
- `thereIsACap`: **sem dentes** — ver A6-7. Apagar o teto do produto não quebra
  nada.

**Mutação que o arquivo inteiro não pega:** apagar
`&& days.size() < MOST_SESSIONS` de `ReplaySession.sessionsIn` (linha 324).

### 3. `ReplaySessionTest`/`ReplayRangeTest` em conjunto — a lacuna comum

Nenhum dos quinze testes desses dois arquivos **liga o `Timer`**. É deliberado e o
javadoc de `ReplaySessionTest` (linhas 29-35) defende bem: teste que espera relógio
de parede falha em máquina carregada. O preço é que `tick()` e
`advanceMarketTime` não têm cobertura nenhuma nesta área — e é dentro de `tick()`
que mora o congelamento eterno do A6-3. A mutação que ninguém pega: apagar o
`if (live.finished()) timer.stop();` de `tick()`. Dá para testar sem relógio:
chamando `tick()` por um seam de pacote, ou verificando `isPlaying()` depois de
`seekFraction(1.0)` seguido de `toggle()`.

### 4. `LoadingTrackTest` (173 linhas, 5 testes)

Os cinco testam a **troca de cartas** — `showLoading(true/false)` — chamada
diretamente. `onScreen` sobe a árvore de pais, o que é cuidado real, e
`bothExistInOneSlot` verifica a altura constante, que é a promessa de desenho.

**Mutação que não pega:** apagar `showLoading(true)` do ramo `building` de
`refresh()` (`ReplayPanel.java:641`) e `showLoading(waiting)` da linha 682. A barra
nunca mais apareceria no programa de verdade e os cinco continuam verdes — porque
nenhum deles passa por `refresh()`. O javadoc de `showLoading` (linhas 709-721)
admite metade disso ("Package-visible so a test can put the transport in both
states") mas conclui que chegar lá de verdade seria testar o leitor; não é:
bastava um seam para o `building`, que já é um `boolean` de campo.

Segundo: `panel = new ReplayPanel()` lê o `SeriesCatalog` **real** da máquina e
varre a pasta de ticks do usuário (A6-12). O teste é lento e dependente da
instalação, e ainda popula o `KNOWN` estático (A6-11) com dados dessa pasta, que
podem vazar para outra classe de teste.

### 5. `FirstSessionTest` (159 linhas, 4 testes)

O melhor arquivo de teste da área depois do `TickSourceChoiceTest`.
`stoppingReleasesTheSessions` conta megabytes reais (`residentTicks()`), e o
comentário das linhas 152-155 documenta que a primeira versão do teste era sem
dentes (`isPreparing() || !isPlaying()`) e por quê. `theSessionsBelongToTheMarket`
cobre o defeito histórico do corte no primeiro traço com quatro casos.

**Mutação que não pega:** fazer `TickLibrary.queue` engolir a `IOException` sem
chamar `whenLoaded.run()`, ou fazer o `if` do `onLoaded` exigir uma condição que
nunca se cumpre — o A6-4. Os testes só percorrem o caminho feliz e o caminho
"não há export"; o caminho **"há export e a leitura falhou"** não é exercido em
lugar nenhum, e é ele que trava o transporte para sempre.

Segunda mutação que não pega: trocar o `invokeLater` de `ReplaySession.java:265`
por uma chamada direta. `preparing` é `volatile`, então o teste continua vendo a
mudança — mas o `announce()` passaria a rodar na thread do carregador, mexendo em
Swing fora da EDT. O `settle()` do teste (linhas 60-70) chama `invokeAndWait`
justamente porque o produto marshaliza, mas nada **verifica** que marshaliza.

### 6. `ReplayEndsTest` (90 linhas, 3 testes)

`endingIsNotRepeated` tem dentes de verdade (parar duas vezes, contar uma).
`watchersGoQuiet` tem a asserção-guarda `assertTrue(before > 0, "the fixture never
ticked, so the check proved nothing")`, que é exatamente a disciplina certa.

Mas: `session()` chama `new ReplaySession("WINFUT", ...)` **sem fixture**. Não há
`ReplayBase.at`, então o `SeriesCatalog` aponta para a instalação real da máquina
e "WINFUT" quase certamente não existe lá — a série sai vazia. O `before > 0` da
guarda é satisfeito porque `announce()` avisa os watchers **mesmo quando
`step(1)` não revela barra nenhuma** (`ReplaySession.java:618-626`: `announce()` é
incondicional). Ou seja, a guarda que existe para provar que o fixture andou é
satisfeita por um passo que não andou.

**Mutação que não pega:** fazer `advance` não avançar nada. Os três continuam
verdes. E o arquivo ainda cria uma `TickLibrary` apontada para a pasta real do
usuário e dispara `request(date)` numa thread de carregamento contra ela.

### 7. `ReplayHandleTest` (129 linhas, 4 testes)

`nothingToDragLooksLikeNothingToDrag` é bom: cobre ícone, cursor e fundo no estado
sem sessão. `theArrowFollowsItsComponent` pinta num `BufferedImage` e conta pixels
brancos — teste de desenho com dentes de verdade, raro.

**Mutação que não pega:** o estado **com** sessão. Nenhum dos quatro chega a
`dressChip(true)`, então trocar `HANDLE_GROUND` por `Color.WHITE`, ou tirar o
`chip.setIcon(ReplayIcons.drag(12))` do ramo verdadeiro, ou fazer
`createTransferable` devolver sempre `null` (a alça deixa de arrastar), passa por
todos. O `TransferHandler` e o `ReplayTransfer` — que são o mecanismo inteiro da
área — não têm **nenhum** teste: `ReplayTransfer` e `ReplayDrop` estão sem
cobertura nas 1.515 linhas.

### 8. `PlayableDaysTest` (149 linhas, 4 testes)

`barFeedOffersItsOwnSessions` varre o intervalo inteiro dia a dia comparando
`!weekend` com `contains` — dentes excelentes, pega tanto oferecer a mais quanto a
menos. `tapeFeedOffersOnlyWhatWasExported` usa uma quarta-feira negociada mas não
exportada, que é o caso difícil certo. `theCalendarFollowsTheFeed` cobre os três
estados do `DatePicker`, inclusive a volta a `null`.

**Mutação que não pega:** `theAnswerIsHeld` afirma `first == again`, identidade.
Isso trava o vazamento do conjunto mutável do cache (A6-11) como se fosse
contrato. E nada testa o **feriado**: dia útil que o mercado não abriu. O javadoc
de `sessions()` (linhas 142-147) afirma que "Holidays fall out for free"; nenhum
teste escreve um fixture com um dia útil faltando para provar. É a afirmação mais
forte do arquivo e a única sem asserção.

Segunda: nada verifica que `sessions()` não é chamado na EDT (A6-5), nem que
`warm()` de fato preenche as mesmas chaves que `sessions()` consulta — se
`saved()` mudasse de formato, `warm()` esquentaria chaves que ninguém lê e o
cache-miss na EDT viraria o caso normal, em silêncio, com todos os testes verdes.

### 9. `TickSourceChoiceTest` (303 linhas, 6 testes)

O melhor da área, e por larga margem. Escreve os dois formatos para o **mesmo dia
do mesmo mercado** com preços impossíveis de confundir (100 contra 200), e
verifica a escolha em três alturas: na biblioteca, na sessão
(`theSessionPlaysWhatItWasGiven`) e nas barras que chegam ao gráfico
(`aTickFeedIsMadeOfTicks`, que ainda prova que sete trades num minuto viram **uma**
barra e não sete). `aFeedIsOneOrTheOther` cobre a invariante do `record` nos dois
lados. `aFeedRoundTrips` cobre a ida-e-volta e os dois casos de "não existe mais".

**Mutação que não pega:** o `label()` (`ReplayFeed.java:206-221`), que é o texto que
o leitor de fato lê no combo e que vai para o cabeçalho do gráfico via
`feedLabel()`. Nenhum teste o chama; trocar a ordem das partes, perder o nome do
export, ou devolver `!navigator.scale.5m!` passa por todos os seis. É o texto que
responde "o que está tocando", que é a razão de existir da classe inteira.

Segunda: `available()` devolver a lista **fora de ordem** — o javadoc promete
"bars first" e as asserções usam `anyMatch`, que não vê ordem.

---

## O que está LIMPO

Escrito com o que foi tentado e não caiu.

**O formato salvo dá a volta e tolera lixo.** Tentei derrubar `ReplayPreferences` e
a persistência do `ReplayPanel` com preferência de versão anterior, valor fora de
faixa e arquivo editado à mão. Não caiu, em quatro frentes: `Settings.getInt`
(linhas 348-358) devolve o padrão em `NumberFormatException`; `clamp` prende entre
0 e o teto e `windowDays()` ainda põe um piso de 1 (linhas 83, 87); `readDate`
(`ReplayPanel.java:255-265`) captura `DateTimeParseException` e volta para ontem; e
`rememberedSpeed` (linhas 231-252) não só captura o `NumberFormatException` como
**confere o valor lido contra `SPEEDS`** — uma preferência de "16", que era uma
velocidade oferecida numa versão anterior (o teste ainda usa 16 na linha 160),
volta como 1 em vez de virar um item fantasma no combo. Tentei um valor negativo,
um valor gigante, um texto e a chave ausente: os quatro caem no padrão.
`ReplayFeed.read` (linhas 235-247) devolve `null` para um feed que já não está no
disco, e o `ReplayPanel` trata `null` sem mexer no combo. Esta parte está
resolvida com cuidado.

**A alça é o único caminho da sessão para o gráfico, e o `DataFlavor` é local.**
Tentei achar uma segunda porta — um método que entregasse a `ReplaySession` a um
gráfico sem passar pelo arrasto — e não há: `ReplayDrop.enable` é chamado de um
único lugar (`MainWindow.java:441`), `createTransferable` devolve `null` quando não
há sessão (`ReplayPanel.java:742`), e `mousePressed` confere `session != null`
antes de exportar (linha 339). O `javaJVMLocalObjectMimeType` é a escolha certa e
o javadoc justifica: uma cópia serializada seria um segundo timer tocando o mesmo
dia fora de compasso. Tentei o arrasto de um arquivo do sistema para dentro:
`canImport` (`ReplayDrop.java:49`) exige `isDrop()` **e** o flavor próprio, então
arquivo inexistente, arquivo de outro tipo e vários arquivos de uma vez são todos
recusados antes de qualquer leitura — nada é lido na EDT porque nada é lido. A
`UnsupportedFlavorException`/`IOException` do `importData` é engolida, mas ali o
comentário está certo: o leitor vê o gráfico não mudar, que é o que aconteceu.

**Nada lê o futuro.** Este era o foco central e não achei violação. O que chega ao
gráfico é `session.series()`, que é a `ReplaySeries` (`ReplaySession.java:560-562`),
e ela recusa índices além do revelado — `ReplayPanel` só consulta `progress()`,
`clock()`, `total()` e `isEmpty()`, que são metadados do transporte, não preço.
Tentei três caminhos: (a) `endText`/`rangeText` — dizem para onde o replay vai, não
o que vai acontecer, e a data de chegada é informação do transporte; (b) o
scrubber — `seekFraction` **avança** a revelação, que é o leitor decidindo pular
para frente, não um indicador espiando; (c) o `ConcatSeries.of(parts)` do
construtor, que junta todos os dias do intervalo antes de qualquer coisa ser
tocada — mas a `ReplaySeries` fica por cima com `origin`/`completed` e nenhuma
referência à série concatenada escapa da `ReplaySession` para fora. Não caiu.

**A EDT é respeitada no caminho que importa.** O `Timer` é `javax.swing.Timer`
(`ReplaySession.java:275`), roda na EDT, e `tick()` não faz I/O nenhum — só
aritmética sobre um array já em memória. A leitura pesada está fora: o
`SwingWorker` de `requestDay` (linha 589) e a thread daemon do `TickLibrary`. A
única volta de fora para dentro é o `onLoaded`, e ela **se marshaliza sozinha**
(`SwingUtilities.invokeLater`, linha 265), com o `preparing` `volatile` e o
javadoc explicando exatamente por quê (linhas 101-107). Tentei achar um `Timer`
que não pare: `stop()` para o dele, o `windowClosing` chama `release()` que chama
`stop()`, e `tick()` para sozinho no fim do dia — a única fuga é a do A6-3, que é o
motor não deixando o `finished()` virar verdade, não um esquecimento aqui. Tentei
achar `synchronized` no caminho de pintura: não há um único `synchronized` em toda
a área.

**As convenções estruturais estão cumpridas.** `ReplayIcons`, `ReplayDrop` e
`ReplayPreferences` recusam instanciação com construtor privado e
`AssertionError`, e `ReplayBase` também. `ReplayFeed` é um `record` e valida a
invariante no construtor compacto (linhas 68-73), com o teste que prova os dois
lados. Nenhum `import` de `ui` dentro de `domain` — conferi todos os imports dos
nove arquivos e o sentido é o certo, inclusive o `ReplayDrop` morar aqui e não em
`ui.chart`, com o javadoc dizendo por quê. Nenhuma comparação de ponto flutuante
por `==` na área; nenhum `equals`/`hashCode` escrito à mão (o `record` os gera).

**O `DatePicker` acerta as bordas do calendário.** Tentei quebrá-lo com o primeiro
e o último dia do arquivo, com um mês que começa no domingo e com um que começa no
sábado: o `Math.floorMod(...)` da linha 206 dá 0 e 6 corretamente, e a semana
começa no domingo por tabela própria em vez de por `Locale`, com o javadoc
justificando (linhas 51-54). Um dia sem dado e um feriado ficam desabilitados e
**não** somem da grade, o que é a escolha certa e está comentada (linhas 245-250).
Um dia escolhido fora da faixa não é aceitável pelo calendário e, se for digitado
à mão, produz uma sessão vazia que o transporte anuncia com `replay.nothing`. A
única coisa que passa é a preposição do A6-18 e o fuso do A6-19.

**A tolerância a "não há nada para tocar" está bem construída.** Um sábado, um
feriado, uma data fora da série: `sessionsIn` devolve lista vazia, o construtor
acrescenta o dia pedido mesmo assim (linhas 241-246), `isEmpty()` fica verdadeiro,
e o transporte diz `replay.nothing` com `Parar` habilitado como saída
(`ReplayPanel.java:659, 669, 692-696`). Tentei fazer o transporte mostrar um botão
de play que não faz nada — o `nothingToPlay` desabilita play, back, forward,
scrubber e velocidade (linhas 663-665). Não caiu. É o oposto do A6-3, e mostra que
o padrão para tratar o congelamento já existe na classe.

**As chaves de bundle usadas existem todas.** Conferi as dezesseis chaves
`Messages.get` da área contra os dois arquivos: nenhuma ausente, nenhum `!chave!`
possível. As chaves de dado (`navigator.tickSource.*`, `navigator.scale.*`,
`navigator.group.*`) passam por `Messages.orElse`, que é o método certo para dado
e não para rótulo, e o javadoc do `Messages` explica a distinção. O que sobrou foi
a duplicata do A6-17 e as duas chaves mortas.
