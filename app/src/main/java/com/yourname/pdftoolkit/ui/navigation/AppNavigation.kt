package com.anonymous.imgpdf.ui.navigation

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.anonymous.imgpdf.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.semantics.Role
import androidx.core.content.FileProvider
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.compose.currentBackStackEntryAsState
import com.anonymous.imgpdf.BuildConfig
import com.anonymous.imgpdf.ui.screens.PdfViewerScreen
import com.anonymous.imgpdf.ui.screens.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Cache management: Clean up old PDF cache files to prevent unbounded growth.
 * Keeps cache under maxCacheSizeMb by deleting oldest files first.
 * NOTE: Cache limit is 20MB (not 50MB) to prevent single large PDFs from dominating cache.
 * This prevents 50MB cache for a single 50MB PDF.
 */
private fun cleanPdfCache(context: android.content.Context) {
    val cacheDir = File(context.cacheDir, "pdf_cache")
    if (!cacheDir.exists()) return

    // Reduced from 50MB to 20MB to prevent large PDFs from filling entire cache
    // and to encourage cleanup of older cached files
    val maxCacheSizeMb = 20L
    val maxCacheSizeBytes = maxCacheSizeMb * 1024 * 1024

    val files = cacheDir.listFiles()
        ?.filter { it.isFile && it.name.endsWith(".pdf") }
        ?.sortedBy { it.lastModified() } // oldest first
        ?: return

    var totalSize = files.sumOf { it.length() }

    // Aggressively delete old files if over limit
    for (file in files) {
        if (totalSize <= maxCacheSizeBytes) break
        totalSize -= file.length()
        file.delete()
        android.util.Log.d("AppNavigation", "Deleted old cache file: ${file.name} (totalCacheSize: ${totalSize / 1024 / 1024}MB)")
    }

    // Also clean up any cache files older than 12 hours for additional cleanup
    val cutoffTime = System.currentTimeMillis() - (12 * 60 * 60 * 1000) // 12 hours
    files.filter { it.lastModified() < cutoffTime }.forEach { oldFile ->
        oldFile.delete()
        android.util.Log.d("AppNavigation", "Auto-deleted aged cache file: ${oldFile.name}")
    }
}

fun safeNavigate(
    navController: NavHostController,
    route: String?,
    navOptions: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null
) {
    if (route.isNullOrBlank()) {
        android.util.Log.w("AppNavigation", "Navigation skipped: empty route")
        return
    }

    try {
        if (navOptions != null) {
            navController.navigate(route, navOptions)
        } else {
            navController.navigate(route)
        }
    } catch (e: IllegalArgumentException) {
        android.util.Log.e("AppNavigation", "Navigation failed for route: $route", e)
    } catch (e: IllegalStateException) {
        android.util.Log.e("AppNavigation", "Navigation state invalid for route: $route", e)
    }
}

fun navigateToPdfViewer(
    navController: NavHostController,
    uri: Uri?,
    name: String?
) {
    val uriString = uri?.toString().orEmpty()
    if (uriString.isBlank()) {
        android.util.Log.w("AppNavigation", "PDF viewer navigation skipped: empty URI")
        return
    }

    val encodedUri = Uri.encode(uriString)
    val encodedName = Uri.encode(name?.takeIf { it.isNotBlank() } ?: "PDF Document")
    if (encodedUri.isNullOrBlank() || encodedName.isNullOrBlank()) {
        android.util.Log.w("AppNavigation", "PDF viewer navigation skipped: invalid encoded arguments")
        return
    }

    safeNavigate(navController, Screen.PdfViewer.createRoute(encodedUri, encodedName))
}

private fun navigateToPdfTool(
    navController: NavHostController,
    tool: String,
    toolUri: Uri?,
    toolName: String?
) {
    val uriString = toolUri?.toString().orEmpty()
    if (uriString.isBlank()) {
        android.util.Log.w("AppNavigation", "Tool navigation skipped for $tool: empty URI")
        return
    }

    val encodedUri = Uri.encode(uriString)
    val encodedName = Uri.encode(toolName?.takeIf { it.isNotBlank() } ?: "PDF Document")
    if (encodedUri.isNullOrBlank() || encodedName.isNullOrBlank()) {
        android.util.Log.w("AppNavigation", "Tool navigation skipped for $tool: invalid encoded arguments")
        return
    }

    when (tool) {
        "compress" -> safeNavigate(navController, "compress?uri=$encodedUri&name=$encodedName")
        "watermark" -> safeNavigate(navController, "watermark?uri=$encodedUri&name=$encodedName")
        else -> {}
    }
}

