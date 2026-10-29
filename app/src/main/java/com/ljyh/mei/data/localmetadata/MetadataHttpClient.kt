package com.ljyh.mei.data.localmetadata

import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

internal const val METADATA_USER_AGENT =
    "MeiloO/1.0 (Android; metadata-only; https://github.com/yulimfish/MeiloO)"
internal const val METADATA_REQUEST_TIMEOUT_MS = 15_000L

internal val metadataJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

/** Shared Ktor client factory with metadata-only headers and bounded network waits. */
fun createMetadataHttpClient(): HttpClient = HttpClient(Android) {
    expectSuccess = false
    followRedirects = true
    install(HttpTimeout) {
        requestTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS
        connectTimeoutMillis = 8_000L
        socketTimeoutMillis = METADATA_REQUEST_TIMEOUT_MS
    }
    defaultRequest {
        header(HttpHeaders.UserAgent, METADATA_USER_AGENT)
        header(HttpHeaders.Accept, "application/json")
    }
}

internal suspend fun HttpResponse.requireSuccessBody(): String {
    if (!status.isSuccess()) {
        throw MetadataHttpException("HTTP ${status.value} from metadata source")
    }
    return bodyAsText()
}

internal class MetadataHttpException(message: String) : IllegalStateException(message)
