# Build de proot + libtalloc (B1)

Receta **reproducible y verificable** para compilar el binario `proot` y su
dependencia `libtalloc` de forma propia (sin binarios preempaquetados de
terceros) usando exclusivamente el Android NDK r29, y empaquetarlos como
biblioteca nativa:

```
app/src/main/jniLibs/arm64-v8a/libproot.so
```

El APK lo incluye automáticamente (`packaging.jniLibs.useLegacyPackaging=true`
ya está en `app/build.gradle.kts`). En runtime no es una biblioteca `dlopen`:
se ejecuta como proceso hijo vía `androidInfo.nativeLibraryDir/libproot.so`
(ver `docs/arquitectura.md` SEG-06 y la tarea B3 `SandboxManager`).

## Prerrequisitos

| Requisito | Detalle |
|---|---|
| Android NDK | r29 (probado: `29.0.14206865`), detectado por `ANDROID_NDK_HOME` o el más reciente de `~/Android/Sdk/ndk/*` |
| binutils host | `readelf`, `file` (para la verificación) |
| `curl`, `tar`, `sha256sum` | descarga verificada de fuentes |
| Red | solo la primera ejecución (la caché posterior es offline) |

Instalación del NDK sin sudo:

```bash
yes | sdkmanager --sdk_root="$HOME/Android/Sdk" "ndk;29.0.14206865"
```

## Ejecutar

```bash
bash tools/build-proot.sh                # build completo + verificación
bash tools/build-proot.sh --verify-only  # solo verifica el .so publicado
```

`--verify-only` sale con exit 0 solo si el binario existe, es ELF aarch64
para Android 26 y **todos** sus segmentos LOAD tienen alineación `0x4000`
(16 KB).

## Fuentes (pinneadas por SHA-256)

| Componente | Versión | URL | SHA-256 |
|---|---|---|---|
| proot | 5.4.0 | `https://github.com/proot-me/proot/archive/refs/tags/v5.4.0.tar.gz` | `29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68` |
| talloc | 2.4.2 | `https://download.samba.org/pub/talloc/talloc-2.4.2.tar.gz` | `85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6` |

- El tarball se descarga a una **caché externa al repo**
  (`${XDG_CACHE_HOME:-~/.cache}/fenix-proot-build`) y se verifica con
  `sha256sum -c` antes de extraer: cualquier alteración aborta el build.
- El árbol de fuentes upstream **nunca** vive dentro del repo (además de
  integridad, evita arrastrar documentación de terceros que no pasa las
  barreras de limpieza del proyecto).
- No se aplica ningún parche a las fuentes: proot se compila con su
  `GNUmakefile` original.

## Receta (lo que hace el script)

1. **talloc estático contra bionic** — se compila `talloc.c` a mano con
   `${TARGET}-clang` y el shim `tools/android-bionic-config.h` (header
   `config.h` mínimo con los rasgos de libc/bionic API 26 que `lib/replace/`
   consulta). Se omite el `configure`/waf de upstream: es un build cross puro
   y solo se necesita la librería estática. Salida:
   `prefix/lib/libtalloc.a` + `prefix/include/talloc.h`.
2. **pkg-config stub** — el `GNUmakefile` de proot llama
   `$(shell pkg-config --cflags --libs talloc)`; un stub mínimo
   (`fakebin/pkg-config`) resuelve `TALLOC_PREFIX` (mismas flags que
   devolvería el `pkg-config` real apuntando al prefijo local). Evita un
   paquete host y cualquier fuga de rutas del sistema.
3. **proot** — copia de `src/` (incluye los `.check_*.c` de detección) a un
   dir de build y `make -f GNUmakefile proot` con overrides de línea de
   comandos (no ediciones de makefile):
   - `CC`/`LD` = `${TARGET}-clang` (`aarch64-linux-android26`, minSdk 26)
   - `STRIP=true` (no-op; el strip real es al final, ver «gotchas»)
   - `OBJCOPY`/`OBJDUMP` = `llvm-objcopy`/`llvm-objdump`
   - `HAS_SWIG=` y `HAS_PYTHON_CONFIG=` (sin extensiones Python en cross)
   - `CFLAGS="-O2 -fPIC"`, `LDFLAGS="-Wl,-z,max-page-size=16384"`
     (16 KB desde el enlace, además del default de LLD)
   - `LOADER_LDFLAGS=… -Wl,-T,loader-ld-script.ld …` (ver abajo)
   - `VERSION=5.4.0-fenix` (identidad de build propia)
