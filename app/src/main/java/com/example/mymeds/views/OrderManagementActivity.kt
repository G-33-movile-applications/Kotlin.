package com.example.mymeds.views

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.tasks.await
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import androidx.datastore.preferences.core.Preferences

private const val TAG = "OrdersActivity"

/**
 * ╔══════════════════════════════════════════════════════════════════════════╗
 * ║           SISTEMA DE PEDIDOS CON MÚLTIPLES TECNOLOGÍAS                   ║
 * ║                    DE PERSISTENCIA DE DATOS                              ║
 * ╚══════════════════════════════════════════════════════════════════════════╝
 *
 * IMPLEMENTACIÓN DE REQUISITOS DE PERSISTENCIA:
 *
 * 1. BD RELACIONAL (Room) - 10 PUNTOS
 *    - Tablas: Medications, Orders, OrderItems
 *    - Relaciones: One-to-Many, Many-to-Many
 *    - DAOs con operaciones CRUD completas
 *    - TypeConverters para tipos complejos
 *
 * 2. BD LLAVE/VALOR (DataStore) - 5 PUNTOS
 *    - Preferencias de usuario (modo entrega, direcciones)
 *    - Configuración de la app
 *    - Almacenamiento tipo key-value asíncrono
 *
 * 3. ARCHIVOS LOCALES - 5 PUNTOS
 *    - Guardado de comprobantes/recibos en JSON
 *    - Almacenamiento en directorio privado de la app
 *    - Operaciones de lectura/escritura de archivos
 *
 * 4. PREFERENCES/DATASTORE - 5 PUNTOS
 *    - SharedPreferences para configuraciones rápidas
 *    - Caché de última sincronización
 *    - Flags de estado de la app
 *
 * TOTAL: 25 PUNTOS
 */

