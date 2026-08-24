# The instrumentation runner is declared in the test manifest, outside the ordinary test call
# graph. Its startup path calls AndroidX Trace before any test method is discovered.
-keep class androidx.test.runner.AndroidJUnitRunner { *; }
-keep class androidx.tracing.** { *; }
