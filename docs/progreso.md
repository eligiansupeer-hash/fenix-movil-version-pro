# PROGRESO DE IMPLEMENTACIÓN — `fenix-movil-version-pro`

> Fuente de persistencia de avance del plan DAG (7 fases, tareas A0→G3).
> Cada fase completada se registra aquí + commit Git. Verificador: cada
> tarea se cierra solo con su `verification_cmd` en exit 0.

---

## Fase A — Cimientos (A0–A4) ✅ COMPLETA

| T | Tarea | verification_cmd | Resultado |
|---|---|---|---|
| A0 | Repo público + clone local, `.gitignore`, LICENSE MIT, estructura base | `test -d .git && git remote -v \| grep fenix-movil-version-pro` | **exit 0** |
| A1 | `settings.gradle.kts` + `build.gradle.kts` raíz + `gradle.properties` + wrapper + versiones de arquitectura.md | `./gradlew help` | **exit 0** |
| A2 | `app/build.gradle.kts` (compileSdk 37, minSdk 26, Compose, Hilt, `useLegacyPackaging=true`, `jniLibs/arm64-v8a`) | `./gradlew :app:tasks` | **exit 0** |
| A3 | Tema Compose `Color.kt`/`Type.kt`/`Theme.kt` con 8 tokens base de Diseno_penpot.md + extras documentados | `./gradlew :app:compileDebugKotlin` | **exit 0** |
| A4 | `AndroidManifest.xml` + permisos mínimos + `FenixApplication` (Hilt) + `MainActivity` | `./gradlew :app:assembleDebug` | **exit 0** |

**Barrera de limpieza (F4) adelantada sobre fase A:** patrón anti-copia del
plan §2 (6 tokens prohibidos: entorno ajeno de terminal + nombres de autores
y proyectos de referencia externos) corrido con
`grep -riE "<patrón F4>" --exclude-dir=.git .` → **sin coincidencias**.
(El patrón exacto vive solo en el plan maestro, fuera de este repo, para no
violar por citarlo.)

### Artefactos creados en fase A
- Repo: `eligiansupeer-hash/fenix-movil-version-pro` (público, `main` con push).
- `.gitignore` (Android/Kotlin/keystores/secrets/rootfs), `LICENSE` (MIT).
- `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`.
- Gradle wrapper **9.6.0** + `app/build.gradle.kts` + `app/proguard-rules.pro`.
- `ui/theme/{Color,Type,Theme}.kt` (tokens Penpot literales, dark-only).
- `AndroidManifest.xml` (INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+DATA_SYNC), POST_NOTIFICATIONS; backup deshabilitado), `FenixApplication.kt`, `MainActivity.kt`, `res/values/{strings,themes,colors}.xml`, `res/xml/data_extraction_rules.xml`.

### Desviaciones registradas (con causa determinista)

1. **Gradle wrapper 9.6.0 en vez de 8.x** (desviación de A1):
   - Conflicto trivalente interno del plan: A1 pide `Gradle 8.x`; el stack
     fija **Hilt 2.60.1** (usuario + arquitectura.md); Hilt 2.60.1 exige
     **AGP ≥ 9.0.0**; AGP ≥ 9.0.0 exige **Gradle ≥ 9.1.0**.
   - Precedencia: Hilt 2.60.1 tiene doble autoridad de especificación
     (mensaje de encargo + arquitectura.md); `Gradle 8.x` solo está en el
     plan. Se conserva Hilt 2.60.1 exacto y se sube Gradle.
   - Cadena final: **Gradle 9.6.0 + AGP 9.4.1 + compileSdk 37** (AGP 9.4.1
     es el mínimo estable que soporta compileSdk 37).
2. **AGP 9.4.1 en vez de 8.x**: obligatorio por Hilt 2.60.1 (arriba) y por
   `compileSdk 37`, exigido por Compose BOM 2026.08.00 / navigation /
   hilt-navigation (18 dependencias). API 37 se publica como
   `platforms;android-37.0` (formato de minor version nuevo).
3. **`org.jetbrains.kotlin.android` NO se declara**: AGP 9 integra Kotlin
   built-in y declararlo aborta la configuración (mensaje del propio plugin).
4. **`targetSdk 36` estable** (plan exige 35+; arquitectura.md contempla 37
   como target futuro cuando salga de preview): `compileSdk=37`,
   `compileSdkMinor=0`.
5. **Ktor engine CIO en vez de Netty**: Netty arrastra natives
   multiplataforma de escritorio que son puro bloat en un APK arm64; CIO es
   puro coroutines y encaja con el servidor loopback `localhost:8080/mcp`.
6. **Versiones verificadas, no adivinadas**: cada versión del catalog se
   contrastó contra el metadata XML de Google Maven / Maven Central
   (correcciones: navigation 2.10.2, activity 1.13.0, core-ktx 1.19.1,
   coroutines 1.11.0, lottie 6.7.1, mockk 1.14.11, truth 1.4.5,
   serialization-json 1.12.0, detekt 1.9.1, ktlint-gradle 14.2.0).

### Toolchain (instalado en modo usuario, sin sudo)
- JDK: `~/opt/jdk-21.0.12.1+1` (Temurin 21 LTS).
- Gradle: `~/opt/gradle-9.6.0` (el wrapper del repo se auto-descarga).
- Android SDK: `~/Android/Sdk` con `platform-tools`, `platforms;android-36`
  y `platforms;android-37.0`, `build-tools;36.0.0` y `37.0.0`,
  cmdline-tools 19.0.
- Uso en cada build:
  `export JAVA_HOME=/home/eli/opt/jdk-21.0.12.1+1 PATH=$JAVA_HOME/bin:$PATH`

### Pendiente (siguiente fase)
- **Fase B (B1–B6): sandbox Linux propio** — proot + libtalloc con NDK r29
  (ELF 16KB, verify `readelf`), rootfs Debian 12 arm64 con SHA-256,
  `SandboxManager`, `ShellSession`, instalador de CLIs, auditoría SEG.
- Nota: los directorios de referencia indicados en el plan
  (`/tmp/kai-ref/`, `/tmp/fenix-movil-oficial/`) **no existen en este
  entorno**; al ser la consigna escribir todo desde cero (sin copia), esto
  no bloquea: el comportamiento a conservar se especifica desde
  `biblioteca-fenix-v3/docs/` (autoridad) y este repo lo implementa desde cero.
