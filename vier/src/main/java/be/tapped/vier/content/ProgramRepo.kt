package be.tapped.vier.content

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.Validated
import arrow.core.computations.either
import arrow.core.extensions.either.applicative.applicative
import arrow.core.extensions.either.traverse.map
import arrow.core.extensions.list.traverse.sequence
import arrow.core.extensions.nonemptylist.semigroup.semigroup
import arrow.core.extensions.validated.applicative.applicative
import arrow.core.extensions.validated.bifunctor.mapLeft
import arrow.core.fix
import arrow.core.flatMap
import arrow.fx.coroutines.parTraverse
import be.tapped.common.internal.executeAsync
import be.tapped.common.internal.toValidateNel
import be.tapped.vier.ApiResponse.Failure
import be.tapped.vier.ApiResponse.Failure.HTML
import be.tapped.vier.ApiResponse.Failure.HTML.Parsing
import be.tapped.vier.ApiResponse.Success
import be.tapped.vier.common.safeAttr
import be.tapped.vier.common.safeBodyString
import be.tapped.vier.common.safeChild
import be.tapped.vier.common.safeSelect
import be.tapped.vier.common.safeSelectFirst
import be.tapped.vier.common.safeText
import be.tapped.vier.common.vierUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

internal data class PartialProgram(val name: String, val path: String)

internal class HtmlPartialProgramParser {

    private val applicative = Validated.applicative(NonEmptyList.semigroup<HTML>())

    internal suspend fun parse(document: Document): Either<HTML, List<PartialProgram>> =
        document.safeSelect("a.program-overview__link").flatMap { links ->
            links.map { link ->
                val path = link.safeAttr("href").toValidatedNel()
                val title = link.safeChild(0).flatMap { it.safeText() }.toValidateNel()
                applicative.mapN(title, path) { (title, path) -> PartialProgram(title, path) }
            }.sequence(applicative)
                .mapLeft { Parsing(it) }
                .map { it.fix() }
                .toEither()
        }
}

internal class HtmlProgramParser {
    private companion object {
        private const val jsonCSSSelector = "data-hero"
    }

    suspend fun parse(document: Document): Either<Failure, Program> =
        document
            .safeSelectFirst("div[$jsonCSSSelector]")
            .flatMap { it.safeAttr(jsonCSSSelector).toEither() }
            .flatMap {
                Either.catch {
                    val programDataObject = Json.decodeFromString<JsonObject>(it)["data"]!!.jsonObject
                    Json {
                        isLenient = true
                        ignoreUnknownKeys = true
                    }.decodeFromJsonElement<Program>(programDataObject)
                }.mapLeft { Failure.JsonParsingException(it) }
            }
}

public interface ProgramRepo {

    /**
     * Fetches the VIER homepage and scrapes the `HTML` and `JSON` information found within this page.
     *
     * Equivalent of:
     * ```
     * curl -X GET "https://www.vier.be/"
     * ```
     *
     * By default it runs on [Dispatchers.IO] and is parallelized to get details for every [Program] on different Coroutines
     *
     * This function is quite expensive to run and it advised to be cached.
     *
     * @return Either a [Failure] or a [Success.Content.Programs]
     */
    public suspend fun fetchPrograms(): Either<Failure, Success.Content.Programs>

    /**
     * Fetch a single [Program] by [SearchHit.Source.SearchKey.Program].
     *
     * Equivalent of:
     * ```
     * curl -X GET "https://www.vier.be/de-slimste-mens-ter-wereld"
     * ```
     *
     * By default it runs on [Dispatchers.IO]
     *
     * @param programSearchKey what program you wish to have more information about.
     * @return Either a [Failure] or a [Success.Content.SingleProgram]
     */
    public suspend fun fetchProgram(programSearchKey: SearchHit.Source.SearchKey.Program): Either<Failure, Success.Content.SingleProgram>

    /**
     * Fetch a single [Success.Content.SingleEpisode] by [SearchHit.Source.SearchKey.Episode].
     *
     * Equivalent of:
     * ```
     * curl -X GET "https://www.vier.be/de-slimste-mens-ter-wereld"
     * ```
     *
     * By default it runs on [Dispatchers.IO]
     *
     * @param episodeSearchKey what episode you want to have more information about
     * @return Either a [Failure] or a [Success.Content.SingleEpisode]
     */
    public suspend fun fetchEpisode(episodeSearchKey: SearchHit.Source.SearchKey.Episode): Either<Failure, Success.Content.SingleEpisode>

}

internal class HttpProgramRepo(
    private val client: OkHttpClient,
    private val htmlPartialProgramParser: HtmlPartialProgramParser,
    private val htmlProgramParser: HtmlProgramParser,
) : ProgramRepo {

    override suspend fun fetchPrograms(): Either<Failure, Success.Content.Programs> =
        withContext(Dispatchers.IO) {
            either {
                val html = !client.executeAsync(
                    Request.Builder()
                        .get()
                        .url(vierUrl)
                        .build()
                ).safeBodyString()

                val partialPrograms = !htmlPartialProgramParser.parse(Jsoup.parse(html))
                val programs = !fetchProgramDetails(partialPrograms)
                Success.Content.Programs(programs)
            }
        }

    private suspend fun fetchProgramFromUrl(programUrl: String): Either<Failure, Program> =
        withContext(Dispatchers.IO) {
            client.executeAsync(
                Request.Builder()
                    .get()
                    .url(programUrl)
                    .build()
            )
                .safeBodyString()
                .flatMap { html -> htmlProgramParser.parse(Jsoup.parse(html)) }
        }

    override suspend fun fetchProgram(programSearchKey: SearchHit.Source.SearchKey.Program): Either<Failure, Success.Content.SingleProgram> =
        fetchProgramFromUrl(programSearchKey.url).map { Success.Content.SingleProgram(it) }

    override suspend fun fetchEpisode(episodeSearchKey: SearchHit.Source.SearchKey.Episode): Either<Failure, Success.Content.SingleEpisode> =
        either {
            val program = !fetchProgramFromUrl(episodeSearchKey.url)
            val episodeForSearchKey =
                !Either
                    .fromNullable(
                        program
                            .playlists
                            .flatMap(Playlist::episodes)
                            .firstOrNull { it.pageInfo.nodeId == episodeSearchKey.nodeId }
                    )
                    .mapLeft { Failure.Content.NoEpisodeFound }
            Success.Content.SingleEpisode(episodeForSearchKey)
        }

    private suspend fun fetchProgramDetails(partialPrograms: List<PartialProgram>): Either<Failure, List<Program>> =
        partialPrograms.parTraverse(Dispatchers.IO) { fetchProgramFromUrl("$vierUrl${it.path}") }
            .sequence(Either.applicative())
            .map { it.fix() }
}
