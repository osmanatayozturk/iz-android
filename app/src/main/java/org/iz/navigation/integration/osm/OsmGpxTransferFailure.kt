package org.iz.navigation.integration.osm

import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.ProtocolException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

internal enum class OsmGpxTransferReason(val code: String) {
    DNS("GPX-DNS"),
    TLS("GPX-TLS"),
    CONNECT("GPX-CONNECT"),
    HEAD_TIMEOUT("GPX-HEAD-TIMEOUT"),
    HEAD_INTERRUPTED("GPX-HEAD-INTERRUPTED"),
    HEAD_SOCKET("GPX-HEAD-SOCKET"),
    HEAD_IO("GPX-HEAD-IO"),
    BODY_TIMEOUT("GPX-BODY-TIMEOUT"),
    BODY_INTERRUPTED("GPX-BODY-INTERRUPTED"),
    BODY_INCOMPLETE("GPX-BODY-INCOMPLETE"),
    BODY_LENGTH("GPX-BODY-LENGTH"),
    BODY_SOCKET("GPX-BODY-SOCKET"),
    BODY_IO("GPX-BODY-IO"),
}

/** Safe to render. Exception text, endpoints and response data never enter this value. */
internal class OsmGpxTransferException(val reason: OsmGpxTransferReason) : IOException(reason.code)

internal fun classifyGpxTransfer(error: IOException, readingBody: Boolean): OsmGpxTransferException {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(8).toList()
    val reason = when {
        !readingBody && causes.any { it is UnknownHostException } -> OsmGpxTransferReason.DNS
        !readingBody && causes.any { it is SSLException } -> OsmGpxTransferReason.TLS
        !readingBody && causes.any { it is ConnectException || it is NoRouteToHostException } -> OsmGpxTransferReason.CONNECT
        causes.any { it is SocketTimeoutException } ->
            if (readingBody) OsmGpxTransferReason.BODY_TIMEOUT else OsmGpxTransferReason.HEAD_TIMEOUT
        causes.any { it is InterruptedIOException } ->
            if (readingBody) OsmGpxTransferReason.BODY_INTERRUPTED else OsmGpxTransferReason.HEAD_INTERRUPTED
        readingBody && causes.any { it is EOFException || it is ProtocolException } -> OsmGpxTransferReason.BODY_INCOMPLETE
        causes.any { it is SocketException } ->
            if (readingBody) OsmGpxTransferReason.BODY_SOCKET else OsmGpxTransferReason.HEAD_SOCKET
        readingBody -> OsmGpxTransferReason.BODY_IO
        else -> OsmGpxTransferReason.HEAD_IO
    }
    return OsmGpxTransferException(reason)
}
