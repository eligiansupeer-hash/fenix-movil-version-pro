#!/usr/bin/env bash
# ============================================================================
# tools/fetch-rootfs.sh — receta reproducible B2 (fase B del plan)
#
# Descarga y verifica el rootfs Debian 12 Bookworm arm64 (glibc 2.36) que el
# sandbox (B3+) monta vía proot. El rootfs NO se empaqueta en el APK (peso):
# se baja en runtime con SHA-256 fijo, verificado antes de cualquier uso.
#
# Puntos clave (detalle y porqués en docs/rootfs.md):
#  * Fuente upstream pinneada por versión+SHA-256 (LinuxContainers image
#    server, réplica oficial de imágenes LXC/Incus para Debian bookworm).
#  * El tarball se guarda en una caché externa al repo en
#    ${XDG_CACHE_HOME:-~/.cache}/fenix-rootfs (raíz de la caché resuelta
#    igual que en tools/build-proot.sh, con enlace si la ruta tiene espacios).
#  * --verify-only valida el SHA-256 del tarball cacheado contra el valor
#    pinneado en este script, sin red (gate determinista de la tarea B2).
#  * El modo por defecto (sin flags) baja + verifica; --extract además
#    descomprime y confirma la identidad Debian 12 / glibc 2.36 del rootfs
#    ya instalado (chequeo profundo, no requerido por el gate de B2).
#
# Uso:
#   bash tools/fetch-rootfs.sh                 # descargar + verificar SHA-256
#   bash tools/fetch-rootfs.sh --extract       # lo anterior + descomprimir y
#                                              # confirmar Debian 12 / glibc
#   bash tools/fetch-rootfs.sh --verify-only   # solo verifica el .tar.xz ya
#                                              # cacheado (sin red)
#
# Requisitos: red (solo primera vez / re-descarga), curl, xz-utils, sha256sum.
# ============================================================================
set -euo pipefail

# --- Fuente pinneada (integridad obligatoria) --------------------------------
# LinuxContainers image server (images.linuxcontainers.org) publica rootfs
# Debian bookworm arm64 construidos con debootstrap desde deb.debian.org,
# versionados por fecha (YYYYMMDD_HH:MM) con SHA256SUMS firmado por GPG.
# Esta receta pinnea una versión concreta por URL + SHA-256 del tarball en sí
# (no del índice), de modo que cualquier reemplazo/MITM del archivo rompe la
# verificación de hash sin depender de la firma del índice en tiempo de build.
# El host raíz publica detrás un conjunto de réplicas CDN
# (patrón {region}{n}mirror{n}.do.images.linuxcontainers.org) vía redirect
# 302; si el host raíz está degradado o tasa-limited, se prueban en orden las
# réplicas observadas. Cualquier host sirve el mismo artefacto byte a byte
# (mismo SHA-256), por lo que el orden solo afecta disponibilidad, nunca
# integridad.
ROOTFS_VERSION="20261021_06:02"
ROOTFS_REL_PATH="images/debian/bookworm/arm64/default/${ROOTFS_VERSION//:/%3A}/rootfs.tar.xz"
ROOTFS_SHA256="5f626ce3cb8443b846aac19fb822212490098856815b4eb1952cd0d6a55abc23"
ROOTFS_TARBALL_NAME="rootfs-debian12-bookworm-arm64-${ROOTFS_VERSION}.tar.xz"

# Candidatos en orden: host canónico primero, luego réplicas CDN conocidas.
ROOTFS_MIRRORS=(
    "https://images.linuxcontainers.org"
    "https://sfo3mirror01.do.images.linuxcontainers.org"
    "https://fra1mirror01.do.images.linuxcontainers.org"
)

# La caché vive FUERA del repo (el .gitignore ya excluye rootfs y *.tar.xz
# como red de seguridad; esto evita que siquiera residen temporalmente aquí).
CACHE_REAL="${XDG_CACHE_HOME:-${HOME}/.cache}/fenix-rootfs"
mkdir -p "${CACHE_REAL}"
if [[ "${CACHE_REAL}" == *" "* ]]; then
    # Misma defensa que tools/build-proot.sh: rutas con espacios rompen
    # herramientas que parten por palabras; se enlaza a un path sin espacios.
    CACHE="${TMPDIR:-/tmp}/fenix-movil-rootfs-cache"
    ln -sfn "${CACHE_REAL}" "${CACHE}"
    echo "  (nota: ruta con espacios → enlace ${CACHE})"
