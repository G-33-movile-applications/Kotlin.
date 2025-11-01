package com.example.mymeds.views

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mymeds.models.AnalyticsUiState
import com.example.mymeds.models.DeliveryMode
import com.example.mymeds.models.UserAnalytics
import com.example.mymeds.viewModels.UserAnalyticsViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

/**
 * ANALYTICS ACTIVITY – filtros compactos + donut filtrado + KPIs de estados y farmacia
 */
class AnalyticsActivity : ComponentActivity() {
    private val viewModel: UserAnalyticsViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AnalyticsTheme { AnalyticsScreen(viewModel) { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AnalyticsScreen(
    viewModel: UserAnalyticsViewModel,
    onBack: () -> Unit
) {
    val uiState by viewModel.uiState
    val isRefreshing by viewModel.isRefreshing

    // ---------- Filtros compactos ----------
    val periodOptions = listOf("Últimos 7 días", "Últimos 30 días", "Últimos 90 días", "Todo")
    var selectedPeriod by remember { mutableStateOf(periodOptions[1]) } // 30 días

    // Multi-selección de modos (chips)
    var showDelivery by remember { mutableStateOf(true) }
    var showPickup by remember { mutableStateOf(true) }

    // Menú período
    var menuPeriodExpanded by remember { mutableStateOf(false) }

    // Tabs
    var analyticsTab by remember { mutableStateOf(0) } // 0: Entregas/Recogidas, 1: BQT2, 2: BQT4

    // Primer load
    LaunchedEffect(Unit) { viewModel.loadAnalytics() }

    // Conectar filtros -> VM
    LaunchedEffect(selectedPeriod, showDelivery, showPickup, analyticsTab) {
        val days = when (selectedPeriod) {
            "Últimos 7 días" -> 7
            "Últimos 30 días" -> 30
            "Últimos 90 días" -> 90
            else -> null
        }
        val mode = when {
            showDelivery && showPickup -> null
            showDelivery && !showPickup -> DeliveryMode.DELIVERY
            !showDelivery && showPickup -> DeliveryMode.PICKUP
            else -> null
        }
        viewModel.loadAnalytics(days, mode)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("📊 Analíticas", fontWeight = FontWeight.Bold, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        ActiveFiltersPill(selectedPeriod, buildString {
                            append(
                                when {
                                    showDelivery && showPickup -> "Todos"
                                    showDelivery -> "Domicilio"
                                    showPickup -> "Recoger"
                                    else -> "Todos"
                                }
                            )
                        })
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }, enabled = !isRefreshing) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refrescar", tint = Color.White)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF6B9BD8))
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFF6F8FB))
        ) {

            // ----------- Barra de filtros compacta -----------
            CompactFiltersBar(
                analyticsTab = analyticsTab,
                onTabChange = { analyticsTab = it },
                periodOptions = periodOptions,
                selectedPeriod = selectedPeriod,
                onOpenPeriod = { menuPeriodExpanded = true },
                onSelectPeriod = { selectedPeriod = it; menuPeriodExpanded = false },
                periodExpanded = menuPeriodExpanded,
                onDismissPeriod = { menuPeriodExpanded = false },
                showDelivery = showDelivery,
                onToggleDelivery = { showDelivery = !showDelivery },
                showPickup = showPickup,
                onTogglePickup = { showPickup = !showPickup }
            )

            // ---------------- Contenido ----------------
            Box(Modifier.fillMaxSize().weight(1f)) {
                when (val state = uiState) {
                    is AnalyticsUiState.Loading -> LoadingView()
                    is AnalyticsUiState.Error -> ErrorView(state.message) { viewModel.loadAnalytics() }
                    is AnalyticsUiState.Success -> {
                        when (analyticsTab) {
                            0 -> DeliveryPickupTab(state.analytics, showDelivery, showPickup)
                            1 -> BQT2Tab(state.analytics)
                            2 -> RefillsByDayTab(state.analytics)
                        }
                    }
                }
            }
        }
    }
}

/* -------------------------- CONTROLES COMPACTOS -------------------------- */

