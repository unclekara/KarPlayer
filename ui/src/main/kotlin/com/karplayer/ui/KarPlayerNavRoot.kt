package com.karplayer.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.karplayer.player.PlayerManager

private enum class Screen { CONNECTION, PLAYER }

@Composable
fun KarPlayerNavRoot(playerManager: PlayerManager) {
    val ctx = LocalContext.current
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory(playerManager))

    var screen by rememberSaveable { mutableStateOf(Screen.CONNECTION) }
    val initialConfig = remember { ConnectionConfigStore.load(ctx) }
    // Hold the latest user choices across screen changes so that returning
    // from PlayerScreen restores exactly what they had, not the disk-loaded
    // baseline. Disk is the source of truth across cold starts only.
    var currentConfig by remember { mutableStateOf(initialConfig) }
    LaunchedEffect(initialConfig) { vm.setConfig(initialConfig) }

    when (screen) {
        Screen.CONNECTION -> ConnectionScreen(
            initial = currentConfig,
            onConnect = { cfg ->
                currentConfig = cfg
                ConnectionConfigStore.save(ctx, cfg)
                vm.connect(cfg)
                screen = Screen.PLAYER
            }
        )
        Screen.PLAYER -> PlayerScreen(
            viewModel = vm,
            onDisconnect = {
                vm.disconnect()
                screen = Screen.CONNECTION
            }
        )
    }
}
