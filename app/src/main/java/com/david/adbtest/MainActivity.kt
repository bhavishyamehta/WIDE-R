package com.david.adbtest

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.david.adbtest.di.AppContainer
import com.david.adbtest.presentation.ui.MainScreen
import com.david.adbtest.ui.theme.ADBTestTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val viewModel = AppContainer.provideViewModel(this)

        enableEdgeToEdge()
        setContent {
            ADBTestTheme {
                MainScreen(viewModel = viewModel)
            }
        }
    }
}
