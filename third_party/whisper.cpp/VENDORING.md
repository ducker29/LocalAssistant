# whisper.cpp (vendored)

Pinned, unmodified upstream source used for offline speech recognition.

## Pin

| Item | Value |
|---|---|
| Upstream repository | `https://github.com/ggml-org/whisper.cpp` |
| Release tag | `v1.9.5` |
| Tag object | `d2a764c648779feb36d44363f212a29b00aad1af` (annotated tag; **not** a commit) |
| Source commit | `d1be6fde11ac6e0407606b4e42fe72d34add8037` (2026-10-06) |
| Archive URL | `https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/d1be6fde11ac6e0407606b4e42fe72d34add8037` |
| Archive size | 9,631,456 bytes |
| Archive SHA-256 | `645445DA884DF6DBD52B30F82D7BE7C01D1767C938714E477E786B88D529E9F4` |
| Bundled ggml | 0.26.0 (source is inside this tree under `ggml/`) |
| License | MIT (`LICENSE`, Copyright (c) 2023-2026 The ggml authors) |

`WHISPER_BUILD_IS_DEV` is `ON` upstream, so the compiled version string is `1.9.5-dev`;
the release *tag* is v1.9.5 and the source *commit* is `d1be6fde...`. Both are recorded
because the tag object hash is not the commit hash.

The archive is kept in `third_party/_dl/` (git-ignored) so the pin can be re-verified
without network access. Only the extracted tree is committed.

## Why a separate Gradle module

`app`'s CMake project already builds llama.cpp and its own copy of ggml (0.27.0). whisper.cpp
1.9.5 vendors ggml 0.26.0 and creates targets named `ggml`, `ggml-base`, `ggml-cpu` and
`whisper`, which would collide with llama.cpp's targets in the same CMake project. Therefore
whisper is built by the `:whisper` Gradle module with its own CMake project and linked into a
separate `libwhisper_jni.so`. This also keeps the working llama.cpp integration untouched and
lets the linker hide all vendored symbols behind a version script.

## Build configuration

Defined in `whisper/src/main/cpp/CMakeLists.txt`:

- `BUILD_SHARED_LIBS=OFF` (static whisper/ggml, linked into `libwhisper_jni.so`);
- tests, examples and server disabled; standalone mode disabled;
- CPU backend only: Metal, Vulkan, OpenCL, CUDA, SYCL, Hexagon, RPC, Kompute, WebGPU,
  KleidiAI and HBM disabled; `GGML_NATIVE=OFF`; `GGML_OPENMP=OFF`;
- `GGML_ALL_WARNINGS=OFF` so upstream warnings do not become project warnings;
- `libwhisper_jni.so` links with `-Wl,--exclude-libs,ALL` and
  `-Wl,--version-script=exports.map`, so only `JNI_OnLoad` and the
  `Java_com_example_localassistant_asr_WhisperNative_*` entry points are exported.

## Update procedure

1. Pick a release tag, resolve the annotated tag to its commit (`git rev-parse <tag>^{}`)
   and download the commit tarball, not the tag page.
2. Record archive size and SHA-256 above, plus the tag object and commit.
3. Replace this directory with the extracted archive and re-apply no source patches.
4. Check `src/whisper.h` for API/ABI changes used by
   `whisper/src/main/cpp/whisper_jni.cpp` (context/full params, abort callback, segment API).
5. Build both ABIs and re-run the symbol-isolation check (see below).
6. Update `docs/DECISIONS.md` with the new pin and reason.

## Symbol-isolation check

After building, run for both `arm64-v8a` and `x86_64`:

```text
llvm-nm -D --defined-only <path>/libwhisper_jni.so | Select-String "ggml_|whisper_"
llvm-readelf -d <path>/libwhisper_jni.so
```

Expected: only the two JNI entry points and `JNI_OnLoad` are defined as dynamic symbols;
no `ggml_*` or `whisper_*` symbols appear; no `DT_NEEDED` entry points at
`liblocalassistant.so` (or any other app native library).

## Model artifact

Pinned separately in Kotlin (`WhisperModelSpec`): multilingual `ggml-base.bin` from
`ggerganov/whisper.cpp` at repository commit `5359861c739e955e79d9a303bcbc70fb988958b1`,
147,951,465 bytes, SHA-256
`60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe`.
The weights are not part of this source tree and are never committed or bundled in the APK.
