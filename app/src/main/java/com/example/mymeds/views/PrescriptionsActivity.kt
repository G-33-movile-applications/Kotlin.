package com.example.mymeds.views

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import java.text.SimpleDateFormat
import java.util.Locale

// ======== Modelo ========
data class UserPrescription(
    val id: String = "",
    val fileName: String = "",
    val fileUrl: String = "",
    val uploadedAt: Timestamp? = null,
    val status: String = "pendiente",   // pendiente | aprobado | rechazado | en_proceso
    val notes: String = ""
)

// ======== Repository ========
class PrescriptionsRepository(
    private val db: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    fun observeUserPrescriptions(
        userId: String,
        onData: (List<UserPrescription>) -> Unit,
        onError: (Throwable) -> Unit
    ): ListenerRegistration {
        val ref = db.collection("usuarios")
            .document(userId)
            .collection("prescripcionesUsuario")
            .orderBy("uploadedAt", Query.Direction.DESCENDING)

        return ref.addSnapshotListener { snap, e ->
            if (e != null) {
                onError(e)
                return@addSnapshotListener
            }
            val list = snap?.documents?.map { doc ->
                UserPrescription(
                    id = doc.id,
                    fileName = doc.getString("fileName") ?: doc.getString("nombreArchivo") ?: "Archivo",
                    fileUrl = doc.getString("fileUrl") ?: doc.getString("url") ?: "",
                    uploadedAt = doc.getTimestamp("uploadedAt") ?: doc.getTimestamp("fechaSubida"),
                    status = doc.getString("status") ?: "pendiente",
                    notes = doc.getString("notes") ?: doc.getString("comentarios") ?: ""
                )
            } ?: emptyList()
            onData(list)
        }
    }
}

// ======== UI State ========
sealed class PrescriptionsUiState {
    object Loading : PrescriptionsUiState()
    data class Success(val items: List<UserPrescription>) : PrescriptionsUiState()
    data class Error(val message: String) : PrescriptionsUiState()
}

// ======== ViewModel ========
class PrescriptionsViewModel : androidx.lifecycle.ViewModel() {
    private val repo = PrescriptionsRepository()
    private val auth = FirebaseAuth.getInstance()

    private var registration: ListenerRegistration? = null

    private val _ui = mutableStateOf<PrescriptionsUiState>(PrescriptionsUiState.Loading)
    val ui: State<PrescriptionsUiState> get() = _ui

    fun start() {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            _ui.value = PrescriptionsUiState.Error("Usuario no autenticado")
            return
        }
        _ui.value = PrescriptionsUiState.Loading
        registration?.remove()
        registration = repo.observeUserPrescriptions(
            userId = uid,
            onData = { list -> _ui.value = PrescriptionsUiState.Success(list) },
            onError = { e -> _ui.value = PrescriptionsUiState.Error(e.message ?: "Error desconocido") }
        )
    }

    fun refresh() = start()

    override fun onCleared() {
        super.onCleared()
        registration?.remove()
    }
}

// ======== Activity ========
class PrescriptionsActivity : ComponentActivity() {

    private val vm: PrescriptionsViewModel by viewModels()

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Si usas tu tema global, cámbialo por MyMedsTheme { ... }
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF6B9BD8),
                    surface = Color.White
                )
            ) {
                LaunchedEffect(Unit) { vm.start() }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Tus prescripciones", color = Color.White) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Filled.ArrowBack, contentDescription = null, tint = Color.White)
                                }
                            },
                            actions = {
                                IconButton(onClick = { vm.refresh() }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refrescar", tint = Color.White)
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = Color(0xFF6B9BD8)
                            )
                        )
                    }
                ) { pv ->
                    Box(
                        Modifier
                            .padding(pv)
                            .fillMaxSize()
                            .background(Color(0xFFF5F5F5))
                    ) {
                        when (val state = vm.ui.value) {
                            is PrescriptionsUiState.Loading -> {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            }
                            is PrescriptionsUiState.Error -> {
                                ErrorView(
                                    message = state.message,
                                    onRetry = { vm.refresh() }
                                )
                            }
                            is PrescriptionsUiState.Success -> {
                                if (state.items.isEmpty()) {
                                    EmptyView()
                                } else {
                                    PrescriptionsList(
                                        items = state.items,
                                        onOpen = { url -> openUrl(url) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Silencio, o muestra un Snackbar/Toast si prefieres
        }
    }
}

// ======== Composables ========

@Composable
private fun PrescriptionsList(
    items: List<UserPrescription>,
    onOpen: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { p ->
            PrescriptionCard(p, onOpen)
        }
    }
}

@Composable
private fun PrescriptionCard(
    p: UserPrescription,
    onOpen: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFCBDEF3)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Description, contentDescription = null, tint = Color.Black)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = p.fileName.ifBlank { "Archivo" },
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
                StatusChip(p.status)
            }

            Spacer(Modifier.height(8.dp))

            val sdf = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
            val fecha = p.uploadedAt?.toDate()?.let { sdf.format(it) } ?: "—"

            Text("Fecha de subida: $fecha", color = Color.Black)
            if (p.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("Notas: ${p.notes}", color = Color.Black)
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                val enabled = p.fileUrl.isNotBlank()
                AssistChip(
                    onClick = { if (enabled) onOpen(p.fileUrl) },
                    label = { Text(if (enabled) "Abrir archivo" else "Sin URL", color = Color.Black) },
                    leadingIcon = {
                        Icon(Icons.Filled.CloudDownload, contentDescription = null, tint = Color.Black)
                    },
                    enabled = enabled
                )
            }
        }
    }
}

@Composable
private fun StatusChip(statusRaw: String) {
    val (label, color) = when (statusRaw.lowercase(Locale.getDefault())) {
        "aprobado" -> "Aprobado" to Color(0xFF4CAF50)
        "rechazado" -> "Rechazado" to Color(0xFFFF5252)
        "en_proceso", "procesando", "en proceso" -> "En proceso" to Color(0xFFFFA000)
        else -> "Pendiente" to Color(0xFF9FB3DF)
    }
    Surface(
        color = color.copy(alpha = 0.2f),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun EmptyView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Description, contentDescription = null, tint = Color.Black)
            Spacer(Modifier.height(8.dp))
            Text("Aún no has subido prescripciones", color = Color.Black)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Error: $message", color = Color(0xFFFF5252))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onRetry) { Text("Reintentar") }
        }
    }
}
