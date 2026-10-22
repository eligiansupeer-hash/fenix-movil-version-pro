# Auditoría de seguridad del sandbox — Tarea B6 (plan fenix-movil-version-pro)

- **Fecha**: 2026-10-22
- **Alcance**: subsistemas `app/src/main/java/com/fenix/movil/sandbox/` y `terminal/`, más
  `AndroidManifest.xml`, `data_extraction_rules.xml`, `app/build.gradle.kts`,
  `jniLibs/arm64-v8a/libproot.so` y `tools/build-proot.sh`.
- **Método**: revisión estática de código + verificaciones deterministas (grep, `readelf`,
  `unzip -l`, ejecución de `build-proot.sh --verify-only`). Sin ejecución en dispositivo
  (quedará para F2/E2).
- **Estado del árbol auditado**: commit `5a4053e` (HEAD de origin/main al momento de la
  auditoría), 138 tests verdes y `assembleDebug` exitoso sin mutar código.

Checklist contra las 6 políticas de seguridad.md. Cada ítem declara evidencia localizable
(ruta:línea o comando y su resultado). Donde el control aún no existe porque su tarea es
posterior, se marca **CUMPLE (base)** con el trabajo futuro explícito.

## Checklist

### SEG-01 — Prohibición de captura de pantalla no autorizada

- No hay código de captura de pantalla: `grep -rniE 'screencap|MediaProjection|captureScreen|screenshot' app/src/main/java/` → 0 coincidencias.
- No se declara `FOREGROUND_SERVICE_MEDIA_PROJECTION` ni permiso equivalente en el manifest (solo INTERNET, ACCESS_NETWORK_STATE, FOREGROUND_SERVICE(+DATA_SYNC), POST_NOTIFICATIONS).
- **Veredicto: CUMPLE.** La app no puede capturar pantalla. Si una tarea futura (p. ej. E2) la requiere, debe ser opt-in y justificada aparte.

### SEG-02 — Almacenamiento seguro de credenciales

- Sin secretos en código: `grep -rniE '(api[_-]?key|token|secret|password)\s*[=:]\s*["'"'"'][A-Za-z0-9_\-]{20,}['"'"']' app/src/main/` → 0 coincidencias.
- Sin keystores/tokens trackeados: `git ls-files | grep -iE '\.(jks|keystore|p12|pfx|pem)$'` → 0; `git ls-files app/src/main/assets/` → vacío; `find` fuera de `.git` de `api_keys.json|*.jks|*.keystore|*.pem|secrets.properties|.env` → 0.
- `.gitignore` excluye keystores (`*.jks`, `*.keystore`, `*.p12`, `*.pfx`, `*.pem`) y secretos (`.env`, `secrets.properties`, `api_keys.json`, `*.token`).
- El rootfs vive en `noBackupFilesDir/sandbox/debian-12` (`SandboxPaths.kt:52`, `SandboxConfig.kt:23`), fuera de `filesDir` y por tanto fuera del backup de la app: `data_extraction_rules.xml` excluye `domain="root"` en cloud-backup y device-transfer, y el manifest tiene `allowBackup="false"` + `fullBackupContent="false"` + `usesCleartextTraffic="false"`.
- **Veredicto: CUMPLE (base).** Cero secretos y cero superficie de credenciales commiteable. La bóveda cifrada (KeystoreVault, tarea C5) es quien implementará el control positivo de SEG-02; al crearse, requerirá nueva auditoría.

### SEG-03 — Aislamiento sandbox (chroot/proot)

- proot corre como usuario normal de la app vía `ptrace`, sin privilegios: no hay llamadas a `su`/`sudo` ni permisos privilegiados en el manifest (`grep -rniE '\b(su|sudo)\b' app/src/main/java/ AndroidManifest.xml` → solo prosa española en KDoc, 0 invocaciones).
- Entorno reemplazado, no heredado: `SandboxProcess.kt:69-71` hace `builder.environment().clear()` y repuebla con un mapa controlado (DNS desde `LinkProperties`, sin valores fijos).
- Mínimo de binds: `SandboxConfig.kt:179-181` monta solo `/dev`, `/proc`, `/sys`; `SandboxPaths.kt:35` añade `sharedDir → /fenix`; `BridgePtyTransport.kt:343` añade el share dir del instalador. Sin `/data/data` ajeno, sin `/sdcard`, sin `/storage`.
- El binario de proot se ejecuta desde `applicationInfo.nativeLibraryDir/libproot.so` (`SandboxPaths.kt:15,51`), directorio interno de solo lectura de la instalación.
- **Veredicto: CUMPLE.** El guest ve únicamente su rootfs y los binds mínimos; el host no expone sus datos privados.

### SEG-04 — Credenciales jamás transportadas en prompts a agentes

