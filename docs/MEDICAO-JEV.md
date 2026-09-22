# O Jev decidindo direção no WIN — seis desenhos, e o que cada um respondeu

Medido em 22/09/2026. Modelo `jev-1.13.0` da TypeSafe, o "System One" que responde
perguntas tipadas contra um estado. Custo total do estudo: **US$ 0,62**.

**Resultado: não há sinal direcional utilizável.** O melhor desenho sobreviveu ao
teste fora da amostra com `t = 0,78` e um Brier pior que o de uma constante.

## A ressalva que vem antes de qualquer número

O Jev é pré-treinado num mundo que inclui estas barras. **Nenhum resultado
histórico aqui seria evidência de vantagem**, nem se tivesse dado positivo — e
esse erro não se mede, só se evita. O que o histórico pode responder, e foi o que
se perguntou, é mais estreito:

- ele **discrimina**? As decisões confiantes diferem das outras?
- a probabilidade que ele diz é **calibrada** contra a frequência observada?

Nenhuma das duas precisa que o dado seja inédito para o modelo.

## O aparato

| peça | o que faz |
|---|---|
| `tools/JevStates` | caminha a série e escreve um estado por momento de decisão |
| `tools/JevCandleStates` | acrescenta a sequência de candles |
| `tools/JevScenarioStates` | a grade: escala do candle × indicadores |
| `tools/jev_decidir.py` | pergunta em lote e em paralelo, grava CSV, retoma |
| `tools/JevScore` | pontua cada decisão, com os nulos ao lado |
| `tools/JevCalibration` | compara a probabilidade dita com a frequência real |
| `domain/.../JevAdvice` | opera as decisões dentro do backtest, offline |

**Nada chama a rede durante um backtest.** Não é só escala: um backtest que
consulta modelo probabilístico responde diferente a cada rodada, e número que
muda sozinho não é medição. As respostas são congeladas em arquivo, e o arquivo
é entrada da rodada como a série de preços é. Há teste para isso.

## Geometria, igual em todos os desenhos

Alvo 300, stop 200, custo 6,5 pontos por giro. O stop ganha empate dentro da
barra. **Uma entrada aleatória acerta o alvo com probabilidade 200/(300+200) =
40,0%** — é o nulo contra o qual tudo abaixo se lê. O equilíbrio, já com custo,
é `p > 0,413`.

## Os seis desenhos

| # | estado | pergunta | n | média | `t` | acerto |
|---|---|---|---:|---:|---:|---:|
| 1 | 11 escalares, amostra espalhada | que posição tomar | 298 | +4,0 | 0,28 | 42,6% |
| 2 | 11 escalares, sequencial | que posição tomar | 5.172 | −5,0 | −1,49 | 40,4% |
| 3 | 20 candles de 15m | que posição tomar | 232 | −9,8 | −0,62 | 39,7% |
| 4 | grade de 12 (busca) | que posição tomar | 500/célula | melhor +7,2 | 0,66 | 43,4% |
| 5 | 10m + ambos (busca) | **probabilidade de movimento** | 1.512 | +7,7 | 1,22 | 43,1% |
| 6 | **o mesmo, fora da amostra** | idem | 3.649 | **+3,1** | **0,78** | 42,8% |

### 2 — os escalares, e a conta que explica tudo

Com 5.172 decisões: acerto de **40,4%** contra os 40,0% do acaso. A conta fecha
redonda:

```
0,404 × 300 − 0,596 × 200 − 6,5 = −4,5     observado: −5,0
```

O resultado inteiro é geometria mais custo. O modelo não contribuiu direção.

### 3 — os candles pioraram

Nos **mesmos 232 instantes**, o acerto caiu de 42,4% para 39,7% custando 61% mais
tokens. Duas mudanças de comportamento: ficou menos vendido (de 83% para 69%) e
**perdeu confiança** — de 48 decisões acima de 0,70 para uma. Mais informação o
deixou mais indeciso, não menos.

### 4 — a grade de doze

5m/10m/15m × {nenhum, estocástico 8/3, Bollinger 20/2, ambos}, 500 decisões cada,
em 2020–2023.

| célula | média | `t` | | célula | média | `t` |
|---|---:|---:|---|---|---:|---:|
| 10m + ambos | +7,2 | 0,66 | | 15m + ambos | −2,9 | −0,26 |
| 5m + estocástico | +6,4 | 0,58 | | 5m + ambos | −3,1 | −0,29 |
| 15m + bollinger | +2,9 | 0,27 | | 10m + estocástico | −3,4 | −0,32 |
| 10m + bollinger | +0,9 | 0,09 | | 15m + estocástico | −9,7 | −0,89 |
| 5m + bollinger | −0,2 | −0,01 | | 15m + nenhum | −9,7 | −0,90 |
| 5m + nenhum | −0,9 | −0,08 | | 10m + nenhum | −10,0 | −0,93 |

