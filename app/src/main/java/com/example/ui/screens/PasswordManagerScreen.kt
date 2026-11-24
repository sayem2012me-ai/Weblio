package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.db.PasswordEntity
import com.example.data.security.KeystoreCryptoManager
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PasswordManagerScreen(
    passwords: List<PasswordEntity>,
    isUnlocked: Boolean,
    onRequestBiometricUnlock: () -> Unit,
    onDecryptPassword: (PasswordEntity) -> String?,
    onSavePassword: (String, String, String) -> Unit,
    onDeletePassword: (PasswordEntity) -> Unit,
    onShowMessage: (String) -> Unit,
    onBack: () -> Unit,
    onCopyPassword: ((String) -> Unit)? = null
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }
    val revealedIds = remember { mutableStateMapOf<Long, Boolean>() }

    val filtered = passwords.filter {
        query.isBlank() || it.originHost.contains(query, true) || it.username.contains(query, true)
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Password Vault") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (isUnlocked) {
                FloatingActionButton(onClick = { showAddDialog = true }, containerColor = WeblioElectricBlue) {
                    Icon(Icons.Filled.Add, contentDescription = "Add password")
                }
            }
        }
    ) { padding ->
        if (!isUnlocked) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = WeblioCyan, modifier = Modifier.size(64.dp))
                    Text("Encrypted Keystore Vault", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "Passwords are AES-256-GCM encrypted using Android Keystore and never leave your device.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(onClick = onRequestBiometricUnlock) {
                        Text("Authenticate to Unlock Vault")
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search saved credentials...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )

                if (filtered.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.Key, contentDescription = null, tint = WeblioCyan, modifier = Modifier.size(48.dp))
                            Spacer(Modifier.height(8.dp))
                            Text("No saved passwords", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { pw ->
                            val isShown = revealedIds[pw.id] == true
                            val plain = if (isShown) onDecryptPassword(pw) ?: "Decrypt error" else "••••••••••••"
                            Card(
                                shape = RoundedCornerShape(18.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Key, contentDescription = null, tint = WeblioCyan)
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(pw.originHost, fontWeight = FontWeight.Bold)
                                            Text(pw.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        IconButton(onClick = { revealedIds[pw.id] = !isShown }) {
                                            Icon(
                                                imageVector = if (isShown) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                                contentDescription = "Toggle password visibility"
                                            )
                                        }
                                        IconButton(onClick = {
                                            val dec = onDecryptPassword(pw)
                                            if (dec != null) {
                                                if (onCopyPassword != null) {
                                                    onCopyPassword(dec)
                                                } else {
                                                    val cm = context.getSystemService(ClipboardManager::class.java)
                                                    val clip = ClipData.newPlainText("WEBLIO Password", dec).apply {
                                                        description.extras = PersistableBundle().apply {
                                                            val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                                ClipDescription.EXTRA_IS_SENSITIVE
                                                            } else {
                                                                "android.content.extra.IS_SENSITIVE"
                                                            }
                                                            putBoolean(key, true)
                                                        }
                                                    }
                                                    cm?.setPrimaryClip(clip)
                                                }
                                                onShowMessage("Password copied to clipboard (marked sensitive)")
                                            }
                                        }) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy password")
                                        }
                                        IconButton(onClick = { onDeletePassword(pw) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "Delete credential", tint = WeblioDanger)
                                        }
                                    }
                                    Text(plain, style = MaterialTheme.typography.labelSmall, color = WeblioCyan)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        var host by remember { mutableStateOf("") }
        var username by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf(KeystoreCryptoManager.generateStrongPassword()) }

        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Encrypted Credential") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Website Host (e.g. github.com)") }, singleLine = true)
                    OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username / Email") }, singleLine = true)
                    OutlinedTextField(value = secret, onValueChange = { secret = it }, label = { Text("Password") }, singleLine = true)
                    OutlinedButton(
                        onClick = { secret = KeystoreCryptoManager.generateStrongPassword() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Generate Strong Password")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSavePassword(host, username, secret)
                        showAddDialog = false
                    },
                    enabled = host.isNotBlank() && username.isNotBlank() && secret.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Cancel") } }
        )
    }
}
