# Pendências

O que está aberto em 06/09/2026, e que não se deduz do código nem do histórico.
Escrito para sobreviver a compactação e a troca de sessão.

---

## 1. Defeito conhecido e não corrigido

### O estocástico não interpola entre pontos fechados; o RSI interpola

Descoberto olhando o aplicativo inteiro renderizado fora da tela, com o workspace
real restaurado: no painel havia estocástico, estocástico em 5m e IFR juntos, e
**a linha do estocástico em 5m sai em degraus enquanto a do IFR ao lado sai
inclinada**.

A causa: quando o RSI foi escrito, ele ganhou a opção *"Aplicar interpolação"*
que o Profit mostra na aba Ativo/Período, e `RelativeStrength.calculate` chama
`OwnScale.smooth` depois do `OwnScale.map`. O `SlowStochastic` **não tem essa
opção nem essa chamada** — a linha de appearance dele não carrega o campo.

Os dois leem escala maior pela mesma regra e só um suaviza. É inconsistência
introduzida por mim ao fazer o RSI, não defeito herdado.

**Correção:** dar ao `SlowStochastic` o mesmo campo `interpolate` (padrão
ligado, como no RSI), a mesma chamada a `OwnScale.smooth`, o mesmo controle na
aba de escala do `StochasticDialog`, e mais um campo na linha de appearance.
Quebra o formato salvo dos estocásticos — o que é aceitável, ver
[[quebrar-redes-salvas-nao-e-restricao]].

---

## 2. Decisão pendente sobre a auditoria

### Cortar a fase 3, ou mantê-la?

O plano original tinha três fases: áreas, lentes transversais, e **verificação
adversarial** com 3 lentes por ALTA e 2 por MÉDIA.

Descobri fazendo que **a fase 3 seria a mais cara de todas**, não a mais barata
como eu havia afirmado. Cada verificador é pequeno, mas com as nove áreas
completas seriam ~21 ALTA e ~87 MÉDIA, ou seja **~237 agentes** e algo entre 3 e
5 milhões de tokens.

**Proposta feita e não decidida:** cortar a fase 3 e verificar os ALTA **inline**,
lendo o código na conversa conforme cada área cai. Já funcionou cinco vezes hoje
ao custo de um `grep` e um `sed` cada — 4 dos 7 ALTA abertos já estão verificados
assim, com o trecho gravado no `auditoria/00-estado.md`. Os MÉDIA carregariam a
evidência do agente, que é como as auditorias do `endeavour` sempre fizeram.

---

## 3. Os 7 ALTA abertos da auditoria

Detalhe completo em `auditoria/a1-series.md`, `a2-renko-ticks.md`,
`a3-chartcanvas.md`. Evidência das verificadas em `auditoria/00-estado.md`.

| # | onde | o quê | verificado |
|---|---|---|---|
| A1-1 | `Timeframe.java:309` | escala acima de 1440 min colapsa em D1 e carimba à meia-noite | ✅ |
| A2-1 | `ReplaySeries.java:173` | replay congela para sempre quando `pathFor` devolve null | — |
| A2-2 | `Renko.java:418` | `made` não zera entre extremos; **as caldas saem curtas** | ✅ |
| A3-1 | `ChartCanvas.java:1206` | sem decimação: 1,05 M barras varridas cinco vezes por repintura | ✅ |
| A3-2 | `ChartCanvas.java:1011` | 4 varreduras e 3 `Files.walk` **na EDT** antes do SwingWorker | — |
| A3-3 | `ChartCanvas.java:1094` | replay troca a série sem recalcular overlays: média de candle sobre tijolo | — |
| A3-4 | `ChartCanvas.java:1084` | guarda compara só o período; construção velha substitui a série ao vivo | ✅ |

Fora da auditoria, um teste sem dentes já identificado: **`RenkoWickBoundsTest`
só afirma tetos, nunca pisos** — por isso a calda curta do A2-2 passou.

---

## 4. Trabalho adiado por decisão dele

- **Simulador** — replay em que dá para operar. Conceito fechado em
  `auditoria/../BACKTEST.md`; nada construído.
- **Backtest** — o avaliador único das duas frentes de pesquisa. Documento com
  as seis decisões pendentes em `BACKTEST.md`; o motor existe no `endeavour` e
  seria portado (~1.240 linhas de núcleo), com duas correções no caminho: custo
  de 6,5 pontos e o motor não executa stop.
- **Linguagem portável de execução** — registrada em `EXECUCAO-PORTAVEL.md`,
  parada de propósito até haver backtest, família clássica escrita, e pelo menos
  uma estratégia portada à mão para o NTSL.
- **Teste end-to-end com janela visível** — modo escolhido por ele; a biblioteca
  é a AssertJ-Swing. Nada construído.
- **As seis capacidades a trazer de outros softwares** — congeladas até a versão
  estabilizar. Ver a memória `lista-do-que-trazer-de-outros-softwares`.

---

## 5. O que a auditoria já atestou como LIMPO

- **As sete regras do renko conferem todas no código.** Nenhuma diverge; duas
  divergem apenas na documentação. A régua validada contra o Profit está íntegra.
- **O `ChartCanvas` não viola nenhuma das três regras de domínio medidas** —
  futuro, base crua e renko. Delega as três corretamente.
