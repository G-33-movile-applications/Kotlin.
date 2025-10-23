package com.example.mymeds.views

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

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

class PhotoOcrUploadViewModel : ViewModel() {
    var extractedText by mutableStateOf<String?>(null)
        private set
    var uploading by mutableStateOf(false)
        private set

    private val storage = FirebaseStorage.getInstance()
    private val firestore = FirebaseFirestore.getInstance()

    fun runOcrFromUri(activity: Activity, uri: Uri, onError: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val image = InputImage.fromFilePath(activity, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                val result = recognizer.process(image).await()
                extractedText = result.text.ifBlank { null }
            } catch (e: Exception) {
                extractedText = null
                onError(e.message ?: "OCR failed")
            }
        }
    }

    fun upload(uri: Uri, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                uploading = true
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val path = "prescriptions/images/prescription_$ts.jpg"
                val ref = storage.reference.child(path)
                ref.putFile(uri).await()
                val downloadUrl = ref.downloadUrl.await().toString()

                val doc = hashMapOf(
                    "type" to "image",
                    "storagePath" to path,
                    "downloadUrl" to downloadUrl,
                    "extractedText" to (extractedText ?: ""),
                    "createdAt" to Date()
                )
                firestore.collection("prescriptions").add(doc).await()
                uploading = false
                onDone(true, "Prescription uploaded successfully")
            } catch (e: Exception) {
                uploading = false
                onDone(false, e.message ?: "Upload failed")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UploadPrescriptionPhotoScreen(vm: PhotoOcrUploadViewModel, finish: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    val scroll = rememberScrollState()

    // Launcher para tomar foto
    val takePicture = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && imageUri != null && activity != null) {
            vm.runOcrFromUri(activity, imageUri!!) { msg ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        } else {
            if (!success && imageUri != null) {
                // Elimina la URI temporal si la toma de foto falla o se cancela
                ctx.contentResolver.delete(imageUri!!, null, null)
                imageUri = null
            }
        }
    }

    var triggerCamera by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Launcher de permisos
    val requestCameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            triggerCamera?.invoke()
        } else {
            Toast.makeText(ctx, "Permiso de cámara denegado", Toast.LENGTH_SHORT).show()
        }
    }

    // Launcher para seleccionar imagen
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && activity != null) {
            imageUri = uri
            vm.runOcrFromUri(activity, uri) { msg ->
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Función para manejar cámara y permisos
    fun requestCameraAndShoot() {
        if (activity == null) {
            Toast.makeText(ctx, "Error de contexto", Toast.LENGTH_SHORT).show()
            return
        }

        val perm = Manifest.permission.CAMERA
        triggerCamera = {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "rx_${System.currentTimeMillis()}.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            }

            val uri = ctx.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            )

            if (uri == null) {
                Toast.makeText(ctx, "No se pudo crear la imagen", Toast.LENGTH_SHORT).show()
            } else {
                imageUri = uri
                takePicture.launch(uri)
            }
        }

        if (ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) {
            triggerCamera?.invoke()
        } else {
            requestCameraPermission.launch(perm)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cargar Prescripción", fontWeight = FontWeight.Bold) },
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                OutlinedButton(
                    onClick = { pickImage.launch("image/*") },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Filled.PhotoAlbum, contentDescription = "Galería", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Galería")
                }

                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = { requestCameraAndShoot() },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    Icon(Icons.Filled.CameraAlt, contentDescription = "Cámara", modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Tomar Foto")
                }
            }

            Spacer(Modifier.height(12.dp))

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
                        contentDescription = "Vista previa de la prescripción",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Selecciona una imagen o toma una foto",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

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
                    Text(
                        vm.extractedText.takeIf { !it.isNullOrBlank() } ?: "No se detectó texto o la imagen está pendiente.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (vm.extractedText.isNullOrBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            Button(
                enabled = imageUri != null && !vm.uploading,
                onClick = {
                    val u = imageUri
                    if (u != null) {
                        vm.upload(u) { ok, msg ->
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
                    Text("Subir Prescripción")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
