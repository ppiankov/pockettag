package dev.ppiankov.pockettag

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.text.method.DigitsKeyListener
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.widget.Button
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import java.util.Locale
import java.util.UUID

// WO-3: one framework editor validates the complete tag before saving any preference.
class EditItemActivity : Activity() {
    private val store by lazy { ItemStore(this) }
    private val fields = linkedMapOf<String, EditText>()
    private var existing: TagItem? = null
    private lateinit var type: TagItem.Type
    private lateinit var error: TextView
    private var wifiSecurity: Spinner? = null // WO-7: the security choice participates in the unsaved draft.
    private var wifiHidden: CheckBox? = null // WO-7: preserve the hidden-network choice across recreation.

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
        // WO-21: a shared draft is prefilled before restoration and remains unsaved until Save.
        if (existing == null && intent.getBooleanExtra(EXTRA_SELECT_AFTER_SAVE, false)) {
            val contentKey = when (type) {
                TagItem.Type.LINK -> "url"
                TagItem.Type.NOTE -> "text"
                else -> null
            }
            if (contentKey != null) {
                fields.getValue("label").setText(intent.getStringExtra(EXTRA_SHARED_LABEL).orEmpty())
                fields.getValue(contentKey).setText(intent.getStringExtra(EXTRA_SHARED_CONTENT).orEmpty())
            }
        }
        fields.forEach { (key, field) ->
            savedInstanceState?.getString("draft:$key")?.let { field.setText(it) }
        }
        // WO-7: non-text Wi-Fi fields restore alongside the existing text-field draft.
        savedInstanceState?.getString(DRAFT_WIFI_SECURITY)?.let { name ->
            TagItem.Wifi.Security.entries.indexOfFirst { it.name == name }.takeIf { it >= 0 }
                ?.let { wifiSecurity?.setSelection(it) }
        }
        wifiHidden?.let { hidden ->
            hidden.isChecked = savedInstanceState?.getBoolean(DRAFT_WIFI_HIDDEN, hidden.isChecked) ?: hidden.isChecked
        }
        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.delete_item).apply {
            visibility = if (existing == null) View.GONE else View.VISIBLE
            setOnClickListener { confirmDelete() }
        }
        // WO-12: only persisted items have a completed-read count to reset.
        findViewById<Button>(R.id.reset_count).apply {
            visibility = if (existing == null) View.GONE else View.VISIBLE
            setOnClickListener { existing?.let { store.resetTapCount(it.id) } }
        }
    }

    // WO-3: dynamically created fields retain an unsaved draft across recreation.
    override fun onSaveInstanceState(outState: Bundle) {
        fields.forEach { (key, field) -> outState.putString("draft:$key", field.text.toString()) }
        // WO-7: use stable security names rather than widget positions for saved drafts.
        wifiSecurity?.selectedItemPosition?.let { position ->
            TagItem.Wifi.Security.entries.getOrNull(position)?.let { outState.putString(DRAFT_WIFI_SECURITY, it.name) }
        }
        wifiHidden?.let { outState.putBoolean(DRAFT_WIFI_HIDDEN, it.isChecked) }
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
            // WO-8: multiline text and its language are editable without rewriting free text.
            TagItem.Type.NOTE -> {
                val item = existing as? TagItem.Note
                addField("text", R.string.field_note, item?.text.orEmpty(), multiline = true)
                addField("language", R.string.field_language, item?.language ?: "en")
            }
            // WO-9: maps links can supply coordinates without asking for the phone's location.
            TagItem.Type.PLACE -> {
                val item = existing as? TagItem.Place
                addField("name", R.string.field_place_name, item?.name.orEmpty())
                val numeric = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    InputType.TYPE_NUMBER_FLAG_SIGNED
                // WO-24: editing reuses the served coordinate format rather than scientific notation.
                addField("latitude", R.string.field_latitude, item?.latitude?.let(TagItem.Place::coordinate).orEmpty(), numeric)
                addField("longitude", R.string.field_longitude, item?.longitude?.let(TagItem.Place::coordinate).orEmpty(), numeric)
                // WO-24: let pasted decimal commas reach validation while retaining the numeric keyboard.
                for (key in listOf("latitude", "longitude")) {
                    fields.getValue(key).apply {
                        keyListener = DigitsKeyListener.getInstance(COORDINATE_CHARACTERS)
                        setRawInputType(numeric)
                    }
                }
                addField("mapsLink", R.string.field_maps_link, "",
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            }
            // WO-10: enter a store identifier without enumerating installed apps.
            TagItem.Type.APP -> {
                addField("packageName", R.string.field_package_name,
                    (existing as? TagItem.App)?.packageName.orEmpty(),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
                fields.getValue("packageName").setHint(R.string.hint_package_name)
            }
            // WO-7: network credentials are entered manually; the password starts masked on every editor opening.
            TagItem.Type.WIFI -> {
                val item = existing as? TagItem.Wifi
                addField("ssid", R.string.field_ssid, item?.ssid.orEmpty())
                val form = findViewById<LinearLayout>(R.id.item_fields)
                val security = Spinner(this).apply {
                    id = View.generateViewId()
                    adapter = ArrayAdapter(this@EditItemActivity, android.R.layout.simple_spinner_item,
                        listOf(getString(R.string.security_wpa2), getString(R.string.security_wpa3),
                            getString(R.string.security_open))).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                    setSelection((item?.security ?: TagItem.Wifi.Security.WPA2_PERSONAL).ordinal)
                }
                form.addView(TextView(this).apply {
                    setText(R.string.field_security)
                    labelFor = security.id
                })
                form.addView(security)
                wifiSecurity = security
                addField("password", R.string.field_wifi_password, item?.password.orEmpty(),
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                val password = fields.getValue("password")
                // WO-7: initialize masking after the shared single-line field setup.
                password.transformationMethod = PasswordTransformationMethod.getInstance()
                val show = CheckBox(this).apply {
                    setText(R.string.show_wifi_password)
                    setOnCheckedChangeListener { _, checked ->
                        password.transformationMethod = if (checked) HideReturnsTransformationMethod.getInstance()
                            else PasswordTransformationMethod.getInstance()
                        password.setSelection(password.text.length)
                    }
                }
                form.addView(show)
                wifiHidden = CheckBox(this).apply {
                    setText(R.string.hidden_network)
                    isChecked = item?.hidden ?: false
                    form.addView(this)
                }
                // WO-7: open networks omit the key and cannot leave an unmasked password on screen.
                fun updatePasswordEnabled() {
                    val protected = TagItem.Wifi.Security.entries.getOrNull(security.selectedItemPosition) !=
                        TagItem.Wifi.Security.OPEN
                    password.isEnabled = protected
                    show.isEnabled = protected
                    if (!protected) show.isChecked = false
                }
                security.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        updatePasswordEnabled()
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) { updatePasswordEnabled() }
                }
                updatePasswordEnabled()
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
                // WO-8: save only after the shared encoder validates the language and file size.
                TagItem.Type.NOTE -> TagItem.Note(label, value("text"), value("language"), id)
                // WO-9: a pasted maps link explicitly replaces the coordinate fields for this save.
                TagItem.Type.PLACE -> {
                    val coordinates = if (value("mapsLink").isNotBlank()) {
                        TagItem.Place.coordinatesFromLink(value("mapsLink"))
                    } else {
                        // WO-24: coordinate fields share the single-comma parsing rule.
                        val latitude = TagItem.Place.coordinateFromInput(value("latitude"))
                        val longitude = TagItem.Place.coordinateFromInput(value("longitude"))
                        require(latitude != null && longitude != null) { getString(R.string.error_coordinates) }
                        latitude to longitude
                    }
                    TagItem.Place(label, coordinates.first, coordinates.second, value("name"), id)
                }
                TagItem.Type.APP -> TagItem.App(label, value("packageName"), id) // WO-10: validate before persistence.
                // WO-7: save the selected security and hidden flag with the same private credential fields.
                TagItem.Type.WIFI -> TagItem.Wifi(label, value("ssid"),
                    requireNotNull(TagItem.Wifi.Security.entries.getOrNull(wifiSecurity?.selectedItemPosition ?: -1)) {
                        getString(R.string.error_wifi_security)
                    }, value("password"), wifiHidden?.isChecked ?: false, id)
            }
            // WO-21: report the whole encoded file size before any save when the shared limit rejects it.
            val message = item.ndefMessage()
            try {
                NdefMessage.ndefFile(message)
            } catch (_: NdefTooLargeException) {
                // WO-21: the plural quantity counts the whole file, just like the displayed size.
                val template = resources.getQuantityString(R.plurals.error_too_long,
                    message.size + TAG_LENGTH_PREFIX_BYTES)
                error.text = tooLargeItemMessage(template, message)
                error.visibility = View.VISIBLE
                return
            }
            store.save(item)
            // WO-21: only a saved shared creation changes selection through the existing trigger.
            if (selectSharedItemAfterSave(existing != null, intent.getBooleanExtra(EXTRA_SELECT_AFTER_SAVE, false))) {
                store.select(item.id)
            }
            finish()
        } catch (e: IllegalArgumentException) {
            error.text = e.message
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
        internal const val EXTRA_SHARED_LABEL = "shared_label" // WO-21: the receiver supplies an editable draft label.
        internal const val EXTRA_SHARED_CONTENT = "shared_content" // WO-21: only the validated shared content is forwarded.
        internal const val EXTRA_SELECT_AFTER_SAVE = "select_after_save" // WO-21: shared creations select only after validation and Save.
        private const val MULTILINE_ROWS = 3 // WO-3: show room for SMS bodies and contact notes.
        private const val DRAFT_WIFI_SECURITY = "draft:wifi_security" // WO-7: stable security draft key.
        private const val DRAFT_WIFI_HIDDEN = "draft:wifi_hidden" // WO-7: checkbox draft key.
        private const val COORDINATE_CHARACTERS = "0123456789+-.," // WO-24: preserve both decimal separators for validation.
    }
}

