package dev.ppiankov.pockettag

import android.content.Context
import android.content.SharedPreferences
import java.net.URLDecoder
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// WO-3: retain the original text of entries this version cannot interpret.
data class ItemEntry(
    val item: TagItem?, // WO-3: only decoded entries are selectable or served.
    val originalJson: String, // WO-3: opaque entries survive edits without JSON normalisation.
)

// WO-3: keep known and opaque entries in one sequence so list operations preserve their order.
data class ItemDocument(
    val entries: List<ItemEntry>, // WO-3: full persisted order, including unreadable entries.
    val readable: Boolean = true, // WO-3: a failed document decode must never become writable.
) {
    val items: List<TagItem> get() = entries.mapNotNull { it.item }
}

// WO-3: JSON mapping stays independent of preferences so JVM tests exercise the real format.
object ItemJson {
    // WO-3: persist stable IDs and explicit discriminators for every content type.
    fun encode(items: List<TagItem>): String =
        encode(ItemDocument(items.map { ItemEntry(it, "") }))

    // WO-3: only known entries are regenerated; unknown entries keep their exact JSON bytes.
    fun encode(document: ItemDocument): String = document.entries.joinToString(
        separator = ",", prefix = "[", postfix = "]",
    ) { entry -> entry.item?.let { encodeItem(it).toString() } ?: entry.originalJson }

    // WO-3: one mapping handles both ordinary saves and documents containing opaque entries.
    private fun encodeItem(item: TagItem): JSONObject {
        val json = JSONObject().put("id", item.id).put("type", item.type.storageName)
            .put("label", item.label)
        when (item) {
            is TagItem.Link -> json.put("url", item.url)
            is TagItem.Raw -> json.put("uri", item.uri) // WO-3: retain the exact migrated link.
            is TagItem.WhatsApp -> json.put("number", item.number)
            is TagItem.Call -> json.put("number", item.number)
            is TagItem.Email -> json.put("address", item.address)
                .put("subject", item.subject ?: JSONObject.NULL)
            is TagItem.Sms -> json.put("number", item.number)
                .put("body", item.body ?: JSONObject.NULL)
            is TagItem.Contact -> json.put("givenName", item.givenName)
                .put("familyName", item.familyName).put("org", item.org).put("title", item.title)
                .put("phone", item.phone).put("email", item.email).put("url", item.url)
                .put("note", item.note)
        }
        return json
    }

    // WO-3: unreadable entries stay hidden without being discarded at the next write.
    fun decode(json: String): ItemDocument {
        val originals = try {
            val array = JSONArray(json)
            splitEntries(json).also { require(it.size == array.length()) }
        } catch (_: JSONException) {
            // WO-3: invalid documents are distinct from intentional empty arrays.
            return ItemDocument(emptyList(), readable = false)
        } catch (_: IllegalArgumentException) {
            // WO-3: disagreeing parsers cannot authorize rewriting the original document.
            return ItemDocument(emptyList(), readable = false)
        }
        return ItemDocument(originals.map { original ->
            val item = try {
                decodeItem(JSONObject(original))
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            ItemEntry(item, original)
        })
    }

    // WO-3: locate top-level JSON values without rewriting opaque objects or their nested text.
    private fun splitEntries(json: String): List<String> {
        val text = json.trim()
        require(text.startsWith('[') && text.endsWith(']'))
        val entries = mutableListOf<String>()
        var start = 1
        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in 1 until text.lastIndex) {
            val character = text[index]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == quote -> quote = null
                }
                continue
            }
            when (character) {
                '"', '\'' -> quote = character
                '{', '[' -> depth++
                '}', ']' -> { depth--; require(depth >= 0) }
                ',' -> if (depth == 0) {
                    entries.add(text.substring(start, index).trim())
                    start = index + 1
                }
            }
        }
        require(depth == 0 && quote == null)
        val last = text.substring(start, text.lastIndex).trim()
        if (last.isNotEmpty()) entries.add(last)
        return entries
    }

    // WO-3: reject wrong JSON value types rather than coercing damaged data into content.
    private fun decodeItem(json: JSONObject): TagItem? {
        val id = json.string("id")
        val label = json.string("label")
        return when (json.string("type")) {
            "link" -> TagItem.Link(label, json.string("url"), id)
            "raw" -> TagItem.Raw(label, json.string("uri"), id) // WO-3: load migration-only links.
            "whatsapp" -> TagItem.WhatsApp(label, json.string("number"), id)
            "call" -> TagItem.Call(label, json.string("number"), id)
            "email" -> TagItem.Email(label, json.string("address"), json.optionalString("subject"), id)
            "sms" -> TagItem.Sms(label, json.string("number"), json.optionalString("body"), id)
            "contact" -> TagItem.Contact(
                label, json.optionalString("givenName") ?: "", json.optionalString("familyName") ?: "",
                json.optionalString("org") ?: "", json.optionalString("title") ?: "",
                json.optionalString("phone") ?: "", json.optionalString("email") ?: "",
                json.optionalString("url") ?: "", json.optionalString("note") ?: "", id,
            )
            else -> null
        }
    }

    private fun JSONObject.string(key: String): String =
        get(key) as? String ?: throw JSONException("Expected string: $key")

    private fun JSONObject.optionalString(key: String): String? =
        if (!has(key) || isNull(key)) null else string(key)
}

