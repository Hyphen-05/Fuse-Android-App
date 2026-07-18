package com.example

import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.TextPart

fun test() {
    val req = GenerateContentRequest.Builder(TextPart("test"))
        .apply { maxOutputTokens = 2000 }
        .build()
}
