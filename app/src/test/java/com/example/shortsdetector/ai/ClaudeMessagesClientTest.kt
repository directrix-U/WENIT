package com.example.shortsdetector.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeMessagesClientTest {

    private val key = "sk-test-secret"
    private val logs = ArrayList<String>()

    private fun call(status: Int, body: String): AiResult =
        ClaudeMessagesClient(key, { _, _, _ -> AiHttpResponse(status, body) }, logs::add)
            .requestJson("system", "user", JSONObject())

    private fun errorBody(type: String, message: String) =
        JSONObject().put("type", "error")
            .put("error", JSONObject().put("type", type).put("message", message))
            .toString()

    @Test
    fun `http error logs the api error type and message`() {
        val result = call(400, errorBody("invalid_request_error", "Your credit balance is too low"))

        assertEquals(AiResult.Failure(AiFailureKind.HTTP_ERROR, 400), result)
        assertEquals(
            listOf("HTTP 400 type=invalid_request_error message=Your credit balance is too low"),
            logs
        )
    }

    @Test
    fun `api key in an error message is masked`() {
        call(401, errorBody("authentication_error", "invalid x-api-key $key"))

        assertFalse(logs.single().contains(key))
        assertTrue(logs.single().contains("***"))
    }

    @Test
    fun `other body fields are not logged`() {
        call(400, """{"error":{"type":"t","message":"m"},"echo":"raw-input"}""")

        assertFalse(logs.single().contains("raw-input"))
    }

    @Test
    fun `long message is cut`() {
        call(400, errorBody("t", "x".repeat(5_000)))

        assertTrue(logs.single().length < 400)
    }

    @Test
    fun `body that is not an api error still gives a log line`() {
        call(502, "<html>bad gateway</html>")
        call(400, "")

        assertEquals("HTTP 502 type=unknown message=(본문에 오류 메시지 없음)", logs[0])
        assertEquals("HTTP 400 type=unknown message=(본문에 오류 메시지 없음)", logs[1])
    }

    @Test
    fun `success logs nothing`() {
        val body = JSONObject()
            .put("stop_reason", "end_turn")
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", "{}")))
        call(200, body.toString())

        assertTrue(logs.isEmpty())
    }
}
