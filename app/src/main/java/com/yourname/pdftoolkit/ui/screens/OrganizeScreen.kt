package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import com.yourname.pdftoolkit.ui.components.PdfThumbnailGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.PdfOrganizer
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for organizing PDF pages (remove, reorder).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrganizeScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val organizer = remember { PdfOrganizer() }

    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var selectedPages by remember { mutableStateOf(setOf<Int>()) }
    var isRemoveMode by remember { mutableStateOf(true) } // true = remove, false = keep/reorder
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var useCustomLocation by remember { mutableStateOf(false) }
    var resultUri by remember { mutableStateOf<Uri?>(null) }

    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFile = FileManager.getFileInfo(context, uri)
            selectedPages = setOf()

            scope.launch {
                pageCount = organizer.getPageCount(context, uri)
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
                    val result = if (isRemoveMode) {
                        organizer.removePages(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputStream,
                            pagesToRemove = selectedPages,
                            onProgress = { progress = it }
                        )
                    } else {
                        organizer.reorderPages(
                            context = context,
                            inputUri = file.uri,
                            outputStream = outputStream,
                            newOrder = selectedPages.sorted(),
                            onProgress = { progress = it }
                        )
                    }

                    result.fold(
                        onSuccess = { organizeResult ->
                            resultSuccess = true
                            resultUri = saveUri
                            resultMessage = if (isRemoveMode) {
                                "Removed ${organizeResult.pagesRemoved} pages. Result has ${organizeResult.resultPageCount} pages."
                            } else {
                                "Extracted ${organizeResult.resultPageCount} pages."
                            }
                            selectedFile = null
                            selectedPages = setOf()
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message ?: "Operation failed"
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

    // Function to organize with default location
    fun organizeWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f

            val result = withContext(Dispatchers.IO) {
                try {
                    val file = selectedFile!!
                    val baseName = file.name.removeSuffix(".pdf")
                    val suffix = if (isRemoveMode) "_edited" else "_extracted"
                    val fileName = "${baseName}${suffix}.pdf"
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val organizeResult = if (isRemoveMode) {
                            organizer.removePages(
                                context = context,
                                inputUri = file.uri,
                                outputStream = outputResult.outputStream,
                                pagesToRemove = selectedPages,
                                onProgress = { progress = it }
                            )
                        } else {
                            organizer.reorderPages(
                                context = context,
                                inputUri = file.uri,
                                outputStream = outputResult.outputStream,
                                newOrder = selectedPages.sorted(),
                                onProgress = { progress = it }
                            )
                        }

                        outputResult.outputStream.close()

                        organizeResult.fold(
                            onSuccess = { oResult ->
                                val message = if (isRemoveMode) {
                                    "Removed ${oResult.pagesRemoved} pages. Result has ${oResult.resultPageCount} pages.\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}"
                                } else {
                                    "Extracted ${oResult.resultPageCount} pages.\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}"
                                }
                                Triple(true, message, outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Operation failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Operation failed", null)
                }
            }

            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third
            if (resultSuccess) {
                selectedFile = null
                selectedPages = setOf()
            }
            isProcessing = false
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_organize_pages),
                subtitle = if (selectedFile == null) {
                    "Remove pages or make a focused extract"
                } else {
                    "$pageCount ${if (pageCount == 1) "page" else "pages"} ready to shape"
                },
                onNavigateBack = onNavigateBack,
                actions = {
                    if (selectedFile != null) {
                        IconButton(
                            onClick = {
                                selectedFile = null
                                selectedPages = emptySet()
                                pageCount = 0
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_remove),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
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
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        PdfToolHeroCard(
                            kicker = "SHAPE YOUR STORY",
                            title = "Keep what matters.",
                            description = "Remove pages you do not need or extract a focused new document.",
                            leadingIcon = Icons.Default.PictureAsPdf,
                            secondaryIcon = Icons.Default.SwapVert
                        )
                        PdfToolEmptyDropZone(
                            title = "Choose a PDF to organize",
                            subtitle = stringResource(R.string.organize_no_pdf_subtitle),
                            icon = Icons.Default.FolderOpen,
                            onClick = {
                                pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                            }
                        )
                        PdfToolPrivacyNote()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp)
                    ) {
                        PdfToolHeroCard(
                            kicker = "SHAPE YOUR STORY",
                            title = "Keep what matters.",
                            description = "Select pages below, then remove them or save them as a new PDF.",
                            leadingIcon = Icons.Default.PictureAsPdf,
                            secondaryIcon = if (isRemoveMode) Icons.Default.Delete else Icons.Default.ContentCopy,
                            status = "$pageCount pages."
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Selected file info
                        FileItemCard(
                            fileName = selectedFile!!.name,
                            fileSize = "${pageCount} pages • ${selectedFile!!.formattedSize}",
                            onRemove = {
                                selectedFile = null
                                selectedPages = setOf()
                                pageCount = 0
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        PdfToolSectionLabel(
                            step = "01",
                            title = "Choose an outcome",
                            subtitle = "Delete pages from the original, or extract only the pages you select."
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // Mode toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = isRemoveMode,
                                onClick = {
                                    isRemoveMode = true
                                    selectedPages = setOf()
                                },
                                label = { Text(stringResource(R.string.organize_remove_pages)) },
                                leadingIcon = if (isRemoveMode) {
                                    { Icon(Icons.Default.Delete, null, Modifier.size(18.dp)) }
                                } else null,
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = !isRemoveMode,
                                onClick = {
                                    isRemoveMode = false
                                    selectedPages = setOf()
                                },
                                label = { Text("Extract pages") },
                                leadingIcon = if (!isRemoveMode) {
                                    { Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp)) }
                                } else null,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Selection info
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (isRemoveMode) {
                                    "Select pages to remove"
                                } else {
                                    "Select pages to extract (in order)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row {
                                TextButton(onClick = {
                                    selectedPages = (1..pageCount).toSet()
                                }) {
                                    Text(stringResource(R.string.action_select_all))
                                }
                                TextButton(onClick = {
                                    selectedPages = setOf()
                                }) {
                                    Text(stringResource(R.string.action_select_none))
                                }
                            }
                        }

                        Text(
                            text = "${selectedPages.size} of $pageCount selected",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Page grid
                        PdfThumbnailGrid(
                            uri = selectedFile!!.uri,
                            pageCount = pageCount,
                            selectedPages = selectedPages,
                            onPageSelected = { pageNum ->
                                selectedPages = if (pageNum in selectedPages) {
                                    selectedPages - pageNum
                                } else {
                                    selectedPages + pageNum
                                }
                            },
                            columns = 3,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                // Progress overlay
                if (isProcessing) {
                    PdfToolProgressOverlay(
                        visible = true,
                        progress = progress,
                        message = if (isRemoveMode) "Removing pages..." else "Extracting pages...",
                        icon = if (isRemoveMode) Icons.Default.Delete else Icons.Default.ContentCopy
                    )
                }
            }

            // Bottom action area
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        val canProcess = selectedPages.isNotEmpty() &&
                                (isRemoveMode && selectedPages.size < pageCount) ||
                                (!isRemoveMode && selectedPages.isNotEmpty())

                        // Save location option
                        SaveLocationSelector(
                            useCustomLocation = useCustomLocation,
                            onUseCustomLocationChange = { useCustomLocation = it }
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        ActionButton(
                            text = if (isRemoveMode) {
                                "Remove ${selectedPages.size} Pages"
                            } else {
                                "Extract ${selectedPages.size} Pages"
                            },
                            onClick = {
                                if (useCustomLocation) {
                                    val baseName = selectedFile!!.name.removeSuffix(".pdf")
                                    val suffix = if (isRemoveMode) "_edited" else "_extracted"
                                    saveFileLauncher.safeLaunch("${baseName}${suffix}.pdf", context)
                                } else {
                                    organizeWithDefaultLocation()
                                }
                            },
                            enabled = canProcess,
                            isLoading = isProcessing,
                            icon = if (isRemoveMode) Icons.Default.Delete else Icons.Default.ContentCopy
                        )
                    }
                }
            }
        }
    }

    // Result dialog with View option
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) "Success" else "Error",
            message = resultMessage,
            onDismiss = {
                showResult = false
                resultUri = null
            },
            onAction = resultUri?.let { uri ->
                { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
            },
            actionText = stringResource(R.string.action_open_pdf)
        )
    }
}
