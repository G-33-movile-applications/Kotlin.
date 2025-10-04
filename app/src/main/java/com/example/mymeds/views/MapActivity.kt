package com.example.mymeds.views

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mymeds.models.PhysicalPoint
import com.example.mymeds.viewModels.MapViewModel
import com.example.mymeds.viewModels.LoadingState
import com.example.mymeds.ui.theme.MyMedsTheme
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

class MapActivity : ComponentActivity() {

    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyMedsTheme {
                MapScreen(
                    viewModel = mapViewModel,
                    onBackClick = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(viewModel: MapViewModel, onBackClick: () -> Unit) {
    val points by viewModel.physicalPoints.observeAsState(initial = emptyList())
    val loadingState by viewModel.loadingState.observeAsState(initial = LoadingState.LOADING)

    val uniandes = LatLng(4.601485, -74.066445)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(uniandes, 15f)
    }

    var selectedPoint by remember { mutableStateOf<PhysicalPoint?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "MAP",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF6B9BD8))
            )
        }
    ) { paddingValues ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {

            when (loadingState) {
                LoadingState.LOADING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                LoadingState.ERROR -> {
                    Text(
                        text = "Error loading points",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Red
                    )
                }
                LoadingState.SUCCESS -> {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState
                    ) {
                        points.forEach { point ->
                            Marker(
                                state = MarkerState(position = LatLng(point.latitude, point.longitude)),
                                title = point.name,
                                snippet = point.address,
                                onClick = {
                                    selectedPoint = point
                                    true
                                }
                            )
                        }
                    }

                    // Bottom panel with selected point info
                    selectedPoint?.let { point ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .padding(16.dp),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(containerColor = Color.White),
                            elevation = CardDefaults.cardElevation(8.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(point.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                Text(point.address, fontSize = 14.sp, color = Color.Gray)
                                Text("Chain: ${point.chain}", fontSize = 14.sp, color = Color.DarkGray)

                                Text("Schedule: ${point.openingHours.joinToString()}")
                                Text("Days: ${point.openingDays.joinToString()}")

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(
                                        onClick = { /* TODO: Delivery logic */ },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6CBAF))
                                    ) {
                                        Text("Hacer Pedido", color = Color.Black)
                                    }
                                    Button(
                                        onClick = { /* TODO: Inventory logic */ },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD6CBAF))
                                    ) {
                                        Text("Ver inventario", color = Color.Black)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}