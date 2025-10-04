package com.example.mymeds.views


import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mymeds.ui.theme.MyMedsTheme
import androidx.compose.ui.tooling.preview.Preview

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyMedsTheme {
                HomeScreen(
                    onMapClick = {
                        navigateToMap()
                    },
                    onUploadPrescriptionClick = {
                        navigateToUploadPrescription()
                    },
                    onProfileClick = {
                        navigateToProfile()
                    },
                    onNotificationsClick = {
                        showNotifications()
                    }
                )
            }
        }
    }

    private fun navigateToMap() {
        // Implementar navegación al mapa
        // val intent = Intent(this, MapActivity::class.java)
        // startActivity(intent)
    }

    private fun navigateToUploadPrescription() {
        // Implementar navegación a subir prescripción
        // val intent = Intent(this, UploadPrescriptionActivity::class.java)
        // startActivity(intent)
    }

    private fun navigateToProfile() {
        // Implementar navegación al perfil
        // val intent = Intent(this, ProfileActivity::class.java)
        // startActivity(intent)
    }

    private fun showNotifications() {
        // Implementar lógica de notificaciones
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onMapClick: () -> Unit = {},
    onUploadPrescriptionClick: () -> Unit = {},
    onProfileClick: () -> Unit = {},
    onNotificationsClick: () -> Unit = {}
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "HOME",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { /* Menú */ }) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menú",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onNotificationsClick) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notificaciones",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF6B9BD8)
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(Color(0xFFF5F5F5))
                .verticalScroll(rememberScrollState())
                .padding(top = 16.dp)
        ) {
            // Tarjetas de funcionalidad
            FunctionalityCard(
                title = "Ver mapa de farmacias",
                description = "Encuentra sucursales EPS cercanas, horarios y stock estimado.",
                icon = Icons.Default.Place,
                buttonText = "Abrir mapa",
                onClick = onMapClick
            )

            Spacer(modifier = Modifier.height(16.dp))

            FunctionalityCard(
                title = "Sube tu prescripción",
                description = "Escanea o carga la fórmula para validar y agilizar tu pedido.",
                icon = Icons.Default.Add,
                buttonText = "Subir",
                onClick = onUploadPrescriptionClick
            )

            Spacer(modifier = Modifier.height(16.dp))

            FunctionalityCard(
                title = "Ver tu perfil",
                description = "Datos del usuario, preferencias y accesibilidad.",
                icon = Icons.Default.Person,
                buttonText = "Ver perfil",
                onClick = onProfileClick,
                isAccount = true
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun FunctionalityCard(
    title: String,
    description: String,
    icon: ImageVector,
    buttonText: String,
    onClick: () -> Unit,
    isAccount: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAccount) Color(0xFFB3CEE8) else Color(0xFFCBDEF3)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (isAccount) "CUENTA" else "FUNCIONALIDAD",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF5A7A9B),
                        letterSpacing = 0.5.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black,
                        lineHeight = 22.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = description,
                        fontSize = 14.sp,
                        color = Color(0xFF444444),
                        lineHeight = 20.sp
                    )
                }

                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier
                        .size(40.dp)
                        .padding(start = 8.dp),
                    tint = Color.White
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onClick,
                modifier = Modifier.height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
            ) {
                Text(
                    text = buttonText,
                    color = Color.Black,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}


@Preview(showBackground = true, showSystemUi = true)
@Composable
fun HomeScreenPreview() {
    MyMedsTheme {
        HomeScreen(
            onMapClick = {},
            onUploadPrescriptionClick = {},
            onProfileClick = {},
            onNotificationsClick = {}
        )
    }
}