- La app aún no integra asistencia guiada por LLM (no hay cliente LLM ni transporte de MCP en el código; el servidor `/mcp` es la tarea C1). Por construcción no hay ruta que envíe credenciales a un modelo.
- El instalador de CLIs trabaja solo con nombres de paquete públicos (`InstallAction.kt`, alfabetos estrictos por token; sin URLs hardcodeadas).
- **Veredicto: CUMPLE (base).** Invariante de diseño vigente; C1/C3 deberán mantenerlo (la clave viaja por IPC local cifrada, jamás en el prompt; DLP sanitiza antes de cualquier salida).

### SEG-05 — Accesibilidad opt-in con exención legal

- No hay servicio de accesibilidad declarado ni referenciado: `grep -rniE 'AccessibilityService|BIND_ACCESSIBILITY' app/src/main/` → 0 coincidencias.
- **Veredicto: CUMPLE.** La app no declara accesibilidad. E2 (lectura de pantalla) solo podrá añadirse opt-in, con consentimiento explícito y base legal documentada.

### SEG-06 — W^X y empaquetado nativo

- `libproot.so` se empaqueta como biblioteca compartida nativa en `jniLibs/arm64-v8a/` y Android lo extrae a `nativeLibraryDir` (solo lectura, ejecutable, sin escritura desde la app) — presente en el APK: `unzip -l app-debug.apk` → `lib/arm64-v8a/libproot.so` (187280 bytes).
- Alineación 16 KB exigida por targetSdk ≥ 35 en Android 15+: `readelf -lW` → 3 segmentos LOAD con `p_align 0x4000`.
- Formato correcto: `bash tools/build-proot.sh --verify-only` → exit 0, ELF 64-bit LSB pie executable, ARM aarch64, dependencias mínimas (`NEEDED`: solo `libc.so`, `libdl.so`).
- Sin hacks de compatibilidad: `targetSdk = 36` (`app/build.gradle.kts:29`), muy por encima del umbral 28 donde la restricción W^X de Android se relaja. No hay ejecución desde storage externo (verificado en SEG-03).
- `useLegacyPackaging = true` está justificado en comentarios: mantiene el `.so` extraído en `nativeLibraryDir` (el mecanismo soportado de ejecución de código nativo propio).
- **Veredicto: CUMPLE.** La única pieza ejecutable viaja como .so del APK, se extrae a un directorio read-only y se ejecuta desde ahí.

## Defensas observadas (no pedidas, relevantes)

- **Inyección de shell en instalación**: cada argumento de CLI (`InstallAction`, `CliSpec`) se valida contra un alfabeto estricto antes de construir la receta; el paquete no puede romper su token.
- **Canales FIFO acotados**: `PtyHelper` usa tuberías acotadas (64 KB) con lectores dedicados para stderr; no hay bloqueo por tubería llena ni crecimiento sin cota.
- **Validación de binds**: `BindMount` rechaza rutas relativas; los mounts del guest se construyen solo desde rutas absolutas de la app.
- **Estados sin carrera**: `SandboxManager` confina la escritura de estados a un único hilo (documentado en `SandboxManager.kt:140`).

## Comandos deterministas ejecutados

| Comando | Resultado |
| --- | --- |
| `grep -rniE 'screencap\|MediaProjection\|captureScreen\|screenshot' app/src/main/java/` | 0 coincidencias |
| `grep -rniE 'AccessibilityService\|BIND_ACCESSIBILITY' app/src/main/` | 0 coincidencias |
| grep de credenciales con valor (patrón ≥20 chars) en `app/src/main/` | 0 coincidencias |
| `git ls-files \| grep -iE '\.(jks\|keystore\|p12\|pfx\|pem)$'` | 0 |
| `find` de archivos sensibles fuera de `.git` | 0 |
| grep de ejecución externa (`/sdcard`, `/storage/`, `getExternalFilesDir`, `Environment.`) en código | 0 (falsos positivos de `childEnvironment` descartados) |
| `grep -rniE '\b(su\|sudo)\b'` en código y manifest | 0 invocaciones |
| `readelf -lW libproot.so` | 3× LOAD `p_align 0x4000` |
| `readelf -d libproot.so` (NEEDED) | solo `libc.so`, `libdl.so` |
| `bash tools/build-proot.sh --verify-only` | exit 0 — ELF 64-bit LSB pie executable, aarch64 |
| `unzip -l app-debug.apk` | `lib/arm64-v8a/libproot.so` presente (187280 B) |
| `./gradlew :app:testDebugUnitTest` | exit 0 — 138 tests, 0 fallos |
| `./gradlew :app:assembleDebug` | exit 0 |
| Barrera de limpieza (6 términos prohibidos) | 0 coincidencias |

## Conclusión

Las 6 políticas SEG-01…SEG-06 se cumplen en el estado actual del proyecto. Cero secretos,
cero superficie de captura, cero declaraciones de accesibilidad, sandbox con binds mínimos
y entorno aislado, proot empaquetado como .so nativo alineado a 16 KB con targetSdk 36.
Las tres áreas cuyo control positivo aún no existe — bóveda de credenciales (C5), transporte
con asistencia LLM (C1/C3) y accesibilidad opt-in (E2) — quedan marcadas como CUMPLE base
con la re-auditoría obligatoria al implementarse.
