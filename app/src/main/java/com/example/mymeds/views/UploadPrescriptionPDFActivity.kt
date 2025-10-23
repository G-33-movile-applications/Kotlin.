package com.example.mymeds.views

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
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
                            sb.appendLine("— Page ${i + 1} —")
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
                onError(e.message ?: "OCR failed")
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

    val pickPdf = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && activity != null) {
            pdfUri = uri
            vm.runOcrFromPdf(activity, uri) {
                Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show()
            }
        }
    }

    vm.uploadPdf(activity!!, pdfUri!!) { ok, msg ->
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        if (ok) finish()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cargar Prescripción por PDF", fontWeight = FontWeight.Bold) }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(scroll),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = { pickPdf.launch("application/pdf") },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Seleccionar PDF") }

            Spacer(Modifier.height(16.dp))

            Text("Texto detectado", style = MaterialTheme.typography.titleMedium)
            Text(
                vm.extractedText ?: "—",
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )

            Spacer(Modifier.height(16.dp))

            Button(
                enabled = pdfUri != null && !vm.uploading,
                onClick = {
                    vm.uploadPdf(ctx, pdfUri!!) { ok, msg ->
                        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                        if (ok) finish()
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (vm.uploading) CircularProgressIndicator(strokeWidth = 2.dp)
                else Text("Subir prescripción")
            }
        }
    }
}
