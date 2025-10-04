package com.example.mymeds.views

import android.os.Bundle
import android.widget.Toast
import androidx.compose.runtime.getValue

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.mymeds.R
import com.example.mymeds.viewModels.ProfileViewModel
import kotlinx.coroutines.launch
import com.example.mymeds.ui.theme.MyMedsTheme
import android.util.Log

class ProfileActivity : ComponentActivity() {
    private val profileViewModel: ProfileViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        profileViewModel.loadProfile()

        setContent {
            MyMedsTheme {
                // Pass the same ViewModel instance to the screen
                ProfileScreen(vm = profileViewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(vm: ProfileViewModel = viewModel()) {
    val profile by vm.profile.observeAsState()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            // This is the content that appears when the drawer is open
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    icon = { Icon(Icons.Outlined.Home, contentDescription = "Home") },
                    label = { Text("Home") },
                    selected = false,
                    onClick = { /* TODO: Navigate to Home */ }
                )
                // Add more NavigationDrawerItem here for other screens
            }
        }
    ) {
        // The Scaffold is now the main content of the ModalNavigationDrawer
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = "PROFILE",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            fontFamily = FontFamily.SansSerif,
                            letterSpacing = 2.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            scope.launch {
                                drawerState.apply {
                                    if (isClosed) open() else close()
                                }
                            }
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open Navigation Menu",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { /* TODO: Home action */ }) {
                            Icon(
                                Icons.Outlined.Home,
                                contentDescription = "Home",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                        IconButton(onClick = { /* TODO: More options */ }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = "More",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(30.dp))

                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "Profile picture",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                )

                Spacer(Modifier.height(30.dp))

                Text("Full Name", modifier = Modifier.fillMaxWidth())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            Color.Gray, RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Text(text = profile?.fullName ?: "Loading...")
                }
                Spacer(Modifier.height(8.dp))
                Text("Email", modifier = Modifier.fillMaxWidth())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Text(text = profile?.email ?: "Loading...")
                }
                Spacer(Modifier.height(8.dp))
                Text("Address", modifier = Modifier.fillMaxWidth())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Text(text = profile?.address ?: "Loading...")
                }
                Spacer(Modifier.height(8.dp))
                Text("Phone Number", modifier = Modifier.fillMaxWidth())
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
                        .padding(horizontal = 16.dp, vertical = 16.dp)
                ) {
                    Text(text = profile?.phoneNumber ?: "Loading...")
                }
                Spacer(Modifier.height(30.dp))

                Button(
                    onClick = { /* TODO: Update profile and navigate to another screen */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("UPDATE PROFILE")
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { /* TODO: navigate to history */ },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text("PRESCRIPTION HISTORY")
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ProfileScreenPreview() {
    ProfileScreen()
}
