# ============================================================================
# FenixMovilPro — ProGuard/R8 rules (release). Se activa minify en F3.
# Regla: NUNCA ofuscar clases serializadas del protocolo MCP JSON-RPC.
# ============================================================================

# kotlinx.serialization: mantener @Serializable + companion serializer
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.fenix.movil.** {
    *** Companion;
}
-keepclasseswithmembers class com.fenix.movil.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Ktor (reflexión limitada)
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
