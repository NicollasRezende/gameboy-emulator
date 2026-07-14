<div align="center">

# 🤝 Contribuindo

**Obrigado pelo interesse!** Este guia leva você do zero ao Pull Request.

<a href="#-o-fluxo-fork--pr-passo-a-passo"><img src="https://img.shields.io/badge/🍴_FORK_%2B_PR-passo_a_passo-2ea44f?style=for-the-badge" alt="Fork + PR"></a>
<a href="#-padrões-do-projeto"><img src="https://img.shields.io/badge/📏_PADROES-commits,_testes,_estilo-1f6feb?style=for-the-badge" alt="Padrões"></a>
<a href="#-ideias-de-contribuição"><img src="https://img.shields.io/badge/💡_IDEIAS-por_onde_começar-8957e5?style=for-the-badge" alt="Ideias"></a>

</div>

---

## ⚙️ Setup

| Requisito | Versão |
|---|---|
| **JDK** | 21 (o wrapper do Gradle já vem no repositório) |
| **Git** | qualquer versão recente |
| **gh CLI** *(opcional)* | facilita fork e PR — [cli.github.com](https://cli.github.com) |

```bash
# rodar a suíte inteira (CPU + PPU + Blargg + acid2 + mooneye)
./gradlew test

# abrir o app desktop com a ROM autoral do repositório
./gradlew :desktop:run --args="homebrew/cinza/cinza.gb"
```

## 🍴 O fluxo fork + PR, passo a passo

### 1 — Faça o fork

O fork é a **sua cópia** do repositório, onde você tem permissão de push.

**Pelo site:** clique em <kbd>Fork</kbd> no topo de
[NicollasRezende/gameboy-emulator](https://github.com/NicollasRezende/gameboy-emulator)
e confirme.

**Ou pela linha de comando** (gh CLI) — faz o fork **e** clona de uma vez:

```bash
gh repo fork NicollasRezende/gameboy-emulator --clone
cd gameboy-emulator
```

<details>
<summary>Fiz o fork pelo site — como clono e conecto ao original?</summary>

```bash
git clone git@github.com:SEU_USUARIO/gameboy-emulator.git
cd gameboy-emulator

# adiciona o repositório original como "upstream" (para se manter atualizado)
git remote add upstream https://github.com/NicollasRezende/gameboy-emulator.git
git remote -v   # origin = seu fork · upstream = o original
```
</details>

### 2 — Crie uma branch

Nunca trabalhe direto na `main`. Uma branch por assunto:

```bash
git switch -c feat/minha-melhoria     # feature
git switch -c fix/bug-tal             # correção
```

### 3 — Faça a mudança (com teste!)

- Mudou comportamento do **`:core`**? Escreva o teste **antes** (TDD é o padrão da casa).
- A suíte inteira precisa continuar verde:

```bash
./gradlew test
```

### 4 — Commit no padrão

Usamos [Conventional Commits](https://www.conventionalcommits.org/) com escopo:

```bash
git add -p
git commit -m "feat(core): descricao curta do que mudou"
#              ^tipo ^escopo
# tipos: feat · fix · docs · test · chore · refactor
# escopos comuns: core · desktop · cli · api
```

### 5 — Push para o SEU fork

```bash
git push -u origin feat/minha-melhoria
```

### 6 — Abra o Pull Request

**Pela linha de comando:**

```bash
gh pr create --title "feat(core): minha melhoria" --body "O que muda e por quê. Como testei."
# ou, para preencher no navegador:
gh pr create --web
```

**Pelo site:** depois do push, o GitHub mostra um banner
<kbd>Compare & pull request</kbd> no seu fork — clique, descreva **o que muda, por quê e
como testou**, e envie.

### 7 — Revisão

- **Rode `./gradlew test` antes de abrir o PR** — a suíte inteira (Blargg, acid2,
  mooneye) precisa estar verde; PR com teste quebrado não é revisado. ✅
- Ajustes pedidos na revisão? É só commitar e dar push na mesma branch — o PR atualiza
  sozinho.

<details>
<summary>🔄 Mantendo seu fork atualizado com o original</summary>

```bash
git fetch upstream
git switch main
git merge upstream/main
git push origin main
```
</details>

## 📏 Padrões do projeto

- **TDD** — teste antes; as ROMs de teste (Blargg, acid2, mooneye) são o oráculo de
  integração. **Não quebre o que está verde.**
- **Commits** — Conventional Commits com escopo (`feat(core): …`).
- **Estilo** — `kotlin.code.style=official`; siga o código vizinho; sem comentário óbvio.
- **ROMs** — 🚫 **nunca** commite ROM comercial (o `.gitignore` bloqueia `*.gb`/`*.gbc`).
  Só ROMs de teste livres (`core/src/test/resources/roms/`) e a homebrew autoral
  (`homebrew/`).

## 🗂️ Onde mexer

| Quero mudar… | Módulo |
|---|---|
| Emulação Game Boy (CPU, PPU, APU, MBC…) | `:core` — Kotlin puro, testável na JVM |
| Emulação NES (6502, PPU, APU, mappers) | `:nes` — Kotlin puro, validado pelo nestest |
| App de jogar (janela, menus, input, áudio) | `:desktop` |
| Runner de terminal (trace, screenshot) | `:cli` |
| Contrato multi-sistema (`EmulatorCore`) | `:api` — implementar isto = console novo |

<sub>Para entender o desenho geral antes de mexer: **[CONSTRUCAO.md](CONSTRUCAO.md)**.</sub>

## 💡 Ideias de contribuição

- 🎯 Bits-exatos do MBC1 · write-window do TIMA (fecha os mooneye restantes)
- 🎮 **NES fase 2** — mapper MMC3 (+IRQ de scanline), canal DMC da APU, PPU dot-accurate
- 🎮 **Core SNES** — o próximo console da escada (65C816 + SPC700)
- ⚡ Otimização dos hot loops (FIFO/APU sem alocação)
- 🕹️ MBC1 multicart · HuC1 · boot ROM opcional
- 💾 Rewind · cheat scanner · TAS tools

---

<div align="center">

<a href="README.md"><img src="https://img.shields.io/badge/⬅_VOLTAR-README-555?style=for-the-badge" alt="Voltar"></a>
<a href="CONSTRUCAO.md"><img src="https://img.shields.io/badge/📖_A_MAQUINA-CONSTRUCAO.md-1f6feb?style=for-the-badge" alt="CONSTRUCAO.md"></a>

</div>
