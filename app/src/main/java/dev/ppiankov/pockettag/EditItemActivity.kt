package dev.ppiankov.pockettag

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.UUID

// WO-3: one framework editor validates the complete tag before saving any preference.
class EditItemActivity : Activity() {
    private val store by lazy { ItemStore(this) }
    private val fields = linkedMapOf<String, EditText>()
    private var existing: TagItem? = null
    private lateinit var type: TagItem.Type
    private lateinit var error: TextView

    // WO-3: existing items determine their own type; callers cannot change it through extras.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_ID)
        existing = id?.let { requested -> store.load().items.firstOrNull { it.id == requested } }
        val requestedType = existing?.type ?: TagItem.Type.entries.firstOrNull {
            it.storageName == intent.getStringExtra(EXTRA_TYPE)
        }
        // WO-3: a Saved link can only be opened as an existing migrated item.
        if ((id != null && existing == null) || requestedType == null ||
            (id == null && requestedType == TagItem.Type.RAW)) {
            finish()
            return
        }
        type = requestedType
        setContentView(R.layout.activity_edit_item)
        title = getString(type.titleResource())
        error = findViewById(R.id.item_error)
        buildFields()
        fields.forEach { (key, field) ->
            savedInstanceState?.getString("draft:$key")?.let { field.setText(it) }
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.delete_item).apply {
            visibility = if (existing == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
        }
    }

    // WO-3: dynamically created fields retain an unsaved draft across recreation.
    override fun onSaveInstanceState(outState: Bundle) {
        fields.forEach { (key, field) -> outState.putString("draft:$key", field.text.toString()) }
        super.onSaveInstanceState(outState)
    }

    // WO-3: only the selected type's fields are exposed in the editor.
    private fun buildFields() {
        // WO-3: Saved link editing exposes only its link text, retaining the saved label.
        if (type != TagItem.Type.RAW) addField("label", R.string.item_label, existing?.label.orEmpty())
        when (type) {
            TagItem.Type.RAW -> addField("uri", R.string.field_raw_uri,
                (existing as? TagItem.Raw)?.uri.orEmpty())
            TagItem.Type.LINK -> addField("url", R.string.field_url,
                (existing as? TagItem.Link)?.url.orEmpty(), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            TagItem.Type.WHATSAPP -> addField("number", R.string.field_number,
                (existing as? TagItem.WhatsApp)?.number.orEmpty(), InputType.TYPE_CLASS_PHONE)
            TagItem.Type.CALL -> addField("number", R.string.field_number,
                (existing as? TagItem.Call)?.number.orEmpty(), InputType.TYPE_CLASS_PHONE)
            TagItem.Type.EMAIL -> {
                val item = existing as? TagItem.Email
                addField("address", R.string.field_address, item?.address.orEmpty(),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                addField("subject", R.string.field_subject, item?.subject.orEmpty())
            }
            TagItem.Type.SMS -> {
                val item = existing as? TagItem.Sms
                addField("number", R.string.field_number, item?.number.orEmpty(), InputType.TYPE_CLASS_PHONE)
                addField("body", R.string.field_body, item?.body.orEmpty(), multiline = true)
            }
            TagItem.Type.CONTACT -> {
                val item = existing as? TagItem.Contact
                addField("givenName", R.string.field_given_name, item?.givenName.orEmpty())
                addField("familyName", R.string.field_family_name, item?.familyName.orEmpty())
                addField("org", R.string.field_org, item?.org.orEmpty())
                addField("title", R.string.field_title, item?.title.orEmpty())
                addField("phone", R.string.field_number, item?.phone.orEmpty(), InputType.TYPE_CLASS_PHONE)
                addField("email", R.string.field_address, item?.email.orEmpty(),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
                addField("url", R.string.field_contact_url, item?.url.orEmpty(),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                addField("note", R.string.field_note, item?.note.orEmpty(), multiline = true)
            }
        }
    }

    // WO-3: persistent labels remain visible after typing, including with accessibility services.
    private fun addField(
        key: String,
        label: Int,
        value: String,
        input: Int = InputType.TYPE_CLASS_TEXT,
        multiline: Boolean = false,
    ) {
        val form = findViewById<LinearLayout>(R.id.item_fields)
        val field = EditText(this).apply {
            id = View.generateViewId()
            inputType = if (multiline) input or InputType.TYPE_TEXT_FLAG_MULTI_LINE else input
            if (multiline) minLines = MULTILINE_ROWS else setSingleLine(true)
            setText(value)
        }
        form.addView(TextView(this).apply {
            setText(label)
            labelFor = field.id
        })
        form.addView(field, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        fields[key] = field
    }

    // WO-3: validate both the type's input rules and whole-file size before persisting.
    private fun save() {
        try {
            val id = existing?.id ?: UUID.randomUUID().toString()
            // WO-3: preserve the label while editing the migration-only link field.
            val label = if (type == TagItem.Type.RAW) requireNotNull(existing).label else value("label")
            val item = when (type) {
                TagItem.Type.RAW -> TagItem.Raw(label, value("uri"), id)
                TagItem.Type.LINK -> TagItem.Link(label, value("url"), id)
                TagItem.Type.WHATSAPP -> TagItem.WhatsApp(label, value("number"), id)
                TagItem.Type.CALL -> TagItem.Call(label, value("number"), id)
                TagItem.Type.EMAIL -> TagItem.Email(label, value("address"), value("subject"), id)
                TagItem.Type.SMS -> TagItem.Sms(label, value("number"), value("body"), id)
                TagItem.Type.CONTACT -> TagItem.Contact(label, value("givenName"), value("familyName"),
                    value("org"), value("title"), value("phone"), value("email"), value("url"), value("note"), id)
            }
            NdefMessage.ndefFile(item.ndefMessage())
            store.save(item)
            finish()
        } catch (e: IllegalArgumentException) {
            error.text = if (e is NdefTooLargeException) getString(R.string.error_too_long) else e.message
            error.visibility = View.VISIBLE
        }
    }

    private fun value(key: String): String = fields.getValue(key).text.toString()

    // WO-3: only an explicit confirmation removes the saved item and updates selection.
    private fun confirmDelete() {
        val item = existing ?: return
        AlertDialog.Builder(this).setTitle(R.string.delete_item_title)
            .setMessage(getString(R.string.delete_item_message, item.label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.delete_item) { _, _ ->
                store.delete(item.id)
                finish()
            }.show()
    }

    companion object {
        const val EXTRA_ID = "item_id" // WO-3: edit the item with this stable identity.
        const val EXTRA_TYPE = "item_type" // WO-3: choose the immutable type when creating an item.
        private const val MULTILINE_ROWS = 3 // WO-3: show room for SMS bodies and contact notes.
    }
}

// WO-3: the selector and editor use the same type names without Android in the content model.
internal fun TagItem.Type.titleResource(): Int = when (this) {
    TagItem.Type.LINK -> R.string.type_link
    TagItem.Type.CONTACT -> R.string.type_contact
    TagItem.Type.WHATSAPP -> R.string.type_whatsapp
    TagItem.Type.CALL -> R.string.type_call
    TagItem.Type.EMAIL -> R.string.type_email
    TagItem.Type.SMS -> R.string.type_sms
    TagItem.Type.RAW -> R.string.type_raw // WO-3: distinguish preserved legacy links.
}