class OrdersManagementActivity : ComponentActivity() {
    private val vm: OrdersViewModel by viewModels {
        OrdersViewModelFactory(applicationContext)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OrdersManagementScreen(vm = vm, finish = { finish() })
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 1. BD RELACIONAL - ROOM (10 PUNTOS)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * ENTIDAD: Medication
 * Representa un medicamento en la base de datos local
 */
@Entity(tableName = "medications")
data class MedicationEntity(
    @PrimaryKey
    val medicationId: String,
    val name: String,
    val doseMg: Int,
    val frequencyHours: Int,
    val stockQuantity: Int,
    val prescriptionId: String,
    val active: Boolean,
    val lastSyncedAt: Long,
    val firebaseDocId: String? = null
)

/**
 * ENTIDAD: Order
 * Representa un pedido de medicamentos
 */
@Entity(
    tableName = "orders",
    indices = [Index(value = ["userId"])]
)
data class OrderEntity(
    @PrimaryKey(autoGenerate = true)
    val orderId: Long = 0,
    val userId: String,
    val orderDate: Long,
    val deliveryType: DeliveryType,
    val deliveryAddress: String?,
    val status: OrderStatus,
    val totalItems: Int,
    val notes: String?,
    val createdAt: Long,
    val syncedToFirebase: Boolean = false,
    val firebaseOrderId: String? = null
)

/**
 * ENTIDAD: OrderItem
 * Representa un ítem dentro de un pedido (relación Many-to-Many)
 */
@Entity(
    tableName = "order_items",
    foreignKeys = [
        ForeignKey(
            entity = OrderEntity::class,
            parentColumns = ["orderId"],
            childColumns = ["orderId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = MedicationEntity::class,
            parentColumns = ["medicationId"],
            childColumns = ["medicationId"],
            onDelete = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["orderId"]), Index(value = ["medicationId"])]
)
data class OrderItemEntity(
    @PrimaryKey(autoGenerate = true)
    val itemId: Long = 0,
    val orderId: Long,
    val medicationId: String,
    val quantity: Int,
    val medicationName: String,
    val doseMg: Int
)

enum class DeliveryType {
    HOME_DELIVERY,      // Entrega a domicilio
    IN_PERSON_PICKUP    // Recoger presencialmente
}

enum class OrderStatus {
    PENDING,      // Pendiente
    CONFIRMED,    // Confirmado
    IN_TRANSIT,   // En camino
    DELIVERED,    // Entregado
    CANCELLED     // Cancelado
}

/**
 * TypeConverter para Room - Convierte enums a/desde base de datos
 */
class Converters {
    @TypeConverter
    fun fromDeliveryType(value: DeliveryType): String = value.name

    @TypeConverter
    fun toDeliveryType(value: String): DeliveryType = DeliveryType.valueOf(value)

    @TypeConverter
    fun fromOrderStatus(value: OrderStatus): String = value.name

    @TypeConverter
    fun toOrderStatus(value: String): OrderStatus = OrderStatus.valueOf(value)
}

/**
 * DAO: MedicationDao
 * Operaciones de base de datos para medicamentos
 */
@Dao
interface MedicationDao {
    @Query("SELECT * FROM medications WHERE active = 1 ORDER BY name ASC")
    fun getAllActiveMedications(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medications WHERE medicationId = :id")
    suspend fun getMedicationById(id: String): MedicationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMedication(medication: MedicationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(medications: List<MedicationEntity>)

    @Update
    suspend fun updateMedication(medication: MedicationEntity)

    @Query("UPDATE medications SET stockQuantity = :quantity WHERE medicationId = :id")
    suspend fun updateStock(id: String, quantity: Int)

    @Query("DELETE FROM medications WHERE medicationId = :id")
    suspend fun deleteMedication(id: String)

    @Query("DELETE FROM medications")
    suspend fun deleteAll()
}

/**
 * DAO: OrderDao
 * Operaciones de base de datos para pedidos
 */
@Dao
interface OrderDao {
    @Query("SELECT * FROM orders WHERE userId = :userId ORDER BY orderDate DESC")
    fun getUserOrders(userId: String): Flow<List<OrderEntity>>

    @Query("SELECT * FROM orders WHERE orderId = :id")
    suspend fun getOrderById(id: Long): OrderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrder(order: OrderEntity): Long

    @Update
    suspend fun updateOrder(order: OrderEntity)

    @Query("UPDATE orders SET status = :status WHERE orderId = :id")
    suspend fun updateOrderStatus(id: Long, status: OrderStatus)

    @Query("DELETE FROM orders WHERE orderId = :id")
    suspend fun deleteOrder(id: Long)
}

/**
 * DAO: OrderItemDao
 * Operaciones para ítems de pedidos
 */
@Dao
interface OrderItemDao {
    @Query("SELECT * FROM order_items WHERE orderId = :orderId")
    suspend fun getOrderItems(orderId: Long): List<OrderItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrderItem(item: OrderItemEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<OrderItemEntity>)

    @Query("DELETE FROM order_items WHERE orderId = :orderId")
    suspend fun deleteOrderItems(orderId: Long)
}

/**
 * Data class para JOIN de Order con sus Items
 */
data class OrderWithItems(
    @Embedded val order: OrderEntity,
    @Relation(
        parentColumn = "orderId",
        entityColumn = "orderId"
    )
    val items: List<OrderItemEntity>
)

/**
 * BASE DE DATOS ROOM PRINCIPAL
 */
@Database(
    entities = [
        MedicationEntity::class,
        OrderEntity::class,
        OrderItemEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicationDao(): MedicationDao
    abstract fun orderDao(): OrderDao
    abstract fun orderItemDao(): OrderItemDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mymeds_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 2. BD LLAVE/VALOR - DATASTORE (5 PUNTOS)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * DATASTORE: Preferences
 * Almacenamiento tipo key-value para preferencias de usuario
 */
// Removed 'private' to make it accessible within the module
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")


object PreferencesKeys {
    val DEFAULT_DELIVERY_TYPE = stringPreferencesKey("default_delivery_type")
    val DEFAULT_ADDRESS = stringPreferencesKey("default_address")
    val PHONE_NUMBER = stringPreferencesKey("phone_number")
    val LAST_SYNC_TIMESTAMP = longPreferencesKey("last_sync_timestamp")
    val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    val AUTO_REORDER_ENABLED = booleanPreferencesKey("auto_reorder_enabled")
}

/**
 * Manager para DataStore
 */
class UserPreferencesManager(private val context: Context) {
    private val dataStore = context.dataStore

    // Leer preferencia de tipo de entrega
    val defaultDeliveryType: Flow<DeliveryType> = dataStore.data
        .map { preferences ->
            val typeString = preferences[PreferencesKeys.DEFAULT_DELIVERY_TYPE] ?: DeliveryType.HOME_DELIVERY.name
            DeliveryType.valueOf(typeString)
        }

    // Leer dirección predeterminada
    val defaultAddress: Flow<String> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.DEFAULT_ADDRESS] ?: ""
        }

    // Guardar preferencia de entrega
    suspend fun saveDefaultDeliveryType(type: DeliveryType) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_DELIVERY_TYPE] = type.name
        }
        Log.d(TAG, "💾 [DataStore] Tipo de entrega guardado: $type")
    }

    // Guardar dirección predeterminada
    suspend fun saveDefaultAddress(address: String) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.DEFAULT_ADDRESS] = address
        }
        Log.d(TAG, "💾 [DataStore] Dirección guardada: $address")
    }

    // Guardar timestamp de última sincronización
    suspend fun saveLastSyncTimestamp(timestamp: Long) {
        dataStore.edit { preferences ->
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] = timestamp
        }
    }

    // Leer última sincronización
    val lastSyncTimestamp: Flow<Long> = dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.LAST_SYNC_TIMESTAMP] ?: 0L
        }
}

