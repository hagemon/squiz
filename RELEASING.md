# Squiz 发布指南

Squiz 通过带版本号的 Git Tag 触发 GitHub Actions。普通提交和普通 `push` 不会发布版本；只有形如 `v0.6.3` 的 Tag 才会构建签名 APK 并创建 GitHub Release。

## 1. 配置 GitHub Actions 密钥

打开 GitHub 仓库的 **Settings → Secrets and variables → Actions → New repository secret**，添加下面三个 Repository secrets：

| Secret | 内容 |
| --- | --- |
| `SQUIZ_KEYSTORE_BASE64` | `/Users/hagemon/jks/squiz-release.jks` 的 Base64 文本 |
| `SQUIZ_STORE_PASSWORD` | JKS 密码 |
| `SQUIZ_KEY_PASSWORD` | `squiz-release` 这个 Key 的密码 |

在 Mac 上复制 JKS 的 Base64 文本：

```bash
base64 -i /Users/hagemon/jks/squiz-release.jks | pbcopy
```

将剪贴板内容完整粘贴为 `SQUIZ_KEYSTORE_BASE64`。Key Alias 已在工作流中固定为 `squiz-release`，它不是密码，不需要单独保存为 Secret。

然后检查 **Settings → Actions → General → Workflow permissions**。工作流需要 `Read and write permissions`，才能创建 Release 并上传 APK。工作流自身也只申请了 `contents: write`。

## 2. 发布新版本

每次发布都必须同时增加 `app/build.gradle.kts` 中的两个值：

- `versionCode`：Android 用来判断新旧的递增整数，发布后绝不能回退或重复。
- `versionName`：展示给用户的版本号，例如 `0.6.3`。

提交并推送代码：

```bash
git add app/build.gradle.kts
git commit -m "Release Squiz 0.6.3"
git push origin main
```

确认 GitHub 上的提交和 Actions 工作流都正确后，创建并推送同版本 Tag：

```bash
git tag -a v0.6.3 -m "Squiz v0.6.3"
git push origin v0.6.3
```

Action 会依次运行单元测试和 Lint、构建 Release APK、验证 APK 签名、检查 Tag 与 `versionName` 一致、生成 SHA-256 文件，并创建正式 GitHub Release。发布说明由 GitHub 根据两次 Tag 之间的提交自动生成，之后可在 Release 页面继续编辑。

## 3. 签名与备份

- 永久、安全地备份 JKS、Alias 和两个密码。丢失后将无法向已经安装 Squiz 的用户提供可覆盖安装的更新。
- 不要提交 JKS、`keystore.properties` 或任何密码；这些文件已被 `.gitignore` 排除。
- 所有正式版本必须始终使用同一个 `/Users/hagemon/jks/squiz-release.jks` 和 `squiz-release` Alias。
- 如果手机上现有的是 Debug 签名版本，它不能覆盖安装正式 Release。第一次测试正式版前需要卸载 Debug 版；应用本地数据也会随卸载清除。

## 4. GitHub 分发下的更新方式

应用每天至多自动检查一次 GitHub 最新正式 Release，也可在 **设置 → 检查更新** 中手动检查。发现新版本后由 Android 系统后台下载，Squiz 校验 SHA-256、包名、版本号和签名，再交给系统安装器。安装始终需要用户确认；Android 8 及以上第一次还需要为 Squiz 开启“允许来自此来源的应用”。

最新版接口只会返回正式发布且非草稿、非预发布的 Release，因此测试版本如果标为 Pre-release，不会推送给普通用户。

如果只通过 GitHub 提供 APK，还应关注 Android 的开发者验证要求，并使用 Android Developer Console 注册包名和正式签名证书。这个注册不会把应用上传到 Google Play。
