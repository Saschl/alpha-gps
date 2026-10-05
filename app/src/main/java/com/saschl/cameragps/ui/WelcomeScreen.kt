package com.saschl.cameragps.ui

import androidx.compose.runtime.Composable
import com.sasch.cameragps.sharednew.ui.welcome.SharedWelcomeScreen

@Composable
fun WelcomeScreen(onGetStarted: () -> Unit) {
    SharedWelcomeScreen(onGetStarted = onGetStarted)
}
