# Fase 3 — as duas auditorias, confrontadas

Duas leituras independentes do mesmo código, com oito dias entre elas. A segunda
**não leu a primeira**, e é isso que dá sentido a este documento: se tivesse
lido, encontraria o que a primeira encontrou e a comparação não diria nada.

Gerado com `docs/auditoria/cruzar.py`, e depois lido à mão — a ferramenta compara
por **arquivo**, que é grosso o bastante para levantar a pergunta e fino demais
para respondê-la sozinha.

| | auditoria I | auditoria II |
|---|---:|---:|
| quando | 30/08 a 06/09/2026 | 07/09/2026, madrugada |
| agentes | 9 áreas + 4 lentes | 10 áreas + 4 lentes |
| achados com id | 249 | 215 |
| ALTA | 52 | 26 |

## O que a comparação por arquivo diz

| | arquivos |
|---|---:|
| apontados pelas **duas** | 58 |
| só pela **I** | 47 |
| só pela **II** | 37 |

**Cuidado com o número do meio.** "Só na I" mistura três coisas que não se
parecem: o que foi corrigido entre uma e outra (bom), o que a II não alcançou
porque a partição mudou (neutro), e o que a II leu e deixou passar (ruim). A
ferramenta não distingue as três, e nenhum número distingue — só a leitura.

---

## As 26 ALTA da segunda passada, separadas pela primeira

### Vinte em arquivo que a primeira também apontou

São arquivos que já estavam sob suspeita, e a segunda leitura achou coisa
diferente neles. Isso **corrobora a partição**, não os achados: duas leituras
concordarem sobre onde olhar é mais fraco do que concordarem sobre o quê.

Cinco desses vinte merecem nota porque são código que a primeira auditoria
**não podia ter visto** — foi escrito depois dela, esta noite:

| | |
|---|---|
| `B3-1` | o quadro parado trocando o renko de ticks pelo de candles |
| `B7a-1` | `openUntil` devolvendo a série inteira do cache |
| `B6-3` | `forgetEnding` sem chamador |
| `B2-2` | `addUpTo` mandando dobrar o pregão duas vezes |
| `B5-1` | os estudos na série crua, desenhados na foldada |

### Dez em arquivo que a primeira não apontou

Estas são as que valem o custo da segunda passada. Separam-se em dois grupos.

**Sete são código desta noite** — arquivos que não existiam ou foram reescritos
depois da primeira auditoria:

| | |
|---|---|
| `B1-1` | `Sessions` respondendo no fuso da máquina |
| `B1-2` | `FoldedTicks` engolindo `IOException` |
| `B2-1`, `B8a-1` | o `OneClockTest` unilateral — e o laço que nunca rodou |
| `B8b-2` a `B8b-5` | quatro testes meus que leem o código-fonte em vez do comportamento |
| `B8a-3` | o teste da varredura casando um literal, não um comportamento |

**Três são falha real da primeira passada** — código antigo, que ela leu e
deixou passar:

| | |
|---|---|
| `L2-1` | `RenkoSource.allows` perguntando o dia no fuso da máquina quatro métodos acima de quem pergunta no do mercado |
| `B8b-1` | o único teste da escala própria do IFR passa com o indicador desenhando NaN em todas as barras |
| `B7b-2` | a janela de séries lendo a série inteira na thread da interface |

---

## O que isto mede, e o que não mede

**Mede:** que uma passada sozinha deixa passar. Três ALTA de código antigo é
5,8% dos 52 ALTA que a primeira reportou — e nenhuma delas é sutil depois de
apontada.

**Não mede:** quantas as duas juntas deixaram passar. Duas leituras
independentes que concordam não provam que o código está certo; provam que
concordam. O que este processo produz de mais confiável é o achado que sobrevive
a duas leituras — e mesmo esse só quer dizer "duas pessoas viram", não "é
verdade".

**E há um viés que precisa ser dito:** metade da segunda passada foi gasta em
código escrito nas horas anteriores por quem a encomendou. Sete das dez ALTA
"novas" são disso. Uma auditoria que audita o trabalho de ontem à noite está a
fazer revisão de código, não auditoria de base — as duas valem, mas não são a
mesma coisa e não devem ser contadas juntas.

---

## O que a primeira viu e a segunda não

Quarenta e sete arquivos. A esmagadora maioria são **BAIXA de texto** — javadoc
no membro errado, comentário que conta cinco onde há seis, mnemónico que não
sublinha nada — que a segunda passada não procurou com a mesma insistência
porque o briefing dela pedia outra coisa primeiro.

Duas exceções que **continuam abertas e valem seguimento**:

| | |
|---|---|
| `L2-1` (auditoria I) | `Aggregation` — o único caminho de dobra que a aplicação usa não aceita fuso |
| `A4-2` | passar o mouse sobre o nome do instrumento varre a série inteira, na EDT, a cada pixel |

O resto da lista velha está em `docs/auditoria/00-achados.md` e não foi
descartado: a decisão **D5** desta noite mandou-a para a fase 4, e ela continua
lá, inteira, para ser trabalhada com a lista nova ao lado.

---

## A honestidade sobre o processo

- **A ferramenta errou duas vezes antes de acertar**, e as duas produziam número
  errado sem parecer errado: só entendia `## ALTA` quando metade dos relatórios
  escreve `# ALTA` (163 dos 215 achados sem gravidade), e a guarda contra
  confundir `### B1-1` com um cabeçalho era "não tem a letra B" — que mata
  também `# BAIXA`. Está corrigido, e ficou registado porque é exatamente o tipo
  de erro que este documento existe para não cometer.
- **A comparação é por arquivo, não por defeito.** Dois achados diferentes no
  mesmo arquivo contam como concordância aqui, e não são.
- **Nenhum achado da segunda passada foi verificado adversarialmente** por uma
  terceira leitura. Cada um carrega a evidência do agente que o escreveu, que era
  obrigado a tentar derrubá-lo antes de reportar e a escrever o que tentou.
  Treze das 26 ALTA foram verificadas do jeito mais duro que existe: corrigidas,
  com o teste que faltava, e o produto quebrado de volta para ver o teste cair.
