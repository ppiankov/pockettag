package dev.ppiankov.pockettag

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// WO-3: JSON mapping stays independent of preferences so JVM tests exercise the real format.
object ItemJson {
    // WO-3: persist stable IDs and explicit discriminators for every content type.
    fun encode(items: List<TagItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            val json = JSONObject().put("id", item.id).put("type", item.type.storageName)
                .put("label", item.label)
            when (item) {
                is TagItem.Link -> json.put("url", item.url)
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
            array.put(json)
        }
        return array.toString()
    }

    // WO-3: a bad entry must not hide unrelated saved items or crash the HCE provider.
    fun decode(json: String): List<TagItem> {
        val array = try {
            JSONArray(json)
        } catch (_: JSONException) {
            return emptyList()
        }
        val items = mutableListOf<TagItem>()
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val item = try {
                decodeItem(entry)
            } catch (_: JSONException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
            if (item != null) items.add(item)
        }
        return items
    }

    // WO-3: reject wrong JSON value types rather than coercing damaged data into content.
    private fun decodeItem(json: JSONObject): TagItem? {
        val id = json.string("id")
        val label = json.string("label")
        return when (json.string("type")) {
            "link" -> TagItem.Link(label, json.string("url"), id)
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
    val items: List<TagItem>, // WO-3: stored list order decides the replacement selection.
    val activeItemId: String?, // WO-3: absent when no item is selected.
) {
    val activeItem: TagItem? get() = items.firstOrNull { it.id == activeItemId }

    // WO-3: edits preserve order, identity, type, and the current selection.
    fun save(item: TagItem): ItemState {
        val existing = items.firstOrNull { it.id == item.id }
        require(existing == null || existing.type == item.type) { "An item's type cannot change." }
        val updated = if (existing == null) items + item else items.map {
            if (it.id == item.id) item else it
        }
        return ItemState(updated, activeItemId ?: item.id)
    }

    // WO-3: selection can only point at an item already in the stored list.
    fun select(id: String): ItemState {
        require(items.any { it.id == id }) { "Item no longer exists." }
        return copy(activeItemId = id)
    }

    // WO-3: deleting the active item picks the first remaining item, or clears selection.
    fun delete(id: String): ItemState {
        val remaining = items.filterNot { it.id == id }
        val active = if (activeItemId == id) remaining.firstOrNull()?.id else activeItemId
        return ItemState(remaining, active)
    }

    // WO-3: readers see no application when disabled, unselected, or unable to encode.
    fun ndefFile(enabled: Boolean): ByteArray? {
        if (!enabled) return null
        return try {
            activeItem?.let { NdefMessage.ndefFile(it.ndefMessage()) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        // WO-3: only absence of items_v1 triggers migration; an intentional empty list stays empty.
        fun load(itemsJson: String?, activeId: String?, legacyUrl: String?): ItemState {
            if (itemsJson == null) {
                val item = TagItem.Link("Web link", legacyUrl ?: TagPrefs.DEFAULT_URL)
                return ItemState(listOf(item), item.id)
            }
            val items = ItemJson.decode(itemsJson)
            return ItemState(items, activeId?.takeIf { id -> items.any { it.id == id } })
        }
    }
}

// WO-3: share the existing pockettag preferences with serving and diagnostic settings.
class ItemStore(context: Context) {
    private val prefs = context.getSharedPreferences("pockettag", Context.MODE_PRIVATE)

    // WO-3: write the migration once, atomically, without touching the legacy URL or other keys.
    fun load(): ItemState {
        val json = prefs.getString(ITEMS_KEY, null)
        val state = ItemState.load(json, prefs.getString(ACTIVE_ID_KEY, null), prefs.getString("url", null))
        if (json == null) persist(state)
        return state
    }

    fun save(item: TagItem) = persist(load().save(item))
    fun select(id: String) = persist(load().select(id))
    fun delete(id: String) = persist(load().delete(id))

    // WO-3: update content and selection together so a tap never sees half an edit.
    private fun persist(state: ItemState) {
        prefs.edit().putString(ITEMS_KEY, ItemJson.encode(state.items))
            .putString(ACTIVE_ID_KEY, state.activeItemId).apply()
    }

    companion object {
        const val ITEMS_KEY = "items_v1" // WO-3: versioned item-array schema.
        const val ACTIVE_ID_KEY = "active_item_id" // WO-3: selected stable item identity.
    }
}
