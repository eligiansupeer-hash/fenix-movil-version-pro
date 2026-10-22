// ============================================================================
// PtyHelper.kt — helper de sesión PTY dentro del guest (plan B4)
// ============================================================================

package com.fenix.movil.sandbox

import java.io.File

/**
 * Helper POSIX que materializa la PTY **dentro** del sandbox.
 *
 * Diseño (ver `docs/shell-session.md` para el porqué completo): en el
 * alcance de B4 no entra código nativo, así que la PTY se crea con las
 * herramientas del rootfs Debian 12 ya pinneado en B2: `script(1)`
 * (util-linux) abre el pty y ejecuta la sesión interna colgada de él, y
 * `stty(1)` aplica la ventana (`TIOCSWINSZ`, que el kernel convierte en
 * `SIGWINCH` para el grupo en primer plano). El host sólo ve tres streams
 * de tubería y un canal de control fuera de banda.
 *
 * Protocolo de archivos en el directorio compartido (host y guest ven los
 * mismos bytes por el bind mount):
 *
 * | Archivo | Rol |
 * |---|---|
 * | `fenix-pty.sh` | el propio helper (rol fuera / rol `--inside`) |
 * | `.fenix-pty-ctrl` | FIFO de control: `"filas columnas"` aplica ventana; `QUIT` detiene al oyente |
 * | `.fenix-pty-size` | acuse: última ventana aplicada, `"filas columnas"` |
 *
 * El rol `--inside` corre **colgado de la PTY** (su fd 0 es el esclavo):
 * fija la ventana inicial, lanza el oyente del canal y `exec` del shell.
 * Detalle crítico resuelto: POSIX obliga a redirigir a `/dev/null` el
 * stdin de las listas asíncronas cuando no hay job control, así que el
 * oyente (subshell en background) **no** hereda el tty; por eso se captura
 * la ruta del pts con `tty` antes del fork y el oyente usa `stty -F`.
 */
object PtyHelper {
    /** Nombre del helper dentro del directorio compartido. */
    const val SCRIPT_NAME = "fenix-pty.sh"

    /** FIFO de control (host → guest). */
    const val CTRL_NAME = ".fenix-pty-ctrl"

    /** Archivo de acuse de ventana aplicada (guest → host). */
    const val SIZE_NAME = ".fenix-pty-size"

    /** Comando del shell dentro de la PTY (por defecto, el del config). */
    const val PTY_SHELL = "/bin/sh"

    fun ctrlFile(shareDir: File): File = File(shareDir, CTRL_NAME)

    fun sizeFile(shareDir: File): File = File(shareDir, SIZE_NAME)

    /**
     * Escribe el helper en [shareDir] y limpia el canal de una sesión
     * anterior (un ctrl/size viejo haría fallar los acuses). Devuelve el
     * archivo escrito.
     */
    fun install(shareDir: File): File {
        require(shareDir.isDirectory || shareDir.mkdirs()) {
            "no se pudo crear el directorio compartido ${shareDir.path}"
        }
        ctrlFile(shareDir).delete()
        sizeFile(shareDir).delete()
        val script = File(shareDir, SCRIPT_NAME)
        script.writeText(scriptContent(), Charsets.UTF_8)
        script.setExecutable(true)
        return script
    }

    /**
     * Contenido exacto del helper (determinista: la misma entrada produce
     * el mismo byte, requisito de toda receta de este repo).
     *
     * El template usa `@@` en lugar de `$`: Kotlin interpretaría `$var`
     * como template de cadena. [install] lo sustituye; el test de
     * integración de B4 ejecuta el resultado real, que es lo que prueba
     * que la traducción es correcta.
     */
    fun scriptContent(): String = SCRIPT_TEMPLATE.replace("@@", "\u0024")

