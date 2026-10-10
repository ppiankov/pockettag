package dev.ppiankov.pockettag

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

// WO-21: shared defaults stay short without imposing a limit on editable labels.
internal const val MAX_SHARED_LABEL = 40
private const val SHARED_TEXT_ERROR = "Share non-empty text or a web link." // WO-21: reject unsupported input without saving it.

// WO-21: classification produces an unsaved draft rather than touching item storage.
internal data class SharedItemDraft(
    val type: TagItem.Type, // WO-21: only links and text notes can arrive through sharing.
    val label: String, // WO-21: the subject or first non-blank line supplies an editable default.
    val content: String, // WO-21: the subject never becomes part of the shared content.
)

// WO-21: accepting only String input prevents arbitrary shared objects from becoming content.
internal fun sharedItemDraft(text: Any?, subject: Any? = null): SharedItemDraft {
    require(text is String && text.isNotBlank()) { SHARED_TEXT_ERROR }
    val trimmed = text.trim()
    val type = try {
        val uri = URI(trimmed)
        if (uri.scheme in setOf("http", "https") && uri.host != null) TagItem.Type.LINK else TagItem.Type.NOTE
    } catch (_: URISyntaxException) {
        TagItem.Type.NOTE
    }
    return SharedItemDraft(type, sharedItemLabel(text, subject), if (type == TagItem.Type.LINK) trimmed else text)
}

// WO-21: code-point truncation preserves a complete emoji at the default-label boundary.
internal fun sharedItemLabel(text: String, subject: Any? = null): String {
    val source = (subject as? String)?.takeIf { it.isNotBlank() }
        ?: requireNotNull(text.lineSequence().firstOrNull { it.isNotBlank() }) { SHARED_TEXT_ERROR }
    val trimmed = source.trim()
    val points = minOf(MAX_SHARED_LABEL, trimmed.codePointCount(0, trimmed.length))
    return trimmed.substring(0, trimmed.offsetByCodePoints(0, points))
}

// WO-21: manual creation and editing keep their existing selection behavior.
internal fun selectSharedItemAfterSave(editing: Boolean, requested: Boolean): Boolean = !editing && requested

// WO-21: styled extras become plain text before reaching the strict String classifier.
internal fun sharedItemDraftFromExtras(text: CharSequence?, subject: CharSequence? = null): SharedItemDraft =
    sharedItemDraft(text?.toString(), subject?.toString())

// WO-21: normalize plain-text MIME casing and parameters without requiring Android in JVM tests.
internal fun isSharedTextMimeType(type: String?): Boolean =
    type?.trim()?.lowercase(Locale.ROOT)?.substringBefore(';') == "text/plain"

// WO-21: the exported receiver forwards only validated draft fields to the private editor.
class ShareReceiverActivity : Activity() {
    // WO-21: unsupported intents finish before a draft can reach the private editor.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val draft = try {
            require(intent.action == Intent.ACTION_SEND && isSharedTextMimeType(intent.type)) { SHARED_TEXT_ERROR }
            sharedItemDraftFromExtras(intent.getCharSequenceExtra(Intent.EXTRA_TEXT),
                intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT))
        } catch (_: RuntimeException) {
            // WO-21: malformed extras are rejected without exposing their contents or writing preferences.
            Toast.makeText(this, R.string.error_shared_text, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        startActivity(Intent(this, EditItemActivity::class.java).apply {
            putExtra(EditItemActivity.EXTRA_TYPE, draft.type.storageName)
            putExtra(EditItemActivity.EXTRA_SHARED_LABEL, draft.label)
            putExtra(EditItemActivity.EXTRA_SHARED_CONTENT, draft.content)
            putExtra(EditItemActivity.EXTRA_SELECT_AFTER_SAVE, true)
        })
        finish()
    }
}
