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
- **Fase B (B1–B6): sandbox Linux propio** — ver detalle abajo; siguiente
  tarea: **B2** (rootfs Debian 12 arm64 con SHA-256).
- Nota: los directorios de referencia indicados en el plan
  (`/tmp/kai-ref/`, `/tmp/fenix-movil-oficial/`) **no existen en este
  entorno**; al ser la consigna escribir todo desde cero (sin copia), esto
  no bloquea: el comportamiento a conservar se especifica desde
  `biblioteca-fenix-v3/docs/` (autoridad) y este repo lo implementa desde cero.
- *Actualización 2026-10-21 (post-B2):* B1 y B2 completas → siguiente
  pendiente real: **B3** (`SandboxManager.kt`).
- *Actualización 2026-10-21 (post-B3):* B1, B2 y B3 completas → siguiente
  pendiente real: **B4** (`ShellSession.kt`: PTY + streams + redimensión).
- *Actualización 2026-10-22 (post-B4):* B1–B4 completas → siguiente
  pendiente real: **B5** (ver plan; fase B continúa).

---

## Fase B — Sandbox Linux propio (B1–B6) 🔄 EN CURSO

### B1 — proot + libtalloc propios con NDK r29 ✅ COMPLETA

**Verify ejecutado (exit 0):**
- `bash tools/build-proot.sh` → build completo desde caché vacía:
  descarga con SHA-256 OK, talloc + proot compilados, `libproot.so`
  publicado (187280 bytes).
- `bash tools/build-proot.sh --verify-only` → exit 0: ELF aarch64 para
  Android 26 (intérprete `linker64`), **los 3 segmentos LOAD con
  `p_align=0x4000`** (16 KB).
- `./gradlew :app:assembleDebug` → exit 0; el APK contiene
  `lib/arm64-v8a/libproot.so` (packaging `useLegacyPackaging=true` ya
  configurado en fase A).
- Barrera de limpieza F4 sobre el árbol completo → sin coincidencias.

**Artefactos:**
- `tools/build-proot.sh` (receta reproducible: fuentes pinneadas por
  SHA-256, verificación de hash, build, strip, publicación, `--verify-only`).
- `tools/android-bionic-config.h` (shim `config.h` de talloc para bionic 26).
- `tools/loader-ld-script.ld` (script LLD del loader interno; evita el
  ELF esparcido de ~128 GB).
- `docs/build-proot.md` (receta completa, gotchas, licencias, nota de
  verificación).
- `app/src/main/jniLibs/arm64-v8a/libproot.so` (binario propio,
  talloc estático, único NEEDED = `libc.so`/`libdl.so`).
- Caché de build **fuera** del repo:
  `${XDG_CACHE_HOME:-~/.cache}/fenix-proot-build` (el árbol upstream no
  reside en el repo: integridad + barrera F4).

**Desviación 7 — `verification_cmd` literal de B1 inaplicable:**
- El plan lo escribe como `readelf -l <so> | grep -q "align 0x4000"`. Con
  GNU binutils `readelf` esa cadena **no puede aparecer nunca**: `align`
  solo existe en el encabezado de columna (`… Flags  Align`, A mayúscula) y
  cada valor de alineación se imprime en la línea siguiente de su segmento,
  sin la palabra al lado (verificado empíricamente: única ocurrencia de
  `align` en la salida = línea 8, el encabezado).
- Verificación implementada (equivalente y **más estricta**): parsear todos
  los segmentos `LOAD` y exigir `p_align == 0x4000` en cada uno, más
  `file` = ARM aarch64, más intérprete `linker64` → implementada en
  `tools/build-proot.sh --verify-only` (exit 0). Detalle en
  `docs/build-proot.md` (sección «Verificación»).
- Resultado real del binario publicado: los 3 LOAD en `0x4000`.

**Gotchas resueltos** (tabla completa en `docs/build-proot.md`): versión
talloc parseada del tarball; rutas con espacios del repo vs
`$(shell pkg-config)` de make (caché en path sin espacios); LLD
`-Ttext=0x2000000000` → ELF de 128 GB (script de enlazador propio);
`llvm-strip` no strippea el loader ligado en VMA alta (strip manual del
binario final); waf/configure de talloc inaplicable en cross (shim header).

### B2 — rootfs Debian 12 Bookworm arm64 con SHA-256 ✅ COMPLETA

**Verify ejecutado (exit 0):**
- `bash tools/fetch-rootfs.sh --extract` → descarga completa desde la fuente
  pinneada (94984164 bytes), SHA-256 OK, extracción OK, verificación profunda
  OK: `etc/debian_version` = **12.15**, `libc6` = **2.36-9+deb12u14**
  (confirma el target «Debian 12 Bookworm / glibc 2.36» de arquitectura.md).
