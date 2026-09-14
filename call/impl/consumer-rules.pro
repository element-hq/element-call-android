# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

# Consumer rules for element-call: what a minifying host has to keep for the matrix-rust-rtc core to
# work. Shipped here because nobody else does (plan §8.3): the RTC AAR's own proguard.txt is a
# placeholder and the SDK AAR carries none. tests/consumer minifies against these on every change.

# JNA: uniffi's FFI layer reaches native code through it by name, and libjnidispatch calls back into
# com.sun.jna by reflection.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.*

# The uniffi bindings of matrix-rust-rtc: callback interfaces, records and enums are looked up by name
# from the Rust side, and the generated Kotlin has no @Keep of its own.
-keep class uniffi.matrix_rtc_ffi.** { *; }
-keep class org.matrix.rtc.** { *; }

# libwebrtc's Java half, bundled in the RTC AAR: its JNI entry points are resolved by name from native
# code, which R8 cannot see.
-keep class livekit.org.webrtc.** { *; }
-keep class livekit.org.jni_zero.** { *; }
-dontwarn livekit.org.webrtc.**

# kotlinx.serialization, for the JSON the core wrapper carries.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class io.element.android.call.**$$serializer { *; }
-keepclassmembers class io.element.android.call.** { *** Companion; }
-keepclasseswithmembers class io.element.android.call.** { kotlinx.serialization.KSerializer serializer(...); }
