package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import com.yourname.pdftoolkit.ui.components.PdfThumbnailGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.PdfOrganizer
import com.yourname.pdftoolkit.domain.operations.PdfSplitter
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * Split modes available in the UI.
 */
private enum class SplitMode(val title: String, val description: String) {
    EXTRACT_RANGE("Extract Range", "Extract a range of pages to a new PDF"),
    ALL_PAGES("All Pages", "Split into individual pages (1 file per page)"),
    SPECIFIC_PAGES("Specific Pages", "Extract specific page numbers"),
    VISUAL_SELECT("Visual Selection", "Select specific pages visually from a preview")
}

/**
 * Screen for splitting PDF files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfSplitter = remember { PdfSplitter() }
    val organizer = remember { PdfOrganizer() }

    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var pageCount by remember { mutableStateOf(0) }
    var selectedMode by remember { mutableStateOf(SplitMode.EXTRACT_RANGE) }
    var startPage by remember { mutableStateOf("1") }
    var endPage by remember { mutableStateOf("1") }
    var specificPages by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showMultiOutputScreen by remember { mutableStateOf(false) }
    var useCustomLocation by remember { mutableStateOf(false) }

    // Visual Split Selection State
    var pages by remember { mutableStateOf<List<ReorderablePage>>(emptyList()) }
    var isLoadingThumbnails by remember { mutableStateOf(false) }
    var selectedVisualPages by remember { mutableStateOf<Set<Int>>(emptySet()) }

    // Sequential background-threaded thumbnail loading for Visual Split


    // File picker launcher - with PDF MIME type filter
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

            scope.launch {
                pageCount = pdfSplitter.getPageCount(context, uri)
                endPage = pageCount.toString()
            }
        }
    }

    // Save file launcher (for custom location - only for single output modes)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            performSplit(
                context = context,
                scope = scope,
                pdfSplitter = pdfSplitter,
                file = selectedFile!!,
                selectedMode = selectedMode,
                startPage = startPage,
                endPage = endPage,
                specificPages = specificPages,
                selectedVisualPages = selectedVisualPages,
                pageCount = pageCount,
                outputUri = outputUri,
                onProgress = { progress = it },
                onProcessing = { isProcessing = it },
                onResult = { success, message, uris ->
                    resultSuccess = success
                    resultMessage = message
                    resultUris = uris
                    showResult = true
                }
            )
        }
    }

    // Function to split with default location
    fun splitWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val originalFile = selectedFile!!

            // Handle ALL_PAGES mode separately to create multiple files
            if (selectedMode == SplitMode.ALL_PAGES) {
                val outputUris = mutableListOf<Uri>()
                var success = false
                var message = ""

                val result = withContext(Dispatchers.IO) {
                    try {
                        val splitResult = pdfSplitter.splitAllPages(
                            context = context,
                            inputUri = originalFile.uri,
                            outputCallback = { pageNumber, inputStream ->
                                // Save each page to a separate file
                                val fileName = "${originalFile.name.removeSuffix(".pdf")}_page_$pageNumber.pdf"
                                val outputFileResult = OutputFolderManager.createOutputStream(context, fileName)

                                if (outputFileResult != null) {
                                    inputStream.copyTo(outputFileResult.outputStream)
                                    outputFileResult.outputStream.close()
                                    outputUris.add(outputFileResult.outputFile.contentUri)
                                }
                                inputStream.close()
                            },
                            onProgress = { progress = it }
                        )

                        splitResult.fold(
                            onSuccess = { splitStats ->
                                success = true
                                message = "Successfully created ${splitStats.totalFilesCreated} PDF files"
                            },
                            onFailure = { error ->
                                success = false
                                message = error.message ?: "Split failed"
                            }
                        )
                    } catch (e: Exception) {
                        success = false
                        message = e.message ?: "Split failed"
                    }
                }

                resultSuccess = success
                resultMessage = message
                resultUris = outputUris

                // Record in history with multiple outputs
                if (success && outputUris.isNotEmpty()) {
                    HistoryManager.recordSuccess(
                        context = context,
                        operationType = OperationType.SPLIT,
                        inputFileName = originalFile.name,
                        outputFileUri = outputUris.firstOrNull(),
                        outputFileUris = outputUris,
                        outputFileName = "${originalFile.name.removeSuffix(".pdf")}_pages.pdf",
                        details = "Split into ${outputUris.size} individual pages"
                    )
                } else if (!success) {
                    HistoryManager.recordFailure(
                        context = context,
                        operationType = OperationType.SPLIT,
                        inputFileName = originalFile.name,
                        errorMessage = message
                    )
                }

                isProcessing = false
                if (success && outputUris.size > 1) {
                    showMultiOutputScreen = true
                } else {
                    showResult = true
                }
            } else {
                // Handle single output modes (EXTRACT_RANGE, SPECIFIC_PAGES)
                val result = withContext(Dispatchers.IO) {
                    try {
                        val fileName = FileManager.generateOutputFileName("split")
                        val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                        if (outputResult != null) {
                            val file = selectedFile!!
                            val pages = when (selectedMode) {
                                SplitMode.EXTRACT_RANGE -> {
                                    val start = startPage.toIntOrNull() ?: 1
                                    val end = endPage.toIntOrNull() ?: pageCount
                                    (start..end).toList()
                                }
                                SplitMode.SPECIFIC_PAGES -> parsePageNumbers(specificPages, pageCount)
                                SplitMode.VISUAL_SELECT -> selectedVisualPages.toList().sorted()
                                SplitMode.ALL_PAGES -> (1..pageCount).toList() // Won't reach here
                            }

                            val splitResult = pdfSplitter.extractPages(
                                context = context,
                                inputUri = file.uri,
                                pageNumbers = pages,
                                outputStream = outputResult.outputStream,
                                onProgress = { progress = it }
                            )

                            outputResult.outputStream.close()

                            splitResult.fold(
                                onSuccess = { count ->
                                    Triple(true, "Successfully extracted $count pages\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}", listOf(outputResult.outputFile.contentUri))
                                },
                                onFailure = { error ->
                                    outputResult.outputFile.file.delete()
                                    Triple(false, error.message ?: "Split failed", emptyList())
                                }
                            )
                        } else {
                            Triple(false, "Cannot create output file", emptyList())
                        }
                    } catch (e: Exception) {
                        Triple(false, e.message ?: "Split failed", emptyList())
                    }
                }

                resultSuccess = result.first
                resultMessage = result.second
                resultUris = result.third

                // Record in history
                if (resultSuccess && result.third.isNotEmpty()) {
                    HistoryManager.recordSuccess(
                        context = context,
                        operationType = OperationType.SPLIT,
                        inputFileName = originalFile.name,
                        outputFileUri = result.third.firstOrNull(),
                        outputFileUris = result.third,
                        outputFileName = "split_${originalFile.name}",
                        details = "Extracted pages from PDF"
                    )
                } else if (!resultSuccess) {
                    HistoryManager.recordFailure(
                        context = context,
                        operationType = OperationType.SPLIT,
                        inputFileName = originalFile.name,
                        errorMessage = result.second
                    )
                }

                isProcessing = false
                showResult = true
            }
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_split_pdf),
                subtitle = if (selectedFile == null) {
                    "Turn one document into exactly what you need"
                } else {
                    "$pageCount pages ready to shape"
                },
                onNavigateBack = onNavigateBack,
                actions = {
                    if (selectedFile != null) {
                        IconButton(
                            onClick = {
                                selectedFile = null
                                pageCount = 0
                                selectedVisualPages = emptySet()
                                useCustomLocation = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear selected PDF",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    item {
                        SplitHeroCard(
                            hasFile = selectedFile != null,
                            pageCount = pageCount
                        )
                    }

                    if (selectedFile == null) {
                        item {
                            SplitEmptyDropZone(
                                onSelectPdf = {
                                    pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                }
                            )
                        }
                        item {
                            SplitPrivacyNote()
                        }
                    } else if (selectedMode == SplitMode.VISUAL_SELECT) {
                        item {
                            SelectedFileHeader(
                                selectedFile = selectedFile!!,
                                pageCount = pageCount,
                                onRemove = { selectedFile = null }
                            )
                        }
                        item {
                            SplitSectionLabel(
                                step = "01",
                                title = "Choose pages visually",
                                subtitle = "Tap pages to include them in your new PDF."
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ShortcutChip(
                                    text = "All",
                                    onClick = { selectedVisualPages = (1..pageCount).toSet() },
                                    modifier = Modifier.weight(1f)
                                )
                                ShortcutChip(
                                    text = "Even",
                                    onClick = { selectedVisualPages = (1..pageCount).filter { it % 2 == 0 }.toSet() },
                                    modifier = Modifier.weight(1f)
                                )
                                ShortcutChip(
                                    text = "Odd",
                                    onClick = { selectedVisualPages = (1..pageCount).filter { it % 2 != 0 }.toSet() },
                                    modifier = Modifier.weight(1f)
                                )
                                ShortcutChip(
                                    text = "Clear",
                                    onClick = { selectedVisualPages = emptySet() },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(340.dp),
                                shape = RoundedCornerShape(22.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                            ) {
                                PdfThumbnailGrid(
                                    uri = selectedFile!!.uri,
                                    pageCount = pageCount,
                                    selectedPages = selectedVisualPages,
                                    onPageSelected = { pageNum ->
                                        selectedVisualPages = if (selectedVisualPages.contains(pageNum)) {
                                            selectedVisualPages - pageNum
                                        } else {
                                            selectedVisualPages + pageNum
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        item {
                            TextButton(
                                onClick = { selectedMode = SplitMode.EXTRACT_RANGE },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text(stringResource(R.string.split_switch_mode))
                            }
                        }
                        item {
                            SaveLocationSelector(
                                useCustomLocation = useCustomLocation,
                                onUseCustomLocationChange = { useCustomLocation = it }
                            )
                        }
                    } else {
                        item {
                            SelectedFileHeader(
                                selectedFile = selectedFile!!,
                                pageCount = pageCount,
                                onRemove = { selectedFile = null }
                            )
                        }
                        item {
                            SplitSectionLabel(
                                step = "01",
                                title = "Choose how to split",
                                subtitle = "Pick the outcome that matches your task."
                            )
                        }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                                SplitMode.entries
                                    .filter { it != SplitMode.VISUAL_SELECT }
                                    .forEach { mode ->
                                        SplitModeOption(
                                            mode = mode,
                                            isSelected = selectedMode == mode,
                                            onClick = { selectedMode = mode }
                                        )
                                    }
                                SplitModeOption(
                                    mode = SplitMode.VISUAL_SELECT,
                                    isSelected = selectedMode == SplitMode.VISUAL_SELECT,
                                    onClick = { selectedMode = SplitMode.VISUAL_SELECT }
                                )
                            }
                        }
                        item {
                            SplitSectionLabel(
                                step = "02",
                                title = when (selectedMode) {
                                    SplitMode.EXTRACT_RANGE -> "Set your range"
                                    SplitMode.SPECIFIC_PAGES -> "Name your pages"
                                    SplitMode.ALL_PAGES -> "Ready to separate"
                                    SplitMode.VISUAL_SELECT -> "Choose pages visually"
                                },
                                subtitle = when (selectedMode) {
                                    SplitMode.EXTRACT_RANGE -> "Keep the pages you want, in one clean file."
                                    SplitMode.SPECIFIC_PAGES -> "Use numbers or ranges, like 1, 3, 5-8."
                                    SplitMode.ALL_PAGES -> "Make one PDF for every page in the document."
                                    SplitMode.VISUAL_SELECT -> "Tap pages to include them in your new PDF."
                                }
                            )
                        }
                        item {
                            when (selectedMode) {
                                SplitMode.EXTRACT_RANGE -> {
                                    RangeInput(
                                        startPage = startPage,
                                        endPage = endPage,
                                        maxPages = pageCount,
                                        onStartChange = { startPage = it },
                                        onEndChange = { endPage = it }
                                    )
                                }
                                SplitMode.SPECIFIC_PAGES -> {
                                    SpecificPagesInput(
                                        value = specificPages,
                                        onChange = { specificPages = it },
                                        maxPages = pageCount
                                    )
                                }
                                SplitMode.ALL_PAGES -> {
                                    SplitInfoCard(
                                        text = "This creates $pageCount separate PDF files — one for every page."
                                    )
                                }
                                SplitMode.VISUAL_SELECT -> Unit
                            }
                        }
                        item {
                            SaveLocationSelector(
                                useCustomLocation = useCustomLocation,
                                onUseCustomLocationChange = { useCustomLocation = it }
                            )
                        }
                    }
                }

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
                        if (selectedFile != null) {
                            Text(
                                text = splitSummary(
                                    selectedMode = selectedMode,
                                    startPage = startPage,
                                    endPage = endPage,
                                    specificPages = specificPages,
                                    selectedVisualPages = selectedVisualPages,
                                    pageCount = pageCount
                                ),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }

                        AnimatedContent(
                            targetState = selectedFile == null,
                            label = "split-action"
                        ) { isEmpty ->
                            if (isEmpty) {
                                ActionButton(
                                    text = stringResource(R.string.split_select_pdf),
                                    onClick = {
                                        pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                    },
                                    icon = Icons.Default.FolderOpen
                                )
                            } else {
                                ActionButton(
                                    text = "Split ${splitSummary(
                                        selectedMode = selectedMode,
                                        startPage = startPage,
                                        endPage = endPage,
                                        specificPages = specificPages,
                                        selectedVisualPages = selectedVisualPages,
                                        pageCount = pageCount
                                    )}",
                                    onClick = {
                                        if (useCustomLocation) {
                                            val fileName = FileManager.generateOutputFileName("split")
                                            savePdfLauncher.safeLaunch(fileName, context)
                                        } else {
                                            splitWithDefaultLocation()
                                        }
                                    },
                                    enabled = isValidInput(
                                        selectedMode,
                                        startPage,
                                        endPage,
                                        specificPages,
                                        selectedVisualPages,
                                        pageCount
                                    ),
                                    isLoading = isProcessing,
                                    icon = Icons.Default.CallSplit
                                )
                            }
                        }
                    }
                }
            }

            if (isProcessing) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = true,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.38f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(28.dp),
                            shape = RoundedCornerShape(28.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    modifier = Modifier.size(56.dp),
                                    shape = RoundedCornerShape(18.dp),
                                    color = MaterialTheme.colorScheme.primaryContainer
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CallSplit,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                OperationProgress(
                                    progress = progress,
                                    message = stringResource(R.string.split_progress)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Result dialog with View option (for single output)
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) "Split Complete" else "Split Failed",
            message = resultMessage,
            onDismiss = {
                showResult = false
                resultUris = emptyList()
            },
            onAction = resultUris.firstOrNull()?.let { uri ->
                { scope.launch(Dispatchers.IO) { FileOpener.openPdf(context, uri) } }
            },
            actionText = stringResource(R.string.action_open_pdf)
        )
    }

    // Multi-output result screen (for ALL_PAGES mode)
    if (showMultiOutputScreen && resultUris.isNotEmpty()) {
        MultiOutputResultScreen(
            title = stringResource(R.string.split_all_pages_title),
            outputUris = resultUris,
            isImageOutput = false,
            onNavigateBack = {
                showMultiOutputScreen = false
                resultUris = emptyList()
                selectedFile = null
            }
        )
    }
}

private fun performSplit(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    pdfSplitter: PdfSplitter,
    file: PdfFileInfo,
    selectedMode: SplitMode,
    startPage: String,
    endPage: String,
    specificPages: String,
    selectedVisualPages: Set<Int>,
    pageCount: Int,
    outputUri: Uri,
    onProgress: (Float) -> Unit,
    onProcessing: (Boolean) -> Unit,
    onResult: (Boolean, String, List<Uri>) -> Unit
) {
    scope.launch {
        onProcessing(true)
        onProgress(0f)

        val outputStream = context.contentResolver.openOutputStream(outputUri)
        if (outputStream != null) {
            val pages = when (selectedMode) {
                SplitMode.EXTRACT_RANGE -> {
                    val start = startPage.toIntOrNull() ?: 1
                    val end = endPage.toIntOrNull() ?: pageCount
                    (start..end).toList()
                }
                SplitMode.SPECIFIC_PAGES -> parsePageNumbers(specificPages, pageCount)
                SplitMode.VISUAL_SELECT -> selectedVisualPages.toList().sorted()
                SplitMode.ALL_PAGES -> (1..pageCount).toList()
            }

            val result = pdfSplitter.extractPages(
                context = context,
                inputUri = file.uri,
                pageNumbers = pages,
                outputStream = outputStream,
                onProgress = onProgress
            )

            outputStream.close()

            result.fold(
                onSuccess = { count ->
                    onResult(true, "Successfully extracted $count pages", listOf(outputUri))
                },
                onFailure = { error ->
                    onResult(false, error.message ?: "Split failed", emptyList())
                }
            )
        } else {
            onResult(false, "Cannot create output file", emptyList())
        }

        onProcessing(false)
    }
}

@Composable
private fun SplitHeroCard(hasFile: Boolean, pageCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.secondary,
                            MaterialTheme.colorScheme.primary
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 42.dp, y = (-58).dp)
                    .size(174.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(100))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 44.dp, y = 70.dp)
                    .size(112.dp)
                    .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(100))
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    Text(
                        text = "MAKE IT YOURS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    Text(
                        text = "Shape one PDF\ninto many.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (hasFile) {
                            "$pageCount pages, ready for your next move."
                        } else {
                            "Keep the pages you need. Let the rest go."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                }
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .size(92.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier
                            .size(58.dp)
                            .offset(x = 10.dp, y = (-10).dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.18f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .size(58.dp)
                            .offset(x = (-10).dp, y = 12.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = Color.White.copy(alpha = 0.28f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallSplit,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitEmptyDropZone(onSelectPdf: () -> Unit) {
    Surface(
        onClick = onSelectPdf,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = 1.5.dp,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.38f)
        ),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(58.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = "Choose a PDF to begin",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "We’ll show you the fastest way forward",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = "Choose a PDF",
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun SplitPrivacyNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Private by design. Your PDF stays on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SplitSectionLabel(step: String, title: String, subtitle: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = RoundedCornerShape(11.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = step,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
        Spacer(modifier = Modifier.width(11.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SplitInfoCard(text: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = MaterialTheme.colorScheme.secondary
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondary,
                    modifier = Modifier.padding(11.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

private fun splitSummary(
    selectedMode: SplitMode,
    startPage: String,
    endPage: String,
    specificPages: String,
    selectedVisualPages: Set<Int>,
    pageCount: Int
): String {
    return when (selectedMode) {
        SplitMode.EXTRACT_RANGE -> {
            val start = startPage.toIntOrNull() ?: 0
            val end = endPage.toIntOrNull() ?: 0
            "${(end - start + 1).coerceAtLeast(0)} pages in 1 PDF"
        }
        SplitMode.SPECIFIC_PAGES -> {
            "${parsePageNumbers(specificPages, pageCount).size} pages in 1 PDF"
        }
        SplitMode.VISUAL_SELECT -> {
            "${selectedVisualPages.size} pages in 1 PDF"
        }
        SplitMode.ALL_PAGES -> "$pageCount separate PDFs"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitModeOption(
    mode: SplitMode,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(
            width = if (isSelected) 1.5.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 1.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(13.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Icon(
                    imageVector = when (mode) {
                        SplitMode.EXTRACT_RANGE -> Icons.Default.SwapVert
                        SplitMode.ALL_PAGES -> Icons.Default.CopyAll
                        SplitMode.SPECIFIC_PAGES -> Icons.Default.FormatListNumbered
                        SplitMode.VISUAL_SELECT -> Icons.Default.GridView
                    },
                    contentDescription = null,
                    tint = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(11.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mode.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RadioButton(
                selected = isSelected,
                onClick = onClick
            )
        }
    }
}

@Composable
private fun RangeInput(
    startPage: String,
    endPage: String,
    maxPages: Int,
    onStartChange: (String) -> Unit,
    onEndChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Pages to keep",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Choose a continuous range from 1 to $maxPages",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = startPage,
                    onValueChange = onStartChange,
                    label = { Text(stringResource(R.string.split_from)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp)
                )

                Text("→", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = endPage,
                    onValueChange = onEndChange,
                    label = { Text(stringResource(R.string.split_to)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    shape = RoundedCornerShape(15.dp)
                )
            }
        }
    }
}

@Composable
private fun SpecificPagesInput(
    value: String,
    onChange: (String) -> Unit,
    maxPages: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Pages to keep",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                label = { Text(stringResource(R.string.split_page_numbers)) },
                placeholder = { Text(stringResource(R.string.split_placeholder)) },
                supportingText = { Text("Use page numbers or ranges from 1 to $maxPages") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(15.dp)
            )
        }
    }
}

/**
 * Parse page numbers from input string.
 * Supports formats: "1, 3, 5-8, 10"
 */