- `bash tools/fetch-rootfs.sh --verify-only` → **exit 0**: hash coincide con
  el valor pinneado `5f626ce3cb8443b846aac19fb822212490098856815b4eb1952cd0d6a55abc23`.
- Caminos negativos verificados (no vacuosamente en verde): tarball ausente →
  exit 1; tarball con hash corrupto → exit 1; tarball íntegro → exit 0.
- Barrera de limpieza F4 sobre el árbol completo → sin coincidencias.

**Artefactos:**
- `tools/fetch-rootfs.sh` (receta reproducible: fuente pinneada por versión +
  SHA-256, fallback entre réplicas, verificación determinista, `--extract` con
  chequeo profundo Debian 12 / glibc 2.36, `--verify-only`).
- `docs/rootfs.md` (receta completa, por qué runtime y no embebido, licencias,
  notas de verificación).

**Gotchas / observaciones (detalle en `docs/rootfs.md`):**
- **Fallback entre réplicas**: el host raíz
  `images.linuxcontainers.org` estuvo degradado/tasa-limited durante la
  primera tentativa de descarga de esta corrida (timeout de conexión); el
  servidor publica detrás réplicas CDN (`{region}{n}mirror{n}.do…`) vía
  redirect 302. Se implementó una lista de candidatos ordenada (canónico
  primero, luego réplicas observadas) que solo afecta **disponibilidad**,
  nunca **integridad**: cualquier host sirve el mismo byte y el SHA-256
  pinneado decide si el artefacto es aceptado. La segunda tentativa (tras
  recuperación del host raíz) completó sin necesidad de fallback.
- **Runtime, no embebido**: el rootfs (~90 MB) se descarga en runtime con
  SHA-256, materializando la mitigación de riesgo del plan («Rootfs Debian 12
  > tamaño APK»); no hay ningún activo en `app/src/main/assets/` (el scope
  lo permitía, no lo requería).
- `verification_cmd` de B2 **no** requirió enmienda: es evaluable literalmente
  tal como figura en el plan (a diferencia de B1, ver Desviación 7).

### B3 — `SandboxManager.kt`: montaje, arranque y parada ✅ COMPLETA

**Verify ejecutado (exit 0):**
- `./gradlew :app:testDebugUnitTest --tests '*SandboxManager*'` →
  **BUILD SUCCESSFUL: 22 tests, 0 fallos, 0 errores, 0 omitidos**
  (clase `com.fenix.movil.sandbox.SandboxManagerTest`, 1.19 s).
- `./gradlew :app:assembleDebug` → exit 0 (el subsistema entra en el APK
  con Hilt y **sin warnings de compilación**; DEUDA-0).
- Barrera de limpieza F4 sobre el árbol completo → sin coincidencias
  (`grep … --exclude-dir=.git .` → exit 1).

**Artefactos** (`app/src/main/java/com/fenix/movil/sandbox/`):

| Archivo | Rol |
|---|---|
| `SandboxState.kt` | enum `STOPPED/BOOTING/RUNNING/ERROR` + documento de transiciones. |
| `BindMount.kt` | montaje host→guest (`-b host[:guest]`); rutas **absolutas** obligatorias (rechazo ruidoso en `init`). |
| `SandboxConfig.kt` | config inmutable, `validationError()` (antes de lanzar), `buildCommand()` (argv proot completo), `guestEnvironment()` (aislado vía `env -i`), `hostEnvironment()` (entorno **reemplazado**, nada heredado), `resolvConfContent()`. |
| `SandboxProcess.kt` | interfaces `SandboxProcessLauncher`/`SandboxProcessHandle` (**puerto donde B4 enchufará la PTY**) + `ProcessBuilderLauncher` real: entorno reemplazado y stdout/stderr consumidos por hilos daemon con búfer acotado (si nadie los leyera, la tubería de 64 KB bloquearía el sandbox). |
| `SandboxManager.kt` | máquina de estados con `Mutex` (`start`/`stop` serializados), ventana de gracia de arranque, supervisor de vida por `delay` (tiempo virtual, sin `waitFor` bloqueante), SIGTERM→SIGKILL en la parada, volcado de DNS. |
| `SandboxPaths.kt` | rutas reales desde `Context` (arquitectura.md §3): `nativeLibraryDir/libproot.so`, `noBackupFilesDir/sandbox/debian-12`, `filesDir/sandbox-share` → `/fenix`, DNS desde `LinkProperties`. |
| `SandboxModule.kt` | proveedor Hilt `@Singleton` de `SandboxManager`. |
| `app/src/test/…/SandboxManagerTest.kt` | test del gate (22 casos) + ciclo real con `ProcessBuilder`. |

