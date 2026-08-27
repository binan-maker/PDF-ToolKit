package com.yourname.pdftoolkit.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.ui.components.ToolTopBar

/**
 * Screen for converting HTML/Web pages to PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HtmlToPdfScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_html_to_pdf),
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "HTML to PDF Conversion",
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This feature is coming soon.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
