<div align="center">

# Como este emulador foi construído

**A ordem importa: primeiro construímos a máquina. Só depois, o jogo.**

<sub>Este documento conta como o emulador foi feito, na ordem real, com os erros no meio.
O outro lado da história — a ROM autoral que roda nele — está em
[homebrew/cinza](homebrew/cinza/README.md).</sub>

<br><br>

<a href="README.md"><img src="https://img.shields.io/badge/⬅_VOLTAR-README-555?style=for-the-badge" alt="Voltar"></a>
<a href="homebrew/cinza/README.md"><img src="https://img.shields.io/badge/🕹️_PROXIMO_ATO-anatomia_da_CINZA-d29922?style=for-the-badge" alt="CINZA"></a>

</div>

---

## Sumário

1. [O método: ROMs de teste como oráculo](#1-o-método-roms-de-teste-como-oráculo)
2. [A CPU antes de qualquer pixel](#2-a-cpu-antes-de-qualquer-pixel)
3. [A porta serial como printf](#3-a-porta-serial-como-printf)
4. [O timer e a borda de descida](#4-o-timer-e-a-borda-de-descida)
5. [A PPU: pixel-FIFO, não scanline](#5-a-ppu-pixel-fifo-não-scanline)
6. [MBCs: o cartucho é um computador](#6-mbcs-o-cartucho-é-um-computador)
7. [APU: som é um efeito colateral do tempo](#7-apu-som-é-um-efeito-colateral-do-tempo)
8. [M-cycle: quando o total certo não basta](#8-m-cycle-quando-o-total-certo-não-basta)
9. [Game Boy Color](#9-game-boy-color)
10. [Save states e determinismo](#10-save-states-e-determinismo)
11. [A cadeia de validação](#11-a-cadeia-de-validação)
12. [Lições que só se aprendem apanhando](#12-lições-que-só-se-aprendem-apanhando)
13. [E então, o jogo](#13-e-então-o-jogo)

---

## 1. O método: ROMs de teste como oráculo

Um emulador tem um problema epistemológico: **como saber se ele está certo?** Olhar a tela
não basta — um jogo pode "parecer certo" com uma CPU sutilmente errada, e quebrar três
horas depois num efeito que depende de um half-carry.

A resposta da comunidade de emulação é usar **ROMs de teste** como oráculos: programas
escritos para hardware real, que exercitam um subsistema exaustivamente e **reportam o
veredito** ("Passed"/"Failed"). Se o teste passa no hardware e passa no emulador, o
emulador reproduz o hardware *naquele recorte*. Todo o projeto foi guiado por elas:

- **Blargg** (`cpu_instrs`, `instr_timing`, `mem_timing`) — CPU e timing;
- **mooneye** — edge cases de timer, banking e comportamento obscuro;
- **dmg-acid2 / cgb-acid2** — a PPU, comparada **pixel a pixel** com imagem de referência.

Cada uma dessas suítes roda como **teste JUnit**: `./gradlew test` executa as ROMs
dentro do emulador e falha o build se qualquer uma regredir. Não existe "acho que
funciona" — ou a suíte está verde, ou não está.

O desenvolvimento seguiu TDD no nível de componente (cada opcode, registrador e modo da
PPU nasceu com teste unitário antes) e as ROMs de teste como validação de integração.

## 2. A CPU antes de qualquer pixel

A primeira decisão estrutural: **começar pela CPU, com zero gráficos**. Parece
contraintuitivo (emulador é sinônimo de tela), mas a CPU é o único componente que dá para
validar por completo sem nenhum outro — e tudo depende dela.

A CPU do Game Boy é a Sharp **SM83** (parente do 8080/Z80): registradores A/F/B/C/D/E/H/L,
SP, PC, ~500 opcodes contando o prefixo `0xCB`. A implementação explora a regularidade da
tabela de opcodes — os bits do opcode codificam operando e operação:

```
0x40..0x7F = LD r,r'   → dst = (op >> 3) & 7, src = op & 7
0x80..0xBF = ALU A,r   → operação = (op >> 3) & 7, operando = op & 7
```

Dois `when` e meia dúzia de helpers (`getReg`/`setReg` indexados na ordem canônica
B,C,D,E,H,L,(HL),A) cobrem metade da tabela. O resto é caso a caso.

A armadilha clássica apareceu já aqui: **`0x76` está no meio do range `0x40..0x7F`** — pela
regularidade seria `LD (HL),(HL)`, mas o hardware o define como `HALT`. O branch do HALT
precisa vir *antes* do range no `when`, ou a CPU trava de um jeito que só se manifesta
minutos de emulação depois.

**Critério de saída da etapa:** os 10 sub-testes de `cpu_instrs` do Blargg passando. Sem
tela, sem timer, sem som — só CPU e memória.

## 3. A porta serial como printf

Como uma ROM de teste reporta o resultado se não há tela? Pela **porta serial** (link
cable): o Blargg escreve o byte em `0xFF01` e pulsa `0xFF02` com `0x81`. O emulador só
precisa capturar esses writes:

```kotlin
0xFF02 -> { if (v == 0x81) serialOutput.append(io[0x01].toChar()) }
```

Esse truque minúsculo foi a ferramenta de depuração mais valiosa do projeto inteiro: um
canal de texto saindo de dentro da máquina emulada, muito antes de existir um framebuffer.
Quando `06-ld r,r` imprimiu `Passed` pela primeira vez, a CPU estava — comprovadamente —
correta naquele recorte.

## 4. O timer e a borda de descida

O timer (DIV/TIMA/TMA/TAC) parece trivial e não é. A implementação ingênua ("a cada N
ciclos, incrementa TIMA") passa nos casos comuns e falha nos testes mooneye, porque o
hardware real deriva o TIMA de um **contador interno de 16 bits**: TIMA incrementa na
**borda de descida** de um bit específico desse contador (selecionado pelo TAC). Escrever
em DIV zera o contador — o que pode *gerar uma borda* e incrementar TIMA como efeito
colateral. Emular o mecanismo (e não o comportamento) reproduz esses quirks de graça.

O segundo nível de sutileza: no overflow, TIMA **não** recarrega TMA imediatamente — fica
lendo `0` por 4 ciclos antes da recarga e da interrupção (janela em que uma escrita
cancela tudo). Esse delay fechou o teste `tima_reload` do mooneye.

## 5. A PPU: pixel-FIFO, não scanline

A decisão de arquitetura mais importante do projeto. Há dois jeitos de desenhar:

- **por scanline** — renderiza a linha inteira de uma vez; simples, roda ~99% dos jogos;
- **pixel-FIFO** — emula o pipeline real do hardware: um *fetcher* busca tiles em passos
  de 2 dots (número do tile → byte baixo → byte alto → push de 8 pixels) e alimenta uma
  fila que despeja 1 pixel por dot, com o fine scroll descartando `SCX % 8` pixels no
  início da linha.

Escolhemos o FIFO porque o objetivo era compatibilidade de referência, não atalho. A PPU
roda uma máquina de modos dirigida por dots (OAM scan 80 → drawing ~172 → HBlank, 456
dots por linha, 154 linhas = 70224 dots por quadro a ~59,73 Hz), dispara VBlank/STAT, e
por cima entram janela (com contador de linha próprio) e sprites (OAM scan com limite de
10 por linha, prioridade por X no DMG e por índice no CGB, flips, 8×16).

A validação é o **dmg-acid2**: uma ROM que desenha um rosto usando exatamente os recursos
fáceis de errar (prioridades, janela, flips, 8×16). O teste compara o framebuffer
**pixel a pixel** com a imagem de referência. Ou os 23.040 pixels batem, ou o teste falha
apontando o primeiro divergente.

## 6. MBCs: o cartucho é um computador

O espaço de endereços do Game Boy enxerga 32 KiB de ROM — mas os jogos têm até 8 MiB. A
mágica é o **MBC** (Memory Bank Controller), um chip *dentro do cartucho* que intercepta
escritas em endereços de ROM (que seriam inúteis) e as usa como comandos de troca de
banco. Implementamos como estratégia: `Cartridge` lê o byte `0x147` do cabeçalho e
instancia `RomOnly`, `MBC1`, `MBC2` (com sua RAM interna de 512 nibbles), `MBC3` (com
relógio de tempo real) ou `MBC5`.

Os testes usam ROMs sintéticas com cada banco "assinado" com o próprio número — seleciona
o banco N, lê `0x4000`, espera N. O save de bateria é só a SRAM externa persistida (mais
o RTC, no MBC3).

## 7. APU: som é um efeito colateral do tempo

A APU tem 4 canais (2 ondas quadradas — uma com sweep —, wave RAM e ruído LFSR),
coordenados por um *frame sequencer* de 512 Hz que cadencia length/envelope/sweep. A
saída é PCM: a cada `4194304 / 48000` ciclos, mistura-se o estado dos 4 canais numa
amostra estéreo. Não há "gerador de áudio" separado — o som *é* o estado da máquina
amostrado no tempo certo, o que explica por que jogos fazem música mexendo em
registradores dentro de interrupções de timer.

## 8. M-cycle: quando o total certo não basta

A primeira versão da CPU executava a instrução inteira e devolvia o total de ciclos — e
`instr_timing` (que mede *totais*) passava. Aí rodamos `mem_timing` e ele reprovou: esse
teste mede **em qual ciclo, dentro da instrução, cada acesso à memória acontece**.

A correção foi tornar a CPU **M-cycle-accurate**: cada `readByte`/`writeByte` custa 4
ciclos-T e avança PPU/APU/timer *no ato*, via callback:

```kotlin
private fun readByte(a: Int): Int { val v = mem.read(a); tick(4); return v }
```

Os ciclos internos (sem acesso à memória — ex.: o ajuste de SP num `PUSH`) são emitidos
na posição correta nos opcodes de pilha, e o restante reconciliado no fim para manter os
totais exatos. O refactor foi feito numa branch com a suíte inteira como rede de
segurança: se `cpu_instrs` ou os acid2 regredissem, era revert. Passou tudo — e
`mem_timing` junto.

## 9. Game Boy Color

O CGB não é outro console — é o mesmo, com mais banco em tudo: 2 bancos de VRAM (o
segundo guarda **atributos por tile**: paleta, banco, flips, prioridade), 8 paletas de BG
+ 8 de OBJ em RGB555, WRAM de 8 bancos, *double-speed* (a CPU dobra; PPU/APU não) e
**HDMA** (transferência para VRAM em blocos de 16 bytes por HBlank).

Um detalhe de fidelidade: a conversão RGB555→RGB888 usa **replicação de bits**
(`(c << 3) | (c >> 2)`), a mesma das imagens de referência — foi isso que permitiu ao
cgb-acid2 passar pixel-perfect. As cores que o jogo define são as cores que aparecem;
qualquer "correção de cor de LCD" é opcional e desligada por padrão.

## 10. Save states e determinismo

Save state é serializar **todo** o estado da máquina. O teste é elegante: rode 10
quadros, tire o snapshot, rode mais 40 e guarde o framebuffer; restaure o snapshot, rode
os mesmos 40 — os framebuffers devem ser **idênticos**. Se qualquer variável de estado
ficar fora do snapshot, os caminhos divergem e o teste acusa. Determinismo vira oráculo.

## 11. A cadeia de validação

| Elo | Ferramenta | O que prova |
|---|---|---|
| CPU | Blargg `cpu_instrs` (10 sub-testes) | todos os opcodes e flags |
| Timing de instrução | Blargg `instr_timing` | totais de ciclos |
| Timing de barramento | Blargg `mem_timing` | posição de cada acesso (M-cycle) |
| Interrupções | Blargg `02-interrupts` | despacho e prioridade |
| PPU (DMG) | **dmg-acid2** | render pixel-perfect |
| PPU (CGB) | **cgb-acid2** | cor/atributos pixel-perfect |
| Timer, MBCs, DAA | **mooneye** (24 ROMs) | edge cases de hardware |
| Save state | teste de determinismo | completude do snapshot |
| Tudo | `./gradlew test` | nada regride sem a suíte quebrar |

## 12. Lições que só se aprendem apanhando

- **`HALT` mora dentro do range dos `LD`.** Ordem dos branches importa.
- **Emule o mecanismo, não o comportamento.** O falling-edge do timer reproduz os quirks
  de graça; a versão "incrementa a cada N ciclos" precisa de remendo para cada um.
- **Total de ciclos certo ≠ timing certo.** `instr_timing` verde com `mem_timing`
  vermelho é o sintoma clássico de CPU instruction-stepped.
- **Golden tests pagam o custo.** Comparar 23.040 pixels parece bruto, mas aponta o
  primeiro pixel divergente — que localiza o bug (prioridade? paleta? scroll?) melhor
  que qualquer log.
- **A fronteira certa faz o resto ser fácil.** O core não conhece Swing nem
  arquivo — recebe bytes, devolve framebuffer/amostras. Por isso o mesmo núcleo roda em
  CLI e desktop sem tocar em uma linha da emulação — e é a mesma fronteira
  (a interface `EmulatorCore`) que abre a porta para os próximos consoles.
- **Nome de teste em Kotlin não pode ter `:`** — e um teste que não compila parece um
  teste que passou, se você olhar só o cache. (`--rerun-tasks` é seu amigo.)

## 13. E então, o jogo

Com a máquina pronta e validada, veio a pergunta natural: *ela roda o quê?* ROMs de teste
provam correção, mas não têm alma. A resposta foi construir **o outro lado do cartucho**:
a [CINZA](homebrew/cinza/README.md) — uma ROM homebrew autoral de Game Boy Color (MBC5,
128 KiB), gerada por scripts que escrevem os formatos do GB Studio, e executada aqui.

A ordem foi essa — **emulador primeiro, jogo depois** — e não por acaso: só entende de
verdade uma plataforma quem já implementou os dois lados dela. O artigo da CINZA percorre
o mesmo hardware deste documento, mas do ponto de vista de quem *escreve para ele* em vez
de quem o *implementa*.

---

<div align="center">

**Fim do Ato 1.** A máquina está pronta — agora leia como nasceu o jogo que roda nela.

<a href="homebrew/cinza/README.md"><img src="https://img.shields.io/badge/🕹️_ATO_2-anatomia_da_ROM_CINZA-d29922?style=for-the-badge" alt="CINZA"></a>
<a href="README.md"><img src="https://img.shields.io/badge/⬅_VOLTAR-README-555?style=for-the-badge" alt="Voltar"></a>
<a href="CONTRIBUTING.md"><img src="https://img.shields.io/badge/🤝_CONTRIBUIR-fork_e_PR-2ea44f?style=for-the-badge" alt="Contribuir"></a>

</div>
