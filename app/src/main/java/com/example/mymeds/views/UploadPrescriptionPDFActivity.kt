package com.example.mymeds.views

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "PDFUploadActivity"

class UploadPrescriptionPDFActivity : ComponentActivity() {
    private val vm: PdfOcrUploadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UploadPrescriptionPDFScreen(vm = vm, finish = { finish() })
        }
    }
}

data class MedicationInfo(
    val medicationId: String,
    val name: String,
    val medicationRef: String,
    val doseMg: Int,
    val frequencyHours: Int,
    val startDate: Date,
    val endDate: Date,
    val active: Boolean,
    val prescriptionId: String
)

class PdfOcrUploadViewModel : ViewModel() {
    var extractedText by mutableStateOf<String?>(null)
        private set
    var parsedMedication by mutableStateOf<MedicationInfo?>(null)
        private set
    var processing by mutableStateOf(false)
        private set
    var uploading by mutableStateOf(false)
        private set
    var tempFileUri by mutableStateOf<Uri?>(null)
        private set

    private val firestore = FirebaseFirestore.getInstance()

    /**
     * Ejecuta OCR sobre el PDF y parsea la información
     */
    fun runOcrFromPdf(context: Activity, uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== INICIANDO OCR ===")
                processing = true

                val temp = copyToTempFile(context, uri, "rx_${System.currentTimeMillis()}.pdf")
                tempFileUri = Uri.fromFile(temp)

                val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

                val sb = StringBuilder()
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()

                        if (result.text.isNotBlank()) {
                            sb.appendLine(result.text)
                        }
                    }
                }
                renderer.close()
                pfd.close()

                extractedText = sb.toString().ifBlank { null }
                Log.d(TAG, "✅ OCR completado: ${extractedText?.length ?: 0} caracteres")

                // Parsea localmente
                if (extractedText != null) {
                    parseMedicationLocally(extractedText!!)
                }

                processing = false

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error durante el OCR", e)
                extractedText = null
                tempFileUri = null
                processing = false
                onError(e.message ?: "Falló el OCR")
            }
        }
    }

    /**
     * Parsea la información localmente usando expresiones regulares
     */
    private fun parseMedicationLocally(text: String) {
        try {
            Log.d(TAG, "🔍 Parseando texto localmente...")
            Log.d(TAG, "Texto a parsear:\n$text")

            val lines = text.lines().map { it.trim() }

            // Extrae medicamento y dosis
            var medicationName = ""
            var doseMg = 0

            // Busca patrones como "Medicamento: Losartán 50mg" o "Losartán 50mg"
            for (line in lines) {
                // Patrón: palabra seguida de número + "mg"
                val medPattern = """([A-Za-záéíóúÁÉÍÓÚñÑ]+)\s*(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE)
                val match = medPattern.find(line)

                if (match != null) {
                    val medName = match.groupValues[1].trim()
                    val dose = match.groupValues[2].toIntOrNull() ?: 0

                    if (medName.length > 2 && dose > 0) {
                        medicationName = "$medName ${dose}mg"
                        doseMg = dose
                        Log.d(TAG, "✅ Encontrado medicamento: $medicationName")
                        break
                    }
                }
            }

            // Si no encontró con el patrón, busca líneas que contengan "Medicamento:"
            if (medicationName.isEmpty()) {
                for (i in lines.indices) {
                    val line = lines[i].lowercase()
                    if (line.contains("medicamento:")) {
                        // Toma la siguiente línea o la misma si tiene contenido
                        val medLine = if (line.replace("medicamento:", "").trim().isNotEmpty()) {
                            lines[i]
                        } else if (i + 1 < lines.size) {
                            lines[i + 1]
                        } else {
                            ""
                        }

                        val parts = medLine.replace("Medicamento:", "", ignoreCase = true).trim()
                        if (parts.isNotEmpty()) {
                            medicationName = parts
                            // Intenta extraer dosis
                            val doseMatch = """(\d+)\s*mg""".toRegex().find(parts)
                            if (doseMatch != null) {
                                doseMg = doseMatch.groupValues[1].toIntOrNull() ?: 0
                            }
                            Log.d(TAG, "✅ Encontrado por etiqueta: $medicationName")
                            break
                        }
                    }
                }
            }

            // Extrae frecuencia (cada X horas)
            var frequencyHours = 24 // default
            for (line in lines) {
                val freqPattern = """cada\s+(\d+)\s+hora""".toRegex(RegexOption.IGNORE_CASE)
                val match = freqPattern.find(line)
                if (match != null) {
                    frequencyHours = match.groupValues[1].toIntOrNull() ?: 24
                    Log.d(TAG, "✅ Frecuencia: cada $frequencyHours horas")
                    break
                }

                // También busca "1 tableta cada 24 horas"
                val tabletPattern = """tableta\s+cada\s+(\d+)""".toRegex(RegexOption.IGNORE_CASE)
                val tabMatch = tabletPattern.find(line)
                if (tabMatch != null) {
                    frequencyHours = tabMatch.groupValues[1].toIntOrNull() ?: 24
                    Log.d(TAG, "✅ Frecuencia (tableta): cada $frequencyHours horas")
                    break
                }
            }

            // Extrae ID de prescripción
            var prescriptionId = "RX-${(1000..9999).random()}"
            for (line in lines) {
                val rxPattern = """RX[- ]?\d+""".toRegex(RegexOption.IGNORE_CASE)
                val match = rxPattern.find(line)
                if (match != null) {
                    prescriptionId = match.value.uppercase()
                    Log.d(TAG, "✅ ID Prescripción: $prescriptionId")
                    break
                }
            }

            // Extrae cantidad/duración (asume 30 días por defecto)
            var durationDays = 30
            for (line in lines) {
                val qtyPattern = """(\d+)\s*tabletas?""".toRegex(RegexOption.IGNORE_CASE)
                val match = qtyPattern.find(line)
                if (match != null) {
                    val quantity = match.groupValues[1].toIntOrNull() ?: 30
                    // Si toma 1 tableta por día, duración = cantidad
                    if (frequencyHours == 24) {
                        durationDays = quantity
                    }
                    Log.d(TAG, "✅ Duración estimada: $durationDays días")
                    break
                }
            }

            // Si no encontró medicamento, usa un valor por defecto
            if (medicationName.isEmpty()) {
                medicationName = "Medicamento no identificado"
                Log.w(TAG, "⚠️ No se pudo identificar el medicamento")
            }

            // Crea las fechas
            val startDate = Date()
            val calendar = Calendar.getInstance().apply {
                time = startDate
                add(Calendar.DAY_OF_YEAR, durationDays)
            }
            val endDate = calendar.time

            // Crea el objeto
            parsedMedication = MedicationInfo(
                medicationId = "med_${System.currentTimeMillis()}",
                name = medicationName,
                medicationRef = "/medicamentosGlobales/med_${medicationName.hashCode().toString().replace("-", "")}",
                doseMg = doseMg,
                frequencyHours = frequencyHours,
                startDate = startDate,
                endDate = endDate,
                active = true,
                prescriptionId = prescriptionId
            )

            Log.d(TAG, "✅ Parseo completo:")
            Log.d(TAG, "  - Medicamento: $medicationName")
            Log.d(TAG, "  - Dosis: $doseMg mg")
            Log.d(TAG, "  - Frecuencia: cada $frequencyHours horas")
            Log.d(TAG, "  - Prescripción: $prescriptionId")
            Log.d(TAG, "  - Duración: $durationDays días")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en parseo local", e)
        }
    }

    /**
     * Guarda el medicamento parseado en Firestore
     */
    fun savePrescriptionData(userId: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "=== GUARDANDO EN FIRESTORE ===")

                if (userId.isBlank()) {
                    throw IllegalStateException("Usuario no autenticado.")
                }

                if (parsedMedication == null) {
                    throw IllegalStateException("No hay información de medicamento para guardar.")
                }

                uploading = true

                val med = parsedMedication!!

                val doc = hashMapOf(
                    "medicationId" to med.medicationId,
                    "name" to med.name,
                    "medicationRef" to med.medicationRef,
                    "doseMg" to med.doseMg,
                    "frequencyHours" to med.frequencyHours,
                    "startDate" to med.startDate,
                    "endDate" to med.endDate,
                    "active" to med.active,
                    "prescriptionId" to med.prescriptionId,
                    "createdAt" to Date()
                )

                Log.d(TAG, "💾 Guardando medicamento: ${med.name}")

                val userMedsCollection = firestore
                    .collection("usuarios").document(userId)
                    .collection("medicamentosUsuario")

                val docRef = userMedsCollection.add(doc).await()

                Log.d(TAG, "✅ Documento guardado con ID: ${docRef.id}")

                uploading = false
                onDone(true, "✅ Medicamento guardado: ${med.name}")

            } catch (e: Exception) {
                uploading = false
                Log.e(TAG, "❌ Error al guardar", e)
                onDone(false, "❌ Error: ${e.message}")
            } finally {
                tempFileUri?.let {
                    try {
                        File(it.path!!).delete()
                    } catch (_: Exception) {}
                }
                tempFileUri = null
            }
        }
    }

    private fun copyToTempFile(context: Activity, uri: Uri, name: String): File {
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(out).use { output ->
                input!!.copyTo(output)
            }
        }
        return out
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadPrescriptionPDFScreen(vm: PdfOcrUploadViewModel, finish: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var pdfUri by remember { mutableStateOf<Uri?>(null) }
    val scroll = rememberScrollState()

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && activity != null) {
            pdfUri = uri
            vm.runOcrFromPdf(activity, uri) { errorMsg ->
                Toast.makeText(ctx, errorMsg, Toast.LENGTH_SHORT).show()
            }
        } else {
            pdfUri = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cargar Prescripción PDF", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(16.dp))

            // SELECTOR DE ARCHIVO
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp)
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                onClick = { pickPdf.launch("application/pdf") }
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (pdfUri != null) {
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = "PDF",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))

                            val fileName: String = remember(pdfUri) {
                                runCatching {
                                    val cursor = ctx.contentResolver.query(
                                        pdfUri!!,
                                        arrayOf(OpenableColumns.DISPLAY_NAME),
                                        null, null, null
                                    )
                                    cursor?.use {
                                        if (it.moveToFirst()) {
                                            it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                                        } else "Documento PDF"
                                    }
                                }.getOrDefault("Documento PDF") as String
                            }

                            Text(fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text("Click para cambiar", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Icon(Icons.Filled.Add, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.height(8.dp))
                            Text("Seleccionar PDF", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // INFORMACIÓN PARSEADA
            if (vm.processing) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Text("Procesando PDF...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else if (vm.parsedMedication != null) {
                val med = vm.parsedMedication!!
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("💊 Medicamento Detectado", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        InfoRow("Nombre:", med.name)
                        InfoRow("Dosis:", "${med.doseMg} mg")
                        InfoRow("Frecuencia:", "Cada ${med.frequencyHours} horas")
                        InfoRow("Prescripción:", med.prescriptionId)
                        InfoRow("Inicio:", SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(med.startDate))
                        InfoRow("Fin:", SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(med.endDate))
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // TEXTO EXTRAÍDO
            if (vm.extractedText != null) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📝 Texto Extraído", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(vm.extractedText!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // BOTÓN GUARDAR
            Button(
                enabled = vm.parsedMedication != null && !vm.uploading,
                onClick = {
                    val userId = FirebaseAuth.getInstance().currentUser?.uid

                    if (userId.isNullOrBlank()) {
                        Toast.makeText(ctx, "❌ Usuario no autenticado", Toast.LENGTH_LONG).show()
                        return@Button
                    }

                    if (activity != null) {
                        vm.savePrescriptionData(userId) { ok, msg ->
                            activity.runOnUiThread {
                                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                                if (ok) finish()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (vm.uploading) {
                    CircularProgressIndicator(strokeWidth = 3.dp, color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(Icons.Filled.Save, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Guardar Prescripcion")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(100.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}