<div align="center">

# 🎮 gameboy-emulator

**Primeiro construímos a máquina. Depois, o jogo que roda nela.**

Um emulador de Game Boy & Game Boy Color escrito do zero em Kotlin — preciso ao nível de
referência, validado por testes — e uma **ROM homebrew autoral** feita para rodar nele.
Os dois lados do cartucho, construídos aqui.

![License](https://img.shields.io/badge/license-MIT-green)
![Kotlin](https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white)
![JDK](https://img.shields.io/badge/JDK-21-orange?logo=openjdk&logoColor=white)
![Tests](https://img.shields.io/badge/tests-112%20passing-brightgreen)

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

## ✨ Destaques

- 🧠 **CPU SM83 M-cycle-accurate** — o sistema avança a cada acesso de memória, como o hardware.
- 🖼️ **PPU pixel-FIFO** — fundo, janela e sprites; **Game Boy Color** em cor real (RGB555, VRAM banking, HDMA, double-speed).
- 💾 **MBC1 / MBC2 / MBC3 (+RTC) / MBC5** + save de bateria + **save states** (4 slots).
- 🔊 **APU** de 4 canais (2 ondas quadradas, wave, ruído) com **canais mutáveis**.
- 🕹️ **App desktop completo**: biblioteca de ROMs, velocidade 0.25×–8× + turbo, tela cheia, filtros, paletas, cheats e gamepad.
- 🎨 **Cores autênticas por padrão** — filtros e correção de cor existem, mas nascem desligados.
- ✅ **112 testes automatizados** — Blargg, dmg-acid2, cgb-acid2 e 24 testes mooneye.

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
| **Save states** | determinismo (snapshot → replay idêntico) | ✅ |

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

:cli      runner (serial, trace, screenshot, save, paleta)
:desktop  app Swing jogável (biblioteca, áudio, gamepad, save states…)
homebrew/ CINZA — ROM autoral + artigo técnico
android/  app Jetpack Compose (requer Android SDK)
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

- **Biblioteca** com busca, cards (título + badge DMG/🌈GBC) e ROMs recentes.
- **Emulação**: pausar, velocidade 0.25×–8×, turbo (<kbd>TAB</kbd>), **save states** em 4 slots (<kbd>F1</kbd>–<kbd>F4</kbd> / <kbd>F5</kbd>–<kbd>F8</kbd>), remapear teclas.
- **Vídeo**: escala 2×–6×, tela cheia (<kbd>F11</kbd>), overlay de FPS, 8 paletas para jogos DMG.
  Filtros (scanlines / LCD grid / ghosting) e correção de cor CGB existem como opção — **desligados por padrão**: a imagem nasce fiel ao que o jogo define.
- **Áudio**: mudo, volume, liga/desliga cada canal.
- **Extras**: **cheats** (Game Genie / GameShark), autosave/continuar, arraste-a-ROM, screenshot (<kbd>F12</kbd>), **gamepad** (experimental).

**Controles:** setas = D-pad · <kbd>Z</kbd>/<kbd>X</kbd> = A/B · <kbd>Enter</kbd> = Start · <kbd>Shift</kbd> = Select (remapeáveis) · <kbd>Espaço</kbd> = pausar · <kbd>TAB</kbd> = turbo.

## 🗺️ Roadmap

- [x] CPU SM83 M-cycle-accurate · Timer · Interrupções · Joypad
- [x] PPU pixel-FIFO (DMG) · **Game Boy Color** completo
- [x] MBC1/2/3(+RTC)/5 · save de bateria · save states
- [x] APU (som) · app desktop · atalho clicável
- [x] **CINZA** — ROM homebrew autoral rodando no emulador
- [ ] Cheat scanner · suporte a boot ROM · bits-exatos do MBC1 · Android release

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
