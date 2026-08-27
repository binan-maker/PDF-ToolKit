package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
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
import com.yourname.pdftoolkit.BuildConfig
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.ui.navigation.Screen
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

    val allTools = getAllTools()
    val isDarkTheme = isSystemInDarkTheme()
    val homeColors = remember(isDarkTheme) { HomeColors.forTheme(isDarkTheme) }

    fun openTool(tool: ToolItem) {
        if (tool.screen == Screen.Home && tool.id == "view_pdf") {
            pdfPickerLauncher.safeLaunch(arrayOf("application/pdf"), context)
        } else {
            val imageToolIds = listOf("image_compress", "image_resize", "image_convert", "image_metadata")
            if (imageToolIds.contains(tool.id) && onNavigateToRoute != null) {
                onNavigateToRoute(Screen.getRouteForToolId(tool.id))
            } else {
                onNavigateToScreen(tool.screen)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(homeColors.canvas)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 32.dp)
        ) {
            item {
                HomeHero(
                    colors = homeColors,
                    onOpenPdf = {
                        pdfPickerLauncher.safeLaunch(arrayOf("application/pdf"), context)
                    }
                )
            }

            item {
                HomeSectionHeading(
                    eyebrow = stringResource(R.string.category_quick_actions).uppercase(),
                    title = stringResource(R.string.home_quick_actions_title),
                    colors = homeColors
                )
            }

            item {
                ToolGrid(
                    tools = allTools.filter { it.section == ToolSection.QUICK_ACTIONS },
                    colors = homeColors,
                    onToolClick = ::openTool
                )
            }

            ToolSection.entries
                .filter { it != ToolSection.QUICK_ACTIONS }
                .forEach { section ->
                    val sectionTools = allTools.filter { it.section == section }
                    if (sectionTools.isNotEmpty()) {
                        item {
                            HomeSectionHeading(
                                eyebrow = getSectionTitle(section).uppercase(),
                                title = getSectionTitle(section),
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
                    canvas = Color(0xFF111116),
                    card = Color(0xFF1A1A21),
                    cardStrong = Color(0xFF24242D),
                    ink = Color(0xFFF5F3F7),
                    muted = Color(0xFFB4AFBC),
                    accent = Color(0xFFFF826D),
                    accentSoft = Color(0xFF3A2425),
                    iconColors = listOf(Color(0xFFFF826D), Color(0xFFB69CFF), Color(0xFF62D6C1), Color(0xFFFFC46B))
                )
            } else {
                HomeColors(
                    canvas = Color(0xFFF8F7FB),
                    card = Color(0xFFFFFFFF),
                    cardStrong = Color(0xFF1E1D29),
                    ink = Color(0xFF1E1D29),
                    muted = Color(0xFF777481),
                    accent = Color(0xFFFF6B57),
                    accentSoft = Color(0xFFFFE6DF),
                    iconColors = listOf(Color(0xFFFF6B57), Color(0xFF7658E8), Color(0xFF159A87), Color(0xFFCA7B00))
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
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardStrong),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(colors.cardStrong, colors.cardStrong.copy(alpha = 0.86f), Color(0xFF3D315E))
                    )
                )
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = colors.accent.copy(alpha = 0.18f)
                ) {
                    Text(
                        text = stringResource(R.string.home_hero_eyebrow),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing,
                        color = colors.accent
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.home_hero_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        lineHeight = MaterialTheme.typography.headlineMedium.lineHeight
                    )
                    Text(
                        text = stringResource(R.string.home_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = colors.accent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.UploadFile,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(19.dp)
                        )
                        Text(
                            text = stringResource(R.string.fab_open_pdf),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeading(
    eyebrow: String,
    title: String,
    colors: HomeColors
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
    var isVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        isVisible = true
    }

    val scale by animateFloatAsState(
        targetValue = if (isVisible) 1f else 0.9f,
        animationSpec = tween(durationMillis = 200),
        label = "tool_card_scale"
    )

    Card(
        onClick = onClick,
        modifier = modifier
            .scale(scale)
            .height(124.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.card
        ),
        border = BorderStroke(
            width = 1.dp,
            color = colors.iconColors[tool.section.ordinal % colors.iconColors.size].copy(alpha = 0.18f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = colors.iconColors[tool.section.ordinal % colors.iconColors.size].copy(alpha = 0.14f),
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.getTitle(),
                    tint = colors.iconColors[tool.section.ordinal % colors.iconColors.size],
                    modifier = Modifier
                        .padding(8.dp)
                        .size(22.dp)
                )
            }

            Text(
                text = tool.getTitle(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Start,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = colors.ink
            )
        }
    }
}

/**
 * Get all tools organized by section.
 * Total: 25+ tools
 */
@Composable
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
