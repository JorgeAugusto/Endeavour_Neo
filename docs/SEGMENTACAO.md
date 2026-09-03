# Base → Segmentação → Escala

Uma **segmentação** é uma fatia nomeada de uma base, definida por datas. Serve
para escolher: *base `winn`, fatia `Estudo`, escala 5 minutos* — em vez de
manter arquivos separados no disco e lembrar em qual deles se está.

Diagrama: [segmentacao.svg](segmentacao.svg)

---

## O que é, e o que não é

**É um seletor.** Um lugar a mais na escolha, entre a base e a escala. Você
cadastra as fatias uma vez e depois só escolhe.

**Não é uma trava.** Não há papel que impeça rodar nada, nem contador de
consultas. Uma proteção que se contorna abrindo o Python não protege — só cria
atrito e a ilusão de estar protegido, que é pior do que saber que não está. A
disciplina de não olhar a prova é sua, e continua sendo.

Os nomes *Estudo* e *Prova* são só nomes de fatia. Significam o que você quiser
que signifiquem.

---

## Três decisões de desenho

**Datas, nunca índices.** A base cresce a cada importação. Uma fatia definida
como "barras 0 a 300.000" muda de significado toda noite; "2021-08-30 até
2024-12-31" não muda nunca. E o fim é opcional: `2025-01-01 em diante` cresce
junto com a base.

**Nada é copiado.** `SegmentedSeries` é uma janela sobre a base: o `closeAt(0)`
dela é o `closeAt(primeiroDaFatia)` da base. Nenhum arquivo novo no disco, e o
gráfico e o motor não sabem que estão olhando uma fatia — por isso nada mais
precisa mudar.

**O rótulo viaja junto do número.** Todo resultado e todo título carrega
`winn · Estudo 2021–2024 · 5m`. Isso não é regra, é etiqueta: é o que responde
"de qual base era mesmo esse número?" seis meses depois, sem depender de
lembrança. No projeto anterior essa pergunta custou uma refação inteira de
medições.

---

## Onde isso vive

```
domain/market/
  Segment.java          nome, início, fim (fim opcional)
  SegmentCatalog.java   a lista, guardada entre execuções
  SegmentedSeries.java  a janela sobre a base, sem copiar
```

`Segment` é dado puro e não conhece série nenhuma; quem junta os dois é
`SegmentedSeries.of(base, segmento, fuso)`, que resolve as datas em índices por
busca binária — a série é cronológica, então custa `log n` e não a base inteira.

Sem fatia escolhida, a base inteira. "Tudo" não é uma fatia especial: é a
ausência de uma.

---

## E o corte à frente, que é outra coisa

A fatia é escolha sua e dura meses. O **corte à frente** — 1/2, 1/3 ou 1/4, como
no MetaTrader — é da rodada de backtest e é automático: dentro da fatia
escolhida, o motor separa o fim e reporta as duas colunas lado a lado.

```
Base            winn-1m.bin
 └─ Fatia       "Estudo 2021–2024"
     └─ Escala  5 minutos
         └─ na rodada:  amostra 2/3 │ à frente 1/3
```

Os dois níveis são independentes e não se substituem.
