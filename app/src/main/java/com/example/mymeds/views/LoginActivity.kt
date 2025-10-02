package com.example.mymeds.views

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.mymeds.MainActivity
import com.example.mymeds.R
import com.example.mymeds.ui.theme.MyMedsTheme
import com.example.mymeds.viewModels.LoginViewModel

class LoginActivity : ComponentActivity() {
    private val loginViewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MyMedsTheme {
                LoginScreen(loginViewModel)
            }
        }
    }
}

@Composable
fun LoginScreen(viewModel: LoginViewModel) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val context = LocalContext.current

    val primaryButtonColor = Color(0xFF1A2247)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // --- App Icon ---
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "App Logo",
            modifier = Modifier
                .size(160.dp)
                .padding(bottom = 32.dp)
        )

        // --- Email ---
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        // --- Password ---
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        // --- Forgot Password ---
        TextButton(
            onClick = {
                context.startActivity(Intent(context, PasswordResetActivity::class.java))
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Forgot password?", color = Color(0xFF1A2247))
        }


        Spacer(modifier = Modifier.height(24.dp))

        // --- Login Button ---
        Button(
            onClick = {
                viewModel.login(email, password) { success, message ->
                    if (success) {
                        Toast.makeText(context, "Login successful", Toast.LENGTH_SHORT).show()

                        val token = message ?: ""
                        val prefs = context.getSharedPreferences("MyMedsPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("auth_token", token).apply()

                        context.startActivity(Intent(context, MainActivity::class.java))
                    } else {
                        Toast.makeText(context, "Login failed: $message", Toast.LENGTH_LONG).show()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = primaryButtonColor),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("LOGIN", color = Color.White, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(48.dp))

        // --- Register Button ---
        Button(
            onClick = {
                context.startActivity(Intent(context, RegisterActivity::class.java))
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE9E9E9)),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            shape = RoundedCornerShape(8.dp),
            elevation = ButtonDefaults.buttonElevation(0.dp)
        ) {
            Text("REGISTER", color = primaryButtonColor, fontWeight = FontWeight.Bold)
        }
    }
}
