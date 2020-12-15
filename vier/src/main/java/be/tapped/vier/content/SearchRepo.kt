package be.tapped.vier.content

import arrow.core.Either
import arrow.core.computations.either
import be.tapped.common.internal.executeAsync
import be.tapped.vier.ApiResponse.Failure
import be.tapped.vier.ApiResponse.Success
import be.tapped.vier.common.safeBodyString
import be.tapped.vier.common.vierBaseApiUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class JsonSearchResultsParser {
    suspend fun parse(json: String): Either<Failure, List<SearchHit>> =
        Either.catch {
            val hitsArr = Json.decodeFromString<JsonObject>(json)["hits"]!!.jsonObject["hits"]!!.jsonArray
            Json.decodeFromJsonElement<List<SearchHit>>(hitsArr)
        }.mapLeft { Failure.JsonParsingException(it) }
}

public interface SearchRepo {
    /**
     * Searches the API.
     *
     * Equivalent of:
     * ```
     * curl -X POST \
     *      -H  -d '{ "query": <query>,"sites":["vier"],"page":0,"mode":"byDate"}' "https://api.viervijfzes.be/search"
     * ```
     *
     * By default it runs on [Dispatchers.IO] and is parallelized to get details for every [Program] on different Coroutines.
     *
     * @param query an arbitrary string
     * @return Either a [Failure] or a [Success.Content.SearchResults]
     */
    public suspend fun search(query: String): Either<Failure, Success.Content.SearchResults>
}

internal class HttpSearchRepo(
    private val client: OkHttpClient,
    private val jsonSearchResultsParser: JsonSearchResultsParser,
) : SearchRepo {

    override suspend fun search(query: String): Either<Failure, Success.Content.SearchResults> =
        withContext(Dispatchers.IO) {
            either {
                val searchResponse = client.executeAsync(
                    Request.Builder()
                        .post(
                            buildJsonObject {
                                put("query", query)
                                put("sites", buildJsonArray {

                                    add("vier")
                                    // TODO add ability to search in vijf and zes
                                    // add("vijf")
                                    // add("zes")
                                })
                                put("page", 0)
                                put("mode", "byDate")
                            }.toString().toRequestBody()
                        )
                        .url("$vierBaseApiUrl/search")
                        .build()
                )

                val searchResults = !jsonSearchResultsParser.parse(!searchResponse.safeBodyString())
                Success.Content.SearchResults(searchResults)
            }
        }
}
