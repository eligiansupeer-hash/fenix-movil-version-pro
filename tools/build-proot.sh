#!/usr/bin/env bash
# ============================================================================
# tools/build-proot.sh — receta reproducible B1 (fase B del plan)
#
# Compila proot + libtalloc propios con el NDK r29 para arm64-v8a y publica el
# resultado como biblioteca nativa empaquetada:
#   app/src/main/jniLibs/arm64-v8a/libproot.so
#
# Puntos clave de la receta (detalle y porqués en docs/build-proot.md):
#  * Fuentes upstream pinneadas por SHA-256; se bajan a una caché externa al
#    repo en ${XDG_CACHE_HOME:-~/.cache}/fenix-proot-build.
#  * talloc se compila como librería estática contra bionic con el shim
#    tools/android-bionic-config.h (sin waf: build cross puro).
#  * proot se construye con el GNUmakefile upstream, sin modificar fuentes.
#  * El loader interno de proot se enlaza con tools/loader-ld-script.ld porque
#    LLD, con -Ttext fijo alto, produciría un ELF esparcido de ~128 GB.
#  * El binario final se strippea con llvm-strip y se verifica:
#      - ELF aarch64 (file)
#      - todos los segmentos LOAD con alineación 0x4000 (16 KB), verificado
#        con readelf (ver docs/build-proot.md, nota de verificación).
#
# Uso:
#   bash tools/build-proot.sh                 # build completo
#   bash tools/build-proot.sh --verify-only   # solo verifica el .so publicado
#
# Requisitos: red (primera vez), curl, Android NDK r29 en ANDROID_NDK_HOME o
# en $HOME/Android/Sdk/ndk/*, binutils host (readelf/file).
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
JNI_OUT="${REPO_ROOT}/app/src/main/jniLibs/arm64-v8a/libproot.so"

# La caché de build vive FUERA del repo (${XDG_CACHE_HOME:-~/.cache}):
# el árbol de fuentes upstream descomprimido contiene documentación de terceros
# que no pasa la barrera de limpieza F4 del plan, y no debe residir en el repo.
# Si la ruta contiene espacios (make parte por palabras la salida de
# $(shell pkg-config)), se enlaza a un path sin espacios.
CACHE_REAL="${XDG_CACHE_HOME:-${HOME}/.cache}/fenix-proot-build"
mkdir -p "${CACHE_REAL}"
if [[ "${CACHE_REAL}" == *" "* ]]; then
    CACHE="${TMPDIR:-/tmp}/fenix-movil-proot-cache"
    ln -sfn "${CACHE_REAL}" "${CACHE}"
    echo "  (nota: ruta con espacios → enlace ${CACHE})"
else
    CACHE="${CACHE_REAL}"
fi

# --- Fuentes pinneadas (integridad obligatoria) -----------------------------
PROOT_VERSION="5.4.0"
PROOT_URL="https://github.com/proot-me/proot/archive/refs/tags/v${PROOT_VERSION}.tar.gz"
PROOT_SHA256="29248aac2a7ce10c3bd5ee5602742ec33b2532310ff9cf73b79f3c133e5a5f68"

TALLOC_VERSION="2.4.2"
TALLOC_URL="https://download.samba.org/pub/talloc/talloc-${TALLOC_VERSION}.tar.gz"
TALLOC_SHA256="85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"

MIN_API=26
TARGET_TRIPLE="aarch64-linux-android${MIN_API}"
LOADER_LD="${SCRIPT_DIR}/loader-ld-script.ld"
BIONIC_CONFIG="${SCRIPT_DIR}/android-bionic-config.h"

