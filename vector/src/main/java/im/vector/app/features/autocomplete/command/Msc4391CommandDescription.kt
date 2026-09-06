/*
 * Copyright 2026 Voyage Client
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.autocomplete.command

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.toModel

const val MSC4391_COMMAND_DESCRIPTION_EVENT_TYPE = "org.matrix.msc4391.command_description"

@JsonClass(generateAdapter = true)
internal data class Msc4391CommandDescriptionContent(
        val command: String? = null,
        val aliases: List<String>? = null,
        val parameters: List<Msc4391CommandParameter>? = null,
        val description: Msc4391ExtensibleText? = null,
        @Json(name = "fi.mau.tail_parameter") val tailParameter: String? = null,
)

@JsonClass(generateAdapter = true)
internal data class Msc4391CommandParameter(
        val key: String? = null,
        val optional: Boolean = false,
        val schema: Msc4391CommandParameterSchema? = null,
)

@JsonClass(generateAdapter = true)
internal data class Msc4391CommandParameterSchema(
        @Json(name = "schema_type") val schemaType: String? = null,
        val type: String? = null,
        val value: Any? = null,
        val variants: List<Msc4391CommandParameterSchema>? = null,
        val items: Msc4391CommandParameterSchema? = null,
)

@JsonClass(generateAdapter = true)
internal data class Msc4391ExtensibleText(
        @Json(name = "m.text") val text: List<Msc4391TextRepresentation>? = null,
)

@JsonClass(generateAdapter = true)
internal data class Msc4391TextRepresentation(
        val body: String? = null,
        val mimetype: String? = null,
)

fun Event.toMsc4391AutocompleteCommand(): AutocompleteCommand? {
    val command = toMsc4391CommandSpec() ?: return null
    val parameters = command.parameters.mapNotNull { parameter ->
        parameter.key?.takeIf { it.isNotBlank() }?.let {
            if (parameter.optional) "[$it]" else "<$it>"
        }
    }.joinToString(" ")
    val description = command.description?.text
            ?.firstOrNull { it.mimetype == null || it.mimetype == "text/plain" }
            ?.body.orEmpty()
    return AutocompleteCommand(
            id = eventId ?: "${stateKey.orEmpty()}:${command.command}",
            command = "/${command.command}",
            aliases = command.aliases.orEmpty().mapNotNull { it.trim().removePrefix("/").takeIf(String::isNotEmpty) }.map { "/$it" },
            sourceUserId = command.sourceUserId,
            parameters = parameters,
            description = description,
    )
}

internal data class Msc4391CommandSpec(
        val command: String,
        val aliases: List<String>?,
        val parameters: List<Msc4391CommandParameter>,
        val description: Msc4391ExtensibleText?,
        val tailParameter: String?,
        val sourceUserId: String?,
)

internal fun Event.toMsc4391CommandSpec(): Msc4391CommandSpec? {
    val content = content.toModel<Msc4391CommandDescriptionContent>() ?: return null
    val command = content.command?.trim()?.removePrefix("/")?.takeIf { it.isNotEmpty() } ?: return null
    val sourceUserId = senderId ?: stateKey?.takeIf { it.startsWith("@") }
    if (sourceUserId == null || content.parameters.orEmpty().mapNotNull { it.key }.groupingBy { it }.eachCount().any { it.value > 1 }) return null
    return Msc4391CommandSpec(command, content.aliases, content.parameters.orEmpty(), content.description, content.tailParameter, sourceUserId)
}

fun Event.msc4391CommandContent(input: String, argumentsText: String): Map<String, Any>? {
    val command = toMsc4391CommandSpec() ?: return null
    val requestedCommand = input.substringBefore('@')
    val requestedSource = input.substringAfter('@', missingDelimiterValue = "").takeIf { it.isNotBlank() }
    if (requestedSource != null && requestedSource != command.sourceUserId) return null
    val aliases = command.aliases.orEmpty().map { "/${it.removePrefix("/")}" }
    if ((listOf("/${command.command}") + aliases).none { it.equals(requestedCommand, ignoreCase = true) }) return null
    val values = parseCommandArguments(argumentsText.trim().substringAfterFirstWhitespace())
    val arguments = linkedMapOf<String, Any>()
    command.parameters.forEachIndexed { index, parameter ->
        val key = parameter.key ?: return null
        val namedIndex = values.indexOfFirst { it == "--$key" || it.startsWith("--$key=") }
        val namedValue = if (namedIndex >= 0) {
            val named = values.removeAt(namedIndex)
            named.substringAfter('=', "").takeIf { '=' in named } ?: values.removeFirstOrNull()
        } else {
            null
        }
        if (namedIndex < 0 && parameter.optional && key != command.tailParameter) return@forEachIndexed
        val isLastRequiredParameter = command.parameters.drop(index + 1).none { !it.optional || it.key == command.tailParameter }
        val positionalValue = if (isLastRequiredParameter && parameter.schema?.schemaType != "array") {
            values.joinToString(" ").also { values.clear() }.takeIf { it.isNotEmpty() }
        } else {
            values.removeFirstOrNull()
        }
        val rawValue = namedValue ?: positionalValue
        val parsedValue = parameter.schema?.parse(rawValue)
        if (parsedValue == null && !parameter.optional) return null
        if (parsedValue != null) arguments[key] = parsedValue
    }
    return mapOf(
            "org.matrix.msc4391.command" to mapOf(
                    "command" to command.command,
                    "arguments" to arguments,
            ),
            "m.mentions" to mapOf(
                    "user_ids" to listOfNotNull(command.sourceUserId),
                    "room" to false,
            ),
    )
}

private fun String.substringAfterFirstWhitespace(): String {
    val index = indexOfFirst(Char::isWhitespace)
    return if (index == -1) "" else substring(index + 1)
}

private fun parseCommandArguments(input: String): MutableList<String> {
    return Regex("(?:\\\"(?:\\\\.|[^\\\"])*\\\"|\\S+)").findAll(input.trim())
            .map { it.value.removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\") }
            .toMutableList()
}

private fun Msc4391CommandParameterSchema.parse(value: String?): Any? {
    if (value == null) return null
    return when (schemaType) {
        "primitive" -> when (type) {
            "integer" -> value.toLongOrNull()
            "boolean" -> when (value.lowercase()) {
                "true" -> true
                "false" -> false
                else -> null
            }
            else -> value
        }
        "literal" -> value.takeIf { literal ->
            when (val expected = this.value) {
                is String,
                is Number,
                is Boolean -> expected.toString() == literal
                else -> false
            }
        }?.let { this.value }
        "union" -> variants.orEmpty().firstNotNullOfOrNull { it.parse(value) }
        "array" -> value.split(',').mapNotNull { items?.parse(it) }
        else -> value
    }
}
