package com.metrolist.desktop.lyrics

import java.text.Normalizer

object DesktopLyricsRomanizer {
    private val HIRAGANA_TO_KATAKANA_OFFSET = 0x30A1 - 0x3041

    private val KANA_MAP = linkedMapOf(
        "キャ" to "kya", "キュ" to "kyu", "キョ" to "kyo",
        "シャ" to "sha", "シュ" to "shu", "ショ" to "sho",
        "チャ" to "cha", "チュ" to "chu", "チョ" to "cho",
        "ニャ" to "nya", "ニュ" to "nyu", "ニョ" to "nyo",
        "ヒャ" to "hya", "ヒュ" to "hyu", "ヒョ" to "hyo",
        "ミャ" to "mya", "ミュ" to "myu", "ミョ" to "myo",
        "リャ" to "rya", "リュ" to "ryu", "リョ" to "ryo",
        "ギャ" to "gya", "ギュ" to "gyu", "ギョ" to "gyo",
        "ジャ" to "ja", "ジュ" to "ju", "ジョ" to "jo",
        "ヂャ" to "ja", "ヂュ" to "ju", "ヂョ" to "jo",
        "ビャ" to "bya", "ビュ" to "byu", "ビョ" to "byo",
        "ピャ" to "pya", "ピュ" to "pyu", "ピョ" to "pyo",
        "ア" to "a", "イ" to "i", "ウ" to "u", "エ" to "e", "オ" to "o",
        "カ" to "ka", "キ" to "ki", "ク" to "ku", "ケ" to "ke", "コ" to "ko",
        "サ" to "sa", "シ" to "shi", "ス" to "su", "セ" to "se", "ソ" to "so",
        "タ" to "ta", "チ" to "chi", "ツ" to "tsu", "テ" to "te", "ト" to "to",
        "ナ" to "na", "ニ" to "ni", "ヌ" to "nu", "ネ" to "ne", "ノ" to "no",
        "ハ" to "ha", "ヒ" to "hi", "フ" to "fu", "ヘ" to "he", "ホ" to "ho",
        "マ" to "ma", "ミ" to "mi", "ム" to "mu", "メ" to "me", "モ" to "mo",
        "ヤ" to "ya", "ユ" to "yu", "ヨ" to "yo",
        "ラ" to "ra", "リ" to "ri", "ル" to "ru", "レ" to "re", "ロ" to "ro",
        "ワ" to "wa", "ヲ" to "o", "ン" to "n",
        "ガ" to "ga", "ギ" to "gi", "グ" to "gu", "ゲ" to "ge", "ゴ" to "go",
        "ザ" to "za", "ジ" to "ji", "ズ" to "zu", "ゼ" to "ze", "ゾ" to "zo",
        "ダ" to "da", "ヂ" to "ji", "ヅ" to "zu", "デ" to "de", "ド" to "do",
        "バ" to "ba", "ビ" to "bi", "ブ" to "bu", "ベ" to "be", "ボ" to "bo",
        "パ" to "pa", "ピ" to "pi", "プ" to "pu", "ペ" to "pe", "ポ" to "po",
        "ァ" to "a", "ィ" to "i", "ゥ" to "u", "ェ" to "e", "ォ" to "o",
        "ャ" to "ya", "ュ" to "yu", "ョ" to "yo", "ー" to "",
    )

    private val HANGUL_INITIALS = arrayOf(
        "g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h",
    )
    private val HANGUL_MEDIALS = arrayOf(
        "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i",
    )
    private val HANGUL_FINALS = arrayOf(
        "", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "p", "t", "t", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t",
    )

