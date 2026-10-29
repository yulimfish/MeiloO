package com.ljyh.mei.data.localmetadata

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

/** A successful or failed per-source lookup; failures do not suppress other sources. */
data class MetadataFailure(
    val source: MetadataSource,
    val message: String,
)

data class MetadataSearchResult(
    val candidates: List<MetadataCandidate>,
    val failures: List<MetadataFailure> = emptyList(),
) {
    val best: MetadataCandidate?
        get() = candidates.firstOrNull()
}

/** Coordinates independent providers; contains no Android UI or playback dependency. */
class MetadataEngine internal constructor(
    private val providers: List<MetadataProvider>,
    private val defaultPreference: SourcePreference,
    private val requestTimeoutMs: Long,
    private val closeable: AutoCloseable?,
) : AutoCloseable {
    constructor(
        providers: List<MetadataProvider>,
        defaultPreference: SourcePreference = SourcePreference(),
        requestTimeoutMs: Long = METADATA_REQUEST_TIMEOUT_MS,
    ) : this(providers, defaultPreference, requestTimeoutMs, null)

    init {
        require(providers.map(MetadataProvider::source).distinct().size == providers.size) {
            "Only one provider per metadata source is allowed"
        }
        require(requestTimeoutMs > 0) { "requestTimeoutMs must be positive" }
    }

    suspend fun search(
        query: MetadataQuery,
        trackId: String? = null,
        preference: SourcePreference = defaultPreference,
    ): MetadataSearchResult {
        val sourceOrder = preference.sourcesFor(trackId)
        val providersBySource = providers.associateBy(MetadataProvider::source)
        val selectedProviders = sourceOrder.mapNotNull(providersBySource::get)
        if (selectedProviders.isEmpty()) return MetadataSearchResult(emptyList())

        val outcomes = supervisorScope {
            selectedProviders.map { provider ->
                async { lookup(provider, query) }
            }.awaitAll()
        }
        return MetadataSearchResult(
            candidates = MetadataMatcher.rank(
                query = query,
                candidates = outcomes.flatMap(ProviderOutcome::candidates),
                sourceOrder = sourceOrder,
            ),
            failures = outcomes.mapNotNull(ProviderOutcome::failure),
        )
    }

    suspend fun findBest(
        query: MetadataQuery,
        trackId: String? = null,
        preference: SourcePreference = defaultPreference,
    ): MetadataCandidate? = search(query, trackId, preference).best

    override fun close() {
        closeable?.close()
    }

    private suspend fun lookup(
        provider: MetadataProvider,
        query: MetadataQuery,
    ): ProviderOutcome = try {
        ProviderOutcome(
            candidates = withTimeout(requestTimeoutMs) { provider.search(query) },
        )
    } catch (timeout: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        ProviderOutcome(
            failure = MetadataFailure(provider.source, "Metadata request timed out"),
        )
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Exception) {
        ProviderOutcome(
            failure = MetadataFailure(
                source = provider.source,
                message = error.message ?: error.javaClass.simpleName,
            ),
        )
    }

    private data class ProviderOutcome(
        val candidates: List<MetadataCandidate> = emptyList(),
        val failure: MetadataFailure? = null,
    )

    companion object {
        /** Creates iTunes, MusicBrainz/CAA, and LRCLIB providers sharing one Ktor client. */
        fun createDefault(
            preference: SourcePreference = SourcePreference(),
            requestTimeoutMs: Long = METADATA_REQUEST_TIMEOUT_MS,
        ): MetadataEngine {
            val client = createMetadataHttpClient()
            return MetadataEngine(
                providers = listOf(
                    ITunesMetadataProvider(client),
                    MusicBrainzMetadataProvider(client),
                    LrcLibMetadataProvider(client),
                ),
                defaultPreference = preference,
                requestTimeoutMs = requestTimeoutMs,
                closeable = client,
            )
        }
    }
}
