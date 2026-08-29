package com.anonymous.imgpdf.ui.screens
import com.anonymous.imgpdf.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.anonymous.imgpdf.R

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonymous.imgpdf.data.FileManager
import com.anonymous.imgpdf.data.HistoryManager
import com.anonymous.imgpdf.data.OperationType
import com.anonymous.imgpdf.data.PdfFileInfo
import com.anonymous.imgpdf.domain.operations.ImageConverter
import com.anonymous.imgpdf.domain.operations.ImageFormat
import com.anonymous.imgpdf.ui.components.*
import com.anonymous.imgpdf.util.FileOpener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Screen for converting PDF pages to images.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToImageScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageConverter = remember { ImageConverter() }

    // State
    var selectedFile by remember { mutableStateOf<PdfFileInfo?>(null) }
    var imageFormat by remember { mutableStateOf(
        when (SettingsPreferences.getDefaultImageFormat(context)) {
            DefaultImageFormat.WEBP -> ImageFormat.WEBP
            DefaultImageFormat.JPEG -> ImageFormat.JPEG
        }
    ) }
    var dpi by remember { mutableStateOf(150f) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var savedImageUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var showMultiOutputScreen by remember { mutableStateOf(false) }

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
            selectedFile = FileManager.getFileInfo(context, uri)
        }
    }

    // Open the specific saved images, or fall back to generic gallery
    fun openGallery() {
        if (savedImageUris.isNotEmpty()) {
            scope.launch(Dispatchers.IO) {
                FileOpener.openMultipleImages(context, savedImageUris)
            }
        } else {
            try {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    type = "image/*"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // Convert PDF to images
    fun convertPdfToImages() {
        val file = selectedFile ?: return

        scope.launch {
            isProcessing = true
            progress = 0f

            val uriList = mutableListOf<Uri>()
            var savedCount = 0

            val result = imageConverter.pdfToImages(
                context = context,
                inputUri = file.uri,
                format = imageFormat,
                dpi = dpi.toInt(),
                pageNumbers = null, // All pages
                outputCallback = { pageNumber, bitmap ->
                    // Clone the bitmap before saving to avoid recycling issues
                    val bitmapCopy = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
                    try {
                        // Save each bitmap to gallery
                        val savedUri = saveBitmapToGallery(
                            context = context,
                            bitmap = bitmapCopy,
                            fileName = "${file.name.removeSuffix(".pdf")}_page_$pageNumber",
                            format = imageFormat
                        )
                        if (savedUri != null) {
                            uriList.add(savedUri)
                            savedCount++
                        }
                    } finally {
                        bitmapCopy.recycle()
                    }
                },
                onProgress = { progress = it }
            )

            savedImageUris = uriList

            result.fold(
                onSuccess = { _ ->
                    resultSuccess = true
                    resultMessage = "Successfully saved $savedCount images to your gallery (Pictures/PDF Toolkit)"

                    // Record in history with isImageOutput = true
                    HistoryManager.recordSuccess(
                        context = context,
                        operationType = OperationType.PDF_TO_IMAGE,
                        inputFileName = file.name,
                        outputFileUri = uriList.firstOrNull(),
                        outputFileUris = uriList,
                        outputFileName = "${file.name.removeSuffix(".pdf")}_images.${imageFormat.extension}",
                        details = "Converted to $savedCount ${imageFormat.extension.uppercase()} images",
                        isImageOutput = true
                    )
                    selectedFile = null
                },
                onFailure = { error ->
                    resultSuccess = false
                    resultMessage = error.message ?: "Conversion failed"

                    // Record failure
                    HistoryManager.recordFailure(
                        context = context,
                        operationType = OperationType.PDF_TO_IMAGE,
                        inputFileName = file.name,
                        errorMessage = error.message
                    )
                }
            )

            isProcessing = false
            if (resultSuccess && savedImageUris.size > 1) {
                showMultiOutputScreen = true
            } else {
                showResult = true
            }
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.tool_pdf_to_images),
                subtitle = if (selectedFile == null) {
                    "Turn every page into something you can share"
                } else {
                    "Choose the look before you export"
                },
                onNavigateBack = onNavigateBack,
                actions = {
                    if (selectedFile != null) {
                        IconButton(onClick = { selectedFile = null }) {
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
                    contentPadding = PaddingValues(start = 16.dp, top = 0.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    item {
                        PdfToImageHeroCard(
                            hasFile = selectedFile != null,
                            originalSize = selectedFile?.formattedSize
                        )
                    }

                    if (selectedFile == null) {
                        item {
                            PdfToImageEmptyDropZone(
                                onSelectPdf = {
                                    pickPdfLauncher.safeLaunch(arrayOf("application/pdf"), context)
                                }
                            )
                        }
                        item {
                            PdfToImagePrivacyNote()
                        }
                    } else {
                        item {
                            PdfToImageFileHeader(
                                file = selectedFile!!,
                                onRemove = { selectedFile = null }
                            )
                        }
                        item {
                            PdfToImageSectionLabel(
                                step = "01",
                                title = "Choose the output",
                                subtitle = "Pick a format that matches where the images are going."
                            )
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ImageFormat.entries.forEach { format ->
                                    ImageFormatCard(
                                        format = format,
                                        selected = imageFormat == format,
                                        onClick = { imageFormat = format },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                        item {
                            PdfResolutionCard(
                                dpi = dpi,
                                onDpiChange = { dpi = it }
                            )
                        }
                        item {
                            PdfToImageGalleryNote()
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
                                text = "All pages  •  ${imageFormat.extension.uppercase()}  •  ${dpi.toInt()} DPI",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.align(Alignment.CenterHorizontally)
                            )
                        }
                        AnimatedContent(
                            targetState = selectedFile == null,
                            label = "pdf-to-image-action"
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
                                    text = "Save ${imageFormat.extension.uppercase()} images",
                                    onClick = { convertPdfToImages() },
                                    icon = Icons.Default.Image,
                                    isLoading = isProcessing
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
                                        imageVector = Icons.Default.Image,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.padding(15.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                OperationProgress(
                                    progress = progress,
                                    message = "Converting pages to images..."
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Result dialog with Open Gallery option (for single output)
    if (showResult) {
        AlertDialog(
            onDismissRequest = { showResult = false },
            icon = {
                Icon(
                    imageVector = if (resultSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (resultSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            },
            title = {
                Text(if (resultSuccess) "Conversion Complete" else "Conversion Failed")
            },
            text = {
                Text(resultMessage)
            },
            confirmButton = {
                if (resultSuccess && savedImageUris.isNotEmpty()) {
                    Button(
                        onClick = {
                            showResult = false
                            openGallery()
                        }
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.history_open_gallery))
                    }
                } else {
                    TextButton(onClick = { showResult = false }) {
                        Text(stringResource(R.string.action_ok))
                    }
                }
            },
            dismissButton = {
                if (resultSuccess && savedImageUris.isNotEmpty()) {
                    TextButton(onClick = { showResult = false }) {
                        Text(stringResource(R.string.action_close))
                    }
                }
            }
        )
    }

    // Multi-output result screen (for multiple images)
    if (showMultiOutputScreen && savedImageUris.isNotEmpty()) {
        MultiOutputResultScreen(
            title = stringResource(R.string.tool_pdf_to_images),
            outputUris = savedImageUris,
            isImageOutput = true,
            onNavigateBack = {
                showMultiOutputScreen = false
                savedImageUris = emptyList()
                selectedFile = null
            }
        )
    }
}

@Composable
private fun PdfToImageHeroCard(hasFile: Boolean, originalSize: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiary),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.tertiary,
                            MaterialTheme.colorScheme.primary
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
                        text = "EVERY PAGE, READY TO GO",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.3.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    Text(
                        text = "Turn pages\ninto pixels.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (hasFile && originalSize != null) {
                            "$originalSize loaded. Choose the look, then share it."
                        } else {
                            "Pull every page into your camera roll in one smooth pass."
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
                            imageVector = Icons.Default.Image,
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
private fun PdfToImageEmptyDropZone(onSelectPdf: () -> Unit) {
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
                    text = "Choose a PDF to convert",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Every page becomes a shareable image",
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
private fun PdfToImagePrivacyNote() {
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
                text = "Conversion happens on-device. Your PDF stays private.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PdfToImageSectionLabel(step: String, title: String, subtitle: String) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageFormatCard(
    format: ImageFormat,
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
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Surface(
                modifier = Modifier.size(34.dp),
                shape = RoundedCornerShape(10.dp),
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Icon(
                    imageVector = if (format == ImageFormat.PNG) Icons.Default.Layers else Icons.Default.Image,
                    contentDescription = null,
                    tint = if (selected) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(8.dp)
                )
            }
            Text(
                text = format.extension.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = when (format) {
                    ImageFormat.WEBP -> "Small"
                    ImageFormat.JPEG -> "Universal"
                    ImageFormat.PNG -> "Lossless"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PdfToImageFileHeader(file: PdfFileInfo, onRemove: () -> Unit) {
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

@Composable
private fun PdfResolutionCard(dpi: Float, onDpiChange: (Float) -> Unit) {
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
                        text = "How crisp should it be?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Higher resolution looks sharper, but uses more space.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primary
                ) {
                    Text(
                        text = "${dpi.toInt()} DPI",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
            Slider(
                value = dpi,
                onValueChange = onDpiChange,
                valueRange = 72f..300f,
                steps = 4,
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "72  /  Fast",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "300  /  HD",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PdfToImageGalleryNote() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(19.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "Saved to Pictures/PDF Toolkit in your gallery.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
        }
    }
}

/**
 * Save bitmap to device gallery using MediaStore.
 * Returns the URI of saved image or null on failure.
 * Uses WebP with 75% quality for optimal compression, PNG/JPEG with 95%.
 */
private suspend fun saveBitmapToGallery(
    context: android.content.Context,
    bitmap: Bitmap,
    fileName: String,
    format: ImageFormat
): Uri? = withContext(Dispatchers.IO) {
    try {
        val mimeType = format.mimeType
        val extension = format.extension

        // Determine compress format and quality
        val (compressFormat, quality) = when (format) {
            ImageFormat.WEBP -> {
                val webpFormat = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Bitmap.CompressFormat.WEBP_LOSSY
                } else {
                    @Suppress("DEPRECATION")
                    Bitmap.CompressFormat.WEBP
                }
                Pair(webpFormat, 78) // WebP quality 75-80 for good balance
            }
            ImageFormat.PNG -> Pair(Bitmap.CompressFormat.PNG, 100) // PNG is lossless
            ImageFormat.JPEG -> Pair(Bitmap.CompressFormat.JPEG, 92)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ - Use MediaStore
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/PDF Toolkit")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val uri = context.contentResolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return@withContext null

            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                bitmap.compress(compressFormat, quality, outputStream)
                outputStream.flush()
            }

            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, contentValues, null, null)

            uri
        } else {
            // Legacy storage
            @Suppress("DEPRECATION")
            val picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            val appDir = File(picturesDir, "PDF Toolkit")
            if (!appDir.exists()) appDir.mkdirs()

            val file = File(appDir, "$fileName.$extension")
            FileOutputStream(file).use { outputStream ->
                bitmap.compress(compressFormat, quality, outputStream)
                outputStream.flush()
            }

            // Notify gallery
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DATA, file.absolutePath)
                put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            }
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

            Uri.fromFile(file)
        }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
