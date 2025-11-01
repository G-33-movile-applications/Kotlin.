package com.example.mymeds.views

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.launch
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale

// ======== Modelos ========
data class UserPrescription(
    val id: String = "",
    val fileName: String = "",
    val fileUrl: String = "",
    val uploadedAt: Timestamp? = null,
    val status: String = "pendiente",
    val notes: String = "",
    val activa: Boolean = false,
    val diagnostico: String = "",
    val medico: String = "",
    val totalItems: Int = 0,
    val fromOCR: Boolean = false
)

data class PrescriptionMedication(
    val id: String = "",
    val medicationId: String = "",
    val name: String = "",
    val medicationRef: String = "",
    val doseMg: Int = 0,
    val frequencyHours: Int = 0,
    val prescriptionId: String = "",
    val sourceFile: String = "",
    val active: Boolean = true
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
                    notes = doc.getString("notes") ?: doc.getString("comentarios") ?: "",
                    activa = doc.getBoolean("activa") ?: false,
                    diagnostico = doc.getString("diagnostico") ?: "",
                    medico = doc.getString("medico") ?: "",
                    totalItems = doc.getLong("totalItems")?.toInt() ?: 0,
                    fromOCR = doc.getBoolean("fromOCR") ?: false
                )
            } ?: emptyList()
            onData(list)
        }
    }

    suspend fun getPrescriptionMedications(
        userId: String,
        prescriptionId: String
    ): List<PrescriptionMedication> {
        return try {
            val snapshot = db.collection("usuarios")
                .document(userId)
                .collection("prescripcionesUsuario")
                .document(prescriptionId)
                .collection("medicamentosPrescripcion")
                .get()
                .await()

            snapshot.documents.map { doc ->
                PrescriptionMedication(
                    id = doc.id,
                    medicationId = doc.getString("medicationId") ?: "",
                    name = doc.getString("name") ?: "",
                    medicationRef = doc.getString("medicationRef") ?: "",
                    doseMg = doc.getLong("doseMg")?.toInt() ?: 0,
                    frequencyHours = doc.getLong("frequencyHours")?.toInt() ?: 0,
                    prescriptionId = doc.getString("prescriptionId") ?: "",
                    sourceFile = doc.getString("sourceFile") ?: "",
                    active = doc.getBoolean("active") ?: true
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun togglePrescriptionStatus(
        userId: String,
        prescriptionId: String,
        newStatus: Boolean
    ): Boolean {
        return try {
            db.collection("usuarios")
                .document(userId)
                .collection("prescripcionesUsuario")
                .document(prescriptionId)
                .update("activa", newStatus)
                .await()
            true
        } catch (e: Exception) {
            false
        }
    }
}

// ======== UI State ========
sealed class PrescriptionsUiState {
    object Loading : PrescriptionsUiState()
    data class Success(val items: List<UserPrescription>) : PrescriptionsUiState()
    data class Error(val message: String) : PrescriptionsUiState()
}

private fun PrescriptionsViewModel.togglePrescriptionStatus(
    userId: Any,
    prescriptionId: String,
    newStatus: Boolean
) {
}

// ======== ViewModel ========
class PrescriptionsViewModel : androidx.lifecycle.ViewModel() {
    private val repo = PrescriptionsRepository()
    private val auth = FirebaseAuth.getInstance()

    private var registration: ListenerRegistration? = null

    private val _ui = mutableStateOf<PrescriptionsUiState>(PrescriptionsUiState.Loading)
    val ui: State<PrescriptionsUiState> get() = _ui

    private val _expandedPrescription = mutableStateOf<String?>(null)
    val expandedPrescription: State<String?> get() = _expandedPrescription

    private val _prescriptionMedications = mutableStateOf<Map<String, List<PrescriptionMedication>>>(emptyMap())
    val prescriptionMedications: State<Map<String, List<PrescriptionMedication>>> get() = _prescriptionMedications

    private val _loadingMedications = mutableStateOf<Set<String>>(emptySet())
    val loadingMedications: State<Set<String>> get() = _loadingMedications

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

    fun togglePrescriptionExpansion(prescriptionId: String) {
        // Si la prescripción ya está expandida, la colapsamos.
        if (_expandedPrescription.value == prescriptionId) {
            _expandedPrescription.value = null
        } else {
            // Si no, la expandimos.
            _expandedPrescription.value = prescriptionId
            // ✅ CORRECCIÓN: Lanzamos una corrutina para llamar a la función suspend.
            viewModelScope.launch {
                loadPrescriptionMedications(prescriptionId)
            }
        }
    }

    private suspend fun loadPrescriptionMedications(prescriptionId: String) {
        val uid = auth.currentUser?.uid ?: return

        // ... código para evitar recargas ...

        _loadingMedications.value = _loadingMedications.value + prescriptionId

        // ❌ ERROR AQUÍ: Estás llamando a una función suspend fuera de una corrutina
        val medications = repo.getPrescriptionMedications(uid, prescriptionId)
        _prescriptionMedications.value = _prescriptionMedications.value + (prescriptionId to medications)

        _loadingMedications.value = _loadingMedications.value - prescriptionId
    }

    // Dentro de PrescriptionsViewModel

    fun togglePrescriptionActive(prescriptionId: String, currentStatus: Boolean) {
        viewModelScope.launch {
            try {
                // ✅ Llamamos a la función `suspend` del repositorio.
                val success = repo.togglePrescriptionStatus(
                    userId = auth.currentUser?.uid ?: return@launch,
                    prescriptionId = prescriptionId,
                    newStatus = !currentStatus
                )

                // ✅ CORRECCIÓN: Usamos la variable 'success' como condición.
                if (success) {
                    // Si la operación fue exitosa, llamamos a refresh() como una acción.
                    refresh()
                } else {
                    // Aquí puedes manejar el caso en que la actualización falló,
                    // por ejemplo, mostrando un mensaje al usuario.
                }
            } catch (e: Exception) {
                // Manejar la excepción (mostrar un Toast, log, etc.)
            }
        }
    }


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
                                        expandedPrescription = vm.expandedPrescription.value,
                                        prescriptionMedications = vm.prescriptionMedications.value,
                                        loadingMedications = vm.loadingMedications.value,
                                        onToggleExpansion = { vm.togglePrescriptionExpansion(it) },
                                        onToggleActive = { id, status -> vm.togglePrescriptionActive(id, status) },
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
            // Silencio
        }
    }
}

// ======== Composables ========

@Composable
private fun PrescriptionsList(
    items: List<UserPrescription>,
    expandedPrescription: String?,
    prescriptionMedications: Map<String, List<PrescriptionMedication>>,
    loadingMedications: Set<String>,
    onToggleExpansion: (String) -> Unit,
    onToggleActive: (String, Boolean) -> Unit,
    onOpen: (String) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items) { p ->
            PrescriptionCard(
                prescription = p,
                isExpanded = expandedPrescription == p.id,
                medications = prescriptionMedications[p.id] ?: emptyList(),
                isLoadingMedications = loadingMedications.contains(p.id),
                onToggleExpansion = { onToggleExpansion(p.id) },
                onToggleActive = { onToggleActive(p.id, p.activa) },
                onOpen = onOpen
            )
        }
    }
}

