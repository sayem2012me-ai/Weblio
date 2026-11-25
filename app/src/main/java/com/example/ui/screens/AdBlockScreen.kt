package com.example.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VpnLock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.db.AdBlockRuleEntity
import com.example.data.db.FilterRuleType
import com.example.data.db.FilterSubscriptionEntity
import com.example.data.prefs.BrowserSettings
import com.example.data.prefs.DnsFilterMode
import com.example.engine.BlockedEvent
import com.example.engine.WeblioDnsVpnService
import com.example.ui.theme.WeblioCyan
import com.example.ui.theme.WeblioDanger
import com.example.ui.theme.WeblioElectricBlue
import com.example.ui.theme.WeblioSuccess
import com.example.ui.theme.WeblioWarning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdBlockScreen(
    settings: BrowserSettings,
    sessionBlockedCount: Long,
    recentBlocked: List<BlockedEvent>,
    rules: List<AdBlockRuleEntity>,
    subscriptions: List<FilterSubscriptionEntity>,
    vpnRunning: Boolean,
    dnsBlockedCount: Long,
    onToggleGlobal: (Boolean) -> Unit,
    onToggleTrackerProtection: (Boolean) -> Unit,
    onTogglePopupBlocking: (Boolean) -> Unit,
    onToggleThirdParty: (Boolean) -> Unit,
    onSelectDnsMode: (DnsFilterMode) -> Unit,
    onUpdateCustomDoh: (String) -> Unit,
    onAddRule: (String, FilterRuleType) -> Unit,
    onToggleRule: (AdBlockRuleEntity) -> Unit,
    onDeleteRule: (AdBlockRuleEntity) -> Unit,
    onUpdateFiltersNow: () -> Unit,
    onVpnToggleState: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var showVpnConsentDialog by remember { mutableStateOf(false) }
    val privateDnsActive = remember { WeblioDnsVpnService.isPrivateDnsActive(context) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val startIntent = Intent(context, WeblioDnsVpnService::class.java).apply {
                action = WeblioDnsVpnService.ACTION_START
            }
            context.startService(startIntent)
            onVpnToggleState(true)
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        topBar = {
            TopAppBar(
                title = { Text("Protection & Ad Shield") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onUpdateFiltersNow) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Update filter lists")
                    }
                    IconButton(onClick = { showAddRuleDialog = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add filter rule")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            TabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Dashboard") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Rules (${rules.size})") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("DNS & VPN") })
            }

            when (selectedTab) {
                0 -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Card(
                                shape = RoundedCornerShape(22.dp),
                                border = BorderStroke(1.2.dp, if (settings.adBlockerEnabled) WeblioCyan.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.Shield,
                                            contentDescription = null,
                                            tint = if (settings.adBlockerEnabled) WeblioSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Global Ad & Tracker Blocker", style = MaterialTheme.typography.titleLarge)
                                            Text(
                                                text = if (settings.adBlockerEnabled) "Shared across all profiles • Active" else "Protection Paused",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (settings.adBlockerEnabled) WeblioCyan else WeblioWarning
                                            )
                                        }
                                        Switch(
                                            checked = settings.adBlockerEnabled,
                                            onCheckedChange = onToggleGlobal,
                                            modifier = Modifier.testTag("adblock_global_switch")
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        StatBox("Total Blocked", "${settings.totalBlockedCount + sessionBlockedCount}")
                                        StatBox("This Session", "$sessionBlockedCount")
                                        StatBox("DNS Blocked", "$dnsBlockedCount")
                                    }
                                }
                            }
                        }

                        item {
                            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ShieldToggleRow("Tracker & Query Stripping", settings.trackerProtectionEnabled, onToggleTrackerProtection)
                                    ShieldToggleRow("Pop-up & Redirect Blocker", settings.popupBlockingEnabled, onTogglePopupBlocking)
                                    ShieldToggleRow("Strict 3rd-Party Script Blocking", settings.thirdPartyRequestBlocking, onToggleThirdParty)
                                }
                            }
                        }

                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Filter Subscriptions", style = MaterialTheme.typography.titleMedium)
                                TextButton(onClick = onUpdateFiltersNow) { Text("Update Lists") }
                            }
                        }

                        items(subscriptions, key = { it.id }) { sub ->
                            Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(sub.name, style = MaterialTheme.typography.titleMedium)
                                        Text("${sub.ruleCount} rules • ${sub.url}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        item {
                            Text("Recent Blocked Requests", style = MaterialTheme.typography.titleMedium)
                        }

                        if (recentBlocked.isEmpty()) {
                            item {
                                Text(
                                    text = "No blocked requests recorded yet this session. Browse a page to inspect real-time interceptions.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            items(recentBlocked) { ev ->
                                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                            Text(ev.host, fontWeight = FontWeight.SemiBold, color = WeblioCyan)
                                            Text(ev.category, style = MaterialTheme.typography.labelSmall, color = WeblioDanger)
                                        }
                                        Text("Rule: ${ev.matchedRule}", style = MaterialTheme.typography.labelSmall)
                                        Text(ev.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Button(onClick = { showAddRuleDialog = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add Custom Filter / Whitelist / DNS Rule")
                            }
                        }
                        items(rules, key = { it.id }) { rule ->
                            Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(rule.pattern, style = MaterialTheme.typography.labelSmall, color = WeblioCyan)
                                        Text("${rule.ruleType} • ${rule.sourceList} • Hits: ${rule.hitCount}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Switch(checked = rule.enabled, onCheckedChange = { onToggleRule(rule) })
                                    IconButton(onClick = { onDeleteRule(rule) }) {
                                        Icon(Icons.Filled.Delete, contentDescription = "Delete rule", tint = WeblioDanger)
                                    }
                                }
                            }
                        }
                    }
                }
                2 -> {
                    var dohDraft by remember(settings.customDohUrl) { mutableStateOf(settings.customDohUrl) }
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (privateDnsActive) {
                            item {
                                Card(
                                    shape = RoundedCornerShape(16.dp),
                                    border = BorderStroke(1.dp, WeblioWarning),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                                ) {
                                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.Warning, contentDescription = null, tint = WeblioWarning)
                                        Spacer(Modifier.width(10.dp))
                                        Text(
                                            text = "Android Private DNS (DNS-over-TLS) is currently active in system settings. System Private DNS bypasses local VpnService UDP port-53 filtering unless set to Automatic/Off.",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }

                        item {
                            Card(shape = RoundedCornerShape(18.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.VpnLock, contentDescription = null, tint = WeblioElectricBlue)
                                        Spacer(Modifier.width(10.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Local VPN DNS Filtering", style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                text = if (vpnRunning) "Active • Local loop filtering on 10.111.222.2" else "Stopped • Requires Android VPN slot",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (vpnRunning) WeblioSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Switch(
                                            checked = vpnRunning,
                                            onCheckedChange = { enable ->
                                                if (enable) {
                                                    showVpnConsentDialog = true
                                                } else {
                                                    context.startService(
                                                        Intent(context, WeblioDnsVpnService::class.java).apply {
                                                            action = WeblioDnsVpnService.ACTION_STOP
                                                        }
                                                    )
                                                    onVpnToggleState(false)
                                                }
                                            }
                                        )
                                    }
                                    Text(
                                        text = "Routes only DNS queries locally on-device to block tracker & ad domains against your DNS blocklist. Never sends traffic to an external VPN.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        item {
                            Text("DNS-over-HTTPS (DoH) Resolver", style = MaterialTheme.typography.titleMedium)
                        }

                        items(DnsFilterMode.entries) { mode ->
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                border = if (settings.dnsFilterMode == mode) BorderStroke(1.5.dp, WeblioCyan) else null,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                onClick = { onSelectDnsMode(mode) }
                            ) {
                                Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Dns, contentDescription = null, tint = if (settings.dnsFilterMode == mode) WeblioCyan else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(mode.label, style = MaterialTheme.typography.titleMedium)
                                        if (mode.dohEndpoint.isNotBlank()) {
                                            Text(mode.dohEndpoint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }

                        if (settings.dnsFilterMode == DnsFilterMode.CUSTOM) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedTextField(
                                        value = dohDraft,
                                        onValueChange = { dohDraft = it },
                                        label = { Text("Custom DoH Endpoint URL") },
                                        placeholder = { Text("https://dns.example.com/dns-query") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(onClick = { onUpdateCustomDoh(dohDraft) }, modifier = Modifier.fillMaxWidth()) {
                                        Text("Save Custom DoH Endpoint")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddRuleDialog) {
        AddFilterRuleDialog(
            onDismiss = { showAddRuleDialog = false },
            onSave = { pattern, type ->
                onAddRule(pattern, type)
                showAddRuleDialog = false
            }
        )
    }

    if (showVpnConsentDialog) {
        AlertDialog(
            onDismissRequest = { showVpnConsentDialog = false },
            icon = { Icon(Icons.Filled.VpnLock, contentDescription = null, tint = WeblioCyan) },
            title = { Text("Enable Local DNS Shield?") },
            text = {
                Text(
                    "Android allows only one active VPN service at a time. Enabling Weblo's local DNS shield will disconnect any other active VPN app. All filtering stays 100% local on your device."
                )
            },
            confirmButton = {
                Button(onClick = {
                    showVpnConsentDialog = false
                    val prep = VpnService.prepare(context)
                    if (prep != null) {
                        vpnPermissionLauncher.launch(prep)
                    } else {
                        context.startService(
                            Intent(context, WeblioDnsVpnService::class.java).apply {
                                action = WeblioDnsVpnService.ACTION_START
                            }
                        )
                        onVpnToggleState(true)
                    }
                }) { Text("Consent & Enable") }
            },
            dismissButton = {
                TextButton(onClick = { showVpnConsentDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StatBox(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = WeblioCyan, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShieldToggleRow(title: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun AddFilterRuleDialog(
    onDismiss: () -> Unit,
    onSave: (String, FilterRuleType) -> Unit
) {
    var pattern by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(FilterRuleType.NETWORK_BLOCK) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Custom Rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterRuleType.entries.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = {
                                Text(
                                    when (t) {
                                        FilterRuleType.NETWORK_BLOCK -> "Block"
                                        FilterRuleType.NETWORK_ALLOW -> "Allow"
                                        FilterRuleType.COSMETIC_HIDE -> "CSS"
                                        FilterRuleType.DNS_BLOCK -> "DNS"
                                    }
                                )
                            }
                        )
                    }
                }
                OutlinedTextField(
                    value = pattern,
                    onValueChange = { pattern = it },
                    label = {
                        Text(
                            when (selectedType) {
                                FilterRuleType.NETWORK_BLOCK -> "||ads.example.com^"
                                FilterRuleType.NETWORK_ALLOW -> "@@||trusted.example.com^"
                                FilterRuleType.COSMETIC_HIDE -> "##.ad-slot"
                                FilterRuleType.DNS_BLOCK -> "tracker.example.com"
                            }
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(pattern, selectedType) }, enabled = pattern.isNotBlank()) {
                Text("Add Rule")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
