package com.hagemon.squiz.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.hagemon.squiz.model.LlmConfig
import com.hagemon.squiz.model.LlmProvider
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LlmSettingsScreen(
    config: LlmConfig,
    onBack: () -> Unit,
    onTest: suspend (LlmConfig) -> Result<String>,
    onSave: suspend (LlmConfig) -> Result<Unit>,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(config) { mutableStateOf(config) }
    var providerMenu by remember { mutableStateOf(false) }
    var keyVisible by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val complete = draft.endpointUrl.isNotBlank() && draft.model.isNotBlank() && draft.apiKey.isNotBlank() &&
        (draft.provider != LlmProvider.CLAUDE || draft.anthropicVersion.isNotBlank())

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text("大模型接口", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isTesting = true
                                status = onTest(draft).fold(
                                    onSuccess = { it },
                                    onFailure = { "连接失败：${it.message ?: "未知错误"}" },
                                )
                                isTesting = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = complete && !isTesting && !isSaving,
                    ) {
                        if (isTesting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Key, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("连通测试")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                isSaving = true
                                status = onSave(draft).fold(
                                    onSuccess = { "配置已保存" },
                                    onFailure = { "保存失败：${it.message ?: "未知错误"}" },
                                )
                                isSaving = false
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = complete && !isTesting && !isSaving,
                    ) {
                        if (isSaving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Rounded.Check, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("保存")
                    }
                }
            }
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    "接口配置",
                    modifier = Modifier.padding(start = 24.dp, top = 18.dp, bottom = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            item {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text("供应商") },
                    supportingContent = { Text(providerDescription(draft.provider)) },
                    trailingContent = {
                        Box {
                            TextButton(onClick = { providerMenu = true }) {
                                Text(draft.provider.displayName)
                                Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                            }
                            DropdownMenu(providerMenu, onDismissRequest = { providerMenu = false }) {
                                LlmProvider.entries.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.displayName) },
                                        onClick = {
                                            providerMenu = false
                                            draft = draft.copy(
                                                provider = provider,
                                                endpointUrl = provider.defaultEndpointUrl,
                                                model = provider.defaultModel,
                                            )
                                            status = null
                                        },
                                    )
                                }
                            }
                        }
                    },
                )
            }
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OutlinedTextField(
                        value = draft.endpointUrl,
                        onValueChange = { draft = draft.copy(endpointUrl = it); status = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API URL") },
                        placeholder = { Text("http:// 或 https:// 完整请求地址") },
                        supportingText = {
                            Text(
                                if (draft.endpointUrl.startsWith("http://")) {
                                    "HTTP 不加密，API Key 和题目样例可能被截获"
                                } else {
                                    "填写完整地址，包含 /chat/completions 或 /messages"
                                },
                                color = if (draft.endpointUrl.startsWith("http://")) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.model,
                        onValueChange = { draft = draft.copy(model = it); status = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("模型名称") },
                        placeholder = { Text("例如供应商控制台中的模型 ID") },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = draft.apiKey,
                        onValueChange = { draft = draft.copy(apiKey = it); status = null },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("API Key") },
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                    contentDescription = if (keyVisible) "隐藏密钥" else "显示密钥",
                                )
                            }
                        },
                    )
                    if (draft.provider == LlmProvider.CLAUDE) {
                        OutlinedTextField(
                            value = draft.anthropicVersion,
                            onValueChange = { draft = draft.copy(anthropicVersion = it); status = null },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Anthropic API 版本") },
                            supportingText = { Text("作为 anthropic-version 请求头发送") },
                            singleLine = true,
                        )
                    }
                }
            }
            status?.let { message ->
                item {
                    Text(
                        message,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp),
                        color = if (message.startsWith("连接失败") || message.startsWith("保存失败")) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            item {
                Spacer(Modifier.height(16.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null)
                        Text(
                            providerHelp(draft.provider) +
                                "\n\nAPI Key 使用 Android Keystore 加密且不参与备份。完整文件只在本机扫描；智能解析按 Sheet 发送有上限的结构统计和代表样例，不上传原文件。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private fun providerDescription(provider: LlmProvider): String = when (provider) {
    LlmProvider.DEEPSEEK -> "OpenAI Chat Completions 格式"
    LlmProvider.OPENAI_COMPATIBLE -> "自定义兼容接口"
    LlmProvider.CLAUDE -> "Anthropic Messages 格式"
    LlmProvider.KIMI -> "Moonshot Chat Completions 格式"
}

private fun providerHelp(provider: LlmProvider): String = when (provider) {
    LlmProvider.CLAUDE ->
        "必填：完整 Messages URL、Claude 模型 ID、API Key、Anthropic API 版本。请求使用 x-api-key 鉴权，并按官方要求发送 max_tokens。"
    LlmProvider.DEEPSEEK ->
        "必填：完整 Chat Completions URL、DeepSeek 模型 ID、API Key。请求使用 Authorization: Bearer 鉴权。"
    LlmProvider.KIMI ->
        "必填：完整 Chat Completions URL、Kimi 模型 ID、API Key。请求使用 Authorization: Bearer 鉴权。"
    LlmProvider.OPENAI_COMPATIBLE ->
        "必填：完整 Chat Completions URL、服务支持的模型名称、API Key。请求使用 OpenAI messages JSON 与 Authorization: Bearer 鉴权。"
}
