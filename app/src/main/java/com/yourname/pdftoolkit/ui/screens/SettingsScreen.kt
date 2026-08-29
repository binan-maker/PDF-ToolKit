package com.anonymous.imgpdf.ui.screens

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.anonymous.imgpdf.BuildConfig
import com.anonymous.imgpdf.ui.components.LicensesDialog
import com.anonymous.imgpdf.util.CacheManager
import com.anonymous.imgpdf.util.ThemeManager
import com.anonymous.imgpdf.util.ThemeMode
import com.anonymous.imgpdf.util.PdfTools
import com.anonymous.imgpdf.util.LanguageManager
import com.anonymous.imgpdf.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Image format options for default setting.
 */
enum class DefaultImageFormat(val displayName: String, val extension: String) {
    WEBP("WebP (Recommended)", "webp"),
    JPEG("JPEG", "jpg")
}

private data class SettingsColors(
    val canvas: Color,
    val card: Color,
    val cardStrong: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val accentSoft: Color,
    val iconColors: List<Color>
) {
    companion object {
        fun forTheme(isDark: Boolean): SettingsColors {
            return if (isDark) {
                SettingsColors(
                    canvas = Color(0xFF111116),
                    card = Color(0xFF1A1A21),
                    cardStrong = Color(0xFF24242D),
                    ink = Color(0xFFF5F3F7),
                    muted = Color(0xFFB4AFBC),
                    accent = Color(0xFFFF826D),
                    accentSoft = Color(0xFF3A2425),
                    iconColors = listOf(Color(0xFFFF826D), Color(0xFFB69CFF), Color(0xFF62D6C1), Color(0xFFFFC46B))
                )
            } else {
                SettingsColors(
                    canvas = Color(0xFFF8F7FB),
                    card = Color(0xFFFFFFFF),
                    cardStrong = Color(0xFF1E1D29),
                    ink = Color(0xFF1E1D29),
                    muted = Color(0xFF777481),
                    accent = Color(0xFFFF6B57),
                    accentSoft = Color(0xFFFFE6DF),
                    iconColors = listOf(Color(0xFFFF6B57), Color(0xFF7658E8), Color(0xFF159A87), Color(0xFFCA7B00))
                )
            }
        }
    }
}

/**
 * Settings preferences manager.
 */
object SettingsPreferences {
    private const val PREFS_NAME = "pdf_toolkit_settings"
    private const val KEY_COMPRESSION_QUALITY = "compression_quality"
    private const val KEY_IMAGE_FORMAT = "default_image_format"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCompressionQuality(context: Context): Int {
        return getPrefs(context).getInt(KEY_COMPRESSION_QUALITY, 75)
    }

    fun setCompressionQuality(context: Context, quality: Int) {
        getPrefs(context).edit().putInt(KEY_COMPRESSION_QUALITY, quality).apply()
    }

    fun getDefaultImageFormat(context: Context): DefaultImageFormat {
        val formatName = getPrefs(context).getString(KEY_IMAGE_FORMAT, DefaultImageFormat.WEBP.name)
        return try {
            DefaultImageFormat.valueOf(formatName ?: DefaultImageFormat.WEBP.name)
        } catch (e: Exception) {
            DefaultImageFormat.WEBP
        }
    }

    fun setDefaultImageFormat(context: Context, format: DefaultImageFormat) {
        getPrefs(context).edit().putString(KEY_IMAGE_FORMAT, format.name).apply()
    }
}

