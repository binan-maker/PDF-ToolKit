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
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.data.PdfFileInfo
import com.yourname.pdftoolkit.domain.operations.CompressionLevel
import com.yourname.pdftoolkit.domain.operations.CompressionMode
import com.yourname.pdftoolkit.domain.operations.PdfCompressor
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen for compressing PDF files.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompressScreen(
    onNavigateBack: () -> Unit,
    initialUri: Uri? = null,
    initialName: String? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfCompressor = remember { PdfCompressor() }

    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    // "Normal" is the user-facing name for our automatic hybrid strategy.
    var compressionMode by remember { mutableStateOf(CompressionMode.HYBRID) }
    var compressionSliderValue by remember { mutableStateOf(50f) }
    var targetSizeText by remember { mutableStateOf("") }

    // Derive CompressionLevel from slider position
    val compressionLevel = when {
        compressionSliderValue < 25f -> CompressionLevel.LOW
        compressionSliderValue < 50f -> CompressionLevel.MEDIUM
        compressionSliderValue < 75f -> CompressionLevel.HIGH
        else -> CompressionLevel.MAXIMUM
    }

    // Parse target size input to bytes (null when invalid/empty)
    val targetSizeBytes: Long? = targetSizeText.toDoubleOrNull()?.let { value ->
        (value * 1024L).toLong().takeIf { it > 0 }
    }
    val targetInputValid = compressionMode != CompressionMode.TARGET_SIZE || targetSizeBytes != null

    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }

    // Auto-load initial file if provided
    LaunchedEffect(initialUri) {
        if (initialUri != null && selectedFile == null) {
            selectedFile = FileManager.getFileInfo(context, initialUri)
        }
    }

    // File picker launcher
    val pickPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            selectedFile = FileManager.getFileInfo(context, uri)
        }
    }

    // Save file launcher (for custom location)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            selectedFile?.let { file ->
                scope.launch {
                    isProcessing = true
                    progress = 0f

                    val outputStream = context.contentResolver.openOutputStream(outputUri)
                    if (outputStream != null) {
                        val resolvedTargetBytes = if (compressionMode == CompressionMode.TARGET_SIZE) {
                            targetSizeBytes
                        } else null

                        val result = if (resolvedTargetBytes != null) {
                            pdfCompressor.compressPdfToTargetSize(
                                context = context,
                                inputUri = file.uri,
                                outputStream = outputStream,
                                targetBytes = resolvedTargetBytes,
                                onProgress = { progress = it }
                            ).mapCatching { it.base }
                        } else {
                            pdfCompressor.compressPdf(
                                context = context,
                                inputUri = file.uri,
                                outputStream = outputStream,
                                level = compressionLevel,
                                qualityPercent = compressionSliderValue.toInt(),
                                onProgress = { progress = it }
                            )
                        }

                        outputStream.close()

                        // Get compressed file size
                        val compressedInfo = FileManager.getFileInfo(context, outputUri)

                        result.fold(
                            onSuccess = { compressionResult ->
                                val actualCompressedSize = compressedInfo?.size ?: compressionResult.compressedSize
                                val originalBytes = file.size
                                val savedBytes = originalBytes - actualCompressedSize
                                val savedPercent = if (originalBytes > 0) {
                                    (savedBytes.toFloat() / originalBytes * 100).toInt()
                                } else 0

                                // IMPORTANT: Copy the file to cache for "Open PDF" functionality
                                // CreateDocument URIs lose read permission after the operation
                                val cachedUri = copyToViewerCache(context, outputUri)

                                if (resolvedTargetBytes != null) {
                                    val missedTarget = actualCompressedSize > resolvedTargetBytes
                                    resultSuccess = true
                                    resultUri = cachedUri ?: outputUri
                                    resultMessage = buildString {
                                        if (missedTarget) {
                                            append("Target could not be reached.\n\n")
                                            append("Requested Size: ${FileManager.formatFileSize(resolvedTargetBytes)}\n")
                                            append("Achieved Size: ${compressedInfo?.formattedSize ?: "Unknown"}\n\n")
                                            append("The best achievable output has been saved.")
                                        } else {
                                            append("Compression successful!\n\n")
                                            append("Requested Size: ${FileManager.formatFileSize(resolvedTargetBytes)}\n")
                                            append("Achieved Size: ${compressedInfo?.formattedSize ?: "Unknown"}\n")
                                            if (savedBytes > 0) {
                                                append("\nSaved: ${FileManager.formatFileSize(savedBytes)} ($savedPercent%)")
                                            }
                                        }
                                    }
                                } else if (savedBytes > 0) {
                                    resultSuccess = true
                                    resultUri = cachedUri ?: outputUri
                                    resultMessage = buildString {
                                        append("Compression successful!\n\n")
                                        append("Before: ${file.formattedSize}\n")
                                        append("After: ${compressedInfo?.formattedSize ?: "Unknown"}\n")
                                        append("Saved: ${FileManager.formatFileSize(savedBytes)} ($savedPercent%)")
                                    }
                                } else {
                                    resultSuccess = true
                                    resultUri = cachedUri ?: outputUri
                                    resultMessage = buildString {
                                        append("Compressed PDF saved.\n\n")
                                        append("Before: ${file.formattedSize}\n")
                                        append("After: ${compressedInfo?.formattedSize ?: "Unknown"}\n\n")
                                        append("Note: No size reduction achieved. This PDF likely contains mostly text or vector content, which cannot be compressed further by image optimization. Try a higher compression level or the file may already be at minimum size.")
                                    }
                                }
                                selectedFile = null
                            },
                            onFailure = { error ->
                                resultSuccess = false
                                resultMessage = error.message ?: "Compression failed"
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
    }

    // Function to compress with default location
    fun compressWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val originalFile = selectedFile!!
            val isTargetMode = compressionMode == CompressionMode.TARGET_SIZE
            val resolvedTargetBytes = if (isTargetMode) targetSizeBytes else null

            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("compressed")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val compressResult = if (resolvedTargetBytes != null) {
                            pdfCompressor.compressPdfToTargetSize(
                                context = context,
                                inputUri = originalFile.uri,
                                outputStream = outputResult.outputStream,
                                targetBytes = resolvedTargetBytes,
                                onProgress = { progress = it }
                            ).mapCatching { it.base }
                        } else {
                            pdfCompressor.compressPdf(
                                context = context,
                                inputUri = originalFile.uri,
                                outputStream = outputResult.outputStream,
                                level = compressionLevel,
                                qualityPercent = compressionSliderValue.toInt(),
                                onProgress = { progress = it }
                            )
                        }

                        outputResult.outputStream.close()

                        compressResult.fold(
                            onSuccess = { cResult ->
                                val compressedSize = FileManager.getFileInfo(context, outputResult.outputFile.contentUri)?.size ?: outputResult.outputFile.file.length()
                                val originalBytes = originalFile.size
                                val savedBytes = originalBytes - compressedSize
                                val savedPercent = if (originalBytes > 0) {
                                    (savedBytes.toFloat() / originalBytes * 100).toInt()
                                } else 0

                                val message = buildString {
                                    if (resolvedTargetBytes != null && compressedSize > resolvedTargetBytes) {
                                        append("Target could not be reached.\n\n")
                                        append("Requested Size: ${FileManager.formatFileSize(resolvedTargetBytes)}\n")
                                        append("Achieved Size: ${FileManager.formatFileSize(compressedSize)}\n\n")
                                        append("The best achievable output has been saved.\n\n")
                                    } else if (resolvedTargetBytes != null) {
                                        append("Compression successful!\n\n")
                                        append("Requested Size: ${FileManager.formatFileSize(resolvedTargetBytes)}\n")
                                        append("Achieved Size: ${FileManager.formatFileSize(compressedSize)}\n")
                                        if (savedBytes > 0) {
                                            append("Saved: ${FileManager.formatFileSize(savedBytes)} ($savedPercent%)\n")
                                        }
                                        append("\n")
                                    } else if (savedBytes > 0) {
                                        append("Compression successful!\n\n")
                                        append("Before: ${originalFile.formattedSize}\n")
                                        append("After: ${FileManager.formatFileSize(compressedSize)}\n")
                                        append("Saved: ${FileManager.formatFileSize(savedBytes)} ($savedPercent%)\n\n")
                                    } else {
                                        append("Compressed PDF saved.\n\n")
                                        append("Before: ${originalFile.formattedSize}\n")
                                        append("After: ${FileManager.formatFileSize(compressedSize)}\n\n")
                                        append("Note: No size reduction achieved. This PDF likely contains mostly text or vector content, which cannot be compressed further by image optimization. Try a higher compression level or the file may already be at minimum size.\n\n")
                                    }
                                    append("Saved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}")
                                }
                                Triple(true, message, outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Compression failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Compression failed", null)
                }
            }

            resultSuccess = result.first
            resultMessage = result.second
            resultUri = result.third

            // Record in history
            if (resultSuccess && result.third != null) {
                // Add to recent files
                SafUriManager.addRecentFile(context, result.third!!)

                HistoryManager.recordSuccess(
                    context = context,
                    operationType = OperationType.COMPRESS,
                    inputFileName = originalFile.name,
                    outputFileUri = result.third,
                    outputFileName = "compressed_${originalFile.name}",
                    details = if (isTargetMode) {
                        "Compressed from ${originalFile.formattedSize} (target size)"
                    } else {
                        "Compressed from ${originalFile.formattedSize}"
                    }
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.COMPRESS,
                    inputFileName = originalFile.name,
                    errorMessage = result.second
                )
            }

            if (resultSuccess) {
                selectedFile = null
            }
            isProcessing = false
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            text = stringResource(R.string.tool_compress_pdf),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (selectedFile == null) {
                                "Make room without losing the good parts"
                            } else {
                                "Tune the balance, then make it lighter"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (selectedFile != null) {
                        IconButton(
                            onClick = {
                                selectedFile = null
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
                },
                windowInsets = WindowInsets(0, 0, 0, 0)
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
                        CompressHeroCard(
                            hasFile = selectedFile != null,
                            originalSize = selectedFile?.formattedSize
                        )
                    }

                    if (selectedFile == null) {
                        item {
                            CompressEmptyDropZone(
                                onSelectPdf = {
                                    pickPdfLauncher.safeLaunch("application/pdf", context)
                                }
                            )
                        }
                        item {
                            CompressPrivacyNote()
                        }
                    } else {
                        item {
                            CompressSelectedFileHeader(
                                file = selectedFile!!,
                                onRemove = { selectedFile = null }
                            )
                        }
                        item {
                            CompressSectionLabel(
                                step = "01",
                                title = "Choose your outcome",
                                subtitle = "Start balanced, or set the exact size you need."
                            )
                        }
                        item {
                            CompressionModeSelector(
                                selectedMode = compressionMode,
                                onModeSelected = { compressionMode = it }
                            )
                        }
                        item {
                            if (compressionMode == CompressionMode.HYBRID) {
                                CompressionTuningCard(
                                    compressionLevel = compressionLevel,
                                    compressionSliderValue = compressionSliderValue,
                                    onSliderChange = { compressionSliderValue = it }
                                )
                            } else {
                                TargetSizeCard(
                                    targetSizeText = targetSizeText,
                                    targetSizeBytes = targetSizeBytes,
                                    onTargetSizeChange = { newValue ->
                                        if (newValue.length <= 7 && newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                                            targetSizeText = newValue
                                        }
                                    }
                                )
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
                                text = if (compressionMode == CompressionMode.HYBRID) {
                                    "Balanced mode  •  quality protected"
                                } else if (targetInputValid) {
                                    "Target  •  ${targetSizeText} KB maximum"
                                } else {
                                    "Enter a target size to continue"
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = if (!targetInputValid) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }

                        AnimatedContent(
                            targetState = selectedFile == null,
                            label = "compress-action"
                        ) { isEmpty ->
                            if (isEmpty) {
                                ActionButton(
                                    text = stringResource(R.string.compress_select_pdf),
                                    onClick = {
                                        pickPdfLauncher.safeLaunch("application/pdf", context)
                                    },
                                    icon = Icons.Default.FolderOpen
                                )
                            } else {
                                ActionButton(
                                    text = "Compress PDF",
                                    onClick = {
                                        if (useCustomLocation) {
                                            val fileName = FileManager.generateOutputFileName("compressed")
                                            savePdfLauncher.safeLaunch(fileName, context)
                                        } else {
                                            compressWithDefaultLocation()
                                        }
                                    },
                                    enabled = targetInputValid,
                                    isLoading = isProcessing,
                                    icon = Icons.Default.Compress
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
                                        imageVector = Icons.Default.Compress,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                OperationProgress(
                                    progress = progress,
                                    message = "Compressing PDF..."
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Result dialog
    if (showResult) {
        ResultDialog(
            isSuccess = resultSuccess,
            title = if (resultSuccess) "Compression Complete" else "Compression Failed",
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

@Composable
private fun CompressHeroCard(hasFile: Boolean, originalSize: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
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
                    .offset(x = 42.dp, y = (-62).dp)
                    .size(176.dp)
                    .background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(100))
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 42.dp, y = 68.dp)
                    .size(118.dp)
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
                        text = "LIGHTER, NOT LESS",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    Text(
                        text = "Make room\nfor more.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (hasFile && originalSize != null) {
                            "$originalSize now. Choose how much lighter it should feel."
                        } else {
                            "Shrink the file. Keep the moments that matter."
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
                            imageVector = Icons.Default.Compress,
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
private fun CompressEmptyDropZone(onSelectPdf: () -> Unit) {
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
                    text = "Choose a PDF to compress",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "We’ll help you find the right balance",
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
private fun CompressPrivacyNote() {
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
                text = "Everything happens on-device. Your file stays private.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompressSectionLabel(step: String, title: String, subtitle: String) {
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
private fun CompressSelectedFileHeader(
    file: PdfFileInfo,
    onRemove: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
                    text = file.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Original  •  ${file.formattedSize}",
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressionModeSelector(
    selectedMode: CompressionMode,
    onModeSelected: (CompressionMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CompressionModeCard(
            title = "Balanced",
            subtitle = "Recommended",
            icon = Icons.Default.AutoAwesome,
            selected = selectedMode == CompressionMode.HYBRID,
            onClick = { onModeSelected(CompressionMode.HYBRID) },
            modifier = Modifier.weight(1f)
        )
        CompressionModeCard(
            title = "Target size",
            subtitle = "Set a limit",
            icon = Icons.Default.Straighten,
            selected = selectedMode == CompressionMode.TARGET_SIZE,
            onClick = { onModeSelected(CompressionMode.TARGET_SIZE) },
            modifier = Modifier.weight(1f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompressionModeCard(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(13.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            Surface(
                modifier = Modifier.size(36.dp),
                shape = RoundedCornerShape(11.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(9.dp)
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CompressionTuningCard(
    compressionLevel: CompressionLevel,
    compressionSliderValue: Float,
    onSliderChange: (Float) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = "How light should it be?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "A little control. No quality surprises.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = when (compressionLevel) {
                            CompressionLevel.LOW -> "Low"
                            CompressionLevel.MEDIUM -> "Balanced"
                            CompressionLevel.HIGH -> "High"
                            CompressionLevel.MAXIMUM -> "Maximum"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            Slider(
                value = compressionSliderValue,
                onValueChange = onSliderChange,
                valueRange = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Better quality",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Smaller file",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TargetSizeCard(
    targetSizeText: String,
    targetSizeBytes: Long?,
    onTargetSizeChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "What’s the limit?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Set the maximum file size you need.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = targetSizeText,
                onValueChange = onTargetSizeChange,
                label = { Text("Maximum size (KB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                isError = targetSizeText.isNotEmpty() && targetSizeBytes == null,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(15.dp),
                supportingText = if (targetSizeText.isNotEmpty() && targetSizeBytes == null) {
                    { Text("Enter a number greater than 0") }
                } else {
                    null
                }
            )
        }
    }
}

/**
 * Copy a URI to the viewer cache directory and return a FileProvider URI.
 * This is necessary for CreateDocument results where read permission is lost
 * after the save operation completes.
 */
private fun copyToViewerCache(context: android.content.Context, uri: Uri): Uri? {
    return try {
        val cacheDir = java.io.File(context.cacheDir, "viewer_cache")
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }

        // Clean old cached files (older than 24 hours)
        val oneDayAgo = System.currentTimeMillis() - (24 * 60 * 60 * 1000)
        cacheDir.listFiles()?.forEach { file ->
            if (file.lastModified() < oneDayAgo) {
                file.delete()
            }
        }

        val cachedFile = java.io.File(cacheDir, "view_${System.currentTimeMillis()}.pdf")

        context.contentResolver.openInputStream(uri)?.use { input ->
            cachedFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        if (cachedFile.exists() && cachedFile.length() > 0) {
            androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                cachedFile
            )
        } else {
            null
        }
    } catch (e: Exception) {
        android.util.Log.e("CompressScreen", "Failed to copy to viewer cache", e)
        null
    }
}