@Composable
private fun CompactFiltersBar(
    analyticsTab: Int,
    onTabChange: (Int) -> Unit,
    periodOptions: List<String>,
    selectedPeriod: String,
    onOpenPeriod: () -> Unit,
    onSelectPeriod: (String) -> Unit,
    periodExpanded: Boolean,
    onDismissPeriod: () -> Unit,
    showDelivery: Boolean,
    onToggleDelivery: () -> Unit,
    showPickup: Boolean,
    onTogglePickup: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Tabs delgadas
        TabRow(
            selectedTabIndex = analyticsTab,
            containerColor = Color(0xFFEAF2FE),
            indicator = {},
            divider = {}
        ) {
            Tab(
                selected = analyticsTab == 0,
                onClick = { onTabChange(0) },
                text = { Text("Entregas/Recogidas") },
                icon = { Icon(Icons.Filled.LocalShipping, null) }
            )
            Tab(
                selected = analyticsTab == 1,
                onClick = { onTabChange(1) },
                text = { Text("BQT2") },
                icon = { Icon(Icons.Filled.Insights, null) }
            )
            Tab(
                selected = analyticsTab == 2,
                onClick = { onTabChange(2) },
                text = { Text("Refills") },
                icon = { Icon(Icons.Filled.Insights, null) }
            )
        }

        Spacer(Modifier.height(8.dp))

        // Fila compacta de chips
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Chip de período (abre menú)
            AssistChip(
                onClick = onOpenPeriod,
                label = { Text(selectedPeriod, fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Filled.DateRange, null, modifier = Modifier.size(16.dp)) }
            )
            DropdownMenu(expanded = periodExpanded, onDismissRequest = onDismissPeriod) {
                periodOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        onClick = { onSelectPeriod(option) }
                    )
                }
            }

            Spacer(Modifier.width(8.dp))

            // Chips de modo (multi-selección)
            FilterChip(
                selected = showDelivery,
                onClick = onToggleDelivery,
                label = { Text("Domicilio", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.LocalShipping, null, modifier = Modifier.size(16.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF3498DB).copy(alpha = 0.12f),
                    selectedLabelColor = Color(0xFF1F6FA0),
                    selectedLeadingIconColor = Color(0xFF1F6FA0)
                )
            )

            Spacer(Modifier.width(8.dp))

            FilterChip(
                selected = showPickup,
                onClick = onTogglePickup,
                label = { Text("Recoger", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(Icons.Filled.Store, null, modifier = Modifier.size(16.dp))
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Color(0xFF2ECC71).copy(alpha = 0.12f),
                    selectedLabelColor = Color(0xFF1E7B44),
                    selectedLeadingIconColor = Color(0xFF1E7B44)
                )
            )
        }
    }
}

@Composable
private fun ActiveFiltersPill(period: String, mode: String) {
    Surface(
        color = Color.White.copy(alpha = 0.18f),
        shape = RoundedCornerShape(999.dp)
    ) {
        Text(
            "· $period · $mode",
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            color = Color.White,
            fontSize = 12.sp
        )
    }
}

/* --------------------------- TAB 0: DELIVERY/PICKUP --------------------------- */

@Composable
private fun DeliveryPickupTab(
    analytics: UserAnalytics,
    showDelivery: Boolean,
    showPickup: Boolean
) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // KPI rápidos
        OverviewKPI(analytics)

        // Donut + leyenda (solo seleccionados)
        DonutDeliveryPickupCard(
            analytics = analytics,
            showDelivery = showDelivery,
            showPickup = showPickup
        )

        // NUEVO: KPIs de ESTADOS (mismo formato compacto)
        StatusKpiRow(analytics)

        // NUEVO: KPI de FARMACIA preferida (mismo formato compacto)
        PharmacyKpiRow(analytics)

        // (Opcional) Si aún quieres conservar esta card informativa adicional:
        // PharmacyPreferenceCard(analytics.mostFrequentPharmacy)

        Spacer(Modifier.height(8.dp))
    }
}

/* ---------------------- KPI filas nuevas solicitadas ---------------------- */

