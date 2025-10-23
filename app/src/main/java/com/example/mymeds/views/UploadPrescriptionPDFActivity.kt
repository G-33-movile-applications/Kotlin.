package com.example.mymeds.views

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns // Necesaria para obtener el nombre del archivo
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
import androidx.compose.foundation.shape.RoundedCornerShape // <-- Importación para el diseño
import androidx.compose.material.icons.Icons // <-- Importación para los iconos
import androidx.compose.material.icons.filled.Add // <-- Importación para el icono '+'
import androidx.compose.material.icons.filled.Description // <-- Importación para el icono de documento
import androidx.compose.material.icons.filled.FileUpload // <-- Importación para el icono de subida
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
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

class PdfOcrUploadViewModel : ViewModel() {
    var extractedText by mutableStateOf<String?>(null)
        private set
    var uploading by mutableStateOf(false)
        private set

    private val storage = FirebaseStorage.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    fun runOcrFromPdf(context: Activity, uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // ... (Se mantiene la lógica de OCR del PDF)
                val temp = copyToTempFile(context, uri, "rx_${System.currentTimeMillis()}.pdf")
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
                            sb.appendLine("— Página ${i + 1} —")
                            sb.appendLine(result.text)
                            sb.appendLine()
                        }
                    }
                }
                renderer.close()
                pfd.close()
                extractedText = sb.toString().ifBlank { null }
            } catch (e: Exception) {
                extractedText = null
                onError(e.message ?: "Falló el OCR")
            }
        }
    }

    fun uploadPdf(context: Activity, uri: Uri, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                uploading = true
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val path = "prescriptions/pdfs/prescription_$ts.pdf"
                val ref = storage.reference.child(path)
                ref.putFile(uri).await()
                val downloadUrl = ref.downloadUrl.await().toString()

                val doc = hashMapOf(
                    "type" to "pdf",
                    "storagePath" to path,
                    "downloadUrl" to downloadUrl,
                    "extractedText" to (extractedText ?: ""),
                    "createdAt" to Date()
                )
                firestore.collection("prescriptions").add(doc).await()
                uploading = false
                onDone(true, "Prescripción subida correctamente")
            } catch (e: Exception) {
                uploading = false
                onDone(false, e.message ?: "Error al subir el PDF")
            }
        }
    }

    private fun copyToTempFile(context: Activity, uri: Uri, name: String): File {
        val out = File(context.cacheDir, name)
        context.contentResolver.openInputStream(uri).use { input ->
            FileOutputStream(out).use { output -> input!!.copyTo(output) }
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

    // Launcher para seleccionar PDF
    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && activity != null) {
            pdfUri = uri
            vm.runOcrFromPdf(activity, uri) {
                Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            }
        } else {
            pdfUri = null // Limpiar si la selección se cancela
        }
    }

    // Código original eliminado: la llamada a vm.uploadPdf no debe estar aquí.

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cargar Prescripción por PDF", fontWeight = FontWeight.Bold) },
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

            // TARJETA DE SELECCIÓN DE ARCHIVO (Clickable)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp)
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                // Al hacer click, redirige a los archivos del celular
                onClick = { pickPdf.launch("application/pdf") }
            ) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (pdfUri != null) {
                            // Muestra el nombre del archivo seleccionado y un ícono de documento
                            Icon(
                                Icons.Filled.Description,
                                contentDescription = "PDF Seleccionado",
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))

                            // Intenta obtener el nombre del archivo
                            val fileName: String = remember(pdfUri) {
                                runCatching {
                                    val cursor = ctx.contentResolver.query(pdfUri!!, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                                    cursor?.use { if (it.moveToFirst()) it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else pdfUri!!.lastPathSegment }
                                }.getOrDefault("Documento PDF") as String
                            }
                            Text(
                                fileName,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text("Click para cambiar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            // Icono grande de "más" para seleccionar
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Seleccionar Archivo",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Toca para seleccionar el archivo PDF",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // TARJETA DE TEXTO DETECTADO (OCR) - FIX de nulidad aplicado aquí
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "📝 Texto Detectado (OCR):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Divider()
                    Spacer(Modifier.height(8.dp))

                    // CORRECCIÓN DE NULIDAD: Muestra el texto o un mensaje si es nulo/vacío
                    val displayText = vm.extractedText
                    val message = if (displayText.isNullOrBlank()) {
                        "No se detectó texto o el PDF está pendiente."
                    } else {
                        displayText
                    }

                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (displayText.isNullOrBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // BOTÓN DE SUBIR
            Button(
                enabled = pdfUri != null && !vm.uploading,
                onClick = {
                    val u = pdfUri
                    if (u != null && activity != null) {
                        vm.uploadPdf(activity, u) { ok, msg ->
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
                    CircularProgressIndicator(
                        strokeWidth = 3.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(Icons.Filled.FileUpload, contentDescription = "Subir")
                    Spacer(Modifier.width(8.dp))
                    Text("Subir Prescripción PDF")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}