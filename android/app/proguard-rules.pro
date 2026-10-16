# The engine bridge and SDL's Java side are called from native code (JNI).
-keep class com.bytarch.dbt3.engine.** { *; }
-keep class org.libsdl.app.** { *; }
