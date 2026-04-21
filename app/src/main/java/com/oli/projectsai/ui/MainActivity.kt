package com.oli.projectsai.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.rememberNavController
import com.oli.projectsai.data.sync.SyncRepository
import com.oli.projectsai.inference.EXTRA_OPEN_CHAT_ID
import com.oli.projectsai.ui.navigation.ProjectsAINavGraph
import com.oli.projectsai.ui.navigation.Routes
import com.oli.projectsai.ui.theme.ProjectsAITheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var syncRepository: SyncRepository

    private val pendingChatId = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeChatIdExtra(intent)
        // Foreground poll: sync every 60s while the activity is at least STARTED.
        // repeatOnLifecycle auto-cancels the loop when backgrounded and restarts
        // on return, so we never burn battery or data when the app isn't visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    syncRepository.syncNow()
                    delay(60_000)
                }
            }
        }
        setContent {
            ProjectsAITheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    val chatId by pendingChatId.collectAsState()
                    LaunchedEffect(chatId) {
                        val id = chatId
                        if (id != null) {
                            navController.navigate(Routes.chat(id))
                            pendingChatId.value = null
                        }
                    }
                    ProjectsAINavGraph(navController = navController)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeChatIdExtra(intent)
    }

    private fun consumeChatIdExtra(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_OPEN_CHAT_ID, -1L) ?: -1L
        if (id > 0) pendingChatId.value = id
    }
}
