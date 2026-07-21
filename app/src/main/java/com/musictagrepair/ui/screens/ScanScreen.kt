package com.musictagrepair.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.musictagrepair.viewmodel.AppViewModel
import com.musictagrepair.viewmodel.PermissionUtil
import com.musictagrepair.viewmodel.ScanPhase

@Composable
fun ScanScreen(
    viewModel: AppViewModel,
    onNavigateToFiles: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }

    // 权限请求（音频读取 + 通知）
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    // SAF 目录选择（仅作为可选的"限定目录"扫描入口）
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Throwable) {}
            selectedTreeUri = uri
        }
    }

    // 申请音频读取权限
    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            val toRequest = perms.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    // 用户从「所有文件访问」系统设置页返回时，重新检测权限状态
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))

        // 图标
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.AutoFixHigh,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }

        Text(
            "音乐标签修复",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Text(
            "扫描本地音乐，在线补全标签信息",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(8.dp))

        // All Files Access 未授权提示
        if (!state.allFilesAccessGranted) {
            AllFilesAccessBanner(
                onClick = { PermissionUtil.requestAllFilesAccess(context) },
            )
        }

        // 选中目录显示
        selectedTreeUri?.let { uri ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            ) {
                Text(
                    text = uri.toString(),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                )
            }
        }

        // 扫描进度
        if (state.scanning) {
            ScanProgressBar(state)
        }

        // 主扫描按钮：直接扫整台设备
        Button(
            onClick = {
                viewModel.scanAllMedia()
                onNavigateToFiles()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = !state.scanning,
        ) {
            Icon(Icons.Filled.LibraryMusic, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text("扫描全设备音频")
        }

        // 可选：选目录后扫描（仅在指定目录下）
        OutlinedButton(
            onClick = { dirPicker.launch(null) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = !state.scanning,
        ) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (selectedTreeUri == null) "选择特定目录扫描" else "更换目录")
        }

        if (selectedTreeUri != null && !state.scanning) {
            Button(
                onClick = {
                    selectedTreeUri?.let {
                        viewModel.scanDirectory(it)
                        onNavigateToFiles()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(14.dp),
                enabled = !state.scanning,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("在所选目录扫描")
            }
        }

        if (state.scanStatus.isNotBlank() && !state.scanning) {
            Text(
                state.scanStatus,
                fontSize = 13.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )
        }

        // 兜底：扫描内置 Music 目录
        OutlinedButton(
            onClick = {
                val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC).absolutePath
                viewModel.scanDirectoryPath(path)
                onNavigateToFiles()
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = !state.scanning,
        ) {
            Text("或扫描内置音乐目录", fontSize = 13.sp)
        }

        Spacer(Modifier.height(8.dp))

        Text(
            "支持 MP3 · FLAC · M4A · OGG · WAV · APE 等",
            fontSize = 11.sp,
            color = Color.LightGray,
        )
    }
}

@Composable
private fun AllFilesAccessBanner(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF3E0))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFE65100),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "需要「所有文件访问」权限",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE65100),
                fontSize = 14.sp,
            )
        }
        Text(
            "Android 11+ 上读写音频文件标签需要该权限。否则即使扫描到文件也无法读取 / 修改标签。",
            fontSize = 12.sp,
            color = Color(0xFF6D4C41),
        )
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("前往系统设置授权")
        }
    }
}

@Composable
private fun ScanProgressBar(state: com.musictagrepair.viewmodel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                when (state.scanPhase) {
                    ScanPhase.Discovering -> "正在发现音频文件..."
                    ScanPhase.ReadingTags -> "正在读取音频标签..."
                    else -> "扫描中..."
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
            )
        }

        when (state.scanPhase) {
            ScanPhase.Discovering -> {
                Text(
                    "已发现 ${state.discoveredCount} 个音频文件",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
            ScanPhase.ReadingTags -> {
                val progress = if (state.scanTotal > 0) {
                    state.scannedCount.toFloat() / state.scanTotal
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Text(
                    "${state.scannedCount} / ${state.scanTotal}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
            else -> {}
        }

        if (state.currentFileName.isNotBlank()) {
            Text(
                state.currentFileName,
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