// WO-3: migration and list operations are pure; preference I/O cannot change their rules.
data class ItemState(
    val document: ItemDocument, // WO-3: include opaque entries in every saved-state transition.
    val activeItemId: String?, // WO-3: absent when no item is selected.
) {
    constructor(items: List<TagItem>, activeItemId: String?) :
        this(ItemDocument(items.map { ItemEntry(it, "") }), activeItemId)

    val readable: Boolean get() = document.readable // WO-3: retain the document's write prohibition.
    val items: List<TagItem> get() = if (readable) document.items else emptyList()
    val activeItem: TagItem? get() = items.firstOrNull { it.id == activeItemId }

    // WO-3: edits preserve order, identity, type, and the current selection.
    fun save(item: TagItem): ItemState {
        // WO-3: unreadable saved data cannot be replaced by a newly added item.
        if (!readable) return this
        val existing = items.firstOrNull { it.id == item.id }
        require(existing == null || existing.type == item.type) { "An item's type cannot change." }
        // WO-3: replace only the decoded entry; opaque neighbours retain their original positions.
        val updated = if (existing == null) document.entries + ItemEntry(item, "") else document.entries.map {
            if (it.item?.id == item.id) ItemEntry(item, "") else it
        }
        return ItemState(ItemDocument(updated), activeItemId ?: item.id)
    }

    // WO-3: selection can only point at an item already in the stored list.
    fun select(id: String): ItemState {
        // WO-3: stale UI selection must not turn a read failure into a write.
        if (!readable) return this
        require(items.any { it.id == id }) { "Item no longer exists." }
        return copy(activeItemId = id)
    }

    // WO-3: deleting the active item picks the first remaining item, or clears selection.
    fun delete(id: String): ItemState {
        // WO-3: unreadable documents remain untouched even for stale delete requests.
        if (!readable) return this
        // WO-3: delete only readable matches and choose the first remaining readable item.
        val remaining = ItemDocument(document.entries.filterNot { it.item?.id == id })
        val active = if (activeItemId == id) remaining.items.firstOrNull()?.id else activeItemId
        return ItemState(remaining, active)
    }

    // WO-3: readers see no application when disabled, unselected, or unable to encode.
    fun ndefFile(enabled: Boolean): ByteArray? {
        // WO-3: a document-level failure cannot expose any saved content.
        if (!enabled || !readable) return null
        return try {
            activeItem?.let { NdefMessage.ndefFile(it.ndefMessage()) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        // WO-3: storage exceptions share the same non-writable state as parse failures.
        fun unreadable(): ItemState = ItemState(ItemDocument(emptyList(), readable = false), null)

        // WO-3: only absence of items_v1 triggers migration; an intentional empty list stays empty.
        fun load(itemsJson: String?, activeId: String?, legacyUrl: String?): ItemState {
            // WO-3: migration changes representation only when the generated URI is unchanged.
            if (itemsJson == null) {
                val item = migrateLegacy(legacyUrl?.trim().orEmpty())
                return ItemState(listOf(item), item.id)
            }
            val document = ItemJson.decode(itemsJson)
            return ItemState(document, activeId?.takeIf { id -> document.items.any { it.id == id } })
        }

        // WO-3: invalid or differently normalised legacy values remain exact Saved links.
        private fun migrateLegacy(uri: String): TagItem {
            if (uri.isEmpty()) return TagItem.Link("Web link", TagPrefs.DEFAULT_URL)
            val candidate = try {
                when {
                    uri.startsWith("http://") || uri.startsWith("https://") -> TagItem.Link("Web link", uri)
                    uri.startsWith("tel:") -> TagItem.Call("Phone", uri.removePrefix("tel:"))
                    uri.startsWith("mailto:") -> {
                        val (address, subject) = parseQuery(uri.removePrefix("mailto:"), "subject")
                        TagItem.Email("Email", address, subject)
                    }
                    uri.startsWith("sms:") -> {
                        val (number, body) = parseQuery(uri.removePrefix("sms:"), "body")
                        TagItem.Sms("SMS", number, body)
                    }
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
            return candidate?.takeIf { it.uri == uri } ?: TagItem.Raw("Saved link", uri)
        }

        // WO-3: recognise only the one specified query field; extra parameters preserve the raw URI.
        private fun parseQuery(value: String, key: String): Pair<String, String?> {
            val parts = value.split('?', limit = 2)
            if (parts.size == 1) return parts[0] to null
            val query = parts[1]
            require(query.startsWith("$key=") && !query.contains('&'))
            return parts[0] to URLDecoder.decode(query.substring(key.length + 1), "UTF-8")
        }
    }
}

// WO-3: share the existing pockettag preferences with serving and diagnostic settings.
class ItemStore internal constructor(
    private val prefs: SharedPreferences, // WO-3: exercise the real write boundary without Android I/O.
) {
    constructor(context: Context) : this(context.getSharedPreferences("pockettag", Context.MODE_PRIVATE))

    // WO-3: write the migration once, atomically, without touching the legacy URL or other keys.
    fun load(): ItemState {
        // WO-3: a single snapshot also tolerates preferences with unexpected stored value types.
        return try {
            val values = prefs.all
            val hasItems = values.containsKey(ITEMS_KEY)
            // WO-3: a present value of the wrong type is unreadable, never a migration request.
            val json = if (hasItems) values[ITEMS_KEY] as? String ?: return ItemState.unreadable() else null
            val state = ItemState.load(json, values[ACTIVE_ID_KEY] as? String, values["url"] as? String)
            if (!hasItems) persist(state)
            state
        } catch (_: Exception) {
            // WO-3: failed reads must not authorize a later overwrite of the stored value.
            ItemState.unreadable()
        }
    }

    fun save(item: TagItem) = update { it.save(item) }
    fun select(id: String) = update { it.select(id) }
    fun delete(id: String) = update { it.delete(id) }

    // WO-3: every mutation stops before persistence when the saved document could not be read.
    private fun update(change: (ItemState) -> ItemState) {
        val state = load()
        if (!state.readable) return
        persist(change(state))
    }

    // WO-3: update content and selection together so a tap never sees half an edit.
    private fun persist(state: ItemState) {
        // WO-3: the final write boundary also rejects unreadable states.
        if (!state.readable) return
        // WO-3: encoding the whole document preserves entries this version cannot read.
        prefs.edit().putString(ITEMS_KEY, ItemJson.encode(state.document))
            .putString(ACTIVE_ID_KEY, state.activeItemId).apply()
    }

    companion object {
        const val ITEMS_KEY = "items_v1" // WO-3: versioned item-array schema.
        const val ACTIVE_ID_KEY = "active_item_id" // WO-3: selected stable item identity.
    }
}