@Composable
private fun StatusKpiRow(analytics: UserAnalytics) {
    Text(
        "Estados de pedidos",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF2C3E50)
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Timelapse,
            title = "Activos",
            value = analytics.activeOrders.toString(),
            color = Color(0xFF3498DB)
        )
        KpiCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.CheckCircle,
            title = "Completados",
            value = analytics.completedOrders.toString(),
            color = Color(0xFF2ECC71)
        )
        KpiCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Cancel,
            title = "Cancelados",
            value = analytics.cancelledOrders.toString(),
            color = Color(0xFFE74C3C)
        )
    }
}

@Composable
private fun PharmacyKpiRow(analytics: UserAnalytics) {
    Text(
        "Farmacias",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = Color(0xFF2C3E50)
    )
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.LocalPharmacy,
            title = "Preferida",
            value = analytics.mostFrequentPharmacy.ifBlank { "—" },
            color = Color(0xFF9B59B6)
        )
        // Si luego agregas "uniquePharmacies" al modelo, puedes mostrarlo aquí.
        // KpiCard(Modifier.weight(1f), Icons.Filled.Store, "Únicas", analytics.uniquePharmacies.toString(), Color(0xFF8E44AD))
    }
}

/* -------------------------- KPI base y auxiliares ------------------------- */

@Composable
private fun OverviewKPI(analytics: UserAnalytics) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        KpiCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.ShoppingCart,
            title = "Total Pedidos",
            value = analytics.totalOrders.toString(),
            color = Color(0xFF6C8CF2)
        )
        KpiCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.LocalShipping,
            title = "Domicilios",
            value = "${analytics.deliveryOrders} (${analytics.deliveryPercentage.toInt()}%)",
            color = Color(0xFF47C1BF)
        )
        KpiCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Store,
            title = "Recogidas",
            value = "${analytics.pickupOrders} (${analytics.pickupPercentage.toInt()}%)",
            color = Color(0xFF4AC06B)
        )
    }
}

@Composable
private fun KpiCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(6.dp))
            Text(title, color = color.darken(0.2f), fontSize = 12.sp)
            Text(value, color = color.darken(0.35f), fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

/* ----------------- Donut (solo categorías seleccionadas) ------------------ */

@Composable
private fun DonutDeliveryPickupCard(
    analytics: UserAnalytics,
    showDelivery: Boolean,
    showPickup: Boolean
) {
    // Solo lo seleccionado + normalizar
    val rawDelivery = if (showDelivery) analytics.deliveryPercentage.coerceIn(0f, 100f) else 0f
    val rawPickup   = if (showPickup)   analytics.pickupPercentage.coerceIn(0f, 100f)   else 0f
    val sum = (rawDelivery + rawPickup).takeIf { it > 0f } ?: 1f
    val deliveryPct = (rawDelivery / sum) * 100f
    val pickupPct   = (rawPickup   / sum) * 100f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DonutChart(
                deliveryFraction = deliveryPct / 100f,
                pickupFraction   = pickupPct   / 100f,
                deliveryColor = Color(0xFF3498DB),
                pickupColor   = Color(0xFF2ECC71)
            )
            Column {
                Text("Distribución", fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
                Spacer(Modifier.height(6.dp))
                if (showDelivery) LegendItem("Domicilio", deliveryPct.toInt(), Color(0xFF3498DB))
                if (showPickup)   LegendItem("Recoger",  pickupPct.toInt(),   Color(0xFF2ECC71))
            }
        }
    }
}

@Composable
private fun DonutChart(
    deliveryFraction: Float, // 0..1
    pickupFraction: Float,   // 0..1
    deliveryColor: Color,
    pickupColor: Color,
    strokeWidth: Float = 20f
) {
    val clampedDelivery = deliveryFraction.coerceIn(0f, 1f)
    val clampedPickup   = pickupFraction.coerceIn(0f, 1f)

    var played by remember { mutableStateOf(false) }
    val deliverySweep by animateFloatAsState(
        targetValue = if (played) 360f * clampedDelivery else 0f,
        animationSpec = tween(800),
        label = "deliverySweep"
    )
    val pickupSweep by animateFloatAsState(
        targetValue = if (played) 360f * clampedPickup else 0f,
        animationSpec = tween(800),
        label = "pickupSweep"
    )
    LaunchedEffect(Unit) { played = true }

    Canvas(modifier = Modifier.size(100.dp)) {
        val start = -90f
        drawArc(
            color = deliveryColor,
            startAngle = start,
            sweepAngle = deliverySweep,
            useCenter = false,
            style = Stroke(width = strokeWidth)
        )
        drawArc(
            color = pickupColor,
            startAngle = start + deliverySweep,
            sweepAngle = pickupSweep,
            useCenter = false,
            style = Stroke(width = strokeWidth)
        )
    }
}

