package com.example.mymeds.views

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mymeds.models.*
import com.example.mymeds.repository.OrdersRepository
import com.example.mymeds.repository.PharmacyInventoryRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private const val TAG = "OrdersManagementActivity"

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║  PEDIDOS POR PRESCRIPCIÓN + INVENTARIO REAL DE FARMACIA                 ║
 * ║  GPS, carrito con tope por stock y checkout                             ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * Los medicamentos se obtienen de: /usuarios/{userId}/prescripcionesUsuario/{id}/medicamentosPrescripcion
 * Al seleccionar una farmacia se hace MERGE con su inventario para traer precio y stock reales.
 */

// Paleta de colores
val CustomBlue1 = Color(0xFF9FB3DF)
val CustomBlue2 = Color(0xFF9EC6F3)
val CustomBlue3 = Color(0xFFBDDDE4)

// Modelo UI para medicamentos de prescripción (extendido con info del inventario)
data class PrescriptionMedicationItem(
    val id: String = "",
    val prescriptionId: String = "",
    val medicationRef: String = "",
    val name: String = "",
    val doseMg: Int = 0,
    val frequencyHours: Int = 24,
    val quantity: Int = 1,
    val principioActivo: String = "",
    val presentacion: String = "",
    val laboratorio: String = "",
    val precioUnidad: Int = 0,
    val stock: Int? = null,
    val inventoryId: String? = null // 👈 NUEVO
)


// Agrupador prescripción + medicamentos
data class PrescriptionWithMedications(
    val prescription: Prescription,
    val medications: List<PrescriptionMedicationItem>
)

