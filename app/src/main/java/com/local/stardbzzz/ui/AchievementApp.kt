@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
)

package com.local.stardbzzz.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.local.stardbzzz.data.local.CompletionFilter
import com.local.stardbzzz.data.local.SortMode
import com.local.stardbzzz.data.local.SyncStatus
import com.local.stardbzzz.data.repository.AchievementScreenState
import com.local.stardbzzz.data.repository.AchievementUiModel
import com.local.stardbzzz.data.update.AppUpdater
import com.local.stardbzzz.scan.ScanCoordinator
import com.local.stardbzzz.R
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.BitmapFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

@Composable
fun AchievementApp(viewModel: AchievementViewModel, updater: AppUpdater, scanCoordinator: ScanCoordinator) {
    val state by viewModel.screenState.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var selectedAchievement by remember { mutableStateOf<AchievementUiModel?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showScan by rememberSaveable { mutableStateOf(false) }
    val openScanRequested by scanCoordinator.openScanScreen.collectAsStateWithLifecycle()

    LaunchedEffect(openScanRequested) {
        if (openScanRequested) {
            showScan = true
            scanCoordinator.consumeOpenScanScreen()
        }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshIfStale()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }

    UpdateDialogs(updater)

    if (showScan) {
        ScanScreen(coordinator = scanCoordinator, onBack = { showScan = false })
        return
    }

    if (showSettings) {
        SettingsScreen(updater = updater, onBack = { showSettings = false }, onOpenScan = { showScan = true })
        return
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = status.hasCredentials,
        drawerContent = {
            CategoryDrawerContent(
                state = state,
                onCategorySelected = { primary, category ->
                    viewModel.updateCategorySelection(primary, category)
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
    Scaffold(
        modifier = Modifier.openDrawerOnHorizontalSwipe(
            enabled = status.hasCredentials && drawerState.isClosed,
            onOpen = { scope.launch { drawerState.open() } },
        ),
        topBar = {
            AchievementTopBar(
                title = state.selectedCategory().ifBlank { state.selectedPrimaryCategory().ifBlank { "绝区零成就" } },
                isRefreshing = status.isRefreshing,
                onOpenCategories = { scope.launch { drawerState.open() } },
                onRefresh = viewModel::refresh,
                onOpenSettings = { showSettings = true },
                onLogout = {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                    viewModel.logout()
                },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (!status.hasCredentials) {
                LoginScreen(
                    isLoading = status.isRefreshing,
                    error = status.error,
                    onLogin = viewModel::login,
                    onCookieLogin = viewModel::loginWithSessionCookie,
                    onDismissError = viewModel::clearError,
                )
            } else {
                AchievementHome(
                    state = state,
                    isRefreshing = status.isRefreshing,
                    error = status.error,
                    onSearchChange = viewModel::updateSearch,
                    onPrimaryCategoryChange = viewModel::updatePrimaryCategory,
                    onCategoryChange = viewModel::updateCategory,
                    onCompletionFilterChange = viewModel::updateCompletionFilter,
                    onVersionChange = viewModel::updateVersion,
                    onToggle = viewModel::toggleAchievement,
                    onSelect = { selectedAchievement = it },
                    onListIndexChange = viewModel::updateFirstVisibleItemIndex,
                    onDismissError = viewModel::clearError,
                )
                selectedAchievement?.let { achievement ->
                    AchievementDetailDialog(
                        achievement = achievement,
                        onDismiss = { selectedAchievement = null },
                        onToggle = { completed ->
                            selectedAchievement = achievement.copy(
                                isCompleted = completed,
                            )
                            viewModel.toggleAchievement(achievement.id, completed)
                        },
                        onPinnedChange = { pinned ->
                            selectedAchievement = achievement.copy(isPinned = pinned)
                            viewModel.updatePinned(achievement.id, pinned)
                        },
                    )
                }
            }
        }
    }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AchievementTopBar(
    title: String,
    isRefreshing: Boolean,
    onOpenCategories: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
) {
    TopAppBar(
        title = title@{
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            return@title
            Column {
                Text("绝区零成就")
                Text(
                    text = "stardb.gg tracker",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenCategories) {
                Icon(Icons.Default.Menu, contentDescription = "分类")
            }
        },
        actions = {
            if (isRefreshing) {
                CircularProgressIndicator(
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
            IconButton(onClick = onLogout) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "退出登录")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

@Composable
private fun CategoryDrawerContent(
    state: AchievementScreenState,
    onCategorySelected: (String, String) -> Unit,
) {
    val selectedPrimary = state.selectedPrimaryCategory()
    val selectedCategory = state.selectedCategory()
    ModalDrawerSheet(modifier = Modifier.fillMaxWidth(0.67f)) {
        Text(
            text = "分类",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        )
        HorizontalDivider()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item(key = "pinned-category") {
                val selected = selectedPrimary == PinnedCategory
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onCategorySelected(PinnedCategory, PinnedCategory) },
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 11.dp),
                    ) {
                        Icon(Icons.Default.PushPin, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = PinnedCategory,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
            item(key = "pinned-divider") { HorizontalDivider() }
            state.categoryGroups.forEach { (primary, categories) ->
                item(key = "primary-$primary") {
                    Text(
                        text = primary,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                    )
                }
                items(categories, key = { category -> "$primary-$category" }) { category ->
                    val selected = primary == selectedPrimary && category == selectedCategory
                    Surface(
                        color = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategorySelected(primary, category) },
                    ) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginScreen(
    isLoading: Boolean,
    error: String?,
    onLogin: (String, String) -> Unit,
    onCookieLogin: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var showWebLogin by remember { mutableStateOf(true) }
    var webLoginMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = "登录 stardb.gg",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = "在网页中登录后会自动同步。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Spacer(Modifier.height(12.dp))
        if (showWebLogin) {
            Button(
                onClick = {
                    val cookie = collectStardbCookie()
                    val cookieNames = cookieNames(cookie)
                    if ("id" in cookieNames) {
                        CookieManager.getInstance().flush()
                        onCookieLogin(cookie)
                    } else {
                        val missing = listOf("id").filterNot { it in cookieNames }
                        webLoginMessage = "还不能进入 App，缺少 Cookie: ${missing.joinToString(", ")}。请在网页里确认已登录，必要时刷新成就页后再点。"
                    }
                },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("我已完成登录")
            }
            webLoginMessage?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            StardbLoginWebView(
                onCookieReady = onCookieLogin,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            Button(
                onClick = { showWebLogin = true },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("网页登录")
            }
            Spacer(Modifier.weight(1f))
        }
    }

    error?.let {
        ErrorDialog(error = it, onDismiss = onDismissError)
    }
}

private fun collectStardbCookie(): String {
    val cookieManager = CookieManager.getInstance()
    val cookieParts = listOf(
        "https://stardb.gg",
        "https://stardb.gg/zh-cn/login",
        "https://stardb.gg/zh-cn/zzz/achievement-tracker",
        "https://stardb.gg/api/users/me/zzz/achievements/completed/1006004",
    ).flatMap { url ->
        cookieManager.getCookie(url).orEmpty()
            .split(";")
            .map { it.trim() }
            .filter { it.contains("=") }
    }

    val byName = linkedMapOf<String, String>()
    cookieParts.forEach { part ->
        val name = part.substringBefore("=").trim()
        if (name.isNotBlank()) {
            byName[name] = part
        }
    }
    byName.putIfAbsent("paraglide_lang", "paraglide_lang=zh-cn")
    return byName.values.joinToString("; ")
}

private fun cookieNames(cookie: String): Set<String> =
    cookie.split(";")
        .map { it.trim().substringBefore("=") }
        .filter { it.isNotBlank() }
        .toSet()

@Composable
private fun StardbLoginWebView(
    onCookieReady: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            var cookieReported = false
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        val cookie = collectStardbCookie()
                        if (!cookieReported && "id" in cookieNames(cookie)) {
                            cookieReported = true
                            CookieManager.getInstance().flush()
                            onCookieReady(cookie)
                        }
                    }
                }
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    loadUrl("https://stardb.gg/zh-cn/login")
                }
            }
        },
    )
}

@Composable
private fun AchievementHome(
    state: AchievementScreenState,
    isRefreshing: Boolean,
    error: String?,
    onSearchChange: (String) -> Unit,
    onPrimaryCategoryChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onCompletionFilterChange: (CompletionFilter) -> Unit,
    onVersionChange: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onSelect: (AchievementUiModel) -> Unit,
    onListIndexChange: (Int) -> Unit,
    onDismissError: () -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.filter.firstVisibleItemIndex)
    var restoredListPosition by remember { mutableStateOf(false) }
    var filtersVisible by remember { mutableStateOf(true) }
    val filterScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput) {
                    when {
                        available.y < -2f -> filtersVisible = false
                        available.y > 2f -> filtersVisible = true
                    }
                }
                return Offset.Zero
            }
        }
    }

    LaunchedEffect(state.totalCount) {
        if (state.totalCount > 0) {
            if (!restoredListPosition && state.filter.firstVisibleItemIndex > 0) {
                val targetIndex = state.filter.firstVisibleItemIndex.coerceAtMost((state.achievements.size - 1).coerceAtLeast(0))
                listState.scrollToItem(targetIndex)
                restoredListPosition = true
            }
        }
    }

    LaunchedEffect(listState, state.totalCount) {
        if (state.totalCount > 0) {
            snapshotFlow { listState.firstVisibleItemIndex }
                .distinctUntilChanged()
                .collect { onListIndexChange(it) }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().nestedScroll(filterScrollConnection),
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        stickyHeader(key = "filters") {
            AnimatedVisibility(
                visible = filtersVisible,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                CompactFilterPanel(
                    state = state,
                    isRefreshing = isRefreshing,
                    onSearchChange = onSearchChange,
                    onCompletionFilterChange = onCompletionFilterChange,
                    onVersionChange = onVersionChange,
                )
            }
        }
        if (state.achievements.isEmpty()) {
            item {
                EmptyState(total = state.totalCount)
            }
        } else {
            items(
                count = state.achievements.size,
                key = { index -> state.achievements[index].id },
            ) { index ->
                AchievementCard(
                    achievement = state.achievements[index],
                    onToggle = { completed -> onToggle(state.achievements[index].id, completed) },
                    onClick = { onSelect(state.achievements[index]) },
                )
            }
        }
    }

    error?.let { ErrorDialog(error = it, onDismiss = onDismissError) }
}

@Composable
private fun ProgressPanel(
    completed: Int,
    total: Int,
    lastSync: Long,
    isRefreshing: Boolean,
) {
    val progress = if (total == 0) 0f else completed.toFloat() / total.toFloat()
    Surface(
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "$completed / $total",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "完成进度 · ${formatPercent(progress)}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                SyncStatusPill(
                    text = if (isRefreshing) "同步中" else "已缓存",
                    status = if (isRefreshing) SyncStatus.Pending else SyncStatus.Synced,
                )
            }
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (lastSync == 0L) "尚未完成同步" else "上次同步 ${formatTime(lastSync)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun CompactFilterPanel(
    state: AchievementScreenState,
    isRefreshing: Boolean,
    onSearchChange: (String) -> Unit,
    onCompletionFilterChange: (CompletionFilter) -> Unit,
    onVersionChange: (String) -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            CompactSearchField(
                value = state.filter.search,
                onValueChange = onSearchChange,
                isRefreshing = isRefreshing,
                syncStatus = state.syncStatus(),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) {
                listOf(CompletionFilter.All, CompletionFilter.Completed, CompletionFilter.Incomplete).forEach { filter ->
                    FilterChip(
                        selected = state.filter.completionFilter == filter,
                        onClick = { onCompletionFilterChange(filter) },
                        label = { Text(filter.label(), style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.height(30.dp),
                    )
                }
                VersionDropdown(
                    versions = state.versions,
                    selected = state.filter.version,
                    onSelected = onVersionChange,
                )
            }
        }
    }
}

@Composable
private fun CompactSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    isRefreshing: Boolean,
    syncStatus: SyncStatus,
) {
    val textStyle = MaterialTheme.typography.bodyMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
    )
    var fieldValue by remember {
        mutableStateOf(TextFieldValue(value, selection = TextRange(value.length)))
    }

    LaunchedEffect(value) {
        if (value != fieldValue.text) {
            fieldValue = TextFieldValue(value, selection = TextRange(value.length))
        }
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth().height(42.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp),
        ) {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = fieldValue,
                onValueChange = { next ->
                    fieldValue = next
                    onValueChange(next.text)
                },
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (fieldValue.text.isBlank()) {
                            Text(
                                text = "搜索标题或描述",
                                style = textStyle,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            if (fieldValue.text.isNotBlank()) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "清空搜索",
                    modifier = Modifier
                        .size(20.dp)
                        .clickable {
                            fieldValue = TextFieldValue("")
                            onValueChange("")
                        },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
            }
            SearchSyncIndicator(
                isRefreshing = isRefreshing,
                syncStatus = syncStatus,
            )
        }
    }
}

@Composable
private fun FilterPanel(
    state: AchievementScreenState,
    isRefreshing: Boolean,
    onSearchChange: (String) -> Unit,
    onPrimaryCategoryChange: (String) -> Unit,
    onCategoryChange: (String) -> Unit,
    onCompletionFilterChange: (CompletionFilter) -> Unit,
) {
    Surface(
        tonalElevation = 1.dp,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            OutlinedTextField(
                value = state.filter.search,
                onValueChange = onSearchChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    SearchSyncIndicator(
                        isRefreshing = isRefreshing,
                        syncStatus = state.syncStatus(),
                    )
                },
                label = { Text("搜索名称或描述") },
                placeholder = { Text("搜索名称或描述") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(CompletionFilter.All, CompletionFilter.Completed, CompletionFilter.Incomplete).forEach { filter ->
                    FilterChip(
                        selected = state.filter.completionFilter == filter,
                        onClick = { onCompletionFilterChange(filter) },
                        label = { Text(filter.label()) },
                        modifier = Modifier.height(32.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSyncIndicator(
    isRefreshing: Boolean,
    syncStatus: SyncStatus,
) {
    if (isRefreshing) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
        )
        return
    }

    val icon = when (syncStatus) {
        SyncStatus.Synced -> Icons.Default.CloudDone
        SyncStatus.Pending -> Icons.Default.Sync
        SyncStatus.Failed -> Icons.Default.Error
    }
    val description = when (syncStatus) {
        SyncStatus.Synced -> "已同步"
        SyncStatus.Pending -> "待同步"
        SyncStatus.Failed -> "同步失败"
    }
    Icon(icon, contentDescription = description)
}

@Composable
private fun CategoryChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

private fun AchievementScreenState.selectedPrimaryCategory(): String =
    filter.primaryCategory.ifBlank { primaryCategories.firstOrNull().orEmpty() }

private fun AchievementScreenState.selectedCategory(): String =
    filter.category.ifBlank { categories.firstOrNull().orEmpty() }

@Composable
private fun VersionDropdown(
    versions: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected.isNotBlank(),
            onClick = { expanded = true },
            label = {
                Text(
                    if (selected.isBlank()) "版本" else selected.asVersionLabel(),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            modifier = Modifier.height(30.dp),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部版本") }, onClick = {
                expanded = false
                onSelected("")
            })
            versions.forEach { version ->
                DropdownMenuItem(text = { Text(version.asVersionLabel()) }, onClick = {
                    expanded = false
                    onSelected(version)
                })
            }
        }
    }
}

private fun String.asVersionLabel(): String = if (startsWith("V", ignoreCase = true)) this else "V$this"

private const val PinnedCategory = "置顶"

private fun Modifier.openDrawerOnHorizontalSwipe(
    enabled: Boolean,
    onOpen: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(enabled) {
        val horizontalThreshold = 72.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var current = down
            var horizontalDistance = 0f
            var verticalDistance = 0f
            while (current.pressed) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                current = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = current.position - current.previousPosition
                horizontalDistance += delta.x
                verticalDistance += delta.y
                if (
                    abs(horizontalDistance) >= horizontalThreshold &&
                    abs(horizontalDistance) > abs(verticalDistance) * 1.25f
                ) {
                    onOpen()
                    break
                }
            }
        }
    }
}

@Composable
private fun CategoryDropdown(
    categories: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(if (selected.isBlank()) "全部分类" else selected, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = { Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("全部分类") }, onClick = {
                expanded = false
                onSelected("")
            })
            categories.forEach { category ->
                DropdownMenuItem(text = { Text(category) }, onClick = {
                    expanded = false
                    onSelected(category)
                })
            }
        }
    }
}

