# Native draw-state regression probes

Review evidence for [PR 2987](https://github.com/jMonkeyEngine/jmonkeyengine/pull/2987),
[PR 2988](https://github.com/jMonkeyEngine/jmonkeyengine/pull/2988), and
[PR 2989](https://github.com/jMonkeyEngine/jmonkeyengine/pull/2989).
This directory contains readable test-only Java, an offline Python runner, pinned
source hashes, and native state/pixel logs. It changes no production files.

## Pinned inputs

- Baseline: `e8cf975be583a668d4121cdce334bfb0c5e75af0`
- PR 2987, stencil cache: `fa5dad4feb492fe86d6d2d5babb604dfe9fe1115`
- PR 2988, numeric vertex divisors: `aa8d5a6ace78d4872b7f8a06fe6f931b159b32f9`
- PR 2989, lazy divisor invalidation: `d6472482fe613ef6d9f0f740593a8d1760f900f5`

The runner reads `GLRenderer.java` and `RenderContext.java` from these exact Git
objects in an existing local clone, checks their SHA-256 hashes in `pins.json`,
and freshly compiles them. Their classes take precedence over the supplied jME
classes. It does not fetch, install, check out a revision, apply patches, or edit
the source checkout. The extracted sources retain their original license notices.
The PR 2988 pin includes a test-only assertion refinement; its two production
files are identical to `e64378e8f53ebcf762b814da9f75845add248916`.

## Run

Already-installed requirements:

- Python 3.9+, Git, and JDK 21+
- JNA and compatible compiled jME core/desktop classes, plus core resources
- Mesa EGL with surfaceless display support, GLES 3, and desktop OpenGL core

Set `JME_REPOSITORY`, `JAVA_HOME`, `JME_VALIDATION_CLASSPATH`, and `OUTPUT` to local
paths. The clone must already contain all four commits. On Linux the classpath
is colon-separated, in this order: JNA jar, jME core classes, core resources,
jME desktop classes. `OUTPUT` must be a new or empty directory outside both the
source checkout and this validation package.

```sh
python3 run.py \
  --repository "$JME_REPOSITORY" \
  --java-home "$JAVA_HOME" \
  --dependency-classpath "$JME_VALIDATION_CLASSPATH" \
  --output "$OUTPUT"
```

The output has `build/` for temporary extracted sources/classes and `evidence/`
for reviewable logs, relative-path manifests, dependency content hashes, and
`SHA256SUMS`. Only `evidence/` is included here. Java assertions are enabled.
The runner requests software rendering and disables Mesa's shader disk cache.
It exits nonzero for a compile failure, unavailable pin, unexpected native state
or pixel, GL error, timeout, or missing success summary. It will not overwrite
a nonempty output directory.

## What the recorded run establishes

`evidence/manifest.json` ties every execution to its production commit, source
hashes, harness hashes, and dependency content hashes. The recorded 2026-10-02
run used Temurin 21.0.12.1, JNA 5.6.0, and Mesa llvmpipe (LLVM 19.1.7), Mesa
25.0.7-2+deb13u1. GLES reports 3.2 and desktop GL reports 4.5 Core Profile.
Compatible dependency classes/resources came from a build at
`e7c87ee640dd5d266d6b3f0278cf206bc3afb6ef`; its only main core Java difference
from the baseline is `GLRenderer.java`, which this runner replaces for each pin.

Seven pixel runs cover 42 case evaluations, 78 native draws, and 240 one-pixel
RGBA readbacks. The additional desktop core-profile control performs no draw.
All eight executions establish their asserted expectations with zero GL errors.

Important: baseline `PASS` means the expected bug was reproduced and controls
passed. It does **not** mean the baseline renderer is correct. Compare requested
state with native state and expected/actual pixels in each log.

### PR 2987: stencil enable state

A 32x8 linear RGBA8 framebuffer with D24S8 is cleared blue with stencil value 2.
The fixture checks framebuffer completeness and eight stencil bits. A red
triangle is drawn after real `GLRenderer.applyRenderState` calls.

- `stencil-baseline`: disabling stencil with unchanged `Never`/`Keep` parameters
  leaves native stencil enabled and the pixel blue (expected faulty result)
- `stencil-pr2987`: native stencil becomes disabled and the pixel becomes red
- Both versions pass five controls: disable while changing the function;
  front-only and back-only reference changes; front-only and back-only mask changes
- Each version: 6 cases, 12 native draws, 12 pixel readbacks, zero GL errors

### PR 2988: numeric instance spans

Real `GLRenderer.renderMesh` draws four instances in separate 8x8 columns.
Attribute slot 4 supplies red/green/blue/yellow. Span 1 means R G B Y;
span 2 means R R G G. Native divisor queries and actual pixels are both asserted.

- `divisor-baseline`: 1-to-2 and 2-to-1 retain the old divisor and old pixel pattern
- `divisor-pr2988`: both transitions use the newly requested divisor and pixels
- Both directions cover distinct buffers and same-buffer span mutations
- 0-to-2 and 2-to-0 controls use constant red data; native queries distinguish
  divisors while pixel reads check valid drawing
- Each version: 6 cases, 12 native draws, 48 pixel readbacks, zero GL errors

### PR 2989: invalidation followed by divisor zero

A divisor of 2 is established by renderer use or an external native call,
then `invalidateState()` is followed by a span-zero draw. The external cases
cover previously used and unused attribute slots. Provoking-vertex data makes
correct divisor-zero output R R R R and stale divisor-two output R R G G.

- `invalidation-baseline` and `invalidation-pr2988`: all three regression cases
  retain native divisor 2 and R R G G (expected faulty results)
- `invalidation-pr2989`: all three restore native divisor 0 and R R R R
- Controls cover no invalidation, and requested spans 1 and 2 after invalidation
- Each version: 6 cases, 10 native draws, 40 pixel readbacks, zero GL errors
- `core-vao-pr2989`: native desktop core context, VAO 0, no eager divisor calls
  during invalidation, and zero errors. The direct native-divisor control is
  reported separately; this Mesa driver also accepts that call without error

## Scope and limits

All state queries, draws, and readbacks reach a real native driver through a
small test-only JNA bridge. Capabilities come from the actual current context;
no mocked GL results or added extension capabilities are used. Probe-owned
shaders and geometry isolate renderer state decisions. Pixel probes read one
sample per solid-color region, not every rasterized pixel. Cases reset native
state and use fresh renderer objects.

These are software-driver, focused renderer tests, not production LWJGL/Android
backend integration, a complete game scene, hardware-GPU coverage, cross-driver
portability proof, or performance measurements. They do not replace the unit
and aggregate tests in the PRs. Other render-state invalidation is outside this
three-PR package's claim.

## Files and integrity

- `src/`: readable Java harnesses and minimal native bridge
- `run.py`, `pins.json`: portable runner and exact production-source SHA-256 pins
- `evidence/`: sanitized compile, driver, native-state, RGBA and summary logs
- `evidence/manifest.json`: run-to-commit mapping and hashed inputs
- `SHA256SUMS`: hashes for all published files except this hash list
- `LICENSE`: jMonkeyEngine BSD 3-Clause notice; source notices are retained

Verify the published package with `sha256sum --check SHA256SUMS` from this folder.
The evidence manifest intentionally omits local command paths, user information,
credentials, private artifact identifiers, and unrelated source snapshots.