else
    CACHE="${CACHE_REAL}"
fi

TARBALL="${CACHE}/${ROOTFS_TARBALL_NAME}"
EXTRACT_DIR="${CACHE}/rootfs-debian12-bookworm-arm64"

log()  { printf '\033[1;34m[fetch-rootfs]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[fetch-rootfs] ERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# --- Verificación determinista del tarball cacheado --------------------------
verify_tarball() {
    local tar_path="$1"
    [[ -f "${tar_path}" ]] || fail "no existe ${tar_path} (corra sin --verify-only para descargar)"
    log "verificando ${tar_path}"
    local actual
    actual="$(sha256sum "${tar_path}" | awk '{print $1}')"
    [[ "${actual}" == "${ROOTFS_SHA256}" ]] || fail "SHA-256 incorrecto: esperado ${ROOTFS_SHA256}, obtenido ${actual}"
    log "SHA-256 OK: ${actual}"
}

if [[ "${1:-}" == "--verify-only" ]]; then
    verify_tarball "${TARBALL}"
    exit 0
fi

# --- Descarga con SHA-256 verificado (fallback entre réplicas) ---------------
fetch_verified() {
    local rel="$1" sha="$2" dest="$3"
    if [[ -f "${dest}" ]]; then
        echo "${sha}  ${dest}" | sha256sum -c --status 2>/dev/null && { log "cache OK: $(basename "${dest}")"; return; }
        log "cache inválida, re-descargando: $(basename "${dest}")"
        rm -f "${dest}"
    fi

    local base url ok=0
    for base in "${ROOTFS_MIRRORS[@]}"; do
        url="${base}/${rel}"
        log "descargando ${url}"
        if curl -fsSL --connect-timeout 15 --retry 2 --max-time 600 -o "${dest}" "${url}" \
            && echo "${sha}  ${dest}" | sha256sum -c --status 2>/dev/null; then
            ok=1
            break
        fi
        log "réplica no disponible o hash inesperado: ${base}"
        rm -f "${dest}"
    done
    [[ ${ok} -eq 1 ]] || fail "todas las réplicas fallaron para ${rel} (último hash no coincide o sin red)"
    log "SHA-256 OK: $(basename "${dest}") ($(stat -c%s "${dest}") bytes)"
}

fetch_verified "${ROOTFS_REL_PATH}" "${ROOTFS_SHA256}" "${TARBALL}"
verify_tarball "${TARBALL}"

if [[ "${1:-}" == "--extract" ]]; then
    # --- Extraer (idempotente) y confirmar identidad Debian 12 / glibc 2.36 ---
    if [[ ! -f "${EXTRACT_DIR}/etc/debian_version" ]]; then
        log "extrayendo a ${EXTRACT_DIR}"
        rm -rf "${EXTRACT_DIR}"
        mkdir -p "${EXTRACT_DIR}"
        tar xJf "${TARBALL}" -C "${EXTRACT_DIR}"
    else
        log "ya extraído: ${EXTRACT_DIR}"
    fi

    local_debian_version="$(cat "${EXTRACT_DIR}/etc/debian_version" 2>/dev/null || echo "MISSING")"
    case "${local_debian_version}" in
        12.*) ;;
        *) fail "etc/debian_version no corresponde a Debian 12: ${local_debian_version}" ;;
    esac

    libc6_version="$(awk '/^Package: libc6$/{f=1} f && /^Version:/{print $2; exit}' "${EXTRACT_DIR}/var/lib/dpkg/status" 2>/dev/null || echo "MISSING")"
    case "${libc6_version}" in
        2.36*) ;;
        *) fail "libc6 no corresponde a glibc 2.36: ${libc6_version}" ;;
    esac

    log "VERIFICACIÓN PROFUNDA OK: Debian ${local_debian_version}, libc6 ${libc6_version}"
fi

log "listo: ${TARBALL}"
