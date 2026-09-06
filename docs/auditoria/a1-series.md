# A1 — domain/market: séries, arquivos e sessões

**Arquivos lidos integralmente:** 16 (~3.185 linhas)
**Achados:** 1 ALTA, 8 MÉDIA, 12 BAIXA

Arquivos lidos linha a linha: `Aggregation.java` (72), `ArraySeries.java` (127),
`ConcatSeries.java` (158), `MarketFile.java` (251), `PriceSeries.java` (100),
`RecordedTicks.java` (159), `Segment.java` (85), `SegmentedSeries.java` (165),
`SeriesMerge.java` (164), `Sessions.java` (88), `TapeFile.java` (541),
`TickFile.java` (462), `TickLibrary.java` (306), `TickPath.java` (47),
`TickSource.java` (144), `Timeframe.java` (316).

Fora da área, abertos só para confirmar ou refutar achado: `Untraded.java`,
`Counted.java`, `TickSeries.java`, `Aggressor.java`, `SyntheticTicks.java`,
`TickBars.java`, `ui/chart/PeriodCatalog.java`, `ui/chart/ChartCanvas.java`,
`ui/replay/ReplaySession.java`, e os testes de `domain/market`.

---

## Achados ALTA

### A1-1. Qualquer escala em minutos acima de 1.440 vira silenciosamente D1, com todas as barras carimbadas à meia-noite

`Timeframe.java:307`

```java
        // Day first, then the slot within the day: a slot number on its own
        // would put 09:00 on Monday and 09:00 on Tuesday in the same bucket.
        int minuteOfDay = local.getHour() * 60 + local.getMinute();

        return local.toLocalDate().toEpochDay() * 1_440L + (minuteOfDay / minutes) * (long) minutes;
```

e o carimbo, em `Timeframe.java:271-282`:

```java
    private long startOf(long millis, ZoneId zone) {
        if (minutes <= 0) {
            return millis;
        }

        ZonedDateTime local = Instant.ofEpochMilli(millis).atZone(zone);
        long sinceMidnight = local.toLocalTime().toSecondOfDay() / 60L;
        long slot = sinceMidnight / minutes * minutes;
```