@Composable
private fun LegendItem(label: String, pct: Int, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text("$label: $pct%", color = Color(0xFF5D6B82), fontSize = 12.sp)
    }
}

/* ------------------------------- TAB 1: BQT2 ------------------------------- */

@Composable
private fun BQT2Tab(analytics: UserAnalytics) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Cabecera con gradiente
        TotalOrdersHeader(analytics.totalOrders)
        // Métricas BQT2
        MedicationRequestsSection(analytics)
        LastClaimSection(analytics)
        // Financieras
        FinancialStatsSection(analytics)
        Spacer(Modifier.height(8.dp))
    }
}

/* ------------------------------- TAB 2: BQT4 ------------------------------- */

@Composable
private fun RefillsByDayTab(analytics: UserAnalytics) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Distribución de Pedidos por Día del Mes",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color(0xFF2C3E50)
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "¿Qué días del mes se solicitan más refills?",
            style = MaterialTheme.typography.bodySmall,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))

        if (analytics.refillsByDayOfMonth.isEmpty()) {
            Text(
                "No se encontraron datos de pedidos para el período seleccionado.",
                modifier = Modifier.padding(16.dp),
                textAlign = TextAlign.Center
            )
        } else {
            RefillBarChart(data = analytics.refillsByDayOfMonth)
        }
    }
}

// Barchart para los refills
@Composable
private fun RefillBarChart(data: List<Pair<Int, Int>>) {
    val maxRefills = data.maxOfOrNull { it.second } ?: 1

    var animationTrigger by remember { mutableStateOf(false) }
    LaunchedEffect(key1 = data) {
        animationTrigger = true
    }

    val animatedProgress = animateFloatAsState(
        targetValue = if (animationTrigger) 1f else 0f,
        animationSpec = tween(800),
        label = "barAnimation"
    ).value

    Column(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                drawLine(
                    color = Color.LightGray,
                    start = Offset(40f, 0f),
                    end = Offset(40f, size.height),
                    strokeWidth = 2f
                )
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Y-axis labels
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End
            ) {
                Text(maxRefills.toString(), fontSize = 10.sp, color = Color.Gray)
                Text((maxRefills / 2).toString(), fontSize = 10.sp, color = Color.Gray)
                Text("0", fontSize = 10.sp, color = Color.Gray)
            }

            // Horizontal scroll for the bars
            Row(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                // Spacer to align with axis line
                Spacer(modifier = Modifier.width(4.dp))

                data.forEach { (day, count) ->
                    Column(
                        modifier = Modifier.fillMaxHeight(), // Columna principal que ocupa toda la altura
                        horizontalAlignment = Alignment.CenterHorizontally                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f) // Ocupa el espacio restante
                                .width(28.dp),
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .fillMaxHeight(fraction = (count.toFloat() / maxRefills) * animatedProgress)
                                    .background(MaterialTheme.colorScheme.primary)
                            )
                        }
                        Text(
                            text = day.toString(),
                            fontSize = 10.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }

            }
        }
        // X-axis label
        Text(
            "Día del Mes",
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelMedium,
            color = Color.Gray
        )
    }
}

/* -------------------------- COMPONENTES REUSADOS -------------------------- */

