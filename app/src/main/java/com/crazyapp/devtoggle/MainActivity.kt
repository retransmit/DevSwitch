package com.crazyapp.devtoggle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.crazyapp.devtoggle.ui.DevToggleTheme
import com.crazyapp.devtoggle.ui.MainScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DevToggleTheme {
                MainScreen()
            }
        }
    }
}