/**
 * URI normalization helper: copies content URIs from external providers to cache for reliable access.
 * Returns FileProvider URI for external content URIs, original URI for FileProvider URIs or file:// URIs.
 * Implements cache size management to prevent 40MB+ accumulation.
 */
private suspend fun normalizeUriToCache(context: android.content.Context, uri: Uri, snackbarHostState: SnackbarHostState, sessionCacheRef: MutableState<File?>): Uri? {
    // Only process content:// URIs that aren't from our FileProvider
    if (uri.scheme != "content") return uri
    if (uri.authority == "${context.packageName}.provider") return uri

    return withContext(Dispatchers.IO) {
        try {
            // Clean cache before adding new file
            cleanPdfCache(context)

            val cacheDir = File(context.cacheDir, "pdf_cache")
            if (!cacheDir.exists()) cacheDir.mkdirs()

            // Generate unique filename based on URI for potential reuse
            val uriHash = uri.toString().hashCode().toLong().toString(16)
            val cacheFile = File(cacheDir, "pdf_${uriHash}.pdf")

            // Check if valid cached copy already exists (same size as source)
            val sourceSize = try {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (e: Exception) { -1L }

            if (cacheFile.exists() && sourceSize > 0 && cacheFile.length() == sourceSize) {
                android.util.Log.d("AppNavigation", "Reusing existing cache file: ${cacheFile.name}")
                sessionCacheRef.value = cacheFile
                return@withContext FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    cacheFile
                )
            }

            // Need to copy - use unique temp file
            val tempFile = File(cacheDir, "pdf_${System.currentTimeMillis()}_${uriHash}.pdf")

            // Try to take persistable permission first (required for media documents provider)
            try {
                val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, takeFlags)
                android.util.Log.d("AppNavigation", "Took persistable permission for: $uri")
            } catch (e: SecurityException) {
                // Permission may not be persistable, continue anyway - temporary grant might work
                android.util.Log.w("AppNavigation", "Could not take persistable permission for $uri: ${e.message}")
            } catch (e: Exception) {
                // Other errors, log and continue
                android.util.Log.w("AppNavigation", "Error taking permission for $uri: ${e.message}")
            }

            // Track if we successfully copied the file
            var copiedSuccessfully = false

            // First attempt: direct input stream open (works for most providers)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    copiedSuccessfully = true
                    android.util.Log.d("AppNavigation", "Copied URI to cache via input stream: $uri")
                }
            } catch (e: SecurityException) {
                android.util.Log.w("AppNavigation", "Direct open failed for $uri: ${e.message}")
            } catch (e: IOException) {
                android.util.Log.w("AppNavigation", "IO error opening $uri: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.w("AppNavigation", "Error opening input stream for $uri: ${e.message}")
            }

            // Second attempt: open as file descriptor (works for media documents provider)
            if (!copiedSuccessfully) {
                try {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        java.io.FileInputStream(pfd.fileDescriptor).use { fdInput ->
                            FileOutputStream(tempFile).use { output ->
                                fdInput.copyTo(output)
                            }
                        }
                        copiedSuccessfully = true
                        android.util.Log.d("AppNavigation", "Copied URI to cache via file descriptor: $uri")
                    }
                } catch (e: SecurityException) {
                    android.util.Log.w("AppNavigation", "File descriptor approach failed (SecurityException): ${e.message}")
                } catch (e: IOException) {
                    android.util.Log.w("AppNavigation", "File descriptor approach failed (IOException): ${e.message}")
                } catch (e: Exception) {
                    android.util.Log.w("AppNavigation", "File descriptor approach failed: ${e.message}")
                }
            }

            // Third attempt: query the document and get a fresh URI
            if (!copiedSuccessfully) {
                try {
                    // For media documents, try to get a fresh URI via query
                    val docId = android.provider.DocumentsContract.getDocumentId(uri)
                    val freshUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(uri, docId)

                    if (freshUri != null && freshUri != uri) {
                        android.util.Log.d("AppNavigation", "Trying fresh URI: $freshUri")
                        try {
                            context.contentResolver.openInputStream(freshUri)?.use { input ->
                                FileOutputStream(tempFile).use { output ->
                                    input.copyTo(output)
                                }
                                copiedSuccessfully = true
                                android.util.Log.d("AppNavigation", "Copied using fresh URI")
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("AppNavigation", "Fresh URI approach failed: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("AppNavigation", "Could not build fresh URI: ${e.message}")
                }
            }

            if (!copiedSuccessfully) {
                throw IllegalStateException("Cannot access PDF file. Permission may have expired or file is no longer accessible.")
            }

            // Verify the temp file was created successfully
            if (!tempFile.exists() || tempFile.length() == 0L) {
                throw IllegalStateException("Failed to copy PDF file to cache")
            }

            // Track this cache file for cleanup when viewer closes
            sessionCacheRef.value = tempFile

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                tempFile
            )
        } catch (e: Exception) {
            android.util.Log.e("AppNavigation", "Failed to normalize URI: $uri", e)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                snackbarHostState.showSnackbar("Failed to access PDF: ${e.message}")
            }
            null
        }
    }
}