private const val TAG_LENGTH_PREFIX_BYTES = 2 // WO-21: the reported size includes Type 4's two-byte NLEN.

// WO-21: byte-count errors use encoded bytes rather than text length for every content type.
internal fun tooLargeItemMessage(template: String, message: ByteArray): String =
    String.format(Locale.ROOT, template, message.size + TAG_LENGTH_PREFIX_BYTES)

// WO-3: the selector and editor use the same type names without Android in the content model.
internal fun TagItem.Type.titleResource(): Int = when (this) {
    TagItem.Type.LINK -> R.string.type_link
    TagItem.Type.CONTACT -> R.string.type_contact
    TagItem.Type.WHATSAPP -> R.string.type_whatsapp
    TagItem.Type.CALL -> R.string.type_call
    TagItem.Type.EMAIL -> R.string.type_email
    TagItem.Type.SMS -> R.string.type_sms
    TagItem.Type.RAW -> R.string.type_raw // WO-3: distinguish preserved legacy links.
    TagItem.Type.NOTE -> R.string.type_note // WO-8: the Add menu and editor share the Text item name.
    TagItem.Type.PLACE -> R.string.type_place // WO-9: use the same name in the selector and editor.
    TagItem.Type.APP -> R.string.type_app // WO-10: Android app records share the selector's title mapping.
    TagItem.Type.WIFI -> R.string.type_wifi // WO-7: the selector and credential editor use the same title.
}
