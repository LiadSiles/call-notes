Kotlin API files copied verbatim from k2-fsa/sherpa-onnx v1.13.8
(`sherpa-onnx/kotlin-api/`, Apache-2.0). `Speaker.kt` is trimmed after the
`SpeakerEmbeddingManager` class (the demo `SpeakerRecognition` object is not used).
The package name and field names must stay as they are: the native library
(`libsherpa-onnx-jni.so`, fetched by `scripts/fetch-sherpa-onnx.sh`) looks them up by name.
When upgrading, copy these files from the same tag as the native libraries.

Do not call `processWithCallback` from Kotlin 2 code: its JNI looks up
`invoke(IIJ)Ljava/lang/Integer;` on the callback's class, which invokedynamic lambdas
don't have, and ART aborts the process. Use `process` (see `SpeakerDiarizer`).
