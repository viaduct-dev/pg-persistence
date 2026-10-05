import org.postgresql.ds.PGSimpleDataSource
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Drops one real PostgreSQL COMMIT response, after the server committed but before JDBC receives it. */
class CommitResponseProxy(
    target: PGSimpleDataSource,
) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val threads = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val armed = AtomicBoolean()
    val dropped = AtomicInteger()
    val source =
        PGSimpleDataSource().apply {
            setURL(target.getURL())
            serverNames = arrayOf(server.inetAddress.hostAddress)
            portNumbers = intArrayOf(server.localPort)
            user = target.user
            password = target.password
            // The test reads PostgreSQL message boundaries; never intercept encrypted application traffic.
            setSslMode("disable")
            setGssEncMode("disable")
        }

    init {
        threads.submit {
            while (!server.isClosed) {
                try {
                    val client = server.accept().also(sockets::add)
                    val upstream = Socket(target.serverNames.single(), target.portNumbers.single()).also(sockets::add)
                    threads.submit { relay(client, upstream) { client.getInputStream().copyTo(upstream.getOutputStream()) } }
                    threads.submit { relay(client, upstream) { responses(upstream, client) } }
                } catch (closed: IOException) {
                    if (!server.isClosed) throw closed
                }
            }
        }
    }

    fun dropNextCommitResponse() = armed.set(true)

    private fun responses(
        upstream: Socket,
        client: Socket,
    ) {
        val input = DataInputStream(upstream.getInputStream())
        val output = DataOutputStream(client.getOutputStream())
        while (true) {
            val type = input.read()
            if (type == -1) return
            val length = input.readInt()
            require(length in 4..(16 * 1024 * 1024)) { "Invalid PostgreSQL message length" }
            val payload = ByteArray(length - 4)
            input.readFully(payload)
            if (type == 'C'.code && payload.contentEquals("COMMIT\u0000".toByteArray()) && armed.compareAndSet(true, false)) {
                dropped.incrementAndGet()
                return // relay closes both sockets; the driver, not a test stub, reports the I/O failure.
            }
            output.writeByte(type)
            output.writeInt(length)
            output.write(payload)
            output.flush()
        }
    }

    private fun relay(
        client: Socket,
        upstream: Socket,
        copy: () -> Unit,
    ) {
        try {
            copy()
        } catch (_: IOException) {
            // Either side may close, including the deliberate lost response.
        } finally {
            client.close()
            upstream.close()
            sockets.remove(client)
            sockets.remove(upstream)
        }
    }

    override fun close() {
        server.close()
        sockets.forEach(Socket::close)
        threads.shutdownNow()
        check(threads.awaitTermination(10, TimeUnit.SECONDS)) { "Proxy threads did not stop" }
    }
}
