# Regras de Proteção e Obfuscação do Proguard / R8 - Meus Recortes

# Preservar assinaturas de métodos e anotações para reflexão e serialização
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# Manter as classes do Kotlin
-keep class kotlin.Metadata { *; }

# Jetpack Compose e Kotlin Compiler
-keep class androidx.compose.compiler.plugins.kotlin.** { *; }
-dontwarn androidx.compose.compiler.plugins.kotlin.**

# Preservar ViewModels para instanciacão dinâmica do Compose viewModel()
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# CameraX e dependências nativas
-keep class androidx.camera.core.** { *; }
-keep class androidx.camera.camera2.** { *; }
-keep class androidx.camera.lifecycle.** { *; }
-keep class androidx.camera.video.** { *; }
-keep class androidx.camera.view.** { *; }
-dontwarn androidx.camera.**

# Biblioteca de Mídia e Reprodução
-keep class android.media.** { *; }

# Manter classes de configuração (Evitar que SupabaseConfig seja renomeado)
-keep class com.example.meusrecortes.data.SupabaseConfig { *; }
