package tanvd.grazi.grammar

import com.intellij.openapi.progress.ProgressManager
import tanvd.grazi.GraziConfig
import tanvd.grazi.language.LangDetector
import tanvd.grazi.language.LangTool
import tanvd.grazi.spellcheck.GraziSpellchecker
import tanvd.grazi.utils.*

object GrammarEngine {
    private const val maxChars = 10_000
    private const val minChars = 2

    private val separators = listOf('\n', '?', '!', '.', ';', ',', ' ', '\t')

    /** Grammar checker will perform only spellcheck for sentences with fewer words */
    private const val minNumberOfWords = 3

    private fun isSmall(str: String) = str.length < minChars
    private fun isBig(str: String) = str.length > maxChars

    fun getFixes(str: String, seps: List<Char> = separators): Set<Typo> = buildSet {
        if (str.isBlankWithNewLines()) return@buildSet

        if (str.split(Regex("\\s+")).size < minNumberOfWords) {
            addAll(GraziSpellchecker.check(str))
            return@buildSet
        }


        val curSeparator = seps.first()

        for ((range, sentence) in str.splitWithRanges(curSeparator)) {
            val stringFixes = if (isBig(sentence) && seps.isNotEmpty()) {
                getFixes(sentence, seps.dropFirst())
            } else {
                getFixesSmall(sentence)
            }.map {
                Typo(it.location.withOffset(range.start), it.info, it.fixes)
            }
            addAll(stringFixes)
        }
    }

    private fun getFixesSmall(str: String) = buildSet<Typo> {
        if (isSmall(str)) return@buildSet

        val lang = LangDetector.getLang(str, GraziConfig.state.enabledLanguages.toList()) ?: return@buildSet

        val allFixes = tryRun { LangTool[lang].check(str) }
                .orEmpty()
                .filterNotNull()
                .map { Typo(it, lang) }
                .let { LinkedHashSet(it) }

        ProgressManager.checkCanceled()

        val withoutTypos = allFixes.filterNot { it.info.rule.isDictionaryBasedSpellingRule }.toSet()
        val verifiedTypos = allFixes.filter { it.info.rule.isDictionaryBasedSpellingRule }.filter {
            !lang.isEnglish() || str.subSequence(it.location.range).split(Regex("\\s")).flatMap { part -> GraziSpellchecker.check(part) }.isNotEmpty()
        }.toSet()

        if (GraziConfig.state.enabledSpellcheck) {
            addAll(withoutTypos + verifiedTypos)
        } else {
            addAll(withoutTypos)
        }
    }
}