private fun parsePageNumbers(input: String, maxPages: Int): List<Int> {
    if (input.isBlank()) return emptyList()

    val pages = mutableSetOf<Int>()

    input.split(",").forEach { part ->
        val trimmed = part.trim()
        if (trimmed.contains("-")) {
            val range = trimmed.split("-")
            if (range.size == 2) {
                val start = range[0].trim().toIntOrNull() ?: return@forEach
                val end = range[1].trim().toIntOrNull() ?: return@forEach
                if (start in 1..maxPages && end in 1..maxPages) {
                    pages.addAll(start..end)
                }
            }
        } else {
            val page = trimmed.toIntOrNull()
            if (page != null && page in 1..maxPages) {
                pages.add(page)
            }
        }
    }

    return pages.sorted()
}

private fun isValidInput(
    mode: SplitMode,
    startPage: String,
    endPage: String,
    specificPages: String,
    selectedVisualPages: Set<Int>,
    pageCount: Int
): Boolean {
    return when (mode) {
        SplitMode.EXTRACT_RANGE -> {
            val start = startPage.toIntOrNull() ?: return false
            val end = endPage.toIntOrNull() ?: return false
            start in 1..pageCount && end in 1..pageCount && start <= end
        }
        SplitMode.SPECIFIC_PAGES -> {
            parsePageNumbers(specificPages, pageCount).isNotEmpty()
        }
        SplitMode.VISUAL_SELECT -> {
            selectedVisualPages.isNotEmpty()
        }
        SplitMode.ALL_PAGES -> true
    }
}

@Composable
private fun SelectedFileHeader(
    selectedFile: PdfFileInfo,
    pageCount: Int,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(12.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = selectedFile.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "$pageCount pages  •  ${selectedFile.formattedSize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ShortcutChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.8f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 8.dp)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