/**
 * Main navigation component with Bottom Navigation (2 tabs: Tools, Files)
 * and Settings accessible via top bar icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    startDestination: String = Screen.Tools.route,
    initialPdfUri: Uri? = null,
    initialPdfName: String? = null
) {
    val context = LocalContext.current
    val actualStartDestination = when {
        initialPdfUri != null -> "pdf_viewer_direct"
        else -> startDestination
    }

    // Track current route
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // The direct viewer is already the start destination when a PDF arrives
    // through an external intent. Navigating again here created two viewer
    // entries and made opening feel stuck. Only navigate when another screen
    // receives a genuinely new intent.
    LaunchedEffect(initialPdfUri) {
        if (initialPdfUri != null && currentRoute != "pdf_viewer_direct") {
            safeNavigate(navController, "pdf_viewer_direct") {
                popUpTo(Screen.Tools.route) { inclusive = false }
                launchSingleTop = true
            }
        }
    }

    // Bottom bar is shown only on main tabs
    val showBottomBar = currentRoute in listOf(
        Screen.Tools.route,
        Screen.Files.route
    )

    // Top bar is shown only on main tabs
    val showTopBar = currentRoute in listOf(
        Screen.Tools.route,
        Screen.Files.route
    )

    // Snackbar for URI errors
    val snackbarHostState = remember { SnackbarHostState() }

    // Track session cache file for cleanup when viewer closes
    val sessionCacheFile = remember { mutableStateOf<File?>(null) }
    // Keep the main header pinned while the home content scrolls underneath it.
    val topBarScrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Box(modifier = modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(topBarScrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (showTopBar) {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFFFF6B57),
                                                    Color(0xFF7658E8)
                                                )
                                            )
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White
                                    )
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(6.dp)
                                            .size(3.dp)
                                            .clip(RoundedCornerShape(50))
                                            .background(Color.White.copy(alpha = 0.9f))
                                    )
                                }
                                Text(
                                    text = when (currentRoute) {
                                        Screen.Tools.route -> stringResource(R.string.app_name)
                                        Screen.Files.route -> stringResource(R.string.nav_tab_files)
                                        else -> stringResource(R.string.app_name)
                                    },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        actions = {
                            Surface(
                                onClick = {
                                    safeNavigate(navController, Screen.Settings.route)
                                },
                                modifier = Modifier
                                    .padding(end = 8.dp)
                                    .size(40.dp),
                                shape = RoundedCornerShape(13.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = stringResource(R.string.settings_title),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        scrollBehavior = topBarScrollBehavior
                    )
                }
            },
            bottomBar = {
                if (showBottomBar) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .height(72.dp),
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            tonalElevation = 5.dp,
                            shadowElevation = 8.dp
                        ) {
                            BottomNavigationBar(
                                navController = navController,
                                currentRoute = currentRoute
                            )
                        }
                    }
                }
            }
        ) { paddingValues ->
            NavHost(
                navController = navController,
                startDestination = actualStartDestination,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Main Tabs
                composable(Screen.Tools.route) {
                    ToolsScreen(
                        onNavigateToScreen = { screen ->
                            safeNavigate(navController, screen.route)
                        },
                        onNavigateToRoute = { route ->
                            safeNavigate(navController, route)
                        },
                        onOpenPdfViewer = { uri, name ->
                            navigateToPdfViewer(navController, uri, name)
                        }
                    )
                }

                composable(Screen.Files.route) {
                    FilesScreen(
                        onOpenPdfViewer = { uri, name ->
                            navigateToPdfViewer(navController, uri, name)
                        }
                    )
                }

                composable(Screen.Settings.route) {
                    SettingsScreen(
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
                // PDF Viewer with URI parameters - Always uses Legacy viewer for full annotation support
                composable(
                    route = Screen.PdfViewer.route,
                    arguments = listOf(
                        navArgument("uri") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("name") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "PDF Document"
                        }
                    )
                ) { backStackEntry ->
                    val uriString = backStackEntry.arguments?.getString("uri") ?: ""
                    val name = backStackEntry.arguments?.getString("name") ?: "PDF Document"
                    val rawUri = if (uriString.isNotEmpty()) Uri.parse(Uri.decode(uriString)) else null

                    // URI normalization state
                    var normalizedUri by remember(rawUri) { mutableStateOf<Uri?>(null) }
                    var isNormalizing by remember { mutableStateOf(rawUri != null) }

                    LaunchedEffect(rawUri) {
                        if (rawUri != null) {
                            isNormalizing = true
                            normalizedUri = normalizeUriToCache(context, rawUri, snackbarHostState, sessionCacheFile)
                            isNormalizing = false
                        } else {
                            normalizedUri = null
                        }
                    }

                    // Cleanup cache file when leaving viewer
                    DisposableEffect(Unit) {
                        onDispose {
                            sessionCacheFile.value?.let { cacheFile ->
                                if (cacheFile.exists()) {
                                    cacheFile.delete()
                                    android.util.Log.d("AppNavigation", "Deleted session cache file: ${cacheFile.name}")
                                }
                                sessionCacheFile.value = null
                            }
                        }
                    }

                    // Show loading while normalizing
                    if (isNormalizing) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        return@composable
                    }

                    if (rawUri != null && normalizedUri == null) {
                        LaunchedEffect(rawUri) {
                            navController.popBackStack()
                        }
                        return@composable
                    }

                    PdfViewerScreen(
                        pdfUri = normalizedUri,
                        pdfName = Uri.decode(name),
                        onNavigateBack = { navController.popBackStack() },
                        onNavigateToTool = { tool, toolUri, toolName ->
                            navigateToPdfTool(navController, tool, toolUri, toolName)
                        }
                    )
                }

                // Direct PDF viewer for intent handling - Always uses Legacy viewer
                composable("pdf_viewer_direct") {
                    // URI normalization state for initialPdfUri
                    var normalizedUri by remember(initialPdfUri) { mutableStateOf<Uri?>(null) }
                    var isNormalizing by remember { mutableStateOf(initialPdfUri != null) }

                    // Track session cache file for cleanup when viewer closes
                    val directSessionCacheFile = remember { mutableStateOf<File?>(null) }

                    LaunchedEffect(initialPdfUri) {
                        if (initialPdfUri != null) {
                            isNormalizing = true
                            normalizedUri = normalizeUriToCache(context, initialPdfUri, snackbarHostState, directSessionCacheFile)
                            isNormalizing = false
                        } else {
                            normalizedUri = null
                        }
                    }

                    // Cleanup cache file when leaving viewer
                    DisposableEffect(Unit) {
                        onDispose {
                            directSessionCacheFile.value?.let { cacheFile ->
                                if (cacheFile.exists()) {
                                    cacheFile.delete()
                                    android.util.Log.d("AppNavigation", "Deleted session cache file: ${cacheFile.name}")
                                }
                                directSessionCacheFile.value = null
                            }
                        }
                    }

                    // Show loading while normalizing
                    if (isNormalizing) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        return@composable
                    }

                    if (initialPdfUri != null && normalizedUri == null) {
                        LaunchedEffect(initialPdfUri) {
                            safeNavigate(navController, Screen.Tools.route) {
                                popUpTo("pdf_viewer_direct") { inclusive = true }
                            }
                        }
                        return@composable
                    }

                    PdfViewerScreen(
                        pdfUri = normalizedUri,
                        pdfName = initialPdfName ?: "PDF Document",
                        onNavigateBack = {
                            safeNavigate(navController, Screen.Tools.route) {
                                popUpTo("pdf_viewer_direct") { inclusive = true }
                            }
                        },
                        onNavigateToTool = { tool, toolUri, toolName ->
                            navigateToPdfTool(navController, tool, toolUri, toolName)
                        }
                    )
                }


                // PDF Tool Screens
                composable(Screen.Merge.route) {
                    MergeScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Split.route) {
                    SplitScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(
                    route = "compress?uri={uri}&name={name}",
                    arguments = listOf(
                        navArgument("uri") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("name") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val uriString = backStackEntry.arguments?.getString("uri") ?: ""
                    val name = backStackEntry.arguments?.getString("name") ?: ""
                    val uri = if (uriString.isNotEmpty()) Uri.parse(uriString) else null

                    CompressScreen(
                        onNavigateBack = { navController.popBackStack() },
                        initialUri = uri,
                        initialName = if (name.isNotEmpty()) Uri.decode(name) else null
                    )
                }

                composable(Screen.Convert.route) {
                    ConvertScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.PdfToImage.route) {
                    PdfToImageScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Extract.route) {
                    ExtractScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Rotate.route) {
                    RotateScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Security.route) {
                    SecurityScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Metadata.route) {
                    MetadataScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.PageNumber.route) {
                    PageNumberScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Organize.route) {
                    OrganizeScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Reorder.route) {
                    ReorderScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Unlock.route) {
                    UnlockScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Repair.route) {
                    RepairScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.HtmlToPdf.route) {
                    HtmlToPdfScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.ExtractText.route) {
                    ExtractTextScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(
                    route = "watermark?uri={uri}&name={name}",
                    arguments = listOf(
                        navArgument("uri") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("name") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val uriString = backStackEntry.arguments?.getString("uri") ?: ""
                    val name = backStackEntry.arguments?.getString("name") ?: ""
                    val uri = if (uriString.isNotEmpty()) Uri.parse(uriString) else null

                    WatermarkScreen(
                        onNavigateBack = { navController.popBackStack() },
                        initialUri = uri,
                        initialName = if (name.isNotEmpty()) Uri.decode(name) else null
                    )
                }

                composable(Screen.Flatten.route) {
                    FlattenScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.SignPdf.route) {
                    SignPdfScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.FillForms.route) {
                    FillFormsScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.Annotate.route) {
                    AnnotationScreen(onNavigateBack = { navController.popBackStack() })
                }

                composable(Screen.ScanToPdf.route) {
                    ScanToPdfScreen(onNavigateBack = { navController.popBackStack() })
                }

                // OCR screen - only available in Play Store flavor
                if (BuildConfig.HAS_OCR) {
                    composable(Screen.Ocr.route) {
                        OcrScreen(onNavigateBack = { navController.popBackStack() })
                    }
                }

                composable(
                    route = Screen.ImageTools.route,
                    arguments = listOf(
                        navArgument("operation") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = "resize"
                        }
                    )
                ) { backStackEntry ->
                    val operation = backStackEntry.arguments?.getString("operation") ?: "resize"
                    ImageToolsScreen(
                        initialOperation = operation,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }
            }
        }

    } // End Box
}

/**
 * Bottom Navigation Bar with 2 tabs (Home, Files).
 */
