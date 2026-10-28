package com.idlix.data.util

data class CleanedTitle(
    val title: String,
    val year: Int? = null
)

object SubdlTitleCleaner {

    private val YEAR_PARENS_REGEX = Regex("""\s*[\(\[]\s*(\d{4})\s*[\)\]]\s*""")
    private val YEAR_AT_END_REGEX = Regex("""\b(19\d{2}|20\d{2})\b\s*$""")
    private val NOISE_PATTERNS = listOf(
        Regex("""(?i)\bseason\s*\d+\b"""),
        Regex("""(?i)\bs\d+\s*e\d+\b"""),
        Regex("""(?i)\bs\d+\b"""),
        Regex("""(?i)\bepisode\s*\d+\b"""),
        Regex("""(?i)\beps?\.?\s*\d+\b"""),
        Regex("""(?i)\bsub\s*indo\b"""),
        Regex("""(?i)\bsubtitle\s*indonesia\b"""),
        Regex("""(?i)\[\s*(?:hd|cam|web-?dl|bluray|dvdrip|1080p|720p|480p)\s*\]"""),
        Regex("""(?i)\(\s*(?:hd|cam|web-?dl|bluray|dvdrip|1080p|720p|480p)\s*\)""")
    )
    private val TRAILING_DASH_NOISE = Regex("""\s*-\s*(?:episode|season|eps)?\s*\d*\s*$""", RegexOption.IGNORE_CASE)

    fun clean(rawTitle: String): CleanedTitle {
        var text = rawTitle.trim()
        var extractedYear: Int? = null

        // 1. Extract year from parentheses (e.g., "(2024)")
        val parensMatch = YEAR_PARENS_REGEX.find(text)
        if (parensMatch != null) {
            extractedYear = parensMatch.groupValues[1].toIntOrNull()
            text = text.replaceRange(parensMatch.range, " ").trim()
        } else {
            // 2. Check for standalone 4-digit year at end (e.g. "Dune 2024")
            val endMatch = YEAR_AT_END_REGEX.find(text)
            if (endMatch != null) {
                extractedYear = endMatch.groupValues[1].toIntOrNull()
                text = text.replaceRange(endMatch.range, " ").trim()
            }
        }

        // 3. Remove series / episode / tag noise
        for (pattern in NOISE_PATTERNS) {
            text = text.replace(pattern, " ")
        }

        // 4. Remove trailing dash noise (e.g. " - Episode 2" or " -")
        text = text.replace(TRAILING_DASH_NOISE, "")

        // 5. Collapse duplicate whitespace
        val cleaned = text.replace(Regex("""\s+"""), " ").trim(' ', '-', ':', '_')

        return CleanedTitle(
            title = cleaned.ifBlank { rawTitle.trim() },
            year = extractedYear
        )
    }
}
