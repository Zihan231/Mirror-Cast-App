package app.mirror.core.signaling

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/** Wire protocol v1. See docs/PROTOCOL.md. */
const val PROTOCOL_VERSION = 1

@Serializable
data class IceServer(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/** Messages this client sends. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ClientMessage {
    val id: String?

    @Serializable
    @SerialName("register-receiver")
    data class RegisterReceiver(
        val deviceName: String,
        val protocolVersion: Int = PROTOCOL_VERSION,
        override val id: String? = null,
    ) : ClientMessage

    @Serializable
    @SerialName("create-pairing")
    data class CreatePairing(override val id: String? = null) : ClientMessage

    @Serializable
    @SerialName("join-pairing")
    data class JoinPairing(
        val code: String,
        val deviceName: String,
        val protocolVersion: Int = PROTOCOL_VERSION,
        override val id: String? = null,
    ) : ClientMessage

    @Serializable
    @SerialName("pairing-decision")
    data class PairingDecision(
        val pairingId: String,
        val approve: Boolean,
        override val id: String? = null,
    ) : ClientMessage

    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String, override val id: String? = null) : ClientMessage

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String, override val id: String? = null) : ClientMessage

    @Serializable
    @SerialName("ice-candidate")
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
        override val id: String? = null,
    ) : ClientMessage

    @Serializable
    @SerialName("get-ice-servers")
    data class GetIceServers(override val id: String? = null) : ClientMessage

    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String? = null, override val id: String? = null) : ClientMessage

    @Serializable
    @SerialName("ping")
    data class Ping(val ts: Double? = null, override val id: String? = null) : ClientMessage
}

/** Messages this client receives. Unknown types from a newer server are dropped, see [SignalingJson.decodeServerMessage]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ServerMessage {

    @Serializable
    @SerialName("registered")
    data class Registered(
        val receiverId: String,
        val serverTime: Long,
        val protocolVersion: Int,
        val id: String? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("pairing-created")
    data class PairingCreated(
        val pairingId: String,
        val code: String,
        /** Epoch millis on the *server* clock; prefer [ttlSeconds] for countdowns. */
        val expiresAt: Long,
        val ttlSeconds: Int,
        val qrPayload: String? = null,
        val id: String? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("pairing-expired")
    data class PairingExpired(val pairingId: String) : ServerMessage

    @Serializable
    @SerialName("pairing-pending")
    data class PairingPending(
        val pairingId: String,
        val approvalTimeoutMs: Long,
        val id: String? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("pairing-request")
    data class PairingRequest(
        val pairingId: String,
        val senderName: String,
        val approvalTimeoutMs: Long,
    ) : ServerMessage

    @Serializable
    @SerialName("pairing-denied")
    data class PairingDenied(val pairingId: String, val reason: String) : ServerMessage

    @Serializable
    @SerialName("paired")
    data class Paired(
        val sessionId: String,
        val role: String,
        val peerName: String,
        val iceServers: List<IceServer> = emptyList(),
    ) : ServerMessage

    @Serializable
    @SerialName("ice-servers")
    data class IceServers(val iceServers: List<IceServer>, val id: String? = null) : ServerMessage

    @Serializable
    @SerialName("offer")
    data class Offer(val sdp: String) : ServerMessage

    @Serializable
    @SerialName("answer")
    data class Answer(val sdp: String) : ServerMessage

    @Serializable
    @SerialName("ice-candidate")
    data class IceCandidate(
        val candidate: String,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
    ) : ServerMessage

    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String) : ServerMessage

    @Serializable
    @SerialName("pong")
    data class Pong(val serverTime: Long, val ts: Double? = null, val id: String? = null) : ServerMessage

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
        val retryAfterMs: Long? = null,
        val id: String? = null,
    ) : ServerMessage
}

object ErrorCodes {
    const val INVALID_MESSAGE = "invalid-message"
    const val WRONG_STATE = "wrong-state"
    const val INVALID_CODE = "invalid-code"
    const val RATE_LIMITED = "rate-limited"
    const val BUSY = "busy"
    const val RECEIVER_UNAVAILABLE = "receiver-unavailable"
    const val NOT_FOUND = "not-found"
    const val FORBIDDEN = "forbidden"
}

object DenyReasons {
    const val REJECTED = "rejected"
    const val TIMEOUT = "timeout"
    const val RECEIVER_GONE = "receiver-gone"
}

object SignalingJson {
    val json: Json = Json {
        ignoreUnknownKeys = true // forward compatible with additive server changes
        encodeDefaults = true // protocolVersion must be sent even though it has a default
        explicitNulls = false // null optionals (id, sdpMid, ...) are omitted on the wire
    }

    fun encode(message: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), message)

    /** Returns null for unknown message types or malformed payloads instead of throwing. */
    fun decodeServerMessage(text: String): ServerMessage? =
        try {
            json.decodeFromString(ServerMessage.serializer(), text)
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
}
