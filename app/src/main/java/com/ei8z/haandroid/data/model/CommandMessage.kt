package com.ei8z.haandroid.data.model

import kotlinx.serialization.Serializable

@Serializable
data class CommandMessage(
    val command: String,
    val target: String,
    val parameters: Map<String, kotlinx.serialization.json.JsonElement>,
    val priority: String = "normal",
    val timestamp: Long
)
