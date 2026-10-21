Kotlin API files copied verbatim from k2-fsa/sherpa-onnx v1.13.8
(`sherpa-onnx/kotlin-api/`, Apache-2.0). `Speaker.kt` is trimmed after the
`SpeakerEmbeddingManager` class (the demo `SpeakerRecognition` object is not used).
The package name and field names must stay as they are: the native library
(`libsherpa-onnx-jni.so`, fetched by `scripts/fetch-sherpa-onnx.sh`) looks them up by name.
When upgrading, copy these files from the same tag as the native libraries.
