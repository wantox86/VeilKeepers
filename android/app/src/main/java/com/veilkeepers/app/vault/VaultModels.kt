package com.veilkeepers.app.vault

import org.json.JSONArray
import org.json.JSONObject

/** Display title for items whose payload lacks a (usable) title. */
const val UNTITLED = "(untitled)"

/**
 * One label/value row inside a vault item payload.
 *
 * [isSecret] (Sprint 6, spec.md §22): secrets render masked by default with
 * show/hide/copy affordances. Wire encoding is ADDITIVE — the payload only
 * gains `"secret":true` when set; old blobs (no key) parse as non-secret.
 */
data class VaultField(val label: String, val value: String, val isSecret: Boolean = false)

/** Decrypted domain view of a backend category entry. */
data class DecryptedCategory(
    val id: Long,
    val name: String,
    val itemCount: Int,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * Decrypted domain view of a backend vault item.
 *
 * [undecryptable] marks blobs that failed GCM authentication or payload
 * parsing (wrong VK, tampering, foreign data): [title] then carries a static
 * generic message and [notes]/[fields] stay empty — the raw blob or the
 * exception detail is NEVER surfaced or logged.
 */
data class DecryptedItem(
    val id: Long,
    /** null = Uncategorized (backend `category_id: null`). */
    val categoryId: Long?,
    val title: String,
    val notes: String,
    val fields: List<VaultField>,
    val undecryptable: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

/** Decrypted item page plus the contract's warning-only `has_more` flag. */
data class DecryptedItemList(val items: List<DecryptedItem>, val hasMore: Boolean)

/** Whole decrypted vault snapshot (one parallel categories+items fetch). */
data class VaultSnapshot(
    val categories: List<DecryptedCategory>,
    val items: DecryptedItemList,
    val hasMoreWarning: Boolean,
)

/**
 * Internal JSON schema V1 of the encrypted vault item payload
 * (spec-1.md §C, evolutionary):
 *
 *     {"v":1,"title":str,"notes":str,"fields":[{"label":str,"value":str,"secret":bool?}]}
 *
 * The title lives INSIDE the payload (spec-1.md §A.3) — there is no title
 * column on the server, which only ever stores the opaque ciphertext.
 * Parsing is intentionally lenient: unknown keys are ignored (forward
 * compatibility), a missing/blank title becomes [UNTITLED]. Sprint 6: the
 * per-field `"secret"` flag is optional — written ONLY when true, parsed
 * as false when missing (old payloads unchanged; the schema stays V1).
 */
object ItemPayload {
    /** Schema version written by [encode]. */
    const val VERSION = 1

    /** Serializes one payload; the result is what gets GCM-encrypted. */
    fun encode(title: String, notes: String, fields: List<VaultField>): String {
        val jsonFields = JSONArray()
        for (field in fields) {
            val jsonField = JSONObject().put("label", field.label).put("value", field.value)
            // Additive V1 extension: emit the flag ONLY when true so blobs of
            // non-secret fields stay byte-compatible with Sprint 5 output.
            if (field.isSecret) jsonField.put("secret", true)
            jsonFields.put(jsonField)
        }
        return JSONObject()
            .put("v", VERSION)
            .put("title", title)
            .put("notes", notes)
            .put("fields", jsonFields)
            .toString()
    }

    /** Parsed payload content. */
    data class Content(val title: String, val notes: String, val fields: List<VaultField>)

    /**
     * Lenient parse. Throws [org.json.JSONException] when [json] is not a
     * JSON object — callers treat that as undecryptable, never crash.
     */
    fun parse(json: String): Content {
        val obj = JSONObject(json)
        val title = obj.optString("title", "").takeIf { it.isNotEmpty() } ?: UNTITLED
        val notes = obj.optString("notes", "")
        val fields = mutableListOf<VaultField>()
        obj.optJSONArray("fields")?.let { arr ->
            for (i in 0 until arr.length()) {
                val entry = arr.optJSONObject(i) ?: continue
                fields.add(
                    VaultField(
                        entry.optString("label", ""),
                        entry.optString("value", ""),
                        // Missing key → false: old blobs parse unchanged.
                        entry.optBoolean("secret", false),
                    )
                )
            }
        }
        return Content(title, notes, fields)
    }
}
