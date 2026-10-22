# Rootfs Debian 12 Bookworm arm64 (B2)

Receta **reproducible y verificable** para obtener el sistema de archivos base
(glibc 2.36) que el sandbox Linux propio (tarea **B3** `SandboxManager`, y
sucesoras) monta con `proot` compilado en **B1**
(`docs/build-proot.md`).

El rootfs **no se empaqueta en el APK**: pesa ~90 MB comprimido (~350 MB
descomprimido), lo que inflaría el APK más allá de todo límite razonable de
distribución (ver «Riesgos registrados» en el plan maestro). Se descarga en
runtime (o en build-time de desarrollo) con **SHA-256 fijo** y se verifica
*antes* de cualquier uso.

## Prerrequisitos

| Requisito | Detalle |
|---|---|
| `curl` | descarga con reconnect/timeout acotados |
| `xz-utils` (`tar xJf`) | solo para `--extract` |
| `sha256sum` | verificación de integridad (coreutils) |
| Red | solo la primera ejecución; la caché posterior es offline |

## Ejecutar

```bash
bash tools/fetch-rootfs.sh                 # descargar + verificar SHA-256
bash tools/fetch-rootfs.sh --extract       # lo anterior + descomprimir y
                                           # confirmar Debian 12 / glibc 2.36
bash tools/fetch-rootfs.sh --verify-only   # gate de B2: solo verifica el
                                           # .tar.xz ya cacheado (sin red)
```

`--verify-only` sale con **exit 0** solo si el tarball existe en la caché y su
SHA-256 coincide exactamente con el valor pinneado en el script. Sale con
exit 1 si falta el archivo o el hash difiere (verificado: ambos caminos se
probaron).

## Fuente (pinneada por versión + SHA-256)

| Campo | Valor |
|---|---|
| Proveedor | LinuxContainers image server (`images.linuxcontainers.org`), réplicas oficiales de imágenes LXC/Incus |
| Construcción | `debootstrap` desde `deb.debian.org/debian/` (fuente primaria Debian, sin terceros) |
| Versión | `20261021_06:02` (snapshot fechado, URL versionada — no mutable «latest») |
| Rel path | `images/debian/bookworm/arm64/default/20261021_06:02/rootfs.tar.xz` |
| SHA-256 | `5f626ce3cb8443b846aac19fb822212490098856815b4eb1952cd0d6a55abc23` |
| Tamaño | 94984164 bytes (~90.6 MiB) |

- Se pinnea el **SHA-256 del tarball en sí** (no del índice `SHA256SUMS`), de
  modo que la verificación no depende de resolver/trustear una clave GPG en el
  momento del fetch: cualquier reemplazo, MITM o bit-rot del archivo aborta la
  verificación antes de que el sandbox llegue a montarlo.
- El servidor publica además `SHA256SUMS.asc` (firma PGP del índice) como vía
  de **cruce independiente** para auditoría humana opcional; no es requerido
  por el gate de B2.
- El snapshot está fechado y versionado en la URL: re-ejecutar la receta en el
  futuro reproduce exactamente el mismo rootfs (byte a byte), hasta que se
  pinnee explícitamente una versión más nueva.

### Fallback entre réplicas (disponibilidad, no integridad)

El host raíz `images.linuxcontainers.org` publica detrás un conjunto de
réplicas CDN (patrón `{region}{n}mirror{n}.do.images.linuxcontainers.org`) vía
redirect `302`. Si el host raíz está degradado o tasa-limited (se observó en
la práctica durante el desarrollo de esta tarea), el script prueba en orden
una lista de candidatos hasta que uno sirva el artefacto **con el hash
esperado**:

1. `https://images.linuxcontainers.org` (canónico)
2. `https://sfo3mirror01.do.images.linuxcontainers.org` (réplica observada)
3. `https://fra1mirror01.do.images.linuxcontainers.org` (réplica observada)

Cualquier host sirve el mismo archivo byte a byte (mismo SHA-256), por lo que
el orden solo afecta **disponibilidad**, nunca **integridad**: un host
comprometido que sirva contenido alterado falla el chequeo de hash y el script
sigue con el siguiente candidato, sin degradar la garantía de integridad.

## Caché (fuera del repo, por diseño)

```
${XDG_CACHE_HOME:-~/.cache}/fenix-rootfs/
├── rootfs-debian12-bookworm-arm64-20261021_06:02.tar.xz   # bajado, verificado
└── rootfs-debian12-bookworm-arm64/                         # extraído (--extract)
    ├── etc/debian_version          # "12.15"
    └── var/lib/dpkg/status         # libc6 2.36-9+deb12u14
```

