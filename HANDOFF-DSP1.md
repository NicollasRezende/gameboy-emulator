# Handoff — DSP-1 (Super Mario Kart) e estado do SNES

> Atualizado na sessão de 2026-07-19. Branch `main`.
> Este arquivo é temporário (pode apagar depois). Serve pra retomar sem perder contexto.

## 🧬 LLE do DSP-1 (µPD7725) — implementado, core validado, OPT-IN (2026-07-20)

O usuário forneceu o `dsp1.bin` (MD5 4865AC61 = DSP-1 original; também `dsp1b.bin`
C8BFB983). Implementei o coprocessador em **baixo nível** rodando o microcódigo real:
`Upd7725.kt` (CPU Harvard 16-bit: prog ROM 2048×24, data ROM 1024×16, RAM 256×16, ALU
A/B + flags, mul K×L→M:N contínuo, DP/RP, pilha, famílias OP/RT/JP/LD). Wiring via
`Dsp1Core` (interface comum HLE/LLE). Commit `5893610`.

**Formato do dsp1.bin** (big-endian, do caitsith2.com/snes/dsp): programa 0x0000-0x17FF
(2048×3 bytes), dados 0x1800-0x1FFF (1024×2 bytes).

**Handshake decifrado:** o microcódigo NÃO trava em SRC/DST=DR — faz **polling** com
`JP brch=0BE na=self` (JRQM = jump-if-RQM-set para si mesmo). Escrever/ler o DR seta
RQM=1; o JRQM auto-loop com RQM setado = busy-wait pelo host → para o `run()`; o host
acessando o DR baixa RQM e destrava. (Modelar isso como "stall na instrução" NÃO funciona.)

**Bugs de ALU corrigidos no caminho** (confirmados contra a arquitetura do chip):
overflow do ADD (era `& (p^r).inv()`, deve ser `& (p^r)`), DEC/INC operam sobre 1 (não P),
SHR1 é aritmético (preserva o sinal, NÃO rotaciona com carry), SHL2/SHL4 preenchem 1s e
limpam C, lógicas/shifts limpam OV0/OV1.

**Estado — core VALIDADO, integração PENDENTE:**
- ✅ O SMK **boota** com o LLE (passa do autoteste do DSP) e **comunica** (handshake OK).
- ✅ `Upd7725Test` passa: Op00 (mul 0.5×0.5=0x2000), Op02 (parameter → Vva=-78), Op04
  (sin/cos; `cos(0)·0x4000 = 0x3FFF` porque 1.0 é 0x7FFF no chip). Multiplicador, ALU,
  divisão, seno/cosseno e data ROM estão **bit-exatos**.
- ❌ **Op0A (raster) não produz a matriz Mode 7 no jogo** → pista sai cinza. O Op0A
  isolado (só Op02+Op0A) retorna zero; no SMK retorna valores mas errados (0xFEAC onde
  o cálculo dá ~0x0040). É um bug de **estado/sequência** (o Op0A depende de setup de
  comandos anteriores que não reproduzi). O SMK também emite ~30× menos comandos ao DSP
  no LLE que no HLE (Op06: 334 vs 11017) — a demo não simula a corrida por completo.
- **Por isso o LLE é OPT-IN** (`SNES_DSP1_LLE=1`); o padrão é o HLE, que renderiza a
  pista. Sem regressão.

**Próximo passo (continuar o LLE):** isolar o Op0A. Reproduzir a sequência exata de
comandos do fluxo do SMK antes do primeiro Op0A (via `--dsp` no CLI com `SNES_DSP1_LLE=1`,
que loga o fluxo cru W/R do LLE) e comparar o retorno com um emulador de referência
(bsnes-mercury/ares tocando o mesmo `dsp1.bin`). Suspeitas: (1) o Op0A precisa de um
comando de setup (attitude/objective) que não está sendo emitido; (2) um bug de timing
(meu DSP responde instantâneo); (3) um bug em Op06/Op28 que faz a simulação divergir.
Ferramentas prontas: `Upd7725.trace`/`pcHist`/`hotPcs` (loops do microcódigo),
`Upd7725.rawLog` (fluxo do DR), `Upd7725Test` (loop de debug rápido sem rodar o SMK).

## ✅ CORRIDA 1P JOGÁVEL: a pista renderiza (fix de overscan, 2026-07-19)

Sintoma reportado: dentro da corrida real (jogada pelos menus, não a demo/attract) a tela
ficava **preta com só os karts** (sprites). Bug **pré-existente** (reproduz em `2d89692`,
antes do DSP-1) — só a demo/attract funcionava, e o usuário notou ao jogar de verdade.