@Composable
private fun TotalOrdersHeader(totalOrders: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF6B9BD8)),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(listOf(Color(0xFF6B9BD8), Color(0xFF4A7BA7)))
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.ShoppingCart, null, modifier = Modifier.size(48.dp), tint = Color.White)
                Spacer(Modifier.height(8.dp))
                Text("Total de Pedidos", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
                Spacer(Modifier.height(4.dp))
                val animated by animateFloatAsState(
                    targetValue = totalOrders.toFloat(),
                    animationSpec = tween(600),
                    label = "ordersAnim"
                )
                Text(
                    animated.toInt().toString(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun MedicationRequestsSection(analytics: UserAnalytics) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("💊 Solicitudes de Medicamentos", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.MedicalServices,
                title = "Total Solicitados",
                value = analytics.totalMedicationRequests.toString(),
                subtitle = "BQT2-1: Total de medicamentos",
                color = Color(0xFFE74C3C)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.Calculate,
                title = "Promedio",
                value = String.format("%.1f", analytics.averageMedicationsPerOrder),
                subtitle = "Por pedido",
                color = Color(0xFF9B59B6)
            )
        }
    }
}

@Composable
private fun LastClaimSection(analytics: UserAnalytics) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("📦 Último Reclamo", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (analytics.hasEverClaimed) Color(0xFFE8F5E9) else Color(0xFFFFF3E0)
            )
        ) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape).background(
                        if (analytics.hasEverClaimed) Color(0xFF4CAF50).copy(alpha = 0.2f) else Color(0xFFFF9800).copy(alpha = 0.2f)
                    ),
                    contentAlignment = Alignment.Center
                ) { Text(if (analytics.hasEverClaimed) "📦" else "⏳", fontSize = 32.sp) }
                Spacer(Modifier.width(16.dp))
                Column {
                    if (analytics.hasEverClaimed) {
                        Text("Hace ${analytics.daysSinceLastClaim} día(s)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                        analytics.lastClaimDate?.let { date ->
                            val sdf = SimpleDateFormat("dd 'de' MMMM, yyyy", Locale("es"))
                            Text(sdf.format(date), style = MaterialTheme.typography.bodyMedium, color = Color(0xFF558B2F))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text("BQT2-2: Última reclamación", style = MaterialTheme.typography.labelSmall, color = Color(0xFF558B2F).copy(alpha = 0.7f))
                    } else {
                        Text("Nunca has reclamado", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                        Text("Aún no hay reclamos registrados", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFF57C00))
                    }
                }
            }
        }
    }
}

@Composable
private fun FinancialStatsSection(analytics: UserAnalytics) {
    if (analytics.totalSpent <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("💰 Estadísticas Financieras", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color(0xFF2C3E50))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val currencyFormat = NumberFormat.getCurrencyInstance(Locale("es", "CO"))
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.AttachMoney,
                title = "Total Gastado",
                value = currencyFormat.format(analytics.totalSpent),
                subtitle = "En todos los pedidos",
                color = Color(0xFF16A085)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Filled.TrendingUp,
                title = "Promedio",
                value = currencyFormat.format(analytics.averageOrderValue),
                subtitle = "Por pedido",
                color = Color(0xFF27AE60)
            )
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    value: String,
    subtitle: String,
    color: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Icon(icon, null, tint = color, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Spacer(Modifier.height(4.dp))
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
private fun PharmacyPreferenceCard(pharmacyName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3E5F5))
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🏪", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.width(12.dp))
            Column {
                Text("Farmacia Preferida", style = MaterialTheme.typography.labelMedium, color = Color(0xFF6A1B9A))
                Text(pharmacyName.ifBlank { "—" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF6A1B9A))
            }
        }
    }
}

@Composable
private fun LoadingView() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color(0xFF6B9BD8))
            Spacer(Modifier.height(16.dp))
            Text("Calculando analíticas...", style = MaterialTheme.typography.bodyLarge, color = Color.Gray)
        }
    }
}

@Composable
private fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Filled.Error, null, modifier = Modifier.size(64.dp), tint = Color(0xFFE74C3C))
            Spacer(Modifier.height(16.dp))
            Text("Error", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color(0xFFE74C3C))
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = Color.Gray, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6B9BD8))) {
                Icon(Icons.Filled.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Reintentar")
            }
        }
    }
}

@Composable
private fun AnalyticsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6B9BD8),
            secondary = Color(0xFF3498DB),
            background = Color(0xFFF6F8FB),
            surface = Color.White
        ),
        content = content
    )
}

/* ---------------------------- helpers de color ---------------------------- */
private fun Color.darken(factor: Float): Color {
    val f = (1f - factor).coerceIn(0f, 1f)
    return Color(red * f, green * f, blue * f, alpha)
}
