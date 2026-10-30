# Release build (the published APK): R8 shrinks and optimises, but does not rename —
# stack traces in the processing log stay readable, and nothing reached by name breaks.
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod

# The accessibility service is referenced only from the manifest.
-keep class com.callnotes.capture.CallRecorderService { *; }

# Native libraries call into these classes by name (JNI): keep every member.
-keep class com.k2fsa.sherpa.onnx.** { *; }
-keep class com.google.ai.edge.** { *; }
-keep class net.zetetic.database.** { *; }
-keep class com.callnotes.inference.whisper.WhisperNative { *; }
-keep interface com.callnotes.inference.whisper.WhisperNative$ProgressListener { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }

# Optional compile-time annotations referenced by libraries, not present at run time.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.j2objc.annotations.**

# Classes a library refers to but doesn't ship (optional features we don't use) must not
# fail the build; at run time they are missing in the debug build just the same.
-ignorewarnings