**Causa raiz: o PPU ignorava o SETINI (`$2133`) e fixava o VBlank/NMI na L225.** O SMK liga
**overscan** (239 linhas → VBlank em L240) na corrida 1P. Com o VBlank errado em L225, o NMI
disparava cedo e a ordem das escritas de `$420C` (HDMAEN) no vblank invertia (o IRQ4@L234,
que faz `STZ $420C`, caía DEPOIS do NMI@L225, que faz `STA $420C=FE`). Terminando em `00`, o
`initHdma` do frame seguinte não inicializava os canais da matriz Mode 7/BGMODE/TM → modo 1,
só OBJ, pista/céu/HUD sumiam. Fix em `dfb6490`: PPU lê SETINI bit 2 e expõe `vblankLine()`.

Como foi achado (método): `--pchist` (histograma de PC → loop de wait-vblank `LDA $44/BEQ`,
que era normal), `--watch <reg> --watchseq` (ordem das escritas de `$420C` por frame/scanline),
hook `onIrq` (scanlines dos IRQs: L107/114/211/**234**), e desmontagem da rotina de IRQ
(`$2E==2` → VTIME=234). Ferramentas commitadas em `ee1d21a`.

### ⏳ Refinamento pendente: a VIEWPORT INFERIOR do 1P (retrovisor/mapa)

A corrida 1P mostra a pista no topo (perfeita), mas a metade inferior tem distorções. NÃO é
um mapa top-down simples — é a viewport **alternável** do SMK 1P (retrovisor OU mapa aéreo,
o jogador troca com o botão **X**). Investigada a fundo (setupLog ring, trace HDMA por canal
`--tracech`, desmontagem):
- **Retrovisor (padrão / Y):** renderiza perspectiva olhando pra trás. Vem do meu Op0A com
  Vs=-102, aas=0x7F00 (≈180°). O HDMA está CERTO (das sequencial, sem dessincronização) — os
  valores de matriz vêm da tabela preenchida a partir do meu Op0A. Distorção: o centro/escala
  saem imprecisos (a viewport mostra char 0 / "fora do mapa" — o quadriculado vermelho — em
  vez do terreno real, e o horizonte cai no meio da viewport em L~139).
- **Mapa aéreo (botão X):** fica PRETO. Op02 com eyeZ≈4474 (câmera bem alta); meu HLE não
  gera a projeção aérea (a matriz sai degenerada → backdrop).
- **Raiz:** precisão do DSP-1 HLE nos regimes que a viewport 1 não exercita (Vs além do
  horizonte, câmera alta). A viewport 1 (Vs=-75) cai no regime "bom" do HLE e renderiza certo.
- **Caminho definitivo:** LLE com `dsp1.rom` (roda o microcódigo real → matriz exata). É o
  caminho (b) do handoff original. Refinar o HLE pra esses regimes é possível mas incerto sem
  a referência do chip. A corrida é JOGÁVEL (a viewport principal é o que importa pra jogar).


## ✅ MARCO ATINGIDO: a pista Mode 7 do SMK RENDERIZA

A demo da corrida (split-screen 2P) mostra asfalto com faixas amarelas tracejadas, areia,
cerca vermelha no horizonte, perspectiva/rotação/escala corretas e os karts da CPU **sobre
a pista** (Op06). Commits: `d37ab87` (fix HDMA repeat) e `a74b732` (DSP-1 HLE).

### A decisão (a) HLE vs (b) LLE — como foi resolvida

O `dsp1.rom` **não estava** em `rooms/` no início da sessão, então o caminho (b) ficou
bloqueado no usuário. Seguindo a árvore do handoff anterior, fui de (a) HLE best-effort com
validação visual obrigatória — e convergiu. **(b) continua desejável**: largue o
`dsp1.rom`/`dsp1b.rom` (~2-8 KB) em `/home/sea/projetos_pessoais/rooms/rooms/` e a próxima
sessão implementa o core µPD77C25 (LLE) para fechar as semânticas que ficaram aproximadas
(lista abaixo). A infra de autoload ainda NÃO existe — criar junto com o core.

### Descobertas de protocolo (validadas contra o fluxo cru, NÃO redescobrir)

1. **Sync bytes**: em idle, byte com bit 7 (0x80) é padding/sync ignorado. O SMK manda
   128×`80` no boot e 1×`80` entre comandos.
2. **Enquadramento por-comando**: tabela fixa de palavras in/out por comando (ver
   `FRAME` em `SnesDsp1.kt`). A heurística write→read antiga desalinhava tudo.
3. **Op0A streaming**: 1 palavra (Vs) de entrada; após ler An,Bn,Cn,Dn o chip incrementa
   Vs e serve a próxima linha sem novo comando. O SMK lê 97 linhas por stream.
4. **Op01 = prime do raster com (azimute, zênite), 2 palavras, sem saída** (a doc pública
   diz "attitude, 4 palavras" — EMPIRICAMENTE no SMK são 2; com 4 o comando engole o
   `0A Vs` seguinte e metade da split-screen fica cinza). Na largada o SMK varre
   w0=0x0100..0x4000 (quadrante de 90° em 64 passos de 1.4°) e grava um stream de raster
   por passo: **pré-computa 64 tabelas HDMA rotacionadas** e por frame só re-aponta os
   headers dos 4 canais HDMA indiretos ($211B..$211E, modo 2, headers em $00:0640/4D/5A/67,
   dados em $7E). O `Op04(azimute dobrado no quadrante)` por frame é o jogo explorando a
   simetria de quadrante.
5. **Sequência por frame na corrida**: `Op00` (mults), `Op02` por câmera (2× na split),
   ~9×`Op06` (projeção dos karts), ~8×`Op04` (heading×velocidade dos AIs), `Op28`.
   Rasters SÓ na inicialização da corrida (129 streams = 2 buffers × 64 ângulos + 1).
6. **Geometria que bate com o jogo real**: com os parâmetros do SMK
   (Les=256, Azs=0x3400=73.125°), Vva=−78; o SMK então pede raster de Vs=−74 (=Vva+4).
   Escala de cruzeiro em y=90 ≈ 0.28 (8.8: 0x48). Câmeras: boot Lfe=0x40, corrida Lfe=0x100.

### O que ficou aproximado (fechar com LLE quando o dump chegar)

- **Vof sempre 0** e sem clipping de zênite (tabela MaxAZS do chip real) — o SMK da demo
  não clipa (Azs fixo 73° < ~79.6°), mas jogos com câmera mais vertical podem precisar.
- **Op06 atrás da câmera**: satura (H=0x8000 etc.) em vez do overflow real do chip.
- **Op06 M**: escala em 8.8 (Les/depth·256) — plausível visualmente, não confirmada.
- **Attitude/objective/subjective/scalar/gyrate (0x11/21, 03/13/23, 0B/1B/2B, 0D/1D/2D,
  14)**: enquadramento certo, saída zero (Pilotwings vai precisar).
- **Op1F (dump da data ROM)**: serve zeros — jogo que checar a ROM interna vai perceber.
- **Artefato visual menor**: tiras arco-íris nas linhas quase-horizonte (esq. do topo) —
  amostragem fora do mapa com SEL=0xC0 (fill de char 0), parecido com o shimmer real mas
  mais forte. Investigar junto com o LLE (os valores reais do chip saturam diferente).
- Matemática interna em Double (bordas s16 idênticas ao chip dentro de ±1 LSB nas escalas
  visíveis). O chip real é ponto fixo 1.15 com tabelas na data ROM.

### Bug independente corrigido de brinde

- **HDMA modo repeat**: bloco 0x81..0xFF que esgotava os 7 bits baixos deixava o canal
  ocioso até 127 linhas (condição de recarga exigia bit7 limpo). `d37ab87`.

## Arquivos-chave

- `snes/src/main/kotlin/snes/SnesDsp1.kt` — protocolo (fases IDLE/PARAMS/OUTPUT, tabela
  `FRAME`, streaming do 0A) + geometria (Op02/06/0A/0E) + diagnóstico (transLog/rawLog/
  setupLog em ring).
- `snes/src/main/kotlin/snes/SnesDma.kt` — HDMA + `chLog` (config dos canais por initHdma).
- `snes/src/main/kotlin/snes/SnesPpu.kt` — `m7Log`/`m7LineLog` (registradores M7 por linha).
- `cli/.../Main.kt` — `--dsp` imprime tudo isso; `--keys` para roteiro de input.
- Testes: `SnesDsp1Test.kt` pina o protocolo e a geometria com os parâmetros reais do SMK.

## Comandos úteis

```bash
cd /home/sea/projetos_pessoais/emulator
./gradlew :cli:installDist -q && ./gradlew :snes:test

# demo do SMK com diagnóstico completo (a demo começa ~frame 1840)
./cli/build/install/cli/bin/cli "/home/sea/projetos_pessoais/rooms/rooms/Super Mario Kart (Europe).sfc" \
  --screenshot /tmp/smk.png --frames 2500 --dsp

# regressões visuais rápidas
# SMW título: --frames 1200 | Zelda intro: --frames 1500 | SMK título: --frames 4000
```

## Estado geral do emulador (roadmap)

- **GB/GBC**: qualidade de referência. **NES**: completo (mappers 0-3, MMC3, DMC).
- **SNES**: CPU/PPU/SPC700/DSP validados; **SMW joga**, **Zelda LttP roda intro/menus**,
  **SMK renderiza a corrida com a pista Mode 7** (falta polish: artefato do horizonte,
  jogar com --keys até uma corrida real 1P pra validar além da demo).
- Próximos candidatos: LLE do DSP-1 (quando `dsp1.rom` aparecer em rooms/), Pilotwings
  (usa os comandos attitude que estão em zero), Star Fox (SuperFX, chip maior).