log()  { printf '\033[1;34m[build-proot]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[build-proot] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- Verificación determinista del binario publicado ------------------------
verify_binary() {
    local so="$1"
    [[ -f "$so" ]] || fail "no existe ${so}"
    log "verificando ${so}"

    # 1) Arquitectura ELF
    local file_out
    file_out="$(LANG=C file -b "${so}")"
    echo "${file_out}" | grep -q "ELF 64-bit"  || fail "no es ELF 64-bit: ${file_out}"
    echo "${file_out}" | grep -q "aarch64"     || fail "no es aarch64: ${file_out}"

    # 2) Alineación 16 KB (0x4000) en TODOS los segmentos LOAD.
    #    readelf imprime la alineación como último campo de cada LOAD; la
    #    palabra "align" solo aparece en el encabezado de columna, por lo que
    #    se valida el valor hexadecimal directamente (nota en docs/build-proot.md).
    local loads bad=0
    loads="$(LANG=C readelf -lW "${so}" | awk '$1=="LOAD"')"
    [[ -n "${loads}" ]] || fail "readelf no reporta segmentos LOAD"
    while IFS= read -r line; do
        local align="${line##* }"
        [[ "${align}" == "0x4000" ]] || { log "LOAD con alineación ${align}: ${line}"; bad=1; }
    done <<< "${loads}"
    [[ ${bad} -eq 0 ]] || fail "hay segmentos LOAD sin alineación 0x4000 (16 KB)"
    # Comprobación literal adicional (grep del plan, sin -q para dejar rastro):
    if ! LANG=C readelf -l "${so}" | grep -q "0x4000"; then
        fail "readelf no muestra 0x4000"
    fi

    # 3) Intérprete/objetivo Android correctos
    LANG=C readelf -l "${so}" | grep -q "linker64" || fail "sin intérprete de Android (linker64)"

    log "VERIFICACIÓN OK: $(echo "${file_out}" | cut -c1-72), LOAD=0x4000"
}

if [[ "${1:-}" == "--verify-only" ]]; then
    verify_binary "${JNI_OUT}"
    exit 0
fi

# --- Toolchain ---------------------------------------------------------------
detect_ndk() {
    if [[ -n "${ANDROID_NDK_HOME:-}" && -d "${ANDROID_NDK_HOME}" ]]; then
        echo "${ANDROID_NDK_HOME}"; return
    fi
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-${HOME}/Android/Sdk}}"
    local best=""
    if [[ -d "${sdk}/ndk" ]]; then
        best="$(ls -1 "${sdk}/ndk" | sort -V | tail -1)"
    fi
    [[ -n "${best}" ]] || fail "NDK no encontrado (instalar: sdkmanager \"ndk;29.0.14206865\")"
    echo "${sdk}/ndk/${best}"
}

NDK="$(detect_ndk)"
TC="${NDK}/toolchains/llvm/prebuilt/linux-x86_64"
[[ -d "${TC}" ]] || fail "toolchain LLVM ausente en ${TC}"
CLANG="${TC}/bin/${TARGET_TRIPLE}-clang"
[[ -x "${CLANG}" ]] || fail "clang de target ausente: ${CLANG}"

log "NDK: ${NDK}"
case "${NDK}" in
    *"/ndk/29."*) ;;
    *) log "AVISO: la receta está validada con NDK r29; el detectado difiere" ;;
esac

mkdir -p "${CACHE}/src" "${CACHE}/prefix/include" "${CACHE}/prefix/lib" "${CACHE}/fakebin"

# --- Descarga con SHA-256 verificado -----------------------------------------
fetch_verified() {
    local url="$1" sha="$2" dest="$3"
    if [[ -f "${dest}" ]]; then
        echo "${sha}  ${dest}" | sha256sum -c --status 2>/dev/null && { log "cache OK: $(basename "${dest}")"; return; }
        log "cache inválida, re-descargando: $(basename "${dest}")"
        rm -f "${dest}"
    fi
    log "descargando ${url}"
    curl -fsSL --retry 3 -o "${dest}" "${url}"
    echo "${sha}  ${dest}" | sha256sum -c --status || fail "SHA-256 incorrecto en ${url}"
    log "SHA-256 OK: $(basename "${dest}")"
}

fetch_verified "${PROOT_URL}"  "${PROOT_SHA256}"  "${CACHE}/src/proot-${PROOT_VERSION}.tar.gz"
fetch_verified "${TALLOC_URL}" "${TALLOC_SHA256}" "${CACHE}/src/talloc-${TALLOC_VERSION}.tar.gz"

# --- Extraer (idempotente) ---------------------------------------------------
if [[ ! -d "${CACHE}/src/proot-${PROOT_VERSION}/src" ]]; then
    tar xzf "${CACHE}/src/proot-${PROOT_VERSION}.tar.gz" -C "${CACHE}/src"
fi
if [[ ! -f "${CACHE}/src/talloc-${TALLOC_VERSION}/talloc.c" ]]; then
    tar xzf "${CACHE}/src/talloc-${TALLOC_VERSION}.tar.gz" -C "${CACHE}/src"
fi
PROOT_SRC="${CACHE}/src/proot-${PROOT_VERSION}/src"
TALLOC_SRC="${CACHE}/src/talloc-${TALLOC_VERSION}"

