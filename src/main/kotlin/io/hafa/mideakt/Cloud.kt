package io.hafa.mideakt

import java.net.HttpURLConnection
import java.net.URI
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Thrown when a cloud request fails. */
open class CloudException(
    message: String,
    /** The cloud or HTTP error code, if known. */
    val code: Int?,
) : Exception(message)

/** Thrown by [SmartHomeCloud.login] when the cloud rejects the account, e.g. a wrong email or password. */
class CloudLoginException(message: String, code: Int?) : CloudException(message, code)

/**
 * Thrown by [Setup.credentials] when the device isn't registered to the
 * signed-in account (cloud code [CODE]); log in with the device owner's account.
 */
class DeviceNotInAccountException(message: String) : CloudException(message, CODE) {
    /** Holds the cloud error code. */
    companion object {
        /** The cloud's "You have no permissions" code. */
        const val CODE = 3201
    }
}

/** A signed-in SmartHome session, from [SmartHomeCloud.login]; pass it to [Setup.credentials]. */
class SmartHomeSession internal constructor(internal val cloud: SmartHomeCloud, internal val accessToken: String)

/**
 * Midea SmartHome cloud client (the MSmartHome app's backend) — just enough to
 * fetch a device's token/key. Ported from msmart's `SmartHomeCloud`.
 *
 * The cloud only issues keys for devices registered to the signed-in account, so
 * pass the device owner's [account] and [password]. Call [login], then pass its session
 * to [Setup.credentials]; one session can serve many devices, from any thread.
 */
class SmartHomeCloud(private val account: String, private val password: String) {
    private val deviceId = randomHex(8)

    /**
     * Signs in and returns the session [Setup.credentials] needs. Throws [CloudLoginException] if the
     * cloud rejects the account.
     */
    fun login(): SmartHomeSession {
        val loginId = apiRequest(
            "/v1/user/login/id/get", baseBody(mapOf("loginAccount" to account)), cloudError = ::CloudLoginException,
        ).string("loginId") ?: throw CloudException("No loginId", null)

        val body = buildJsonObject {
            putJsonObject("data") {
                put("platform", 2)
                put("deviceId", deviceId)
            }
            putJsonObject("iotData") {
                put("appId", APP_ID)
                put("src", APP_ID)
                put("clientType", 1)
                put("loginAccount", account)
                put("iampwd", iamPassword(loginId, password))
                put("password", encryptPassword(loginId, password))
                put("pushToken", randomHex(90))
                put("stamp", timestamp())
                put("reqId", randomHex(16))
            }
        }
        val result = apiRequest("/mj/user/login", body, cloudError = ::CloudLoginException)
        val accessToken = (result["mdata"] as? JsonObject)?.string("accessToken")
            ?: throw CloudException("No accessToken", null)
        return SmartHomeSession(this, accessToken)
    }

    /** Fetches the token/key pair, as hex, the cloud files under one byte order of [deviceId]. */
    internal fun getToken(session: SmartHomeSession, deviceId: Long, bigEndian: Boolean): Pair<String, String> {
        val udpid = UDPID.compute(deviceId, bigEndian)
        val result = apiRequest(
            "/v1/iot/secure/getToken",
            baseBody(mapOf("udpid" to udpid, "applianceCodes" to deviceId.toString())),
            session.accessToken,
        ) { message, code ->
            if (code == DeviceNotInAccountException.CODE) DeviceNotInAccountException(message)
            else CloudException(message, code)
        }
        val list = result["tokenlist"] as? JsonArray ?: throw CloudException("No tokenlist", null)
        for (entry in list) {
            val entryObject = entry as? JsonObject ?: continue
            if (entryObject.string("udpId") == udpid) {
                val token = entryObject.string("token")
                val key = entryObject.string("key")
                if (token != null && key != null) return token to key
            }
        }
        throw CloudException("No token/key for udpid $udpid", null)
    }

