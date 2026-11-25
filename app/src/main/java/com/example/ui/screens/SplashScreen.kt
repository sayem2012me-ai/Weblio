package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.WeblioGrayBg
import com.example.ui.theme.WeblioSlateText
import com.example.ui.theme.WeblioWhite
import kotlinx.coroutines.delay

@Composable
fun SplashScreen(
    isReady: Boolean = true,
    onFinished: () -> Unit
) {
    val scale = remember { Animatable(0.86f) }
    val alpha = remember { Animatable(0f) }
    var animationCompleted by remember { mutableStateOf(false) }
    val latestOnFinished by rememberUpdatedState(onFinished)

    LaunchedEffect(Unit) {
        scale.animateTo(1f, animationSpec = tween(420, easing = FastOutSlowInEasing))
        alpha.animateTo(1f, animationSpec = tween(320))
        delay(350)
        animationCompleted = true
    }

    LaunchedEffect(animationCompleted, isReady) {
        if (animationCompleted && isReady) {
            latestOnFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(WeblioGrayBg)
            .testTag("splash_screen_root"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .scale(scale.value)
                .alpha(alpha.value)
                .padding(32.dp)
        ) {
            Text(
                text = "Weblo",
                style = MaterialTheme.typography.displayLarge.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp
                ),
                color = WeblioWhite
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(id = R.string.tagline),
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 2.2.sp
                ),
                color = WeblioSlateText
            )
        }
    }
}
