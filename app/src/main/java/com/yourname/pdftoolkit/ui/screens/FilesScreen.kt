package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.PersistedFile
import com.yourname.pdftoolkit.data.SafUriManager
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

/**
 * File type filter options.
 */
enum class FileFilter(val title: String, val icon: ImageVector) {
    ALL("All", Icons.Default.Folder),
    PDF("PDF", Icons.Default.PictureAsPdf)
}

private data class FilesColors(
    val canvas: Color,
    val card: Color,
    val cardStrong: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val pdf: Color,
    val image: Color,
    val filterSelected: Color
) {
    companion object {
        fun forTheme(isDark: Boolean): FilesColors {
            return if (isDark) {
                FilesColors(
                    canvas = Color(0xFF111116),
                    card = Color(0xFF1A1A21),
                    cardStrong = Color(0xFF24242D),
                    ink = Color(0xFFF5F3F7),
                    muted = Color(0xFFB4AFBC),
                    accent = Color(0xFFFF826D),
                    pdf = Color(0xFFFF826D),
                    image = Color(0xFF62D6C1),
                    filterSelected = Color(0xFF3A2425)
                )
            } else {
                FilesColors(
                    canvas = Color(0xFFF8F7FB),
                    card = Color(0xFFFFFFFF),
                    cardStrong = Color(0xFF1E1D29),
                    ink = Color(0xFF1E1D29),
                    muted = Color(0xFF777481),
                    accent = Color(0xFFFF6B57),
                    pdf = Color(0xFFFF6B57),
                    image = Color(0xFF159A87),
                    filterSelected = Color(0xFFFFE6DF)
                )
            }
        }
    }
}

