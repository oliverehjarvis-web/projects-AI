package com.oli.projectsai.ui.privacy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PrivateGateScreen(
    onNeedSetup: () -> Unit,
    onNeedUnlock: () -> Unit,
    onAlreadyUnlocked: () -> Unit,
    viewModel: PrivateGateViewModel = hiltViewModel()
) {
    val target by viewModel.target.collectAsStateWithLifecycle()

    LaunchedEffect(target) {
        when (target) {
            PrivateGateTarget.Setup -> onNeedSetup()
            PrivateGateTarget.Unlock -> onNeedUnlock()
            PrivateGateTarget.Projects -> onAlreadyUnlocked()
            PrivateGateTarget.Loading -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
