package com.anonymous.imgpdf.ui.screens
import com.anonymous.imgpdf.util.safeLaunch

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonymous.imgpdf.data.FileManager
import com.anonymous.imgpdf.data.HistoryManager
import com.anonymous.imgpdf.data.OperationType
import com.anonymous.imgpdf.data.PdfFileInfo
import com.anonymous.imgpdf.domain.operations.PdfOrganizer
import com.anonymous.imgpdf.ui.components.*
import com.anonymous.imgpdf.util.FileOpener
import com.anonymous.imgpdf.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data class representing a page that can be reordered.
 */
data class ReorderablePage(
    val originalIndex: Int,  // 1-based original page number
    val thumbnail: Bitmap?
)

/**
 * Screen for reordering PDF pages with visual previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val organizer = remember { PdfOrganizer() }

    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var pages by remember { mutableStateOf<List<ReorderablePage>>(emptyList()) }
    var isLoadingThumbnails by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }
    var selectedPageIndex by remember { mutableStateOf<Int?>(null) }

    // Check if order has changed
    val hasOrderChanged = remember(pages) {
        pages.mapIndexed { index, page -> page.originalIndex != index + 1 }.any { it }
    }

    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFile = FileManager.getFileInfo(context, uri)
            pages = emptyList()
            selectedPageIndex = null

            // Load page thumbnails
            scope.launch {
                isLoadingThumbnails = true
                val pageCount = organizer.getPageCount(context, uri)

                // Create placeholder pages first
                pages = (1..pageCount).map { ReorderablePage(it, null) }

                // Load thumbnails in background
                withContext(Dispatchers.IO) {
                    try {
                        organizer.getPageThumbnails(
                            context = context,
                            uri = uri,
                            width = 150,
                            height = 200
                        ) { pageNum, bitmap ->
                            // Update the specific page with its thumbnail
                            pages = pages.map { page ->
                                if (page.originalIndex == pageNum) {
                                    page.copy(thumbnail = bitmap)
                                } else {
                                    page
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Continue without thumbnails
                    }
                }
                isLoadingThumbnails = false
            }
        }
    }

    // Save file launcher (for custom location)
    val saveFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { saveUri ->
            val file = selectedFile ?: return@let

            scope.launch {
                isProcessing = true
                progress = 0f

                context.contentResolver.openOutputStream(saveUri)?.use { outputStream ->
                    val newOrder = pages.map { it.originalIndex }

                    val result = organizer.reorderPages(
                        context = context,
                        inputUri = file.uri,
                        outputStream = outputStream,
                        newOrder = newOrder,
                        onProgress = { progress = it }
                    )

                    result.fold(
                        onSuccess = { organizeResult ->
                            resultSuccess = true
                            resultUri = saveUri
                            resultMessage = "Successfully reordered ${organizeResult.resultPageCount} pages."

                            HistoryManager.recordSuccess(
                                context = context,
                                operationType = OperationType.REORDER,
                                inputFileName = file.name,
                                outputFileUri = saveUri,
                                outputFileName = "reordered_${file.name}",
                                details = "Reordered ${organizeResult.resultPageCount} pages"
                            )

                            selectedFile = null
                            pages = emptyList()
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message ?: "Reorder failed"

                            HistoryManager.recordFailure(
                                context = context,
                                operationType = OperationType.REORDER,
                                inputFileName = file.name,
                                errorMessage = error.message
                            )
                        }
                    )
                } ?: run {
                    resultSuccess = false
                    resultMessage = "Cannot create output file"
                }

                isProcessing = false
                showResult = true
            }
        }
    }

    // Function to reorder with default location
    fun reorderWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val file = selectedFile!!

            val result = withContext(Dispatchers.IO) {
                try {
                    val baseName = file.name.removeSuffix(".pdf")
                    val fileName = "${baseName}_reordered.pdf"
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val newOrder = pages.map { it.originalIndex }

                        val organizeResult = organizer.reorderPages(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputResult.outputStream,
                            newOrder = newOrder,
                            onProgress = { progress = it }
                        )

                        outputResult.outputStream.close()

                        organizeResult.fold(
                            onSuccess = { oResult ->
                                Triple(
                                    true,
                                    "Successfully reordered ${oResult.resultPageCount} pages.\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}",
                                    outputResult.outputFile.contentUri
                                )
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Reorder failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Reorder failed", null)
                }
            }

            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third

            if (resultSuccess && result.third != null) {
                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.REORDER,
                    inputFileName = file.name,
                    outputFileUri = result.third,
                    outputFileName = "reordered_${file.name}",
                    details = "Reordered ${pages.size} pages"
                )
                selectedFile = null
                pages = emptyList()
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.REORDER,
                    inputFileName = file.name,
                    errorMessage = result.second
                )
            }

            isProcessing = false
            showResult = true
        }
    }

    // Move page functions
    fun movePageUp(index: Int) {
        if (index > 0) {
            val mutableList = pages.toMutableList()
            val temp = mutableList[index]
            mutableList[index] = mutableList[index - 1]
            mutableList[index - 1] = temp
            pages = mutableList
            selectedPageIndex = index - 1
        }
    }

    fun movePageDown(index: Int) {
        if (index < pages.size - 1) {
            val mutableList = pages.toMutableList()
            val temp = mutableList[index]
            mutableList[index] = mutableList[index + 1]
            mutableList[index + 1] = temp
            pages = mutableList
            selectedPageIndex = index + 1
        }
    }

    fun moveToFirst(index: Int) {
        if (index > 0) {
            val mutableList = pages.toMutableList()
            val page = mutableList.removeAt(index)
            mutableList.add(0, page)
            pages = mutableList
            selectedPageIndex = 0
        }
    }

    fun moveToLast(index: Int) {
        if (index < pages.size - 1) {
            val mutableList = pages.toMutableList()
            val page = mutableList.removeAt(index)
            mutableList.add(page)
            pages = mutableList
            selectedPageIndex = pages.size - 1
        }
    }

    fun resetOrder() {
        pages = pages.sortedBy { it.originalIndex }
        selectedPageIndex = null
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            ToolTopBar(
                title = "Reorder Pages",
                onNavigateBack = onNavigateBack
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Content area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (selectedFile == null) {
                    EmptyState(
                        icon = Icons.Default.SwapVert,
                        title = "No PDF Selected",
                        subtitle = "Select a PDF to rearrange its pages",
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else if (isLoadingThumbnails && pages.all { it.thumbnail == null }) {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Loading page previews...")
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 18.dp, end = 18.dp, bottom = 8.dp)
                    ) {
                        // Selected file info
                        FileItemCard(
                            fileName = selectedFile!!.name,
                            fileSize = "${pages.size} pages • ${selectedFile!!.formattedSize}",
                            onRemove = {
                                selectedFile = null
                                pages = emptyList()
                                selectedPageIndex = null
                            }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Instructions and reset
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = if (hasOrderChanged) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = if (hasOrderChanged) {
                                            Icons.Default.AutoAwesome
                                        } else {
                                            Icons.Default.TouchApp
                                        },
                                        contentDescription = null,
                                        tint = if (hasOrderChanged) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = if (hasOrderChanged) {
                                                "New order ready"
                                            } else {
                                                "Tap a page to move it"
                                            },
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (hasOrderChanged) {
                                                "Your changes are unsaved"
                                            } else {
                                                "Select one page, then use the controls below"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                if (hasOrderChanged) {
                                    TextButton(onClick = { resetOrder() }) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = null,
                                            modifier = Modifier.size(17.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Reset")
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Page grid with thumbnails
                        PdfThumbnailGrid(
                            uri = selectedFile!!.uri,
                            pageCount = pages.size,
                            displayPages = pages.map { it.originalIndex },
                            selectedPages = selectedPageIndex?.let { setOf(it) } ?: emptySet(),
                            onPageSelected = { index ->
                                selectedPageIndex = if (selectedPageIndex == index) null else index
                            },
                            multiSelect = false,
                            topLeftBadge = { pageNum, index ->
                                val hasChanged = pageNum != index + 1
                                Box(
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .background(
                                            if (hasChanged) MaterialTheme.colorScheme.tertiary else Color.Black.copy(alpha = 0.6f),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (hasChanged) "${index + 1} (was $pageNum)" else "${index + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (hasChanged) MaterialTheme.colorScheme.onTertiary else Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        // Action buttons for reordering
                        if (selectedPageIndex != null) {
                            val index = selectedPageIndex!!
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                shape = RoundedCornerShape(20.dp),
                                color = MaterialTheme.colorScheme.primaryContainer
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "MOVE SELECTED PAGE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 1.2.sp,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                        Text(
                                            text = "PAGE ${index + 1} OF ${pages.size}",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceEvenly
                                    ) {
                                        ReorderControl(
                                            icon = Icons.Default.KeyboardDoubleArrowLeft,
                                            label = "First",
                                            enabled = index > 0,
                                            onClick = { moveToFirst(index) }
                                        )
                                        ReorderControl(
                                            icon = Icons.Default.KeyboardArrowLeft,
                                            label = "Previous",
                                            enabled = index > 0,
                                            onClick = { movePageUp(index) }
                                        )
                                        ReorderControl(
                                            icon = Icons.Default.KeyboardArrowRight,
                                            label = "Next",
                                            enabled = index < pages.size - 1,
                                            onClick = { movePageDown(index) }
                                        )
                                        ReorderControl(
                                            icon = Icons.Default.KeyboardDoubleArrowRight,
                                            label = "Last",
                                            enabled = index < pages.size - 1,
                                            onClick = { moveToLast(index) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Progress overlay
                if (isProcessing) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp)
                            .align(Alignment.Center)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            OperationProgress(
                                progress = progress,
                                message = "Reordering pages..."
                            )
                        }
                    }
                }
            }

            // Bottom action area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 5.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    if (selectedFile == null) {
                        ActionButton(
                            text = "Select PDF",
                            onClick = {
                                pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                            },
                            icon = Icons.Default.FolderOpen,
                        )
                    } else {
                        // Save location option
                        SaveLocationSelector(
                            useCustomLocation = useCustomLocation,
                            onUseCustomLocationChange = { useCustomLocation = it }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ActionButton(
                            text = "Save Reordered PDF",
                            onClick = {
                                if (useCustomLocation) {
                                    val baseName = selectedFile!!.name.removeSuffix(".pdf")
                                    saveFileLauncher.safeLaunch("${baseName}_reordered.pdf", context)
                                } else {
                                    reorderWithDefaultLocation()
                                }
                            },
                            enabled = hasOrderChanged && !isProcessing,
                            isLoading = isProcessing,
                            icon = Icons.Default.Save
                        )
                    }
                }
            }
        }
    }

    // Result dialog
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) "Reorder Complete" else "Reorder Failed",
            message = resultMessage,
            onDismiss = {
                showResult = false
                resultUri = null
            },
            onAction = resultUri?.let { uri ->
                { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
            },
            actionText = "Open PDF"
        )
    }
}

@Composable
private fun ReorderControl(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(66.dp)
    ) {
        IconButton(
            onClick = onClick,
            enabled = enabled
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.35f)
                },
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(
                alpha = if (enabled) 0.82f else 0.35f
            ),
            maxLines = 1
        )
    }
}

/**
 * Card showing a page preview with reorder controls.
 */