@Composable
private fun PrescriptionCard(
    prescription: UserPrescription,
    isExpanded: Boolean,
    medications: List<PrescriptionMedication>,
    isLoadingMedications: Boolean,
    onToggleExpansion: () -> Unit,
    onToggleActive: () -> Unit,
    onOpen: (String) -> Unit
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (prescription.activa) Color(0xFFE3F2FD) else Color(0xFFF5F5F5)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // HEADER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.Description,
                        contentDescription = null,
                        tint = if (prescription.activa) Color(0xFF1976D2) else Color.Gray
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            text = prescription.fileName.ifBlank { "Archivo" },
                            fontWeight = FontWeight.Bold,
                            color = Color.Black
                        )
                        if (prescription.fromOCR) {
                            Text(
                                "📋 Prescripción #${prescription.id.take(8)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF1976D2)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusChip(prescription.status)
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onToggleExpansion,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Contraer" else "Expandir",
                            tint = Color(0xFF1976D2)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // INFO BÁSICA
            val sdf = remember { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()) }
            val fecha = prescription.uploadedAt?.toDate()?.let { sdf.format(it) } ?: "—"

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("📅 Fecha: $fecha", style = MaterialTheme.typography.bodySmall, color = Color.Black)
                if (prescription.totalItems > 0) {
                    Text(
                        "💊 ${prescription.totalItems} medicamento(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF1976D2),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            if (prescription.diagnostico.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "🏥 Diagnóstico: ${prescription.diagnostico}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
            }

            if (prescription.medico.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "👨‍⚕️ Médico: ${prescription.medico}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
            }

            if (prescription.notes.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "📝 Notas: ${prescription.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // CONTENIDO EXPANDIDO
            if (isExpanded) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))

                if (isLoadingMedications) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(80.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    }
                } else if (medications.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "No hay medicamentos en esta prescripción",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                } else {
                    Text(
                        "💊 Medicamentos (${medications.size})",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(Modifier.height(8.dp))

                    medications.forEach { med ->
                        MedicationItemCard(med)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ACCIONES
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = prescription.activa,
                        onCheckedChange = { onToggleActive() },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF4CAF50),
                            checkedTrackColor = Color(0xFF4CAF50).copy(alpha = 0.5f)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (prescription.activa) "Activa" else "Inactiva",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (prescription.activa) Color(0xFF4CAF50) else Color.Gray
                    )
                }

                val enabled = prescription.fileUrl.isNotBlank()
                AssistChip(
                    onClick = { if (enabled) onOpen(prescription.fileUrl) },
                    label = {
                        Text(
                            if (enabled) "Abrir archivo" else "Sin URL",
                            color = Color.Black
                        )
                    },
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
private fun MedicationItemCard(medication: PrescriptionMedication) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.MedicalServices,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF1976D2)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        medication.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.Black
                    )
                }

                if (medication.active) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                    ) {
                        Text(
                            "✓",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (medication.doseMg > 0) {
                        Text(
                            "💊 Dosis: ${medication.doseMg}mg",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )
                    }
                    if (medication.frequencyHours > 0) {
                        Text(
                            "⏰ Cada ${medication.frequencyHours} horas",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )
                    }
                    if (medication.sourceFile.isNotBlank()) {
                        Text(
                            "📄 Fuente: ${medication.sourceFile}",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
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
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun EmptyView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.Gray.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Aún no has subido prescripciones",
                color = Color.Gray
            )
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