**Problema.** `minuteOfDay` vale no máximo 1.439. Para qualquer `minutes > 1440`
a divisão `minuteOfDay / minutes` é sempre 0, então o bucket colapsa em
`epochDay * 1_440L` — um balde por dia do calendário, exatamente o que `DAILY`
produz. E `slot` também é sempre 0, então `startOf` devolve a meia-noite local:
o carimbo que o próprio javadoc da classe proíbe, em `Timeframe.java:266-269`
("A day, week or month bucket begins at midnight, and midnight is not a moment
this market existed... So those keep it").

E a faixa é oferecida de propósito, `Timeframe.java:73-74`:

```java
    /** A month of trading minutes: the longest a minute-count may be asked for. */
    public static final int MOST_MINUTES = 43_200;
```

**Consequência.** O caminho é o teclado do leitor, não uma API interna.
`PeriodCatalog.forText` (`ui/chart/PeriodCatalog.java:137-141`) chama
`Timeframe.ofMinutes(number)` com o que foi digitado — `parse`
(`PeriodCatalog.java:257-263`) aceita qualquer inteiro — e monta a escolha com
`minutes.label()`. Digitar `2000` oferece uma escala rotulada **"2000m"**,
descrita em minutos, que desenha **candles diários** carimbados às 00:00. Toda
medição feita sobre essa escala está errada e nada na tela diz isso. O mesmo
vale para `1441` até `43200`. Em `1440` o agrupamento acerta, mas o carimbo
ainda é meia-noite: "1440m" e "D1" cobrem o mesmo período e devolvem horários
diferentes.

**Correção.** Duas partes. (a) Em `bucketOf`, tratar escalas maiores que um dia
pelo calendário e não pelo minuto do dia — ou, mais simples e honesto com o que
a classe promete, baixar `MOST_MINUTES` para `1_440` e recusar o resto em
`ofMinutes`, deixando D1/W1/M1 como os nomes das escalas acima de um dia.
(b) Em `startOf`, cair no `return millis` quando `minutes >= 1440`, pelo mesmo
argumento que o javadoc já dá para DAY/WEEK/MONTH.

**Tentei refutar assim:** procurei um teto acima de `MOST_MINUTES` em
`PeriodCatalog` (`SMALLEST_BRICK`/`LARGEST_BRICK` são do renko, não da escala em
minutos) e em `ChartCanvas`; procurei validação em `ofMinutes`
(`Timeframe.java:111-123` — só `count < 1 || count > MOST_MINUTES`); e procurei
teste que cobrisse a faixa em `TimeframeTest` (`ofMinutes` só aparece em
`PeriodCatalogTest:148-151`, que testa 0, -5, `MOST_MINUTES + 1` e 5 — nunca um
valor entre 1.441 e 43.200). Nada barra o caminho.

---

## Achados MÉDIA

### A1-2. `Timeframe.fold` decide "esta série não tem volume" olhando só a barra 0, e apaga todos os volumes já somados

`Timeframe.java:237-239`

```java
        if (!Double.isFinite(source.volumeAt(0))) {
            java.util.Arrays.fill(volumes, 0, kept, Double.NaN);
        }
```

**Problema.** Uma barra basta para decidir por todas. O laço acima
(`Timeframe.java:227-229`) já soma só o que é finito:

```java
            if (Double.isFinite(volume)) {
                volumes[out] += volume;
            }
```

Então o `fill` é redundante quando a série inteira é NaN, e destrutivo quando
não é. Se a primeira barra vier sem volume e as seguintes vierem com, todo o
volume da série é jogado fora. No sentido oposto — barra 0 com volume, algumas
depois sem — o `fill` não roda e a barra agregada devolve uma soma parcial
apresentada como se fosse completa.

**Consequência.** Volume errado, em silêncio, nas duas direções. O caso
misturado é alcançável por construção: `ConcatSeries` e `SeriesMerge.Joined`
delegam `volumeAt` a partes diferentes, e `PriceSeries.volumeAt` é `default` e
devolve NaN para qualquer implementação que não o escreva
(`PriceSeries.java:61-63`).

**Correção.** Decidir por barra de saída e não pela série: manter um contador de
quantas barras de origem tinham volume finito naquele balde e carimbar NaN só
nos baldes que não tiveram nenhuma. Custa um `int[]` e nada mais.

**Tentei refutar assim:** procurei o teste. `TimeframeTest:231-238`
(`absentVolumeStaysAbsent`) monta duas barras, ambas com `Double.NaN`, e só
verifica `volumeAt(0)`. É exatamente o caso homogêneo; o misturado não é testado
nem por um lado nem pelo outro.

---

### A1-3. Em `Timeframe`, o javadoc de `bucketOf` está colado em `startOf`, e `bucketOf` fica sem nenhum

`Timeframe.java:250-271`

```java
    /**
     * @return a key that is equal for two bars of the same output bar
     *
     * <p>Built from local calendar fields, never from the epoch — that is the
     * whole point of this class. See the two failures described above.</p>
     */
    /**
     * @return when the bucket holding that instant begins
     ...
     */
    private long startOf(long millis, ZoneId zone) {
```

**Problema.** Dois blocos javadoc seguidos antes do mesmo método. O primeiro
descreve o contrato de `bucketOf` — "a key that is equal for two bars of the
same output bar" — e nada disso é o que `startOf` faz. Só o segundo bloco
realmente se liga ao método; o primeiro é comentário morto que afirma outra
coisa. E `bucketOf` (`Timeframe.java:284`), o método que carrega toda a regra de
fuso (W1 de segunda, D1 pelo calendário local), ficou sem documentação nenhuma.

**Consequência.** É a convenção da casa quebrada no ponto mais caro da classe:
quem for mexer no agrupamento por fuso lê um comentário que descreve o método
errado e não encontra nenhum no método certo. É também o que ergueu o A1-1 —
ninguém revisou o contrato de `bucketOf` porque ele não está escrito ali.

**Correção.** Mover o primeiro bloco para cima de `bucketOf`.

**Tentei refutar assim:** conferi que não é um bloco de comentário comum
(`/* */`) e sim `/** */`, e que a ferramenta de javadoc associa apenas o último —
o primeiro se perde. Procurei se `startOf` faria alguma vez o papel de chave: não,
`fold` usa `bucketOf` para comparar (`Timeframe.java:205-207`) e `startOf` só
para carimbar (`Timeframe.java:211`).

---

### A1-4. `RecordedTicks` não respeita o contrato que `TickPath` declara: o caminho não começa na abertura nem termina no fechamento

`TickPath.java:44-46`

```java
     * @return the prices inside it, opening price first and closing price last
     */
    double[] pathFor(PriceSeries series, int index);
```

`RecordedTicks.java:101-113`

```java
        double[] path = new double[count];
        int at = 0;

        for (int i = first; i < ticks.size() && ticks.timeAt(i) < to; i++) {
            if (ticks.hasLast(i) && ticks.lastAt(i) > 0) {
                path[at++] = ticks.lastAt(i);
            }
        }

        return path;
```

**Problema.** O caminho é a lista crua dos negócios da janela. Nada garante que
`path[0]` seja `series.openAt(index)` nem que o último seja
`series.closeAt(index)` — e, quando os candles vêm da base de minutos e os ticks
vêm do export do MetaTrader, são duas fontes diferentes que não têm por que
coincidir na ponta. A outra implementação da mesma interface faz o contrário e
honra o contrato, `SyntheticTicks.java:91-111`:

```java
        double open = series.openAt(index);
        ...
        path[0] = open;
```

**Consequência.** As duas implementações que existem discordam do contrato que a
interface publica — que é precisamente a razão de a interface existir
(`TickPath.java:35-37`: "cross from a day with ticks into one without,
mid-playback, and only get less faithful rather than stopping"). No replay a
barra em formação anima até um preço que não é o fechamento do candle que ela
vira, e o salto aparece na troca. E é alcançável: `ReplaySession.java:253-256`
envolve o feed em `RecordedTicks` **sempre**, inclusive quando o feed é a base
de minutos e não os ticks.

**Correção.** Ou `RecordedTicks` fecha as pontas (`path[0] = series.openAt(index)`,
último = `series.closeAt(index)`), ou o javadoc de `TickPath` deixa de prometer o
que uma das duas implementações não cumpre. A primeira é a que preserva a
intenção declarada.

**Tentei refutar assim:** verifiquei se o replay só usa `RecordedTicks` sobre
candles feitos dos próprios ticks — não usa: `ReplaySession.dayOf`
(`ReplaySession.java:350-362`) devolve `foldedFromTicks(day)` **ou**
`SegmentedSeries.of(whole, ...)` sobre a base, e o `RecordedTicks` é montado uma
vez para os dois casos.

---

### A1-5. O parâmetro `zone` de `RecordedTicks` não tem efeito nenhum sobre os ticks: as sessões calculam a meia-noite no fuso do sistema

`RecordedTicks.java:55-59`

```java
    public RecordedTicks(TickLibrary library, TickPath fallback, ZoneId zone) {
        this.library = library;
        this.fallback = fallback;
        this.zone = zone;
    }
```

`TickFile.java:392-394`

```java
            // Worked out once. Doing it per tick would call the calendar 4,4
            // million times to produce the same number.
            this.midnight = date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
```

e o mesmo em `TapeFile.java:400`:

```java
        private static final ZoneId ZONE = ZoneId.systemDefault();
```

**Problema.** `RecordedTicks` usa o `zone` recebido só para escolher o DIA
(`dayOf`, `RecordedTicks.java:156-158`), mas compara instantes:
`ticks.timeAt(i) < to` (`RecordedTicks.java:87` e `:104`). E `ticks.timeAt` sai
de `midnight + millis[index]`, com `midnight` calculado no fuso do sistema, não
no fuso que o chamador pediu. Um `RecordedTicks` construído com um fuso
diferente do sistema desloca todos os instantes dos ticks em horas inteiras
contra os instantes das barras.

**Consequência.** A janela `[from, to)` pega ticks de outro momento do dia, ou
nenhum — e nesse caso o `count < 2` (`RecordedTicks.java:93`) manda tudo para o
sintético sem dizer nada. Uma abstração que vaza: o construtor de três
argumentos é público e promete algo que o formato de arquivo abaixo dele não
sabe cumprir.

**Correção.** `TickSeries` precisa aceitar o fuso — passar o `ZoneId` a
`TickFile.read`/`TapeFile.read` (ou a um método `atZone` sobre a sessão) e
calcular `midnight` com ele. Enquanto isso não existir, o construtor de três
argumentos deveria recusar um fuso diferente do sistema em vez de aceitar e
mentir.

**Tentei refutar assim:** procurei todo uso do construtor de três argumentos.
Produção: nenhum — `ReplaySession.java:254` usa o de dois, que passa
`ZoneId.systemDefault()`. Testes: `TickLibraryTest:252, 280, 298`, todos com
`ZONE = ZoneId.systemDefault()` (`TickLibraryTest:45`). Ou seja: o parâmetro
nunca foi exercitado com um fuso diferente, então o defeito está latente e não
manifesto — é o que o mantém em MÉDIA e não em ALTA. Não derruba o achado: o
construtor é público e a promessa é falsa.

---

### A1-6. Em `TickLibrary`, dois comentários dizem que o mapa está em ordem de uso; ele está em ordem de inserção e nunca é reordenado

`TickLibrary.java:76-82`

```java
    /**
     * What is loaded, newest use last.
     *
     * <p>Access is synchronised on the map itself. It is touched by the loader
     * thread and by whoever is drawing, which are never the same thread.</p>
     */
    private final Map<LocalDate, TickSeries> resident = new LinkedHashMap<>();
```

`TickLibrary.java:188-193`

```java
    /** @return the dates in memory, oldest use first */
    public List<LocalDate> residentDays() {
        synchronized (resident) {
            return new ArrayList<>(resident.keySet());
        }
    }
```

**Problema.** `new LinkedHashMap<>()` é ordem de inserção — o construtor com
`accessOrder = true` não foi usado — e `at` (`TickLibrary.java:136-140`) só faz
`resident.get(day)`, que em ordem de inserção não move nada. Não existe "uso"
registrado em lugar nenhum. Os dois comentários afirmam uma ordem que o código
não mantém, e o javadoc da própria classe (`TickLibrary.java:230-237`) diz por
que ela seria errada aqui: "Furthest in DAYS, not least recently used.
Least-recently-used is the usual answer and it is the wrong one here".

**Consequência.** Um comentário que mente sobre o código, no ponto em que a
classe explicou que a intuição usual está errada — exatamente onde alguém vai
confiar no comentário. `residentDays()` é público; quem ordenar decisão por ele
("descarte o mais antigo em uso") vai descartar por ordem de chegada.

**Correção.** Trocar os dois textos por "in the order they arrived" / "the dates
in memory, first loaded first", e apontar `keep` como quem decide o descarte.

**Tentei refutar assim:** conferi se `keep` (`TickLibrary.java:238-259`) faria a
reordenação implicitamente com `put` — não faz: `LinkedHashMap.put` sobre chave
existente mantém a posição original em ordem de inserção. E conferi que nenhum
outro ponto chama `remove`+`put` para promover a chave.

---

### A1-7. Em `TapeFile`, "uma sessão em que ninguém negociou" é também o que se lê de uma sessão cheia cujo dicionário ficou vazio

`TapeFile.java:186-191`

```java
        if (channel.size() == at) {
            // A session in which nobody traded. Legal, and not worth an
            // exception: the trades are the file, and a dictionary of none is
            // what a session of none has.
            return names;
        }
```

`TapeFile.java:346-349`

```java
        private void writeBrokers() throws IOException {
            if (brokers.isEmpty()) {
                return;
            }
```

**Problema.** O comentário atribui esse caminho a uma sessão sem negócios. Mas
`writeBrokers` volta sem escrever nada sempre que o dicionário está vazio — e o
dicionário só se enche por chamadas explícitas a `broker(code, name)`
(`TapeFile.java:324-330`), que são independentes de `add`. Um conversor que
escreva cinco milhões de negócios e esqueça de nomear as corretoras produz um
arquivo que termina exatamente em `brokersAt`, e a leitura entra nesse ramo com
o comentário dizendo que ninguém negociou.

**Consequência.** Comentário mentiroso guardando o único ponto que distinguiria
"sessão vazia" de "dicionário perdido". `brokerName(code)`
(`TapeFile.java:536-539`) devolve `null` para todo mundo e nada na cadeia
reclama: o formato que existe para não perder coluna nenhuma perde as trinta e
uma corretoras em silêncio.

**Correção.** Escrever sempre o inteiro de contagem, mesmo zero — o formato
passa a distinguir "nenhuma corretora" de "nada escrito" — e ajustar o
comentário para o que o ramo realmente cobre.

**Tentei refutar assim:** procurei se `add` alimenta o dicionário sozinho
(`TapeFile.java:276-314` — não toca em `brokers`), e se algum guarda exige
`broker()` antes de `close()` (não há). Conferi também o teste
`TapeFileTest`, que nomeia as corretoras — logo, o caminho vazio não é coberto.

---

### A1-8. `TapeFile.Session.aggressorAt` clona um array por chamada e estoura com `ArrayIndexOutOfBoundsException` — não `IOException` — num byte zero

`TapeFile.java:531-534`

```java
        @Override
        public Aggressor aggressorAt(int index) {
            return Aggressor.values()[aggressor[index] - 1];
        }
```

**Problema.** Duas coisas na mesma linha. (a) `Aggressor.values()` clona o array
de constantes a cada chamada — alocação por elemento dentro de laço, sobre
sessões de cinco milhões de negócios, que é o que a convenção da casa proíbe
explicitamente. (b) `aggressor[index] - 1` não é validado. O cabeçalho promete
`aggressor  byte, an {@link Aggressor} and never zero` (`TapeFile.java:52`), mas
`read` (`TapeFile.java:173`) copia o byte cru sem conferir nada; um zero — de um
arquivo de outra versão, de um conversor com bug, de um setor danificado — vira
índice `-1`.

**Consequência.** (a) `TapeFileTest:98` já percorre a sessão inteira chamando
`aggressorAt(i)`; num arquivo de verdade são milhões de clones de cinco
elementos. (b) A exceção é `RuntimeException`, então passa direto por
`TickLibrary.queue`, que só captura `IOException` (`TickLibrary.java:219`), e
explode depois, na thread de desenho ou de replay, longe do arquivo que a
causou. É o oposto do que `MarketFile` e `TickFile` fazem: recusar um arquivo
errado na leitura, com o nome do arquivo na mensagem.

**Correção.** Guardar `private static final Aggressor[] KINDS = Aggressor.values();`
uma vez, e validar em `read` que todo byte de agressor está em `[1, KINDS.length]`,
lançando `IOException` com o caminho e o índice — do mesmo jeito que o `count` já
é validado em `TapeFile.java:136-138`.

**Tentei refutar assim:** conferi se o escritor pode gerar zero:
`TapeFile.java:311` grava `(byte) (aggressor.ordinal() + 1)` e `add` recusa
`aggressor == null` (`TapeFile.java:296-298`), então nossos próprios arquivos
estão bem. Não derruba: o leitor é público, aceita qualquer caminho, e a defesa
de todo o resto deste pacote é justamente não confiar no que está no disco
(`MarketFile.java:56-59`).

---

### A1-9. `TickLibrary.exported()` engole a `IOException` da varredura e responde "nenhuma sessão", sem dizer nada

`TickLibrary.java:283-300`

```java
        try (var files = Files.walk(folder, 4)) {
            ...
        } catch (IOException e) {
            return List.of();
        }
```

**Problema.** `Files.walk` levanta `IOException` por um subdiretório sem
permissão, por um link circular, por um volume de rede que caiu — situações que
não têm relação com "não há sessão exportada". O `catch` transforma todas em
lista vazia. E como `Files.walk` é preguiçoso, a exceção pode vir no meio da
varredura: as sessões já encontradas também são descartadas.

**Consequência.** O transporte do replay mostra zero dias com ticks, sem
mensagem e sem log, e o leitor conclui que o export não existe. É o pior
caminho para um erro de ambiente: indistinguível do estado normal de quem nunca
exportou nada.

**Correção.** Deixar a `IOException` subir (o método está a um `throws` de
distância dos chamadores), ou no mínimo devolver o que já foi coletado e
registrar o motivo. O comentário logo acima (`TickLibrary.java:289-292`) explica
por que os `null` individuais não merecem mensagem; a falha da varredura inteira
é outra coisa e não está coberta por esse argumento.

**Tentei refutar assim:** verifiquei se algum chamador distingue lista vazia de
falha — `RenkoSource.allows` e o transporte só olham o conteúdo. E verifiquei se
`Files.isDirectory(folder)` (`TickLibrary.java:276`) já cobriria o caso: cobre
só a raiz ausente, não um subdiretório ilegível nem uma falha no meio do walk.

---

## Achados BAIXA

- `PriceSeries.java:66-98` — a série vazia não sobrescreve `volumeAt`; os cinco
  irmãos lançam `IndexOutOfBoundsException("empty series")` e o volume devolve
  NaN pelo `default` (`PriceSeries.java:61-63`). Um laço sobre a série vazia
  falha em `timeAt` e passa em `volumeAt`.
- `ArraySeries.java:70-81` — o construtor aceita arrays de tamanhos diferentes.
  `size()` devolve `times.length` e o desencontro só aparece como
  `ArrayIndexOutOfBoundsException` no acessor da coluna curta, longe de quem
  montou. Os três construtores atuais (`MarketFile.java:138`, `Renko.java:640`,
  `Timeframe.java:241`) usam um tamanho só, mas a classe não exige.
- `Timeframe.java:271-282` — `startOf` soma `slot` de minutos de relógio ao
  INSTANTE da meia-noite (`atStartOfDay(zone).toInstant()`). Num dia em que o
  fuso muda, os dois discordam e a barra sai carimbada uma hora fora. Não
  alcançável com a base atual (o Brasil abandonou o horário de verão em 2019 e a
  fonte começa em set/2020), mas a fórmula está errada e não diz que depende
  disso.
- `Timeframe.java` — a classe não tem `equals`/`hashCode`, e `ofMinutes`
  (`Timeframe.java:122`) devolve uma instância nova para todo valor fora dos oito
  nomeados. `ChartCanvas.java:824` compara com `newPeriod == period` e
  `ChartCanvas.java:1085` com `asked != period`: identidade sobre um tipo que se
  comporta como valor.
- `ConcatSeries.java:34`, `SegmentedSeries.java:39`, `SeriesMerge.java:127` —
  nenhum dos três envelopes implementa `Untraded`/`Counted`, então
  `Untraded.at(...)` e `Counted.at(...)` devolvem "não sabe" para qualquer renko
  que passe por eles. Hoje ninguém envolve renko nesses três (verificado em
  `ReplaySession.java:253`, `:361` e `MainWindow.java:437`, todos sobre a base de
  minutos), mas a capacidade some sem aviso.
- `Timeframe.java:194-247` — `fold` aloca seis arrays do tamanho da ORIGEM e
  depois copia cada um para o tamanho final; o pico é o dobro do necessário. Para
  1.050.000 barras são ~50 MB vivos duas vezes ao mesmo tempo, num método que a
  própria documentação (`Timeframe.java:182-184`) justifica por memória.
- `TapeFile.java:103-105` — `isTape` usa o literal `"ENDVTAPE"` em vez da
  constante `MAGIC` declarada oito linhas antes (`TapeFile.java:85`).
  Duplicação que vai divergir na primeira mudança de tag.
- `TapeFile.java:87` e `TickFile.java:75` — dois `private static final int
  VERSION = 1` independentes, mas `TickSource.sessionIn`
  (`TickSource.java:140-143`) valida um arquivo `.tape` chamando
  `TickFile.sessionOf`, que confere contra a VERSION do `TickFile`. Subir a
  versão do tape sem subir a do tick faz `has()` e `exported()` negarem sessões
  que `TapeFile.read` aceitaria.
- `TickFile.java:320-330` — `header(channel, file, tag)` compara contra
  `byte[] magic = new byte[MAGIC.length]`, e não `tag.length`. Funciona porque as
  duas tags têm oito bytes; uma terceira fonte com tag de outro tamanho leria a
  quantidade errada de bytes e compararia mal.
- `TapeFile.java:206-219` — depois de ler as `entries` corretoras, nada confere
  que o arquivo terminou. `MarketFile.java:97` e `TickFile.java:154` recusam
  tamanho diferente do prometido (`!=`); aqui a checagem é só `<`
  (`TapeFile.java:143`), então bytes sobrando no fim passam despercebidos.
- `TapeFile.java:360-365` — `entry.putShort((short) name.length)` sem conferir o
  limite, num método (`broker`, `TapeFile.java:324-330`) que valida o código mas
  não o nome. Um nome acima de 65.535 bytes trunca em silêncio, que é
  exatamente o que o javadoc da classe (`TapeFile.java:76-79`) diz que este
  formato existe para evitar.
- `MarketFile.java:197-209` e `TickFile.java:97-109` — `isSeries`/`isTicks`
  transformam qualquer `IOException` em "não é dos nossos", inclusive uma falha
  real de leitura sobre um arquivo válido.

---

## O que foi auditado e está LIMPO

| o quê | como conferi |
|---|---|
| Fronteira de camada | Li todos os `import` dos 16 arquivos: só `java.*` (`java.io`, `java.nio`, `java.time`, `java.util`). Nenhum `javax.swing`, nenhum `...endeavourneo.ui`. `TickSource.key()` usa `java.util.Locale` qualificado, também JDK. |
| Dependência de runtime | Nenhum import fora do JDK em nenhum dos 16. |
| Classes utilitárias recusando instanciação | `MarketFile:75-77`, `SeriesMerge:48-50`, `Sessions:50-52`, `TickFile:92-94`, `TapeFile:98-100` — todos com construtor privado lançando `AssertionError`. |
| Invariante de record | `Segment:45-60` valida nome vazio, `from` nulo e `to` antes de `from`, e normaliza com `trim()` depois do `isBlank()` (a ordem está certa: `isBlank` já cobre só-espaços). |
| Busca binária de `ConcatSeries.partOf` | `ConcatSeries:90-110`. `starts` tem `parts.length + 1` entradas, `high` começa em `parts.length - 1`, o arredondamento é para cima (`(low + high + 1) >>> 1`) e o limite é testado antes (`:91-94`). Verifiquei o caso de índice 0, o do último e o das fronteiras entre partes; e `of` já descarta as partes vazias (`:57-64`), então nenhuma fronteira fica degenerada. |
| Busca binária de `SegmentedSeries.firstAtOrAfter` | `SegmentedSeries:93-108`. É a forma canônica de limite inferior, com `high = size()` (não `size()-1`), então devolve `size()` quando toda barra é anterior — que é o que o javadoc promete. O limite superior usa `to.plusDays(1).atStartOfDay` (`:83-84`), então o último dia entra inteiro. `Math.max(0, until - from)` (`:86`) cobre o segmento fora da base. |
| Busca binária de `SeriesMerge.countBefore` | `SeriesMerge:99-118`. Testado mentalmente contra série de tamanho 0 (devolve 0), `when` antes de tudo (0) e `when` depois de tudo (`size()`); `stepAt` e `of` tratam `keep == 0` explicitamente (`:69-71`, `:91-93`). |
| `firstAtOrAfter` de `RecordedTicks` | `RecordedTicks:137-154`. `found` inicia em `ticks.size()`, então uma janela sem tick devolve o tamanho e os dois laços (`:87`, `:104`) não entram; `count < 2` (`:93`) manda para o fallback. |
| Simetria dos dois laços de `RecordedTicks` | `:87-91` conta e `:104-111` preenche, com condição idêntica (`ticks.hasLast(i) && ticks.lastAt(i) > 0`) e mesmo intervalo. O array não estoura nem sobra. |
| Escala dos preços de tick | `RecordedTicks:109` põe `lastAt(i)` (int) direto no caminho em pontos. Confirmei em `TickBars:119-134` que `lastAt` já é o preço em pontos inteiros, não em centavos — não há fator de escala perdido. |
| Ida e volta de `MarketFile` | `write` (`:151-188`) e `read` (`:91-140`) escrevem e leem os mesmos seis campos na mesma ordem, big-endian; o buffer tem capacidade múltipla exata de `RECORD_BYTES` (`:115`, `:167`), então o `remaining() < RECORD_BYTES` (`:171`) chega a zero certo. O tamanho prometido é conferido antes de qualquer leitura (`:95-105`). Volume NaN sobrevive à ida e volta como NaN. |
| Ida e volta de `TickFile` | Cabeçalho de 24 bytes (8+4+4+8 = `HEADER_BYTES`) e registro de 22 (`5*4+2 = RECORD_BYTES`) batem com o `<pre>` do javadoc (`:42-54`). O byte de presença é escrito por `mask` (`:268-271`) e lido pelos mesmos quatro bits (`:84-90`, `:419-449`). O `!=` no tamanho (`:154`) recusa arquivo truncado. |
| `TickFile.Writer` fechando o handle | `close()` (`:277-287`) tem `channel.close()` em `finally`; o canal não vaza mesmo se `flush()` ou a reescrita do cabeçalho falharem. Idem `TapeFile.Writer.close()` (`:332-344`). |
| Mensagem de tick fora de ordem | `TickFile:246-247` diz "tick at X comes after one at Y" quando `millis < lastMillis`. Suspeitei de inversão; não é — descreve a ordem NO ARQUIVO, que é o que está errado. Refutado. |
| Trava do mapa residente | `TickLibrary`: todo acesso a `resident` está dentro de `synchronized (resident)` — `at:137`, `residentCount:183`, `residentDays:189`, `forget:197`, `keep:239`. `focus` e `whenLoaded` são `volatile`. Nenhum acesso solto. |
| Teto de memória dos ticks | `keep` (`:238-259`) roda o descarte em laço `while (resident.size() > RESIDENT)`, e é o único ponto que insere; `load` (`:163-179`) também passa por ele. O teto de três sessões vale para as duas portas de entrada. |
| Thread do carregador | `Executors.newSingleThreadExecutor` com fábrica que marca `setDaemon(true)` (`:93-99`), e `close()` chama `shutdownNow()` (`:202-206`). Não segura o encerramento da aplicação. |
| Dupla fila do mesmo dia | `queue` (`:208-211`) combina `at(day) != null` com `loading.add(day)` sobre um `ConcurrentHashMap.newKeySet()`, e o `finally` (`:224-226`) remove sempre. Duas chamadas concorrentes não enfileiram duas leituras. |
| Profundidade da varredura | `Files.walk(folder, 4)` (`:283`) com o comentário "the source, the year, the month, the file": `folder` é profundidade 0, então 4 é exatamente o arquivo. Confere com `TickSource.fileFor` (`:112-118`). |
| `exported()` casando com `has()` | `:293-294` compara `fileFor(day).toAbsolutePath()` com o caminho encontrado, então um arquivo fora do lugar não vira dia jogável — que é o que o javadoc (`:270-273`) promete. |
| Agregação por fuso | `Timeframe.bucketOf` (`:284-310`): D1 por `toLocalDate().toEpochDay()`, M1 por `ano*12 + mês`, W1 voltando ao domingo... não: volta à SEGUNDA local (`getDayOfWeek().getValue() - MONDAY.getValue()`), nunca `epochDay / 7`. Bate com o que `TimeframeTest:145` (`dailyKeepsItsOwnDate`), `:168` (`weekStartsOnMonday`) e `:203` (`theZoneMatters`) exigem. |
| Barra final incompleta | `Timeframe.fold` guarda o último balde parcial (`kept = out + 1`, `:232`), como o javadoc declara (`:60-62`) e `TimeframeTest:217` (`keepsTheIncompleteTail`) cobre. Não é leitura do futuro: nenhuma barra de saída lê barra de origem posterior ao seu próprio balde. |
| Leitura do futuro nesta área | Nenhuma das 16 classes calcula indicador ou olha à frente do índice pedido. As duas únicas leituras de `index + 1` são `RecordedTicks.endOf` (`:125`), que usa só o INSTANTE de abertura da barra seguinte para saber onde a atual termina — nenhum preço — e `ConcatSeries`/`SegmentedSeries`, que só traduzem índices. |
| Base crua | Nada nos 16 arquivos aplica fator, razão ou ajuste a preço. `MarketFile` lê e escreve os doubles como estão. |
| `Sessions.of` | `:73-84`. Compara com o dia anterior em vez de consultar o `TreeSet`, e o acumulador começa em `null`, que nenhum `LocalDate` iguala — a primeira barra sempre entra. `series == null` devolve conjunto vazio, `zone == null` cai no padrão. Sem O(n²). |
| `Aggregation.none()` | `:69-71` devolve `PriceSeries.empty()` para fonte nula em vez de propagar o `null`. |

---

## Observações sobre a área

A qualidade média é alta e incomum: os comentários explicam decisão e quase
sempre trazem o número medido que a sustenta (86 sessões comparadas em
`SeriesMerge`, 824.881 barras em `Sessions`, 4,4 milhões de ticks por dia em
`TickFile`, 43,0 milhões de negócios em `Aggressor`). As buscas binárias — que
são quatro, escritas quatro vezes — estão todas corretas, o que não é o
resultado comum. Os limites de memória em `TickLibrary` são reais e valem para
as duas portas de entrada.

Três riscos estruturais, em ordem:

**1. `Timeframe` cresceu para além do que seu modelo de balde suporta.** O A1-1
não é um descuido pontual: é a consequência de `MOST_MINUTES` ter sido levado a
um mês enquanto `bucketOf` continuou dividindo o minuto do dia. O A1-3 mostra
por que passou — o contrato de `bucketOf` está escrito em cima do método errado.
As quatro escalas acima de um dia (D1, W1, M1 e "minutos > 1440") não são o
mesmo mecanismo e a classe as trata como se fossem.

**2. O fuso é assumido em três lugares e parametrizado em quatro.**
`SegmentedSeries`, `Sessions` e `Timeframe` recebem `ZoneId`; `TickFile.Session`
e `TapeFile.Session` decidem sozinhos por `systemDefault()`. `RecordedTicks`
fica no meio e recebe um parâmetro que não consegue honrar (A1-5). Enquanto a
máquina roda em São Paulo nada disso aparece; qualquer teste que fixe o fuso
para não depender da máquina vai bater nisso, e é a espécie de defeito que
aparece como "faltam ticks nesse dia" e não como exceção.

**3. `TapeFile` é o mais novo e o menos defendido dos três leitores.**
`MarketFile` e `TickFile` recusam tamanho inesperado com `!=`; `TapeFile`
verifica só `<`, não confere o fim do dicionário, não valida o byte de agressor
e tem um comentário errado guardando o único ramo que distingue sessão vazia de
dicionário perdido (A1-7, A1-8). Como é o formato que ainda vai receber os anos
de tape comprados, é onde uma correção agora custa menos.

**O que merece segundo olhar,** fora do que reportei: a interação entre
`RecordedTicks` e o feed do replay quando os candles NÃO vêm dos mesmos ticks
(A1-4 é o sintoma que consegui provar; suspeito que haja mais na costura entre
`ReplaySeries` e `RecordedTicks`, que fica fora desta área). E o custo real de
`Timeframe.fold` sobre 1.050.000 barras — o pico de memória do achado BAIXA é
aritmética, não medição; vale medir antes de mexer.
