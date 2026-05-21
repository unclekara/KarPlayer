package com.karplayer.ui

import androidx.activity.compose.BackHandler
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

private enum class Screen { QUICK, SETTINGS, PLAYER }

@Composable
fun KarPlayerNavRoot(playerManager: PlayerManager) {
    val ctx = LocalContext.current
    val vm: PlayerViewModel = viewModel(factory = PlayerViewModel.Factory(playerManager))

    val initialConfig = remember { ConnectionConfigStore.load(ctx) }
    var currentConfig by remember { mutableStateOf(initialConfig) }
    LaunchedEffect(initialConfig) { vm.setConfig(initialConfig) }

    // Both platforms now land on QuickConnect — phones get the same one-tap
    // flow that TV uses. Detail editing lives behind the Settings button.
    var screen by rememberSaveable { mutableStateOf(Screen.QUICK) }

    when (screen) {
        Screen.QUICK -> QuickConnectScreen(
            config = currentConfig,
            onConnect = {
                ConnectionConfigStore.save(ctx, currentConfig)
                vm.connect(currentConfig)
                screen = Screen.PLAYER
            },
            onSettings = { screen = Screen.SETTINGS }
        )
        Screen.SETTINGS -> {
            // BACK from Settings always returns to QuickConnect now that it
            // is the landing screen on every platform.
            BackHandler { screen = Screen.QUICK }
            ConnectionScreen(
                initial = currentConfig,
                onConnect = { cfg ->
                    currentConfig = cfg
                    ConnectionConfigStore.save(ctx, cfg)
                    vm.connect(cfg)
                    screen = Screen.PLAYER
                }
            )
        }
        Screen.PLAYER -> PlayerScreen(
            viewModel = vm,
            onDisconnect = {
                vm.disconnect()
                screen = Screen.QUICK
            }
        )
    }
}