/**
 * Files Screen - Content management tab.
 * Purpose: File access, NOT tools.
 *
 * Features:
 * - Recent files list (PDF, Images)
 * - Open Document button using SAF (ACTION_OPEN_DOCUMENT)
 * - System file picker with persistent URI permissions
 * - Simple filters (PDF / Image)
 *
 * IMPORTANT: This screen uses SafUriManager for proper SAF compliance.
 * All files are stored as URI strings, NOT file paths.
 * This ensures proper scoped storage compliance on Android 10+.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(
    onOpenPdfViewer: (Uri, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    val filesColors = remember(isDarkTheme) { FilesColors.forTheme(isDarkTheme) }

    var selectedFilter by remember { mutableStateOf(FileFilter.ALL) }
    var recentFiles by remember { mutableStateOf<List<PersistedFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var showClearHistoryDialog by remember { mutableStateOf(false) }

    // Supported MIME types for document picker (PDF only)
    val pdfMimeTypes = arrayOf("application/pdf")

    /**
     * Copy content URI to app cache for reliable access.
     * This is critical for in-app picker URIs that lose permission quickly.
     */
    suspend fun copyUriToCache(context: Context, uri: Uri): android.net.Uri? = withContext(kotlinx.coroutines.Dispatchers.IO) {
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
            android.net.Uri.fromFile(tempFile)
        } catch (e: Exception) {
            android.util.Log.e("FilesScreen", "Failed to copy URI to cache", e)
            null
        }
    }

    /**
     * Document picker using SAF (ACTION_OPEN_DOCUMENT).
     * Immediately copies picked file to cache before opening to avoid permission issues.
     */
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { selectedUri ->
            scope.launch {
                // Take persistable URI permission immediately
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                val persistedFile = SafUriManager.addRecentFile(context, selectedUri, flags)

                if (persistedFile != null) {
                    // Update local list immediately
                    recentFiles = SafUriManager.loadRecentFiles(context)

                    // Open PDF files - copy to cache first for reliable access
                    if (persistedFile.mimeType == "application/pdf") {
                        // CRITICAL: Copy to cache before opening to avoid permission expiration
                        val cachedUri = copyUriToCache(context, selectedUri)
                        if (cachedUri != null) {
                            onOpenPdfViewer(cachedUri, persistedFile.name.substringBeforeLast('.'))
                        } else {
                            // Fallback to direct URI if copy fails (may fail on some devices)
                            onOpenPdfViewer(selectedUri, persistedFile.name.substringBeforeLast('.'))
                        }
                    }
                } else {
                    // Fallback: try to open anyway, may fail if no permission
                    val mimeType = context.contentResolver.getType(selectedUri)
                    val name = getFileName(context, selectedUri)

                    if (mimeType == "application/pdf") {
                        val cachedUri = copyUriToCache(context, selectedUri)
                        if (cachedUri != null) {
                            onOpenPdfViewer(cachedUri, name)
                        } else {
                            onOpenPdfViewer(selectedUri, name)
                        }
                    }
                }
            }
        }
    }

    // Load recent files from SafUriManager
    LaunchedEffect(Unit) {
        isLoading = true
        recentFiles = SafUriManager.loadRecentFiles(context)
        isLoading = false
    }

    // Filter files based on selection
    val filteredFiles = remember(recentFiles, selectedFilter) {
        when (selectedFilter) {
            FileFilter.ALL -> recentFiles
            FileFilter.PDF -> recentFiles.filter { it.mimeType == "application/pdf" }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(filesColors.canvas)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.files_header_eyebrow),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    color = filesColors.accent
                )
                Text(
                    text = stringResource(R.string.files_header_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = filesColors.ink
                )
                Text(
                    text = stringResource(R.string.files_header_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = filesColors.muted
                )
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = filesColors.cardStrong),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                onClick = {
                    documentPickerLauncher.safeLaunch(pdfMimeTypes, context)
                }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(filesColors.cardStrong, Color(0xFF3D315E))
                            )
                        )
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = filesColors.accent.copy(alpha = 0.18f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = null,
                            tint = filesColors.accent,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.files_open_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = stringResource(R.string.files_open_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.68f)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = filesColors.accent
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(9.dp).size(20.dp)
                        )
                    }
                }
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(FileFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedFilter == filter,
                        onClick = { selectedFilter = filter },
                        label = {
                            Text(
                                text = filter.title,
                                fontWeight = if (selectedFilter == filter) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = filter.icon,
                                contentDescription = null,
                                modifier = Modifier.size(17.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = filesColors.card,
                            labelColor = filesColors.muted,
                            iconColor = filesColors.muted,
                            selectedContainerColor = filesColors.filterSelected,
                            selectedLabelColor = filesColors.accent,
                            selectedLeadingIconColor = filesColors.accent
                        ),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (selectedFilter == filter) {
                                filesColors.accent.copy(alpha = 0.28f)
                            } else {
                                filesColors.muted.copy(alpha = 0.18f)
                            }
                        )
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = stringResource(R.string.files_recent_eyebrow),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.4.sp,
                        color = filesColors.accent
                    )
                    Text(
                        text = stringResource(R.string.files_recent_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = filesColors.ink
                    )
                }
                if (recentFiles.isNotEmpty()) {
                    IconButton(onClick = { showClearHistoryDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.DeleteSweep,
                            contentDescription = stringResource(R.string.history_clear_all),
                            tint = filesColors.muted
                        )
                    }
                }
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = filesColors.accent)
                }
            } else if (filteredFiles.isEmpty()) {
                EmptyFilesState(
                    colors = filesColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredFiles, key = { it.uriString }) { file ->
                        RecentFileItem(
                            file = file,
                            colors = filesColors,
                            onClick = {
                                scope.launch {
                                    val uri = file.toUri()
                                    if (uri != null) {
                                        SafUriManager.updateLastAccessed(context, file.uriString)
                                        val displayName = file.name.substringBeforeLast('.')
                                        if (file.mimeType == "application/pdf") {
                                            val cachedUri = copyUriToCache(context, uri)
                                            if (cachedUri != null) {
                                                onOpenPdfViewer(cachedUri, displayName)
                                            } else {
                                                onOpenPdfViewer(uri, displayName)
                                            }
                                        }
                                    }
                                }
                            }
                        )
                    }
                    item {
                        Spacer(modifier = Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    // Clear History Dialog
    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { showClearHistoryDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.files_clear_dialog_title)) },
            text = {
                Text(stringResource(R.string.files_clear_dialog_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            SafUriManager.clearAllRecentFiles(context)
                            recentFiles = emptyList()
                            showClearHistoryDialog = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearHistoryDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
private fun LazyItemScope.FilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    leadingIcon: @Composable () -> Unit,
    colors: SelectableChipColors,
    border: BorderStroke
) {
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyFilesState(
    colors: FilesColors,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, colors.muted.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = colors.accent.copy(alpha = 0.12f),
                modifier = Modifier.size(68.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.padding(19.dp),
                    tint = colors.accent
                )
            }
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.files_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colors.ink
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.files_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.muted
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentFileItem(
    file: PersistedFile,
    colors: FilesColors,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = colors.card
        ),
        border = BorderStroke(1.dp, colors.muted.copy(alpha = 0.12f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // File type icon
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = getFileIconColor(file.mimeType, colors).copy(alpha = 0.14f),
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = getFileIcon(file.mimeType),
                    contentDescription = null,
                    tint = getFileIconColor(file.mimeType, colors),
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.name.substringBeforeLast('.'),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = FileManager.formatFileSize(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted.copy(alpha = 0.6f)
                    )
                    Text(
                        text = formatDate(file.lastAccessed),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.muted
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = colors.muted
            )
        }
    }
}

@Composable
private fun getFileIconColor(mimeType: String, colors: FilesColors): Color {
    return when {
        mimeType == "application/pdf" -> colors.pdf
        mimeType.startsWith("image/") -> colors.image
        mimeType.contains("word") || mimeType.contains("document") ->
            colors.accent
        mimeType.contains("excel") || mimeType.contains("spreadsheet") ->
            colors.image
        mimeType.contains("powerpoint") || mimeType.contains("presentation") ->
            colors.accent
        else -> colors.muted
    }
}

private fun getFileIcon(mimeType: String): ImageVector {
    return when {
        mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
        mimeType.startsWith("image/") -> Icons.Default.Photo
        mimeType.contains("word") || mimeType.contains("document") -> Icons.Default.Description
        mimeType.contains("excel") || mimeType.contains("spreadsheet") -> Icons.Default.TableView
        mimeType.contains("powerpoint") || mimeType.contains("presentation") -> Icons.Default.Slideshow
        else -> Icons.Default.InsertDriveFile
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun getFileName(context: Context, uri: Uri): String {
    var name = "Document"
    try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex)?.substringBeforeLast('.') ?: name
                }
            }
        }
    } catch (e: Exception) {
        name = uri.lastPathSegment ?: "Document"
    }
    return name
}
