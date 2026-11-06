# Keep classes and constructors looked up from jni_bridge.cpp.
-keep class com.veluna.usbaudio.model.DacFormat { <init>(...); }
-keep class com.veluna.usbaudio.model.DacCapabilities { <init>(...); }

# Keep names used by the exported JNI method symbols.
-keepclasseswithmembernames class com.veluna.usbaudio.UsbAudioDriver { native <methods>; }
