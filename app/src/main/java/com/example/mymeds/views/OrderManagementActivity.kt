package com.example.mymeds.views

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

private const val TAG = "OrdersManagementActivity"

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║     SISTEMA COMPLETO DE PEDIDOS CON INTEGRACIÓN DE FARMACIAS            ║
 * ║         GPS, INVENTARIO, CARRITO Y RASTREO DE PEDIDOS                   ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 */

// Paleta de colores personalizada
val CustomBlue1 = Color(0xFF9FB3DF)
val CustomBlue2 = Color(0xFF9EC6F3)
val CustomBlue3 = Color(0xFFBDDDE4)

class OrdersManagementActivity : ComponentActivity() {
    private val vm: EnhancedOrdersViewModel by viewModels {
        EnhancedOrdersViewModelFactory(applicationContext)
    }

    private lateinit var fusedLocationClient: FusedLocationProviderClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

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
                    finish = { finish() }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ENHANCED VIEWMODEL
// ═══════════════════════════════════════════════════════════════════════════

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

    private val _pharmacyInventory = MutableStateFlow<List<InventoryMedication>>(emptyList())
    val pharmacyInventory: StateFlow<List<InventoryMedication>> = _pharmacyInventory.asStateFlow()

    private val _cart = MutableStateFlow(ShoppingCart())
    val cart: StateFlow<ShoppingCart> = _cart.asStateFlow()

    private val _userLocation = MutableStateFlow<Location?>(null)
    val userLocation: StateFlow<Location?> = _userLocation.asStateFlow()

    private val _detectedAddress = MutableStateFlow<String>("")
    val detectedAddress: StateFlow<String> = _detectedAddress.asStateFlow()

    private val _userOrders = MutableStateFlow<List<MedicationOrder>>(emptyList())
    val userOrders: StateFlow<List<MedicationOrder>> = _userOrders.asStateFlow()

    var isLoading by mutableStateOf(false)
        private set

    init {
        Log.d(TAG, "🎯 EnhancedOrdersViewModel inicializado")
        loadNearbyPharmacies()
        loadUserOrders()
    }

