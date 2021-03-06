package io.ktor.client.features

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.response.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.util.*
import kotlinx.coroutines.io.*
import kotlinx.io.charsets.*
import kotlinx.io.core.*
import kotlin.math.*

/**
 * [HttpClient] feature that encodes [String] request bodies to [TextContent]
 * and processes the response body as [String].
 *
 * To configure charsets set following properties in [HttpPlainText.Config].
 */
class HttpPlainText internal constructor(
    acceptCharsets: Set<Charset>,
    charsetQuality: Map<Charset, Float>,
    sendCharset: Charset?,
    internal val responseCharsetFallback: Charset
) {
    private val requestCharset: Charset
    private val acceptCharsetHeader: String

    init {
        val withQuality = charsetQuality.toList().sortedByDescending { it.second }
        val withoutQuality = acceptCharsets.filter { !charsetQuality.containsKey(it) }.sortedBy { it.name }

        acceptCharsetHeader = buildString {
            withoutQuality.forEach {
                if (length > 0) append(",")
                append(it.name)
            }

            withQuality.forEach { (charset, quality) ->
                if (length > 0) append(",")

                check(quality in 0.0..1.0)

                val truncatedQuality = (100 * quality).roundToInt() / 100.0
                append("${charset.name};q=$truncatedQuality")
            }

            if (isEmpty()) {
                append(responseCharsetFallback.name)
            }
        }

        requestCharset = sendCharset
            ?: withoutQuality.firstOrNull()
                ?: withQuality.firstOrNull()?.first
                ?: Charsets.UTF_8
    }

    /**
     * Charset configuration for [HttpPlainText] feature.
     */
    class Config {
        internal val acceptCharsets: MutableSet<Charset> = mutableSetOf()
        internal val charsetQuality: MutableMap<Charset, Float> = mutableMapOf()

        /**
         * Add [charset] to allowed list with selected [quality].
         */
        fun register(charset: Charset, quality: Float? = null) {
            quality?.let { check(it in 0.0..1.0) }

            acceptCharsets.add(charset)

            if (quality == null) {
                charsetQuality.remove(charset)
            } else {
                charsetQuality[charset] = quality
            }
        }

        /**
         * Explicit [Charset] for sending content.
         *
         * Use first with highest quality from [register] charset if null.
         */
        var sendCharset: Charset? = null

        /**
         * Fallback charset for the response.
         * Use it if no charset specified.
         */
        var responseCharsetFallback: Charset = Charsets.UTF_8

        /**
         * Default [Charset] to use.
         */
        @Deprecated(
            "Use [accept] method instead.",
            replaceWith = ReplaceWith("accept()"),
            level = DeprecationLevel.ERROR
        )
        var defaultCharset: Charset = Charsets.UTF_8
    }

    @Suppress("KDocMissingDocumentation")
    companion object Feature : HttpClientFeature<Config, HttpPlainText> {
        override val key = AttributeKey<HttpPlainText>("HttpPlainText")

        override fun prepare(block: Config.() -> Unit): HttpPlainText {
            val config = Config().apply(block)

            with(config) {
                return HttpPlainText(
                    acceptCharsets, charsetQuality,
                    sendCharset, responseCharsetFallback
                )
            }
        }

        override fun install(feature: HttpPlainText, scope: HttpClient) {
            scope.requestPipeline.intercept(HttpRequestPipeline.Render) { content ->
                feature.addCharsetHeaders(context)

                if (content !is String) return@intercept

                val contentType = context.contentType()
                if (contentType != null && contentType.contentType != ContentType.Text.Plain.contentType) return@intercept

                val contentCharset = contentType?.charset()
                proceedWith(feature.wrapContent(content, contentCharset))
            }

            scope.responsePipeline.intercept(HttpResponsePipeline.Parse) { (info, body) ->
                if (info.type != String::class || body !is ByteReadChannel) return@intercept
                val content = feature.read(context, body.readRemaining())
                proceedWith(HttpResponseContainer(info, content))
            }
        }
    }

    private fun wrapContent(content: String, contentCharset: Charset?): Any {
        val charset = contentCharset ?: requestCharset
        return TextContent(content, ContentType.Text.Plain.withCharset(charset))
    }

    internal fun read(call: HttpClientCall, body: Input): String {
        val actualCharset = call.response.charset() ?: responseCharsetFallback
        return body.readText(charset = actualCharset)
    }

    internal fun addCharsetHeaders(context: HttpRequestBuilder) {
        if (context.headers[HttpHeaders.AcceptCharset] != null) return
        context.headers[HttpHeaders.AcceptCharset] = acceptCharsetHeader
    }

    /**
     * Deprecated
     */
    @Deprecated(
        "Use [Config.accept] method instead.",
        replaceWith = ReplaceWith("accept()"),
        level = DeprecationLevel.ERROR
    )
    var defaultCharset: Charset
        get() = error("defaultCharset is deprecated")
        set(value) = error("defaultCharset is deprecated")
}

/**
 * Configure client charsets.
 *
 * ```kotlin
 * val client = HttpClient {
 *     Charsets {
 *         accept(Charsets.UTF_8)
 *         accept(Charsets.ISO_8859_1, quality = 0.1)
 *     }
 * }
 * ```
 */
fun HttpClientConfig<*>.Charsets(block: HttpPlainText.Config.() -> Unit) {
    install(HttpPlainText, block)
}
