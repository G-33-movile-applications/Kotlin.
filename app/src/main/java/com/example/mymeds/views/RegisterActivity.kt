package com.example.mymeds.views

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter

class RegisterActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RegisterScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen() {
    // Campos
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var documentNumber by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var zipCode by remember { mutableStateOf("") }

    // Dropdown tipo documento
    var docTypeExpanded by remember { mutableStateOf(false) }
    var selectedDocType by remember { mutableStateOf("ID Card") }
    val docTypes = listOf("ID Card", "Passport", "Driver License")

    // Foto de perfil opcional
    var profilePicUri by remember { mutableStateOf<Uri?>(null) }

    // Checkboxes
    var acceptTerms by remember { mutableStateOf(false) }
    var acceptDataPolicy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()), // 👈 Hace scrollable todo el formulario
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Título
        Text("Register", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        // Foto de perfil opcional
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .border(BorderStroke(2.dp, Color.Gray), CircleShape)
                .clickable { /* TODO: abrir selector de imagen */ },
            contentAlignment = Alignment.Center
        ) {
            if (profilePicUri != null) {
                Image(
                    painter = rememberAsyncImagePainter(profilePicUri),
                    contentDescription = "Profile Picture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Text(
            "Tap to upload profile picture (optional)",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Campos de texto ---
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Full Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text("Phone Number") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Dropdown tipo documento
        ExposedDropdownMenuBox(
            expanded = docTypeExpanded,
            onExpandedChange = { docTypeExpanded = !docTypeExpanded }
        ) {
            OutlinedTextField(
                value = selectedDocType,
                onValueChange = {},
                label = { Text("Document Type") },
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = docTypeExpanded,
                onDismissRequest = { docTypeExpanded = false }
            ) {
                docTypes.forEach { doc ->
                    DropdownMenuItem(
                        text = { Text(doc) },
                        onClick = {
                            selectedDocType = doc
                            docTypeExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = documentNumber,
            onValueChange = { documentNumber = it },
            label = { Text("Document Number") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Address") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = city,
            onValueChange = { city = it },
            label = { Text("City") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = state,
            onValueChange = { state = it },
            label = { Text("State") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = zipCode,
            onValueChange = { zipCode = it },
            label = { Text("Zip Code") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Checkboxes
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acceptTerms, onCheckedChange = { acceptTerms = it })
            Text("I accept the Terms and Conditions")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = acceptDataPolicy, onCheckedChange = { acceptDataPolicy = it })
            Text("I agree to the Data Processing Policy")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Botón
        Button(
            onClick = { /* TODO: lógica de registro */ },
            enabled = acceptTerms && acceptDataPolicy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Register")
        }

        Spacer(modifier = Modifier.height(32.dp)) // espacio final
    }
}

