package dev.ppiankov.pockettag

import android.content.Context
import android.nfc.NfcAdapter
import java.lang.reflect.Method

// WO-2: the transaction can be tested without Android or access to a phone's controller.
internal interface ChipIo {
    fun read(): ByteArray?
    fun write(message: ByteArray): Int?
}

// WO-2: retain the published T4TNFCEE_STATUS_t names without treating zero as a written count.
internal enum class ChipWriteStatus(val vendorCode: Int? = null) {
    WRITTEN, COUNT_MISMATCH, UNKNOWN,
    STATUS_FAILED(-1), ERROR_RF_ACTIVATED(-2), ERROR_MPOS_ON(-3), ERROR_NFC_NOT_ON(-4),
    ERROR_INVALID_FILE_ID(-5), ERROR_INVALID_LENGTH(-6), ERROR_CONNECTION_FAILED(-7),
    ERROR_EMPTY_PAYLOAD(-8), ERROR_NDEF_VALIDATION_FAILED(-9), ERROR_WRITE_PERMISSION(-10),
    ERROR_NFC_OFF_TRIGGERED(-11),
}

// WO-2: only the exact positive byte count can prove that the requested message was written.
internal fun chipWriteStatus(result: Int?, size: Int): ChipWriteStatus = when {
    result == null || result == 0 -> ChipWriteStatus.UNKNOWN
    result > 0 -> if (result == size) ChipWriteStatus.WRITTEN else ChipWriteStatus.COUNT_MISMATCH
    else -> ChipWriteStatus.entries.firstOrNull { it.vendorCode == result } ?: ChipWriteStatus.UNKNOWN
}

// WO-2: reflect only the two E104 content operations; optional vendor APIs cannot be linked directly.
internal class NxpT4tNfcee private constructor(
    private val adapter: Any, // WO-2: the vendor adapter is deliberately opaque to the app.
    private val readMethod: Method, // WO-2: only the NDEF file can be read.
    private val writeMethod: Method, // WO-2: only the NDEF file can be written.
) : ChipIo {
    // WO-2: null and reflection failures remain unknown rather than being interpreted as an empty tag.
    override fun read(): ByteArray? = try {
        (readMethod.invoke(adapter, Type4Constants.NDEF_FILE_ID.copyOf()) as? ByteArray)?.copyOf()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    // WO-2: pass the raw message and its exact byte length, never the HCE file's NLEN prefix.
    override fun write(message: ByteArray): Int? = try {
        writeMethod.invoke(adapter, Type4Constants.NDEF_FILE_ID.copyOf(), message.copyOf(), message.size) as? Int
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    companion object {
        // WO-2: capability detection resolves methods without publishing a probe or changing controller state.
        fun detect(context: Context): NxpT4tNfcee? = try {
            val host = NfcAdapter.getDefaultAdapter(context)
            if (host == null) null else {
                val type = Class.forName("com.nxp.nfc.NxpNfcAdapter", true, context.classLoader)
                val adapter = type.getMethod("getNxpNfcAdapter", NfcAdapter::class.java).invoke(null, host)
                if (adapter == null) null else {
                    val read = type.getMethod("doReadT4tData", ByteArray::class.java)
                    val write = type.getMethod("doWriteT4tData", ByteArray::class.java,
                        ByteArray::class.java, Int::class.javaPrimitiveType)
                    if (read.returnType != ByteArray::class.java || write.returnType != Int::class.javaPrimitiveType) {
                        null
                    } else NxpT4tNfcee(adapter, read, write)
                }
            }
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }
}
