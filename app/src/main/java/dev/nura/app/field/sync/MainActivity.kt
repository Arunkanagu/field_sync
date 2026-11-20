package dev.nura.app.field.sync

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.nura.app.field.sync.navigation.FieldSyncRoot
import dev.nura.app.field.sync.ui.theme.FieldSyncTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FieldSyncTheme {
                FieldSyncRoot()
            }
        }
    }
}
