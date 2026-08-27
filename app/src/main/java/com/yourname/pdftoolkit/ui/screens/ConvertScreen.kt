package com.yourname.pdftoolkit.ui.screens
import com.yourname.pdftoolkit.util.safeLaunch

import androidx.compose.ui.res.stringResource
import com.yourname.pdftoolkit.R

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yourname.pdftoolkit.data.FileManager
import com.yourname.pdftoolkit.data.HistoryManager
import com.yourname.pdftoolkit.data.SafUriManager
import com.yourname.pdftoolkit.data.OperationType
import com.yourname.pdftoolkit.domain.operations.ImageConverter
import com.yourname.pdftoolkit.domain.operations.PageSize
import com.yourname.pdftoolkit.ui.components.*
import com.yourname.pdftoolkit.util.CropHelper
import com.yourname.pdftoolkit.util.FileOpener
import com.yourname.pdftoolkit.util.OutputFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Data class for selected image info.
 */
private data class ImageInfo(
    val uri: Uri,
    val name: String,
    val originalIndex: Int = 0
)

/**
 * Screen for converting images to PDF.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConvertScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageConverter = remember { ImageConverter() }

    // State
    var selectedImages by remember { mutableStateOf<List<ImageInfo>>(emptyList()) }
    var pageSize by remember { mutableStateOf(PageSize.A4) }
    var quality by remember { mutableStateOf(85f) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var showResult by remember { mutableStateOf(false) }
    var resultSuccess by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf("") }
    var resultUri by remember { mutableStateOf<Uri?>(null) }
    var useCustomLocation by remember { mutableStateOf(false) }

    // Crop state - track which image index is being cropped
    var cropImageIndex by remember { mutableStateOf(-1) }
    var selectedItemIndex by remember { mutableStateOf<Int?>(null) }

    val hasOrderChanged = remember(selectedImages) {
        selectedImages.zipWithNext { a, b -> a.originalIndex > b.originalIndex }.any { it }
    }

    // Crop launcher - handles the result from uCrop activity
    val cropLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val croppedUri = CropHelper.getResultUri(result.resultCode, result.data)
            if (croppedUri != null && cropImageIndex >= 0 && cropImageIndex < selectedImages.size) {
                // Replace the original image with the cropped one
                val updatedImages = selectedImages.toMutableList()
                val originalName = updatedImages[cropImageIndex].name
                updatedImages[cropImageIndex] = ImageInfo(
                    uri = croppedUri,
                    name = "${originalName.substringBeforeLast(".")}_cropped.${originalName.substringAfterLast(".", "jpg")}"
                )
                selectedImages = updatedImages
            }
        }
        cropImageIndex = -1
    }

    // Image picker launcher
    val pickImagesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val currentSize = selectedImages.size
            val newImages = uris.mapIndexedNotNull { index, uri ->
                val info = FileManager.getFileInfo(context, uri)
                if (info != null) {
                    ImageInfo(
                        uri = uri,
                        name = info.name,
                        originalIndex = currentSize + index
                    )
                } else null
            }
            selectedImages = selectedImages + newImages
        }
    }

    // Save file launcher (for custom location)
    val savePdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri ->
        uri?.let { outputUri ->
            if (selectedImages.isNotEmpty()) {
                scope.launch {
                    isProcessing = true
                    progress = 0f

                    val outputStream = context.contentResolver.openOutputStream(outputUri)
                    if (outputStream != null) {
                        val result = imageConverter.imagesToPdf(
                            context = context,
                            imageUris = selectedImages.map { it.uri },
                            outputStream = outputStream,
                            pageSize = pageSize,
                            quality = quality.toInt(),
                            onProgress = { progress = it }
                        )

                        outputStream.close()

                        result.fold(
                            onSuccess = { count ->
                                resultSuccess = true
                                resultUri = outputUri
                                resultMessage = "Successfully converted $count images to PDF"
                                selectedImages = emptyList()
                            },
                            onFailure = { error ->
                                resultSuccess = false
                                resultMessage = error.message ?: "Conversion failed"
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

    // Function to convert with default location
    fun convertWithDefaultLocation() {
        scope.launch {
            isProcessing = true
            progress = 0f
            val imageCount = selectedImages.size

            val result = withContext(Dispatchers.IO) {
                try {
                    val fileName = FileManager.generateOutputFileName("images")
                    val outputResult = OutputFolderManager.createOutputStream(context, fileName)

                    if (outputResult != null) {
                        val convertResult = imageConverter.imagesToPdf(
                            context = context,
                            imageUris = selectedImages.map { it.uri },
                            outputStream = outputResult.outputStream,
                            pageSize = pageSize,
                            quality = quality.toInt(),
                            onProgress = { progress = it }
                        )

                        outputResult.outputStream.close()

                        convertResult.fold(
                            onSuccess = { count ->
                                Triple(true, "Successfully converted $count images to PDF\n\nSaved to: ${OutputFolderManager.getOutputFolderPath(context)}/${outputResult.outputFile.fileName}", outputResult.outputFile.contentUri)
                            },
                            onFailure = { error ->
                                outputResult.outputFile.file.delete()
                                Triple(false, error.message ?: "Conversion failed", null)
                            }
                        )
                    } else {
                        Triple(false, "Cannot create output file", null)
                    }
                } catch (e: Exception) {
                    Triple(false, e.message ?: "Conversion failed", null)
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
                    operationType = OperationType.CONVERT,
                    inputFileName = "$imageCount images",
                    outputFileUri = result.third,
                    outputFileName = "images_to_pdf.pdf",
                    details = "Converted $imageCount images to PDF"
                )
            } else if (!resultSuccess) {
                HistoryManager.recordFailure(
                    context = context,
                    operationType = OperationType.CONVERT,
                    inputFileName = "$imageCount images",
                    errorMessage = result.second
                )
            }

            if (resultSuccess) {
                selectedImages = emptyList()
            }
            isProcessing = false
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            ToolTopBar(
                title = stringResource(R.string.convert_title),
                subtitle = if (selectedImages.isEmpty()) {
                    "Turn your favorite images into one polished PDF"
                } else {
                    "${selectedImages.size} ${if (selectedImages.size == 1) "image" else "images"} ready to arrange"
                },
                onNavigateBack = onNavigateBack,
                actions = {
                    if (selectedImages.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                selectedImages = emptyList()
                                selectedItemIndex = null
                                useCustomLocation = false
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.action_clear_all),
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
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 24.dp)
                ) {
                    item(span = { GridItemSpan(3) }) {
                        ConvertHeroCard(
                            hasImages = selectedImages.isNotEmpty(),
                            imageCount = selectedImages.size
                        )
                    }

                    if (selectedImages.isEmpty()) {
                        item(span = { GridItemSpan(3) }) {
                            ConvertEmptyDropZone(
                                onSelectImages = {
                                    pickImagesLauncher.safeLaunch(arrayOf("image/*"), context)
                                }
                            )
                        }
                        item(span = { GridItemSpan(3) }) {
                            ConvertPrivacyNote()
                        }
                    } else {
                        // Image list header
                        item(span = { GridItemSpan(3) }) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "Selected images",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (hasOrderChanged) {
                                        Text(
                                            text = "Order modified • reset anytime",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Row {
                                    if (hasOrderChanged) {
                                        TextButton(
                                            onClick = {
                                                selectedImages = selectedImages.sortedBy { it.originalIndex }
                                                selectedItemIndex = null
                                            }
                                        ) {
                                            Text(stringResource(R.string.action_reset))
                                        }
                                    }
                                    Text(
                                        text = "${selectedImages.size} ${if (selectedImages.size == 1) "image" else "images"}",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }

                        // Image list
                        itemsIndexed(
                            items = selectedImages,
                            key = { index, image -> "${image.uri}-${image.originalIndex}" }
                        ) { index, image ->
                            ImagePreviewCard(
                                image = image,
                                currentPosition = index + 1,
                                isSelected = selectedItemIndex == index,
                                canMoveUp = index > 0,
                                canMoveDown = index < selectedImages.lastIndex,
                                onRemove = {
                                    selectedImages = selectedImages.toMutableList().apply {
                                        removeAt(index)
                                    }
                                    if (selectedItemIndex == index) {
                                        selectedItemIndex = null
                                    } else if (selectedItemIndex != null && selectedItemIndex!! > index) {
                                        selectedItemIndex = selectedItemIndex!! - 1
                                    }
                                },
                                onCrop = {
                                    // Launch crop for this image
                                    cropImageIndex = index
                                    val cropIntent = CropHelper.getCropIntent(
                                        context = context,
                                        sourceUri = image.uri,
                                        aspectRatio = null, // Free crop
                                        maxSize = 2048
                                    )
                                    cropLauncher.safeLaunch(cropIntent, context)
                                },
                                onSelect = {
                                    selectedItemIndex = if (selectedItemIndex == index) null else index
                                },
                                onMoveUp = {
                                    if (index > 0) {
                                        selectedImages = selectedImages.toMutableList().apply {
                                            val item = removeAt(index)
                                            add(index - 1, item)
                                        }
                                        selectedItemIndex = index - 1
                                    }
                                },
                                onMoveDown = {
                                    if (index < selectedImages.lastIndex) {
                                        selectedImages = selectedImages.toMutableList().apply {
                                            val item = removeAt(index)
                                            add(index + 1, item)
                                        }
                                        selectedItemIndex = index + 1
                                    }
                                },
                                onMoveToFirst = {
                                    if (index > 0) {
                                        selectedImages = selectedImages.toMutableList().apply {
                                            val item = removeAt(index)
                                            add(0, item)
                                        }
                                        selectedItemIndex = 0
                                    }
                                },
                                onMoveToLast = {
                                    if (index < selectedImages.lastIndex) {
                                        selectedImages = selectedImages.toMutableList().apply {
                                            val item = removeAt(index)
                                            add(this.size, item)
                                        }
                                        selectedItemIndex = selectedImages.lastIndex
                                    }
                                }
                            )
                        }

                        // Add more button
                        item(span = { GridItemSpan(3) }) {
                            OutlinedButton(
                                onClick = {
                                    pickImagesLauncher.safeLaunch(arrayOf("image/*"), context)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(18.dp),
                                border = BorderStroke(
                                    1.5.dp,
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                                ),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.primary
                                )
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.action_add_more_images))
                            }
                        }

                        // Settings section
                        item(span = { GridItemSpan(3) }) {
                            ConvertSectionLabel(
                                step = "02",
                                title = "Make it yours",
                                subtitle = "Choose the page shape and image quality before export."
                            )
                        }

                        // Page size selection
                        item(span = { GridItemSpan(3) }) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Text(
                                        text = "Page Size",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Medium
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        PageSize.entries.forEach { size ->
                                            FilterChip(
                                                selected = pageSize == size,
                                                onClick = { pageSize = size },
                                                label = { Text(size.name.replace("_", " ")) }
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // Quality slider
                        item(span = { GridItemSpan(3) }) {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(20.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface
                                ),
                                border = BorderStroke(
                                    1.dp,
                                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
                                )
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Image Quality",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "${quality.toInt()}%",
                                            style = MaterialTheme.typography.labelLarge,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Slider(
                                        value = quality,
                                        onValueChange = { quality = it },
                                        valueRange = 20f..100f,
                                        steps = 7
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = "Smaller file",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Text(
                                            text = "Better quality",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Progress overlay
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
                                            imageVector = Icons.Default.PictureAsPdf,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(15.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    OperationProgress(
                                        progress = progress,
                                        message = "Creating your PDF..."
                                    )
                                }
                            }
                        }
                    }
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
                    if (selectedImages.isEmpty()) {
                        ActionButton(
                            text = "Choose images",
                            onClick = {
                                pickImagesLauncher.safeLaunch(arrayOf("image/*"), context)
                            },
                            icon = Icons.Default.Image,
                        )
                    } else {
                        // Save location option
                        SaveLocationSelector(
                            useCustomLocation = useCustomLocation,
                            onUseCustomLocationChange = { useCustomLocation = it }
                        )

                        ActionButton(
                            text = "Create PDF",
                            onClick = {
                                if (useCustomLocation) {
                                    val fileName = FileManager.generateOutputFileName("images")
                                    savePdfLauncher.safeLaunch(fileName, context)
                                } else {
                                    convertWithDefaultLocation()
                                }
                            },
                            icon = Icons.Default.Transform,
                            isLoading = isProcessing,
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
            title = if (resultSuccess) "Conversion Complete" else "Conversion Failed",
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
private fun ConvertHeroCard(hasImages: Boolean, imageCount: Int) {
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
                        text = "MOMENTS, TOGETHER",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp),
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.78f)
                    )
                    Text(
                        text = "One story.\nOne PDF.",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        lineHeight = 32.sp,
                        color = Color.White
                    )
                    Text(
                        text = if (hasImages) {
                            "$imageCount ${if (imageCount == 1) "image" else "images"} selected. Arrange them, then make it shareable."
                        } else {
                            "Bring your images together in a clean, easy-to-share document."
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
                            imageVector = Icons.Default.Image,
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
                            imageVector = Icons.Default.PictureAsPdf,
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
private fun ConvertEmptyDropZone(onSelectImages: () -> Unit) {
    Surface(
        onClick = onSelectImages,
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
                    imageVector = Icons.Default.Collections,
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
                    text = stringResource(R.string.convert_no_images_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.convert_no_images_subtitle),
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
                    contentDescription = "Choose images",
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun ConvertPrivacyNote() {
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
                text = "Everything happens on-device. Your images stay private.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConvertSectionLabel(step: String, title: String, subtitle: String) {
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
private fun ImagePreviewCard(
    image: ImageInfo,
    currentPosition: Int,
    isSelected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onSelect: () -> Unit,
    onRemove: () -> Unit,
    onCrop: (() -> Unit)? = null,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToFirst: () -> Unit,
    onMoveToLast: () -> Unit
) {
    val hasChanged = image.originalIndex + 1 != currentPosition

    Card(
        onClick = onSelect,
        modifier = Modifier
            .aspectRatio(1f),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AsyncImage(
                model = image.uri,
                contentDescription = image.name,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Crop
            )

            // Position badge (top-left)
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                shape = CircleShape,
                color = if (hasChanged) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                }
            ) {
                Text(
                    text = "$currentPosition",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (hasChanged) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            // Original page number (if different)
            if (hasChanged) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp),
                    shape = RoundedCornerShape(4.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
                ) {
                    Text(
                        text = "was ${image.originalIndex + 1}",
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Move controls (when selected)
            if (isSelected) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.95f)
                        )
                        .padding(2.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Row 1: Actions
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (onCrop != null) {
                            IconButton(onClick = onCrop, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Crop, contentDescription = stringResource(R.string.action_crop), modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_remove), tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                        }
                    }
                    // Row 2: Navigation
                    Row(
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = onMoveToFirst, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardDoubleArrowUp, contentDescription = stringResource(R.string.action_move_first), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.action_move_up), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.action_move_down), modifier = Modifier.size(18.dp))
                        }
                        IconButton(onClick = onMoveToLast, enabled = canMoveDown, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.KeyboardDoubleArrowDown, contentDescription = stringResource(R.string.action_move_last), modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}