/**
 * Comprehensive Settings Screen with organized sections.
 * Includes: Default compression quality, Default image format, Cache cleanup, About/Privacy/License
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDarkTheme = isSystemInDarkTheme()
    val settingsColors = remember(isDarkTheme) { SettingsColors.forTheme(isDarkTheme) }

    var cacheSize by remember { mutableStateOf("Calculating...") }
    var isClearing by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showFeatureRequestDialog by remember { mutableStateOf(false) }
    var showImageFormatDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLanguageScreen by remember { mutableStateOf(false) }

    // Settings state
    var compressionQuality by remember { mutableStateOf(SettingsPreferences.getCompressionQuality(context)) }
    var defaultImageFormat by remember { mutableStateOf(SettingsPreferences.getDefaultImageFormat(context)) }

    // Theme state
    val currentTheme by ThemeManager.getThemeMode(context).collectAsState(initial = ThemeMode.SYSTEM)

    // Language state
    val currentLanguage by LanguageManager.getLanguageFlow(context).collectAsState(initial = LanguageManager.getCurrentLanguage())

    // Calculate cache size on screen load
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            cacheSize = CacheManager.getFormattedCacheSize(context)
        }
    }

    if (showLanguageScreen) {
        LanguageSelectionScreen(
            currentLanguage = currentLanguage,
            onNavigateBack = { showLanguageScreen = false }
        )
        return
    }

    Scaffold(
        containerColor = settingsColors.canvas,
        topBar = {
            SettingsTopBar(
                title = stringResource(R.string.settings_title),
                onNavigateBack = onNavigateBack,
                colors = settingsColors
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(settingsColors.canvas)
                .padding(paddingValues),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)
        ) {
            item {
                SettingsHero(colors = settingsColors)
            }

            // Quality Settings Section
            item {
                SettingsSectionHeader(
                    title = stringResource(R.string.settings_section_quality),
                    topPadding = 0.dp
                )
            }

            // Default Compression Quality
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = CardDefaults.cardColors(containerColor = settingsColors.card),
                    border = BorderStroke(1.dp, settingsColors.muted.copy(alpha = 0.12f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = settingsColors.accentSoft,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HighQuality,
                                    contentDescription = stringResource(R.string.cd_compression_quality),
                                    modifier = Modifier.padding(9.dp),
                                    tint = settingsColors.accent
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.settings_compression_quality),
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = settingsColors.ink
                                )
                                Text(
                                    text = "${compressionQuality}% - ${getQualityDescription(compressionQuality)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = settingsColors.muted
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Slider(
                            value = compressionQuality.toFloat(),
                            onValueChange = {
                                compressionQuality = it.toInt()
                            },
                            onValueChangeFinished = {
                                SettingsPreferences.setCompressionQuality(context, compressionQuality)
                            },
                            valueRange = 30f..100f,
                            steps = 6,
                            colors = SliderDefaults.colors(
                                thumbColor = settingsColors.accent,
                                activeTrackColor = settingsColors.accent,
                                inactiveTrackColor = settingsColors.muted.copy(alpha = 0.22f)
                            ),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        Text(
                            text = stringResource(R.string.settings_quality_range),
                            style = MaterialTheme.typography.labelSmall,
                            color = settingsColors.muted,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }
                }
            }

            // Default Image Format
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_image_format),
                    subtitle = defaultImageFormat.displayName,
                    icon = Icons.Default.Image,
                    onClick = { showImageFormatDialog = true }
                )
            }

            // Appearance Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_appearance))
            }

            // Theme Mode
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_theme_mode),
                    subtitle = currentTheme.displayName,
                    icon = Icons.Default.Palette,
                    onClick = { showThemeDialog = true }
                )
            }

            // Language Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_language))
            }

            // Language Selector
            item {
                SettingsItem(
                    title = stringResource(R.string.settings_select_language),
                    subtitle = LanguageManager.getLanguageDisplayName(currentLanguage),
                    icon = Icons.Default.Language,
                    onClick = { showLanguageScreen = true }
                )
            }

            // Storage Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_storage))
            }

            item {
                SettingsItem(
                    title = stringResource(R.string.settings_cache_size),
                    subtitle = cacheSize,
                    icon = Icons.Default.Storage,
                    onClick = { showClearCacheDialog = true }
                ) {
                    if (isClearing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        TextButton(onClick = { showClearCacheDialog = true }) {
                            Text(stringResource(R.string.action_clear))
                        }
                    }
                }
            }

            // About Section
            item {
                SettingsSectionHeader(title = stringResource(R.string.settings_section_about))
            }

            item {
                SettingsItem(
                    title = stringResource(R.string.settings_version),
                    subtitle = "V - 1 (0.0.1)",
                    icon = Icons.Default.Info,
                    onClick = { showAboutDialog = true }
                )
            }

            if (false) {
                item {
                    SettingsItem(
                        title = stringResource(R.string.settings_privacy_policy),
                        subtitle = stringResource(R.string.settings_privacy_policy_subtitle),
                        icon = Icons.Default.PrivacyTip,
                        onClick = {
                            openPrivacyPolicy(context)
                        }
                    )
                }
            }

        }
    }

    // Licenses Dialog
    if (showLicensesDialog) {
        LicensesDialog(
            onDismiss = { showLicensesDialog = false }
        )
    }

    // Theme Selection Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            icon = { Icon(Icons.Default.Palette, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_theme_mode)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { theme ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        ThemeManager.setThemeMode(context, theme)
                                    }
                                    showThemeDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = currentTheme == theme,
                                onClick = {
                                    scope.launch {
                                        ThemeManager.setThemeMode(context, theme)
                                    }
                                    showThemeDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = theme.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = when (theme) {
                                        ThemeMode.LIGHT -> "Always use light theme"
                                        ThemeMode.DARK -> "Always use dark theme"
                                        ThemeMode.SYSTEM -> "Follow system settings"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Image Format Selection Dialog
    if (showImageFormatDialog) {
        AlertDialog(
            onDismissRequest = { showImageFormatDialog = false },
            icon = { Icon(Icons.Default.Image, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_image_format)) },
            text = {
                Column {
                    DefaultImageFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    defaultImageFormat = format
                                    SettingsPreferences.setDefaultImageFormat(context, format)
                                    showImageFormatDialog = false
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = defaultImageFormat == format,
                                onClick = {
                                    defaultImageFormat = format
                                    SettingsPreferences.setDefaultImageFormat(context, format)
                                    showImageFormatDialog = false
                                }
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = format.displayName,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                if (format == DefaultImageFormat.WEBP) {
                                    Text(
                                        text = "Best compression, smaller files",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "Universal compatibility",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImageFormatDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // Clear Cache Dialog
    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { showClearCacheDialog = false },
            icon = { Icon(Icons.Default.DeleteSweep, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_clear_cache)) },
            text = {
                Text(stringResource(R.string.settings_clear_cache_message))
            },
            confirmButton = {
                Button(
                    onClick = {
                        isClearing = true
                        showClearCacheDialog = false
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                CacheManager.clearAllCache(context)
                            }
                            cacheSize = CacheManager.getFormattedCacheSize(context)
                            isClearing = false
                            Toast.makeText(context, context.getString(R.string.settings_cache_cleared), Toast.LENGTH_SHORT).show()
                        }
                    }
                ) {
                    Text(stringResource(R.string.action_clear))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearCacheDialog = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            icon = { Icon(Icons.Default.Info, contentDescription = null) },
            title = { Text(stringResource(R.string.settings_about_title)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(stringResource(R.string.settings_about_description))

                    Divider()

                    Text(
                        text = stringResource(R.string.settings_about_features),
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(stringResource(R.string.settings_about_feature_1))
                    Text(stringResource(R.string.settings_about_feature_2))
                    Text(stringResource(R.string.settings_about_feature_3))
                    Text(stringResource(R.string.settings_about_feature_4))
                    Text(stringResource(R.string.settings_about_feature_5))

                    Divider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_about_version), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(BuildConfig.VERSION_NAME)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_about_build), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(BuildConfig.VERSION_CODE.toString())
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stringResource(R.string.settings_about_made_with), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.settings_about_kotlin_compose))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text(stringResource(R.string.action_close))
                }
            }
        )
    }

    // Feature Request Dialog
    if (showFeatureRequestDialog) {
        FeatureRequestDialog(
            onDismiss = { showFeatureRequestDialog = false },
            onSubmit = { featureText ->
                sendFeatureRequest(context, featureText)
                showFeatureRequestDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTopBar(
    title: String,
    onNavigateBack: () -> Unit,
    colors: SettingsColors
) {
    TopAppBar(
        navigationIcon = {
            Surface(
                onClick = onNavigateBack,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .size(42.dp),
                shape = RoundedCornerShape(14.dp),
                color = colors.card,
                border = BorderStroke(1.dp, colors.ink.copy(alpha = 0.08f))
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.action_back),
                        tint = colors.ink,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink
                )
                Text(
                    text = stringResource(R.string.settings_topbar_subtitle),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.muted
                )
            }
        },
        windowInsets = WindowInsets(0, 0, 0, 0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = colors.canvas
        )
    )
}

@Composable
private fun SettingsHero(colors: SettingsColors) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(containerColor = colors.cardStrong),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            colors.cardStrong,
                            colors.cardStrong.copy(alpha = 0.92f),
                            colors.iconColors[1].copy(alpha = 0.72f)
                        )
                    )
                )
                .padding(22.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = colors.accent.copy(alpha = 0.18f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = null,
                            tint = colors.accent,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = stringResource(R.string.settings_hero_eyebrow),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.1.sp,
                            color = colors.accent
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        text = stringResource(R.string.settings_hero_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = stringResource(R.string.settings_hero_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f)
                    )
                }
            }
        }
    }
}

@Composable
private fun getQualityDescription(quality: Int): String {
    return when {
        quality >= 90 -> stringResource(R.string.compress_quality_maximum)
        quality >= 75 -> stringResource(R.string.compress_quality_high)
        quality >= 60 -> stringResource(R.string.compress_quality_balanced)
        quality >= 45 -> stringResource(R.string.compress_quality_compressed)
        else -> stringResource(R.string.compress_quality_maximum_compression)
    }
}

@Composable
private fun SettingsSectionHeader(
    title: String,
    topPadding: androidx.compose.ui.unit.Dp = 24.dp
) {
    val isDarkTheme = isSystemInDarkTheme()
    val colors = remember(isDarkTheme) { SettingsColors.forTheme(isDarkTheme) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(18.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.accent)
        )
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = colors.accent,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    trailing: @Composable (() -> Unit)? = null
) {
    val isDarkTheme = isSystemInDarkTheme()
    val colors = remember(isDarkTheme) { SettingsColors.forTheme(isDarkTheme) }
    val iconColor = colors.iconColors[title.length % colors.iconColors.size]

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = colors.card),
        border = BorderStroke(1.dp, iconColor.copy(alpha = 0.15f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = iconColor.copy(alpha = 0.14f),
                modifier = Modifier.size(42.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    modifier = Modifier
                        .padding(9.dp),
                    tint = iconColor
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = colors.ink
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    maxLines = 1
                )
            }

            if (trailing != null) {
                trailing()
            } else {
                Surface(
                    shape = CircleShape,
                    color = iconColor.copy(alpha = 0.10f),
                    modifier = Modifier.size(30.dp)
                ) {
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.padding(6.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureRequestDialog(
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var featureText by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("Feature") }
    var showCategoryMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Lightbulb, contentDescription = null) },
        title = { Text(stringResource(R.string.feature_request_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(stringResource(R.string.feature_request_description))

                // Category selector
                ExposedDropdownMenuBox(
                    expanded = showCategoryMenu,
                    onExpandedChange = { showCategoryMenu = it }
                ) {
                    OutlinedTextField(
                        value = category,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(R.string.feature_request_category)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showCategoryMenu)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor()
                    )
                    ExposedDropdownMenu(
                        expanded = showCategoryMenu,
                        onDismissRequest = { showCategoryMenu = false }
                    ) {
                        listOf("Feature", "Improvement", "UI/UX", "Other").forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option) },
                                onClick = {
                                    category = option
                                    showCategoryMenu = false
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = featureText,
                    onValueChange = { featureText = it },
                    label = { Text(stringResource(R.string.feature_request_idea)) },
                    placeholder = { Text(stringResource(R.string.feature_request_placeholder)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp),
                    maxLines = 6
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSubmit("[$category] $featureText") },
                enabled = featureText.isNotBlank()
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.action_submit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// Helper functions for intents

/**
 * Developer email for support requests.
 */
