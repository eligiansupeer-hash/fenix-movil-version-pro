// ============================================================================
// BridgePtyTransportTest.kt — puente host ↔ PTY del guest (plan B4)
//
// Dos niveles:
// - unidad con dobles de proceso: argv/entorno de la variante PTY,
//   protocolo de acuse (true/false/cerrado/muerto), close idempotente,
//   fallos de lanzamiento y validaciones;
// - integración con el helper REAL vía script(1) del host (Assume sobre
//   los binarios): ejecución de comandos, redimensión con stty aplicado
//   al tty y cierre limpio del canal.
// ============================================================================

package com.fenix.movil.sandbox

import com.fenix.movil.terminal.FeedableInputStream
import com.fenix.movil.terminal.RecordingOutputStream
import com.fenix.movil.terminal.ShellSession
import com.fenix.movil.terminal.ShellSessionState
import com.fenix.movil.terminal.TerminalSize
import com.fenix.movil.terminal.waitUntil
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BridgePtyTransportTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var rootfs: File
    private lateinit var proot: File
    private lateinit var share: File

    @Before
    fun setUp() {
        rootfs = tmp.newFolder("rootfs")
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/debian_version").writeText("12.15\n")
        proot = tmp.newFile("libproot.so")
        proot.setExecutable(true)
        share = tmp.newFolder("share")
    }

    private fun config(
        transform: SandboxConfig.() -> SandboxConfig = { this },
    ): SandboxConfig = SandboxConfig(
        prootBinary = proot,
        rootfsDir = rootfs,
    ).transform()

    // ------------------------------------------------------------------
    // Dobles
    // ------------------------------------------------------------------

    private class FakePtyProcess : PtyProcess {
        val input = RecordingOutputStream()
        val stdoutFeeder = FeedableInputStream()
        val stderrFeeder = FeedableInputStream()
        val graces = CopyOnWriteArrayList<Long>()

        @Volatile
        var alive = true

        override val stdin: OutputStream
            get() = input

        override val stdout: InputStream = stdoutFeeder

        override val stderr: InputStream = stderrFeeder

        override fun isAlive(): Boolean = alive

        override fun destroy(graceMillis: Long) {
            graces.add(graceMillis)
            alive = false
            stdoutFeeder.endOfStream()
            stderrFeeder.endOfStream()
        }
    }

    /** Lanzador capturado; puede simular acuse o fallo de arranque. */
    private class FakePtyLauncher(
        private val onLaunch: () -> Unit = {},
        private val failWith: IOException? = null,
    ) : PtyProcessLauncher {
        var command: List<String>? = null
            private set
        var environment: Map<String, String>? = null
            private set
        var workingDir: File? = null
            private set
        val process = FakePtyProcess()

        override fun launch(
            command: List<String>,
            environment: Map<String, String>,
            workingDir: File,
        ): PtyProcess {
            this.command = command
            this.environment = environment
            this.workingDir = workingDir
            failWith?.let { throw it }
            onLaunch()
            return process
        }
    }

    /** Lanzador que escribe el acuse inicial como lo haría el guest. */
    private fun ackingLauncher(protocol: String) = FakePtyLauncher(
        onLaunch = { PtyHelper.sizeFile(share).writeText("$protocol\n", Charsets.UTF_8) },
    )

    // ------------------------------------------------------------------
    // Variante PTY del config (diseño del protocolo)
    // ------------------------------------------------------------------

    @Test
    fun `ptyVariant monta el helper y mueve el shell a FENIX_SHELL`() {
        val original = config()
        val variant = BridgePtyTransport.ptyVariant(original, TerminalSize(40, 120))
        assertThat(variant.guestCommand)
            .containsExactly("/bin/sh", "/fenix/${PtyHelper.SCRIPT_NAME}")
            .inOrder()
        assertThat(variant.guestEnv["FENIX_SHARE"]).isEqualTo("/fenix")
        assertThat(variant.guestEnv["FENIX_ROWS"]).isEqualTo("40")
        assertThat(variant.guestEnv["FENIX_COLS"]).isEqualTo("120")
        assertThat(variant.guestEnv["FENIX_SHELL"]).isEqualTo("/bin/bash --login")
        // ptyVariant no toca binds ni entorno host: eso es de ptyConfig.
        assertThat(variant.binds).isEqualTo(original.binds)
        assertThat(variant.hostEnv).isEqualTo(original.hostEnv)
    }

    @Test
    fun `ptyConfig agrega el bind del share en fenix reemplazando uno previo`() {
        val original = config().copy(
            binds = SandboxConfig.defaultBinds(extra = listOf(BindMount("/tmp/viejo", "/fenix"))),
        )
        val pty = BridgePtyTransport.ptyConfig(original, share, TerminalSize.DEFAULT)
        val shareBinds = pty.binds.filter { it.guestPath == BridgePtyTransport.GUEST_SHARE }
        assertThat(shareBinds).containsExactly(BindMount(share.absolutePath, "/fenix"))
        // 3 del default + 1 del share (el previo se reemplazó, no se duplica).
        assertThat(pty.binds).hasSize(4)
    }

    @Test
    fun `shellJoin deja los tokens seguros y cita el resto`() {
        assertThat(
            BridgePtyTransport.shellJoin(listOf("/bin/bash", "--login")),
        ).isEqualTo("/bin/bash --login")
        assertThat(
            BridgePtyTransport.shellJoin(listOf("/bin/sh", "-c", "echo hola")),
        ).isEqualTo("/bin/sh -c 'echo hola'")
        assertThat(BridgePtyTransport.shellJoin(listOf("it's"))).isEqualTo("'it'\\''s'")
    }

    // ------------------------------------------------------------------
    // open()
    // ------------------------------------------------------------------

    @Test
    fun `open instala el helper y arma el argv de la sesion PTY`() {
        val launcher = ackingLauncher("24 80")
        val transport = BridgePtyTransport.open(config(), share, launcher)
        val command = requireNotNull(launcher.command)
        // El helper manda al final del argv de proot, montado en /fenix.
        assertThat(command.subList(command.size - 2, command.size))
            .containsExactly("/bin/sh", "/fenix/${PtyHelper.SCRIPT_NAME}")
            .inOrder()
        // buildCommand emite "-b" y la especificación como argv separados.
        assertThat(command).contains("-b")
        assertThat(command).contains("${share.absolutePath}:/fenix")
        assertThat(command).contains("FENIX_ROWS=24")
        assertThat(command).contains("FENIX_COLS=80")
        assertThat(command).contains("FENIX_SHARE=/fenix")
        assertThat(command).contains("FENIX_SHELL=/bin/bash --login")
        assertThat(launcher.environment).isEqualTo(config().hostEnvironment())
        assertThat(launcher.workingDir).isEqualTo(rootfs)
        assertThat(share.resolve(PtyHelper.SCRIPT_NAME).isFile).isTrue()
        assertThat(transport.isTty).isTrue()
        transport.close(true)
    }

    @Test
    fun `open con config invalida no instala ni lanza nada`() {
        val broken = config().copy(rootfsDir = tmp.newFolder("vacio"))
        val launcher = FakePtyLauncher()
        val error = assertThrows(IllegalArgumentException::class.java) {
            BridgePtyTransport.open(broken, share, launcher)
        }
        assertThat(error).hasMessageThat().contains("config del sandbox inválida")
        assertThat(launcher.command).isNull()
        assertThat(share.resolve(PtyHelper.SCRIPT_NAME).exists()).isFalse()
    }

    @Test
    fun `open rechaza un directorio compartido relativo`() {
        val launcher = FakePtyLauncher()
        assertThrows(IllegalArgumentException::class.java) {
            BridgePtyTransport.open(config(), File("share"), launcher)
        }
        assertThat(launcher.command).isNull()
    }

    @Test
    fun `open envuelve el fallo del launcher en IOException`() {
        val launcher = FakePtyLauncher(failWith = IOException("proot no arranca"))
        val error = assertThrows(IOException::class.java) {
            BridgePtyTransport.open(config(), share, launcher)
        }
        assertThat(error).hasMessageThat().contains("no se pudo lanzar la sesión PTY")
        assertThat(error.cause).isNotNull()
        assertThat(error.cause).hasMessageThat().isEqualTo("proot no arranca")
    }

    @Test
    fun `open sin acuse destruye la sesion y falla`() {
        val launcher = FakePtyLauncher() // el "guest" nunca acusa
        val error = assertThrows(IOException::class.java) {
            BridgePtyTransport.open(config(), share, launcher, ackTimeoutMs = 100)
        }
        assertThat(error).hasMessageThat().contains("no confirmó la ventana inicial")
        // La sesión fallida se destruye con terminate.
        assertThat(launcher.process.graces).containsExactly(config().stopGraceMs)
        assertThat(launcher.process.alive).isFalse()
    }

    // ------------------------------------------------------------------
    // attach() y resize()
    // ------------------------------------------------------------------

    @Test
    fun `attach rechaza un directorio compartido relativo`() {
        assertThrows(IllegalArgumentException::class.java) {
            BridgePtyTransport.attach(FakePtyProcess(), File("share"))
        }
    }

    @Test
    fun `attach sin acuse destruye la sesion y falla`() {
        val process = FakePtyProcess()
        val error = assertThrows(IOException::class.java) {
            BridgePtyTransport.attach(process, share, ackTimeoutMs = 100)
        }
        assertThat(error).hasMessageThat().contains("no confirmó la ventana inicial")
        assertThat(process.graces).hasSize(1)
        assertThat(process.alive).isFalse()
    }

    @Test
    fun `resize devuelve true cuando el acuse refleja la ventana`() {
        val launcher = ackingLauncher("24 80")
        val transport = BridgePtyTransport.open(config(), share, launcher, ackTimeoutMs = 500)
        // El "guest" acusa la ventana pedida.
        PtyHelper.sizeFile(share).writeText("30 100\n", Charsets.UTF_8)
        assertThat(transport.resize(TerminalSize(30, 100))).isTrue()
        transport.close(true)
    }

    @Test
    fun `resize devuelve false sin acuse en el plazo y no destruye nada`() {
        val launcher = ackingLauncher("24 80")
        val transport = BridgePtyTransport.open(config(), share, launcher, ackTimeoutMs = 200)
        assertThat(transport.resize(TerminalSize(40, 120))).isFalse()
        assertThat(launcher.process.graces).isEmpty()
        assertThat(launcher.process.alive).isTrue()
        transport.close(true)
    }

    @Test
    fun `resize devuelve false si el proceso ya murio`() {
        val launcher = ackingLauncher("24 80")
        val transport = BridgePtyTransport.open(config(), share, launcher)
        launcher.process.alive = false
        assertThat(transport.resize(TerminalSize(30, 100))).isFalse()
    }

    @Test
    fun `resize devuelve false tras cerrar la sesion`() {
        val launcher = ackingLauncher("24 80")
        val transport = BridgePtyTransport.open(config(), share, launcher)
        transport.close(true)
        assertThat(transport.resize(TerminalSize(30, 100))).isFalse()
    }

    // ------------------------------------------------------------------
    // close()
    // ------------------------------------------------------------------

    @Test
    fun `close con terminate destruye al proceso una sola vez`() {
        val launcher = ackingLauncher("24 80")
        val transport = BridgePtyTransport.open(config(), share, launcher)
        transport.close(true)
        transport.close(true)
        transport.close(true)
        assertThat(launcher.process.graces).containsExactly(config().stopGraceMs)
        assertThat(launcher.process.alive).isFalse()
    }

    @Test
    fun `close sin terminate solo cierra stdin`() {
        val launcher = ackingLauncher("24 80")
        val transport = BridgePtyTransport.open(config(), share, launcher)
        transport.close(terminate = false)
        assertThat(launcher.process.input.isClosed).isTrue()
        assertThat(launcher.process.graces).isEmpty()
        assertThat(launcher.process.alive).isTrue()
        assertThrows(IOException::class.java) { transport.stdin.write(0x61) }
    }

    // ------------------------------------------------------------------
    // Integración: el helper real con script(1) del host
    // ------------------------------------------------------------------

    @Test
    fun `la PTY real ejecuta comandos redimensiona con acuse y cierra limpio`() {
        Assume.assumeTrue("/bin/sh debe existir", File("/bin/sh").canExecute())
        Assume.assumeTrue("script(1) debe existir", File("/usr/bin/script").canExecute())
        Assume.assumeTrue("mkfifo debe existir", File("/usr/bin/mkfifo").canExecute())
        Assume.assumeTrue("stty debe existir", File("/usr/bin/stty").canExecute())
        Assume.assumeTrue("timeout debe existir", File("/usr/bin/timeout").canExecute())
        Assume.assumeTrue("/dev/ptmx debe existir", File("/dev/ptmx").exists())

        val script = PtyHelper.install(share)
        val builder = ProcessBuilder("/bin/sh", script.absolutePath)
            .directory(share)
            .redirectErrorStream(true)
        val env = builder.environment()
        env.clear()
        env["PATH"] = "/usr/bin:/bin"
        env["FENIX_SHARE"] = share.absolutePath
        env["FENIX_ROWS"] = "24"
        env["FENIX_COLS"] = "80"
        env["FENIX_SHELL"] = "/bin/sh"
        val process = builder.start()
        try {
            val transport = BridgePtyTransport.attach(
                process = ProcessPtyProcess(process),
                shareDir = share,
                initialSize = TerminalSize.DEFAULT,
                ackTimeoutMs = 15_000,
                stopGraceMs = 3_000,
            )
            assertThat(transport.isTty).isTrue()

            val session = ShellSession(transport)
            session.open()
            // El resultado del aritmético (res-42) no aparece en el eco del
            // comando: su presencia prueba que el shell lo EJECUTÓ.
            session.sendLine("""echo "res-$((6*7))"""")
            waitUntil(timeoutMs = 10_000, what = "ejecucion del comando") {
                session.plainText().contains("res-42")
            }

            // Redimensión real: acuse del oyente + TIOCSWINSZ en el tty.
            assertThat(session.resize(30, 100)).isTrue()
            assertThat(session.size.value).isEqualTo(TerminalSize(30, 100))
            session.sendLine("stty size")
            waitUntil(timeoutMs = 10_000, what = "ventana aplicada en el tty") {
                session.plainText().contains("30 100")
            }

            session.close()
            assertThat(session.state.value).isEqualTo(ShellSessionState.CLOSED)
            waitUntil(timeoutMs = 10_000, what = "proceso terminado") { !process.isAlive }
            waitUntil(timeoutMs = 5_000, what = "canal limpio") {
                !PtyHelper.ctrlFile(share).exists() && !PtyHelper.sizeFile(share).exists()
            }
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }
}
