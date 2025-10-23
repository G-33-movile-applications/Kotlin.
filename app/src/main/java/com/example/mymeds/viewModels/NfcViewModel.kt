package com.example.mymeds.viewModels

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val Ndef.isWriteProtected: Boolean
    get() = this.canMakeReadOnly() && !this.isWritable

class NfcViewModel : ViewModel() {

    data class UiState(
        val supported: Boolean = false,
        val enabled: Boolean = false,
        val reading: Boolean = false,
        val status: String = "",
        val lastPayload: String? = null
    )

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    fun init(nfcAdapter: NfcAdapter?) {
        _ui.update { it.copy(
            supported = nfcAdapter != null,
            enabled = nfcAdapter?.isEnabled == true
        ) }
    }

    fun startReading() { _ui.update { it.copy(reading = true, status = "Acerque el tag…") } }
    fun stopReading() { _ui.update { it.copy(reading = false, status = "Lectura detenida") } }

    fun onTagDiscovered(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ndef = Ndef.get(tag) ?: error("El tag no es compatible con NDEF")
                ndef.connect()
                // 4. Corrige el bloque runCatching para que devuelva un String o null
                val msg = ndef.ndefMessage
                val rec = msg.records.firstOrNull { it.tnf == NdefRecord.TNF_MIME_MEDIA }
                val data = rec?.payload?.toString(Charsets.UTF_8)
                ndef.close()
                data // Esta es la última expresión, se convierte en el resultado
            }.onSuccess { json ->
                _ui.update { it.copy(status = if (json!=null) "Prescripción leída" else "Tag vacío",
                    lastPayload = json) }
            }.onFailure { exception -> // Es buena práctica nombrar la excepción
                _ui.update { it.copy(status = "Error leyendo tag: ${exception.message}") }
            }
        }
    }

    fun writeToTag(tag: Tag, json: String, mime: String = "application/com.example.mymeds.prescription",
                   onDone: (Boolean,String)->Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val payload = json.toByteArray(Charsets.UTF_8)
                val rec = NdefRecord.createMime(mime, payload)
                val msg = NdefMessage(arrayOf(rec))
                val ndef = Ndef.get(tag)
                if (ndef != null) {
                    ndef.connect()
                    check(!ndef.isWriteProtected) { "Tag de solo lectura" }
                    check(ndef.maxSize >= msg.toByteArray().size) { "Tag sin espacio" }
                    ndef.writeNdefMessage(msg)
                    ndef.close()
                } else {
                    val fmt = NdefFormatable.get(tag) ?: error("Tag no compatible")
                    fmt.connect()
                    fmt.format(msg)
                    fmt.close()
                }
            }.onSuccess { onDone(true, "Escritura exitosa") }
                .onFailure { onDone(false, it.message ?: "Error escribiendo") }
        }
    }

    fun wipeTag(tag: Tag, onDone: (Boolean,String)->Unit) {
        writeToTag(tag, "{}", onDone = onDone)
    }
}