    // `@@` = `$` (ver scriptContent). Las líneas clave:
    //   rol --inside:  tty→stty inicial→oyente del FIFO→exec del shell
    //   rol fuera:     mkfifo→script(1)→QUIT al oyente→limpieza
    @Suppress("MaxLineLength")
    private val SCRIPT_TEMPLATE: String = """
        #!/bin/sh
        # fenix-pty.sh — sesion bajo PTY real + canal de redimension (B4)
        # Escrito por PtyHelper.install() en el directorio compartido.
        set -u

        share="@@{FENIX_SHARE:-/fenix}"
        ctrl="@@share/.fenix-pty-ctrl"
        size_file="@@share/.fenix-pty-size"
        rows="@@{FENIX_ROWS:-24}"
        cols="@@{FENIX_COLS:-80}"
        shell_cmd="@@{FENIX_SHELL:-/bin/bash --login}"

        if [ "@@{1:-}" = "--inside" ]; then
            # rol 2: dentro de la PTY (fd 0 = esclavo del pty)
            tty_path=@@(tty) || exit 71
            stty rows "@@rows" cols "@@cols" 2>/dev/null || exit 71
            printf '%s %s\n' "@@rows" "@@cols" > "@@size_file" || exit 72
            (
                # el subshell en background recibe stdin=/dev/null (POSIX),
                # por eso el oyente usa la ruta del pts con stty -F
                while :; do
                    [ -e "@@ctrl" ] || exit 0
                    line=""
                    if ! IFS= read -r line < "@@ctrl"; then
                        sleep 0.1
                        continue
                    fi
                    case "@@line" in QUIT) exit 0 ;; esac
                    set -- @@line
                    [ "@@#" -eq 2 ] || continue
                    case "@@1@@2" in *[!0-9]*) continue ;; esac
                    if stty -F "@@tty_path" rows "@@1" cols "@@2" 2>/dev/null; then
                        printf '%s %s\n' "@@1" "@@2" > "@@size_file"
                    fi
                done
            ) &
            exec /bin/sh -c "@@shell_cmd"
        fi

        command -v script >/dev/null 2>&1 || {
            echo "fenix-pty: falta script(1)" >&2
            exit 74
        }
        mkdir -p "@@share" || exit 70
        rm -f "@@size_file"
        mkfifo "@@ctrl" || exit 73
        self="@@0"
        case "@@self" in
            /*) ;;
            *) self="@@(pwd)/@@self" ;;
        esac
        quit_listener() {
            timeout 1 sh -c 'printf "QUIT\n" > "$1"' _ "@@ctrl" 2>/dev/null || true
        }
        # script(1) corre en background para poder atender SIGTERM mientras
        # vive, pero sin job control la shell redirige el stdin de las listas
        # asíncronas a /dev/null: script vería EOF al instante y cerraría la
        # pty (matando la sesion). Se salva el stdin real en fd 8 y se lo
        # pasa explicito a script, que lo reenvia a la pty como teclado.
        exec 8<&0
        script -e -q -f -c "exec /bin/sh \"@@self\" --inside" /dev/null <&8 &
        script_pid=@@!
        # SIGTERM al rol externo debe morir tambien a script(1) (con su
        # pty cerrada muere el shell interior), avisar al oyente del canal
        # y limpiar los archivos: si no, el hijo quedaria huerfano
        # reteniendo los pipes de la sesion. script(1) forwarda TERM al
        # grupo de la sesion, pero el shell interior es interactivo y lo
        # IGNORA: por eso tras un TERM breve se envia KILL directo a
        # script(1); el kernel cierra el master y el slave recibe SIGHUP.
        trap 'kill "@@script_pid" 2>/dev/null; sleep 0.2; kill -9 "@@script_pid" 2>/dev/null; wait "@@script_pid" 2>/dev/null; quit_listener; rm -f "@@ctrl" "@@size_file"; exit 143' TERM INT HUP
        wait "@@script_pid"
        status=@@?
        quit_listener
        rm -f "@@ctrl" "@@size_file"
        exit @@status
    """.trimIndent()
}
