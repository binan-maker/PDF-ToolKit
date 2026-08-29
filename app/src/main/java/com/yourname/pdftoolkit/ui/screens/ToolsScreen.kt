package com.anonymous.imgpdf.ui.screens
import com.anonymous.imgpdf.util.safeLaunch

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonymous.imgpdf.BuildConfig
import com.anonymous.imgpdf.R
import com.anonymous.imgpdf.data.SafUriManager
import com.anonymous.imgpdf.ui.navigation.Screen
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Tool section enumeration for categorization.
 */
@Composable
fun getToolSections(): List<ToolSectionData> {
    return listOf(
        ToolSectionData(stringResource(R.string.category_quick_actions)),
        ToolSectionData(stringResource(R.string.category_organize)),
        ToolSectionData(stringResource(R.string.category_convert)),
        ToolSectionData(stringResource(R.string.category_security)),
        ToolSectionData(stringResource(R.string.category_image_tools)),
        ToolSectionData(stringResource(R.string.category_view_export))
    )
}

data class ToolSectionData(val title: String)

// Keep for backwards compatibility
enum class ToolSection(val title: String) {
    QUICK_ACTIONS("Quick Actions"),
    ORGANIZE("Organize"),
    CONVERT("Convert"),
    SECURITY("Security"),
    IMAGE_TOOLS("Image Tools"),
    VIEW_EXPORT("View & Export")
}

/**
 * Data class representing a PDF/Image tool.
 */
data class ToolItem(
    val id: String,
    val titleResId: Int,
    val descResId: Int,
    val icon: ImageVector,
    val section: ToolSection,
    val screen: Screen
) {
    @Composable
    fun getTitle(): String = stringResource(titleResId)

    @Composable
    fun getDescription(): String = stringResource(descResId)
}

/**
 * Tools Screen - Primary home screen with sectioned layout.
 * Organized in grid/card-based design with clear categorization.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    onNavigateToScreen: (Screen) -> Unit,
    onNavigateToRoute: ((String) -> Unit)? = null,
    onOpenPdfViewer: (Uri, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /**
     * Copy content URI to app cache for reliable access.
     * This is critical for picker URIs that lose permission quickly.
     */
    suspend fun copyUriToCache(context: android.content.Context, uri: Uri): Uri? = withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val cacheDir = File(context.cacheDir, "viewer_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            val tempFile = File(cacheDir, "pdf_${System.currentTimeMillis()}.pdf")

            // Try to copy the file
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext null

            // Return file:// URI for direct file access
            Uri.fromFile(tempFile)
        } catch (e: Exception) {
            android.util.Log.e("ToolsScreen", "Failed to copy URI to cache", e)
            null
        }
    }

    /**
     * PDF picker using SAF (ACTION_OPEN_DOCUMENT).
     * Immediately copies picked file to cache before opening to avoid permission issues.
     */
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                // Take persistable URI permission immediately
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val persistedFile = SafUriManager.addRecentFile(context, selectedUri, flags)

                val name = persistedFile?.name?.substringBeforeLast('.') ?: run {
                    var displayName = "PDF Document"
                    context.contentResolver.query(selectedUri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (nameIndex >= 0) {
                                displayName = c.getString(nameIndex)?.substringBeforeLast('.') ?: displayName
                            }
                        }
                    }
                    displayName
                }

                // CRITICAL: Copy to cache before opening to avoid permission expiration
                val cachedUri = copyUriToCache(context, selectedUri)
                if (cachedUri != null) {
                    onOpenPdfViewer(cachedUri, name)
                } else {
                    // Fallback to direct URI if copy fails
                    onOpenPdfViewer(selectedUri, name)
                }
            }
        }
    }

    val allTools = remember { getAllTools().filterNot { it.id == "view_pdf" } }
    val isDarkTheme = isSystemInDarkTheme()
    val homeColors = remember(isDarkTheme) { HomeColors.forTheme(isDarkTheme) }
    var selectedSection by remember { mutableStateOf<ToolSection?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    fun openTool(tool: ToolItem) {
        if (tool.id == "view_pdf") return

        val imageToolIds = listOf("image_compress", "image_resize", "image_convert", "image_metadata")
        if (imageToolIds.contains(tool.id) && onNavigateToRoute != null) {
            onNavigateToRoute(Screen.getRouteForToolId(tool.id))
        } else {
            onNavigateToScreen(tool.screen)
        }
    }

    val normalizedQuery = searchQuery.trim()
    val matchingTools = allTools.filter { tool ->
        val title = tool.getTitle()
        val description = tool.getDescription()
        normalizedQuery.isBlank() ||
                title.contains(normalizedQuery, ignoreCase = true) ||
                description.contains(normalizedQuery, ignoreCase = true)
    }
    val visibleTools = matchingTools.filter { tool ->
        selectedSection == null || tool.section == selectedSection
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(homeColors.canvas)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(22.dp),
            contentPadding = PaddingValues(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 40.dp)
        ) {
            item {
                ToolSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    colors = homeColors
                )
            }

            item {
                ToolFilterRail(
                    selectedSection = selectedSection,
                    onSectionSelected = { selectedSection = it },
                    colors = homeColors
                )
            }

            if (selectedSection == null && normalizedQuery.isBlank()) {
                item {
                    LaunchRail(
                        tools = allTools.filter { it.section == ToolSection.QUICK_ACTIONS },
                        colors = homeColors,
                        onToolClick = ::openTool
                    )
                }
            }

            if (visibleTools.isEmpty()) {
                item {
                    EmptyToolSearch(
                        query = normalizedQuery,
                        colors = homeColors
                    )
                }
            }

            ToolSection.entries
                .filter {
                    (selectedSection == null || it == selectedSection) &&
                            !(selectedSection == null && normalizedQuery.isBlank() && it == ToolSection.QUICK_ACTIONS)
                }
                .forEach { section ->
                    val sectionTools = visibleTools.filter { it.section == section }
                    if (sectionTools.isNotEmpty()) {
                        item {
                            HomeSectionHeading(
                                eyebrow = getSectionTitle(section).uppercase(),
                                title = getSectionTitle(section),
                                count = sectionTools.size,
                                colors = homeColors
                            )
                        }
                        item {
                            ToolGrid(
                                tools = sectionTools,
                                colors = homeColors,
                                onToolClick = ::openTool
                            )
                        }
                    }
                }
        }
    }
}

