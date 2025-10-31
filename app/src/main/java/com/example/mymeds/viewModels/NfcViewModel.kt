package com.example.mymeds.viewModels

import android.app.Application
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class UiState(
    val supported: Boolean = false,
    val enabled: Boolean = false,
    val reading: Boolean = false,
    val status: String = "",
    val lastPayload: String? = null,
    val parsedData: NfcViewModel.NfcData? = null,
    val isSaving: Boolean = false
)

private enum class PendingAction { NONE, WRITE, WIPE }

private val Ndef.isWriteProtected: Boolean
    get() = this.canMakeReadOnly() && !this.isWritable

class NfcViewModel(private val application: Application) : AndroidViewModel(application) {

    data class NfcData(
        @SerializedName("rxId") val id: String,
        @SerializedName("patient") val patientId: String,
        @SerializedName("meds") val medications: List<NfcMedication>,
        @SerializedName("issuedAt") val issuedTimestamp: String,
        @SerializedName("signed") val isSigned: Boolean
    )

    data class NfcMedication(
        @SerializedName("drug") val drugName: String,
        @SerializedName("dose") val dose: String,
        @SerializedName("freq") val frequency: String,
        @SerializedName("days") val durationInDays: Int
    )

    private var pendingAction = PendingAction.NONE
    private var dataToWrite: String? = null

    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private val firestore = FirebaseFirestore.getInstance()
    private val gson = Gson()

    fun init(nfcAdapter: NfcAdapter?) {
        _ui.update { it.copy(
            supported = nfcAdapter != null,
            enabled = nfcAdapter?.isEnabled == true
        ) }
    }

    fun startReading() { _ui.update { it.copy(reading = true, status = "Acerque el tag…") } }
    fun stopReading() { _ui.update { it.copy(reading = false, status = "Lectura detenida") } }

    fun prepareToWrite(json: String) {
        dataToWrite = json
        pendingAction = PendingAction.WRITE
        _ui.update { it.copy(status = "Acerque el tag para escribir") }
    }

    fun prepareToWipe() {
        pendingAction = PendingAction.WIPE
        _ui.update { it.copy(status = "Acerque el tag para limpiar") }
    }

    fun onTagDiscovered(tag: Tag) {
        when (pendingAction) {
            PendingAction.WRITE -> {
                val json = dataToWrite ?: "{}"
                pendingAction = PendingAction.NONE
                dataToWrite = null
                writeToTag(tag, json) { _, msg -> _ui.update { it.copy(status = msg) } }
            }
            PendingAction.WIPE -> {
                pendingAction = PendingAction.NONE
                wipeTag(tag) { _, msg -> _ui.update { it.copy(status = msg) } }
            }
            PendingAction.NONE -> {
                if (_ui.value.reading) {
                    readFromTag(tag)
                }
            }
        }
    }

    private fun readFromTag(tag: Tag) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ndef = Ndef.get(tag) ?: error("El tag no es compatible con NDEF")
                ndef.connect()
                val rawPayload = ndef.ndefMessage?.records?.firstOrNull()?.payload
                ndef.close()

