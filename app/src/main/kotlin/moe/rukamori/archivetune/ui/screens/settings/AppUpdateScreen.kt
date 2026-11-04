/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import moe.rukamori.archivetune.resona.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.window.core.layout.WindowSizeClass
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.LocalAnimationsDisabled
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.MarkdownText
import moe.rukamori.archivetune.updates.AppUpdateRelease
import moe.rukamori.archivetune.utils.AppUpdateInstaller
import moe.rukamori.archivetune.utils.reportException
import moe.rukamori.archivetune.viewmodels.AppUpdateEvent
import moe.rukamori.archivetune.viewmodels.AppUpdateState
import moe.rukamori.archivetune.viewmodels.AppUpdateTransfer
import moe.rukamori.archivetune.viewmodels.AppUpdateViewModel
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Date
import kotlin.math.roundToInt

const val AppUpdateRoute = "app_update"

@Composable
fun AppUpdateScreen(
    navController: NavController,
    onReleaseChecked: (AppUpdateRelease?) -> Unit,
    viewModel: AppUpdateViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val updateRequired by viewModel.updateRequired.collectAsStateWithLifecycle()
    val mandatory = updateRequired || (state as? AppUpdateState.Success)?.release?.isMandatory == true
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val releaseChecked by rememberUpdatedState(onReleaseChecked)
    val checkedRelease = (state as? AppUpdateState.Success)?.release
    LaunchedEffect(checkedRelease, state is AppUpdateState.Empty) {
        if (checkedRelease != null) releaseChecked(checkedRelease)
        else if (state is AppUpdateState.Empty) releaseChecked(null)
    }
    val durationScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]
    val installer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.externalActionFinished()
    }
    val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.installPermissionResult(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls(),
        )
    }
    LaunchedEffect(viewModel, lifecycleOwner, navController, context, uriHandler) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.events.collect { event ->
                when (event) {
                    AppUpdateEvent.NavigateBack -> navController.navigateUp()
                    is AppUpdateEvent.OpenReleasePage,
                    is AppUpdateEvent.InstallApk,
                    -> {
                        val errorRes = if (event is AppUpdateEvent.OpenReleasePage) {
                            R.string.app_update_open_release_failed
                        } else {
                            R.string.app_update_install_failed
                        }
                        try {
                            when (event) {
                                is AppUpdateEvent.OpenReleasePage -> {
                                    uriHandler.openUri(event.url)
                                    viewModel.externalActionFinished()
                                }
                                is AppUpdateEvent.InstallApk -> {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                        installPermission.launch(
                                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
                                        )
                                    } else {
                                        installer.launch(AppUpdateInstaller.createInstallIntent(context, event.file))
                                    }
                                }
                                else -> Unit
                            }
                        } catch (exception: ActivityNotFoundException) {
                            reportException(exception)
                            viewModel.externalActionFinished(errorRes)
                        } catch (exception: SecurityException) {
                            reportException(exception)
                            viewModel.externalActionFinished(errorRes)
                        } catch (exception: IllegalArgumentException) {
                            reportException(exception)
                            viewModel.externalActionFinished(errorRes)
                        }
                    }
                }
            }
        }
    }
    BackHandler { viewModel.back() }
    AppUpdateContent(
        state = state,
        mandatory = mandatory,
        animationsDisabled = LocalAnimationsDisabled.current || (durationScale?.scaleFactor ?: 1f) == 0f,
        onBack = viewModel::back,
        onCheck = { viewModel.checkForUpdate() },
        onPrimaryAction = viewModel::primaryAction,
        onCancelDownload = { viewModel.cancelDownload() },
    )
    if (!mandatory && (state as? AppUpdateState.Success)?.confirmLeave == true) {
        AlertDialog(
            onDismissRequest = viewModel::keepDownloading,
            icon = { Icon(painterResource(R.drawable.download), contentDescription = null) },
            title = { Text(stringResource(R.string.app_update_cancel_title)) },
            text = { Text(stringResource(R.string.app_update_cancel_description)) },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelDownload(leave = true) }) {
                    Text(stringResource(R.string.app_update_cancel_and_leave))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::keepDownloading) {
                    Text(stringResource(R.string.app_update_keep_downloading))
                }
            },
        )
    }
}

