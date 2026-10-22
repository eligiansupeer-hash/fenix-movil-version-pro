# ShellSession — sesión PTY real (plan B4)

Cómo la app obtiene una terminal interactiva **dentro** del sandbox (plan
`PLAN_fenix-movil-version-pro.md`, fase B4). Este documento es la referencia
del diseño que los KDoc de `PtyHelper.kt` y `BridgePtyTransport.kt`
resumen; los porqués viven aquí.

## El problema

`ShellSession` (B4) necesita un stream crudo `stdin`→`stdout` con la
semántica de una tty real: el shell interior debe ver un terminal (para
prompt, edición de línea, `stty`, colores, `SIGWINCH`), y el host debe ver
tuberías simples. En bionic no hay `openpty()` portable (el libc de Android
no expone el par master/slave como glibc), y la consigna de B4 prohíbe
código nativo nuevo.

## La solución: PTY materializada en el rootfs

El sandbox de B2 ya trae un Debian 12 completo, que incluye
`script(1)` (util-linux) y `stty(1)`. Con eso alcanza:

1. **`script -e -q -f -c "…" /dev/null`** abre una PTY real (el kernel
   crea master/slave) y ejecuta el comando pedido colgado del esclavo. El
   host ve solo los tres streams del proceso: `stdin` del proceso se
   reenvía al master (teclado), `stdout`/`stderr` salen del master
   (pantalla). `-f` hace flush tras cada write (stream en vivo, no
   bufferizado al cierre).
2. **`stty rows R cols C`** sobre el esclavo aplica la ventana
   (`TIOCSWINSZ`), que el kernel convierte en `SIGWINCH` para el grupo en
   primer plano. Sin acceso directo al fd del esclavo en el host se usa
   `stty -F <ruta-pts>` desde el propio guest.

El host (JVM/Kotlin) nunca ve un fd de PTY: ve un `Process` de tuberías
más un canal de control por FIFO en el directorio compartido (`/fenix`,
mismo bind que B2/B3).

## Arquitectura de procesos

```
host (app)
 └─ ProcessBuilder → proot -r <rootfs> … -b <share>:/fenix /bin/sh /fenix/fenix-pty.sh
     └─ rol "fuera" (fenix-pty.sh): mkfifo + script(1) + trap + limpieza
         └─ script(1)  ← master de la PTY
             └─ rol "--inside" (fenix-pty.sh): stty inicial + oyente FIFO
                 └─ exec /bin/sh -c "$FENIX_SHELL"   ← sesión interactiva
```

### El helper tiene dos roles

Un único `fenix-pty.sh` (instalado por `PtyHelper.install()` en el
directorio compartido) cumple los dos papeles; el rol `--inside` se activa
con ese argumento:

- **Rol `--inside`** (corre *colgado de la PTY*: su fd 0 es el esclavo):
  fija la ventana inicial con `stty`, escribe el acuse inicial
  (`.fenix-pty-size`), lanza el oyente del canal de control en background y
  `exec` del shell pedido (`FENIX_SHELL`).
- **Rol fuera** (el que lanza proot): valida `script(1)`, crea el FIFO de
  control, lanza `script(1)` con el rol `--inside` en background, instala
  el `trap` de limpieza y espera.

## Protocolo de archivos (directorio compartido `/fenix`)

| Archivo | Dirección | Formato | Rol |
|---|---|---|---|
| `fenix-pty.sh` | instala el host | — | el propio helper |
| `.fenix-pty-ctrl` | host → guest | FIFO: `"filas columnas"` aplica ventana; `QUIT` detiene al oyente | control |
| `.fenix-pty-size` | guest → host | `"filas columnas"` (última ventana aplicada) | acuse |

### Redimensión con acuse (no a ciegas)

1. el host escribe `"filas columnas"` en el FIFO;
2. el oyente del guest aplica `stty -F <pts> rows R cols C`;
3. el guest sobrescribe el archivo de acuse con la geometría aplicada;
4. `BridgePtyTransport.resize` espera ese acuse: `true` = ventana aplicada
   en el tty, `false` = sin acuse en el plazo (la llamada es
   reintentable, contrato de `PtyTransport.resize`).

El oyente valida la entrada (`2` campos, solo dígitos) antes de aplicar:
una línea corrupta no debe tumbar la sesión.

### Cierre limpio

