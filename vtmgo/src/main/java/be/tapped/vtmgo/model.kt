package be.tapped.vtmgo

import arrow.core.NonEmptyList
import be.tapped.vtmgo.content.*
import be.tapped.vtmgo.epg.Epg
import be.tapped.vtmgo.profile.JWT
import okhttp3.Request

public sealed class ApiResponse {
    public sealed class Success : ApiResponse() {
        public sealed class Content : Success() {
            public data class Catalog(val catalog: List<PagedTeaserContent>) : Content()
            public data class Categories(val categories: List<Category>) : Content()
            public data class LiveChannels(val channels: List<LiveChannel>) : Content()
            public data class StoreFrontRows(val rows: List<StoreFront>) : Content()
            public data class Programs(val program: Program) : Content()
            public data class Favorites(val favorites: StoreFront.MyListStoreFront) : Content()
            public data class Search(val search: List<SearchResultResponse>) : Content()
        }

        public data class ProgramGuide(val epg: Epg) : Success()

        public sealed class Authentication : ApiResponse() {
            public data class Token(val jwt: JWT) : Authentication()
        }

        public data class Stream(val stream: AnvatoStream) : Success()
    }

    public sealed class Failure : ApiResponse() {
        public data class NetworkFailure(val responseCode: Int, val request: Request) : Failure()
        public data class JsonParsingException(val throwable: Throwable) : Failure()
        public object EmptyJson : Failure()

        public sealed class Authentication : Failure() {
            public data class MissingCookieValues(val cookieValues: NonEmptyList<String>) : Authentication()
            public object NoAuthorizeResponse : Authentication()
            public object NoCodeFound : Authentication()
            public object NoStateFound : Authentication()
            public object JWTTokenNotValid : Authentication()
        }

        public sealed class Stream : Failure() {
            public data class UnsupportedTargetType(val targetType: TargetResponse.Target) : Stream()
            public object NoAnvatoStreamFound : Stream()
            public object NoJSONFoundInAnvatoJavascriptFunction : Stream()
            public object NoPublishedEmbedUrlFound : Stream()
            public object NoMPDManifestUrlFound : Stream()
        }
    }
}
