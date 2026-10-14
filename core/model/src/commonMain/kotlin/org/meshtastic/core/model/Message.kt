/*
 * Copyright (c) 2026 Meshtastic LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.meshtastic.core.model

import okio.Path.Companion.toPath
import org.jetbrains.compose.resources.StringResource
import org.meshtastic.core.resources.Res
import org.meshtastic.core.resources.error
import org.meshtastic.core.resources.message_delivery_status
import org.meshtastic.core.resources.message_routing_error_admin_bad_session_key
import org.meshtastic.core.resources.message_routing_error_admin_bad_session_key_detail
import org.meshtastic.core.resources.message_routing_error_admin_public_key_unauthorized
import org.meshtastic.core.resources.message_routing_error_admin_public_key_unauthorized_detail
import org.meshtastic.core.resources.message_routing_error_bad_request
import org.meshtastic.core.resources.message_routing_error_bad_request_detail
import org.meshtastic.core.resources.message_routing_error_duty_cycle_limit
import org.meshtastic.core.resources.message_routing_error_duty_cycle_limit_detail
import org.meshtastic.core.resources.message_routing_error_got_nak_detail
import org.meshtastic.core.resources.message_routing_error_max_retransmit
import org.meshtastic.core.resources.message_routing_error_max_retransmit_detail
import org.meshtastic.core.resources.message_routing_error_no_channel
import org.meshtastic.core.resources.message_routing_error_no_channel_detail
import org.meshtastic.core.resources.message_routing_error_no_interface
import org.meshtastic.core.resources.message_routing_error_no_interface_detail
import org.meshtastic.core.resources.message_routing_error_no_response
import org.meshtastic.core.resources.message_routing_error_no_response_detail
import org.meshtastic.core.resources.message_routing_error_no_route_detail
import org.meshtastic.core.resources.message_routing_error_not_authorized
import org.meshtastic.core.resources.message_routing_error_not_authorized_detail
import org.meshtastic.core.resources.message_routing_error_pki_failed
import org.meshtastic.core.resources.message_routing_error_pki_failed_detail
import org.meshtastic.core.resources.message_routing_error_pki_send_fail_public_key
import org.meshtastic.core.resources.message_routing_error_pki_send_fail_public_key_detail
import org.meshtastic.core.resources.message_routing_error_pki_unknown_pubkey
import org.meshtastic.core.resources.message_routing_error_pki_unknown_pubkey_detail
import org.meshtastic.core.resources.message_routing_error_rate_limit_exceeded
import org.meshtastic.core.resources.message_routing_error_rate_limit_exceeded_detail
import org.meshtastic.core.resources.message_routing_error_timeout_detail
import org.meshtastic.core.resources.message_routing_error_too_large
import org.meshtastic.core.resources.message_routing_error_too_large_detail
import org.meshtastic.core.resources.message_status_delivered
import org.meshtastic.core.resources.message_status_enroute
import org.meshtastic.core.resources.message_status_recipient_delivered
import org.meshtastic.core.resources.message_status_relayed_not_confirmed
import org.meshtastic.core.resources.message_status_sfpp_confirmed
import org.meshtastic.core.resources.message_status_sfpp_routing
import org.meshtastic.core.resources.message_status_unknown
import org.meshtastic.core.resources.routing_error_admin_bad_session_key
import org.meshtastic.core.resources.routing_error_admin_public_key_unauthorized
import org.meshtastic.core.resources.routing_error_bad_request
import org.meshtastic.core.resources.routing_error_duty_cycle_limit
import org.meshtastic.core.resources.routing_error_got_nak
import org.meshtastic.core.resources.routing_error_max_retransmit
import org.meshtastic.core.resources.routing_error_no_channel
import org.meshtastic.core.resources.routing_error_no_interface
import org.meshtastic.core.resources.routing_error_no_response
import org.meshtastic.core.resources.routing_error_no_route
import org.meshtastic.core.resources.routing_error_none
import org.meshtastic.core.resources.routing_error_not_authorized
import org.meshtastic.core.resources.routing_error_pki_failed
import org.meshtastic.core.resources.routing_error_pki_send_fail_public_key
import org.meshtastic.core.resources.routing_error_pki_unknown_pubkey
import org.meshtastic.core.resources.routing_error_rate_limit_exceeded
import org.meshtastic.core.resources.routing_error_timeout
import org.meshtastic.core.resources.routing_error_too_large
import org.meshtastic.core.resources.unrecognized
import org.meshtastic.proto.MeshPacket
import org.meshtastic.proto.Routing

@Suppress("CyclomaticComplexMethod")
fun getStringResFrom(routingError: Int): StringResource = when (routingError) {
    Routing.Error.NONE.value -> Res.string.routing_error_none
    Routing.Error.NO_ROUTE.value -> Res.string.routing_error_no_route
    Routing.Error.GOT_NAK.value -> Res.string.routing_error_got_nak
    Routing.Error.TIMEOUT.value -> Res.string.routing_error_timeout
    Routing.Error.NO_INTERFACE.value -> Res.string.routing_error_no_interface
    Routing.Error.MAX_RETRANSMIT.value -> Res.string.routing_error_max_retransmit
    Routing.Error.NO_CHANNEL.value -> Res.string.routing_error_no_channel
    Routing.Error.TOO_LARGE.value -> Res.string.routing_error_too_large
    Routing.Error.NO_RESPONSE.value -> Res.string.routing_error_no_response
    Routing.Error.DUTY_CYCLE_LIMIT.value -> Res.string.routing_error_duty_cycle_limit
    Routing.Error.BAD_REQUEST.value -> Res.string.routing_error_bad_request
    Routing.Error.NOT_AUTHORIZED.value -> Res.string.routing_error_not_authorized
    Routing.Error.PKI_FAILED.value -> Res.string.routing_error_pki_failed
    Routing.Error.PKI_UNKNOWN_PUBKEY.value -> Res.string.routing_error_pki_unknown_pubkey
    Routing.Error.ADMIN_BAD_SESSION_KEY.value -> Res.string.routing_error_admin_bad_session_key
    Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED.value -> Res.string.routing_error_admin_public_key_unauthorized
    Routing.Error.RATE_LIMIT_EXCEEDED.value -> Res.string.routing_error_rate_limit_exceeded
    Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY.value -> Res.string.routing_error_pki_send_fail_public_key
    else -> Res.string.unrecognized
}

@Suppress("CyclomaticComplexMethod")
fun getMessageRoutingErrorStringResFrom(routingError: Int): StringResource = when (routingError) {
    Routing.Error.GOT_NAK.value,
    Routing.Error.TIMEOUT.value,
    Routing.Error.MAX_RETRANSMIT.value,
    -> Res.string.message_routing_error_max_retransmit

    Routing.Error.NO_CHANNEL.value -> Res.string.message_routing_error_no_channel

    Routing.Error.NO_INTERFACE.value -> Res.string.message_routing_error_no_interface

    Routing.Error.DUTY_CYCLE_LIMIT.value -> Res.string.message_routing_error_duty_cycle_limit

    Routing.Error.RATE_LIMIT_EXCEEDED.value -> Res.string.message_routing_error_rate_limit_exceeded

    Routing.Error.TOO_LARGE.value -> Res.string.message_routing_error_too_large

    Routing.Error.NO_RESPONSE.value -> Res.string.message_routing_error_no_response

    Routing.Error.BAD_REQUEST.value -> Res.string.message_routing_error_bad_request

    Routing.Error.NOT_AUTHORIZED.value -> Res.string.message_routing_error_not_authorized

    Routing.Error.PKI_FAILED.value -> Res.string.message_routing_error_pki_failed

    Routing.Error.PKI_UNKNOWN_PUBKEY.value -> Res.string.message_routing_error_pki_unknown_pubkey

    Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY.value -> Res.string.message_routing_error_pki_send_fail_public_key

    Routing.Error.ADMIN_BAD_SESSION_KEY.value -> Res.string.message_routing_error_admin_bad_session_key

    Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED.value ->
        Res.string.message_routing_error_admin_public_key_unauthorized

    else -> getStringResFrom(routingError)
}

@Suppress("CyclomaticComplexMethod")
fun getMessageRoutingErrorDetailResFrom(routingError: Int): StringResource? = when (routingError) {
    Routing.Error.NO_ROUTE.value -> Res.string.message_routing_error_no_route_detail

    Routing.Error.GOT_NAK.value -> Res.string.message_routing_error_got_nak_detail

    Routing.Error.TIMEOUT.value -> Res.string.message_routing_error_timeout_detail

    Routing.Error.MAX_RETRANSMIT.value -> Res.string.message_routing_error_max_retransmit_detail

    Routing.Error.NO_CHANNEL.value -> Res.string.message_routing_error_no_channel_detail

    Routing.Error.NO_INTERFACE.value -> Res.string.message_routing_error_no_interface_detail

    Routing.Error.DUTY_CYCLE_LIMIT.value -> Res.string.message_routing_error_duty_cycle_limit_detail

    Routing.Error.RATE_LIMIT_EXCEEDED.value -> Res.string.message_routing_error_rate_limit_exceeded_detail

    Routing.Error.TOO_LARGE.value -> Res.string.message_routing_error_too_large_detail

    Routing.Error.NO_RESPONSE.value -> Res.string.message_routing_error_no_response_detail

    Routing.Error.BAD_REQUEST.value -> Res.string.message_routing_error_bad_request_detail

    Routing.Error.NOT_AUTHORIZED.value -> Res.string.message_routing_error_not_authorized_detail

    Routing.Error.PKI_FAILED.value -> Res.string.message_routing_error_pki_failed_detail

    Routing.Error.PKI_UNKNOWN_PUBKEY.value -> Res.string.message_routing_error_pki_unknown_pubkey_detail

    Routing.Error.PKI_SEND_FAIL_PUBLIC_KEY.value -> Res.string.message_routing_error_pki_send_fail_public_key_detail

    Routing.Error.ADMIN_BAD_SESSION_KEY.value -> Res.string.message_routing_error_admin_bad_session_key_detail

    Routing.Error.ADMIN_PUBLIC_KEY_UNAUTHORIZED.value ->
        Res.string.message_routing_error_admin_public_key_unauthorized_detail

    else -> null
}

fun getMessageStatusDetailRes(status: MessageStatus?, routingError: Int): StringResource? =
    if (status == MessageStatus.ERROR) getMessageRoutingErrorDetailResFrom(routingError) else null

fun getMessageStatusStringRes(
    status: MessageStatus?,
    routingError: Int,
    isDirectMessage: Boolean = false,
): Pair<StringResource, StringResource> {
    val title = if (routingError > 0) Res.string.error else Res.string.message_delivery_status
    val text =
        when (status) {
            MessageStatus.RECEIVED -> Res.string.message_status_recipient_delivered

            MessageStatus.QUEUED -> Res.string.message_status_enroute

            MessageStatus.ENROUTE -> Res.string.message_status_enroute

            MessageStatus.SFPP_ROUTING -> Res.string.message_status_sfpp_routing

            MessageStatus.SFPP_CONFIRMED -> Res.string.message_status_sfpp_confirmed

            MessageStatus.DELIVERED ->
                if (isDirectMessage) {
                    Res.string.message_status_relayed_not_confirmed
                } else {
                    Res.string.message_status_delivered
                }

            MessageStatus.ERROR -> getMessageRoutingErrorStringResFrom(routingError)

            MessageStatus.UNKNOWN,
            null,
            -> Res.string.message_status_unknown
        }
    return title to text
}

fun isMessageStatusRetryable(status: MessageStatus?, routingError: Int, isDirectMessage: Boolean = false): Boolean =
    when {
        status == MessageStatus.DELIVERED && isDirectMessage -> true
        status != MessageStatus.ERROR -> false
        routingError in nonRetryableMessageRoutingErrors -> false
        else -> true
    }

private val nonRetryableMessageRoutingErrors = setOf(Routing.Error.NO_CHANNEL.value, Routing.Error.TOO_LARGE.value)

enum class MessageType {
    TEXT,
    AUDIO,
    IMAGE,
    PC_COMMAND,
}

data class Message(
    val uuid: Long,
    val receivedTime: Long,
    val node: Node,
    val text: String,
    val fromLocal: Boolean,
    val time: String,
    /**
     * Mesh time in epoch millis (the packet's `rx_time`) — the instant [time] renders. 0 when the radio never stamped
     * one (the packet carried no arrival time, or an old-firmware 0); read [displayTime] instead of this field so that
     * case falls back to [receivedTime].
     */
    val meshTime: Long = 0L,
    val read: Boolean,
    val status: MessageStatus?,
    val routingError: Int,
    val packetId: Int,
    val emojis: List<Reaction>,
    /** Signal-to-noise ratio in dB, or null when the packet carried no measurement. 0 dB is a valid reading. */
    val snr: Float?,
    /** Received signal strength, or null when the radio did not report one. 0 dBm is a valid reading. */
    val rssi: Int?,
    val hopsAway: Int,
    val replyId: Int?,
    val originalMessage: Message? = null,
    val viaMqtt: Boolean = false,
    val relayNode: Int? = null,
    val relays: Int = 0,
    val filtered: Boolean = false,
    /** The transport mechanism this packet arrived over (see [MeshPacket.TransportMechanism]). */
    val transportMechanism: Int = 0,
    /** True when the radio verified this broadcast's XEdDSA signature ([MeshPacket.xeddsa_signed]). */
    val xeddsaSigned: Boolean = false,
    /** On-device translation of [text], persisted so the user can toggle back to it without re-translating. */
    val translatedText: String? = null,
    /** Whether the bubble currently displays [translatedText] instead of [text]. */
    val showTranslated: Boolean = false,
    /** Type of media payload (Text, Audio, Image, PC Command). */
    val messageType: MessageType = MessageType.TEXT,
    /** Path to internal storage file for media attachments. */
    val mediaPath: String? = null,
    /** True if this is an AI "Thinking..." placeholder bubble. */
    val isAiThinking: Boolean = false,
) {
    val isAudio: Boolean
        get() =
            messageType == MessageType.AUDIO ||
                mediaPath?.endsWith(".c2") == true ||
                mediaPath?.endsWith(".wt") == true ||
                mediaPath?.endsWith(".wav") == true ||
                text.startsWith("[Voice Note:") ||
                text.startsWith("[Live PTT:") ||
                text.startsWith("🎤 Voice note") ||
                text.startsWith("📻 Live PTT")

    /** Formatted preview snippet for display in conversation lists, notifications, and reply quotes. */
    val snippet: String
        get() = org.meshtastic.core.model.util.formatMessageSnippet(text)

    /** Estimated payload size in bytes (or actual file size if mediaPath is present and accessible). */
    val mediaSizeBytes: Long?
        get() {
            if (!mediaPath.isNullOrBlank() && !mediaPath.endsWith(".wav")) {
                try {
                    val size = okio.FileSystem.SYSTEM.metadataOrNull(mediaPath.toPath())?.size
                    if (size != null && size > 0) return size
                } catch (_: Throwable) {}
            }
            if (isAudio) {
                // Heuristic from duration if text contains "[Voice Note: M:SS" or "🎤 Voice note (M:SS)"
                val timeStr =
                    when {
                        text.contains("(") && text.contains(")") -> text.substringAfter("(").substringBefore(")").trim()

                        text.contains("[") && text.contains("]") -> {
                            val bracketContent = text.substringAfter("[").substringBefore("]")
                            bracketContent.substringAfter(":").substringBefore("|").trim()
                        }

                        else -> ""
                    }
                val parts = timeStr.split(":")
                val sec =
                    if (parts.size >= 2) {
                        (parts[0].trim().toIntOrNull() ?: 0) * 60 + (parts[1].trim().toIntOrNull() ?: 0)
                    } else {
                        timeStr.toIntOrNull() ?: 0
                    }
                if (sec > 0) {
                    val isWt =
                        mediaPath?.endsWith(".wt") == true ||
                            mediaPath?.endsWith(".wav") == true ||
                            text.contains(".wt")
                    return if (isWt) ((sec * 900L) / 8L) else ((sec * 700L) / 8L)
                }
            }
            return null
        }

    val isImage: Boolean
        get() =
            messageType == MessageType.IMAGE ||
                mediaPath?.endsWith(".png") == true ||
                mediaPath?.endsWith(".rle") == true ||
                mediaPath?.endsWith(".jpg") == true ||
                text.startsWith("[1-bit Image:")

    /**
     * Epoch millis behind the displayed [time]: mesh time when the radio stamped one, otherwise the local
     * [receivedTime]. Use this for anything time-based the user can see, so an offline backlog sync — where every
     * packet shares one receipt instant — still reads as the conversation actually happened.
     */
    val displayTime: Long
        get() = meshTime.takeIf { it > 0 } ?: receivedTime

    /**
     * The text the UI displays (and copies): the persisted translation when [showTranslated] is set, except while
     * [searching] — search matches and highlights apply to the original [text].
     */
    fun displayedText(searching: Boolean = false): String =
        if (showTranslated && translatedText != null && !searching) translatedText else text

    fun getStatusStringRes(isDirectMessage: Boolean = false): Pair<StringResource, StringResource> =
        getMessageStatusStringRes(status, routingError, isDirectMessage)

    fun getStatusDetailRes(): StringResource? = getMessageStatusDetailRes(status, routingError)

    fun isStatusRetryable(isDirectMessage: Boolean = false): Boolean =
        isMessageStatusRetryable(status, routingError, isDirectMessage)
}
