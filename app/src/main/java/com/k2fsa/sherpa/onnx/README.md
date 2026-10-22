Kotlin API files copied verbatim from k2-fsa/sherpa-onnx v1.13.8
(`sherpa-onnx/kotlin-api/`, Apache-2.0). `Speaker.kt` is trimmed after the
`SpeakerEmbeddingManager` class (the demo `SpeakerRecognition` object is not used).
The package name and field names must stay as they are: the native library
(`libsherpa-onnx-jni.so`, fetched by `scripts/fetch-sherpa-onnx.sh`) looks them up by name.
When upgrading, copy these files from the same tag as the native libraries.

The app uses only `SpeakerEmbeddingExtractor` (through `inference/speakers/VoiceEmbedder`).
`OfflineSpeakerDiarization.kt` has been unused since 0.5.3 (sherpa's diarization pipeline
calls `exit(-1)` on edge cases); it is kept so this folder stays a verbatim copy of the
release, which keeps upgrades a plain copy and rules out any native lookup of a missing
class. Do not start using it again. (Also: `processWithCallback` crashes from Kotlin 2 —
its JNI looks up `invoke(IIJ)Ljava/lang/Integer;`, which invokedynamic lambdas lack.)