    fun loadNearbyPharmacies() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "📍 Cargando farmacias cercanas...")
                _uiState.value = OrderUiState.Loading

                val result = pharmacyRepository.getAllPharmacies()

                if (result.isSuccess) {
                    val pharmacies = result.getOrNull() ?: emptyList()
                    val userLoc = _userLocation.value

                    val pharmaciesWithDistance = if (userLoc != null) {
                        pharmacies.map { pharmacy ->
                            val distance = calculateDistance(
                                userLoc.latitude,
                                userLoc.longitude,
                                pharmacy.location.latitude,
                                pharmacy.location.longitude
                            )
                            PharmacyWithDistance(pharmacy, distance)
                        }.sortedBy { it.distanceKm }
                    } else {
                        pharmacies.map { PharmacyWithDistance(it, null) }
                    }

                    _nearbyPharmacies.value = pharmaciesWithDistance
                    _uiState.value = OrderUiState.Success
                    Log.d(TAG, "✅ ${pharmaciesWithDistance.size} farmacias cargadas")
                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "❌ Error cargando farmacias: ${error?.message}")
                    _uiState.value = OrderUiState.Error(error?.message ?: "Error desconocido")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en loadNearbyPharmacies", e)
                _uiState.value = OrderUiState.Error(e.message ?: "Error desconocido")
            }
        }
    }

    fun updateUserLocation(location: Location) {
        viewModelScope.launch {
            _userLocation.value = location
            Log.d(TAG, "📍 Ubicación actualizada: ${location.latitude}, ${location.longitude}")
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
                    val address = addresses[0]
                    val fullAddress = buildString {
                        if (address.thoroughfare != null) append("${address.thoroughfare} ")
                        if (address.subThoroughfare != null) append("${address.subThoroughfare}, ")
                        if (address.locality != null) append("${address.locality}, ")
                        if (address.adminArea != null) append("${address.adminArea}")
                    }.trim()

                    _detectedAddress.value = fullAddress
                    Log.d(TAG, "📍 Dirección detectada: $fullAddress")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error detectando dirección", e)
            }
        }
    }

    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    fun selectPharmacy(pharmacy: PhysicalPoint) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "🏪 Farmacia seleccionada: ${pharmacy.name}")
                _selectedPharmacy.value = pharmacy
                _uiState.value = OrderUiState.Loading

                val result = pharmacyRepository.getPharmacyInventoryWithDetails(pharmacy.id)

                if (result.isSuccess) {
                    val inventory = result.getOrNull() ?: emptyList()
                    _pharmacyInventory.value = inventory
                    _cart.value = ShoppingCart(
                        pharmacyId = pharmacy.id,
                        pharmacyName = pharmacy.name
                    )
                    _uiState.value = OrderUiState.Success
                    Log.d(TAG, "✅ Inventario cargado: ${inventory.size} medicamentos")
                } else {
                    val error = result.exceptionOrNull()
                    Log.e(TAG, "❌ Error cargando inventario: ${error?.message}")
                    _uiState.value = OrderUiState.Error(error?.message ?: "Error cargando inventario")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en selectPharmacy", e)
                _uiState.value = OrderUiState.Error(e.message ?: "Error desconocido")
            }
        }
    }

    fun deselectPharmacy() {
        _selectedPharmacy.value = null
        _pharmacyInventory.value = emptyList()
        _cart.value = ShoppingCart()
        Log.d(TAG, "🏪 Farmacia deseleccionada, carrito limpiado")
    }

    fun addToCart(medication: InventoryMedication, quantity: Int = 1) {
        val currentCart = _cart.value
        if (currentCart.pharmacyId != _selectedPharmacy.value?.id) {
            Log.w(TAG, "⚠️ Farmacia no coincide con carrito actual")
            return
        }
        currentCart.addItem(medication, quantity)
        _cart.value = currentCart.copy()
        Log.d(TAG, "🛒 Agregado al carrito: ${medication.nombre} x$quantity")
    }

    fun updateCartItemQuantity(medicationId: String, newQuantity: Int) {
        val currentCart = _cart.value
        currentCart.updateQuantity(medicationId, newQuantity)
        _cart.value = currentCart.copy()
        Log.d(TAG, "🛒 Cantidad actualizada: $medicationId -> $newQuantity")
    }

    fun removeFromCart(medicationId: String) {
        val currentCart = _cart.value
        currentCart.removeItem(medicationId)
        _cart.value = currentCart.copy()
        Log.d(TAG, "🛒 Removido del carrito: $medicationId")
    }

    fun clearCart() {
        _cart.value = ShoppingCart(
            pharmacyId = _selectedPharmacy.value?.id ?: "",
            pharmacyName = _selectedPharmacy.value?.name ?: ""
        )
        Log.d(TAG, "🛒 Carrito limpiado")
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
            onError("Usuario no autenticado")
            return
        }

        val pharmacy = _selectedPharmacy.value
        if (pharmacy == null) {
            onError("No hay farmacia seleccionada")
            return
        }

        val currentCart = _cart.value
        if (currentCart.isEmpty()) {
            onError("El carrito está vacío")
            return
        }

        viewModelScope.launch {
            try {
                isLoading = true
                Log.d(TAG, "📦 Creando pedido...")

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
                    Log.d(TAG, "✅ Pedido creado exitosamente: $orderId")
                } else {
                    val error = result.exceptionOrNull()!!
                    isLoading = false
                    onError(error.message ?: "Error creando pedido")
                    Log.e(TAG, "❌ Error creando pedido: ${error.message}")
                }
            } catch (e: Exception) {
                isLoading = false
                onError(e.message ?: "Error desconocido")
                Log.e(TAG, "❌ Error en createOrder", e)
            }
        }
    }

    fun loadUserOrders() {
        val userId = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                Log.d(TAG, "📋 Cargando pedidos del usuario...")
                val result = ordersRepository.getUserOrders(userId)
                if (result.isSuccess) {
                    val orders = result.getOrNull() ?: emptyList()
                    _userOrders.value = orders
                    Log.d(TAG, "✅ ${orders.size} pedidos cargados")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en loadUserOrders", e)
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
                    val error = result.exceptionOrNull()!!
                    isLoading = false
                    onError(error.message ?: "Error cancelando pedido")
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

sealed class OrderUiState {
    object Loading : OrderUiState()
    object Success : OrderUiState()
    data class Error(val message: String) : OrderUiState()
}

data class PharmacyWithDistance(
    val pharmacy: PhysicalPoint,
    val distanceKm: Double?
) {
    fun getDistanceText(): String {
        return distanceKm?.let {
            if (it < 1.0) "${(it * 1000).toInt()}m"
            else "${String.format("%.1f", it)}km"
        } ?: "Distancia desconocida"
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// UI - COMPOSE
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedOrdersManagementScreen(
    vm: EnhancedOrdersViewModel,
    fusedLocationClient: FusedLocationProviderClient,
    finish: () -> Unit
) {
    val context = LocalContext.current

    var selectedTab by remember { mutableStateOf(0) }
    var showCartSheet by remember { mutableStateOf(false) }
    var showCheckoutDialog by remember { mutableStateOf(false) }

    val nearbyPharmacies by vm.nearbyPharmacies.collectAsState()
    val selectedPharmacy by vm.selectedPharmacy.collectAsState()
    val pharmacyInventory by vm.pharmacyInventory.collectAsState()
    val cart by vm.cart.collectAsState()
    val userOrders by vm.userOrders.collectAsState()
    val uiState by vm.uiState.collectAsState()
    val detectedAddress by vm.detectedAddress.collectAsState()

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
                        if (selectedPharmacy != null) selectedPharmacy!!.name
                        else "Gestión de Pedidos",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedPharmacy != null) {
                            vm.deselectPharmacy()
                        } else {
                            finish()
                        }
                    }) {
                        Icon(Icons.Filled.ArrowBack, "Volver")
                    }
                },
                actions = {
                    if (selectedPharmacy != null) {
                        if (cart.getTotalItems() > 0) {
                            BadgedBox(
                                badge = {
                                    Badge(
                                        containerColor = Color(0xFFFF5252)
                                    ) {
                                        Text(
                                            "${cart.getTotalItems()}",
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            ) {
                                IconButton(onClick = { showCartSheet = true }) {
                                    Icon(
                                        Icons.Filled.ShoppingCart,
                                        "Ver carrito",
                                        tint = CustomBlue2
                                    )
                                }
                            }
                        } else {
                            IconButton(onClick = { }) {
                                Icon(
                                    Icons.Filled.ShoppingCart,
                                    "Carrito vacío",
                                    tint = Color.Gray.copy(alpha = 0.3f)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CustomBlue1
                )
            )
        }
    ) { padding ->
        when {
            selectedPharmacy != null -> {
                Box(modifier = Modifier.padding(padding)) {
                    PharmacyInventoryView(
                        pharmacy = selectedPharmacy!!,
                        inventory = pharmacyInventory,
                        cart = cart,
                        uiState = uiState,
                        onAddToCart = { medication, quantity ->
                            vm.addToCart(medication, quantity)
                            Toast.makeText(
                                context,
                                "✅ ${medication.nombre} agregado al carrito",
                                Toast.LENGTH_SHORT
                            ).show()
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
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = CustomBlue3
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text("Farmacias", color = Color.Black) },
                            icon = {Icon(Icons.Filled.LocalPharmacy, null, tint = Color.Black)},

                            )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text("Mis Pedidos", color = Color.Black) },
                            icon = { Icon(Icons.Filled.ShoppingCart, null, tint = Color.Black) },
                        )
                    }

                    when (selectedTab) {
                        0 -> PharmaciesListTab(
                            pharmacies = nearbyPharmacies,
                            uiState = uiState,
                            onSelectPharmacy = { vm.selectPharmacy(it.pharmacy) }
                        )
                        1 -> OrdersHistoryTab(
                            orders = userOrders,
                            onCancelOrder = { orderId ->
                                vm.cancelOrder(
                                    orderId = orderId,
                                    onSuccess = {
                                        Toast.makeText(
                                            context,
                                            "✅ Pedido cancelado exitosamente",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    onError = { error ->
                                        Toast.makeText(
                                            context,
                                            "❌ Error: $error",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                )
                            },
                            onTrackOrder = { order ->
                                Toast.makeText(
                                    context,
                                    "🚚 Rastreando pedido #${order.id.take(8)}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showCartSheet) {
        CartBottomSheet(
            cart = cart,
            pharmacy = selectedPharmacy!!,
            onDismiss = { showCartSheet = false },
            onUpdateQuantity = { medicationId, newQuantity ->
                vm.updateCartItemQuantity(medicationId, newQuantity)
            },
            onRemoveItem = { medicationId ->
                vm.removeFromCart(medicationId)
                Toast.makeText(context, "Item eliminado del carrito", Toast.LENGTH_SHORT).show()
            },
            onCheckout = {
                showCartSheet = false
                showCheckoutDialog = true
            }
        )
    }

    if (showCheckoutDialog) {
        CheckoutDialog(
            cart = cart,
            pharmacy = selectedPharmacy!!,
            detectedAddress = detectedAddress,
            onDismiss = { showCheckoutDialog = false },
            onConfirm = { deliveryType, address, phone, notes ->
                vm.createOrder(
                    deliveryType = deliveryType,
                    deliveryAddress = address,
                    phoneNumber = phone,
                    notes = notes,
                    onSuccess = { orderId ->
                        showCheckoutDialog = false
                        Toast.makeText(
                            context,
                            "✅ Pedido creado exitosamente",
                            Toast.LENGTH_SHORT
                        ).show()
                        vm.deselectPharmacy()
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
            if (location != null) {
                Log.d(TAG, "📍 Ubicación obtenida: ${location.latitude}, ${location.longitude}")
                onLocation(location)
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "❌ Error en getCurrentLocation", e)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CART BOTTOM SHEET
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
                    Text(
                        "Mi Carrito",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${cart.getTotalItems()} items",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Gray
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, "Cerrar")
                }
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
                        Text(
                            pharmacy.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            pharmacy.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
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
                        Icon(
                            Icons.Filled.ShoppingCart,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = CustomBlue1.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Tu carrito está vacío",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(cart.items) { item ->
                        CartItemCard(
                            item = item,
                            onUpdateQuantity = { newQuantity ->
                                onUpdateQuantity(item.medicationId, newQuantity)
                            },
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
                            Text(
                                "Subtotal (${cart.getTotalItems()} items):",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "$${cart.calculateTotal()}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "TOTAL:",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "$${cart.calculateTotal()}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = CustomBlue2
                            )
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
                    Text(
                        "Proceder al Checkout",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White
                    )
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
                Text(
                    item.medicationName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2
                )
                if (item.principioActivo.isNotEmpty()) {
                    Text(
                        item.principioActivo,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "$${item.pricePerUnit} c/u",
                    style = MaterialTheme.typography.bodySmall,
                    color = CustomBlue2,
                    fontWeight = FontWeight.SemiBold
                )

                if (item.exceedsStock()) {
                    Text(
                        "⚠️ Stock insuficiente (${item.stock} disponibles)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFFFF5252)
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilledTonalIconButton(
                        onClick = { if (item.quantity > 1) onUpdateQuantity(item.quantity - 1) },
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = CustomBlue3
                        )
                    ) {
                        Icon(Icons.Filled.Remove, null, modifier = Modifier.size(16.dp))
                    }

                    Text(
                        "${item.quantity}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(32.dp),
                        textAlign = TextAlign.Center
                    )

                    FilledTonalIconButton(
                        onClick = { if (item.quantity < item.stock) onUpdateQuantity(item.quantity + 1) },
                        modifier = Modifier.size(32.dp),
                        enabled = item.quantity < item.stock,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = CustomBlue3
                        )
                    ) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        "$${item.getSubtotal()}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CustomBlue2
                    )

                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            "Eliminar",
                            tint = Color(0xFFFF5252),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHARMACY LIST
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun PharmaciesListTab(
    pharmacies: List<PharmacyWithDistance>,
    uiState: OrderUiState,
    onSelectPharmacy: (PharmacyWithDistance) -> Unit
) {
    when (uiState) {
        is OrderUiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CustomBlue2)
            }
        }
        is OrderUiState.Error -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Error,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFFF5252)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Error: ${uiState.message}", color = Color(0xFFFF5252))
                }
            }
        }
        else -> {
            if (pharmacies.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.LocalPharmacy,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = CustomBlue1.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No hay farmacias disponibles",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(pharmacies) { pharmacyWithDistance ->
                        PharmacyCard(
                            pharmacyWithDistance = pharmacyWithDistance,
                            onClick = { onSelectPharmacy(pharmacyWithDistance) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PharmacyCard(
    pharmacyWithDistance: PharmacyWithDistance,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.LocalPharmacy, null, modifier = Modifier.size(48.dp), tint = CustomBlue2)

            Spacer(Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(pharmacyWithDistance.pharmacy.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.Black
                )
                Spacer(Modifier.height(4.dp))
                Text(pharmacyWithDistance.pharmacy.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
                if (pharmacyWithDistance.pharmacy.phone.isNotEmpty()) {
                    Text("📞 ${pharmacyWithDistance.pharmacy.phone}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black
                    )
                }
                Text("🕐 ${pharmacyWithDistance.pharmacy.openingHours}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                if (pharmacyWithDistance.distanceKm != null) {
                    Surface(shape = RoundedCornerShape(8.dp), color = CustomBlue3) {
                        Text(
                            pharmacyWithDistance.getDistanceText(),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Icon(Icons.Filled.ChevronRight, null, tint = Color.Black.copy(alpha = 0.3f))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PHARMACY INVENTORY VIEW
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun PharmacyInventoryView(
    pharmacy: PhysicalPoint,
    inventory: List<InventoryMedication>,
    cart: ShoppingCart,
    uiState: OrderUiState,
    onAddToCart: (InventoryMedication, Int) -> Unit,
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
                    Icon(
                        Icons.Filled.Error,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = Color(0xFFFF5252)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("Error: ${uiState.message}", color = Color(0xFFFF5252))
                }
            }
        }
        else -> {
            if (inventory.isEmpty()) {
                Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.MedicalServices,
                            null,
                            modifier = Modifier.size(64.dp),
                            tint = CustomBlue1.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No hay medicamentos en inventario",
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.Gray
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        PharmacyInfoHeader(pharmacy)
                    }

                    items(inventory) { medication ->
                        MedicationInventoryCard(
                            medication = medication,
                            onAddToCart = onAddToCart
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PharmacyInfoHeader(pharmacy: PhysicalPoint) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CustomBlue3)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Información de la Farmacia",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text("📍 ${pharmacy.address}", style = MaterialTheme.typography.bodySmall)
            Text("📞 ${pharmacy.phone}", style = MaterialTheme.typography.bodySmall)
            Text("🕐 ${pharmacy.openingHours}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun MedicationInventoryCard(
    medication: InventoryMedication,
    onAddToCart: (InventoryMedication, Int) -> Unit
) {
    var quantity by remember { mutableStateOf(1) }

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
                    Text(medication.nombre,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    if (medication.principioActivo.isNotEmpty()) {
                        Text(medication.principioActivo,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Black
                        )
                    }
                }

                Surface(shape = RoundedCornerShape(8.dp), color = CustomBlue3) {
                    Text(
                        "$${medication.precioUnidad}",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (medication.descripcion.isNotEmpty()) {
                Text(medication.descripcion,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Black
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Stock: ${medication.stock} unidades",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (medication.stock < 10) Color(0xFFFF5252) else Color.Black,
                    fontWeight = FontWeight.SemiBold
                )
                if (medication.presentacion.isNotEmpty()) {
                    Text(medication.presentacion,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black
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

                    Text("$quantity",
                        modifier = Modifier.padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )

                    FilledTonalIconButton(
                        onClick = { if (quantity < medication.stock) quantity++ },
                        modifier = Modifier.size(36.dp),
                        enabled = quantity < medication.stock,
                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = CustomBlue3)
                    ) { Icon(Icons.Filled.Add, null, modifier = Modifier.size(20.dp)) }
                }

                Button(
                    onClick = { onAddToCart(medication, quantity) },
                    enabled = medication.stock > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = CustomBlue2)
                ) {
                    Icon(Icons.Filled.ShoppingCart, null, modifier = Modifier.size(18.dp), tint = Color.White)
                    Spacer(Modifier.width(4.dp))
                    Text("Agregar", color = Color.White)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// CHECKOUT DIALOG
// ═══════════════════════════════════════════════════════════════════════════

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
                        Text(
                            "Resumen del Pedido",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        cart.items.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${item.medicationName} x${item.quantity}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "$${item.getSubtotal()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "TOTAL:",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "$${cart.calculateTotal()}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = CustomBlue2
                            )
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Tipo de entrega:",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )

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
                            if (detectedAddress.isNotEmpty()) {
                                Text("📍 Dirección detectada por GPS")
                            }
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
            ) {
                Text("Confirmar Pedido", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar", color = CustomBlue2)
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// ORDERS HISTORY
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun OrdersHistoryTab(
    orders: List<MedicationOrder>,
    onCancelOrder: (String) -> Unit,
    onTrackOrder: (MedicationOrder) -> Unit
) {
    if (orders.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.ShoppingCart,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = CustomBlue1.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No tienes pedidos registrados",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.Gray
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(orders) { order ->
                OrderHistoryCard(
                    order = order,
                    onCancelOrder = onCancelOrder,
                    onTrackOrder = onTrackOrder
                )
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Pedido #${order.id.take(8)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                OrderStatusBadge(order.status)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text(
                "🏪 ${order.pharmacyName}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "📍 ${order.pharmacyAddress}",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(Modifier.height(8.dp))

            Text(
                "📅 ${order.createdAt?.toDate()?.let { SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(it) }}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "🚚 ${if (order.deliveryType == DeliveryType.HOME_DELIVERY) "Entrega a domicilio" else "Recoger en farmacia"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "💰 Total: $${order.totalAmount}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = CustomBlue2
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Medicamentos (${order.items.size}):",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            order.items.take(3).forEach { item ->
                Text(
                    "• ${item.medicationName} x${item.quantity}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }
            if (order.items.size > 3) {
                Text(
                    "... y ${order.items.size - 3} más",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }

            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}
