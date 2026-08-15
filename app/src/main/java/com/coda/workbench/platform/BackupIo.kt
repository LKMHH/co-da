package com.coda.workbench.platform

import android.content.ContentResolver
import android.net.Uri
import java.io.InputStream
import java.io.OutputStream

/** 备份输出目标（平台层承载文件选择与流适配，核心层只消费流）。 */
interface BackupDestination {
    fun openOutputStream(): OutputStream
}

/** 备份来源（平台层承载 SAF 输入流）。 */
interface BackupSource {
    fun openInputStream(): InputStream
}

/** SAF 选定的输出位置（技术稿 §10.2：平台层承载文件选择，核心层不依赖 Uri）。 */
class UriBackupDestination(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : BackupDestination {
    override fun openOutputStream(): OutputStream =
        resolver.openOutputStream(uri) ?: error("无法写入所选位置")
}

class UriBackupSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
) : BackupSource {
    override fun openInputStream(): InputStream =
        resolver.openInputStream(uri) ?: error("无法读取所选文件")
}