@Composable
private fun AchievementCard(
    achievement: AchievementUiModel,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    IconAchievementCard(achievement = achievement, onToggle = onToggle, onClick = onClick)
    return

    ElevatedCard(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = achievement.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (achievement.description.isNotBlank()) {
                        Text(
                            text = achievement.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = achievement.isCompleted,
                    onCheckedChange = onToggle,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (achievement.category.isNotBlank()) InfoChip(achievement.category)
                if (achievement.version.isNotBlank()) InfoChip("版本 ${achievement.version}")
            }
        }
    }
}

@Composable
private fun IconAchievementCard(
    achievement: AchievementUiModel,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val containerColor = if (achievement.isPinned) {
        pinnedAchievementContainerColor()
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val displayIconTier = achievement.iconTier ?: 1
            AchievementIconColumn(iconTier = displayIconTier, version = achievement.version)
            Spacer(Modifier.width(12.dp))
            Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            text = achievement.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (achievement.description.isNotBlank()) {
                        Text(
                            text = achievement.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                key(achievement.id) {
                    Switch(checked = achievement.isCompleted, onCheckedChange = onToggle)
                }
            }
        }
    }
}

@Composable
private fun AchievementIconColumn(iconTier: Int, version: String) {
    val iconRes = iconResource(iconTier) ?: return
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(56.dp)) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.height(2.dp))
        VersionBadge(version = version)
    }
}

