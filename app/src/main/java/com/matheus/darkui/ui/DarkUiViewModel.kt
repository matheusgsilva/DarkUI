package com.matheus.darkui.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.matheus.darkui.data.AppScanner
import com.matheus.darkui.engine.AiIconSegmenter
import com.matheus.darkui.engine.DarkIconEngine
import com.matheus.darkui.engine.IconCache
import com.matheus.darkui.export.IconExporter
import com.matheus.darkui.model.AppIconItem
import com.matheus.darkui.model.SmartIconResult
import com.matheus.darkui.pack.GeneratedPackBuilder
import com.matheus.darkui.pack.PackInstaller
import com.matheus.darkui.pack.ThemeParkLauncher
import com.matheus.darkui.util.BitmapUtils
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
        val query: String = "",
        val busy: Boolean = false,
        val progress: Float = 0f,
        val status: String = "Detectando aplicativos…",
        val builtApk: File? = null,
        val generatedPackInstalled: Boolean = false,
        val themeParkInstalled: Boolean = false
    )

    private val appContext = application.applicationContext
    private val scanner = AppScanner(appContext)
    private val engine = DarkIconEngine()
    private val aiSegmenter: AiIconSegmenter? by lazy {
        runCatching { AiIconSegmenter(appContext, AI_ICON_SIZE) }.getOrNull()
    }
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
            _state.update {
                it.copy(
                    busy = true,
                    progress = 0f,
                    status = "Lendo aplicativos instalados…",
                    builtApk = null
                )
            }

            val scanResult = runCatching {
                withContext(Dispatchers.IO) {
                    scanner.scan().map { AppIconItem(it) }
                }
            }

            val apps = scanResult.getOrElse { error ->
                _state.update {
                    it.copy(
                        busy = false,
                        progress = 0f,
                        status = "Falha ao ler aplicativos: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    apps = apps,
                    status = "${apps.size} apps encontrados. Criando aparência Dark…"
                )
            }
            generateAll(force = false)
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun regenerateAll() {
        if (_state.value.busy) return
        cache.clear()
        viewModelScope.launch { generateAll(force = true) }
    }

    fun exportIcons() {
        if (_state.value.busy || _state.value.apps.none { it.generated != null }) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    status = "Exportando ícones Dark para Pictures/DarkUI…"
                )
            }

            val count = withContext(Dispatchers.IO) {
                exporter.export(_state.value.apps)
            }

            _state.update {
                it.copy(
                    busy = false,
                    status = if (count > 0) {
                        "$count ícones Dark exportados."
                    } else {
                        "Exportação requer Android 10 ou superior."
                    }
                )
            }
        }
    }

    fun buildPack() {
        if (_state.value.busy || _state.value.apps.none { it.generated != null }) return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    busy = true,
                    progress = 0f,
                    status = "Montando o pack DarkUI Generated…"
                )
            }

            val result = runCatching {
                withContext(Dispatchers.IO) {
                    packBuilder.build(_state.value.apps)
                }
            }

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
                _state.update {
                    it.copy(
                        busy = false,
                        status = "Falha ao gerar pack: ${error.message ?: error.javaClass.simpleName}"
                    )
                }
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
            _state.update {
                it.copy(
                    status = "Autorize ‘Instalar apps desconhecidos’ para o DarkUI e volte aqui."
                )
            }
            requestInstallPermission()
            return
        }

        getApplication<Application>().startActivity(installer.installIntent(apk))
        _state.update {
            it.copy(status = "Confirme a instalação/atualização de DarkUI Generated.")
        }
    }

    fun openThemePark() {
        refreshExternalState()
        val intent = themePark.launchIntent()

        if (intent != null) {
            getApplication<Application>().startActivity(intent)
            _state.update {
                it.copy(
                    status = "No Theme Park: Icons → Create new → Iconpack → DarkUI Generated → Apply."
                )
            }
        } else {
            runCatching {
                getApplication<Application>().startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("samsungapps://ProductDetail/${ThemeParkLauncher.THEME_PARK_PACKAGE}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }

            _state.update {
                it.copy(
                    status = "Theme Park não foi encontrado. Instale/ative o módulo Theme Park do Good Lock."
                )
            }
        }
    }

    fun refreshExternalState() {
        _state.update {
            it.copy(
                generatedPackInstalled = installer.isGeneratedPackInstalled(),
                themeParkInstalled = themePark.isInstalled()
            )
        }
    }

    private suspend fun generateAll(force: Boolean) {
        val snapshot = _state.value.apps

        if (snapshot.isEmpty()) {
            _state.update {
                it.copy(
                    busy = false,
                    status = "Nenhum app com ícone de launcher foi encontrado."
                )
            }
            return
        }

        _state.update {
            it.copy(
                busy = true,
                progress = 0f,
                status = "Analisando ícones com IA local…",
                builtApk = null
            )
        }

        val output = snapshot.toMutableList()
        var failures = 0

        withContext(Dispatchers.Default) {
            output.indices.forEach { index ->
                val item = output[index]
                val generated = runCatching {
                    generateItem(item, force)
                }.getOrNull()

                if (generated == null) failures++
                output[index] = item.copy(generated = generated)

                if (index % 4 == 0 || index == output.lastIndex) {
                    _state.update {
                        it.copy(
                            apps = output.toList(),
                            progress = (index + 1f) / output.size,
                            status = "Gerando Dark… ${index + 1}/${output.size}"
                        )
                    }
                }
            }
        }

        val generatedCount = output.count { it.generated != null }
        val aiCount = output.count { it.generated?.method?.startsWith("IA local") == true }
        _state.update {
            it.copy(
                apps = output,
                busy = false,
                progress = 1f,
                status = if (failures == 0) {
                    "$generatedCount ícones Dark prontos • $aiCount segmentados por IA."
                } else {
                    "$generatedCount ícones Dark prontos; $failures falharam • $aiCount por IA."
                }
            )
        }
    }

    private fun generateItem(item: AppIconItem, force: Boolean): SmartIconResult {
        if (!force) {
            cache.get(item.app.packageName, item.app.versionCode)?.let {
                return SmartIconResult(
                    bitmap = it,
                    method = "Dark automático • cache",
                    confidence = 1f
                )
            }
        }

        val aiGenerated = if (!item.app.isGame) {
            runCatching {
                val source = BitmapUtils.drawableToBitmap(
                    item.app.sourceDrawable,
                    AI_ICON_SIZE
                )
                val segmentation = aiSegmenter?.segment(source)
                if (segmentation != null) {
                    engine.generateWithAiMask(
                        source = source,
                        foregroundMask = segmentation.mask,
                        maskConfidence = segmentation.confidence,
                        isGame = false
                    )
                } else {
                    null
                }
            }.getOrNull()
        } else {
            null
        }

        val generated = aiGenerated ?: engine.generate(
            drawable = item.app.sourceDrawable,
            isGame = item.app.isGame
        )

        cache.put(
            packageName = item.app.packageName,
            versionCode = item.app.versionCode,
            bitmap = generated.bitmap
        )

        return generated
    }

    override fun onCleared() {
        runCatching { aiSegmenter?.close() }
        super.onCleared()
    }

    companion object {
        private const val AI_ICON_SIZE = 256
    }
}
