# Ascon image encryption (Chisel) - sprint 1 build

Reproduction of the plan in "Ascon Image Encryption: Sprint 1": a pixel-stream pipeline in Chisel
with a swappable core (`Passthrough` or `Ascon`), verified bottom-up against a Python golden model of
Ascon-AEAD128 (NIST SP 800-232).

```
PNG -> bytes -> Packer (16 B -> 128-bit) -> core -> Unpacker -> bytes -> PNG
                                             ^ Passthrough | Ascon(roundsPerCycle = 1|2|4)
```

## Layout
| Path | Content |
| --- | --- |
| `python/ascon.py` | Golden model: AEAD128 encrypt/decrypt, `trace=[]` records the state after every round |
| `python/test_ascon.py` | All 1089 official KATs (`LWC_AEAD_KAT_128_128.txt`, from github.com/ascon/ascon-c) |
| `python/imgtool.py`, `demo.py` | 128x128 grayscale PNG <-> 16384-byte binary; software-reference demo (original, noise, decrypted) |
| `python/export_vectors.py` | Writes `vectors/` (format below). Deterministic |
| `src/main/scala/ascon/` | `AsconRound`, `AsconPermutation`, `AsconCore` (FSM), `Stream.scala` (interfaces, Packer, Unpacker, PassthroughCore, `StreamTop`), `Main` (Verilog) |
| `src/test/scala/ascon/` | chiseltest suites (see below) |
| `.github/workflows/ci.yml` | `sbt test` + Python tests on PRs |

## Run
```
python3 -m unittest discover -s python -p 'test_*.py'   # needs pillow
sbt test                                                # ~2 min (image test simulates ~16k bytes x2)
python3 python/imgtool.py gen test.png && python3 python/demo.py test.png out/   # software demo
sbt "runMain ascon.Main ascon 4"                        # emit SystemVerilog to generated/ (needs firtool)
```

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