Maior `t` = 0,66. Com 12 tentativas o limiar é `t ≥ 3,0`, e o maior `t` esperado
sob ruído puro seria ~2,2 — **a grade é menos dispersa que ruído**, que é o que
acontece quando todas as células medem quase a mesma coisa.

Um padrão sobrevive ao olhar: **as três piores células são exatamente as três
`nenhum`**, e toda célula com indicador ficou acima da irmã sem indicador na
mesma escala. Seis comparações pareadas — é um padrão que valeria testar, não um
achado.

### 5 e 6 — a pergunta reformulada, e o teste fora da amostra

Em vez de "que posição tomar", perguntou-se o **evento que de fato é operado**:
sobe 300 antes de cair 200? cai 300 antes de subir 200? O lado sai da comparação,
e abaixo de 0,413 não opera.

Pré-registro antes de rodar 2024–2026: passa se `t ≥ 2,0` **e** o Brier bater a
constante. Falha se qualquer uma faltar.

```
                     busca 2020-23      fora 2024-26
media por decisao        +7,7               +3,1
t                         1,22               0,78
Brier do modelo         0,2413             0,2397
Brier da constante      0,2397             0,2386
ganho sobre ela          -0,7%              -0,5%
vies                    -0,018             -0,020
```

**Falhou nos dois.** A média encolheu 60% fora da amostra — o formato clássico de
um resultado que era, em boa parte, a própria busca.

### O que replicou nos dois recortes

A calibração por faixa, fora da amostra, com 4.600 eventos:

| disse | n | ocorreu | erro |
|---|---:|---:|---:|
| 0,26 | 513 | 0,339 | +0,077 |
| 0,32 | 849 | 0,373 | +0,052 |
| 0,37 | 1.389 | 0,401 | +0,030 |
| 0,42 | 1.360 | 0,417 | −0,002 |
| 0,46 | 481 | 0,399 | **−0,061** |
| 0,50 | 8 | 0,250 | −0,254 |

Três coisas atravessaram o corte:

1. **O viés é pequeno** (−0,020). Reformular a pergunta consertou o nível, e isso
   é reprodutível — a pergunta antiga tinha teto de 0,55 e mediana 0,44.
2. **As quatro primeiras faixas são monótonas.** Há discriminação real, mas
   **comprimida demais para pagar**: ele varre 0,26 a 0,42 sobre um intervalo
   verdadeiro de 0,34 a 0,42.
3. **A inversão na quinta faixa também replicou.** Acima de 0,45 ele erra para o
   outro lado. Sua maior confiança aponta para o lado errado, nos dois recortes.

O Brier pior que o da constante é o veredito: substituir cada previsão pelo
número fixo 0,393 teria dado resultado marginalmente melhor.

## Por que o lado invertido dá `t = −4,03` e isso não é vantagem

É o número mais forte do estudo e o mais fácil de ler errado. Fazer o contrário
do que ele diz perde consistentemente — mas perder de propósito não vira ganho ao
inverter o sinal, porque custo e geometria sangram nos dois sentidos. Ele diz só
que o modelo **não está sistematicamente errado**, o que é diferente de estar
certo.

## Onde parar

Seis desenhos, ~15.000 decisões. Um sétimo precisaria de `t ≥ 2,9` para contar, e
nada no que se viu sugere que esteja perto. O aparato fica: responde em vinte
minutos e menos de um dólar se um modelo novo presta.

## Um defeito de método, registrado para não repetir

Na primeira tentativa da grade, o script de amostragem usou
`caminho.replace('cenarios/e_', 'cenarios/busca_')`, e o `glob` do Windows devolve
barra invertida. O nome de saída ficou igual ao de entrada e **cada um dos doze
arquivos completos foi sobrescrito pela própria amostra de 500 linhas**. O laço
seguinte não achou o que processar e terminou com **código 0** — foi isso que
fez parecer que tinha rodado.

Custo: zero em dinheiro, seis minutos de CPU. A versão nova tem
`assert saida != caminho` antes de cada gravação: um script que pode destruir a
própria entrada precisa de uma linha que recuse fazê-lo, e não da atenção de quem
o escreveu.
