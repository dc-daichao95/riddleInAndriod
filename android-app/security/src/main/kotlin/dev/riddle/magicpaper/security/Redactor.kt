package dev.riddle.magicpaper.security

object Redactor {
    private val authorization = Regex(
        "(?i)(authorization\\s*[:=]\\s*)[^\\r\\n]*?(?=\\s+[a-z][a-z0-9_-]*\\s*[:=]|$)",
    )

    fun redact(input: String, secrets: Set<String> = emptySet()): String {
        val withoutSecrets = secrets.filter(String::isNotEmpty).fold(input) { text, secret ->
            text.replace(secret, "[REDACTED]")
        }
        return authorization.replace(withoutSecrets) { match ->
            match.groupValues[1] + "[REDACTED]"
        }
    }
}