class OrdersManagementActivity : ComponentActivity() {
    private val vm: EnhancedOrdersViewModel by viewModels {
        EnhancedOrdersViewModelFactory(applicationContext)
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val pharmacyId = intent.getStringExtra("PHARMACY_ID")
        val pharmacyName = intent.getStringExtra("PHARMACY_NAME")
        val fromMap = intent.getBooleanExtra("FROM_MAP", false)

        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = CustomBlue2,
                    primaryContainer = CustomBlue3,
                    secondary = CustomBlue1,
                    tertiary = CustomBlue2,
                    surface = Color.White,
                    background = Color(0xFFF5F5F5)
                )
            ) {
                EnhancedOrdersManagementScreen(
                    vm = vm,
                    fusedLocationClient = fusedLocationClient,
                    finish = { finish() },
                    preselectedPharmacyId = pharmacyId,
                    fromMap = fromMap
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// VIEWMODEL
// ═══════════════════════════════════════════════════════════════════════════

sealed class OrderUiState {
    object Loading : OrderUiState()
    object Success : OrderUiState()
    data class Error(val message: String) : OrderUiState()
}

data class PharmacyWithDistance(
    val pharmacy: PhysicalPoint,
    val distanceKm: Double?
) {
    fun getDistanceText(): String = distanceKm?.let {
        if (it < 1.0) "${(it * 1000).toInt()}m" else "${String.format("%.1f", it)}km"
    } ?: "Distancia desconocida"
}

@RequiresApi(Build.VERSION_CODES.O)
class EnhancedOrdersViewModel(
    private val context: Context
) : ViewModel() {

    private val ordersRepository = OrdersRepository()
    private val pharmacyRepository = PharmacyInventoryRepository()
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private val _uiState = MutableStateFlow<OrderUiState>(OrderUiState.Loading)
    val uiState: StateFlow<OrderUiState> = _uiState.asStateFlow()

    private val _nearbyPharmacies = MutableStateFlow<List<PharmacyWithDistance>>(emptyList())
    val nearbyPharmacies: StateFlow<List<PharmacyWithDistance>> = _nearbyPharmacies.asStateFlow()

    private val _selectedPharmacy = MutableStateFlow<PhysicalPoint?>(null)
    val selectedPharmacy: StateFlow<PhysicalPoint?> = _selectedPharmacy.asStateFlow()

    private val _userPrescriptions = MutableStateFlow<List<PrescriptionWithMedications>>(emptyList())
    val userPrescriptions: StateFlow<List<PrescriptionWithMedications>> = _userPrescriptions.asStateFlow()

    private val _selectedPrescription = MutableStateFlow<PrescriptionWithMedications?>(null)
    val selectedPrescription: StateFlow<PrescriptionWithMedications?> = _selectedPrescription.asStateFlow()

    private val _prescriptionMedications = MutableStateFlow<List<PrescriptionMedicationItem>>(emptyList())
    val prescriptionMedications: StateFlow<List<PrescriptionMedicationItem>> = _prescriptionMedications.asStateFlow()

    private val _cart = MutableStateFlow(ShoppingCart())
    val cart: StateFlow<ShoppingCart> = _cart.asStateFlow()

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    private val _detectedAddress = MutableStateFlow("")
    val detectedAddress: StateFlow<String> = _detectedAddress.asStateFlow()

    private val _userOrders = MutableStateFlow<List<MedicationOrder>>(emptyList())
    val userOrders: StateFlow<List<MedicationOrder>> = _userOrders.asStateFlow()

    var isLoading by mutableStateOf(false)
        private set

    init {
        Log.d(TAG, "🎯 EnhancedOrdersViewModel inicializado")
        loadNearbyPharmacies()
        loadUserPrescriptions()
        loadUserOrders()
    }

    fun loadNearbyPharmacies() {
        viewModelScope.launch {
            try {
                _uiState.value = OrderUiState.Loading
                val result = pharmacyRepository.getAllPharmacies()
                if (result.isSuccess) {
                    val pharmacies = result.getOrNull().orEmpty()
                    val userLoc = _userLocation.value
                    val list = if (userLoc != null) {
                        pharmacies.map { p ->
                            val d = calculateDistance(
                                userLoc.latitude, userLoc.longitude,
                                p.location.latitude, p.location.longitude
                            )
                            PharmacyWithDistance(p, d)
                        }.sortedBy { it.distanceKm }
                    } else pharmacies.map { PharmacyWithDistance(it, null) }
                    _nearbyPharmacies.value = list
                    _uiState.value = OrderUiState.Success
                } else {
                    _uiState.value = OrderUiState.Error(result.exceptionOrNull()?.message ?: "Error desconocido")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ loadNearbyPharmacies", e)
                _uiState.value = OrderUiState.Error(e.message ?: "Error desconocido")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ [Draft] Error leyendo $path", e)
        null
    }

    @RequiresApi(Build.VERSION_CODES.O)
    fun loadUserPrescriptions() {
        val userId = auth.currentUser?.uid ?: run {
            Log.e(TAG, "❌ Usuario no autenticado")
            return
        }
        viewModelScope.launch {
            try {
                _uiState.value = OrderUiState.Loading
                val docs = firestore.collection("usuarios")
                    .document(userId)
                    .collection("prescripcionesUsuario")
                    .whereEqualTo("activa", true)
                    .get()
                    .await()

                val result = docs.documents.mapNotNull { doc ->
                    try {
                        val p = Prescription(
                            id = doc.id,
                            activa = doc.getBoolean("activa") ?: false,
                            diagnostico = doc.getString("diagnostico") ?: "",
                            fechaCreacion = doc.getTimestamp("fechaCreacion"),
                            medico = doc.getString("medico") ?: ""
                        )

                        val medsSnap = doc.reference
                            .collection("medicamentosPrescripcion")
                            .get()
                            .await()

                        val meds = medsSnap.documents.mapNotNull { m ->
                            try {
                                val medicationRef = m.getString("medicationRef") ?: ""
                                val details = if (medicationRef.isNotEmpty()) {
                                    getMedicationDetails(medicationRef)
                                } else null

                                PrescriptionMedicationItem(
                                    id = m.id,
                                    prescriptionId = doc.id,
                                    medicationRef = medicationRef,
                                    name = m.getString("name") ?: "",
                                    doseMg = m.getLong("doseMg")?.toInt() ?: 0,
                                    frequencyHours = m.getLong("frequencyHours")?.toInt() ?: 24,
                                    quantity = m.getLong("quantity")?.toInt() ?: 1,
                                    principioActivo = details?.get("principioActivo") as? String ?: "",
                                    presentacion = details?.get("presentacion") as? String ?: "",
                                    laboratorio = details?.get("laboratorio") as? String ?: "",
                                    precioUnidad = (details?.get("precioUnidad") as? Long)?.toInt() ?: 0,
                                    stock = (details?.get("stock") as? Long)?.toInt()
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "Error parseando medicamento ${m.id}: ${e.message}")
                                null
                            }
                        }

                        if (meds.isNotEmpty()) PrescriptionWithMedications(p, meds) else null
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando prescripción ${doc.id}: ${e.message}")
                        null
                    }
                }

                _userPrescriptions.value = result
                _uiState.value = OrderUiState.Success
                Log.d(TAG, "✅ Prescripciones cargadas: ${result.size}")
            } catch (e: Exception) {
                Log.e(TAG, "❌ loadUserPrescriptions", e)
                _uiState.value = OrderUiState.Error(e.message ?: "Error desconocido")
            }
        }
    }

    private suspend fun getMedicationDetails(medicationRef: String): Map<String, Any>? {
        return try {
            val medId = medicationRef.substringAfterLast("/").trim()
            if (medId.isEmpty()) return null

            val medDoc = firestore.collection("medicamentosGlobales")
                .document(medId)
                .get()
                .await()
            if (medDoc.exists()) medDoc.data else null
        } catch (e: Exception) {
            Log.e(TAG, "Error obteniendo detalles del medicamento: ${e.message}")
            null
        }
    }


    fun updateUserLocation(location: Location) {
        viewModelScope.launch {
            _userLocation.value = location
            loadNearbyPharmacies()
            detectAddress(location)
        }
    }

    private fun detectAddress(location: Location) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.getDefault())
                val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
                if (!addresses.isNullOrEmpty()) {
                    val a = addresses[0]
                    val full = buildString {
                        if (a.thoroughfare != null) append("${a.thoroughfare} ")
                        if (a.subThoroughfare != null) append("${a.subThoroughfare}, ")
                        if (a.locality != null) append("${a.locality}, ")
                        if (a.adminArea != null) append("${a.adminArea}")
                    }.trim()
                    _detectedAddress.value = full
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ detectAddress", e)
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return R * c
    }

    /** ------------------ MERGE con inventario de farmacia ------------------ **/
    /** ------------------ MERGE con inventario de farmacia (por nombre) ------------------ **/
    private fun mergePrescriptionWithInventory(
        meds: List<PrescriptionMedicationItem>,
        inventory: List<InventoryMedication>
    ): List<PrescriptionMedicationItem> {

        fun toId(ref: String) = ref.substringAfterLast("/").trim()
        fun normName(s: String) = s.lowercase()
            .replace("\\s+".toRegex(), " ")
            .replace(" mg", "mg")
            .replace("[^a-z0-9áéíóúñ ]".toRegex(), "")
            .trim()

        val byRefId = inventory.associateBy { toId(it.medicamentoRef) }
        val byName  = inventory.associateBy { normName(it.nombre) }

        return meds.map { pm ->
            val pmRefId = toId(pm.medicationRef)
            val inv = (if (pmRefId.isNotEmpty()) byRefId[pmRefId] else null)
                ?: byName[normName(pm.name)]

            if (inv != null) {
                pm.copy(
                    inventoryId     = inv.id,                 // 👈 guarda ID real de inventario
                    precioUnidad    = if (pm.precioUnidad > 0) pm.precioUnidad else inv.precioUnidad,
                    stock           = inv.stock,
                    principioActivo = pm.principioActivo.ifEmpty { inv.principioActivo },
                    presentacion    = pm.presentacion.ifEmpty { inv.presentacion },
                    laboratorio     = pm.laboratorio.ifEmpty { inv.laboratorio ?: "" }
                )
            } else pm
        }
    }



    /** Selecciona prescripción y farmacia, y fusiona con inventario */
    fun selectPrescription(prescriptionWithMeds: PrescriptionWithMedications, pharmacy: PhysicalPoint) {
        viewModelScope.launch {
            try {
                _selectedPrescription.value = prescriptionWithMeds
                _selectedPharmacy.value = pharmacy
                _uiState.value = OrderUiState.Loading

                val invResult = pharmacyRepository.getPharmacyInventoryWithDetails(pharmacy.id)
                val inventory = if (invResult.isSuccess) invResult.getOrNull().orEmpty() else emptyList()

                val merged = mergePrescriptionWithInventory(prescriptionWithMeds.medications, inventory)
                _prescriptionMedications.value = merged

                _cart.value = ShoppingCart(pharmacyId = pharmacy.id, pharmacyName = pharmacy.name)
                _uiState.value = OrderUiState.Success
                Log.d(TAG, "✅ Merge hecho con inventario (${merged.size} items)")
            } catch (e: Exception) {
                Log.e(TAG, "❌ selectPrescription", e)
                _uiState.value = OrderUiState.Error(e.message ?: "Error desconocido")
            }
        }
    }

    fun deselectPrescription() {
        _selectedPrescription.value = null
        _selectedPharmacy.value = null
        _prescriptionMedications.value = emptyList()
        _cart.value = ShoppingCart()
    }

    /** Carrito con tope por stock */
    fun addToCart(medication: PrescriptionMedicationItem, quantity: Int = 1) {
        val currentCart = _cart.value
        if (currentCart.pharmacyId != _selectedPharmacy.value?.id) {
            Log.w(TAG, "⚠️ Farmacia no coincide con carrito actual")
            return
        }

        // ⚠️ ESTA ES LA CLAVE: ID de inventario real
        val inventoryId = medication.inventoryId ?: run {
            Log.e(TAG, "❌ Este medicamento no tiene inventoryId; no se puede validar stock de inventario")
            Toast.makeText(context, "No se puede agregar: falta vincular al inventario", Toast.LENGTH_SHORT).show()
            return
        }

        val stock = medication.stock ?: 0
        if (stock <= 0) {
            Toast.makeText(context, "Sin stock disponible", Toast.LENGTH_SHORT).show()
            return
        }

        val q = quantity.coerceIn(1, stock)
        val existing = currentCart.items.find { it.medicationId == inventoryId }

        if (existing != null) {
            val newQ = (existing.quantity + q).coerceAtMost(stock)
            currentCart.updateQuantity(inventoryId, newQ)
        } else {
            currentCart.items.add(
                CartItem(
                    medicationId   = inventoryId,                 // 👈 usa el ID de inventario
                    medicationRef  = medication.medicationRef,
                    medicationName = medication.name,
                    quantity       = q,
                    pricePerUnit   = medication.precioUnidad,
                    stock          = stock,
                    batch          = "",
                    principioActivo= medication.principioActivo,
                    presentacion   = medication.presentacion,
                    laboratorio    = medication.laboratorio
                )
            )
        }

        _cart.value = currentCart.copy()
        Log.d(TAG, "🛒 ${medication.name} x$q (stock=$stock) [invId=$inventoryId]")
    }


    fun updateCartItemQuantity(medicationId: String, newQuantity: Int) {
        val current = _cart.value
        val item = current.items.find { it.medicationId == medicationId } ?: return
        val capped = newQuantity.coerceIn(1, item.stock)
        current.updateQuantity(medicationId, capped)
        _cart.value = current.copy()
    }

    fun removeFromCart(medicationId: String) {
        val current = _cart.value
        current.removeItem(medicationId)
        _cart.value = current.copy()
    }

    fun clearCart() {
        _cart.value = ShoppingCart(
            pharmacyId = _selectedPharmacy.value?.id ?: "",
            pharmacyName = _selectedPharmacy.value?.name ?: ""
        )
    }

    fun createOrder(
        deliveryType: DeliveryType,
        deliveryAddress: String,
        phoneNumber: String,
        notes: String,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            onError("Usuario no autenticado"); return
        }
        val pharmacy = _selectedPharmacy.value
        if (pharmacy == null) {
            onError("No hay farmacia seleccionada"); return
        }
        val currentCart = _cart.value
        if (currentCart.isEmpty()) {
            onError("El carrito está vacío"); return
        }
    }

        viewModelScope.launch {
            try {
                isLoading = true
                val result = ordersRepository.createOrder(
                    cart = currentCart,
                    userId = userId,
                    pharmacy = pharmacy,
                    deliveryType = deliveryType,
                    deliveryAddress = deliveryAddress,
                    phoneNumber = phoneNumber,
                    notes = notes
                )
                if (result.isSuccess) {
                    val orderId = result.getOrNull()!!
                    clearCart()
                    loadUserOrders()
                    isLoading = false
                    onSuccess(orderId)
                } else {
                    val e = result.exceptionOrNull()
                    isLoading = false
                    onError(e?.message ?: "Error creando pedido")
                }
            } catch (e: Exception) {
                isLoading = false
                onError(e.message ?: "Error desconocido")
            }
        }
    }

    fun loadUserOrders() {
        val userId = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val result = ordersRepository.getUserOrders(userId)
                if (result.isSuccess) _userOrders.value = result.getOrNull().orEmpty()
            } catch (e: Exception) {
                Log.e(TAG, "❌ loadUserOrders", e)
            }
        }
    }

    fun cancelOrder(orderId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val userId = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                isLoading = true
                val result = ordersRepository.cancelOrder(userId, orderId)
                if (result.isSuccess) {
                    loadUserOrders()
                    isLoading = false
                    onSuccess()
                } else {
                    isLoading = false
                    onError(result.exceptionOrNull()?.message ?: "Error cancelando pedido")
                }
            } catch (e: Exception) {
                isLoading = false
                onError(e.message ?: "Error desconocido")
            }
        }
    }
}

