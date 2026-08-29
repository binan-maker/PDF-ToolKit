package com.anonymous.imgpdf.ui.components

import androidx.compose.ui.res.stringResource
import com.anonymous.imgpdf.R

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.anonymous.imgpdf.domain.operations.PdfOrganizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

// Global or shared cache for thumbnails. Max size in bytes (e.g., 30MB)
// For simplicity, sizing by count. E.g. 100 bitmaps max.
// Compose may still be drawing a bitmap after it is evicted from this cache.
// Let the GC reclaim evicted bitmaps instead of recycling them manually, which
// can make a still-visible thumbnail crash with "trying to use a recycled bitmap".
private val thumbnailCache = LruCache<String, Bitmap>(100)

// Semaphore to limit concurrent thumbnail generations
private val renderSemaphore = Semaphore(4)

@Composable
fun PdfThumbnailGrid(
    uri: Uri,
    pageCount: Int,
    selectedPages: Set<Int>,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 3,
    multiSelect: Boolean = true,
    displayPages: List<Int> = (1..pageCount).toList(),
    rotationDegrees: (Int) -> Float = { 0f },
    topLeftBadge: @Composable ((Int, Int) -> Unit)? = null, // (pageNum, index)
    topRightBadge: @Composable ((Int, Int, Boolean) -> Unit)? = null // (pageNum, index, isSelected)
) {
    val organizer = remember { PdfOrganizer() }
    val defaultTopLeft: @Composable (Int, Int) -> Unit = { pageNum, _ ->
        Box(
            modifier = Modifier
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "$pageNum",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }

    val defaultTopRight: @Composable (Int, Int, Boolean) -> Unit = { _, _, isSel ->
        Box(
            modifier = Modifier
                .padding(6.dp)
                .size(27.dp)
                .background(
                    color = if (isSel) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        Color.Black.copy(alpha = 0.46f)
                    },
                    shape = CircleShape
                )
                .border(1.5.dp, Color.White.copy(alpha = 0.92f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (isSel) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = stringResource(R.string.cd_selected),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier,
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        itemsIndexed(displayPages) { index, pageNum ->
            val selectionKey = if (multiSelect) pageNum else index
            val isSelected = selectedPages.contains(selectionKey)

            PdfThumbnailCard(
                uri = uri,
                pageNumber = pageNum,
                organizer = organizer,
                isSelected = isSelected,
                onClick = { onPageSelected(selectionKey) },
                rotationDegrees = rotationDegrees(pageNum),
                topLeftBadge = { (topLeftBadge ?: defaultTopLeft)(pageNum, index) },
                topRightBadge = { (topRightBadge ?: defaultTopRight)(pageNum, index, isSelected) }
            )
        }
    }
}

@Composable
fun PdfThumbnailCard(
    uri: Uri,
    pageNumber: Int,
    organizer: PdfOrganizer,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    rotationDegrees: Float = 0f,
    topLeftBadge: @Composable (() -> Unit)? = null,
    topRightBadge: @Composable (() -> Unit)? = null
) {
    val context = LocalContext.current
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri, pageNumber) {
        val cacheKey = "${uri}_$pageNumber"
        val cached = thumbnailCache.get(cacheKey)
        if (cached != null && !cached.isRecycled) {
            thumbnail = cached
            return@LaunchedEffect
        }

        val loadedBitmap = withContext(Dispatchers.IO) {
            renderSemaphore.withPermit {
                try {
                    // Assuming grid width is around 100-150dp per column, scaling to pixel approx
                    organizer.getPageThumbnail(
                        context = context,
                        uri = uri,
                        pageIndex = pageNumber - 1, // 0-based for PdfRenderer
                        width = 200, // Slightly smaller to save memory
                        height = 280
                    )
                } catch (e: Exception) {
                    // Fallback to placeholder on error
                    null
                }
            }
        }

        loadedBitmap?.let { bmp ->
            thumbnailCache.put(cacheKey, bmp)
            thumbnail = bmp
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.73f)
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
                },
                shape = RoundedCornerShape(16.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (thumbnail != null && thumbnail?.isRecycled == false) {
                Image(
                    bitmap = thumbnail!!.asImageBitmap(),
                    contentDescription = "Page $pageNumber",
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        rotationZ = rotationDegrees
                    },
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }

            if (topLeftBadge != null) {
                Box(modifier = Modifier.align(Alignment.TopStart)) {
                    topLeftBadge()
                }
            }

            if (topRightBadge != null) {
                Box(modifier = Modifier.align(Alignment.TopEnd)) {
                    topRightBadge()
                }
            }
        }
    }
}
