package com.example.mochi_pet.core.voice

data class SpeechVoice(
    val id: String,
    val name: String,
    val languageTag: String,
    val category: String = "",
)

val IFLYTEK_BASIC_VOICES = listOf(
    SpeechVoice("x4_xiaoyan", "Xiaoyan", "zh-CN", "Basic voice"),
    SpeechVoice("x4_yezi", "Yezi", "zh-CN", "Basic voice"),
    SpeechVoice("aisjiuxu", "Xu Jiu", "zh-CN", "Basic voice"),
    SpeechVoice("aisjinger", "Xiao Jing", "zh-CN", "Basic voice"),
    SpeechVoice("aisbabyxu", "Xu Xiaobao", "zh-CN", "Basic voice"),
)
