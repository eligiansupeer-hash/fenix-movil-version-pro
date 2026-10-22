// ============================================================================
// SandboxConfig.kt — configuración del montaje proot (plan B3)
// ============================================================================

package com.fenix.movil.sandbox

import java.io.File

/**
 * Configuración inmutable del sandbox: binario proot, rootfs, montajes,
 * cwd, entornos (host y guest) y temporizaciones.
 *
 * [buildCommand] produce el argv completo de proot y [guestEnvironment] el
 * entorno que se aplica **dentro** del guest mediante `env -i`, de modo que
 * ninguna variable heredada del proceso anfitrión se filtra al sandbox.
 *
 * Todas las rutas de guest (`guestCwd`, `guestHome`, `guestCommand`) son
 * rutas **del rootfs**, no del host.
 */
data class SandboxConfig(
    /** Binario proot (en Android: `nativeLibraryDir/libproot.so`). */
    val prootBinary: File,
    /** Rootfs Debian 12 descomprimido (en Android: `noBackupFilesDir/sandbox/debian-12`). */
    val rootfsDir: File,
    /** Montajes host → guest (`-b`). */
    val binds: List<BindMount> = defaultBinds(),
    /** Directorio de trabajo inicial dentro del guest (`-w`). */
    val guestCwd: String = DEFAULT_GUEST_CWD,
    /** `$HOME` dentro del guest. */
    val guestHome: String = DEFAULT_GUEST_HOME,
    /** Programa a ejecutar dentro del guest (al final del argv). */
    val guestCommand: List<String> = listOf(DEFAULT_SHELL, "--login"),
    /** Variables adicionales/alternativas del entorno guest (pisan las base). */
    val guestEnv: Map<String, String> = emptyMap(),
    /**
     * Entorno del proceso proot en el host. Se usa **en reemplazo total**
     * del entorno heredado: nada del entorno de la app se transmitirá.
     */
    val hostEnv: Map<String, String> = defaultHostEnv(),
    /** `$TERM` del guest (B4 lo re-dimensiona con la PTY). */
    val term: String = DEFAULT_TERM,
    /** DNS del dispositivo a volcar en `<rootfs>/etc/resolv.conf` (vacío = no tocar). */
    val dnsServers: List<String> = emptyList(),
    /** `-0`: el usuario del guest *aparece* como root (sin privilegios reales). */
    val fakeRoot: Boolean = true,
    /** `--link2symlink`: los hardlinks se materializan como symlinks (storage sin hardlinks). */
    val link2Symlink: Boolean = true,
    /** `--kill-on-exit`: al morir el comando principal mueren sus huérfanos. */
    val killOnExit: Boolean = true,
    /** `-v`: nivel de verbosidad de proot (0 = solo avisos; negativo = solo errores). */
    val verboseLevel: Int = 0,
    /** Ventana (ms) en la que una salida temprana del proceso se considera fallo de arranque. */
    val bootGraceMs: Long = 5_000,
    /** Intervalo (ms) de sondeo del proceso. Debe ser > 0. */
    val pollIntervalMs: Long = 50,
    /** Espera (ms) de SIGTERM antes de pasar a SIGKILL en la parada. */
    val stopGraceMs: Long = 2_000,
) {
    /**
     * Devuelve `null` si la configuración es arrancable, o en otro caso el
     * motivo textual del rechazo. Se evalúa **antes** de lanzar cualquier
     * proceso, de modo que un error de configuración ni siquiera intenta
     * arrancar proot.
     */
    fun validationError(): String? {
        if (!rootfsDir.isDirectory) {
            return "rootfs no encontrado en ${rootfsDir.path} " +
                "(ejecutar tools/fetch-rootfs.sh --extract)"
        }
        if (!File(rootfsDir, "etc/debian_version").isFile) {
            return "rootfs inválido en ${rootfsDir.path}: falta etc/debian_version"
        }
        if (!prootBinary.isFile) {
            return "binario de proot ausente: ${prootBinary.path}"
        }
        if (!prootBinary.canExecute()) {
            return "binario de proot no ejecutable: ${prootBinary.path}"
        }
        if (!guestCwd.startsWith("/")) {
            return "cwd del guest debe ser absoluto: '$guestCwd'"
        }
        if (guestCommand.isEmpty()) {
            return "guestCommand vacío: no hay programa que ejecutar en el guest"
        }
        if (pollIntervalMs <= 0) {
            return "pollIntervalMs debe ser > 0 (valor: $pollIntervalMs)"
        }
        if (bootGraceMs < 0 || stopGraceMs < 0) {
            return "las temporizaciones no pueden ser negativas " +
                "(bootGraceMs=$bootGraceMs, stopGraceMs=$stopGraceMs)"
        }
        return null
    }

    /**
     * argv completo de proot. Orden garantizado: opciones de proot →
     * `env -i KEY=VALUE…` → comando guest.
     */
    fun buildCommand(): List<String> {
        val command = mutableListOf(
            prootBinary.absolutePath,
            "-r",
            rootfsDir.absolutePath,
        )
        binds.forEach { bind -> command += listOf("-b", bind.toArgument()) }
        command += listOf("-w", guestCwd)
        if (fakeRoot) command += "-0"
        if (link2Symlink) command += "--link2symlink"
        if (killOnExit) command += "--kill-on-exit"
        command += listOf("-v", verboseLevel.toString())
        command += listOf("/usr/bin/env", "-i")
        guestEnvironment().forEach { (key, value) -> command += "$key=$value" }
        command += guestCommand
        return command
    }

    /**
     * Entorno guest aplicado con `env -i` (aislado): claves base + [guestEnv],
     * en orden estable para que el argv sea determinista.
     */
    fun guestEnvironment(): Map<String, String> {
        val environment = linkedMapOf(
            "HOME" to guestHome,
            "LOGNAME" to ROOT_USER,
            "USER" to ROOT_USER,
            "SHELL" to DEFAULT_SHELL,
            "TERM" to term,
            "LANG" to UTF8_LANG,
            "TMPDIR" to GUEST_TMPDIR,
            "PATH" to DEFAULT_GUEST_PATH,
        )
        environment.putAll(guestEnv)
        return environment
    }

    /** Entorno del proceso proot en el host (reemplaza al heredado). */
    fun hostEnvironment(): Map<String, String> = hostEnv.toMap()

    /** Archivo DNS dentro del rootfs que administra este sandbox. */
    fun resolvConfFile(): File = File(rootfsDir, RESOLV_CONF_RELATIVE)

    /**
     * Contenido para `etc/resolv.conf`, o `null` si no hay DNS que volcar
     * (para no pisar el archivo que trae el rootfs).
     */
    fun resolvConfContent(): String? {
        val servers = dnsServers.map { it.trim() }.filter { it.isNotEmpty() }
        if (servers.isEmpty()) return null
        return buildString {
            append("# generado por fenix-movil\n")
            servers.forEach { server ->
                append("nameserver ")
                append(server)
                append('\n')
            }
        }
    }

    companion object {
        const val DEFAULT_GUEST_CWD = "/root"
        const val DEFAULT_GUEST_HOME = "/root"
        const val DEFAULT_SHELL = "/bin/bash"
        const val DEFAULT_TERM = "xterm-256color"
        const val UTF8_LANG = "C.UTF-8"
        const val GUEST_TMPDIR = "/tmp"
        const val ROOT_USER = "root"
        const val DEFAULT_GUEST_PATH =
            "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        const val RESOLV_CONF_RELATIVE = "etc/resolv.conf"

        /**
         * Montajes mínimos del host que todo programa Debian necesita:
         * dispositivos (`/dev`, incluye `ptmx` para la PTY de B4), interfaces
         * del kernel (`/proc`, `/sys`). No se monta storage externo: el
         * sandbox no debe ejecutar ni leer contenido fuera del sandbox (SEG).
         */
        fun defaultBinds(extra: List<BindMount> = emptyList()): List<BindMount> =
            listOf(
                BindMount("/dev"),
                BindMount("/proc"),
                BindMount("/sys"),
            ) + extra

        /** Entorno host mínimo: rutas públicas de Android + idioma. */
        fun defaultHostEnv(): Map<String, String> = linkedMapOf(
            "PATH" to "/system/bin:/system/xbin:/vendor/bin",
            "LANG" to UTF8_LANG,
            "ANDROID_ROOT" to "/system",
            "ANDROID_DATA" to "/data",
        )
    }
}
