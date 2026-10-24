package com.englishcoach.llm

import com.englishcoach.core.model.Scenario

/**
 * System prompt 拼装器：
 * chat = 场景角色 + 通用对话规则；correct / summarize / translate 各有独立指令。
 */
class PromptBuilder {

    /** 场景角色 + 对话规则 */
    fun chatSystemPrompt(scenario: Scenario): String {
        return scenario.systemPrompt.trim() + "\n\n" + CHAT_RULES
    }

    /** 沉浸模式结束复盘：让 AI 批改本轮全部用户发言，返回 JSON 数组 */
    fun summarizeSystemPrompt(scenario: Scenario): String {
        return "You are reviewing an English practice conversation in this scenario:\n" +
            "\"\"\"\n" + scenario.systemPrompt.trim() + "\n\"\"\"\n\n" + SUMMARIZE_RULES
    }

    /** 一键中文翻译 */
    fun translationSystemPrompt(): String {
        return "You are a translator. Translate the user's English text into natural, concise Simplified Chinese. " +
            "Reply with the Chinese translation ONLY — no explanations, no pinyin, no original text."
    }

    companion object {
        /**
         * 纠错专用 system prompt（correct() 用）。
         * type 增加了 NONE：没毛病时也返回合法 JSON，调用方按 NONE 跳过纠错条。
         * notable_words：顺带收集值得学的词（PM v2 需求2），每轮 ≤3 个，无则空数组。
         */
        const val CORRECTION_SYSTEM_PROMPT = """
            You are an English coach for a Chinese learner. Grade the learner's spoken English sentence.
            Focus on: grammar mistakes, unnatural wording (what a native speaker would actually say), and possible mispronunciations inferred from speech recognition errors.
            Respond with ONLY a JSON object, no markdown, no extra text, in exactly this shape:
            {"original": "<the learner's sentence verbatim>", "fixed": "<the corrected or more natural sentence>", "type": "GRAMMAR|WORDING|PRONUNCIATION|NONE", "note": "<one short Chinese explanation, <=40 chars>", "notable_words": [{"word": "<single word or short phrase>", "phonetic": "<IPA, e.g. /ˈsænwɪdʒ/>", "meaning": "<Chinese: part of speech + meaning, e.g. n. 三明治>", "example": "<one short English sentence using it>"}]}
            Rules:
            - If the sentence is already correct and natural, set "fixed" equal to "original" and "type" to "NONE", with a short encouraging Chinese note.
            - "note" must be in Chinese, brief and specific (point out the exact problem).
            - Do not change the meaning of the sentence when fixing it.
            - "notable_words": at most 3 items — words from the learner's sentence they got wrong or used unnaturally, plus high-frequency spoken words worth learning in this context. Include phonetic (IPA), a short Chinese meaning ("n. 三明治" style), and one short English example. Return [] when there is nothing worth noting.
        """

        /** chat 的通用对话规则：角色扮演 + 简短口语 + 不在回复里纠错（纠错走独立请求） */
        private val CHAT_RULES = """
            General rules:
            - Stay fully in character. Never mention that you are an AI or a language coach.
            - Reply in natural, conversational English only. NO Chinese in your reply.
            - Keep each reply SHORT: 1-3 sentences (spoken style, easy to say out loud).
            - React to what the user said, then move the conversation forward with a natural follow-up question or prompt.
            - Do NOT correct the user's mistakes inside your reply. Corrections are handled separately by another system.
        """.trimIndent()

        /** 复盘规则：返回 JSON 数组 */
        private val SUMMARIZE_RULES = """
            Summarize the learner's mistakes in this practice session.
            Respond with ONLY a JSON array (no markdown, no extra text), each item shaped like:
            {"original": "...", "fixed": "...", "type": "GRAMMAR|WORDING|PRONUNCIATION|NONE", "note": "<one short Chinese explanation>"}
            Rules:
            - Only include sentences that actually have grammar/wording/pronunciation issues (max 5, pick the most instructive ones).
            - If everything was fine, return [].
            - "note" must be in Chinese.
        """.trimIndent()
    }
}