@Composable
private fun VersionBadge(version: String) {
    if (version.isBlank()) return
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.primary,
    ) {
        Text(
            text = "V$version",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        )
    }
}

@Composable
private fun rememberRemoteBitmap(url: String): android.graphics.Bitmap? {
    var bitmap by remember(url) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(url) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
    }
    return bitmap
}

private fun iconResource(iconTier: Int?): Int? = when (iconTier) {
    1 -> R.drawable.achievement_bronze
    2 -> R.drawable.achievement_silver
    3 -> R.drawable.achievement_gold
    else -> null
}

@Composable
private fun CompactAchievementCard(
    achievement: AchievementUiModel,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    ElevatedCard(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = achievement.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (achievement.description.isNotBlank()) {
                        Text(
                            text = achievement.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = achievement.isCompleted,
                    onCheckedChange = onToggle,
                )
            }
            if (achievement.version.isNotBlank()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoChip("版本 ${achievement.version}")
                }
            }
        }
    }
}

@Composable
private fun AchievementDetailDialog(
    achievement: AchievementUiModel,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
) {
    CompactAchievementDetailDialog(
        achievement = achievement,
        onDismiss = onDismiss,
        onToggle = onToggle,
        onPinnedChange = onPinnedChange,
    )
    return

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = { Text(achievement.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(achievement.description.ifBlank { "暂无描述" })
                DetailLine("分类", achievement.category)
                DetailLine("版本", achievement.version)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("完成状态", modifier = Modifier.weight(1f))
                    Switch(checked = achievement.isCompleted, onCheckedChange = onToggle)
                }
                if (!achievement.isCompleted) {
                    TextButton(
                        onClick = { onPinnedChange(!achievement.isPinned) },
                    ) {
                        Text(if (achievement.isPinned) "取消置顶" else "置顶")
                    }
                }
            }
        },
    )
}

