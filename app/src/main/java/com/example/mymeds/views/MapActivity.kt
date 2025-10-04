// Archivo: com/example/mymeds/views/MapActivity.kt
package com.example.mymeds.views

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.mymeds.models.PhysicalPoint
import com.example.mymeds.ui.theme.MyMedsTheme // Asegúrate de que este sea el nombre correcto de tu tema
import com.example.mymeds.viewModels.LoadingState
import com.example.mymeds.viewModels.MapViewModel
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState

// --- PUNTO DE ENTRADA: LA ACTIVITY ---

class MapActivity : ComponentActivity() {

    // Obtiene una instancia del ViewModel, vinculada al ciclo de vida de esta Activity
    private val mapViewModel: MapViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // setContent es el puente entre el mundo de las Views y el de Compose
        setContent {
            // Configura el tema de tu aplicación
            MyMedsTheme {
                // Llama al Composable principal que define la pantalla
                MapScreen(viewModel = mapViewModel)
            }
        }
    }
}

// --- PANTALLA COMPLETA: EL COMPOSABLE PRINCIPAL ---

@Composable
fun MapScreen(viewModel: MapViewModel) {
    // Observa el LiveData del ViewModel y lo convierte en un State de Compose.
    // La pantalla se recompondrá automáticamente cuando estos valores cambien.
    val physicalPoints by viewModel.physicalPoints.observeAsState(initial = emptyList())
    val loadingState by viewModel.loadingState.observeAsState()
    val context = LocalContext.current

    Scaffold { paddingValues ->
        // Box permite apilar elementos. Usaremos esto para poner el indicador de carga sobre el mapa.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // El Composable del mapa
            MapView(
                points = physicalPoints,
                onMarkerClick = { point ->
                    // Define la acción al hacer clic en un marcador
                    val info = "${point.name}\nDirección: ${point.address}"
                    Toast.makeText(context, info, Toast.LENGTH_LONG).show()
                }
            )

            // Gestiona la visibilidad del indicador de carga y los mensajes de error
            when (loadingState) {
                LoadingState.LOADING -> {
                    // Muestra un indicador de progreso en el centro de la pantalla
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                LoadingState.ERROR -> {
                    // Muestra un mensaje de error (esto podría ser un Snackbar para una mejor UX)
                    Toast.makeText(context, "Error al cargar los puntos", Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // No hace nada en los estados SUCCESS o nulo
                }
            }
        }
    }
}

// --- COMPONENTE REUTILIZABLE: EL MAPA ---

@Composable
fun MapView(
    points: List<PhysicalPoint>,
    onMarkerClick: (PhysicalPoint) -> Unit
) {
    // Configura la posición inicial de la cámara (ej: Bogotá, Colombia)
    val initialLocation = LatLng(4.60971, -74.08175)
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(initialLocation, 10f)
    }

    // El Composable de GoogleMap que ocupa todo el espacio disponible
    GoogleMap(
        modifier = Modifier.fillMaxSize(),
        cameraPositionState = cameraPositionState
    ) {
        // Itera sobre la lista de puntos para crear un marcador para cada uno
        points.forEach { point ->
            // Asegura que la ubicación no sea nula antes de intentar crear el marcador
            point.location?.let { geoPoint ->
                val position = LatLng(geoPoint.latitude, geoPoint.longitude)
                Marker(
                    state = MarkerState(position = position),
                    title = point.name,
                    snippet = point.address,
                    onInfoWindowClick = {
                        // El clic en la ventana de información del marcador activa la acción
                        onMarkerClick(point)
                    }
                )
            }
        }
    }
}