@Composable
private fun BottomNavigationBar(
    navController: NavHostController,
    currentRoute: String?
) {
    val isDarkTheme = isSystemInDarkTheme()
    val selectedBackground = if (isDarkTheme) Color(0xFF3A2425) else Color(0xFFFFE6DF)
    val selectedContent = if (isDarkTheme) Color(0xFFFF826D) else Color(0xFFFF6B57)
    val unselectedContent = if (isDarkTheme) Color(0xFFB4AFBC) else Color(0xFF777481)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        BottomNavTab.entries.forEach { tab ->
            val selected = when (tab) {
                BottomNavTab.HOME -> currentRoute == Screen.Tools.route
                BottomNavTab.FILES -> currentRoute == Screen.Files.route
            }

            val tabTitle = getTabTitle(tab)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (selected) selectedBackground else Color.Transparent)
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = {
                            val targetRoute = when (tab) {
                                BottomNavTab.HOME -> Screen.Tools.route
                                BottomNavTab.FILES -> Screen.Files.route
                            }

                            safeNavigate(navController, targetRoute) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = tab.icon,
                        contentDescription = tabTitle
                        ,
                        tint = if (selected) selectedContent else unselectedContent,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = tabTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = if (selected) selectedContent else unselectedContent,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun getTabTitle(tab: BottomNavTab): String {
    return when (tab) {
        BottomNavTab.HOME -> stringResource(R.string.nav_tab_home)
        BottomNavTab.FILES -> stringResource(R.string.nav_tab_files)
    }
}
