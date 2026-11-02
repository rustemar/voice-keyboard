# Speech runtime build

The app uses Silero VAD with a minimal CPU build of ONNX Runtime 1.24.3.
The runtime contains the operators required by the pinned model, with C++
exceptions enabled. Model parameters remain float32; quantization and operator
type reduction are disabled. All four Android ABIs and 16 KB pages are supported.

`source-lock.json` pins every source archive, upstream patch, build tool and model.
The generated AAR keeps the Java API and metadata from the matching trusted
Maven Central artifact and replaces its native libraries with source-built ones.
The source trees are checked before compilation. Verified per-ABI cache receipts
bind the recipe, compiler/toolchain and native hashes; changed inputs rebuild.

Use Linux x86_64, JDK 17, Python 3.10 or newer and Android NDK 28.0.13004108.
`prepareSpeechRuntime` downloads and checks the sources. `buildSpeechRuntime`
then compiles offline, including the host protobuf compiler, and produces
`build/onnxruntime/onnxruntime-reduced.aar`:

```sh
sdkmanager 'ndk;28.0.13004108'
./gradlew prepareSpeechRuntime buildSpeechRuntime
./gradlew assembleRelease -PspeechRuntimeAar=build/onnxruntime/onnxruntime-reduced.aar
```

Prepared sources and native caches live in `external/onnxruntime`, outside
Gradle's clean directory. Downloaded archives and approved PyPI build tools live
outside the checkout, under `~/.cache/voice-keyboard/onnxruntime`. The initial
native compilation is substantial; subsequent builds reuse verified outputs.
`-PspeechRuntimeNdk=/path/to/ndk` can select the same pinned NDK elsewhere.
Ordinary development builds without `speechRuntimeAar` use the official full
Maven runtime with the same model, preserving the quick Java/Kotlin workflow.
Release CI explicitly builds and selects the reduced AAR.

## Model provenance

The original Silero model is retained at `tools/onnxruntime/models/silero_vad.onnx`
for source provenance, outside the packaged assets. The bundled
`silero_vad.ort` contains the same model serialized with official ONNX Runtime
1.24.3 BASIC optimization. The original source revision and both file hashes
are recorded in the lock and the bundled third-party notice.

`SerializeSileroOrt.java` documents the export. Use the official full
`com.microsoft.onnxruntime:onnxruntime:1.24.3` Java artifact to run it on Linux:

```sh
java -cp /path/to/onnxruntime-1.24.3.jar tools/onnxruntime/SerializeSileroOrt.java \
  tools/onnxruntime/models/silero_vad.onnx /tmp/silero_vad.ort
```

Normal app builds use the committed, hash-checked ORT asset and do not convert
models. The upstream exporter serializes kernel resolver entries from unordered
maps, so repeated exports can have different metadata layouts. The model graph
and weights reproduce identically; the committed asset fixes the exact bytes
used by the app and by reproducible builds.

Sources: [Silero VAD](https://github.com/snakers4/silero-vad),
[ONNX Runtime custom builds](https://onnxruntime.ai/docs/build/custom.html).
Both components are MIT-licensed; their notices are bundled with the app.
