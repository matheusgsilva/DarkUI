package com.matheus.darkui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import com.matheus.darkui.ui.DarkUiScreen
import com.matheus.darkui.ui.DarkUiViewModel
import com.matheus.darkui.ui.theme.DarkUITheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DarkUITheme {
                val vm: DarkUiViewModel = viewModel()
                DarkUiScreen(vm)
            }
        }
    }
}