Mismo patrón que `tools/build-proot.sh`: la caché **nunca** reside dentro del
árbol del repo (el `.gitignore` ya excluye `rootfs-cache/`, `*.tar.xz` y
`app/src/main/assets/rootfs/` como red de seguridad adicional). Si la ruta de
la caché contiene espacios, se enlaza a un path sin espacios (defensa idéntica
a la de B1, ver gotchas de `docs/build-proot.md`).

## Receta (lo que hace el script)

1. **Cache-first** — si el tarball ya existe y su SHA-256 coincide, no hay
   red: retorno inmediato.
2. **Descarga con verificación** — si falta o el hash no coincide, se limpia y
   se reintenta contra cada réplica de la lista (`--connect-timeout 15`,
   `--retry 2`, `--max-time 600`) hasta que uno complete la descarga *y*
   pase `sha256sum -c`.
3. **Verificación determinista** (`verify_tarball`) — misma función sirve al
   modo por defecto y al gate `--verify-only`: calcula el SHA-256 real y lo
   compara contra el valor pinneado, sin ambigüedad (exit 0 / exit 1).
4. **Extracción opcional + identidad profunda** (`--extract`) — descomprime de
   forma idempotente (si el árbol ya existe, no repite `tar`) y confirma:
   - `etc/debian_version` coincide con `12.*` (Debian 12 / Bookworm), y
   - `var/lib/dpkg/status` reporta `libc6` con versión `2.36*` (glibc 2.36,
     el target fijado en `arquitectura.md`).

   Este chequeo profundo **no** forma parte del gate de B2 (que es solo
   «hash OK», sin red ni extracción); existe para que B3 y posteriores puedan
   confiar en que el árbol descomprimido ya instalado corresponde a la versión
   declarada, sin re-validarlo en cada arranque.

## Verificación (gate de la tarea B2)

```bash
# Equivalente exacto que ejecuta el plan (exit 0 = OK):
bash tools/fetch-rootfs.sh --verify-only
```

Salida observada:

```
[fetch-rootfs] verificando /home/eli/.cache/fenix-rootfs/rootfs-debian12-bookworm-arm64-20261021_06:02.tar.xz
[fetch-rootfs] SHA-256 OK: 5f626ce3cb8443b846aac19fb822212490098856815b4eb1952cd0d6a55abc23
```

Casos negativos verificados en desarrollo (no vacuosamente en verde):

| Caso | Resultado |
|---|---|
| tarball ausente de la caché | exit 1 (`no existe …`) |
| tarball presente pero hash corrupto | exit 1 (`SHA-256 incorrecto …`) |
| tarball presente e íntegro | exit 0 |

## Por qué runtime y no empaquetado

| Enfoque | Costo | Decisión |
|---|---|---|
| Embebido en `app/src/main/assets/` | +~90 MB al APK comprimido, +~350 MB en disco tras instalación, duplicado por cada actualización | descartado |
| **Descarga en runtime con SHA-256** | 0 en el APK; se paga una sola vez al primer arranque (o ya cacheado en desarrollo) | **adoptado** |

Esto materializa el riesgo «Rootfs Debian 12 > tamaño APK» registrado en el
plan maestro: su mitigación es literalmente «Descarga en runtime con SHA-256
(B2), no embebido».

## Licencias (cumplimiento de distribución)

- **El rootfs no se redistribuye dentro del APK** (solo se descarga en runtime
  desde la fuente oficial). La app mantiene licencia MIT (`LICENSE` del repo)
  sin arrastrar las licencias de los paquetes Debian incluidos en el rootfs.
- El contenido del rootfs en sí está compuesto por paquetes Debian DFSG-free
  (GPL, LGPL, BSD, MIT, etc. — ver `copyright` de cada paquete dentro del árbol
  extraído). Queda documentado que cualquier redistribución *derivada* del
  rootfs (no es el caso de este repo) exigiría respetar esas licencias.
- `proot` (B1, GPL-2.0-or-later) y `talloc` (B1, LGPL-3.0-or-later) se
  distribuyen como se documenta en `docs/build-proot.md`.

## Auditoría F4

El árbol del repo (incluido este documento y `tools/fetch-rootfs.sh`) pasa
`grep -riE "<patrón F4>" --exclude-dir=.git .` sin coincidencias: ni el
script, ni la documentación, ni la URL pinneada citan proyectos prohibidos; la
fuente es el servidor público de imágenes Debian/LXC, ajena a cualquier
referencia externa restringida. (El patrón exacto vive solo en el plan
maestro, fuera de este repo, para no violar por citarlo.)