// ═══════════════════════════════════════════════════════════════════════════
// 3. ARCHIVOS LOCALES (5 PUNTOS)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Manager para archivos locales
 * Guarda comprobantes/recibos de pedidos en JSON
 */
class LocalFileManager(private val context: Context) {
    private val receiptsDir = File(context.filesDir, "receipts")

    init {
        if (!receiptsDir.exists()) {
            receiptsDir.mkdirs()
            Log.d(TAG, "📁 [Files] Directorio de recibos creado: ${receiptsDir.absolutePath}")
        }
    }

    /**
     * Guarda un recibo de pedido en archivo JSON
     */
    suspend fun saveOrderReceipt(order: OrderWithItems): File? = withContext(Dispatchers.IO) {
        try {
            val fileName = "receipt_${order.order.orderId}_${System.currentTimeMillis()}.json"
            val file = File(receiptsDir, fileName)

            val receiptData = buildString {
                appendLine("{")
                appendLine("  \"orderId\": \"${order.order.orderId}\",")
                appendLine("  \"date\": \"${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(order.order.orderDate))}\",")
                appendLine("  \"deliveryType\": \"${order.order.deliveryType}\",")
                appendLine("  \"status\": \"${order.order.status}\",")
                appendLine("  \"items\": [")
                order.items.forEachIndexed { index, item ->
                    appendLine("    {")
                    appendLine("      \"name\": \"${item.medicationName}\",")
                    appendLine("      \"dose\": \"${item.doseMg}mg\",")
                    appendLine("      \"quantity\": ${item.quantity}")
                    appendLine("    }${if (index < order.items.size - 1) "," else ""}")
                }
                appendLine("  ]")
                appendLine("}")
            }

            FileOutputStream(file).use { output ->
                output.write(receiptData.toByteArray())
            }

            Log.d(TAG, "📄 [Files] Recibo guardado: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Files] Error guardando recibo", e)
            null
        }
    }