                if (rawPayload != null) {
                    val jsonStartIndex = rawPayload.indexOfFirst { it.toInt().toChar() == '{' }
                    if (jsonStartIndex != -1) {
                        rawPayload.drop(jsonStartIndex).toByteArray().toString(Charsets.UTF_8)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }.onSuccess { jsonString ->
                val parsedObject = if (jsonString != null && jsonString != "{}") {
                    try { gson.fromJson(jsonString, NfcData::class.java) } catch (e: Exception) { null }
                } else {
                    null
                }

                if (parsedObject == null) {
                    launch(Dispatchers.Main) {
                        Toast.makeText(application, "El tag NFC está vacío", Toast.LENGTH_LONG).show()
                    }
                }

                _ui.update {
                    it.copy(
                        status = if (parsedObject != null) "Prescripción Leída" else "El tag NFC está vacío",
                        lastPayload = jsonString,
                        parsedData = parsedObject
                    )
                }
                stopReading()

            }.onFailure { exception ->
                _ui.update { it.copy(status = "Error: ${exception.message}") }
                stopReading()
            }
        }
    }

    private fun writeToTag(tag: Tag, json: String, mime: String = "application/com.example.mymeds.prescription", onDone: (Boolean, String) -> Unit) {
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

    private fun wipeTag(tag: Tag, onDone: (Boolean, String) -> Unit) {
        writeToTag(tag, "{}", onDone = onDone)
    }

    /**
     * Guardar en Firebase
     */
    fun saveLastReadDataToFirebase(currentUserId: String, onComplete: (Boolean, String) -> Unit) {
        val dataToSave = _ui.value.parsedData ?: run {
            onComplete(false, "No hay datos leídos para guardar.")
            return
        }

        viewModelScope.launch {
            _ui.update { it.copy(isSaving = true, status = "Verificando usuario...") }

            // Security check: la prescripcion pertenece al user actual
            if (dataToSave.patientId != currentUserId) {
                _ui.update { it.copy(isSaving = false, status = "Verificación fallida.") }
                onComplete(false, "Error: La prescripción no pertenece a este usuario.")
                return@launch
            }

            try {
                _ui.update { it.copy(status = "Creando prescripción...") }

                val userPrescriptionsCollection = firestore.collection("usuarios").document(currentUserId).collection("prescripcionesUsuario")

                val prescriptionDocument = mapNfcDataToPrescriptionHashMap(dataToSave)
                val newPrescriptionRef = userPrescriptionsCollection.add(prescriptionDocument).await()

                _ui.update { it.copy(status = "Guardando medicamentos...") }

                val medicationDocuments = mapNfcMedsToHashMapList(dataToSave.medications, newPrescriptionRef.id, dataToSave.id, dataToSave.issuedTimestamp)
                val medsSubCollection = newPrescriptionRef.collection("medicamentosPrescripcion")

                val saveJobs = medicationDocuments.map { doc ->
                    async(Dispatchers.IO) { medsSubCollection.add(doc).await() }
                }
                saveJobs.awaitAll()

                _ui.update { it.copy(isSaving = false, status = "Guardado con éxito.", parsedData = null) }
                onComplete(true, "✅ ${medicationDocuments.size} medicamento(s) guardado(s) en una nueva prescripción.")

            } catch (e: Exception) {
                _ui.update { it.copy(isSaving = false, status = "Error.") }
                onComplete(false, "❌ Error al guardar: ${e.message}")
            }
        }
    }

    private fun mapNfcDataToPrescriptionHashMap(nfcData: NfcData): HashMap<String, Any> {
        return hashMapOf(
            "activa" to true,
            "fileName" to nfcData.id,
            "fromOCR" to false,
            "notes" to "NFC",
            "status" to "pendiente",
            "totalItems" to nfcData.medications.size,
            "uploadedAt" to Timestamp.now()
        )
    }

    private suspend fun mapNfcMedsToHashMapList(
        medications: List<NfcMedication>,
        firestorePrescriptionId: String,
        nfcPrescriptionId: String,
        issuedTimestamp: String
    ): List<HashMap<String, Any>> {
        val globalMedsCollection = firestore.collection("medicamentosGlobales")

        return medications.map { nfcMed ->
            val querySnapshot = globalMedsCollection.whereEqualTo("nombre", nfcMed.drugName).limit(1).get().await()
            val medDoc = querySnapshot.documents.firstOrNull()
            val medId = medDoc?.id ?: "unknown"
            val medRef = medDoc?.reference?.path ?: "/medicamentosGlobales/unknown"
            val doseMg = nfcMed.dose.filter { it.isDigit() }.toIntOrNull() ?: 0
            val frequencyHours = nfcMed.frequency.filter { it.isDigit() }.toIntOrNull() ?: 24

            val startDate = try {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                parser.parse(issuedTimestamp) ?: Date()
            } catch (e: Exception) { Date() }

            val calendar = Calendar.getInstance().apply {
                time = startDate
                add(Calendar.DAY_OF_YEAR, nfcMed.durationInDays)
            }
            val endDate = calendar.time

            hashMapOf(
                "medicationId" to medId,
                "medicationRef" to medRef,
                "name" to nfcMed.drugName,
                "doseMg" to doseMg,
                "frequencyHours" to frequencyHours,
                "startDate" to Timestamp(startDate),
                "endDate" to Timestamp(endDate),
                "createdAt" to Timestamp(Date()),
                "active" to true,
                "prescriptionId" to firestorePrescriptionId,
                "sourceFile" to "NFC Tag (ID: $nfcPrescriptionId)"
            )
        }
    }
}
