# Instalación de CLIs en el sandbox (B5)

Este documento describe el subsistema `sandbox/installer/` del plan B5:
**detección de red, instalación de CLIs dentro del rootfs y verificación
de versiones**. Sobre la base de B2 (rootfs Debian 12), B3 (sesión proot)
y B4 (terminal PTY).

## Objetivo

Que la app pueda, en un solo toque:

1. **Detectar** qué CLIs ya están instalados y con qué versión
   (`<binario> --version` dentro del guest).
2. **Instalar** los que falten, solo si hay red usable, respetando el
   orden y las dependencias de un plan determinista.
3. **Verificar** la versión final y exponer un reporte observable
   (`StateFlow<InstallerReport>`) para UI y pruebas.

## Componentes

| Archivo | Responsabilidad |
|---|---|
| `CliSpec.kt` | Descriptor de CLI: id, binario, dependencias apt, acciones, `minVersion`. Registro por defecto. |
| `InstallAction.kt` | Acciones `AptInstall` / `NpmGlobalInstall` con receta shell y alfabetos anti-inyección. |
| `InstallPlan.kt` | Plan determinista: paso `apt:base` deduplicado + un paso npm por CLI pendiente. |
| `VersionDetector.kt` | Parseo (`1.18.21`, `v22.23.3`, texto mixto) y comparación numérica por segmento. |
| `NetworkProbe.kt` | Puerto `NetworkProbe` + implementación Android (`ConnectivityManager` con `NET_CAPABILITY_VALIDATED`). |
| `GuestCommandRunner.kt` | Puerto para ejecutar comandos efímeros en el guest; implementación `ProotGuestCommandRunner`. |
| `InstallState.kt` | Fases (`InstallerPhase`), `CliStatus` e `InstallerReport`. |
| `CliInstaller.kt` | Orquestador: CHECKING → (READY \| NO_NETWORK \| INSTALLING → VERIFYING → READY/FAILED). |
| `InstallerModule.kt` | Proveedores Hilt (`NetworkProbe`, `CliInstaller`). |

## Ciclo de ejecución

```
IDLE → CHECKING ─┬─(todo instalado)──────────────→ READY
                 ├─(falta instalar y sin red)────→ NO_NETWORK
                 └─(falta instalar y hay red)────→ INSTALLING → VERIFYING
                                                      ├─→ READY
                                                      └─→ FAILED
```

- `CHECKING`: sondea cada CLI con `CliSpec.verifyCommand()` (por defecto
  `<binario> --version`) y construye los `CliStatus`.
- Si no hay pendientes → `READY` (sin red, sin pasos).
- Si hay pendientes y `NetworkProbe.isOnline()` es `false` → `NO_NETWORK`
  **sin ejecutar ningún paso de instalación**.
- `INSTALLING`: ejecuta `InstallPlan.steps` en orden; el primer fallo
  (exit ≠ 0, timeout o `IOException` al lanzar) corta el resto y cierra
  en `FAILED` con el detalle del paso.
- `VERIFYING`: vuelve a sondear; todos satisfechos → `READY`, si no →
  `FAILED`.

## Plan de instalación

1. Un CLI está **pendiente** si su versión detectada no satisface
   `CliSpec.isSatisfiedBy` (`minVersion == null` → cualquier versión
   funcional sirve; `null` detectado → pendiente).
2. Las dependencias apt de los pendientes (dependencias + acciones
   `AptInstall`) se **deduplican** en un único paso `apt:base` al frente,
   en orden de primera aparición.
3. Cada CLI pendiente aporta sus acciones no-apt como paso propio
   (`npm-global:<id>`), en orden del registro.
4. La **verificación no es un paso del plan**: la orquesta el
   `CliInstaller` en `CHECKING` (antes) y `VERIFYING` (después).
5. `requiresNetwork` es `true` solo si hay pendientes.

## Red y seguridad

- El detector de red exige `NET_CAPABILITY_INTERNET` **y**
  `NET_CAPABILITY_VALIDATED`: una Wi-Fi local sin salida real reporta
  `false` y el instalador no intenta `apt-get update` contra un enlace
  muerto.
- **Cero URLs en el código (RN-06)**: la instalación usa los gestores del
  propio rootfs (`apt-get` con su `/etc/apt/sources.list`, `npm` con su
  registro por defecto). La política de mirrors es dato del rootfs, no
  del APK.
- **Anti-inyección**: ids, binarios y nombres de paquete se validan en
  los `init` con alfabetos estrictos (`InstallAction.DEB_PACKAGE`,
  `NPM_PACKAGE`, `BINARY_NAME`, `CliSpec.ID`). Un nombre inválido revienta
  al construirse, no en medio de una instalación.
- Los comandos se ejecutan con `bash -c` y `set -euo pipefail`; el plan
  serializa ejecuciones con un `Mutex` (dos toques no corren dos planes).

## Registro por defecto

| id | binario | npm global | dependencias apt |
|---|---|---|---|
| `opencode` | `opencode` | `opencode-ai` | `ca-certificates curl git nodejs npm` |
| `claude-code` | `claude` | `@anthropic-ai/claude-code` | *(igual)* |
| `codex-cli` | `codex` | `@openai/codex` | *(igual)* |

Paquetes npm verificados contra el registro (2026-10-22). Las versiones
exactas las decide la cadena de suministro del rootfs y del registro npm;
el código no pinea URLs ni versiones de descarga.

## Versiones

- `VersionDetector.parse` extrae el **primer token numérico con puntos**
  (excluye prefijo `v`; trunca sufijos `1.2.3-beta`).
- La comparación es **numérica por segmento** (`1.10.0 > 1.9.9`); los
  segmentos ausentes valen `0` (`1.2 == 1.2.0`).
- `minVersion == null` → cualquier versión funcional satisface (política
  por defecto: "que el CLI arranque y responda").

## Pruebas

Gate de B5:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.fenix.movil.sandbox.installer.*'
```

- `VersionDetectorTest` — parseo de formatos reales, comparación numérica,
  tres ramas de `satisfies`.
- `InstallPlanTest` — alfabetos anti-inyección, recetas shell exactas,
  orden/dedupe/idempotencia/determinismo del plan.
- `CliInstallerTest` — máquina de fases, red ausente sin ejecutar
  instalación, secuencia exacta de comandos con timeouts, fallo de paso
  que corta el resto, `IOException` → `FAILED`.

## Límites actuales (DEUDA-0)

- Sin reintentos por red inestable (el orquestador de reintento vivo es
  de una tarea posterior; aquí solo hay UN intento por corrida).
- Sin UI propia: el reporte queda expuesto como `StateFlow` para la
  pantalla que lo consuma más adelante.
- Los timeouts por defecto (30 s sondeo, 600 s instalación) son
  constantes del `CliInstaller`, no configurables desde UI.