    /**
     * Lee un recibo desde archivo
     */
    suspend fun readOrderReceipt(orderId: Long): String? = withContext(Dispatchers.IO) {
        try {
            val files = receiptsDir.listFiles { _, name ->
                name.startsWith("receipt_$orderId")
            }

            if (files.isNullOrEmpty()) {
                Log.w(TAG, "⚠️ [Files] No se encontró recibo para pedido $orderId")
                return@withContext null
            }

            val content = files.first().readText()
            Log.d(TAG, "📄 [Files] Recibo leído para pedido $orderId")
            content
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Files] Error leyendo recibo", e)
            null
        }
    }

    /**
     * Lista todos los recibos guardados
     */
    fun getAllReceipts(): List<File> {
        return receiptsDir.listFiles()?.toList() ?: emptyList()
    }

    /**
     * Elimina un recibo
     */
    suspend fun deleteReceipt(orderId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            val files = receiptsDir.listFiles { _, name ->
                name.startsWith("receipt_$orderId")
            }

            files?.forEach { it.delete() }
            Log.d(TAG, "🗑️ [Files] Recibo eliminado para pedido $orderId")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ [Files] Error eliminando recibo", e)
            false
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 4. SHAREDPREFERENCES (5 PUNTOS)
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Manager para SharedPreferences
 * Configuraciones rápidas y flags de estado
 */
class SharedPrefsManager(context: Context) {
    private val prefs = context.getSharedPreferences("mymeds_prefs", Context.MODE_PRIVATE)

    // Guardar flag de primera ejecución
    fun setFirstTimeLaunch(isFirst: Boolean) {
        prefs.edit().putBoolean("first_time_launch", isFirst).apply()
        Log.d(TAG, "💾 [SharedPrefs] Primera ejecución: $isFirst")
    }

    fun isFirstTimeLaunch(): Boolean {
        return prefs.getBoolean("first_time_launch", true)
    }

    // Guardar último ID de pedido procesado
    fun setLastOrderId(orderId: Long) {
        prefs.edit().putLong("last_order_id", orderId).apply()
        Log.d(TAG, "💾 [SharedPrefs] Último pedido: $orderId")
    }

    fun getLastOrderId(): Long {
        return prefs.getLong("last_order_id", 0L)
    }

    // Contador de pedidos realizados
    fun incrementOrderCount() {
        val current = prefs.getInt("order_count", 0)
        prefs.edit().putInt("order_count", current + 1).apply()
        Log.d(TAG, "💾 [SharedPrefs] Contador de pedidos: ${current + 1}")
    }

    fun getOrderCount(): Int {
        return prefs.getInt("order_count", 0)
    }

    // Cache de última sincronización (timestamp)
    fun setLastSyncTime(timestamp: Long) {
        prefs.edit().putLong("last_sync_time", timestamp).apply()
    }

    fun getLastSyncTime(): Long {
        return prefs.getLong("last_sync_time", 0L)
    }

    // Modo de vista preferido (lista/grid)
    fun setViewMode(mode: String) {
        prefs.edit().putString("view_mode", mode).apply()
    }

    fun getViewMode(): String {
        return prefs.getString("view_mode", "list") ?: "list"
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// VIEWMODEL - Lógica de negocio
// ═══════════════════════════════════════════════════════════════════════════

class OrdersViewModel(
    private val context: Context
) : ViewModel() {

    private val database = AppDatabase.getDatabase(context)
    private val medicationDao = database.medicationDao()
    private val orderDao = database.orderDao()
    private val orderItemDao = database.orderItemDao()

    private val preferencesManager = UserPreferencesManager(context)
    private val fileManager = LocalFileManager(context)
    private val sharedPrefs = SharedPrefsManager(context)

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    // Estados observables
    val medications = medicationDao.getAllActiveMedications()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val orders = auth.currentUser?.uid?.let { userId ->
        orderDao.getUserOrders(userId)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
    } ?: MutableStateFlow(emptyList())

    val defaultDeliveryType = preferencesManager.defaultDeliveryType
        .stateIn(viewModelScope, SharingStarted.Lazily, DeliveryType.HOME_DELIVERY)

    val defaultAddress = preferencesManager.defaultAddress
        .stateIn(viewModelScope, SharingStarted.Lazily, "")

    var isLoading by mutableStateOf(false)
        private set

    var selectedMedications by mutableStateOf<List<MedicationEntity>>(emptyList())
        private set

    init {
        Log.d(TAG, "🎯 OrdersViewModel inicializado")
        Log.d(TAG, "📊 [SharedPrefs] Total de pedidos históricos: ${sharedPrefs.getOrderCount()}")

        // Sincroniza datos de Firebase a Room
        syncMedicationsFromFirebase()
    }

    /**
     * Sincroniza medicamentos desde Firebase a Room
     * (BD RELACIONAL + Operaciones de red)
     */
    private fun syncMedicationsFromFirebase() {
        val userId = auth.currentUser?.uid ?: return

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "🔄 Sincronizando medicamentos desde Firebase...")

                val snapshot = firestore
                    .collection("usuarios")
                    .document(userId)
                    .collection("medicamentosUsuario")
                    .whereEqualTo("active", true)
                    .get()
                    .await()

                val medicationEntities = snapshot.documents.mapNotNull { doc ->
                    try {
                        MedicationEntity(
                            medicationId = doc.getString("medicationId") ?: return@mapNotNull null,
                            name = doc.getString("name") ?: "Sin nombre",
                            doseMg = doc.getLong("doseMg")?.toInt() ?: 0,
                            frequencyHours = doc.getLong("frequencyHours")?.toInt() ?: 24,
                            stockQuantity = 30, // Default
                            prescriptionId = doc.getString("prescriptionId") ?: "",
                            active = doc.getBoolean("active") ?: true,
                            lastSyncedAt = System.currentTimeMillis(),
                            firebaseDocId = doc.id
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parseando medicamento", e)
                        null
                    }
                }

                // Guarda en Room
                medicationDao.insertAll(medicationEntities)

                // Actualiza timestamp en DataStore
                preferencesManager.saveLastSyncTimestamp(System.currentTimeMillis())

                // Actualiza SharedPrefs
                sharedPrefs.setLastSyncTime(System.currentTimeMillis())

                Log.d(TAG, "✅ Sincronización completada: ${medicationEntities.size} medicamentos")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error en sincronización", e)
            }
        }
    }

    /**
     * Crea un nuevo pedido
     * (Usa TODAS las tecnologías: Room + DataStore + Files + SharedPrefs + Firebase)
     */
    fun createOrder(
        selectedMeds: List<Pair<MedicationEntity, Int>>,
        deliveryType: DeliveryType,
        address: String?,
        notes: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val userId = auth.currentUser?.uid
        if (userId == null) {
            onError("Usuario no autenticado")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                isLoading = true
                Log.d(TAG, "📦 Creando nuevo pedido...")

                // 1. ROOM: Crea el pedido en BD local
                val order = OrderEntity(
                    userId = userId,
                    orderDate = System.currentTimeMillis(),
                    deliveryType = deliveryType,
                    deliveryAddress = address,
                    status = OrderStatus.PENDING,
                    totalItems = selectedMeds.sumOf { it.second },
                    notes = notes,
                    createdAt = System.currentTimeMillis(),
                    syncedToFirebase = false
                )

                val orderId = orderDao.insertOrder(order)
                Log.d(TAG, "💾 [Room] Pedido guardado con ID: $orderId")

                // 2. ROOM: Guarda los ítems del pedido
                val items = selectedMeds.map { (med, qty) ->
                    OrderItemEntity(
                        orderId = orderId,
                        medicationId = med.medicationId,
                        quantity = qty,
                        medicationName = med.name,
                        doseMg = med.doseMg
                    )
                }
                orderItemDao.insertAll(items)
                Log.d(TAG, "💾 [Room] ${items.size} ítems guardados")

                // 3. ARCHIVOS: Guarda recibo en JSON
                val orderWithItems = OrderWithItems(
                    order = order.copy(orderId = orderId),
                    items = items
                )
                val receiptFile = fileManager.saveOrderReceipt(orderWithItems)
                Log.d(TAG, "📄 [Files] Recibo guardado: ${receiptFile?.name}")

                // 4. DATASTORE: Actualiza preferencias si es necesario
                if (deliveryType != defaultDeliveryType.value) {
                    preferencesManager.saveDefaultDeliveryType(deliveryType)
                }
                if (!address.isNullOrBlank() && address != defaultAddress.value) {
                    preferencesManager.saveDefaultAddress(address)
                }

                // 5. SHAREDPREFS: Incrementa contador
                sharedPrefs.incrementOrderCount()
                sharedPrefs.setLastOrderId(orderId)

                // 6. FIREBASE: Sincroniza a la nube
                syncOrderToFirebase(orderWithItems)

                withContext(Dispatchers.Main) {
                    isLoading = false
                    onSuccess()
                }

                Log.d(TAG, "✅ Pedido creado exitosamente")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error creando pedido", e)
                withContext(Dispatchers.Main) {
                    isLoading = false
                    onError(e.message ?: "Error desconocido")
                }
            }
        }
    }

    /**
     * Sincroniza un pedido a Firebase
     */
    private suspend fun syncOrderToFirebase(orderWithItems: OrderWithItems) = withContext(Dispatchers.IO) {
        try {
            val orderData = hashMapOf(
                "userId" to orderWithItems.order.userId,
                "orderDate" to Date(orderWithItems.order.orderDate),
                "deliveryType" to orderWithItems.order.deliveryType.name,
                "deliveryAddress" to orderWithItems.order.deliveryAddress,
                "status" to orderWithItems.order.status.name,
                "totalItems" to orderWithItems.order.totalItems,
                "notes" to orderWithItems.order.notes,
                "items" to orderWithItems.items.map { item ->
                    hashMapOf(
                        "medicationId" to item.medicationId,
                        "medicationName" to item.medicationName,
                        "doseMg" to item.doseMg,
                        "quantity" to item.quantity
                    )
                },
                "createdAt" to Date(orderWithItems.order.createdAt)
            )

            val docRef = firestore
                .collection("usuarios")
                .document(orderWithItems.order.userId)
                .collection("pedidos")
                .add(orderData)
                .await()

            // Actualiza el pedido local con el ID de Firebase
            orderDao.updateOrder(
                orderWithItems.order.copy(
                    syncedToFirebase = true,
                    firebaseOrderId = docRef.id
                )
            )

            Log.d(TAG, "☁️ [Firebase] Pedido sincronizado: ${docRef.id}")

        } catch (e: Exception) {
            Log.e(TAG, "❌ [Firebase] Error sincronizando pedido", e)
        }
    }

    fun toggleMedicationSelection(medication: MedicationEntity) {
        selectedMedications = if (selectedMedications.contains(medication)) {
            selectedMedications - medication
        } else {
            selectedMedications + medication
        }
    }

    fun clearSelection() {
        selectedMedications = emptyList()
    }
}

