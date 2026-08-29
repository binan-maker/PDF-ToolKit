package com.anonymous.imgpdf.ui.screens
import com.anonymous.imgpdf.util.safeLaunch

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import com.anonymous.imgpdf.ui.components.PdfThumbnailGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anonymous.imgpdf.data.FileManager
import com.anonymous.imgpdf.data.HistoryManager
import com.anonymous.imgpdf.data.OperationType
import com.anonymous.imgpdf.data.PdfFileInfo
import com.anonymous.imgpdf.domain.operations.PdfSplitter
import com.anonymous.imgpdf.ui.components.*
import com.anonymous.imgpdf.util.FileOpener
import com.anonymous.imgpdf.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for extracting specific pages from PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfSplitter = remember { PdfSplitter() }

    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var selectedPages by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }

    // File picker launcher - with PDF MIME type filter and validation
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            // Validate PDF file
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != "application/pdf") {
                // Invalid file type - ignore or show error
                return@let
            }
            val fileInfo = FileManager.getFileInfo(context, uri)
            selectedFile = fileInfo
            selectedPages = emptySet()

            scope.launch {
                pageCount = pdfSplitter.getPageCount(context, uri)
            }
        }
    }

    // Save file launcher (for custom location)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            val file = selectedFile ?: return@let
            if (selectedPages.isEmpty()) return@let

            scope.launch {
                isProcessing = true
                progress = 0f

                val outputStream = context.contentResolver.openOutputStream(outputUri)
                if (outputStream != null) {
                    val result = pdfSplitter.extractPages(
                        context = context,
                        inputUri = file.uri,
                        pageNumbers = selectedPages.sorted(),
                        outputStream = outputStream,
                        onProgress = { progress = it }
                    )

                    outputStream.close()

                    result.fold(
                        onSuccess = { count ->
                            resultSuccess = true
                            resultMessage = "Successfully extracted $count pages"
                            resultUri = outputUri
                        },
                        onFailure = { error ->
                            resultSuccess = false
                            resultMessage = error.message ?: "Extraction failed"
                        }
                    )
                } else {
                    resultSuccess = false
                    resultMessage = "Cannot create output file"
                }

                isProcessing = false
                showResult = true
            }
        }
    }

    // Function to extract with default location
    fun extractWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val originalFile = selectedFile!!
            val pagesList = selectedPages.sorted()

            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("extracted")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val file = selectedFile!!
                        val extractResult = pdfSplitter.extractPages(
                            context = context,
                            inputUri = file.uri,
                            pageNumbers = pagesList,
                            outputStream = outputResult.outputStream,
                            onProgress = { progress = it }
                        )

                        outputResult.outputStream.close()

                        extractResult.fold(
                            onSuccess = { count ->
                                Triple(true, "Successfully extracted $count pages\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}", outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Extraction failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Extraction failed", null)
                }
            }

            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third

            // Record in history
            if (resultSuccess && result.third != null) {
                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.EXTRACT,
                    inputFileName = originalFile.name,
                    outputFileUri = result.third,
                    outputFileName = "extracted_${originalFile.name}",
                    details = "Extracted ${pagesList.size} pages"
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.EXTRACT,
                    inputFileName = originalFile.name,
                    errorMessage = result.second
                )
            }

            isProcessing = false
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = "Extract Pages",
                subtitle = if (selectedFile == null) {
                    "Save the pages you need as a new PDF"
                } else {
                    "$pageCount ${if (pageCount == 1) "page" else "pages"} ready to choose from"
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
                                contentDescription = "Remove selected PDF",
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
                            kicker = "MAKE A FOCUSED COPY",
                            title = "Keep the good parts.",
                            description = "Choose the pages you need and save them as a clean, shareable PDF.",
                            leadingIcon = Icons.Default.PictureAsPdf,
                            secondaryIcon = Icons.Default.ContentCopy
                        )
                        PdfToolEmptyDropZone(
                            title = "Choose a PDF to extract from",
                            subtitle = "Select the pages you want to keep",
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
                            kicker = "MAKE A FOCUSED COPY",
                            title = "Keep the good parts.",
                            description = "Select the pages you want to keep, then create a new PDF.",
                            leadingIcon = Icons.Default.PictureAsPdf,
                            secondaryIcon = Icons.Default.ContentCopy,
                            status = "$pageCount pages."
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Selected file info
                        FileItemCard(
                            fileName = selectedFile!!.name,
                            fileSize = "$pageCount pages • ${selectedFile!!.formattedSize}",
                            onRemove = {
                                selectedFile = null
                                selectedPages = emptySet()
                                pageCount = 0
                            }
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        PdfToolSectionLabel(
                            step = "01",
                            title = "Choose your pages",
                            subtitle = "Tap any page to include it in the new PDF."
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // Selection controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Select Pages (${selectedPages.size} of $pageCount)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Row {
                                TextButton(
                                    onClick = { selectedPages = (1..pageCount).toSet() }
                                ) {
                                    Text("Select All")
                                }
                                TextButton(
                                    onClick = { selectedPages = emptySet() }
                                ) {
                                    Text("Clear")
                                }
                            }
                        }

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
                            columns = 4,
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                }

                // Progress overlay
                if (isProcessing) {
                    PdfToolProgressOverlay(
                        visible = true,
                        progress = progress,
                        message = "Extracting pages...",
                        icon = Icons.Default.ContentCopy
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
                        SaveLocationSelector(
                            useCustomLocation = useCustomLocation,
                            onUseCustomLocationChange = { useCustomLocation = it }
                        )
                        ActionButton(
                            text = "Extract ${selectedPages.size} Pages",
                            onClick = {
                                if (useCustomLocation) {
                                    val fileName = FileManager.generateOutputFileName("extracted")
                                    savePdfLauncher.safeLaunch(fileName, context)
                                } else {
                                    extractWithDefaultLocation()
                                }
                            },
                            enabled = selectedPages.isNotEmpty(),
                            isLoading = isProcessing,
                            icon = Icons.Default.ContentCopy
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
            title = if (resultSuccess) "Extraction Complete" else "Extraction Failed",
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

