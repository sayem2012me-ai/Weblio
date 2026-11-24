package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.db.ProfileEntity
import com.example.engine.ProfileIsolationManager
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfilesScreen(
    profiles: List<ProfileEntity>,
    activeProfileId: Long,
    onSwitchProfile: (ProfileEntity) -> Unit,
    onCreateProfile: (String, String) -> Unit,
    onRenameProfile: (ProfileEntity, String) -> Unit,
    onUpdateIcon: (ProfileEntity, String) -> Unit,
    onSetPin: (ProfileEntity, String?) -> Unit,
    onSetBiometric: (ProfileEntity, Boolean) -> Unit,
    onDeleteProfile: (ProfileEntity) -> Unit,
    onBack: () -> Unit
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProfileEntity?>(null) }
    var pinDialogProfile by remember { mutableStateOf<ProfileEntity?>(null) }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Browser Profiles") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreateDialog = true },
                containerColor = WeblioElectricBlue,
                modifier = Modifier.testTag("profiles_add_fab")
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create profile")
            }
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            item {
                Card(
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("True Local Profile Isolation", style = MaterialTheme.typography.titleMedium, color = WeblioCyan)
                        Text(
                            text = if (ProfileIsolationManager.isMultiProfileSupported()) {
                                "WebKit Multi-Profile Store is active on this device: cookies, storage, cache, bookmarks, history, extensions, downloads, and Keystore passwords are strictly isolated per profile."
                            } else {
                                "Isolated Room data & session storage active per profile; Guest mode clears ephemeral session data automatically."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            items(profiles, key = { it.id }) { profile ->
                val isActive = profile.id == activeProfileId
                Card(
                    shape = RoundedCornerShape(20.dp),
                    border = if (isActive) BorderStroke(1.8.dp, WeblioCyan) else null,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable { onSwitchProfile(profile) }
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(if (isActive) WeblioCyan.copy(alpha = 0.22f) else WeblioElectricBlue.copy(alpha = 0.16f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = profileIconFor(profile.iconKey),
                                    contentDescription = null,
                                    tint = if (isActive) WeblioCyan else WeblioElectricBlue
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    if (isActive) {
                                        Spacer(Modifier.width(8.dp))
                                        Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = WeblioSuccess)
                                    }
                                }
                                Text(
                                    text = when {
                                        profile.isGuest -> "Ephemeral Guest Session • Zero saved history"
                                        profile.pinHash != null && profile.biometricEnabled -> "PIN + Biometric Locked"
                                        profile.pinHash != null -> "PIN Protected"
                                        profile.biometricEnabled -> "Biometric Protected"
                                        else -> "Isolated Local Profile"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!profile.isGuest) {
                                IconButton(onClick = { editingProfile = profile }) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit profile")
                                }
                                if (profiles.count { !it.isGuest } > 1) {
                                    IconButton(onClick = { onDeleteProfile(profile) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete profile", tint = WeblioDanger)
                                    }
                                }
                            }
                        }

                        if (!profile.isGuest) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { pinDialogProfile = profile }) {
                                    Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (profile.pinHash == null) "Set PIN" else "Change/Clear PIN")
                                }
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Filled.Fingerprint, contentDescription = null, tint = WeblioCyan, modifier = Modifier.size(18.dp))
                                Text("Biometric", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = profile.biometricEnabled,
                                    onCheckedChange = { onSetBiometric(profile, it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        ProfileEditorDialog(
            title = "Create Local Profile",
            initialName = "",
            initialIcon = "person",
            onDismiss = { showCreateDialog = false },
            onConfirm = { name, icon ->
                onCreateProfile(name, icon)
                showCreateDialog = false
            }
        )
    }

    editingProfile?.let { prof ->
        ProfileEditorDialog(
            title = "Edit Profile",
            initialName = prof.name,
            initialIcon = prof.iconKey,
            onDismiss = { editingProfile = null },
            onConfirm = { name, icon ->
                onRenameProfile(prof, name)
                onUpdateIcon(prof, icon)
                editingProfile = null
            }
        )
    }

    pinDialogProfile?.let { prof ->
        var pinInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { pinDialogProfile = null },
            title = { Text("Profile PIN for ${prof.name}") },
            text = {
                OutlinedTextField(
                    value = pinInput,
                    onValueChange = { pinInput = it },
                    label = { Text("Enter new PIN (leave empty to remove)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSetPin(prof, pinInput.ifBlank { null })
                    pinDialogProfile = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { pinDialogProfile = null }) { Text("Cancel") }
            }
        )
    }
}

private fun profileIconFor(key: String): ImageVector = when (key) {
    "work" -> Icons.Filled.Work
    "guest" -> Icons.Filled.VisibilityOff
    "code" -> Icons.Filled.Code
    "school" -> Icons.Filled.School
    else -> Icons.Filled.Person
}

@Composable
private fun ProfileEditorDialog(
    title: String,
    initialName: String,
    initialIcon: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var iconKey by remember { mutableStateOf(initialIcon) }
    val icons = listOf("person" to "Personal", "work" to "Work", "code" to "Dev", "school" to "Study")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Profile Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    icons.forEach { (key, label) ->
                        FilterChip(
                            selected = iconKey == key,
                            onClick = { iconKey = key },
                            label = { Text(label) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, iconKey) }, enabled = name.isNotBlank()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
