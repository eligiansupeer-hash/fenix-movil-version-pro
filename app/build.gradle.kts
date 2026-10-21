// ============================================================================
// FenixMovilPro — app/build.gradle.kts (módulo único :app)
// Stack autoridad: docs/arquitectura.md
//   Kotlin 2.4.10 · Compose BOM 2026.08.00 · Material 3 · Hilt 2.60.1
//   Ktor embebido localhost:8080/mcp · proot NDK r29 (ELF 16KB →
//   useLegacyPackaging=true en packaging.jniLibs) · rootfs en runtime
// ============================================================================
plugins {
    alias(libs.plugins.android.application)
    // NOTA (AGP 9 built-in Kotlin): org.jetbrains.kotlin.android va integrado
    // en AGP 9.0+; declararlo explícitamente aborta la configuración.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.fenix.movil"
    // Las libs de este ecosistema (Compose BOM 2026.08.00, navigation,
    // hilt-navigation) exigen compileSdk 37 (docs/arquitectura.md
    // contempla targetSdk 37). targetSdk queda en 36 estable (plan: 35+).
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "com.fenix.movil"
        minSdk = 26          // API 26: Kotlin/Compose/keystore modernos sin soportar basura
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // jniLibs/arm64-v8a contiene los .so propios (proot, talloc, pty)
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // DEUDA-0: sin minify en la fase actual para que el lint/review sea
            // directo; se activa al madurar el proguard (F3).
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = false
    }

    packaging {
        jniLibs {
            // Requerimiento B1: ELF de alineación 16 KB se extrae al
            // nativeLibraryDir y se ejecuta sin W^X conflictivo (solo lectura).
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/license/**"
            excludes += "/META-INF/*.SF"
            excludes += "/META-INF/*.DSA"
            excludes += "/META-INF/*.RSA"
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        htmlReport = true
        xmlReport = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.add("-opt-in=kotlin.RequiresOptIn")
    }
}

dependencies {
    // --- AndroidX core ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // --- Compose (BOM gobierna todas las versiones de androidx.compose) ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Navegación (BottomBar 5 pestañas) ---
    implementation(libs.androidx.navigation.compose)

    // --- Hilt ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // --- Ktor embebido (servidor MCP loopback localhost:8080/mcp) ---
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.cors)

    // --- Coroutines / serialization ---
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // --- Animaciones ---
    implementation(libs.lottie.compose)

    // --- Unit tests ---
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.truth)
    testImplementation(libs.ktor.server.test.host)

    // --- Instrumented tests ---
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.mockk.android)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