class OrdersViewModelFactory(private val context: Context) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(OrdersViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return OrdersViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// UI - COMPOSE
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersManagementScreen(vm: OrdersViewModel, finish: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity

    var showNewOrderDialog by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }

    val medications by vm.medications.collectAsState()
    val orders by vm.orders.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Gestión de Pedidos", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = finish) {
                        Icon(Icons.Filled.ArrowBack, "Volver")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        floatingActionButton = {
            if (selectedTab == 0 && medications.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { showNewOrderDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary
                ) {
                    Icon(Icons.Filled.Add, "Nuevo pedido")
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            // TABS
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("Medicamentos") },
                    icon = { Icon(Icons.Filled.MedicalServices, null) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("Mis Pedidos") },
                    icon = { Icon(Icons.Filled.ShoppingCart, null) }
                )
            }

            // CONTENIDO
            when (selectedTab) {
                0 -> MedicationsListTab(medications, vm)
                1 -> OrdersHistoryTab(orders)
            }
        }
    }

    // DIÁLOGO PARA CREAR NUEVO PEDIDO
    if (showNewOrderDialog) {
        NewOrderDialog(
            medications = medications,
            vm = vm,
            onDismiss = { showNewOrderDialog = false },
            onConfirm = { selectedMeds, deliveryType, address, notes ->
                vm.createOrder(
                    selectedMeds = selectedMeds,
                    deliveryType = deliveryType,
                    address = address,
                    notes = notes,
                    onSuccess = {
                        showNewOrderDialog = false
                        Toast.makeText(ctx, "✅ Pedido creado exitosamente", Toast.LENGTH_SHORT).show()
                    },
                    onError = { error ->
                        Toast.makeText(ctx, "❌ Error: $error", Toast.LENGTH_LONG).show()
                    }
                )
            }
        )
    }
}

