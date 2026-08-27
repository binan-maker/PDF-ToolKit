package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.pdftoolkit.R
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.PdfMerger
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


/**
 * Screen for merging multiple PDF files into one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MergeScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfMerger = remember { PdfMerger() }

    // State
    var selectedFiles by remember { mutableStateOf<List<PdfFileInfo>>(emptyList()) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }

    // File picker launcher for multiple PDFs - with MIME type filter
    val pickPdfsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val newFiles = uris.mapNotNull { uri ->
                // Validate PDF file
                val mimeType = context.contentResolver.getType(uri)
                if (mimeType == "application/pdf") {
                    FileManager.getFileInfo(context, uri)
                } else null
            }
            selectedFiles = selectedFiles + newFiles
        }
    }

    // Custom save file launcher (optional)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            performMerge(
                context = context,
                scope = scope,
                pdfMerger = pdfMerger,
                selectedFiles = selectedFiles,
                outputUri = outputUri,
                onProgress = { progress = it },
                onProcessing = { isProcessing = it },
                onResult = { success, message, uri ->
                    resultSuccess = success
                    resultMessage = message
                    resultUri = uri
                    if (success) selectedFiles = emptyList()
                    showResult = true
                }
            )
        }
    }

    // Function to merge with default location
    fun mergeWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val fileCount = selectedFiles.size
            val firstFileName = selectedFiles.firstOrNull()?.name

            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("merged")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val mergeResult = pdfMerger.mergePdfs(
                            context = context,
                            inputUris = selectedFiles.map { it.uri },
                            outputStream = outputResult.outputStream,
                            onProgress = { progress = it }
                        )

                        outputResult.outputStream.close()

                        mergeResult.fold(
                            onSuccess = {
                                Triple(true, "Successfully merged $fileCount PDFs\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}", outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Merge failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Merge failed", null)
                }
            }

            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third

            // Record in history
            if (resultSuccess && result.third != null) {
                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.MERGE,
                    inputFileName = firstFileName,
                    outputFileUri = result.third,
                    outputFileName = "merged_${fileCount}_files.pdf",
                    details = "Merged $fileCount PDF files"
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.MERGE,
                    inputFileName = firstFileName,
                    errorMessage = result.second
                )
            }

            if (resultSuccess) selectedFiles = emptyList()
            isProcessing = false
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.merge_title),
                subtitle = if (selectedFiles.isEmpty()) {
                    "Build one polished PDF"
                } else {
                    "${selectedFiles.size} ${if (selectedFiles.size == 1) "file" else "files"} in your queue"
                },
                onNavigateBack = onNavigateBack,
                actions = {
                    if (selectedFiles.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                selectedFiles = emptyList()
                                useCustomLocation = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear selected files",
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
                    contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    item {
                        MergeHeroCard(fileCount = selectedFiles.size)
                    }

                    item {
                        AnimatedContent(
                            targetState = selectedFiles.isEmpty(),
                            label = "merge-content"
                        ) { isEmpty ->
                            if (isEmpty) {
                                EmptyMergeDropZone(
                                    onAddFiles = {
                                        pickPdfsLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                    }
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    MergeQueueHeading(fileCount = selectedFiles.size)
                                    Text(
                                        text = "The order here becomes the order in your final PDF.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    selectedFiles.forEachIndexed { index, file ->
                                        FileItemCardWithOrder(
                                            index = index + 1,
                                            fileName = file.name,
                                            fileSize = file.formattedSize,
                                            onRemove = {
                                                selectedFiles = selectedFiles.toMutableList().apply {
                                                    removeAt(index)
                                                }
                                            },
                                            onMoveUp = if (index > 0) {
                                                {
                                                    selectedFiles = selectedFiles.toMutableList().apply {
                                                        val item = removeAt(index)
                                                        add(index - 1, item)
                                                    }
                                                }
                                            } else null,
                                            onMoveDown = if (index < selectedFiles.lastIndex) {
                                                {
                                                    selectedFiles = selectedFiles.toMutableList().apply {
                                                        val item = removeAt(index)
                                                        add(index + 1, item)
                                                    }
                                                }
                                            } else null
                                        )
                                    }

                                    OutlinedButton(
                                        onClick = {
                                            pickPdfsLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(52.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(
                                            width = 1.dp,
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                        )
                                    ) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.action_add_more_pdfs),
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }

                                    SaveLocationSelector(
                                        useCustomLocation = useCustomLocation,
                                        onUseCustomLocationChange = { useCustomLocation = it }
                                    )
                                }
                            }
                        }
                    }

                    if (selectedFiles.isEmpty()) {
                        item {
                            MergePrivacyNote()
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
                        if (selectedFiles.size == 1) {
                            Text(
                                text = "Add one more PDF to unlock merging",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }

                        AnimatedContent(
                            targetState = selectedFiles.isEmpty(),
                            label = "merge-action"
                        ) { isEmpty ->
                            if (isEmpty) {
                                ActionButton(
                                    text = stringResource(R.string.merge_add_pdfs),
                                    onClick = {
                                        pickPdfsLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                    },
                                    icon = Icons.Default.FolderOpen
                                )
                            } else {
                                ActionButton(
                                    text = stringResource(R.string.merge_n_pdfs, selectedFiles.size),
                                    onClick = {
                                        if (useCustomLocation) {
                                            val fileName = FileManager.generateOutputFileName("merged")
                                            savePdfLauncher.safeLaunch(fileName, context)
                                        } else {
                                            mergeWithDefaultLocation()
                                        }
                                    },
                                    enabled = selectedFiles.size >= 2,
                                    isLoading = isProcessing,
                                    icon = Icons.Default.MergeType
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
                            shape = RoundedCornerShape(28.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
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
                                        imageVector = Icons.Default.MergeType,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                OperationProgress(
                                    progress = progress,
                                    message = stringResource(R.string.merge_progress)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Result dialog with View option
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) stringResource(R.string.merge_complete) else stringResource(R.string.merge_failed),
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

private fun performMerge(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    pdfMerger: PdfMerger,
    selectedFiles: List<PdfFileInfo>,
    outputUri: Uri,
    onProgress: (Float) -> Unit,
    onProcessing: (Boolean) -> Unit,
    onResult: (Boolean, String, Uri?) -> Unit
) {
    scope.launch {
        onProcessing(true)
        onProgress(0f)

        val outputStream = context.contentResolver.openOutputStream(outputUri)
        if (outputStream != null) {
            val result = pdfMerger.mergePdfs(
                context = context,
                inputUris = selectedFiles.map { it.uri },
                outputStream = outputStream,
                onProgress = onProgress
            )

            outputStream.close()

            result.fold(
                onSuccess = {
                    onResult(true, "Successfully merged ${selectedFiles.size} PDFs", outputUri)
                },
                onFailure = { error ->
                    onResult(false, error.message ?: "Merge failed", null)
                }
            )
        } else {
            onResult(false, "Cannot create output file", null)
        }

        onProcessing(false)
    }
}

@Composable
private fun MergeHeroCard(fileCount: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.tertiary
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 40.dp, y = (-62).dp)
                    .size(176.dp)
                    .clip(RoundedCornerShape(100))
                    .background(Color.White.copy(alpha = 0.08f))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 38.dp, y = 68.dp)
                    .size(118.dp)
                    .clip(RoundedCornerShape(100))
                    .background(Color.White.copy(alpha = 0.10f))
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
                        text = "ONE CLEAN DOCUMENT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            letterSpacing = 1.4.sp
                        ),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    Text(
                        text = "Bring your PDFs\ntogether.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (fileCount == 0) {
                            "Choose your files, set the order, and make one flow."
                        } else {
                            "$fileCount ${if (fileCount == 1) "file is" else "files are"} ready to become one."
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
                            imageVector = Icons.Default.MergeType,
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
private fun EmptyMergeDropZone(onAddFiles: () -> Unit) {
    Surface(
        onClick = onAddFiles,
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
                    text = "Start with your PDFs",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Select two or more files at once",
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
                    contentDescription = "Choose PDF files",
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun MergeQueueHeading(fileCount: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "YOUR MERGE QUEUE",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.3.sp),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Arrange the story",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Text(
                text = "$fileCount ${if (fileCount == 1) "PDF" else "PDFs"}",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun MergePrivacyNote() {
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
                text = "Your files stay on this device. Nothing is uploaded.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * File item card with ordering controls.
 */
@Composable
private fun FileItemCardWithOrder(
    index: Int,
    fileName: String,
    fileSize: String,
    onRemove: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(44.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = index.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(lineHeight = 19.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "ORDER",
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 7.sp),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    modifier = Modifier.padding(10.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.width(11.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                    ,overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "PDF  •  $fileSize",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { onMoveUp?.invoke() },
                    enabled = onMoveUp != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = stringResource(R.string.action_move_up),
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { onMoveDown?.invoke() },
                    enabled = onMoveDown != null,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.action_move_down),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(38.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = stringResource(R.string.action_remove),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

