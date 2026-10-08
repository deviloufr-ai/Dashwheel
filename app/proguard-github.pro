# Keep rules for the GitHub edition only (see the "github" flavor in build.gradle.kts).
# The Play edition does not ship these libraries.

# dadb (pure-Kotlin ADB client used by the priv-app self-install).
-keep class dev.mobile.dadb.** { *; }
-dontwarn dev.mobile.dadb.**