@Composable
private fun AppUpdateContent(
    state: AppUpdateState,
    mandatory: Boolean,
    animationsDisabled: Boolean,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onPrimaryAction: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val compactHeight = !currentWindowAdaptiveInfo().windowSizeClass
        .isHeightAtLeastBreakpoint(WindowSizeClass.HEIGHT_DP_MEDIUM_LOWER_BOUND)
    val insets = LocalPlayerAwareWindowInsets.current
    val success = state as? AppUpdateState.Success
    val listState = rememberLazyListState()
    LaunchedEffect(success?.errorRes) {
        if (success?.errorRes != null) listState.scrollToItem(0)
    }
    val transfer = success?.transfer
    val downloading = transfer as? AppUpdateTransfer.Downloading
    val header = when {
        state is AppUpdateState.Loading -> UpdateHeader(R.string.app_update_checking, R.string.app_update_checking_description, R.drawable.update)
        state is AppUpdateState.Error -> UpdateHeader(R.string.app_update_check_failed, R.string.app_update_check_failed_description, R.drawable.error, isError = true)
        state is AppUpdateState.Empty -> UpdateHeader(R.string.app_update_empty, R.string.app_update_empty_description, R.drawable.info)
        success?.errorRes != null -> UpdateHeader(
            title = when (success.errorRes) {
                R.string.app_update_download_failed -> R.string.app_update_download_failed_title
                R.string.app_update_permission_required -> R.string.app_update_permission_title
                R.string.app_update_open_release_failed -> R.string.app_update_open_release_failed_title
                else -> R.string.app_update_install_failed_title
            },
            description = success.errorRes,
            icon = R.drawable.error,
            isError = true,
        )
        downloading != null -> UpdateHeader(R.string.app_update_downloading, R.string.app_update_downloading_description, R.drawable.download)
        transfer is AppUpdateTransfer.Ready -> UpdateHeader(R.string.app_update_ready, R.string.app_update_ready_description, R.drawable.check)
        mandatory && success?.release?.isUpdateAvailable == true -> UpdateHeader(R.string.app_update_required, R.string.app_update_required_description, R.drawable.update)
        success?.release?.isUpdateAvailable == true -> UpdateHeader(R.string.app_update_available, R.string.app_update_available_description, R.drawable.new_release)
        else -> UpdateHeader(R.string.app_update_current, R.string.updates_status_current, R.drawable.check)
    }
    Scaffold(
        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            val title: @Composable () -> Unit = { Text(stringResource(R.string.app_update_title)) }
            val navigationIcon: @Composable () -> Unit = {
                if (!mandatory) {
                    FilledTonalIconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.arrow_back), stringResource(R.string.back_button_desc))
                    }
                }
            }
            val colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
                scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            )
            if (compactHeight) {
                TopAppBar(title = title, navigationIcon = navigationIcon, colors = colors)
            } else {
                MediumFlexibleTopAppBar(
                    title = title,
                    navigationIcon = navigationIcon,
                    colors = colors,
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                    contentAlignment = Alignment.Center,
                ) {
                    UpdateActions(
                        state = state,
                        mandatory = mandatory,
                        compactHeight = compactHeight,
                        onBack = onBack,
                        onCheck = onCheck,
                        onPrimaryAction = onPrimaryAction,
                        onCancelDownload = onCancelDownload,
                        modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
                .windowInsetsPadding(insets.only(WindowInsetsSides.Horizontal)),
            contentAlignment = Alignment.TopCenter,
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.widthIn(max = 680.dp).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                item(key = "status", contentType = "status") {
                    Crossfade(
                        targetState = header,
                        animationSpec = if (animationsDisabled) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(),
                        label = "appUpdateStatus",
                    ) { status -> UpdateStatusHeader(status) }
                }
                if (state is AppUpdateState.Loading || downloading != null) {
                    item(key = "progress", contentType = "progress") {
                        UpdateProgress(downloading, animationsDisabled)
                    }
                }
                item(key = "versions", contentType = "versions") {
                    UpdateVersionDetails(success?.release)
                }
                if (success?.release?.isUpdateAvailable == true) {
                    item(key = "release_notes", contentType = "release_notes") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                stringResource(R.string.app_update_release_notes),
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { heading() },
                            )
                            val notes = success.release.notes
                            if (notes.isNullOrBlank()) {
                                Text(stringResource(R.string.release_notes_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                MarkdownText(
                                    markdown = notes,
                                    modifier = Modifier.fillMaxWidth(),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateActions(
    state: AppUpdateState,
    mandatory: Boolean,
    compactHeight: Boolean,
    onBack: () -> Unit,
    onCheck: () -> Unit,
    onPrimaryAction: () -> Unit,
    onCancelDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val success = state as? AppUpdateState.Success
    val hasUpdate = success?.release?.isUpdateAvailable == true
    val downloading = success?.transfer as? AppUpdateTransfer.Downloading
    val actionLabel = when {
        mandatory && downloading != null -> R.string.app_update_downloading
        downloading?.cancelling == true -> R.string.app_update_cancelling
        downloading != null -> R.string.app_update_cancel_download
        state is AppUpdateState.Loading -> R.string.app_update_checking
        !hasUpdate -> if (state is AppUpdateState.Error) R.string.app_update_try_again else R.string.app_update_check_again
        success?.release?.canInstallInApp == false -> R.string.app_update_open_release
        success?.errorRes == R.string.app_update_permission_required -> R.string.app_update_allow_installation
        success?.transfer is AppUpdateTransfer.Ready -> R.string.app_update_install
        success?.errorRes != null -> R.string.app_update_retry_download
        else -> R.string.app_update_download_install
    }
    val icon = when {
        !hasUpdate || downloading != null -> null
        success?.transfer is AppUpdateTransfer.Ready -> R.drawable.update
        success?.release?.canInstallInApp == false -> R.drawable.arrow_forward
        else -> R.drawable.download
    }
    val primary: @Composable (Modifier) -> Unit = { buttonModifier ->
        Button(
            onClick = when {
                downloading != null -> onCancelDownload
                hasUpdate -> onPrimaryAction
                else -> onCheck
            },
            enabled = state !is AppUpdateState.Loading && success?.openingExternalAction != true &&
                downloading?.cancelling != true && !(mandatory && downloading != null),
            shapes = ButtonDefaults.shapes(),
            contentPadding = ButtonDefaults.contentPaddingFor(56.dp, hasStartIcon = icon != null),
            modifier = buttonModifier.heightIn(min = 56.dp),
        ) {
            if (icon != null) {
                Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(ButtonDefaults.iconSizeFor(56.dp)))
            }
            Text(stringResource(actionLabel), modifier = if (icon != null) Modifier.padding(start = ButtonDefaults.iconSpacingFor(56.dp)) else Modifier)
        }
    }
    val secondary: @Composable (Modifier) -> Unit = { buttonModifier ->
        TextButton(onClick = onBack, enabled = success?.openingExternalAction != true, modifier = buttonModifier) {
            Text(stringResource(when {
                hasUpdate -> R.string.later
                success != null -> R.string.app_update_done
                else -> R.string.back_button_desc
            }))
        }
    }
    if (compactHeight && downloading == null && !mandatory) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            primary(Modifier.weight(1f))
            secondary(Modifier.widthIn(max = 160.dp))
        }
    } else {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (mandatory) {
                Text(
                    stringResource(R.string.app_update_required_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            primary(Modifier.fillMaxWidth())
            if (downloading == null && !mandatory) secondary(Modifier.fillMaxWidth())
        }
    }
}

private data class UpdateHeader(
    @param:StringRes val title: Int,
    @param:StringRes val description: Int,
    @param:DrawableRes val icon: Int,
    val isError: Boolean = false,
)

@Composable
private fun UpdateStatusHeader(header: UpdateHeader) {
    Column(modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Surface(
            modifier = Modifier.size(80.dp),
            shape = MaterialShapes.SoftBurst.toShape(),
            color = if (header.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
            contentColor = if (header.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(header.icon), contentDescription = null, modifier = Modifier.size(32.dp))
            }
        }
        Text(
            stringResource(header.title),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(stringResource(header.description), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UpdateVersionDetails(release: AppUpdateRelease?) {
    val context = LocalContext.current
    val releaseDate = remember(release?.publishedAt, context.resources.configuration.locales.toLanguageTags()) {
        release?.publishedAt?.takeIf { it.isNotBlank() }?.let {
            try {
                android.text.format.DateFormat.getMediumDateFormat(context).format(Date.from(Instant.parse(it)))
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.current_version), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleLarge)
            }
            if (release?.isUpdateAvailable == true) {
                HorizontalDivider()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.new_release), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.app_update_new_version), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(release.version, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    }
                }
                if (releaseDate != null) {
                    Text(stringResource(R.string.app_update_release_date, releaseDate), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                release.downloadSizeBytes?.let { bytes ->
                    Text(stringResource(R.string.app_update_download_size, Formatter.formatShortFileSize(context, bytes)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun UpdateProgress(transfer: AppUpdateTransfer.Downloading?, animationsDisabled: Boolean) {
    val context = LocalContext.current
    val fraction = transfer?.takeIf { it.totalBytes > 0 }?.let {
        (it.downloadedBytes.toFloat() / it.totalBytes).coerceIn(0f, 1f)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = fraction ?: 0f,
        animationSpec = if (animationsDisabled) snap() else MaterialTheme.motionScheme.defaultEffectsSpec(),
        label = "appUpdateDownloadProgress",
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val progressModifier = Modifier.fillMaxWidth()
        when {
            animationsDisabled -> LinearProgressIndicator(
                progress = { fraction ?: 0f },
                modifier = if (fraction == null) progressModifier.clearAndSetSemantics {
                    progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                } else progressModifier,
            )
            fraction != null -> LinearWavyProgressIndicator(progress = { animatedProgress }, modifier = progressModifier)
            else -> LinearWavyProgressIndicator(modifier = progressModifier)
        }
        if (transfer != null) {
            val bytes = Formatter.formatShortFileSize(context, transfer.downloadedBytes)
            Text(
                text = if (fraction != null) {
                    stringResource(R.string.app_update_transfer_progress, bytes, Formatter.formatShortFileSize(context, transfer.totalBytes), (fraction * 100).roundToInt())
                } else {
                    stringResource(R.string.app_update_transfer_bytes, bytes)
                },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
