package com.matheus.darkui.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.matheus.darkui.model.AppIconItem
import com.matheus.darkui.model.IconStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DarkUiScreen(viewModel: DarkUiViewModel) {
    val state by viewModel.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshExternalState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val filtered = state.apps.filter {
        state.query.isBlank() ||
            it.app.label.contains(state.query, ignoreCase = true) ||
            it.app.packageName.contains(state.query, ignoreCase = true)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("DarkUI", fontWeight = FontWeight.Bold)
                        Text(
                            "Dark icons inteligentes para One UI",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SummaryCard(state, viewModel) }
            item { StyleSelector(state.style, enabled = !state.busy, onSelect = viewModel::setStyle) }
            item {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = viewModel::setQuery,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    label = { Text("Buscar aplicativo") },
                    singleLine = true
                )
            }
            items(filtered, key = { it.app.packageName }) { item ->
                AppIconRow(item = item, enabled = !state.busy, onCycleMode = { viewModel.cycleMode(item.app.packageName) })
            }
            item { PackActions(state, viewModel) }
            item { Spacer(Modifier.height(28.dp)) }
        }
    }
}

@Composable
private fun SummaryCard(state: DarkUiViewModel.UiState, vm: DarkUiViewModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Default.DarkMode, null)
                Column(Modifier.weight(1f)) {
                    Text("${state.apps.size} aplicativos", fontWeight = FontWeight.SemiBold)
                    Text(state.status, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state.busy) {
                LinearProgressIndicator(progress = { state.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::scanApps, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Escanear")
                }
                OutlinedButton(onClick = vm::regenerateAll, enabled = !state.busy && state.apps.isNotEmpty(), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Build, null)
                    Spacer(Modifier.size(6.dp))
                    Text("Regerar")
                }
            }
        }
    }
}

@Composable
private fun StyleSelector(style: IconStyle, enabled: Boolean, onSelect: (IconStyle) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Estilo global", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconStyle.entries.forEach { option ->
                FilterChip(
                    selected = option == style,
                    onClick = { onSelect(option) },
                    enabled = enabled,
                    label = { Text(option.title) }
                )
            }
        }
        Text(
            "Toque no modo de um app para alternar Auto → Dark → AMOLED → Tinted → Original.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppIconRow(item: AppIconItem, enabled: Boolean, onCycleMode: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    bitmap = item.app.originalBitmap.asImageBitmap(),
                    contentDescription = "Original",
                    modifier = Modifier.size(54.dp),
                    contentScale = ContentScale.Fit
                )
                Text("→", style = MaterialTheme.typography.titleLarge)
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    item.generated?.let {
                        Image(
                            bitmap = it.bitmap.asImageBitmap(),
                            contentDescription = "Dark",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(item.app.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    Text(
                        item.app.packageName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = onCycleMode, enabled = enabled, label = { Text(item.mode.title) })
                item.generated?.let {
                    Text(
                        "${it.method} • ${(it.confidence * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PackActions(state: DarkUiViewModel.UiState, vm: DarkUiViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        HorizontalDivider()
        Text("Aplicar na One UI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "O DarkUI cria um APK de icon pack com os ícones acima. O Android pede confirmação para instalar e o Theme Park faz a aplicação final.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(
            onClick = vm::buildPack,
            enabled = !state.busy && state.apps.any { it.generated != null },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.Build, null)
            Spacer(Modifier.size(8.dp))
            Text("Gerar e instalar icon pack")
        }
        if (state.builtApk != null) {
            OutlinedButton(onClick = vm::installBuiltPack, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.size(8.dp))
                Text(if (state.generatedPackInstalled) "Reinstalar/atualizar pack" else "Instalar pack gerado")
            }
        }
        OutlinedButton(onClick = vm::exportIcons, enabled = !state.busy && state.apps.any { it.generated != null }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Download, null)
            Spacer(Modifier.size(8.dp))
            Text("Exportar PNGs")
        }
        Button(onClick = vm::openThemePark, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Settings, null)
            Spacer(Modifier.size(8.dp))
            Text(if (state.themeParkInstalled) "Abrir Theme Park" else "Instalar/abrir Theme Park")
        }
        Text(
            if (state.generatedPackInstalled) "✓ DarkUI Generated está instalado." else "O pack ainda não está instalado.",
            style = MaterialTheme.typography.labelMedium
        )
    }
}
