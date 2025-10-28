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

private enum class PendingAction { NONE, WRITE, WIPE }
private val Ndef.isWriteProtected: Boolean
    get() = this.canMakeReadOnly() && !this.isWritable

class NfcViewModel : ViewModel() {

    private var pendingAction = PendingAction.NONE
    private var dataToWrite: String? = null

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
        when (pendingAction) {
            PendingAction.WRITE -> {
                val json = dataToWrite ?: "{}"
                pendingAction = PendingAction.NONE
                dataToWrite = null
                writeToTag(tag, json) { success, msg ->
                    _ui.update { it.copy(status = msg) }
                }
            }
            PendingAction.WIPE -> {
                pendingAction = PendingAction.NONE
                wipeTag(tag) { success, msg ->
                    _ui.update { it.copy(status = msg) }
                }
            }
            PendingAction.NONE -> {
                if (_ui.value.reading) {
                    readFromTag(tag)
                }
            }
        }
    }

    fun prepareToWrite(json: String) {
        dataToWrite = json
        pendingAction = PendingAction.WRITE
        _ui.update { it.copy(status = "Acerque el tag para ESCRIBIR") }
    }

    fun prepareToWipe() {
        pendingAction = PendingAction.WIPE
        _ui.update { it.copy(status = "Acerque el tag para LIMPIAR") }
    }

    private fun readFromTag(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ndef = Ndef.get(tag) ?: error("El tag no es compatible con NDEF")
                ndef.connect()
                val msg = ndef.ndefMessage
                val rec = msg.records.firstOrNull { it.tnf == NdefRecord.TNF_MIME_MEDIA }
                val data = rec?.payload?.toString(Charsets.UTF_8)
                ndef.close()
                data
            }.onSuccess { json ->
                _ui.update { it.copy(status = if (json != null) "Prescripción leída" else "Tag vacío",
                    lastPayload = json) }
                stopReading() // Exito de lectura
            }.onFailure { exception ->
                _ui.update { it.copy(status = "Error leyendo tag: ${exception.message}") }
                stopReading() // Error de lectura
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
