package com.sportenth.data.util

private val SENSITIVE_FIELDS = Regex(
    """"(password|token|secret|apiKey|api_key|code|refreshToken|refresh_token|accessToken|access_token)"\s*:\s*"[^"]*"""",
    RegexOption.IGNORE_CASE
)

private const val MAX_LOGGED_BODY = 2_000

fun maskSensitive(body: String): String {
    val masked = SENSITIVE_FIELDS.replace(body) { match ->
        val field = match.groupValues[1]
        """"$field":"***""""
    }
    return if (masked.length > MAX_LOGGED_BODY) masked.take(MAX_LOGGED_BODY) + "...[truncated]" else masked
}