@Composable
private fun CompactAchievementDetailDialog(
    achievement: AchievementUiModel,
    onDismiss: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onPinnedChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val bilibiliSearchUrl = remember(achievement.name) {
        val query = URLEncoder.encode("绝区零成就${achievement.name}", "UTF-8")
        "https://search.bilibili.com/all?keyword=$query"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(bilibiliSearchUrl)))
                },
            ) {
                Text("B站搜索")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        title = {
            SelectionContainer {
                Text(achievement.name)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(achievement.description.ifBlank { "暂无描述" })
                }
                SelectionContainer {
                    DetailLine("版本", achievement.version)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("完成状态", modifier = Modifier.weight(1f))
                    Switch(checked = achievement.isCompleted, onCheckedChange = onToggle)
                }
                if (!achievement.isCompleted) {
                    TextButton(
                        onClick = { onPinnedChange(!achievement.isPinned) },
                    ) {
                        Text(if (achievement.isPinned) "取消置顶" else "置顶")
                    }
                }
            }
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value.ifBlank { "无" }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun EmptyState(total: Int) {
    Card(
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (total == 0) "还没有本地缓存" else "没有符合筛选条件的成就",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (total == 0) "请登录并刷新，首次同步完成后下次会快速加载。" else "调整搜索、分类或完成状态筛选后再试。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    AssistChip(onClick = {}, label = { Text(text) })
}

@Composable
private fun SyncStatusPill(text: String, status: SyncStatus) {
    val icon = when (status) {
        SyncStatus.Synced -> Icons.Default.CloudDone
        SyncStatus.Pending -> Icons.Default.Sync
        SyncStatus.Failed -> Icons.Default.Error
    }
    AssistChip(
        onClick = {},
        label = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
    )
}

@Composable
private fun ErrorDialog(error: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Error, contentDescription = null) },
        title = { Text("操作失败") },
        text = { Text(error) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

private fun CompletionFilter.label(): String = when (this) {
    CompletionFilter.All -> "全部"
    CompletionFilter.Completed -> "已完成"
    CompletionFilter.Incomplete -> "未完成"
    CompletionFilter.PendingSync -> "待同步"
}

private fun SortMode.label(): String = when (this) {
    SortMode.Category -> "分类"
    SortMode.Name -> "名称"
    SortMode.Completion -> "完成状态"
    SortMode.Version -> "版本"
}

private fun SyncStatus.label(): String = when (this) {
    SyncStatus.Synced -> "已同步"
    SyncStatus.Pending -> "待同步"
    SyncStatus.Failed -> "同步失败"
}

@Composable
private fun pinnedAchievementContainerColor(): Color =
    if (isSystemInDarkTheme()) {
        Color(0xFF4A3B18)
    } else {
        Color(0xFFFFF4BF)
    }

private fun formatPercent(value: Float): String = "${(value * 100).toInt()}%"

private fun formatTime(epochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epochMillis))

private fun AchievementScreenState.syncStatus(): SyncStatus = when {
    achievements.any { it.syncStatus == SyncStatus.Failed } -> SyncStatus.Failed
    achievements.any { it.syncStatus == SyncStatus.Pending } -> SyncStatus.Pending
    else -> SyncStatus.Synced
}
