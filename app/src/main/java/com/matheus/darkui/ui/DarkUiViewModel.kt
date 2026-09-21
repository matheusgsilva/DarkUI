package com.matheus.darkui.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.matheus.darkui.data.AppScanner
import com.matheus.darkui.engine.DarkIconEngine
import com.matheus.darkui.engine.IconCache
import com.matheus.darkui.export.IconExporter
import com.matheus.darkui.model.AppIconItem
import com.matheus.darkui.model.AppIconMode
import com.matheus.darkui.model.IconStyle
import com.matheus.darkui.model.SmartIconResult
import com.matheus.darkui.pack.GeneratedPackBuilder
import com.matheus.darkui.pack.PackInstaller
import com.matheus.darkui.pack.ThemeParkLauncher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DarkUiViewModel(application: Application) : AndroidViewModel(application) {
    data class UiState(
        val apps: List<AppIconItem> = emptyList(),
        val style: IconStyle = IconStyle.DARK,
        val query: String = "",
        val busy: Boolean = false,
        val progress: Float = 0f,
        val status: String = "Detectando aplicativos…",
        val builtApk: File? = null,
        val canInstallPackages: Boolean = false,
        val generatedPackInstalled: Boolean = false,
        val themeParkInstalled: Boolean = false
    )

    private val appContext = application.applicationContext
    private val scanner = AppScanner(appContext)
    private val engine = DarkIconEngine()
    private val cache = IconCache(appContext)
    private val packBuilder = GeneratedPackBuilder(appContext)
    private val installer = PackInstaller(appContext)
    private val themePark = ThemeParkLauncher(appContext)
    private val exporter = IconExporter(appContext)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        refreshExternalState()
        scanApps()
    }

    fun scanApps() {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f, status = "Lendo aplicativos instalados…", builtApk = null) }
            val apps = withContext(Dispatchers.IO) { scanner.scan().map { AppIconItem(it) } }
            _state.update { it.copy(apps = apps, status = "${apps.size} apps encontrados. Gerando ícones…") }
            generateAll(force = false)
        }
    }

    fun setStyle(style: IconStyle) {
        if (style == _state.value.style || _state.value.busy) return
        _state.update { it.copy(style = style, builtApk = null) }
        viewModelScope.launch { generateAll(force = false) }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun regenerateAll() {
        if (_state.value.busy) return
        cache.clear()
        viewModelScope.launch { generateAll(force = true) }
    }

    fun cycleMode(packageName: String) {
        if (_state.value.busy) return
        val index = _state.value.apps.indexOfFirst { it.app.packageName == packageName }
        if (index < 0) return
        val current = _state.value.apps[index]
        val next = current.mode.next()
        val changed = _state.value.apps.toMutableList().apply { set(index, current.copy(mode = next)) }
        _state.update { it.copy(apps = changed, builtApk = null) }
        viewModelScope.launch { regenerateOne(index) }
    }

    fun exportIcons() {
        if (_state.value.busy || _state.value.apps.none { it.generated != null }) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, status = "Exportando PNGs para Pictures/DarkUI…") }
            val count = withContext(Dispatchers.IO) { exporter.export(_state.value.apps) }
            _state.update { it.copy(busy = false, status = if (count > 0) "$count ícones exportados." else "Exportação requer Android 10 ou superior.") }
        }
    }

    fun buildPack() {
        if (_state.value.busy || _state.value.apps.none { it.generated != null }) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, progress = 0f, status = "Montando DarkUI Generated…") }
            val result = runCatching { withContext(Dispatchers.IO) { packBuilder.build(_state.value.apps) } }
            result.onSuccess { built ->
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 1f,
                        builtApk = built.apk,
                        status = "Pack pronto: ${built.iconCount} apps / ${built.componentCount} atalhos."
                    )
                }
                installBuiltPack()
            }.onFailure { error ->
                _state.update { it.copy(busy = false, status = "Falha ao gerar pack: ${error.message ?: error.javaClass.simpleName}") }
            }
        }
    }

    fun requestInstallPermission() {
        getApplication<Application>().startActivity(installer.permissionIntent())
    }

    fun installBuiltPack() {
        val apk = _state.value.builtApk ?: return
        refreshExternalState()
        if (!installer.canInstallPackages()) {
            _state.update { it.copy(status = "Autorize ‘Instalar apps desconhecidos’ para o DarkUI e volte aqui.") }
            requestInstallPermission()
            return
        }
        getApplication<Application>().startActivity(installer.installIntent(apk))
        _state.update { it.copy(status = "Confirme a instalação/atualização de DarkUI Generated.") }
    }

    fun openThemePark() {
        refreshExternalState()
        val intent = themePark.launchIntent()
        if (intent != null) {
            getApplication<Application>().startActivity(intent)
            _state.update { it.copy(status = "No Theme Park: Icons → Create new → Iconpack → DarkUI Generated → Apply.") }
        } else {
            runCatching {
                getApplication<Application>().startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("samsungapps://ProductDetail/${ThemeParkLauncher.THEME_PARK_PACKAGE}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            _state.update { it.copy(status = "Theme Park não foi encontrado. Instale/ative o módulo Theme Park do Good Lock.") }
        }
    }

    fun refreshExternalState() {
        _state.update {
            it.copy(
                canInstallPackages = installer.canInstallPackages(),
                generatedPackInstalled = installer.isGeneratedPackInstalled(),
                themeParkInstalled = themePark.isInstalled()
            )
        }
    }

    private suspend fun generateAll(force: Boolean) {
        val snapshot = _state.value.apps
        if (snapshot.isEmpty()) {
            _state.update { it.copy(busy = false, status = "Nenhum app com ícone de launcher foi encontrado.") }
            return
        }
        _state.update { it.copy(busy = true, progress = 0f, status = "Gerando ícones ${_state.value.style.title}…", builtApk = null) }
        val style = _state.value.style
        val output = snapshot.toMutableList()
        withContext(Dispatchers.Default) {
            output.indices.forEach { index ->
                val item = output[index]
                output[index] = item.copy(generated = generateItem(item, style, force))
                if (index % 4 == 0 || index == output.lastIndex) {
                    _state.update {
                        it.copy(
                            apps = output.toList(),
                            progress = (index + 1f) / output.size,
                            status = "Gerando ícones… ${index + 1}/${output.size}"
                        )
                    }
                }
            }
        }
        _state.update { it.copy(apps = output, busy = false, progress = 1f, status = "${output.size} ícones prontos.") }
    }

    private suspend fun regenerateOne(index: Int) {
        _state.update { it.copy(busy = true, status = "Regerando ${it.apps[index].app.label}…") }
        val item = _state.value.apps[index]
        val generated = withContext(Dispatchers.Default) { generateItem(item, _state.value.style, force = true) }
        val list = _state.value.apps.toMutableList()
        list[index] = item.copy(generated = generated)
        _state.update { it.copy(apps = list, busy = false, status = "${item.app.label}: modo ${item.mode.title}.") }
    }

    private fun generateItem(item: AppIconItem, globalStyle: IconStyle, force: Boolean): SmartIconResult {
        if (item.mode == AppIconMode.ORIGINAL) {
            return SmartIconResult(item.app.originalBitmap, "Original", 1f)
        }
        val style = when (item.mode) {
            AppIconMode.AUTO -> globalStyle
            AppIconMode.DARK -> IconStyle.DARK
            AppIconMode.AMOLED -> IconStyle.AMOLED
            AppIconMode.TINTED -> IconStyle.TINTED
            AppIconMode.ORIGINAL -> globalStyle
        }
        if (!force) {
            cache.get(item.app.packageName, item.app.versionCode, style)?.let {
                return SmartIconResult(it, "Cache inteligente", 1f)
            }
        }
        val generated = engine.generate(item.app.sourceDrawable, style, isGame = item.app.isGame)
        cache.put(item.app.packageName, item.app.versionCode, style, generated.bitmap)
        return generated
    }
}
