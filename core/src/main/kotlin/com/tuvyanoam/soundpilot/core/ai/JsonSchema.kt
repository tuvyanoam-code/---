package com.tuvyanoam.soundpilot.core.ai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** A tiny builder for the JSON schemas of tool inputs. */
object Schema {
    fun string(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) put("enum", buildJsonArray { enum.forEach { add(JsonPrimitive(it)) } })
    }

    fun integer(description: String, min: Int? = null, max: Int? = null): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
        if (min != null) put("minimum", min)
        if (max != null) put("maximum", max)
    }

    fun number(description: String): JsonObject = buildJsonObject {
        put("type", "number")
        put("description", description)
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }

    fun array(items: JsonObject, description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", items)
    }

    fun obj(description: String?, properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
        buildJsonObject {
            put("type", "object")
            if (description != null) put("description", description)
            put("properties", JsonObject(properties))
            if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
            put("additionalProperties", false)
        }
}

/** Converts kotlinx JSON into the plain Java maps, lists and values the Anthropic SDK accepts. */
fun JsonElement.toJava(): Any? = when (this) {
    is JsonNull -> null
    is JsonObject -> LinkedHashMap<String, Any?>().also { map -> forEach { (k, v) -> map[k] = v.toJava() } }
    is JsonArray -> map { it.toJava() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}