`BridgePtyTransport.close(terminate = true)` escribe `QUIT` al FIFO y
envía `SIGTERM` al proceso. El `trap` del rol fuera mata a `script(1)`
(`TERM` + `KILL` breve, ver gotcha 4), espera, avisa al oyente y borra el
FIFO y el acuse. El flujo es idempotente y sin archivos huérfanos.

## Flujo del host (`BridgePtyTransport`)

- `open(config, shareDir, …)` — valida config, instala el helper, arma la
  variante PTY (`ptyVariant`: `guestCommand` pasa a ser el helper y el
  comando original viaja en `FENIX_SHELL`), lanza proot y espera el acuse
  de ventana inicial. Sin ese acuse la sesión no es usable y se destruye
  con `IOException`.
- `attach(process, shareDir, …)` — une un proceso ya lanzado (o un doble)
  y espera el mismo acuse. Es lo que usa el test de integración de B4
  para ejecutar el helper real sin proot.
- `resize(size)` — protocolo de acuse (arriba).
- `close(terminate)` — `QUIT` + terminación (o cierre de stdin si el
  cierre lo originó el proceso).

`PtyTransport` (el puerto de `ShellSession`) queda implementado con
`isTty = true`: `ShellSession` reenvía `\r` como terminador de línea en
lugar de `\n`, que es lo que un tty real espera.

## Gotchas resueltos (el porqué de cada línea rara del template)

1. **`@@` en lugar de `$`:** el template vive en un triple-quoted string
   de Kotlin; `$var` se interpretaría como template. `scriptContent()`
   sustituye `@@` → `$` en runtime. El test de integración ejecuta el
   resultado real, lo que prueba que la traducción es correcta.
2. **stdin de listas asíncronas (POSIX):** sin job control, la shell
   redirige el stdin de los comandos en background a `/dev/null`. El
   oyente del FIFO (subshell en background) **no** hereda el tty, por eso
   captura la ruta del pts con `tty` antes del fork y usa `stty -F`. Y
   `script(1)`, que también corre en background, vería EOF al instante y
   cerraría la pty: se salva el stdin real en un fd (`exec 8<&0`) y se lo
   pasa explícito (`<&8`), que script reenvía a la pty como teclado.
3. **El shell interior ignora `SIGTERM`:** una sesión interactiva (el
   `exec /bin/sh -c "$FENIX_SHELL"` colgado del esclavo) tiene el
   job control y el shell interactivo **ignora** SIGTERM (y SIGINT para
   el grupo en background). `script(1)` forwarda la señal al grupo, pero
   eso no basta: el `trap` del rol fuera envía `TERM` y, pasado un breve
   respiro, `KILL` directo a `script(1)`. El kernel cierra el master y el
   slave recibe `SIGHUP`, que sí mata la sesión.
4. **`QUIT` con tope:** abrir un FIFO en escritura bloquea hasta que el
   oyente lo abra en lectura. Si el guest ya murió, ese open quedaría
   colgado para siempre: el host escribe el canal desde un hilo daemon y
   descarta la escritura si no completa en el plazo (`writeControl`).
5. **El acuse inicial evita la condición de carrera:** `open()`/`attach()`
   no devuelven hasta ver `.fenix-pty-size` con la geometría esperada. El
   host no puede distinguir "la sesión arrancó" de "la sesión va a morir"
   sin ese handshake.

## Tests

- **Unidad:** `PtyHelperTest` (contenido determinista del template,
  instalación, canales), `BridgePtyTransportTest` (variante PTY, argv de
  proot, protocolo de acuse con dobles de proceso).
- **Integración real (en el gate):** `la PTY real ejecuta comandos
  redimensiona con acuse y cierra limpio` — instala el helper con
  `install()`, lanza el proceso con `ProcessBuilder` (entorno mínimo
  reemplazado), `attach()`, ejecuta `echo "res-$((6*7))"` (el resultado
  aritmético prueba ejecución, no eco), redimensiona a `30×100` y verifica
  con `stty size`, y cierra limpio (proceso terminado + archivos de canal
  borrados). Usa `Assume` sobre `/dev/ptmx` para no romper el build en
  entornos sin PTY.

## Fuera de alcance (no es B4)

- Múltiples sesiones simultáneas (`ShellSessionFactory`): tarea propia.
- Selección de shell por el usuario (hoy `FENIX_SHELL` = config).
- `SIGWINCH` al *app* (el host no recibe señales; la UI consulta
  `session.size`).