class EnhancedOrdersViewModelFactory(
    private val context: Context
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(EnhancedOrdersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return EnhancedOrdersViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// UI
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedOrdersManagementScreen(
    vm: EnhancedOrdersViewModel,
    fusedLocationClient: FusedLocationProviderClient,
    finish: () -> Unit,
    preselectedPharmacyId: String? = null,
    fromMap: Boolean = false
) {
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(if (fromMap) 0 else 0) }
    var showCartSheet by remember { mutableStateOf(false) }
    var showCheckoutDialog by remember { mutableStateOf(false) }

    val nearbyPharmacies by vm.nearbyPharmacies.collectAsState()
    val selectedPharmacy by vm.selectedPharmacy.collectAsState()
    val userPrescriptions by vm.userPrescriptions.collectAsState()
    val selectedPrescription by vm.selectedPrescription.collectAsState()
    val prescriptionMedications by vm.prescriptionMedications.collectAsState()
    val cart by vm.cart.collectAsState()
    val userOrders by vm.userOrders.collectAsState()
    val uiState by vm.uiState.collectAsState()
    val detectedAddress by vm.detectedAddress.collectAsState()


    LaunchedEffect(preselectedPharmacyId, nearbyPharmacies) {
        if (fromMap && preselectedPharmacyId != null && nearbyPharmacies.isNotEmpty()) {
            val pharmacy = nearbyPharmacies.find { it.pharmacy.id == preselectedPharmacyId }
            if (pharmacy != null) {

                Toast.makeText(
                    context,
                    "Farmacia '${pharmacy.pharmacy.name}' seleccionada. Por favor elige una prescripción.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            getCurrentLocation(fusedLocationClient, context) { location ->
                vm.updateUserLocation(location)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        } else {
            getCurrentLocation(fusedLocationClient, context) { location ->
                vm.updateUserLocation(location)
            }
        }
    }



    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selectedPrescription != null) "Medicamentos de Prescripción"
                        else "Gestión de Pedidos",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedPrescription != null) vm.deselectPrescription() else finish()
                    }) { Icon(Icons.Filled.ArrowBack, "Volver") }
                },
                actions = {
                    if (cart.getTotalItems() > 0) {
                        BadgedBox(badge = {
                            Badge(containerColor = Color(0xFFFF5252)) {
                                Text("${cart.getTotalItems()}", style = MaterialTheme.typography.labelSmall)
                            }
                        }) {
                            IconButton(onClick = {
                                if (selectedPharmacy != null) showCartSheet = true
                                else Toast.makeText(context, "Selecciona una farmacia primero", Toast.LENGTH_SHORT).show()
                            }) { Icon(Icons.Filled.ShoppingCart, "Ver carrito") }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CustomBlue1)
            )
        }
    ) { padding ->
        when {
            selectedPrescription != null -> {
                Box(modifier = Modifier.padding(padding)) {
                    PrescriptionMedicationsView(
                        prescription = selectedPrescription!!,
                        medications = prescriptionMedications,
                        pharmacy = selectedPharmacy!!,
                        cart = cart,
                        uiState = uiState,
                        onAddToCart = { medication, quantity ->
                            vm.addToCart(medication, quantity)
                            Toast.makeText(context, "✅ ${medication.name} agregado", Toast.LENGTH_SHORT).show()
                            showCartSheet = true
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (cart.getTotalItems() > 0) {
                        FloatingActionButton(
                            onClick = { showCartSheet = true },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            containerColor = CustomBlue2
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.ShoppingCart, "Ver carrito", tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Ver Carrito (${cart.getTotalItems()})",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                Column(modifier = Modifier.padding(padding)) {
                    TabRow(selectedTabIndex = selectedTab, containerColor = CustomBlue3) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Prescripciones", color = Color.Black) },
                            icon = { Icon(Icons.Filled.MedicalServices, null, tint = Color.Black) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Mis Pedidos", color = Color.Black) },
                            icon = { Icon(Icons.Filled.ShoppingCart, null, tint = Color.Black) }
                        )
                    }

                    when (selectedTab) {
                        0 -> PrescriptionsListTab(
                            prescriptions = userPrescriptions,
                            pharmacies = nearbyPharmacies,
                            uiState = uiState,
                            preselectedPharmacyId = if (fromMap) preselectedPharmacyId else null,
                            onSelectPrescription = { prescription, pharmacy ->
                                vm.selectPrescription(prescription, pharmacy)
                            }
                        )
                        1 -> OrdersHistoryTab(
                            orders = userOrders,
                            onCancelOrder = { orderId ->
                                vm.cancelOrder(
                                    orderId = orderId,
                                    onSuccess = {
                                        Toast.makeText(context, "✅ Pedido cancelado", Toast.LENGTH_SHORT).show()
                                    },
                                    onError = { error ->
                                        Toast.makeText(context, "❌ Error: $error", Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            onTrackOrder = { order ->
                                Toast.makeText(context, "🚚 Rastreando pedido #${order.id.take(8)}", Toast.LENGTH_SHORT).show()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCartSheet && selectedPharmacy != null && !cart.isEmpty()) {
        CartBottomSheet(
            cart = cart,
            pharmacy = selectedPharmacy!!,
            onDismiss = { showCartSheet = false },
            onUpdateQuantity = { medicationId, newQuantity ->
                vm.updateCartItemQuantity(medicationId, newQuantity)
                if (vm.cart.value.isEmpty()) showCartSheet = false
            },
            onRemoveItem = { medicationId ->
                vm.removeFromCart(medicationId)
                Toast.makeText(context, "Item eliminado", Toast.LENGTH_SHORT).show()
                if (vm.cart.value.isEmpty()) showCartSheet = false
            },
            onCheckout = {
                showCartSheet = false
                showCheckoutDialog = true
            }
        )
    } else if (showCartSheet && selectedPharmacy == null) {
        Toast.makeText(context, "Selecciona una farmacia para continuar", Toast.LENGTH_SHORT).show()
        showCartSheet = false
    }

    if (showCheckoutDialog) {
        CheckoutDialog(
            cart = cart,
            pharmacy = selectedPharmacy ?: return,
            detectedAddress = detectedAddress,
            onDismiss = { showCheckoutDialog = false },
            onConfirm = { deliveryType, address, phone, notes ->
                vm.createOrder(
                    deliveryType = deliveryType,
                    deliveryAddress = address,
                    phoneNumber = phone,
                    notes = notes,
                    onSuccess = {
                        showCheckoutDialog = false
                        Toast.makeText(context, "✅ Pedido creado", Toast.LENGTH_SHORT).show()
                        vm.deselectPrescription()
                    },
                    onError = { error ->
                        Toast.makeText(context, "❌ Error: $error", Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }
}

@SuppressLint("MissingPermission")
fun getCurrentLocation(
    fusedLocationClient: FusedLocationProviderClient,
    context: Context,
    onLocation: (Location) -> Unit
) {
    try {
        val cancellationToken = CancellationTokenSource()
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationToken.token
        ).addOnSuccessListener { location ->
            if (location != null) onLocation(location)
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ getCurrentLocation", e)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Pestaña: Prescripciones (con selector de farmacia)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun PrescriptionsListTab(
    prescriptions: List<PrescriptionWithMedications>,
    pharmacies: List<PharmacyWithDistance>,
    uiState: OrderUiState,
    onSelectPrescription: (PrescriptionWithMedications, PhysicalPoint) -> Unit,
    preselectedPharmacyId: String? = null
) {
    var selectedPrescriptionForPharmacy by remember { mutableStateOf<PrescriptionWithMedications?>(null) }
    var showPharmacySelector by remember { mutableStateOf(false) }


    val preselectedPharmacy = remember(preselectedPharmacyId, pharmacies) {
        if (preselectedPharmacyId != null) {
            pharmacies.find { it.pharmacy.id == preselectedPharmacyId }?.pharmacy
        } else null
    }

    when (uiState) {
        is OrderUiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CustomBlue2)
            }
        }
        is OrderUiState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Error, null, modifier = Modifier.size(64.dp), tint = Color(0xFFFF5252))
                    Spacer(Modifier.height(16.dp))
                    Text("Error: ${uiState.message}", color = Color(0xFFFF5252))
                }
            }
        }
        else -> {
            if (prescriptions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.MedicalServices, null, modifier = Modifier.size(64.dp), tint = CustomBlue1.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Text("No tienes prescripciones activas", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // 🔥 Mostrar mensaje si hay farmacia preseleccionada
                    if (preselectedPharmacy != null) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(containerColor = CustomBlue1)
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Filled.LocalPharmacy, null, tint = CustomBlue2)
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Farmacia seleccionada: ${preselectedPharmacy.name}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    items(prescriptions) { item ->
                        PrescriptionCard(
                            prescriptionWithMeds = item,
                            onClick = {
                                // 🔥 Si hay farmacia preseleccionada, ir directo
                                if (preselectedPharmacy != null) {
                                    onSelectPrescription(item, preselectedPharmacy)
                                } else {
                                    selectedPrescriptionForPharmacy = item
                                    showPharmacySelector = true
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (showPharmacySelector && selectedPrescriptionForPharmacy != null) {
        PharmacySelectorDialog(
            pharmacies = pharmacies,
            onDismiss = {
                showPharmacySelector = false
                selectedPrescriptionForPharmacy = null
            },
            onSelectPharmacy = { pharmacy ->
                onSelectPrescription(selectedPrescriptionForPharmacy!!, pharmacy)
                showPharmacySelector = false
                selectedPrescriptionForPharmacy = null
            }
        )
    }
}

@Composable
fun PrescriptionCard(
    prescriptionWithMeds: PrescriptionWithMedications,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.MedicalServices, null, modifier = Modifier.size(40.dp), tint = CustomBlue2)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Prescripción #${prescriptionWithMeds.prescription.id.take(8)}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        prescriptionWithMeds.prescription.fechaCreacion?.let {
                            Text(
                                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(it.toDate()),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (prescriptionWithMeds.prescription.activa) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color.Gray.copy(alpha = 0.2f)
                ) {
                    Text(
                        if (prescriptionWithMeds.prescription.activa) "Activa" else "Inactiva",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (prescriptionWithMeds.prescription.activa) Color(0xFF4CAF50) else Color.Gray,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            if (prescriptionWithMeds.prescription.diagnostico.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("Diagnóstico: ${prescriptionWithMeds.prescription.diagnostico}", style = MaterialTheme.typography.bodyMedium)
            }
            if (prescriptionWithMeds.prescription.medico.isNotEmpty()) {
                Text("Médico: ${prescriptionWithMeds.prescription.medico}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${prescriptionWithMeds.medications.size} medicamento(s)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CustomBlue2
                )
                Icon(Icons.Filled.ChevronRight, "Ver medicamentos", tint = CustomBlue2)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PharmacySelectorDialog(
    pharmacies: List<PharmacyWithDistance>,
    onDismiss: () -> Unit,
    onSelectPharmacy: (PhysicalPoint) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Selecciona una farmacia") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(pharmacies) { p ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPharmacy(p.pharmacy) },
                        colors = CardDefaults.cardColors(containerColor = CustomBlue3)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.LocalPharmacy, null, tint = CustomBlue2)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.pharmacy.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                Text(p.pharmacy.address, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            p.distanceKm?.let {
                                Text(p.getDistanceText(), style = MaterialTheme.typography.labelSmall, color = CustomBlue2, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = CustomBlue2) } }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// Vista: Medicamentos de la prescripción (con stock/price fusionados)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun PrescriptionMedicationsView(
    prescription: PrescriptionWithMedications,
    medications: List<PrescriptionMedicationItem>,
    pharmacy: PhysicalPoint,
    cart: ShoppingCart,
    uiState: OrderUiState,
    onAddToCart: (PrescriptionMedicationItem, Int) -> Unit,
    modifier: Modifier = Modifier
) {
    when (uiState) {
        is OrderUiState.Loading -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CustomBlue2)
            }
        }
        is OrderUiState.Error -> {
            Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Error, null, modifier = Modifier.size(64.dp), tint = Color(0xFFFF5252))
                    Spacer(Modifier.height(16.dp))
                    Text("Error: ${uiState.message}", color = Color(0xFFFF5252))
                }
            }
        }
        else -> {
            if (medications.isEmpty()) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.MedicalServices, null, modifier = Modifier.size(64.dp), tint = CustomBlue1.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Text("No hay medicamentos en esta prescripción", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { PrescriptionInfoHeader(prescription.prescription, pharmacy) }
                    items(medications) { m ->
                        PrescriptionMedicationCard(
                            medication = m,
                            onAddToCart = onAddToCart
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PrescriptionInfoHeader(prescription: Prescription, pharmacy: PhysicalPoint) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomBlue3)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Prescripción", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text("ID: ${prescription.id.take(8)}", style = MaterialTheme.typography.bodySmall)
                    if (prescription.diagnostico.isNotEmpty())
                        Text(prescription.diagnostico, style = MaterialTheme.typography.bodySmall)
                    if (prescription.medico.isNotEmpty())
                        Text("Dr. ${prescription.medico}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Farmacia Seleccionada", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("🏪 ${pharmacy.name}", style = MaterialTheme.typography.bodySmall)
            Text("📍 ${pharmacy.address}", style = MaterialTheme.typography.bodySmall)
            Text("📞 ${pharmacy.phone}", style = MaterialTheme.typography.bodySmall)
            Text("🕐 ${pharmacy.openingHours}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun PrescriptionMedicationCard(
    medication: PrescriptionMedicationItem,
    onAddToCart: (PrescriptionMedicationItem, Int) -> Unit
) {
    var quantity by remember { mutableStateOf(medication.quantity.coerceAtLeast(1)) }
    val stock = medication.stock ?: 999
    val canAdd = stock > 0
    val lowStock = stock in 1..9

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(medication.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.Black)
                    if (medication.principioActivo.isNotEmpty()) {
                        Text(medication.principioActivo, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    if (medication.doseMg > 0) {
                        Text(
                            "Dosis: ${medication.doseMg}mg cada ${medication.frequencyHours}h",
                            style = MaterialTheme.typography.bodySmall,
                            color = CustomBlue2,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Surface(shape = RoundedCornerShape(8.dp), color = CustomBlue3) {
                    Text(
                        if (medication.precioUnidad > 0) "$${medication.precioUnidad}" else "Precio a confirmar",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    if (medication.presentacion.isNotEmpty())
                        Text(medication.presentacion, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    if (medication.laboratorio.isNotEmpty())
                        Text(medication.laboratorio, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    // Stock en letra chiquita (rojo si bajo o sin stock)
                    Text(
                        text = if (canAdd) "Stock: $stock" else "Sin stock",
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            !canAdd -> Color(0xFFFF5252)
                            lowStock -> Color(0xFFFF5252)
                            else -> Color.Gray
                        },
                        fontWeight = if (!canAdd || lowStock) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(
                        onClick = { if (quantity > 1) quantity-- },
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomBlue3)
                    ) { Icon(Icons.Filled.Remove, null, modifier = Modifier.size(20.dp)) }

                    Text(
                        "$quantity",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    FilledTonalIconButton(
                        onClick = { if (quantity < stock) quantity++ },
                        enabled = quantity < stock,
                        modifier = Modifier.size(36.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomBlue3)
                    ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp)) }
                }

                Button(
                    onClick = { onAddToCart(medication, quantity) },
                    enabled = canAdd,
                    colors = ButtonDefaults.buttonColors(containerColor = CustomBlue2)
                ) {
                    Icon(Icons.Filled.ShoppingCart, null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text(if (canAdd) "Agregar" else "Sin stock", color = Color.White)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Carrito (bottom sheet) + Checkout + Historial
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CartBottomSheet(
    cart: ShoppingCart,
    pharmacy: PhysicalPoint,
    onDismiss: () -> Unit,
    onUpdateQuantity: (String, Int) -> Unit,
    onRemoveItem: (String) -> Unit,
    onCheckout: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Mi Carrito", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("${cart.getTotalItems()} items", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cerrar") }
            }

            Spacer(Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = CustomBlue1)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.LocalPharmacy, null, tint = CustomBlue2)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(pharmacy.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(pharmacy.address, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            if (cart.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Filled.ShoppingCart, null, modifier = Modifier.size(64.dp), tint = CustomBlue1.copy(alpha = 0.5f))
                        Spacer(Modifier.height(16.dp))
                        Text("Tu carrito está vacío", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(cart.items) { item ->
                        CartItemCard(
                            item = item,
                            onUpdateQuantity = { newQuantity -> onUpdateQuantity(item.medicationId, newQuantity) },
                            onRemove = { onRemoveItem(item.medicationId) }
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Subtotal (${cart.getTotalItems()} items):", style = MaterialTheme.typography.bodyMedium)
                            Text("$${cart.calculateTotal()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("TOTAL:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("$${cart.calculateTotal()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = CustomBlue2)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onCheckout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    enabled = cart.getTotalItems() > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = CustomBlue2)
                ) {
                    Icon(Icons.Filled.ShoppingCart, null, tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Proceder al Checkout", style = MaterialTheme.typography.titleMedium, color = Color.White)
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun CartItemCard(
    item: CartItem,
    onUpdateQuantity: (Int) -> Unit,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp),
                color = CustomBlue3
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.MedicalServices, null, tint = CustomBlue2)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(item.medicationName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2)
                if (item.principioActivo.isNotEmpty()) {
                    Text(item.principioActivo, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Spacer(Modifier.height(4.dp))
                Text("$${item.pricePerUnit} c/u", style = MaterialTheme.typography.bodySmall, color = CustomBlue2, fontWeight = FontWeight.SemiBold)
                if (item.quantity > item.stock) {
                    Text(
                        "⚠️ Stock insuficiente (${item.stock} disponibles)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF5252)
                    )
                } else {
                    Text(
                        "Stock: ${item.stock}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilledTonalIconButton(
                        onClick = { if (item.quantity > 1) onUpdateQuantity(item.quantity - 1) },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomBlue3)
                    ) { Icon(Icons.Filled.Remove, null, modifier = Modifier.size(16.dp)) }

                    Text(
                        "${item.quantity}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.Center
                    )

                    FilledTonalIconButton(
                        onClick = { if (item.quantity < item.stock) onUpdateQuantity(item.quantity + 1) },
                        enabled = item.quantity < item.stock,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomBlue3)
                    ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp)) }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$${item.getSubtotal()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = CustomBlue2)
                    IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "Eliminar", tint = Color(0xFFFF5252), modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckoutDialog(
    cart: ShoppingCart,
    pharmacy: PhysicalPoint,
    detectedAddress: String,
    onDismiss: () -> Unit,
    onConfirm: (DeliveryType, String, String, String) -> Unit
) {
    var selectedDeliveryType by remember { mutableStateOf(DeliveryType.HOME_DELIVERY) }
    var address by remember { mutableStateOf(detectedAddress) }
    var phoneNumber by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Confirmar Pedido") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Card(colors = CardDefaults.cardColors(containerColor = CustomBlue3)) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("Resumen del Pedido", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        cart.items.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${item.medicationName} x${item.quantity}", style = MaterialTheme.typography.bodySmall)
                                Text("$${item.getSubtotal()}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("TOTAL:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Text("$${cart.calculateTotal()}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = CustomBlue2)
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text("Tipo de entrega:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedDeliveryType = DeliveryType.HOME_DELIVERY },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedDeliveryType == DeliveryType.HOME_DELIVERY,
                        onClick = { selectedDeliveryType = DeliveryType.HOME_DELIVERY },
                        colors = RadioButtonDefaults.colors(selectedColor = CustomBlue2)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("🚚 Entrega a domicilio")
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedDeliveryType = DeliveryType.IN_PERSON_PICKUP },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = selectedDeliveryType == DeliveryType.IN_PERSON_PICKUP,
                        onClick = { selectedDeliveryType = DeliveryType.IN_PERSON_PICKUP },
                        colors = RadioButtonDefaults.colors(selectedColor = CustomBlue2)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("🏪 Recoger en farmacia")
                }

                if (selectedDeliveryType == DeliveryType.HOME_DELIVERY) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("Dirección de entrega") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = { Icon(Icons.Filled.Home, null) },
                        supportingText = {
                            if (detectedAddress.isNotEmpty()) Text("📍 Dirección detectada por GPS")
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = CustomBlue2,
                            focusedLabelColor = CustomBlue2
                        )
                    )
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Teléfono de contacto") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Filled.Phone, null) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CustomBlue2,
                        focusedLabelColor = CustomBlue2
                    )
                )

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notas adicionales (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CustomBlue2,
                        focusedLabelColor = CustomBlue2
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedDeliveryType, address, phoneNumber, notes) },
                enabled = (selectedDeliveryType == DeliveryType.IN_PERSON_PICKUP ||
                        (selectedDeliveryType == DeliveryType.HOME_DELIVERY && address.isNotBlank())) &&
                        phoneNumber.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = CustomBlue2)
            ) { Text("Confirmar Pedido", color = Color.White) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = CustomBlue2) } }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// Historial de pedidos
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun OrdersHistoryTab(
    orders: List<MedicationOrder>,
    onCancelOrder: (String) -> Unit,
    onTrackOrder: (MedicationOrder) -> Unit
) {
    if (orders.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ShoppingCart, null, modifier = Modifier.size(64.dp), tint = CustomBlue1.copy(alpha = 0.5f))
                Spacer(Modifier.height(16.dp))
                Text("No tienes pedidos registrados", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(orders) { order ->
                OrderHistoryCard(order = order, onCancelOrder = onCancelOrder, onTrackOrder = onTrackOrder)
            }
        }
    }
}

@Composable
fun OrderHistoryCard(
    order: MedicationOrder,
    onCancelOrder: (String) -> Unit,
    onTrackOrder: (MedicationOrder) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Pedido #${order.id.take(8)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OrderStatusBadge(order.status)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("🏪 ${order.pharmacyName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text("📍 ${order.pharmacyAddress}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Spacer(Modifier.height(8.dp))

            Text(
                "📅 ${order.createdAt?.toDate()?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(it) }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text("🚚 ${if (order.deliveryType == DeliveryType.HOME_DELIVERY) "Entrega a domicilio" else "Recoger en farmacia"}", style = MaterialTheme.typography.bodySmall)
            Text("💰 Total: $${order.totalAmount}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = CustomBlue2)

            Spacer(Modifier.height(8.dp))

            Text("Medicamentos (${order.items.size}):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            order.items.take(3).forEach { item ->
                Text("• ${item.medicationName} x${item.quantity}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            }
            if (order.items.size > 3) {
                Text("... y ${order.items.size - 3} más", style = MaterialTheme.typography.bodySmall, color = Color.Gray, fontStyle = FontStyle.Italic)
            }

            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (order.isActive()) {
                    Button(
                        onClick = { onTrackOrder(order) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = CustomBlue2)
                    ) {
                        Icon(Icons.Filled.LocationOn, null, modifier = Modifier.size(18.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("Rastrear", color = Color.White)
                    }
                }
                if (order.canBeCancelled()) {
                    OutlinedButton(
                        onClick = { onCancelOrder(order.id) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF5252))
                    ) {
                        Icon(Icons.Filled.Cancel, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Cancelar")
                    }
                }
            }
        }
    }
}

@Composable
fun OrderStatusBadge(status: OrderStatus) {
    val (text, color) = when (status) {
        OrderStatus.PENDING -> "Pendiente" to CustomBlue1
        OrderStatus.CONFIRMED -> "Confirmado" to CustomBlue2
        OrderStatus.IN_TRANSIT -> "En camino" to CustomBlue2
        OrderStatus.READY_PICKUP -> "Listo" to Color(0xFF4CAF50)
        OrderStatus.DELIVERED -> "Entregado" to Color(0xFF4CAF50)
        OrderStatus.COMPLETED -> "Completado" to Color(0xFF4CAF50)
        OrderStatus.CANCELLED -> "Cancelado" to Color(0xFFFF5252)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = color.copy(alpha = 0.2f)) {
        Text(text, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