# --- 1) talloc estático para bionic ------------------------------------------
log "compilando talloc ${TALLOC_VERSION} (estático, ${TARGET_TRIPLE})"
TALLOC_MINOR="${TALLOC_VERSION#*.}"; TALLOC_MINOR="${TALLOC_MINOR%%.*}"
TALLOC_PATCH="${TALLOC_VERSION##*.}"
cp "${BIONIC_CONFIG}" "${CACHE}/prefix/include/config.h"
rm -f "${CACHE}/prefix/talloc.o" "${CACHE}/prefix/lib/libtalloc.a"
"${CLANG}" -c "${TALLOC_SRC}/talloc.c" -o "${CACHE}/prefix/talloc.o" \
    -O2 -fPIC -fvisibility=hidden \
    -D__STDC_WANT_LIB_EXT1__=1 \
    -I"${CACHE}/prefix/include" -I"${TALLOC_SRC}/lib/replace" \
    -DTALLOC_BUILD_VERSION_MAJOR=2 \
    -DTALLOC_BUILD_VERSION_MINOR="${TALLOC_MINOR}" \
    -DTALLOC_BUILD_VERSION_RELEASE="${TALLOC_PATCH}" \
    -Wall -Wextra -Wno-unused-parameter
"${TC}/bin/llvm-ar" rcs "${CACHE}/prefix/lib/libtalloc.a" "${CACHE}/prefix/talloc.o"
cp "${TALLOC_SRC}/talloc.h" "${CACHE}/prefix/include/talloc.h"

# --- 2) pkg-config stub (solo resuelve talloc para el makefile upstream) -----
cat > "${CACHE}/fakebin/pkg-config" <<'STUB'
#!/bin/sh
# Stub pkg-config de build: resuelve únicamente talloc contra el prefijo local.
for arg in "$@"; do
  case "$arg" in
    --cflags)  printf -- '-I%s\n' "${TALLOC_PREFIX}/include"; exit 0 ;;
    --libs)    printf -- '-L%s -ltalloc\n' "${TALLOC_PREFIX}/lib"; exit 0 ;;
    --version) echo "0.29.2-fenix-stub"; exit 0 ;;
    *) exit 1 ;;
  esac
done
STUB
chmod +x "${CACHE}/fakebin/pkg-config"

# --- 3) proot con el GNUmakefile upstream (sin parches) ----------------------
log "compilando proot ${PROOT_VERSION} (${TARGET_TRIPLE})"
# El makefile invoca el linker con la ruta del script .ld dentro de $(shell)/reglas:
# se copia a la caché (path sin espacios) por el mismo motivo del enlace de CACHE.
LOADER_LD_CACHE="${CACHE}/loader-ld-script.ld"
cp "${LOADER_LD}" "${LOADER_LD_CACHE}"
BUILD="${CACHE}/build-proot"
rm -rf "${BUILD}" && mkdir -p "${BUILD}"
cp -a "${PROOT_SRC}/." "${BUILD}/"

(
    cd "${BUILD}"
    export PATH="${TC}/bin:${CACHE}/fakebin:${PATH}"
    export TALLOC_PREFIX="${CACHE}/prefix"
    export CFLAGS="-O2 -fPIC"
    export LDFLAGS="-Wl,-z,max-page-size=16384"
    make -f GNUmakefile proot \
        VERSION="${PROOT_VERSION}-fenix" \
        CC="${CLANG}" \
        LD="${CLANG}" \
        STRIP=true \
        OBJCOPY=llvm-objcopy \
        OBJDUMP=llvm-objdump \
        HAS_SWIG= \
        HAS_PYTHON_CONFIG= \
        LOADER_LDFLAGS="-static -nostdlib -Wl,--build-id=none -Wl,-T,${LOADER_LD_CACHE} -Wl,-z,noexecstack" \
        -j"$(nproc)"
)

PROOT_BIN="${BUILD}/proot"
[[ -f "${PROOT_BIN}" ]] || fail "make no produjo el binario proot"

# --- 4) Publicar como jniLibs (strip final con llvm-strip) ------------------
log "stripping + publicando en jniLibs/arm64-v8a"
"${TC}/bin/llvm-strip" "${PROOT_BIN}"
mkdir -p "$(dirname "${JNI_OUT}")"
cp "${PROOT_BIN}" "${JNI_OUT}"

# --- 5) Verificación (misma que --verify-only) -------------------------------
verify_binary "${JNI_OUT}"
log "listo: ${JNI_OUT} ($(stat -c%s "${JNI_OUT}") bytes)"