@Composable
fun MedicationsListTab(
    medications: List<MedicationEntity>,
    vm: OrdersViewModel
) {
    if (medications.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.MedicalServices,
                    null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No hay medicamentos registrados",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(medications) { med ->
                MedicationCard(
                    medication = med,
                    isSelected = vm.selectedMedications.contains(med),
                    onToggleSelection = { vm.toggleMedicationSelection(med) }
                )
            }
        }
    }
}

@Composable
fun MedicationCard(
    medication: MedicationEntity,
    isSelected: Boolean,
    onToggleSelection: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleSelection),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelection() }
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    medication.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Dosis: ${medication.doseMg}mg • Cada ${medication.frequencyHours}h",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Text(
                    "Stock: ${medication.stockQuantity} unidades",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (medication.stockQuantity < 10)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            }

            Icon(
                Icons.Filled.MedicalServices,
                null,
                tint = if (isSelected)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
fun OrdersHistoryTab(orders: List<OrderEntity>) {
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
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "No hay pedidos registrados",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
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
                OrderCard(order)
            }
        }
    }
}

@Composable
fun OrderCard(order: OrderEntity) {
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
                    "Pedido #${order.orderId}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                StatusBadge(order.status)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            InfoRow("📅 Fecha:", SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(order.orderDate)))
            InfoRow("📦 Total ítems:", "${order.totalItems}")
            InfoRow("🚚 Entrega:", when (order.deliveryType) {
                DeliveryType.HOME_DELIVERY -> "A domicilio"
                DeliveryType.IN_PERSON_PICKUP -> "Recoger en farmacia"
            })

            if (!order.deliveryAddress.isNullOrBlank()) {
                InfoRow("📍 Dirección:", order.deliveryAddress)
            }

            if (!order.notes.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Notas: ${order.notes}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun StatusBadge(status: OrderStatus) {
    val (text, color) = when (status) {
        OrderStatus.PENDING -> "Pendiente" to MaterialTheme.colorScheme.tertiary
        OrderStatus.CONFIRMED -> "Confirmado" to MaterialTheme.colorScheme.primary
        OrderStatus.IN_TRANSIT -> "En camino" to MaterialTheme.colorScheme.secondary
        OrderStatus.DELIVERED -> "Entregado" to Color(0xFF4CAF50)
        OrderStatus.CANCELLED -> "Cancelado" to MaterialTheme.colorScheme.error
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

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.width(120.dp)
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewOrderDialog(
    medications: List<MedicationEntity>,
    vm: OrdersViewModel,
    onDismiss: () -> Unit,
    onConfirm: (List<Pair<MedicationEntity, Int>>, DeliveryType, String?, String?) -> Unit
) {
    val defaultDeliveryType by vm.defaultDeliveryType.collectAsState()
    val defaultAddress by vm.defaultAddress.collectAsState()

    var selectedDeliveryType by remember { mutableStateOf(defaultDeliveryType) }
    var address by remember { mutableStateOf(defaultAddress) }
    var notes by remember { mutableStateOf("") }
    val quantities = remember { mutableStateMapOf<String, Int>() }

    // Inicializa cantidades para medicamentos seleccionados
    LaunchedEffect(vm.selectedMedications) {
        vm.selectedMedications.forEach { med ->
            if (!quantities.containsKey(med.medicationId)) {
                quantities[med.medicationId] = 1
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo Pedido") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                if (vm.selectedMedications.isEmpty()) {
                    Text(
                        "Por favor selecciona al menos un medicamento",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        "Medicamentos seleccionados:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    vm.selectedMedications.forEach { med ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    med.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "${med.doseMg}mg",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(
                                    onClick = {
                                        val current = quantities[med.medicationId] ?: 1
                                        if (current > 1) {
                                            quantities[med.medicationId] = current - 1
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.Remove, null)
                                }

                                Text(
                                    "${quantities[med.medicationId] ?: 1}",
                                    modifier = Modifier.width(32.dp),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )

                                IconButton(
                                    onClick = {
                                        val current = quantities[med.medicationId] ?: 1
                                        if (current < med.stockQuantity) {
                                            quantities[med.medicationId] = current + 1
                                        }
                                    }
                                ) {
                                    Icon(Icons.Filled.Add, null)
                                }
                            }
                        }
                        HorizontalDivider()
                    }

                    Spacer(Modifier.height(16.dp))

                    // TIPO DE ENTREGA
                    Text(
                        "Tipo de entrega:",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedDeliveryType = DeliveryType.HOME_DELIVERY },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedDeliveryType == DeliveryType.HOME_DELIVERY,
                                onClick = { selectedDeliveryType = DeliveryType.HOME_DELIVERY }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("🚚 Entrega a domicilio", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Recibe tus medicamentos en casa",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedDeliveryType = DeliveryType.IN_PERSON_PICKUP },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedDeliveryType == DeliveryType.IN_PERSON_PICKUP,
                                onClick = { selectedDeliveryType = DeliveryType.IN_PERSON_PICKUP }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("🏪 Recoger en farmacia", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Recoge personalmente tu pedido",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }

                    // DIRECCIÓN (solo si es entrega a domicilio)
                    if (selectedDeliveryType == DeliveryType.HOME_DELIVERY) {
                        Spacer(Modifier.height(16.dp))

                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Dirección de entrega") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Filled.Home, null) }
                        )
                    }

                    // NOTAS
                    Spacer(Modifier.height(16.dp))

                    OutlinedTextField(
                        value = notes,
                        onValueChange = { notes = it },
                        label = { Text("Notas (opcional)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (vm.selectedMedications.isNotEmpty()) {
                        val selectedMeds = vm.selectedMedications.map { med ->
                            med to (quantities[med.medicationId] ?: 1)
                        }

                        val finalAddress = if (selectedDeliveryType == DeliveryType.HOME_DELIVERY && address.isNotBlank()) {
                            address
                        } else null

                        onConfirm(
                            selectedMeds,
                            selectedDeliveryType,
                            finalAddress,
                            notes.ifBlank { null }
                        )
                    }
                },
                enabled = vm.selectedMedications.isNotEmpty() &&
                        (selectedDeliveryType == DeliveryType.IN_PERSON_PICKUP ||
                                (selectedDeliveryType == DeliveryType.HOME_DELIVERY && address.isNotBlank()))
            ) {
                Text("Confirmar Pedido")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancelar")
            }
        }
    )
}