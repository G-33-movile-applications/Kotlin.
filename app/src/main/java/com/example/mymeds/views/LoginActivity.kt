package com.example.mymeds.views

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.mymeds.R
import com.example.mymeds.session.RememberSessionPrefs
import com.example.mymeds.ui.theme.MyMedsTheme
import com.example.mymeds.viewModels.LoginViewModel
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {
    private val loginViewModel: LoginViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val hasFirebaseUser = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser != null
            val rememberedOk = RememberSessionPrefs.shouldAutoLogin(this@LoginActivity)

            if (hasFirebaseUser && rememberedOk) {
                // 🔁 sesión válida → ir a Home y terminar LoginActivity
                startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                finish()
                return@launch    // 👈 evita ejecutar setContent
            }

            // ⬇️ solo pintamos la UI si NO hay autologin
            setContent {
                MyMedsTheme {
                    LoginScreen(
                        viewModel = loginViewModel,
                        onLoginSuccess = { keepSignedIn ->
                            lifecycleScope.launch {
                                if (keepSignedIn) {
                                    RememberSessionPrefs.setKeepSignedIn(this@LoginActivity, true)
                                    RememberSessionPrefs.setLastLoginNow(this@LoginActivity)
                                } else {
                                    RememberSessionPrefs.setKeepSignedIn(this@LoginActivity, false)
                                    RememberSessionPrefs.clearTimestamp(this@LoginActivity)
                                }
                            }
                            startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                            finish()
                        },
                        onShowMessage = { msg ->
                            Toast.makeText(this@LoginActivity, msg, Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }
        }
    }
}


@Composable
fun LoginScreen(
    viewModel: LoginViewModel,
    onLoginSuccess: (keepSignedInChecked: Boolean) -> Unit,
    onShowMessage: (String) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var keepSignedIn by remember { mutableStateOf(true) } // ✅ por defecto marcado
    val context = LocalContext.current
    val primaryButtonColor = Color(0xFF1A2247)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Image(
            painter = painterResource(id = R.mipmap.ic_launcher_foreground),
            contentDescription = "App Logo",
            modifier = Modifier
                .size(160.dp)
                .padding(bottom = 32.dp)
        )

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )

        // ✅ Casilla “Mantener sesión por 7 días”
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Checkbox(
                checked = keepSignedIn,
                onCheckedChange = { keepSignedIn = it }
            )
            Text("Mantener sesión iniciada por 7 días")
        }

        TextButton(
            onClick = {
                context.startActivity(Intent(context, PasswordResetActivity::class.java))
            },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Forgot password?", color = Color(0xFF1A2247))
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                viewModel.login(email, password) { success, message ->
                    onShowMessage(message ?: "Operación completada") // <- convierte String? a String
                    if (success) onLoginSuccess(keepSignedIn)
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

        Button(
            onClick = { context.startActivity(Intent(context, RegisterActivity::class.java)) },
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