private data class HomeColors(
    val canvas: Color,
    val card: Color,
    val cardStrong: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val iconColors: List<Color>
) {
    companion object {
        fun forTheme(isDark: Boolean): HomeColors {
            return if (isDark) {
                HomeColors(
                    canvas = Color(0xFF101114),
                    card = Color(0xFF1A1B20),
                    cardStrong = Color(0xFF25212F),
                    ink = Color(0xFFF8F7FA),
                    muted = Color(0xFFAAA7B2),
                    accent = Color(0xFFFF725F),
                    accentSoft = Color(0xFF3A2527),
                    iconColors = listOf(Color(0xFFFF725F), Color(0xFFAD91FF), Color(0xFF52D3B2), Color(0xFFFFC968))
                )
            } else {
                HomeColors(
                    canvas = Color(0xFFF5F3EF),
                    card = Color(0xFFFFFEFC),
                    cardStrong = Color(0xFF211E2D),
                    ink = Color(0xFF211F28),
                    muted = Color(0xFF777481),
                    accent = Color(0xFFFF624F),
                    accentSoft = Color(0xFFFFE4DE),
                    iconColors = listOf(Color(0xFFFF624F), Color(0xFF7256E8), Color(0xFF118F7C), Color(0xFFC27B10))
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeHero(
    colors: HomeColors,
    onOpenPdf: () -> Unit
) {
    Card(
        onClick = onOpenPdf,
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(30.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardStrong),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            colors.cardStrong,
                            Color(0xFF312849),
                            Color(0xFF5C3E58)
                        )
                    )
                )
                .padding(24.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 34.dp, y = (-50).dp)
                    .size(176.dp)
                    .clip(RoundedCornerShape(100))
                    .background(Color.White.copy(alpha = 0.06f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 58.dp, y = 64.dp)
                    .size(128.dp)
                    .clip(RoundedCornerShape(100))
                    .background(colors.accent.copy(alpha = 0.14f))
            )

            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.10f)
                ) {
                    Text(
                        text = "PAPERLY  /  PRIVATE BY DEFAULT",
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.1.sp,
                        color = Color.White.copy(alpha = 0.82f)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.home_hero_title),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        lineHeight = 39.sp,
                        letterSpacing = (-0.7).sp
                    )
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = onOpenPdf,
                        shape = RoundedCornerShape(15.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.accent,
                            contentColor = Color.White
                        ),
                        contentPadding = PaddingValues(horizontal = 15.dp, vertical = 11.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.fab_open_pdf),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        modifier = Modifier.padding(end = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF66E0B2))
                        )
                        Text(
                            text = "ON-DEVICE",
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            color = Color.White.copy(alpha = 0.76f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    colors: HomeColors
) {
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.ink.copy(alpha = 0.08f)),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 15.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = "Search tools",
                tint = colors.accent,
                modifier = Modifier.size(21.dp)
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 11.dp, vertical = 16.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = colors.ink,
                    fontWeight = FontWeight.Medium
                ),
                decorationBox = { innerTextField ->
                    if (query.isBlank()) {
                        Text(
                            text = "Search tools by name or task",
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.muted
                        )
                    }
                    innerTextField()
                }
            )
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Clear search",
                        tint = colors.muted
                    )
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(9.dp),
                    color = colors.canvas
                ) {
                    Text(
                        text = "⌘ K",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = colors.muted
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeading(
    eyebrow: String,
    title: String,
    count: Int? = null,
    colors: HomeColors
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = eyebrow,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = colors.accent
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
        }

        if (count != null) {
            Surface(
                shape = RoundedCornerShape(50),
                color = colors.card,
                border = BorderStroke(1.dp, colors.ink.copy(alpha = 0.08f))
            ) {
                Text(
                    text = count.toString().padStart(2, '0'),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = colors.muted
                )
            }
        }
    }
}