    private val DEVANAGARI_CONSONANTS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "ng",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "ny",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
        'क़' to "q", 'ख़' to "kh", 'ग़' to "g", 'ज़' to "z", 'ड़' to "r", 'ढ़' to "rh", 'फ़' to "f",
    )
    private val DEVANAGARI_VOWELS = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au",
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo",
        'ृ' to "ri", 'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au",
        'ं' to "n", 'ँ' to "n", 'ः' to "h", '्' to "", '़' to "",
    )

    private val GURMUKHI_CONSONANTS = mapOf(
        'ਕ' to "k", 'ਖ' to "kh", 'ਗ' to "g", 'ਘ' to "gh", 'ਙ' to "ng",
        'ਚ' to "ch", 'ਛ' to "chh", 'ਜ' to "j", 'ਝ' to "jh", 'ਞ' to "ny",
        'ਟ' to "t", 'ਠ' to "th", 'ਡ' to "d", 'ਢ' to "dh", 'ਣ' to "n",
        'ਤ' to "t", 'ਥ' to "th", 'ਦ' to "d", 'ਧ' to "dh", 'ਨ' to "n",
        'ਪ' to "p", 'ਫ' to "ph", 'ਬ' to "b", 'ਭ' to "bh", 'ਮ' to "m",
        'ਯ' to "y", 'ਰ' to "r", 'ਲ' to "l", 'ਵ' to "v", 'ੜ' to "r", 'ਸ' to "s", 'ਹ' to "h",
        'ਸ਼' to "sh", 'ਖ਼' to "kh", 'ਗ਼' to "g", 'ਜ਼' to "z", 'ਫ਼' to "f",
    )
    private val GURMUKHI_VOWELS = mapOf(
        'ਅ' to "a", 'ੲ' to "e", 'ੳ' to "o",
        'ਾ' to "aa", 'ਿ' to "i", 'ੀ' to "ee", 'ੁ' to "u", 'ੂ' to "oo",
        'ੇ' to "e", 'ੈ' to "ai", 'ੋ' to "o", 'ੌ' to "au",
        'ੰ' to "n", 'ਂ' to "n", 'ੱ' to "", '੍' to "", '਼' to "",
    )

    private val CYRILLIC_BASE = mapOf(
        'А' to "A", 'Б' to "B", 'В' to "V", 'Г' to "G", 'Ґ' to "G", 'Д' to "D",
        'Ѓ' to "Gj", 'Ђ' to "Dj", 'Е' to "E", 'Ё' to "Yo", 'Є' to "Ye", 'Ж' to "Zh",
        'З' to "Z", 'Ѕ' to "Dz", 'И' to "I", 'І' to "I", 'Ї' to "Yi", 'Й' to "Y",
        'Ј' to "J", 'К' to "K", 'Л' to "L", 'Љ' to "Lj", 'М' to "M", 'Н' to "N",
        'Њ' to "Nj", 'О' to "O", 'П' to "P", 'Р' to "R", 'С' to "S", 'Т' to "T",
        'Ћ' to "C", 'У' to "U", 'Ў' to "U", 'Ф' to "F", 'Х' to "Kh", 'Ц' to "Ts",
        'Ч' to "Ch", 'Џ' to "Dz", 'Ш' to "Sh", 'Щ' to "Shch", 'Ъ' to "", 'Ы' to "Y",
        'Ь' to "", 'Э' to "E", 'Ю' to "Yu", 'Я' to "Ya", 'Ќ' to "Kj", 'Ө' to "O", 'Ү' to "U", 'Ң' to "Ng",
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'ґ' to "g", 'д' to "d",
        'ѓ' to "gj", 'ђ' to "dj", 'е' to "e", 'ё' to "yo", 'є' to "ye", 'ж' to "zh",
        'з' to "z", 'ѕ' to "dz", 'и' to "i", 'і' to "i", 'ї' to "yi", 'й' to "y",
        'ј' to "j", 'к' to "k", 'л' to "l", 'љ' to "lj", 'м' to "m", 'н' to "n",
        'њ' to "nj", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'ћ' to "c", 'у' to "u", 'ў' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
        'ч' to "ch", 'џ' to "dz", 'ш' to "sh", 'щ' to "shch", 'ъ' to "", 'ы' to "y",
        'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ќ' to "kj", 'ө' to "o", 'ү' to "u", 'ң' to "ng",
    )

    fun romanizeLine(line: String, enabledLanguages: Set<String>): String? {
        if (line.isBlank() || enabledLanguages.isEmpty()) return null
        val text = Normalizer.normalize(line, Normalizer.Form.NFC)
        val hasKana = text.any { it in '\u3040'..'\u30FF' }
        val hasHangul = text.any { it in '\uAC00'..'\uD7A3' }
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        val hasGurmukhi = text.any { it in '\u0A00'..'\u0A7F' }
        val hasCyrillic = text.any { it in '\u0400'..'\u04FF' }

        val cyrillicEnabled = enabledLanguages.any { it in setOf("ru", "uk", "sr", "bg", "be", "ky", "mk") }

        var result = text
        var changed = false
        if (hasKana && "ja" in enabledLanguages) {
            result = romanizeJapaneseKana(result)
            changed = true
        }
        if (hasHangul && "ko" in enabledLanguages) {
            result = romanizeKorean(result)
            changed = true
        }
        if (hasDevanagari && "hi" in enabledLanguages) {
            result = romanizeIndic(result, DEVANAGARI_CONSONANTS, DEVANAGARI_VOWELS, '्')
            changed = true
        }
        if (hasGurmukhi && "pa" in enabledLanguages) {
            result = romanizeIndic(result, GURMUKHI_CONSONANTS, GURMUKHI_VOWELS, '੍')
            changed = true
        }
        if (hasCyrillic && cyrillicEnabled) {
            result = romanizeCyrillic(result, enabledLanguages)
            changed = true
        }
        return if (changed && result != text) result else null
    }

    fun romanizeJapaneseKana(input: String): String {
        val katakana = buildString(input.length) {
            for (c in input) {
                if (c in '\u3041'..'\u3096') append((c.code + HIRAGANA_TO_KATAKANA_OFFSET).toChar()) else append(c)
            }
        }
        val sb = StringBuilder()
        var i = 0
        var doubleNext = false
        while (i < katakana.length) {
            val ch = katakana[i]
            if (ch == 'ッ') {
                doubleNext = true
                i++
                continue
            }
            val pair = if (i + 1 < katakana.length) katakana.substring(i, i + 2) else null
            val mapped = if (pair != null && KANA_MAP.containsKey(pair)) {
                i += 2
                KANA_MAP[pair]!!
            } else {
                i += 1
                KANA_MAP[ch.toString()] ?: ch.toString()
            }
            if (doubleNext && mapped.isNotEmpty() && mapped[0].isLetter()) {
                sb.append(mapped[0])
                doubleNext = false
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    fun romanizeKorean(input: String): String {
        val sb = StringBuilder()
        for (c in input) {
            if (c in '\uAC00'..'\uD7A3') {
                val sIdx = c.code - 0xAC00
                val cho = sIdx / (21 * 28)
                val jung = (sIdx % (21 * 28)) / 28
                val jong = sIdx % 28
                sb.append(HANGUL_INITIALS[cho]).append(HANGUL_MEDIALS[jung]).append(HANGUL_FINALS[jong])
            } else {
                sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun romanizeIndic(
        input: String,
        consonants: Map<Char, String>,
        vowels: Map<Char, String>,
        virama: Char,
    ): String {
        val sb = StringBuilder()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            val cons = consonants[c]
            if (cons != null) {
                sb.append(cons)
                val next = input.getOrNull(i + 1)
                if (next == virama) {
                    i += 2
                } else if (next != null && next in vowels) {
                    sb.append(vowels[next])
                    i += 2
                } else {
                    // Inherent schwa vowel 'a' unless end of word
                    val endOfWord = next == null || next.isWhitespace() || !next.isLetter()
                    if (!endOfWord) sb.append('a')
                    i += 1
                }
            } else {
                sb.append(vowels[c] ?: c)
                i += 1
            }
        }
        return sb.toString()
    }

    fun romanizeCyrillic(input: String, langs: Set<String>): String {
        val isUk = "uk" in langs && input.any { it in "іїєґІЇЄҐ" }
        val isSr = "sr" in langs && input.any { it in "ђјљњћџЂЈЉЊЋЏ" }
        val isBg = "bg" in langs && !isUk && !isSr && input.contains('ъ', ignoreCase = true)
        val sb = StringBuilder(input.length)
        for (c in input) {
            val mapped = when {
                isUk && c == 'г' -> "h"
                isUk && c == 'Г' -> "H"
                isBg && c == 'щ' -> "sht"
                isBg && c == 'Щ' -> "Sht"
                isBg && c == 'ъ' -> "a"
                isBg && c == 'Ъ' -> "A"
                isSr && c == 'ж' -> "z"
                isSr && c == 'ч' -> "c"
                isSr && c == 'ш' -> "s"
                else -> CYRILLIC_BASE[c] ?: c.toString()
            }
            sb.append(mapped)
        }
        return sb.toString()
    }

    fun filterCreditLines(raw: String): String {
        val stripRegexes = listOf(
            Regex("""^\[\d{1,2}:\d{2}\.\d{2,3}]"""),
            Regex("""^\{agent:[^}]+}"""),
            Regex("""^\{bg}"""),
            Regex("""^v\d+:"""),
        )
        return raw.lines().filter { line ->
            var text = line.trim()
            var changed = true
            while (changed) {
                val before = text.length
                stripRegexes.forEach { text = text.replaceFirst(it, "").trim() }
                changed = text.length < before
            }
            val lower = text.lowercase()
            !(lower.startsWith("synced by") ||
                lower.startsWith("lyrics by") ||
                lower.startsWith("music by") ||
                lower.startsWith("arranged by") ||
                lower.startsWith("composed by") ||
                lower.startsWith("written by") ||
                lower.startsWith("produced by"))
        }.joinToString("\n")
    }
}