4. **Loader con script de enlazador propio** — proot enlaza su «loader»
   (blob que se embebe en el binario) con `-Ttext=0x2000000000`. Con LLD,
   esa congruencia offset≡vaddr genera un ELF esparcido de ~128 GB y
   `llvm-objcopy` muere al reubicarlo (`failed to allocate memory buffer`).
   `tools/loader-ld-script.ld` conserva la VMA fija que proot espera con
   offsets de archivo compactos y descarta `.comment/.note/.eh_frame`:
   loader de ~3 KB en vez de 128 GB.
5. **Strip final + publicación** — `llvm-strip` al binario `proot` resultante
   (187 KB) y copia a `app/src/main/jniLibs/arm64-v8a/libproot.so`.

## Gotchas resueltos (por qué la receta es así)

| Síntoma | Causa | Solución en la receta |
|---|---|---|
| `talloc.c: "TALLOC_VERSION_MINOR != TALLOC_BUILD_VERSION_MINOR"` | versiones maj/min no derivadas del nombre del tarball | script parsea `2.4.2` → major/minor/patch reales |
| `clang: no such file or directory: 'Project/…'` | la ruta del repo contiene espacios y `$(shell pkg-config)` parte por palabras | caché fuera del repo (path sin espacios) + enlace si hiciera falta; `.ld` copiado a la caché por el mismo motivo |
| LLD produce ELF de ~128 GB en el loader | `-Ttext=0x2000000000` con offsets≡VMA | `loader-ld-script.ld` |
| `llvm-strip` falla en `loader/loader.elf` | strip de un ELF ligado en 0x2000000000 | `STRIP=true` en el make (no-op) + strip manual del binario final (ya con direcciones normales) |
| waf/configure de talloc inviable en cross | depende de un compiler/executable target | shim `android-bionic-config.h` |

## Verificación

```bash
# Equivalente exacto que ejecuta el script (exit 0 = OK):
bash tools/build-proot.sh --verify-only

# Desglose (lo que hace internamente):
LANG=C file -b app/src/main/jniLibs/arm64-v8a/libproot.so
#   → "ELF 64-bit LSB pie executable, ARM aarch64, …, for Android 26, … stripped"
LANG=C readelf -lW app/src/main/jniLibs/arm64-v8a/libproot.so | awk '$1=="LOAD"{print $NF}'
#   → 0x4000 (×3 segmentos: TODOS con alineación 16 KB)
LANG=C readelf -l app/src/main/jniLibs/arm64-v8a/libproot.so | grep linker64
#   → intérprete /system/bin/linker64 (objetivo Android)
```

**Nota sobre el `verification_cmd` del plan.** El plan (tarea B1) lo escribe
como `readelf -l <so> | grep -q "align 0x4000"`. Con GNU binutils `readelf`
esa cadena literal **no puede aparecer**: la palabra `align` solo figura en
el encabezado de columna (`… Flags  Align`, con A mayúscula) y los valores
de alineación se imprimen en la línea siguiente de cada segmento, sin la
palabra al lado. Por eso la verificación implementada es **equivalente y más
estricta**: parsea cada segmento `LOAD` y exige `p_align == 0x4000` en
*todos* ellos (no basta con que el valor aparezca una vez en cualquier
lugar del texto), más `file` = ARM aarch64, más presencia del intérprete
`linker64`. TODO el `readelf -l` del binario publicado muestra los tres
segmentos `LOAD … 0x4000`.

Estado del binario publicado:

```
ELF 64-bit LSB pie executable, ARM aarch64, version 1 (SYSV),
dynamically linked, interpreter /system/bin/linker64,
for Android 26, built by NDK r29 (14206865), stripped · 187280 bytes
NEEDED: libc.so, libdl.so   (talloc enlazado estáticamente)
```

## Licencias (cumplimiento de distribución)

- **proot 5.4.0** — GPL-2.0-or-later: es un proceso independiente agregado al
  APK (no una obra derivada del código de la app, que es MIT). Correspondiente
  source disponible: fuentes upstream sin modificar + esta receta completa +
  `tools/*.ld`/`*.h` del repo.
- **talloc 2.4.2** — LGPL-3.0-or-later: se enlaza **estáticamente** a proot.
  Corresponding source disponible en `https://download.samba.org/pub/talloc/`
  (mismo tarball pinneado arriba) sin modificaciones.
- La app en sí mantiene licencia MIT (`LICENSE` del repo).

## Auditoría F4

El árbol del repo (incluido este documento y el `.so`) pasa
`grep -riE "<patrón F4>" --exclude-dir=.git .` sin coincidencias: las fuentes
upstream viven fuera del repo y ni el binario ni los scripts citan proyectos
prohibidos. (El patrón exacto vive solo en el plan maestro, fuera de este
repo, para no violar por citarlo.)