**Cobertura de los 22 tests:** construcción del argv (rootfs, binds
`/dev` `/proc` `/sys`, `-w`, `-0`, `--link2symlink`, `--kill-on-exit`, `-v`,
`env -i …`, comando guest al final) · aislamiento host/guest ·
transición `STOPPED→BOOTING→RUNNING` observada como `StateFlow` · 6
validaciones que impiden lanzar (rootfs ausente, rootfs sin
`etc/debian_version`, proot ausente, proot no ejecutable, `pollIntervalMs≤0`
—contra bucle infinito—, fallo del launcher) · muerte dentro de la ventana
de gracia → `ERROR` con código + cola de stderr · `stop()` con destrucción
del proceso e idempotencia · no-relanzo en `RUNNING` · reinicio desde
`ERROR` · salida inesperada (≠0) → `ERROR`, salida limpia (=0) → `STOPPED` ·
bind con ruta relativa rechazado · escritura y omisión de
`etc/resolv.conf` · ciclo real end-to-end con `ProcessBuilder`
(`Assume` sobre `/bin/sh`).

**Desviaciones y decisiones registradas (numeración global):**

8. **DNS del dispositivo volcado al rootfs (incluido en B3, sin tarea propia):**
   bionic resuelve vía *netd* y no publica un `/etc/resolv.conf` que el
   guest pueda leer. `SandboxPaths` lee los servidores de
   `LinkProperties` (requiere `ACCESS_NETWORK_STATE`, ya declarado en el
   manifest) y `SandboxManager` los vuelca en `<rootfs>/etc/resolv.conf`
   **solo si cambiaron** (no reescribe el rootfs en cada arranque); sin
   DNS configurados no se toca el archivo. Cero valores fijos (RN-06).
9. **Sin montaje de storage externo:** los binds por defecto son `/dev`,
   `/proc`, `/sys` más la carpeta privada de la app (`…/sandbox-share`
   → `/fenix`, mismo UID que la app: no amplía privilegios). `/sdcard` y
   `/storage` quedan **fuera** a propósito: la auditoría B6 exige que el
   sandbox no ejecute ni lea storage externo.
10. **`verification_cmd` de B3 sin enmienda:** `./gradlew
    :app:testDebugUnitTest --tests '*SandboxManager*'` es evaluable
    literalmente y sale 0 (a diferencia de B1, ver Desviación 7).
11. **Supervisor por tiempo virtual:** la vida del proceso se sondea con
    `delay` sobre el scope inyectado y no con `Process.waitFor`, para que
    la máquina de estados sea determinista en pruebas (tiempo virtual de
    `runTest`) y no bloquee hilos en producción; el único bloqueo real
    (`SIGTERM` + gracia) corre en `Dispatchers.IO` dentro de `stop()`/`fail()`.

---

### B4 — `ShellSession.kt`: PTY, streams, escapes, UTF-8, redimensión ✅ COMPLETA

**Verify ejecutado (exit 0):**
- `./gradlew :app:testDebugUnitTest --tests '*ShellSession*'` →
  **BUILD SUCCESSFUL: exit 0** (clase `com.fenix.movil.terminal.ShellSessionTest`,
  20 tests, 0 fallos).
- `./gradlew :app:testDebugUnitTest` (suite completa) → **96 tests,
  0 fallos, 0 errores, 0 omitidos** (incluye las 22 de SandboxManager y
  las nuevas de PTY).
- `./gradlew :app:assembleDebug` → exit 0 (el subsistema entra en el APK).
- Barrera de limpieza F4 sobre el árbol completo → sin coincidencias
  (`grep … --exclude-dir=.git .` → exit 1).

**Artefactos** (`app/src/main/java/com/fenix/movil/terminal/` y
`sandbox/`):

