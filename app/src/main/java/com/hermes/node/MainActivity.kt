package com.hermes.node

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hermes.node.data.ConfigSerializer
import com.hermes.node.data.EncryptedConfigRepository
import com.hermes.node.engine.BootstrapExtractor
import com.hermes.node.service.HermesServerService
import com.hermes.node.ui.navigation.HermesApp
import com.hermes.node.ui.theme.HermesTheme
import com.hermes.node.viewmodel.ServerViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: ServerViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appCtx = applicationContext
                val extractor = BootstrapExtractor(appCtx)
                val configRepository = EncryptedConfigRepository.create(appCtx)
                val configSerializer = ConfigSerializer(appCtx.filesDir)
                return ServerViewModel(
                    context = appCtx,
                    bootstrapExtractor = extractor,
                    configRepository = configRepository,
                    configSerializer = configSerializer,
                    serviceRunningFlow = HermesServerService.isRunning,
                    processStateFlow = HermesServerService.processState
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            HermesTheme {
                HermesApp(viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.checkBatteryOptimizationStatus(this)
    }
}
