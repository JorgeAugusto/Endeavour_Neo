# Pendências

O que está aberto em 06/09/2026, e que não se deduz do código nem do histórico.
Escrito para sobreviver a compactação e a troca de sessão.

---

## 1. Defeitos conhecidos e não corrigidos

### A interpolação de escala maior nunca agiu — em nenhum indicador

**Corrigido em 06/09/2026 pela auditoria A5.** O que estava escrito aqui antes
dizia que o RSI interpolava e o estocástico não, e que a linha do estocástico
saía em degraus ao lado da do RSI inclinada. **O sintoma estava errado**: as duas
saem em degraus, porque `OwnScale.smooth` é matematicamente idêntico ao
`OwnScale.map`.

`indexOfClosed` devolve o maior `c` com `coarse.timeAt(c+1) <= t`, e o `smooth`
mede a rampa de `timeAt(c)` a `timeAt(c+1)` — intervalo que `t` já ultrapassou.
O fator `along` é sempre ≥ 1, é grampeado em 1,0, e o resultado é sempre
`slow[c]`. A caixa "Inclinar entre os pontos fechados", **ligada por padrão** na
média, no RSI e nas bandas, não faz nada. Evidência em
`auditoria/00-estado.md`.

**Ordem de correção, nesta sequência:**

1. **`OwnScale.smooth`** — a rampa tem de correr durante a barra em formação: de
   `timeAt(closed+1)` a `timeAt(closed+2)`, saindo de `slow[closed-1]` e chegando
   em `slow[closed]`. A linha atrasa em vez de adiantar, que é o lado honesto de
   errar.
2. **`OwnPeriodTest.interpolationStaysBehind`** — hoje só afirma um teto
   (`value <= closeAt(bar)`) e passaria com o `smooth` apagado; foi ele que deu a
   licença falsa. Tem de afirmar que a linha **inclina**: dois valores
   consecutivos diferentes dentro de uma mesma barra grossa.
3. **Só então o `SlowStochastic`** ganha o campo `interpolate` (padrão ligado,
   como nos outros), a chamada a `OwnScale.smooth`, e o controle numa aba de
   escala própria — hoje ele tem duas abas e enfia a escala no fim dos
   parâmetros, contra três abas do RSI. Quebra o `appearance` posicional de 15
   campos, que é o único da área **sem teste de ida-e-volta**; quebrar formato
   salvo é aceitável, ver [[quebrar-redes-salvas-nao-e-restricao]].

---

## 2. A fase 3 foi CORTADA — decidido em 06/09/2026

O plano tinha três fases: as nove áreas, as quatro lentes transversais, e uma
**verificação adversarial** com três lentes por ALTA e duas por MÉDIA.

**As duas primeiras rodaram. A terceira não vai rodar.** Decisão dele.

### Por que

A conta ficou grande demais para o que entrega. Com 52 ALTA e 130 MÉDIA são
52×3 + 130×2 = **416 agentes**. E a promessa de que seriam baratos — "o insumo
é um achado de vinte linhas" — não sobreviveu à medição das lentes: previ 40k
cada e custaram **185k**, porque `grep` dirigido reduz o que se **lê**, não o que
se **julga**. Um verificador que reproduz de verdade abre arquivo, lê em volta e
caça guardas. A 30k por agente, **~12 milhões de tokens** — quatro vezes a
auditoria inteira.

### O que entra no lugar

**Verificação inline dos ALTA**, na conversa, com o trecho de código gravado em
`auditoria/00-estado.md`. Já foi feita para cerca de vinte deles ao custo de um
`grep` e um `sed` cada, e provou valer mais que a promessa da fase 3:

- **corrigiu o próprio relatório duas vezes.** O sintoma que este arquivo
  descrevia sobre a interpolação do estocástico estava errado — os dois
  indicadores saíam em degraus, não só um — e o teste novo do layout revelou um
  **segundo** defeito que nenhum agente tinha visto.
- **doze ALTA passaram de verificados a corrigidos**, e cinco deles com um teste
  que foi escrito antes e **visto falhar**. Isso é prova mais forte do que
  qualquer agente adversarial consegue dar: o `RenkoWickBoundsTest` dizia "the
  tail stops at 105.0" antes da correção e passa depois.

Os **MÉDIA seguem com a evidência do próprio agente**, que é como as auditorias
do `endeavour` sempre fizeram, e nunca deu problema.

### O que isso custa, dito com honestidade

Um falso positivo entre os MÉDIA não será pego até alguém tentar corrigi-lo. É
um custo real e aceito de propósito: corrigir um achado falso gasta uma hora,
enquanto a fase 3 gastaria dias de limite para prevenir isso.

---

## 3. Os 52 ALTA: fechados

**Todos corrigidos em 06 e 07/09/2026**, cada um com o teste que faltava. A suíte
foi de 449 para **500**. O detalhe achado por achado, com o commit de cada um,
está em `auditoria/00-verificacao.md`.

Um dos 52 era **falso positivo**, e a causa foi a partição da auditoria: eu
excluí o `OwnPeriodTest` do escopo do agente A8b porque a área A5 já o havia
coberto, e o agente então reportou que a média em escala maior não tinha teste
nenhum. Tinha.

### O que se aprendeu, e vale mais que a lista

**Onze dos 52 eram testes.** Não código quebrado: testes que passavam enquanto o
produto estava errado. Três tinham a mesma forma — `assertTrue(x <= limite)` sem
nenhuma asserção de piso — e cada um deixou passar um defeito real.

**A única forma de saber se um teste tem dentes é quebrar o produto e olhar.**
Isso foi feito para toda correção desta rodada, e pegou dois testes meus que não
afirmavam nada: um exercitava um ramo que a correção não tocava, outro fechava um
serviço antes de o trabalho falhar.

**Medir antes de corrigir mudou o que era para ser feito três vezes.** A auditoria
apontou "varredura de 1 M de barras" e o custo real era a rasterização; ia
construir uma pirâmide com cache e não precisou de nenhuma; ia mandar o recálculo
de indicador para outra thread e o custo inteiro era um `List<Double>`.

---

## 3b. As seis correções de `423d15a` estão quase todas desprotegidas

| correção | teste que a segura |
|---|---|
| `OwnScale.smooth` | ✅ `OwnPeriodTest`, três dentes novos |
| `OverlayLegend` | ❌ **nenhum** |
| `JobService` | ❌ nenhum |
| `MainWindow.exit` | ❌ `MainWindowTest:220` exercita só o `windowClosing` |
| `MainWindow` ternário | ❌ nenhum |
| `ReplayPanel` velocidade | ❌ nenhum |

**Cinco das seis podem ser desfeitas sem que nada avise.** Dar dentes a elas vem
antes de corrigir mais defeitos: a lição desta auditoria inteira é que correção
sem teste é meia correção, e quatro dos sete ALTA de teste existem exatamente
porque alguém consertou algo e não prendeu.

---

## 4. Trabalho adiado por decisão dele

- **Simulador** — replay em que dá para operar. Conceito fechado em
  `auditoria/../BACKTEST.md`; nada construído.
- **Backtest** — **não está mais adiado.** A linguagem de execução
  (`6b8f7ba`) e o motor (`f9ab9a3`) estão construídos; quatro das seis decisões
  se resolveram por evidência. Faltam o fatiamento com teste à frente e a tela.
  Ver `BACKTEST.md`.
- **Tradutor para NTSL / MQL5** — o vocabulário existe em
  `domain.trading.order`; o tradutor continua parado de propósito, até haver a
  família clássica escrita e pelo menos uma estratégia portada à mão. Ver
  `EXECUCAO-PORTAVEL.md`.
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