private const val DEVELOPER_EMAIL = "developerncn29@gmail.com"

private fun sendFeatureRequest(context: Context, featureText: String) {
    val deviceInfo = """
        
        ---
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
    """.trimIndent()

    val emailBody = "$featureText\n$deviceInfo"

    try {
        // Restrict to Gmail only
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            setPackage("com.google.android.gm") // Gmail package
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "[Feature Request] PDF Toolkit")
            putExtra(Intent.EXTRA_TEXT, emailBody)
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        // Gmail not installed
        Toast.makeText(
            context,
            "Gmail app is required. Please install Gmail or send feedback to $DEVELOPER_EMAIL",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun sendBugReport(context: Context) {
    val deviceInfo = """
        Bug Description:
        [Please describe the issue you encountered]
        
        Steps to Reproduce:
        1. 
        2. 
        3. 
        
        Expected Behavior:
        [What did you expect to happen?]
        
        Actual Behavior:
        [What actually happened?]
        
        ---
        Device: ${Build.MANUFACTURER} ${Build.MODEL}
        Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
        App Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
    """.trimIndent()

    try {
        // Restrict to Gmail only
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            setPackage("com.google.android.gm") // Gmail package
            putExtra(Intent.EXTRA_EMAIL, arrayOf(DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "[Bug Report] PDF Toolkit")
            putExtra(Intent.EXTRA_TEXT, deviceInfo)
        }

        context.startActivity(intent)
    } catch (e: Exception) {
        // Gmail not installed
        Toast.makeText(
            context,
            "Gmail app is required. Please install Gmail or send bug reports to $DEVELOPER_EMAIL",
            Toast.LENGTH_LONG
        ).show()
    }
}

private fun openPlayStore(context: Context) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.anonymous.imgpdf"))
        )
    } catch (e: Exception) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.anonymous.imgpdf"))
        )
    }
}

private fun openPrivacyPolicy(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://karna14314.github.io/Pdf_Tools/"))
    context.startActivity(intent)
}
