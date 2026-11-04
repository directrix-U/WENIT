package com.example.shortsdetector.ai

import android.os.Looper
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

enum class AiFailureKind {
    NO_KEY,
    NO_CONSENT,
    NETWORK,
    HTTP_ERROR,
    TIMEOUT,
    BAD_FORMAT,
    REFUSED,
    /** stop_reason=max_tokens. 출력이 잘렸다. */
    TRUNCATED
}

sealed interface AiResult {
    data class Success(val json: JSONObject) : AiResult
    data class Failure(val kind: AiFailureKind, val httpStatus: Int? = null) : AiResult
}

data class AiHttpResponse(val status: Int, val body: String)

/** 테스트에서 가짜 응답을 넣는 지점. 연결 실패는 IOException, 시간 초과는 SocketTimeoutException 으로 던진다. */
fun interface AiTransport {
    fun post(url: String, headers: Map<String, String>, body: String): AiHttpResponse
}

/** 실제 전송. 메인 스레드에서 부르면 예외를 던진다. */
class HttpUrlConnectionTransport : AiTransport {

    override fun post(url: String, headers: Map<String, String>, body: String): AiHttpResponse {
        check(Looper.myLooper() != Looper.getMainLooper()) { "메인 스레드에서 호출할 수 없다" }
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            headers.forEach(connection::setRequestProperty)
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            // 오류 응답 본문은 쓰지 않는다. 상태 코드만으로 구분한다.
            val text = if (status in 200..299) {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                ""
            }
            return AiHttpResponse(status, text)
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 15_000
    }
}

/**
 * Messages API 호출 한 번. 재시도하지 않는다(실패하면 호출 측이 규칙 기반 추천으로 대체한다).
 * 응답 원문과 키는 로그에 남기지 않는다. 호출 스레드에서 동기로 동작한다.
 */
class ClaudeMessagesClient(apiKey: String, private val transport: AiTransport) {

    private val key = apiKey.trim()

    val hasKey: Boolean get() = key.isNotEmpty()

    /** @param schema 모든 object 에 additionalProperties=false 가 있어야 한다 */
    fun requestJson(system: String, userText: String, schema: JSONObject): AiResult {
        if (!hasKey) return AiResult.Failure(AiFailureKind.NO_KEY)
        val response = try {
            transport.post(ENDPOINT, headers(), requestBody(system, userText, schema).toString())
        } catch (e: SocketTimeoutException) {
            return AiResult.Failure(AiFailureKind.TIMEOUT)
        } catch (e: IOException) {
            return AiResult.Failure(AiFailureKind.NETWORK)
        }
        if (response.status !in 200..299) return AiResult.Failure(AiFailureKind.HTTP_ERROR, response.status)
        return try {
            parse(response.body)
        } catch (e: JSONException) {
            AiResult.Failure(AiFailureKind.BAD_FORMAT, response.status)
        }
    }

    private fun headers() = mapOf(
        "x-api-key" to key,
        "anthropic-version" to API_VERSION,
        "content-type" to "application/json"
    )

    internal fun requestBody(system: String, userText: String, schema: JSONObject): JSONObject =
        JSONObject()
            .put("model", MODEL)
            .put("max_tokens", MAX_TOKENS)
            .put("system", system)
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", userText))
            )
            .put(
                "output_config",
                JSONObject()
                    .put("effort", EFFORT)
                    .put("format", JSONObject().put("type", "json_schema").put("schema", schema))
            )

    /** thinking 블록이 앞에 올 수 있으므로 위치가 아니라 type 으로 text 블록을 찾는다. */
    private fun parse(body: String): AiResult {
        val message = JSONObject(body)
        when (message.optString("stop_reason")) {
            "refusal" -> return AiResult.Failure(AiFailureKind.REFUSED)
            "max_tokens" -> return AiResult.Failure(AiFailureKind.TRUNCATED)
        }
        val content = message.getJSONArray("content")
        val text = StringBuilder()
        for (i in 0 until content.length()) {
            val block = content.getJSONObject(i)
            if (block.optString("type") == "text") text.append(block.getString("text"))
        }
        if (text.isEmpty()) return AiResult.Failure(AiFailureKind.BAD_FORMAT)
        return AiResult.Success(JSONObject(text.toString()))
    }

    companion object {
        const val ENDPOINT = "https://api.anthropic.com/v1/messages"
        const val API_VERSION = "2023-06-01"
        const val MODEL = "claude-haiku-5-5"

        /** thinking 이 max_tokens 를 함께 쓴다. 실제 호출에서 1024 는 통계가 들어간 입력에 잘렸다(TRUNCATED). */
        const val MAX_TOKENS = 4096

        /** Haiku 5.5 는 thinking 이 기본으로 켜진다. 짧은 판단이라 낮춰 지연과 토큰을 줄인다. */
        private const val EFFORT = "low"
    }
}
