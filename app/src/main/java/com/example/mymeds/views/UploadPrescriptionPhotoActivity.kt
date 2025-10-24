package com.example.mymeds.views

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.rememberAsyncImagePainter
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "PhotoUploadActivity"

// ------------------ ACTIVITY ------------------
class UploadPrescriptionPhotoActivity : ComponentActivity() {
    private val vm: PhotoOcrUploadViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UploadPrescriptionPhotoScreen(vm = vm, finish = { finish() })
        }
    }
}

// ------------------ DATA CLASS ------------------
data class UserMedicationInfo(
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

// ------------------ VIEWMODEL ------------------
class PhotoOcrUploadViewModel : ViewModel() {
    var extractedText by mutableStateOf<String?>(null)
        private set
    var parsedMedication by mutableStateOf<UserMedicationInfo?>(null)
        private set
    var processing by mutableStateOf(false)
        private set
    var uploading by mutableStateOf(false)
        private set

    private val firestore = FirebaseFirestore.getInstance()

    fun runOcrFromUri(activity: Activity, uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                processing = true
                val image = InputImage.fromFilePath(activity, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val result = recognizer.process(image).await()
                extractedText = result.text.ifBlank { null }
                if (extractedText != null) parseMedicationLocally(extractedText!!)
            } catch (e: Exception) {
                Log.e(TAG, "Error OCR", e)
                onError(e.message ?: "OCR falló")
            } finally {
                processing = false
            }
        }
    }

    private fun parseMedicationLocally(text: String) {
        try {
            Log.d(TAG, "🔍 Parseando texto localmente...")
            Log.d(TAG, "Texto a parsear:\n$text")

            val lines = text.lines().map { it.trim() }

            // Extrae medicamento y dosis
            var medicationName = ""
            var doseMg = 0

            // Patrón base: "Losartán 50mg" o con etiqueta "Medicamento: Losartán 50mg"
            for (line in lines) {
                // Palabra(s) + número + mg (soporta acentos)
                val medPattern = """([A-Za-záéíóúÁÉÍÓÚñÑ]+(?:\s+[A-Za-záéíóúÁÉÍÓÚñÑ]+)*)\s*(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE)
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

            // Si no encontró con el patrón directo, busca la etiqueta "Medicamento:"
            if (medicationName.isEmpty()) {
                for (i in lines.indices) {
                    val lower = lines[i].lowercase()
                    if (lower.contains("medicamento:")) {
                        // Toma lo que viene tras la etiqueta o la siguiente línea
                        val medLine = if (lower.replace("medicamento:", "").trim().isNotEmpty()) {
                            lines[i]
                        } else if (i + 1 < lines.size) {
                            lines[i + 1]
                        } else {
                            ""
                        }

                        val parts = medLine.replace("Medicamento:", "", ignoreCase = true).trim()
                        if (parts.isNotEmpty()) {
                            medicationName = parts
                            // Extrae dosis si está dentro de la misma línea
                            val doseMatch = """(\d+)\s*mg""".toRegex(RegexOption.IGNORE_CASE).find(parts)
                            if (doseMatch != null) {
                                doseMg = doseMatch.groupValues[1].toIntOrNull() ?: 0
                            }
                            Log.d(TAG, "✅ Encontrado por etiqueta: $medicationName")
                            break
                        }
                    }
                }
            }

            // Extrae frecuencia (cada X horas / h / hrs)
            var frequencyHours = 24 // default
            for (line in lines) {
                // "cada 24 horas" | "cada 24 h" | "cada 24 hrs"
                val freqPattern = """cada\s+(\d+)\s*(?:hora|horas|h|hrs)""".toRegex(RegexOption.IGNORE_CASE)
                val match = freqPattern.find(line)
                if (match != null) {
                    frequencyHours = match.groupValues[1].toIntOrNull() ?: 24
                    Log.d(TAG, "✅ Frecuencia: cada $frequencyHours horas")
                    break
                }

                // "1 tableta cada 24 horas"
                val tabletPattern = """tableta\s+cada\s+(\d+)""".toRegex(RegexOption.IGNORE_CASE)
                val tabMatch = tabletPattern.find(line)
                if (tabMatch != null) {
                    frequencyHours = tabMatch.groupValues[1].toIntOrNull() ?: 24
                    Log.d(TAG, "✅ Frecuencia (tableta): cada $frequencyHours horas")
                    break
                }
            }

            // Extrae ID de prescripción (si existe en el texto)
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

            // Estima duración a partir de cantidad (si hay "30 tabletas" y frecuencia diaria => 30 días)
            var durationDays = 30
            for (line in lines) {
                val qtyPattern = """(\d+)\s*tabletas?""".toRegex(RegexOption.IGNORE_CASE)
                val match = qtyPattern.find(line)
                if (match != null) {
                    val quantity = match.groupValues[1].toIntOrNull() ?: 30
                    if (frequencyHours == 24) {
                        durationDays = quantity
                    }
                    Log.d(TAG, "✅ Duración estimada: $durationDays días")
                    break
                }
            }

            // Defaults si no identificó medicamento
            if (medicationName.isEmpty()) {
                medicationName = "Medicamento no identificado"
                Log.w(TAG, "⚠️ No se pudo identificar el medicamento")
            }

            // Fechas de inicio/fin
            val startDate = Date()
            val endDate = Calendar.getInstance().apply {
                time = startDate
                add(Calendar.DAY_OF_YEAR, durationDays)
            }.time

            // Construye el objeto final (usa UserMedicationInfo; si tu data class se llama MedicationInfo, cambia el nombre aquí)
            parsedMedication = UserMedicationInfo(
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
                if (parsedMedication == null) throw IllegalStateException("No hay medicamento para guardar.")
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

                firestore.collection("usuarios")
                    .document(userId)
                    .collection("medicamentosUsuario")
                    .add(doc)
                    .await()

                uploading = false
                onDone(true, "✅ Medicamento guardado correctamente")

            } catch (e: Exception) {
                uploading = false
                onDone(false, "❌ Error al guardar: ${e.message}")
            }
        }
    }
}

// ------------------ UI COMPOSABLE ------------------
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadPrescriptionPhotoScreen(vm: PhotoOcrUploadViewModel, finish: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    val scroll = rememberScrollState()

    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && imageUri != null && activity != null) {
            vm.runOcrFromUri(activity, imageUri!!) { msg ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        } else if (!success && imageUri != null) {
            ctx.contentResolver.delete(imageUri!!, null, null)
            imageUri = null
        }
    }

    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && activity != null) {
            imageUri = uri
            vm.runOcrFromUri(activity, uri) {
                Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && activity != null) {
            imageUri = uri
            vm.runOcrFromUri(activity, uri) {
                Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "rx_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) takePicture.launch(uri)
        } else {
            Toast.makeText(ctx, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cargar Prescripción por Foto", fontWeight = FontWeight.Bold) },
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
            // BOTONES
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.PhotoAlbum, contentDescription = "Galería")
                    Spacer(Modifier.width(6.dp))
                    Text("Galería")
                }

                OutlinedButton(
                    onClick = { pickFile.launch("image/*") },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.Folder, contentDescription = "Archivos")
                    Spacer(Modifier.width(6.dp))
                    Text("Archivos")
                }

                Button(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                            == PackageManager.PERMISSION_GRANTED
                        ) {
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, "rx_${System.currentTimeMillis()}.jpg")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                            }
                            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            if (uri != null) {
                                imageUri = uri
                                takePicture.launch(uri)
                            }
                        } else {
                            requestCameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Cámara")
                    Spacer(Modifier.width(6.dp))
                    Text("Cámara")
                }
            }

            Spacer(Modifier.height(16.dp))

            // PREVISUALIZACIÓN
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 320.dp)
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                if (imageUri != null) {
                    Image(
                        painter = rememberAsyncImagePainter(imageUri),
                        contentDescription = "Prescripción",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Selecciona una imagen o toma una foto",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ⬇️ LOADER justo debajo de la imagen
            AnimatedVisibility(visible = vm.processing) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Procesando imagen… extrayendo información",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // INFO MEDICAMENTO DETECTADO
            vm.parsedMedication?.let { med ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("💊 Medicamento Detectado", fontWeight = FontWeight.Bold)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        InfoRow("Nombre:", med.name)
                        InfoRow("Dosis:", "${med.doseMg} mg")
                        InfoRow("Frecuencia:", "Cada ${med.frequencyHours} horas")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // TEXTO EXTRAÍDO (opcional)
            vm.extractedText?.let { txt ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("📝 Texto Extraído", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(txt, style = MaterialTheme.typography.bodySmall)
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
                        Toast.makeText(ctx, "Usuario no autenticado", Toast.LENGTH_SHORT).show()
                    } else {
                        vm.savePrescriptionData(userId) { ok, msg ->
                            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                            if (ok) finish()
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (vm.uploading) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                } else {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Guardar fórmula")
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}


@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(100.dp))
        Text(value)
    }
}
