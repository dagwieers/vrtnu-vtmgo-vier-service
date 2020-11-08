package be.tapped.vrtnu

import be.tapped.vrtnu.model.VRTLogin
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

interface VRTCookieJar : OIDCXSRFCache, CookieJar

interface OIDCXSRFCache {
    fun getXSRFToken(url: String): String
}

class DefaultVRTCookieJar : VRTCookieJar {
    private val cookieCache: MutableMap<String, List<Cookie>> = mutableMapOf()

    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookieCache[url.toString()] ?: emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookieCache[url.toString()] = cookies
    }

    override fun getXSRFToken(url: String): String =
        cookieCache[url]!!.first { it.name == "OIDCXSRF" }.value
}

class TokenValidator {
    fun isValidToken(loginJson: JsonObject): Boolean =
        loginJson["errorCode"]!!.jsonPrimitive.int == 0

    fun handleInvalidToken(listener: TokenResolver.Listener, loginJson: JsonObject) {
        listener.onFailedToLogin(
            when (loginJson["errorDetails"]?.jsonPrimitive?.content) {
                "invalid loginID or password" -> VRTLogin.Failure.InvalidCredentials
                "loginID must be provided" -> VRTLogin.Failure.MissingLoginId
                "Missing required parameter: password" -> VRTLogin.Failure.MissingPassword
                else -> VRTLogin.Failure.Unknown
            }
        )
    }
}

class TokenResolver(
    private val listener: Listener,
    private val tokenValidator: TokenValidator = TokenValidator(),
    private val cookieJar: VRTCookieJar = DefaultVRTCookieJar()
) {

    interface Listener {
        fun onFailedToLogin(failure: VRTLogin.Failure)
    }

    companion object {
        private const val API_KEY =
            "3_qhEcPa5JGFROVwu5SWKqJ4mVOIkwlFNMSKwzPDAh8QZOtHqu6L4nD5Q7lk0eXOOG"
        private const val LOGIN_URL = "https://accounts.vrt.be/accounts.login"
        private const val VRT_LOGIN_URL = "https://login.vrt.be/perform_login"
        private const val TOKEN_GATEWAY_URL = "https://token.vrt.be"
        private const val USER_TOKEN_GATEWAY_URL =
            "https://token.vrt.be/vrtnuinitlogin?provider=site&destination=https://www.vrt.be/vrtnu/"
    }

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .build()

    fun login(userName: String, password: String) {
        val loginJson = getLoginJson(userName, password)
        if (tokenValidator.isValidToken(loginJson)) {
            fetchXVRTToken(userName, loginJson)
            //fetchRefreshToken(loginJson)
        } else {
            tokenValidator.handleInvalidToken(listener, loginJson)
        }
    }

    //TODO investigate we get a 403
    private fun fetchRefreshToken(loginJson: JsonObject) {
        val response = client.newCall(
            Request.Builder()
                .get()
                .url(USER_TOKEN_GATEWAY_URL)
                .build()
        ).execute()
        val xsrf = cookieJar.getXSRFToken(response.request.url.toString())

        val uid = loginJson["UID"]!!.jsonPrimitive.content
        val uidSignature = loginJson["UIDSignature"]!!.jsonPrimitive.content
        val signatureTimestamp = loginJson["signatureTimestamp"]!!.jsonPrimitive.content

        val performLoginResponse = client.newCall(
            Request.Builder()
                .url(VRT_LOGIN_URL)
                .post(
                    FormBody.Builder()
                        .add("UID", uid)
                        .add("UIDSignature", uidSignature)
                        .add("signatureTimestamp", signatureTimestamp)
                        .add("client_id", "vrtnu-site")
                        .add("_csrf", xsrf)
                        .build()
                )
                .build()
        ).execute()
    }

    private fun fetchXVRTToken(userName: String, loginJson: JsonObject) {
        val loginToken =
            loginJson["sessionInfo"]!!.jsonObject["login_token"]?.jsonPrimitive?.content
                ?: throw IllegalStateException("No login token found!")

        val loginCookie = "glt_${API_KEY}=$loginToken"
        val json = buildJsonObject {
            put("uid", loginJson["UID"]!!.jsonPrimitive.content)
            put("uidsig", loginJson["UIDSignature"]!!.jsonPrimitive.content)
            put("ts", loginJson["signatureTimestamp"]!!.jsonPrimitive.content)
            put("email", userName)
        }.toString()

        client.newCall(
            Request.Builder()
                .url(TOKEN_GATEWAY_URL)
                .addHeader("Cookie", loginCookie)
                .post(json.toRequestBody(jsonMediaType))
                .build()
        ).execute()
    }

    private fun getLoginJson(userName: String, password: String): JsonObject =
        Json.decodeFromString(
            client.newCall(
                Request.Builder()
                    .url(LOGIN_URL)
                    .post(
                        FormBody.Builder()
                            .add("loginID", userName)
                            .add("password", password)
                            .add("sessionExpiration", "-1")
                            .add("APIKey", API_KEY)
                            .add("targetEnv", "jssdk")
                            .build()
                    )
                    .build()
            ).execute().body!!.string()
        )
}