| Archivo | Rol |
|---|---|
| `PtyTransport.kt` | puerto `PtyTransport` (`stdin`/`stdout`/`stderr` crudos + `isTty` + `resize(size): Boolean` + `close(terminate)`), fiel al contrato de `ShellSession`. |
| `TerminalChunk.kt` | tipos de token del escáner ANSI (`TEXT`/`ESCAPE`/`OSC`) y `AnsiToken`. |
| `TerminalSize.kt` | value class `TerminalSize(rows, cols)` + `toProtocol()` ("filas columnas"). |
| `Utf8StreamDecoder.kt` | decodificador UTF-8 incremental: un multibyte partido entre dos lecturas se ensambla (no duplica ni corrompe). |
| `AnsiEscapeScanner.kt` | máquina de estados de escapes: CSI (`ESC [ … final`), OSC (`ESC ] … ST`), cadenas (`ESC P`/`ESC ^`/`ESC _`), abortos, y **flush de texto pendiente al fin del chunk** (sin esto el scrollback nunca se actualiza). |
| `ShellSession.kt` | máquina de estados `IDLE/OPENING/OPEN/CLOSED`, `readLoop` sobre el stdout del transporte (dispatcher IO, búfer acotado, stop cooperativo), `sendLine` (`\r` en tty, `\n` en pipe), `resize` delegado, scrollback con tope. |
| `sandbox/PtyHelper.kt` | helper POSIX de la sesión (ver `docs/shell-session.md`): template `fenix-pty.sh` (rol fuera + rol `--inside`), FIFO de control, acuse de ventana, `install()`. |
| `sandbox/BridgePtyTransport.kt` | puente host↔PTY del guest: `PtyProcess`/`PtyProcessLauncher` (puerto para dobles de proceso), `ProcessBuilderPtyLauncher` real, `open()` (variante PTY + acuse inicial), `attach()`, `resize()` con acuse, `close()` idempotente. |

**Cobertura de los tests del gate (`ShellSessionTest`, 20 casos):**
transiciones de estado · `sendLine` con `isTty=true` (terminador `\r`) y
`isTty=false` (`\n`) · `readLoop` alimentado por un `FeedableInputStream`
(texto al scrollback, UTF-8 multibyte partido, escapes CSI/OSC) · tope de
scrollback (evita crecimiento sin límite) · `resize` delegado al
transporte · cierre cooperativo sin bloquear el hilo · doble `close`
idempotente · errores de IO del stream → estado `CLOSED`.

**Cobertura de los tests de soporte:** `AnsiEscapeScannerTest` (CSI
partida entre dos feeds, OSC con ST, abortos, texto pendiente al fin del
chunk) · `Utf8StreamDecoderTest` (multibyte partido, secuencias
completas, bytes inválidos) · `TerminalSizeTest` (protocolo) ·
`PtyHelperTest` (contenido determinista del template, instalación,
canales) · `BridgePtyTransportTest` (variante PTY, argv de proot,
protocolo de acuse con dobles, **integración real** con `script(1)`: echo
aritmético, `stty size` tras resize, cierre limpio).

**Desviaciones y decisiones registradas (numeración global):**

12. **`verification_cmd` de B4 concretizado:** el plan escribe "test
    unitario `ShellSession` exit 0"; se interpretó como
    `./gradlew :app:testDebugUnitTest --tests '*ShellSession*'`, que
    evalúa literalmente y sale 0. La suite completa (96 tests) también
    pasa, incluyendo el test de integración real de la PTY.
13. **`docs/shell-session.md` creado fuera del whitelist:** el KDoc de
    `PtyHelper.kt` lo referencia como documento del diseño, y las reglas
    del repo exigen que todo archivo referenciado exista. Es la única
    escritura fuera de `app/src/main/java/**/{sandbox,terminal}/*`.

**Decisiones de diseño (fuera de desviación, dentro del alcance):**

- **PTY sin código nativo:** la consigna de B4 prohíbe código nativo
  nuevo; bionic no expone `openpty()` portable. La PTY se materializa en
  el rootfs de B2 con `script(1)` + `stty(1)`, y el host ve solo
  tuberías + FIFO de control (`docs/shell-session.md`).
- **`PtyTransport` separado de `SandboxProcessHandle` (B3):** la interfaz
  de B3 no tiene streams crudos a propósito (sus hilos `OutputTail`
  consumen stdout/stderr para el volcado de errores de arranque); una
  sesión interactiva necesita el stream en vivo, así que es un puerto
  aparte.
- **Acuse de ventana (no a ciegas):** `resize()` devuelve `true` solo si
  el guest confirmó la geometría aplicada en el tty; sin acuse devuelve
  `false` y la llamada es reintentable (contrato de `PtyTransport`).
- **Gotcha POSIX resoluble:** sin job control, la shell redirige el stdin
  de listas asíncronas a `/dev/null`; `script(1)` corre en background y
  vería EOF al instante (cerrando la pty). El helper salva el stdin real
  en un fd y se lo pasa explícito. Detalle en `docs/shell-session.md`.
- **Shell interior ignora SIGTERM:** el `trap` del rol fuera envía
  `TERM` + `KILL` a `script(1)`; el kernel cierra el master y el slave
  recibe `SIGHUP` (que sí mata la sesión interactiva).
