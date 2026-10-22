// ============================================================================
// TestStreams.kt — dobles de streams para la verificación de B4
//
// Dos utilidades compartidas por los tests de `terminal/` y del puente:
// - [FeedableInputStream]: el test alimenta la "salida" del proceso a mano,
//   con EOF explícito y cómputo de bytes consumidos (`taken`) para poder
//   garantizar que dos porciones llegaron en dos `read()` distintos (los
//   cortes reales entre lecturas) en lugar de confiar en tiempos.
// - [RecordingOutputStream]: stdin de la sesión grabado, con cierre que
//   falla las escrituras posteriores como haría una tubería real.
// - [waitUntil]: espera activa de los resultados que llegan desde los
//   hilos de lectura reales de la sesión (SharedFlow sin replay).
// ============================================================================

package com.fenix.movil.terminal

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** InputStream que el test alimenta a mano y cierra con [endOfStream]. */
internal class FeedableInputStream : InputStream() {
    private val pending = ByteArrayOutputStream()
    // monitor java.lang.Object: Kotlin Any no expone wait/notify.
    private val lock = java.lang.Object()
    private var eof = false

    /** Bytes devueltos hasta ahora por [read] (para forzar cortes reales). */
    @Volatile
    var taken: Int = 0
        private set

    fun feed(bytes: ByteArray) {
        synchronized(lock) {
            pending.write(bytes)
            lock.notifyAll()
        }
    }

    fun feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    /** Simula el fin del stream (el proceso cerró su extremo). */
    fun endOfStream() {
        synchronized(lock) {
            eof = true
            lock.notifyAll()
        }
    }

    override fun read(): Int {
        val single = ByteArray(1)
        return if (read(single, 0, 1) == -1) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        synchronized(lock) {
            while (true) {
                if (pending.size() > 0) {
                    val available = pending.toByteArray()
                    pending.reset()
                    val count = minOf(len, available.size)
                    System.arraycopy(available, 0, b, off, count)
                    if (count < available.size) {
                        pending.write(available, count, available.size - count)
                    }
                    taken += count
                    return count
                }
                if (eof) return -1
                lock.wait()
            }
        }
    }
}

/** OutputStream que graba lo que la sesión escribe en stdin. */
internal class RecordingOutputStream : OutputStream() {
    private val buffer = ByteArrayOutputStream()

    @Volatile
    private var closedFlag = false

    val isClosed: Boolean
        get() = closedFlag

    @Throws(IOException::class)
    override fun write(b: Int) {
        ensureOpen()
        synchronized(buffer) { buffer.write(b) }
    }

    @Throws(IOException::class)
    override fun write(b: ByteArray, off: Int, len: Int) {
        ensureOpen()
        synchronized(buffer) { buffer.write(b, off, len) }
    }

    override fun close() {
        closedFlag = true
    }

    fun text(): String = synchronized(buffer) { buffer.toString(Charsets.UTF_8) }

    private fun ensureOpen() {
        if (closedFlag) throw IOException("stream cerrado")
    }
}

/**
 * Espera activa (sondeo cada [POLL_MS]) hasta que [condition] se cumpla;
 * lanza [AssertionError] con [what] si no ocurre en [timeoutMs]. Las
 * salidas de una sesión real vienen de hilos de lectura, así que las
 * pruebas no pueden asumir orden respecto al hilo de test.
 */
internal fun waitUntil(
    timeoutMs: Long = 5_000,
    what: String = "condición",
    condition: () -> Boolean,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    while (System.nanoTime() < deadline) {
        if (condition()) return
        Thread.sleep(POLL_MS)
    }
    if (!condition()) {
        throw AssertionError("no se cumplió '$what' en $timeoutMs ms")
    }
}

private const val POLL_MS = 10L