    private fun baseBody(data: Map<String, String>): JsonObject = buildJsonObject {
        put("appId", APP_ID)
        put("src", APP_ID)
        put("format", 2)
        put("clientType", 1)
        put("language", "en_US")
        put("deviceId", deviceId)
        put("stamp", timestamp())
        put("reqId", randomHex(16))
        data.forEach { (key, value) -> put(key, value) }
    }

    /** Posts [body] to [endpoint]; a cloud-reported error is thrown as [cloudError]'s result. */
    private fun apiRequest(
        endpoint: String,
        body: JsonObject,
        accessToken: String = "",
        cloudError: (String, Int?) -> CloudException = ::CloudException,
    ): JsonObject {
        val contents = body.toString()
        val random = randomHex(16)

        val connection = URI("$BASE_URL/mas/v5/app/proxy?alias=$endpoint").toURL()
            .openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 10000
        connection.readTimeout = 10000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("secretVersion", "1")
        connection.setRequestProperty("sign", sign(contents, random))
        connection.setRequestProperty("random", random)
        connection.setRequestProperty("accessToken", accessToken)
        connection.outputStream.use { it.write(contents.toByteArray(Charsets.UTF_8)) }

        // On an HTTP error `inputStream` throws and the response body is on `errorStream`.
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

        val parsed = if (text.isNotBlank()) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null
        val json = parsed as? JsonObject
            ?: throw CloudException("HTTP $status from cloud: ${text.take(200).ifBlank { "empty body" }}", status)
        // `code` arrives as a number or a string.
        val code = json.string("code")?.toIntOrNull()
        if (code == 0) return json["data"] as? JsonObject ?: JsonObject(emptyMap())
        throw cloudError(json.string("msg") ?: "Unknown cloud error", code)
    }

    internal companion object {
        // Public MSmartHome app identity, shared by every Midea LAN implementation.
        const val BASE_URL = "https://mp-prod.appsmb.com"
        const val APP_ID = "1010"
        const val HMAC_KEY = "PROD_VnoClJI9aikS8dyy"
        const val IOT_KEY = "meicloud"
        const val LOGIN_KEY = "ac21b9f9cbfe4ca5a88562ef25e2b768"

        private val secureRandom = SecureRandom()

        fun sign(body: String, random: String): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            return mac.doFinal((IOT_KEY + body + random).toByteArray(Charsets.UTF_8)).toHexString()
        }

        fun encryptPassword(loginId: String, password: String): String =
            sha256Hex(loginId + sha256Hex(password) + LOGIN_KEY)

        fun iamPassword(loginId: String, password: String): String =
            sha256Hex(loginId + md5Hex(md5Hex(password)) + LOGIN_KEY)

        private fun sha256Hex(text: String): String = Crypto.sha256(text.toByteArray(Charsets.UTF_8)).toHexString()

        private fun md5Hex(text: String): String = Crypto.md5(text.toByteArray(Charsets.UTF_8)).toHexString()

        private fun randomHex(byteCount: Int): String = ByteArray(byteCount).also(secureRandom::nextBytes).toHexString()

        private fun timestamp(): String {
            val formatter = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            formatter.timeZone = TimeZone.getTimeZone("UTC")
            return formatter.format(Date())
        }
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** Computes the udpid the cloud uses to look up a device's token/key. */
internal object UDPID {
    /** The udpid for [deviceId], from its six bytes in little- or [bigEndian] order. */
    fun compute(deviceId: Long, bigEndian: Boolean): String {
        val bytes = ByteArray(6)
        var value = deviceId
        for (i in 0 until 6) {
            bytes[i] = (value and 0xFF).toByte()
            value = value shr 8
        }
        if (bigEndian) bytes.reverse()
        val hash = Crypto.sha256(bytes)
        return ByteArray(16) { (hash[it].toInt() xor hash[it + 16].toInt()).toByte() }.toHexString()
    }
}
