# TNO operado: a medição

**13/09/2026.** Três escalas, uma rodada cada, aprovadas antes de rodar. A
primeira foi uma célula só; as outras duas vieram depois, a pedido — e é por
isso que **isto é uma varredura**, e o t precisa ser lido contra o número de
tentativas.

## A configuração, igual nas três

| | |
|---|---|
| série | `winfull-1m`, base inteira — 824.881 barras de 1m |
| decisão | 5m, 10m, 15m |
| execução | 1m, as barras armazenadas |
| estratégia | `MomentumCross` com os defaults do porte |
| PMO | 1 / 35 / 20 / 10, escala 10 — os valores do `JorgeReis_TNO_PMO.src` |
| alvo | 2R · stop no extremo do candle do sinal · folga 200 pts |
| contratos | 1 |
| custo | 6,5 pontos por giro por contrato |

**Tick a tick não foi usado, e a razão é de tamanho:** 824.881 minutos a ~1.500
ticks sintéticos por minuto dá ~1,2 bilhão de passos. Executar nas barras de 1m
é bem mais fino que a escala de decisão e cabe na memória. As barras ambíguas
ficaram em 68, 19 e 9 — o desempate stop-contra-alvo quase não foi exercido.

## Os números

| | 5m | 10m | 15m |
|---|---|---|---|
| barras de decisão | 165.288 | 83.518 | 55.762 |
| operações | 9.156 | 4.401 | 2.960 |
| bruto | −25.145 | +30.425 | +19.175 |
| custo | 59.514 | 28.607 | 19.240 |
| **líquido** | **−84.659** | **+1.819** | **−65** |
| **bruto/operação** | **−2,75** | **+6,91** | **+6,48** |
| desvio por operação | 268,4 | 384,6 | 441,0 |
| erro padrão | 2,80 | 5,80 | 8,11 |
| **t** | **−0,98** | **+1,19** | **+0,80** |
| acerto | 35,9% | 39,1% | 39,9% |
| queda máxima | −99.590 | −40.108 | −39.890 |
| exposição | 22,2% | 21,7% | 21,0% |
| barras ambíguas | 68 | 19 | 9 |

Trivial (comprar e segurar, 1 contrato): **+81.940 pts**.

## O que isso diz

**Nenhuma das três é distinguível de zero.** O maior t da varredura é +1,19.
Com três tentativas, a probabilidade de o acaso entregar um máximo de 1,19 ou
melhor é **31%** — e o valor esperado do maior de três sorteios normais é 0,85.
O resultado está dentro do ruído da própria busca. O limiar de Bonferroni para
5% com três células seria t ≈ 2,13.

**O achado estrutural vale mais que os t.** O bruto por operação estabiliza em
+6,91 e +6,48 nas duas escalas grossas, e o custo é 6,5 por giro: a estratégia
pousa em cima do próprio custo. O líquido de +1.819 e de −65 é consequência
disso, não coincidência. Em 5m o giro dobra, a vantagem bruta some (−2,75) e o
custo cobra o dobro — as duas coisas na mesma direção.

**Sobre poder, que é onde isto poderia enganar.** Em 10m, n = 4.401 e desvio
385 dão erro padrão 5,80: é suficiente para ver uma vantagem de 12,5 pontos —
o piso de [[janela-de-busca-viavel]] — com t ≈ 2,2. Medimos 6,91, que põe 12,5
a 0,96σ da medição. **Improvável, mas não descartado.** A base não separa "sem
vantagem" de "vantagem no limite do viável".

**Conclusão: ausência de evidência, e não refutação.** O TNO operado assim não
demonstra vantagem em nenhuma das três escalas, e a que mais se aproxima empata
com o custo.

**O acerto não é o vilão.** Num alvo de 2R o equilíbrio bruto fica em ~33,3%, e
as três ficaram acima (35,9%, 39,1%, 39,9%) — o que bate com o bruto perto de
zero ou levemente positivo. A conta limpa de R não fecha exatamente porque nem
toda perda é 1R: há saídas por inversão, por fim de pregão, e stops que executam
além do nível dentro da folga.

**O trivial não é nulo justo** — a estratégia fica no mercado ~21% do tempo e
nos dois lados —, mas está na tabela porque é a referência que a janela mostra.

## O que NÃO foi medido

- **Qualquer outro jogo de períodos.** Os quatro vieram do indicador do Profit e
  não foram tocados. Mexer neles e escolher o melhor aumenta o número de
  tentativas, e aí valem as regras de [[selecao-overfitting-medido]].
- **Andar para frente.** Cada escala é a base inteira de uma vez. Não houve
  separação entre base de busca e base de teste porque nada foi ajustado — mas
  se alguma destas três for escolhida por ter ganhado, a escolha PRECISA ser
  validada fora da amostra antes de valer.
