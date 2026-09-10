package com.hagemon.squiz.data.excel

data class DelimiterCandidate(
    val delimiter: String,
    val matchedRows: Int,
    val totalRows: Int,
    val commonOptionCount: Int,
    val confidence: Double,
)

object OptionTextParser {
    fun detectDelimiters(values: List<String>): List<DelimiterCandidate> {
        val nonBlankValues = values.map(String::trim).filter(String::isNotBlank)
        if (nonBlankValues.isEmpty()) return emptyList()
        val samples = if (nonBlankValues.size <= MAX_SAMPLES) {
            nonBlankValues
        } else {
            List(MAX_SAMPLES) { index ->
                nonBlankValues[index * (nonBlankValues.lastIndex) / (MAX_SAMPLES - 1)]
            }
        }
        val candidates = linkedSetOf(
            "\n", "\t", "|", "||", ";", "；", ",", "，", "#", "##", "@", "@#", "~", "^", "§", "◆", "◇", "●", "•",
        )
        val symbolRun = Regex("[^\\p{L}\\p{N}\\s]{1,8}")
        samples.forEach { value ->
            symbolRun.findAll(value).forEach { match ->
                if (candidates.size < MAX_TRACKED_CANDIDATES) {
                    match.value.takeIf { token -> token !in ignoredDynamicTokens }?.let(candidates::add)
                }
            }
        }
        val minimumMatches = if (samples.size < 3) 1 else 2
        return candidates.mapNotNull { delimiter ->
            val partCounts = samples.mapNotNull { value ->
                rawParts(value, delimiter).size.takeIf { it in 2..MAX_OPTIONS }
            }
            if (partCounts.size < minimumMatches) return@mapNotNull null
            val commonCount = partCounts.groupingBy { it }.eachCount().maxByOrNull { it.value } ?: return@mapNotNull null
            val coverage = partCounts.size.toDouble() / samples.size
            val stability = commonCount.value.toDouble() / partCounts.size
            DelimiterCandidate(
                delimiter = delimiter,
                matchedRows = partCounts.size,
                totalRows = samples.size,
                commonOptionCount = commonCount.key,
                confidence = coverage * 0.72 + stability * 0.28,
            )
        }.sortedWith(
            compareByDescending<DelimiterCandidate> { it.confidence }
                .thenByDescending { it.matchedRows }
                .thenByDescending { it.delimiter.length },
        ).take(MAX_CANDIDATES)
    }

    fun split(raw: String, delimiter: String): List<Pair<String, String>> =
        labelParts(rawParts(raw, delimiter))

    fun splitByLabels(raw: String): List<Pair<String, String>> {
        val matches = optionBoundary.findAll(raw).toList()
        if (matches.size < 2 || !matches.first().groupValues[1].equals("A", ignoreCase = true)) return emptyList()
        return matches.mapIndexedNotNull { index, match ->
            val label = match.groupValues[1].uppercase()
            val end = matches.getOrNull(index + 1)?.range?.first ?: raw.length
            raw.substring(match.range.last + 1, end).trim()
                .takeIf(String::isNotBlank)
                ?.let { label to it }
        }.take(MAX_OPTIONS).takeIf { parts -> parts.map { it.first }.distinct().size == parts.size }.orEmpty()
    }

    fun hasLabeledOptions(raw: String): Boolean = splitByLabels(raw).size >= 2

    private fun rawParts(raw: String, delimiter: String): List<String> = when (delimiter) {
        "\n" -> raw.lines()
        "\t" -> raw.split('\t')
        "" -> listOf(raw)
        else -> raw.split(delimiter)
    }.map(String::trim).filter(String::isNotBlank)

    private fun labelParts(parts: List<String>): List<Pair<String, String>> {
        val limited = parts.take(MAX_OPTIONS)
        val parsed = limited.map { part ->
            optionPrefix.matchEntire(part)?.let { match ->
                match.groupValues[1].uppercase() to match.groupValues[2].trim()
            }
        }
        if (parsed.all { it != null } && parsed.filterNotNull().map { it.first }.distinct().size == parsed.size) {
            return parsed.filterNotNull().filter { it.second.isNotBlank() }
        }
        return limited.mapIndexedNotNull { index, part ->
            val text = optionPrefix.matchEntire(part)?.groupValues?.get(2)?.trim() ?: part
            text.takeIf(String::isNotBlank)?.let { ('A' + index).toString() to it }
        }
    }

    private val optionPrefix = Regex("^([A-Za-z])(?:\\s*[.\\u3001:：)）-]\\s*|\\s+)(.+)$")
    private val optionBoundary = Regex("(?i)([A-Z])(?:\\s*[.\\u3001:：)）-]\\s*|\\s+)")
    private val ignoredDynamicTokens = setOf(".", "。", ":", "：", ")", "）", "(", "（", "-", "_")
    private const val MAX_OPTIONS = 26
    private const val MAX_CANDIDATES = 8
    private const val MAX_SAMPLES = 2_000
    private const val MAX_TRACKED_CANDIDATES = 48
}