@Composable
private fun ToolFilterRail(
    selectedSection: ToolSection?,
    onSectionSelected: (ToolSection?) -> Unit,
    colors: HomeColors
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 4.dp)
    ) {
        item {
            ToolFilterChip(
                label = stringResource(R.string.tools_all),
                selected = selectedSection == null,
                color = colors.accent,
                onClick = { onSectionSelected(null) }
            )
        }
        items(ToolSection.entries.size) { index ->
            val section = ToolSection.entries[index]
            ToolFilterChip(
                label = getSectionTitle(section),
                selected = selectedSection == section,
                color = colors.iconColors[section.ordinal % colors.iconColors.size],
                onClick = { onSectionSelected(section) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolFilterChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) color else color.copy(alpha = 0.10f),
        contentColor = if (selected) Color.White else color,
        border = if (selected) null else BorderStroke(1.dp, color.copy(alpha = 0.20f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Color.White else color)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LaunchRail(
    tools: List<ToolItem>,
    colors: HomeColors,
    onToolClick: (ToolItem) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
        HomeSectionHeading(
            eyebrow = stringResource(R.string.category_quick_actions).uppercase(),
            title = stringResource(R.string.home_quick_actions_title),
            count = tools.size,
            colors = colors
        )
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(end = 4.dp)
        ) {
            items(tools.size) { index ->
                val tool = tools[index]
                val accent = colors.iconColors[index % colors.iconColors.size]
                Card(
                    onClick = { onToolClick(tool) },
                    modifier = Modifier
                        .width(184.dp)
                        .height(148.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = accent),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Surface(
                                shape = RoundedCornerShape(13.dp),
                                color = Color.White.copy(alpha = 0.18f),
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = tool.icon,
                                    contentDescription = tool.getTitle(),
                                    tint = Color.White,
                                    modifier = Modifier.padding(9.dp)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowOutward,
                                contentDescription = null,
                                tint = Color.White.copy(alpha = 0.72f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = tool.getTitle(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "Tap to get started",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.74f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyToolSearch(
    query: String,
    colors: HomeColors
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = colors.card,
        border = BorderStroke(1.dp, colors.ink.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Icon(
                imageVector = Icons.Default.SearchOff,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(32.dp)
            )
            Text(
                text = "Nothing matched “$query”",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.ink,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Try a different word, like merge, scan, or image.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.muted,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Get localized title for a ToolSection.
 */
@Composable
private fun getSectionTitle(section: ToolSection): String {
    return when (section) {
        ToolSection.QUICK_ACTIONS -> stringResource(R.string.category_quick_actions)
        ToolSection.ORGANIZE -> stringResource(R.string.category_organize)
        ToolSection.CONVERT -> stringResource(R.string.category_convert)
        ToolSection.SECURITY -> stringResource(R.string.category_security)
        ToolSection.IMAGE_TOOLS -> stringResource(R.string.category_image_tools)
        ToolSection.VIEW_EXPORT -> stringResource(R.string.category_view_export)
    }
}

@Composable
private fun ToolGrid(
    tools: List<ToolItem>,
    colors: HomeColors,
    onToolClick: (ToolItem) -> Unit
) {
    val rows = tools.chunked(2)

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rows.forEachIndexed { rowIndex, rowTools ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowTools.forEach { tool ->
                    ToolCard(
                        tool = tool,
                        colors = colors,
                        onClick = { onToolClick(tool) },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(2 - rowTools.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolCard(
    tool: ToolItem,
    colors: HomeColors,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.height(152.dp),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.card
        ),
        border = BorderStroke(
            width = 1.dp,
            color = colors.iconColors[tool.section.ordinal % colors.iconColors.size].copy(alpha = 0.15f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        val accent = colors.iconColors[tool.section.ordinal % colors.iconColors.size]

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(15.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accent.copy(alpha = 0.14f),
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = tool.getTitle(),
                        tint = accent,
                        modifier = Modifier
                            .padding(9.dp)
                            .size(24.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Default.ArrowOutward,
                    contentDescription = null,
                    tint = colors.muted.copy(alpha = 0.58f),
                    modifier = Modifier.size(17.dp)
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = tool.getTitle(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Start,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.ink
                )
                Text(
                    text = tool.getDescription(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = colors.muted,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

/**
 * Get all tools organized by section.
 * Total: 25+ tools
 */
fun getAllTools(): List<ToolItem> = listOf(
    // SECTION 1: QUICK ACTIONS (Top, Always Visible)
    ToolItem(
        id = "merge",
        titleResId = R.string.tool_merge_pdf,
        descResId = R.string.desc_merge_pdfs,
        icon = Icons.Default.MergeType,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Merge
    ),
    ToolItem(
        id = "split",
        titleResId = R.string.tool_split_pdf,
        descResId = R.string.desc_split_pdf,
        icon = Icons.Default.CallSplit,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Split
    ),
    ToolItem(
        id = "compress",
        titleResId = R.string.tool_compress_pdf,
        descResId = R.string.desc_compress_pdf,
        icon = Icons.Default.Compress,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Compress
    ),
    ToolItem(
        id = "pdf_to_image",
        titleResId = R.string.tool_pdf_to_images,
        descResId = R.string.desc_pdf_to_images,
        icon = Icons.Default.PhotoLibrary,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.PdfToImage
    ),
    ToolItem(
        id = "image_to_pdf",
        titleResId = R.string.tool_images_to_pdf,
        descResId = R.string.desc_images_to_pdf,
        icon = Icons.Default.Image,
        section = ToolSection.QUICK_ACTIONS,
        screen = Screen.Convert
    ),

    // SECTION 2: ORGANIZE
    ToolItem(
        id = "reorder",
        titleResId = R.string.tool_reorder_pages,
        descResId = R.string.desc_reorder_pages,
        icon = Icons.Default.SwapVert,
        section = ToolSection.ORGANIZE,
        screen = Screen.Reorder
    ),
    ToolItem(
        id = "rotate",
        titleResId = R.string.tool_rotate_pages,
        descResId = R.string.desc_rotate_pages,
        icon = Icons.Default.RotateRight,
        section = ToolSection.ORGANIZE,
        screen = Screen.Rotate
    ),
    ToolItem(
        id = "delete_pages",
        titleResId = R.string.tool_delete_pages,
        descResId = R.string.desc_delete_pages,
        icon = Icons.Default.Delete,
        section = ToolSection.ORGANIZE,
        screen = Screen.Organize
    ),
    ToolItem(
        id = "extract",
        titleResId = R.string.tool_extract_pages,
        descResId = R.string.desc_extract_pages,
        icon = Icons.Default.ContentCopy,
        section = ToolSection.ORGANIZE,
        screen = Screen.Extract
    ),

    // SECTION 3: CONVERT (PDF-CENTRIC)
    ToolItem(
        id = "html_to_pdf",
        titleResId = R.string.tool_html_to_pdf,
        descResId = R.string.desc_html_to_pdf,
        icon = Icons.Default.Language,
        section = ToolSection.CONVERT,
        screen = Screen.HtmlToPdf
    ),
    ToolItem(
        id = "scan_to_pdf",
        titleResId = R.string.tool_scan_to_pdf,
        descResId = R.string.desc_scan_to_pdf,
        icon = Icons.Default.CameraAlt,
        section = ToolSection.CONVERT,
        screen = Screen.ScanToPdf
    ),
    ToolItem(
        id = "ocr",
        titleResId = R.string.tool_ocr,
        descResId = R.string.desc_ocr,
        icon = Icons.Default.DocumentScanner,
        section = ToolSection.CONVERT,
        screen = Screen.Ocr
    ),
    ToolItem(
        id = "extract_text",
        titleResId = R.string.tool_extract_text,
        descResId = R.string.desc_extract_text,
        icon = Icons.Default.TextFields,
        section = ToolSection.CONVERT,
        screen = Screen.ExtractText
    ),

    // SECTION 4: SECURITY
    ToolItem(
        id = "lock",
        titleResId = R.string.tool_lock_pdf,
        descResId = R.string.desc_lock_pdf,
        icon = Icons.Default.Lock,
        section = ToolSection.SECURITY,
        screen = Screen.Security
    ),
    ToolItem(
        id = "unlock",
        titleResId = R.string.tool_unlock_pdf,
        descResId = R.string.desc_unlock_pdf,
        icon = Icons.Default.LockOpen,
        section = ToolSection.SECURITY,
        screen = Screen.Unlock
    ),
    ToolItem(
        id = "watermark",
        titleResId = R.string.tool_add_watermark,
        descResId = R.string.desc_add_watermark,
        icon = Icons.Default.WaterDrop,
        section = ToolSection.SECURITY,
        screen = Screen.Watermark
    ),
    ToolItem(
        id = "sign",
        titleResId = R.string.tool_sign_pdf,
        descResId = R.string.desc_sign,
        icon = Icons.Default.Draw,
        section = ToolSection.SECURITY,
        screen = Screen.SignPdf
    ),
    ToolItem(
        id = "fill_forms",
        titleResId = R.string.tool_fill_forms,
        descResId = R.string.desc_fill_forms,
        icon = Icons.Default.EditNote,
        section = ToolSection.SECURITY,
        screen = Screen.FillForms
    ),
    ToolItem(
        id = "flatten",
        titleResId = R.string.tool_flatten_pdf,
        descResId = R.string.desc_flatten_pdf,
        icon = Icons.Default.Layers,
        section = ToolSection.SECURITY,
        screen = Screen.Flatten
    ),

    // SECTION 5: IMAGE TOOLS (LOW-BLOAT ONLY)
    ToolItem(
        id = "image_compress",
        titleResId = R.string.tool_image_compress,
        descResId = R.string.desc_compress_image,
        icon = Icons.Default.Compress,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_resize",
        titleResId = R.string.tool_image_resize,
        descResId = R.string.desc_resize_image,
        icon = Icons.Default.AspectRatio,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_convert",
        titleResId = R.string.tool_image_convert,
        descResId = R.string.desc_convert_format,
        icon = Icons.Default.Transform,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),
    ToolItem(
        id = "image_metadata",
        titleResId = R.string.tool_image_metadata,
        descResId = R.string.desc_strip_metadata,
        icon = Icons.Default.DeleteSweep,
        section = ToolSection.IMAGE_TOOLS,
        screen = Screen.ImageTools
    ),

    // SECTION 6: VIEW & EXPORT
    ToolItem(
        id = "view_pdf",
        titleResId = R.string.tool_view_pdf,
        descResId = R.string.desc_view_pdf,
        icon = Icons.Default.PictureAsPdf,
        section = ToolSection.VIEW_EXPORT,
        screen = Screen.Home // Special handling
    ),
    ToolItem(
        id = "page_numbers",
        titleResId = R.string.tool_page_numbers,
        descResId = R.string.desc_page_numbers,
        icon = Icons.Default.FormatListNumbered,
        section = ToolSection.VIEW_EXPORT,
        screen = Screen.PageNumber
    ),
    ToolItem(
        id = "metadata",
        titleResId = R.string.tool_view_metadata,
        descResId = R.string.desc_view_metadata,
        icon = Icons.Default.Info,
        section = ToolSection.VIEW_EXPORT,
        screen = Screen.Metadata
    )
)
