package com.coda.workbench.core.rules

import java.text.Normalizer

object TextRules {
    private val builtInSynonyms = mapOf(
        "信号弱" to setOf("信号弱", "信号强度偏弱"),
        "回波弱" to setOf("回波弱", "有效回波偏弱"),
    )

    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replace(Regex("\\s+"), " ")

    /**
     * 同义词扩展（产品稿 §9 / 技术稿 §8）：原文规范化后恒在结果集中；
     * 查询整词命中同义词键时返回同义集合；查询包含键（如"一号雷达 信号弱"）时追加替换变体；
     * 扩展失败时只返回原文匹配结果。
     */
    fun expandedTerms(query: String): Set<String> {
        val normalized = normalize(query)
        val result = linkedSetOf(normalized)
        builtInSynonyms.forEach { (key, expansions) ->
            if (normalized.contains(key)) {
                expansions.forEach { expansion ->
                    if (expansion != key) result += normalized.replace(key, expansion)
                }
            }
        }
        return result.toSet()
    }

    fun escapeLike(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\', '%', '_' -> append('\\').append(character)
                else -> append(character)
            }
        }
    }

    fun containsLikePattern(query: String): String = "%${escapeLike(normalize(query))}%"
}
