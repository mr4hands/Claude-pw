package dev.wristcontrol.wear.data.net

import android.util.Log
import dev.wristcontrol.wear.data.model.ClientCommand
import dev.wristcontrol.wear.data.model.ConnectionState
import dev.wristcontrol.wear.data.model.SessionEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.math.min
import kotlin.random.Random

/**
 * Keeps one WSS attachment alive across the radio going up and down.
 *
 * A watch loses its connection constantly — screen off, wrist down, wifi/BT
 * handover — so reconnection is the normal case rather than the error case.
 * Each reconnect re-requests a snapshot so the UI and the tile recover the
 * current status and any approval that landed while we were dark.
 */
class WebSocketSessionStream(
    private val httpClient: OkHttpClient,
    private val sessionId: String,
    private val requestFactory: (token: String) -> Request,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    parentScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : SessionStream {

    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob() + Dispatchers.IO)

    private val _messages = MutableSharedFlow<StreamMessage>(
        replay = 0,
        extraBufferCapacity = 128,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val messages: Flow<StreamMessage> = _messages.asSharedFlow()

    @Volatile
    private var socket: WebSocket? = null
    private val closed = AtomicBoolean(false)

    init {
        scope.launch { runReconnectLoop() }
    }

    private suspend fun runReconnectLoop() {
        var attempt = 0
        // Counts consecutive 401/403s. A rejected token is worth exactly one
        // silent refresh; past that the user genuinely has to sign in again,
        // and retrying forever would just burn the battery in a loop.
        var authRejections = 0

        while (scope.isActive && !closed.get()) {
            emit(StreamMessage.Connection(ConnectionState.Connecting))

            val token = tokenProvider(authRejections > 0)
            if (token == null) {
                emit(StreamMessage.Connection(ConnectionState.Failed("Signed out")))
                return
            }

            val outcome = connectOnce(token)
            if (closed.get() || !scope.isActive) return

            when (outcome) {
                Outcome.AUTH_REJECTED -> {
                    authRejections++
                    if (authRejections > MAX_AUTH_RETRIES) {
                        emit(StreamMessage.Connection(ConnectionState.Failed("Sign-in expired")))
                        return
                    }
                    continue
                }

                Outcome.ENDED_BY_SERVER -> {
                    emit(StreamMessage.Connection(ConnectionState.Disconnected))
                    return
                }

                Outcome.DROPPED -> authRejections = 0
            }

            attempt++
            val backoff = backoffMillis(attempt)
            emit(StreamMessage.Connection(ConnectionState.Reconnecting(attempt, backoff)))
            delay(backoff)
        }
    }

    private enum class Outcome { DROPPED, AUTH_REJECTED, ENDED_BY_SERVER }

    private suspend fun connectOnce(token: String): Outcome = suspendCancellableCoroutine { continuation ->
        var settled = false
        fun settle(outcome: Outcome) {
            if (settled) return
            settled = true
            socket = null
            if (continuation.isActive) continuation.resume(outcome)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socket = webSocket
                emit(StreamMessage.Connection(ConnectionState.Connected))
                // Ask for current status + any approval raised while we were away.
                webSocket.send(EventCodec.encodeCommand(ClientCommand.RequestSnapshot))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                EventCodec.decodeEvent(text, clock)?.let { emit(StreamMessage.Event(it)) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                settle(if (code == NORMAL_CLOSURE) Outcome.ENDED_BY_SERVER else Outcome.DROPPED)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.d(TAG, "Socket failure for $sessionId: ${t.message}")
                if (response?.code == 401 || response?.code == 403) {
                    settle(Outcome.AUTH_REJECTED)
                } else {
                    emit(StreamMessage.Event(SessionEvent.Failure(t.message ?: "Connection lost")))
                    settle(Outcome.DROPPED)
                }
            }
        }

        val webSocket = httpClient.newWebSocket(requestFactory(token), listener)
        continuation.invokeOnCancellation { webSocket.cancel() }
    }

    override suspend fun send(command: ClientCommand): Boolean {
        val live = socket ?: return false
        return live.send(EventCodec.encodeCommand(command))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket?.close(NORMAL_CLOSURE, "client closed")
        socket = null
        scope.cancel()
    }

    private fun emit(message: StreamMessage) {
        _messages.tryEmit(message)
    }

    private fun backoffMillis(attempt: Int): Long {
        val exponential = min(MAX_BACKOFF_MS, BASE_BACKOFF_MS shl (attempt - 1).coerceIn(0, 5))
        // Jitter keeps a fleet of watches from stampeding the relay after an outage.
        return exponential / 2 + Random.nextLong(exponential / 2 + 1)
    }

    private companion object {
        const val TAG = "SessionStream"
        const val NORMAL_CLOSURE = 1000
        const val MAX_AUTH_RETRIES = 1
        const val BASE_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 30_000L
    }
}
