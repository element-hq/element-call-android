# Copyright (c) 2026 Element Creations Ltd.
#
# SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
# Please see LICENSE files in the repository root for full details.

# Consumer rules for element-call-matrix: what a minifying host has to keep for the Matrix Rust SDK
# bindings to work. The SDK AAR ships no proguard.txt of its own (plan §8.3); Element X keeps these in
# its app rules, and a host that is not Element X gets them from here.

# JNA, which the SDK's uniffi layer reaches native code through.
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { public *; }
-dontwarn java.awt.*

# The SDK's uniffi bindings: callback interfaces (WidgetCapabilitiesProvider, RoomInfoListener) are
# called from Rust by name.
-keep class org.matrix.rustcomponents.sdk.** { *; }
-keep class uniffi.** { *; }

# kotlinx.serialization, for the widget-driver JSON.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
