# Ascon image encryption (Chisel) - sprint 1 build

Reproduction of the plan in "Ascon Image Encryption: Sprint 1": a pixel-stream pipeline in Chisel
with a swappable core (`Passthrough` or `Ascon`), verified bottom-up against a Python golden model of
Ascon-AEAD128 (NIST SP 800-232).

```
PNG -> bytes -> Packer (16 B -> 128-bit) -> core -> Unpacker -> bytes -> PNG
                                             ^ Passthrough | Ascon(roundsPerCycle = 1|2|4)
```

## Setup

The Python code is the software reference. The Scala/Chisel code describes the hardware;
`sbt test` checks that hardware in simulation against the reference vectors.
You do not need an FPGA or a separate Scala installation to run the tests.

### Prerequisites

- Git to clone the repository.
- **JDK 17 recommended** (the version used by CI). JDK 11 also works with this project's
  sbt 1.10.2 / Scala 2.13.14 build. Use a JDK, not just a JRE.
- **sbt runner**. The repository pins sbt 1.10.2 in `project/build.properties`;
  the runner downloads that version, Scala, Chisel and the test libraries on first use.
- **Python 3** with `pip` and `venv`; Python 3.11 is used by CI.
- **Pillow**, installed in the virtual environment below, for the image tools and tests.
- **firtool only for SystemVerilog emission**, not for the quickstart tests or Python demo.
  Chisel 6 normally downloads its matching firtool automatically on supported systems.
  See [Run](#run) if that download is unavailable on your machine.

An internet connection is needed for installation and the first sbt run.
The commands below use Bash or zsh. Choose one installation path for your operating system.

### Ubuntu / Debian (also Ubuntu in WSL)

Install Java, Python and the command-line tools:

```bash
sudo apt-get update
sudo apt-get install -y git curl gnupg openjdk-17-jdk python3 python3-pip python3-venv
```

Add the official sbt package repository, then install the runner:

```bash
sudo install -d -m 0755 /etc/apt/keyrings
curl -fsSL 'https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823' | sudo gpg --dearmor -o /etc/apt/keyrings/scalasbt.gpg
sudo chmod 644 /etc/apt/keyrings/scalasbt.gpg
echo 'deb [signed-by=/etc/apt/keyrings/scalasbt.gpg] https://repo.scala-sbt.org/scalasbt/debian all main' | sudo tee /etc/apt/sources.list.d/sbt.list
sudo apt-get update
sudo apt-get install -y sbt
```

If you already configured the sbt repository, use its existing key/repository setup rather
than adding it again. Official alternatives, including RPM packages and SDKMAN, are listed
on the [sbt download page](https://www.scala-sbt.org/download/).

### macOS

With [Homebrew](https://brew.sh/) installed:

```bash
brew install git python sbt
brew install --cask temurin@17
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export PATH="$JAVA_HOME/bin:$PATH"
```

The two `export` commands select JDK 17 for this terminal, even if Homebrew installed a newer
Java as an sbt dependency. Add them to your shell startup file (usually `~/.zshrc`) if you
want that selection in future terminals.

If you prefer Coursier for the sbt runner, install Java, Git and Python as above, then use
`brew install coursier && coursier setup` instead of `brew install sbt`.
Follow its prompts and reopen the terminal when it asks. The
[Scala installation guide](https://docs.scala-lang.org/getting-started/install-scala.html)
has Coursier commands for Linux and macOS without Homebrew too.

### Windows

For the same shell commands as Linux, use Ubuntu in WSL. On Windows 11 or a supported
Windows 10 installation, open **PowerShell as Administrator** and run:

```powershell
wsl --install
```

Restart if prompted, open Ubuntu, finish creating its Linux user, then follow the
Ubuntu / Debian section above inside Ubuntu. Clone into your Linux home directory.
See [Microsoft's WSL installation guide](https://learn.microsoft.com/en-us/windows/wsl/install)
if WSL is already installed or your Windows version needs different steps.

### Quickstart: clone, test, run the demo

Run these commands in a terminal after installing the tools above:

```bash
git clone https://github.com/AbnormalityAI/ascon-image-stream.git
cd ascon-image-stream

# Check that Java 17 (or 11) and Python 3 are available.
java -version
python3 --version

# Keep Python packages separate from your system Python.
python3 -m venv .venv
source .venv/bin/activate
python3 -m pip install pillow

# Software reference and image-tool tests.
python3 -m unittest discover -s python -p 'test_*.py' -v

# Hardware simulation tests. Run from the repository root.
sbt test

# Generate a 128x128 grayscale image and encrypt/decrypt it in software.
python3 python/imgtool.py gen test.png
python3 python/demo.py test.png out/
```

A passing run reports **8 Python tests (`OK`)** and **21 Scala tests (all passed)**.
The first sbt run can take longer while it downloads dependencies; later test runs are
about two minutes on the CI machine. The image tests simulate roughly 16 KB twice.

Open `test.png` (the original), `out/noise.png` (ciphertext shown as an image) and
`out/decrypted.png` (the recovered original). The demo also writes `out/original.bin`
and prints the authentication tag. This is a **software demo**, not an FPGA run.
Its fixed key and nonce are test data only, not a safe pattern for encrypting real images.

In each new terminal, `cd` back into the repository and run `source .venv/bin/activate`
before using the Python commands. Run `deactivate` when you are finished.
If Python reports `No module named PIL`, activate that environment and rerun
`python3 -m pip install pillow`. If Java or sbt is not found, reopen the terminal after
installation and check your `PATH` / `JAVA_HOME`.

## Layout
| Path | Content |
| --- | --- |
| `python/ascon.py` | Golden model: AEAD128 encrypt/decrypt, `trace=[]` records the state after every round |
| `python/test_ascon.py` | All 1089 official KATs (`python/kat/`: the official `LWC_AEAD_KAT_128_128.txt` from github.com/ascon/ascon-c split into 5 parts at record boundaries, joined with a newline plus a final newline; the test checks the SHA-256 of the result against the official file) |
| `python/imgtool.py`, `demo.py` | 128x128 grayscale PNG <-> 16384-byte binary; software-reference demo (original, noise, decrypted) |
| `python/export_vectors.py` | Writes `vectors/` (format below). Deterministic |
| `src/main/scala/ascon/` | `AsconRound`, `AsconPermutation`, `AsconCore` (FSM), `Stream.scala` (interfaces, Packer, Unpacker, PassthroughCore, `StreamTop`), `Main` (Verilog) |
| `src/test/scala/ascon/` | chiseltest suites (see below) |
| `.github/workflows/ci.yml` | `sbt test` + Python tests on PRs |

## Run

The [quickstart](#quickstart-clone-test-run-the-demo) runs both test suites and the software demo.
For optional SystemVerilog emission, run from the repository root:

```bash
sbt "runMain ascon.Main ascon 4"
# Or emit the passthrough pipeline:
sbt "runMain ascon.Main passthrough"
```

Output goes to `generated/`. The Ascon argument `4` is rounds per cycle; `1` and `2`
are also supported. Emission needs firtool. Chisel 6.5.0 normally downloads the matching
binary; if this fails, follow the [Chisel installation guide](https://www.chisel-lang.org/docs/installation)
and set `CHISEL_FIRTOOL_PATH` to the directory containing a compatible `firtool` binary.
Verilog emission and synthesis are separate from the tests and have not been verified
for this build; green tests establish simulation results, not FPGA or synthesis readiness.

## Interfaces
* Stream: `Decoupled(UInt(8.W))` in and out (`ByteStreamIO`), the same for both cores.
* Block (core side): `Decoupled(Block)`, `Block = {data: UInt(128), last: Bool}`; byte 0 is bits 7:0 (little-endian, matching how Ascon loads words; key/nonce ports use the same order: x0/K0 = low 64 bits).
* `StreamTop(cipher, messageBytes = 16384)` has extra ports `key`, `nonce`, `decrypt`, `tagValid`, `tagOk` (ignored by Passthrough) and debug `dbg*` ports.

## Ascon core behaviour
* Init p12, (empty AD), data blocks with p8, finalization p12. One extra padding-only block is absorbed after the last full block (no ciphertext produced, no permutation after it), as in the reference. Output = ciphertext blocks then a 16-byte tag, so 16384 B in -> 16400 B out.
* Decrypt: input is ciphertext then tag; `last` marks the tag block; plaintext is streamed out as computed, **before** the tag is verified. Consumers must discard it if `tagOk` is false after `tagValid` pulses.
* Permutation: p12 starts at round index 0, p8 at 4; `roundsPerCycle` in {1,2,4}; latency = 1 load cycle + rounds/roundsPerCycle.
* A message starts when the core is idle and the first block arrives; key/nonce/decrypt are sampled then. Never reuse a nonce with a key.

### Limitations (not implemented)
Associated data (hardware assumes empty AD), partial final blocks (messages must be a whole number of 16-byte blocks, at least one), zero-length messages, constant-time/side-channel hardening, throughput optimisation (one block in flight, no pipelining between packer/core/unpacker).

## Vector formats (`vectors/`)
Hex words are 64-bit (16 hex chars) in order x0..x4, separated by spaces; `#` lines are comments.
* `round.vec`: `R <roundIndex 0..11> <in x0..x4> <out x0..x4>`
* `perm.vec`: `P <12|8> <in x0..x4> <out x0..x4>`
* `perm_trace.vec`: `S <rounds> <start state>` followed by `T <rounds> <roundIndex> <state after that round>`
* `aead.vec`: `A <key> <nonce> <ad|-> <pt|-> <ct||tag>`, all hex byte strings
* `aead_16384.vec`: `A16384 <key> <nonce> <tag>` for the pattern plaintext byte i = (7*i) mod 256
* `trace_enc_32B.txt`: per-step states of one 32-byte encryption (phase, round index or `-`, state)
* `image_enc.hex`: golden ciphertext||tag (16400 B, hex) of the generated test image (no binaries are committed; the PNG is generated at test time by `imgtool.py gen` / `TestUtil.genImage`, which must stay identical) with key 00..0f, nonce 10..1f

## Tests
| Suite | What |
| --- | --- |
| `test_ascon.py` | 1089 official KATs encrypt+decrypt, tamper, trace round counts, aligned padding |
| `test_imgtool.py` | PNG<->bin lossless, wrong size/mode rejected, image encrypt/decrypt in software |
| `RoundSpec` | combinational round vs golden, all 12 constants |
| `PermutationSpec` | p12/p8 vs golden for 1/2/4 rounds per cycle; per-round state check |
| `StreamSpec` | passthrough with backpressure; packer byte order and `last`; packer/unpacker round trip with stalls |
| `AsconSpec` | encrypt/decrypt block-aligned golden vectors for rpc 1/2/4, tampered tag/ciphertext rejected, per-round states of a whole encryption, 16384-byte padding case |
| `ImageSpec` | image through passthrough (identical output, writes `target/e2e/*.png`); Ascon encrypt == golden, decrypt == original, tag ok |

## Workflow notes (from the plan)
Every PR needs one review; CI must be green. Branch protection and the PR template are repository settings, not code.
