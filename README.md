<div align="center">

# 🎮 gameboy-emulator

**Primeiro construímos a máquina. Depois, o jogo que roda nela.**

Um emulador de Game Boy & Game Boy Color escrito do zero em Kotlin — preciso ao nível de
referência, validado por testes — e uma **ROM homebrew autoral** feita para rodar nele.
Os dois lados do cartucho, construídos aqui.

![License](https://img.shields.io/badge/license-MIT-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-orange?logo=openjdk&logoColor=white)
![Tests](https://img.shields.io/badge/tests-641%20passing-brightgreen)

<br>

<a href="CONSTRUCAO.md"><img src="https://img.shields.io/badge/📖_A_MAQUINA-como_o_emulador_foi_construido-1f6feb?style=for-the-badge" alt="Como foi construído"></a>
<a href="homebrew/cinza/README.md"><img src="https://img.shields.io/badge/🕹️_O_JOGO-anatomia_da_ROM_CINZA-d29922?style=for-the-badge" alt="Anatomia da CINZA"></a>
<br>
<a href="#-rodando"><img src="https://img.shields.io/badge/⬇️_INSTALAR-e_jogar_em_2_comandos-8957e5?style=for-the-badge" alt="Instalar"></a>
<a href="CONTRIBUTING.md"><img src="https://img.shields.io/badge/🤝_CONTRIBUIR-fork_e_pull_request-2ea44f?style=for-the-badge" alt="Contribuir"></a>

<br><br>

<img src="screenshots/dmg-acid2.png" width="240" alt="dmg-acid2"> <img src="screenshots/cgb-acid2.png" width="240" alt="cgb-acid2"> <img src="screenshots/blargg.png" width="240" alt="Blargg cpu_instrs">

<sub>Renderizado pelo próprio emulador · validado **pixel a pixel** contra as imagens de referência da comunidade.</sub>

</div>

---

## 📖 A história, em dois atos

<table>
<tr>
<td width="50%" align="center" valign="top">

### 🏗️ Ato 1 — A máquina

<sub>CPU SM83 M-cycle · PPU pixel-FIFO · APU · MBCs<br>
o método, os bugs e as lições — na ordem em que aconteceram</sub>

<br><br>

<a href="CONSTRUCAO.md"><img src="https://img.shields.io/badge/LER-CONSTRUCAO.md-1f6feb?style=for-the-badge" alt="Ler CONSTRUCAO.md"></a>

</td>
<td width="50%" align="center" valign="top">

### 🕹️ Ato 2 — O jogo

<a href="homebrew/cinza/README.md"><img src="homebrew/cinza/img/telaInicial.png" width="260" alt="CINZA — tela de título"></a>

<sub><b>CINZA</b> — ROM autoral de GBC (MBC5, 128 KiB), gerada por scripts,<br>rodando no emulador deste repositório</sub>

<br><br>

<a href="homebrew/cinza/README.md"><img src="https://img.shields.io/badge/LER-homebrew%2Fcinza-d29922?style=for-the-badge" alt="Ler o artigo da CINZA"></a>

</td>
</tr>
</table>

## 🕹️ Rodando de verdade

<div align="center">

<img src="screenshots/nes-zelda.png" width="420" alt="The Legend of Zelda rodando no core NES"> <img src="screenshots/nes-megaman3.png" width="420" alt="Mega Man 3 rodando no core NES">

<sub><b>The Legend of Zelda</b> (MMC1) e <b>Mega Man 3</b> (MMC3) rodando no app desktop. O
gradiente do céu do Mega Man 3 é um efeito por scanline dirigido pelo <b>IRQ do MMC3</b> —
prova visual de que o mapper e a interrupção de scanline funcionam num jogo comercial de
verdade (o filtro de scanlines está ligado na captura).</sub>

<br><br>

<img src="screenshots/nes-biblioteca.png" width="480" alt="Biblioteca multi-sistema">

<sub>A biblioteca multi-sistema: abas por console, busca e cards por jogo.</sub>

<br><br>

<img src="screenshots/snes-smw.png" width="560" alt="Super Mario World rodando no core SNES">

<sub><b>Super Mario World</b> (SNES) rodando no emulador — a tela de título completa, bootada
a partir do cartucho comercial: CPU 65C816, SPC700 fazendo o upload do driver de som, PPU e a
cadeia toda. O bug que a segurava era sutil (a interrupção nativa empurrava o status sem o flag X);
como interrupção não é um opcode, nenhum teste de CPU pegava — só o rastreamento do crash.</sub>

<br><br>

<img src="screenshots/snes-8bpp.png" width="270" alt="SNES background 8bpp"> <img src="screenshots/snes-mode7.png" width="270" alt="SNES Mode 7 (afim)"> <img src="screenshots/snes-colormath.png" width="270" alt="SNES color math (blend)">

<sub>A PPU do SNES contra as ROMs de teste do PeterLemon: BG <b>8bpp</b> (modo 3, 256 cores);
<b>Mode 7</b> (transformação afim, como F-Zero/Mario Kart); e <b>color math</b> (blend → gradiente de 3840 cores).</sub>

</div>

## ✨ Destaques

- 🧠 **CPU SM83 M-cycle-accurate** — o sistema avança a cada acesso de memória, como o hardware.
- 🖼️ **PPU pixel-FIFO** — fundo, janela e sprites; **Game Boy Color** em cor real (RGB555, VRAM banking, HDMA, double-speed).
- 💾 **MBC1 / MBC2 / MBC3 (+RTC) / MBC5** + save de bateria + **save states** (4 slots).
- 🔊 **APU** de 4 canais (2 ondas quadradas, wave, ruído) com **canais mutáveis**.
- 🎮 **NES**: CPU 6502 validada instrução a instrução pelo `nestest.log`, PPU por scanline
  (scroll, sprites, sprite-0 hit), APU de 5 canais (incl. **DMC**), mappers NROM/MMC1/UNROM/CNROM/**MMC3**
  (com **IRQ de scanline** — o split de tela de SMB3, Mega Man e cia.).
- 🟣 **SNES**: CPUs **65C816** e **SPC700** validadas contra os ProcessorTests, PPU
  (**modos 0–4 + Mode 7, color math, janelas, mosaico, prioridade**), DMA/HDMA, **APU com SPC700 real** (IPL + handshake) e **DSP** (áudio: BRR, ADSR, mixagem). **Boota o Super Mario World** (tela de título) — e outros jogos que não dependam de timing ciclo-a-ciclo exato.
- 🕹️ **App desktop multi-sistema**: seletor de console, biblioteca de ROMs, velocidade 0.25×–8× + turbo, tela cheia, filtros, paletas, cheats e gamepad.
- 🎨 **Cores autênticas por padrão** — filtros e correção de cor existem, mas nascem desligados.
- ✅ **641 testes automatizados** — Blargg, dmg/cgb-acid2, mooneye, nestest e ProcessorTests (65C816 + SPC700).

## 🎯 Precisão

A precisão não é opinião — é medida por ROMs de teste da comunidade, executadas pela suíte (`./gradlew test`):

| Suíte de teste | O que valida | Status |
|---|---|---|
| **Blargg `cpu_instrs`** | todos os ~500 opcodes da CPU | ✅ 10/10 |
| **Blargg `instr_timing`** | timing (ciclos) das instruções | ✅ |
| **Blargg `mem_timing`** | timing dos acessos à memória (exige M-cycle) | ✅ |
| **Blargg `02-interrupts`** | despacho de interrupções | ✅ |
| **dmg-acid2** | PPU do Game Boy (fundo/janela/sprites/prioridade) | ✅ pixel-perfect |
| **cgb-acid2** | PPU do Game Boy **Color** (paletas/atributos) | ✅ pixel-perfect |
| **mooneye** | banking (MBC1/5), timer, DAA, e mais | ✅ 24 testes |
| **nestest** (NES) | CPU 6502: 8991 instruções comparadas com o log de referência (PC, registradores, flags, ciclos) | ✅ instrução a instrução |
| **ProcessorTests** (SNES CPU) | 65C816: 254 opcodes em modo emulação, ~2,5 mi de vetores estado-a-estado | ✅ |
| **ProcessorTests** (SNES APU) | SPC700: 256 opcodes, estado a estado (A/X/Y/SP/PC/PSW + RAM) | ✅ |
| **Save states** | determinismo (snapshot → replay idêntico), GB, NES e SNES | ✅ |

<div align="center">
<sub>Quer entender <i>como</i> cada elo dessa cadeia foi conquistado?</sub><br><br>
<a href="CONSTRUCAO.md#11-a-cadeia-de-validação"><img src="https://img.shields.io/badge/📖_A_CADEIA_DE_VALIDACAO-em_CONSTRUCAO.md-1f6feb?style=flat-square" alt="Cadeia de validação"></a>
</div>

## 🏗️ Arquitetura

O **núcleo (`:core`)** é Kotlin/JVM puro — não conhece front-end — e é testável sem dispositivo.

```
GameBoy (scheduler: CPU → PPU/APU/timer a cada M-cycle)
├── Cpu (SM83)      · Registers · Interrupts
├── Ppu (pixel-FIFO, DMG + CGB)
├── Apu (4 canais)  · Timer · Joypad
├── Memory (mapa de endereços, HDMA, WRAM banking)
└── Cartridge → Mbc (RomOnly · MBC1 · MBC2 · MBC3+RTC · MBC5) + Cheats

:api      interface EmulatorCore — o contrato que qualquer console implementa
:nes      NES — CPU 6502 (nestest), PPU scanline, APU, mappers 0–4 (MMC3+IRQ)
:snes     SNES — CPUs 65C816 + SPC700 (ProcessorTests), PPU completa (0-4/Mode7/blend/janela), DMA/HDMA, APU+DSP; roda o SMW
:cli      runner (serial, trace, screenshot, save, paleta)
:desktop  app multi-sistema (seletor de console, biblioteca, áudio, gamepad, save states…)
homebrew/ CINZA — ROM autoral + artigo técnico
```

## 🚀 Rodando

Requer **JDK 21** (o wrapper do Gradle já vem incluído).

```bash
# rodar todos os testes (CPU + PPU + Blargg + acid2 + mooneye)
./gradlew test

# abrir o app desktop (com ou sem uma ROM)
./gradlew :desktop:run
./gradlew :desktop:run --args="homebrew/cinza/cinza.gb"

# instalar um ATALHO CLICÁVEL no menu de aplicativos (Linux), com ícone de Game Boy
./install-desktop.sh
```

Depois do instalador, procure **"GB Emulator"** no menu e clique — abre direto na
biblioteca de ROMs. A **CINZA** (`homebrew/cinza/cinza.gb`) já vem no repositório: é uma
ROM autoral, livre, pronta para jogar.

## 🎛️ Recursos do app desktop

- **Biblioteca multi-sistema**: abas por console, busca, cards (título + badge DMG/🌈GBC) e ROMs recentes.
- **Emulação**: pausar, velocidade 0.25×–8×, turbo (<kbd>TAB</kbd>), **save states** em 4 slots (<kbd>F1</kbd>–<kbd>F4</kbd> / <kbd>F5</kbd>–<kbd>F8</kbd>).
- **Controles** (menu *Emulação → Configurar controles*): abas **Teclado** e **Controle**, ambos remapeáveis no modo "pressione para aprender" e salvos automaticamente. No Linux o gamepad é lido direto de `/dev/input/js*` (sem bibliotecas nativas), com **hotplug** e defaults prontos para controle padrão Xbox.
- **Vídeo**: escala 2×–6×, tela cheia (<kbd>F11</kbd>), overlay de FPS, 8 paletas para jogos DMG.
  Filtros (scanlines / LCD grid / ghosting) e correção de cor CGB existem como opção — **desligados por padrão**: a imagem nasce fiel ao que o jogo define.
- **Áudio**: mudo, volume, liga/desliga cada canal.
- **Extras**: **cheats** (Game Genie / GameShark), autosave/continuar, arraste-a-ROM, screenshot (<kbd>F12</kbd>).

**Controles padrão:** setas = D-pad · <kbd>Z</kbd>/<kbd>X</kbd> = A/B · <kbd>Enter</kbd> = Start · <kbd>Shift</kbd> = Select · <kbd>Espaço</kbd> = pausar · <kbd>TAB</kbd> = turbo. Teclado e gamepad são remapeáveis em *Emulação → Configurar controles*.

## 🗺️ Roadmap

- [x] CPU SM83 M-cycle-accurate · Timer · Interrupções · Joypad
- [x] PPU pixel-FIFO (DMG) · **Game Boy Color** completo
- [x] MBC1/2/3(+RTC)/5 · save de bateria · save states
- [x] APU (som) · app desktop · atalho clicável
- [x] **CINZA** — ROM homebrew autoral rodando no emulador
- [x] **Arquitetura multi-sistema** — interface `EmulatorCore` + seletor de console na biblioteca
- [x] **NES** — CPU 6502 (nestest instrução a instrução), PPU, APU, mappers 0–3, save states
- [x] **NES fase 2** — MMC3 (banking + IRQ de scanline, destrava SMB3/Mega Man 3-6/Kirby…) e canal DMC (samples)
- [ ] **NES — precisão de barramento** — IRQ do MMC3 clocado por A12 real e PPU dot-accurate (o `mmc3_test` de conformidade estrita ainda falha; a aproximação por scanline cobre os jogos, não o teste exato)
- [ ] **SNES** — em construção pela escada de sempre:
  - [x] **CPU 65C816** — validada contra os ProcessorTests (254 opcodes em modo emulação, ~2,5 mi de vetores estado-a-estado)
  - [x] **Sistema bootável** — mapa de memória (LoROM/HiROM), DMA/HDMA, PPU (backgrounds modo 0-1, sprites), interrupções, controle; **roda ROMs de teste e renderiza backgrounds**
  - [x] **CPU SPC700** — o processador de som, validada contra os ProcessorTests (256 opcodes) + IPL boot ROM e handshake real das portas (jogos comerciais fazem o upload do driver de som e passam do boot do APU)
  - [x] **PPU completa** — modos 0–4 + Mode 7 (afim), 2/4/8bpp, color math, janelas, mosaico e composição por **prioridade** de BG/sprites — validada contra as ROMs de teste do PeterLemon
  - [x] **DSP** — síntese de áudio: 8 vozes, decode BRR, envelope ADSR/GAIN, mixagem estéreo (32 kHz → 48 kHz)
  - [x] **Roda o Super Mario World** — do cartucho comercial: title, overworld e as fases (fix da interrupção nativa preservando o flag X + wrap de tilemap de 64 tiles que cortava o cenário)
  - [x] **IRQ de contador H/V** — `$4200`/HTIME/VTIME/`$4211` + bit HBlank do `$4212`; destrava os splits de raster
  - [x] **Roda o Zelda: A Link to the Past** — passa do título e roda a intro (prólogo) e os menus (registro de nome, seleção de arquivo), do cartucho comercial
  - [ ] **Timing ciclo-a-ciclo** — para os jogos mais sensíveis a timing e para efeitos de raster mais precisos
- [ ] **N64** — pesquisa de longo prazo (MIPS + RSP; sem promessa de data)
- [ ] Cheat scanner · suporte a boot ROM · bits-exatos do MBC1

## ⚖️ Legal

O emulador é 100% original, e a única ROM distribuída aqui é **autoral**
([CINZA](homebrew/cinza/README.md)) — além das ROMs de teste livres da comunidade
(Blargg, dmg-acid2, cgb-acid2, mooneye), usadas na validação. ROMs comerciais não
acompanham o projeto e o `.gitignore` impede que entrem.

---

<div align="center">

### 📚 Continue lendo

<a href="CONSTRUCAO.md"><img src="https://img.shields.io/badge/📖_A_MAQUINA-CONSTRUCAO.md-1f6feb?style=for-the-badge" alt="CONSTRUCAO.md"></a>
<a href="homebrew/cinza/README.md"><img src="https://img.shields.io/badge/🕹️_O_JOGO-homebrew%2Fcinza-d29922?style=for-the-badge" alt="CINZA"></a>
<a href="CONTRIBUTING.md"><img src="https://img.shields.io/badge/🤝_CONTRIBUIR-CONTRIBUTING.md-2ea44f?style=for-the-badge" alt="CONTRIBUTING.md"></a>

<sub>Licenciado sob <b>MIT</b> — veja <a href="LICENSE">LICENSE</a>.</sub>

</div>
