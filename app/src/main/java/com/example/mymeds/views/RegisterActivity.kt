package com.example.mymeds.views

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.example.mymeds.viewModels.RegisterViewModel
import androidx.activity.result.contract.ActivityResultContracts

class RegisterActivity : ComponentActivity() {
    private val registerViewModel: RegisterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RegisterScreen(registerViewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(viewModel: RegisterViewModel = viewModel()) {
    val context = LocalContext.current

    // 📌 Campos de texto
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") }
    var zipCode by remember { mutableStateOf("") }

    // 📌 Imágenes
    var profilePicUri by remember { mutableStateOf<Uri?>(null) }
    var idPicUri by remember { mutableStateOf<Uri?>(null) }

    // 📌 Checkboxes
    var acceptTerms by remember { mutableStateOf(false) }
    var acceptDataPolicy by remember { mutableStateOf(false) }

    // Launchers para elegir imágenes
    val pickProfileImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        profilePicUri = uri
    }
    val pickIdImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        idPicUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Register", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(16.dp))

        // 🔹 Foto de perfil (opcional, círculo)
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .border(BorderStroke(2.dp, Color.Gray), CircleShape)
                .clickable { pickProfileImage.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (profilePicUri != null) {
                Image(
                    painter = rememberAsyncImagePainter(profilePicUri),
                    contentDescription = "Profile Picture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("Profile")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // --- Campos de texto ---
        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Full Name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = phone, onValueChange = { phone = it }, label = { Text("Phone") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("Address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = city, onValueChange = { city = it }, label = { Text("City") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = state, onValueChange = { state = it }, label = { Text("State") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = zipCode, onValueChange = { zipCode = it }, label = { Text("Zip Code") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 🔹 Foto de ID (obligatoria, rectángulo)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .border(BorderStroke(2.dp, if (idPicUri == null) Color.Red else Color.Gray))
                .clickable { pickIdImage.launch("image/*") },
            contentAlignment = Alignment.Center
        ) {
            if (idPicUri != null) {
                Image(
                    painter = rememberAsyncImagePainter(idPicUri),
                    contentDescription = "ID Picture",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text("Upload ID Document *", color = Color.Red)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 🔹 Checkboxes
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = acceptTerms, onCheckedChange = { acceptTerms = it })
            Text("I accept the Terms and Conditions")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(checked = acceptDataPolicy, onCheckedChange = { acceptDataPolicy = it })
            Text("I agree to the Data Processing Policy")
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 🔹 Botón de registro
        Button(
            onClick = {
                viewModel.register(
                    context,
                    name, email, password, phone,
                    address, city, state, zipCode,
                    profilePicUri, idPicUri
                ) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
            enabled = idPicUri != null && acceptTerms && acceptDataPolicy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Register")
        }
    }
}
