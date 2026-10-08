# Dashwheel release keep rules.
#
# Most libraries ship their own consumer rules (Compose, MapLibre, OkHttp,
# coroutines). The entries below cover the ones that reach into native code or
# reflection and are not fully covered by consumer rules.

# MapLibre GL + navigation: native bridge and Gson-mapped route models.
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Keep the app's own JSON-facing enums by name: tile kinds and theme modes are
# persisted with Enum.name / valueOf, which R8 would otherwise rename.
-keepclassmembers enum com.openauto.dash.** { *; }

# Kotlin metadata / coroutines internals that R8 may warn about.
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# The QF car app's Parcelables, received in its broadcasts and read back by
# class name (CarBox): keep the name, the CREATOR and the fields.
-keep class com.qf.vehicle.entity.** { *; }
