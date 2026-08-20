package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.ui.navigation.MainNavGraph
import com.example.ui.theme.UniversalClipboardTheme
import com.example.ui.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UniversalClipboardTheme {
                MainNavGraph(viewModel = mainViewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        mainViewModel.startClipboardCapture()
    }

    override fun onPause() {
        super.onPause()
        mainViewModel.stopClipboardCapture()
    }
}

