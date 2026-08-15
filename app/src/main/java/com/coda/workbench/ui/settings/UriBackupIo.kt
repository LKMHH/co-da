package com.coda.workbench.ui.settings

import android.content.ContentResolver
import android.net.Uri
import com.coda.workbench.core.usecase.BackupDestination
import com.coda.workbench.core.usecase.BackupSource
import java.io.InputStream
import java.io.OutputStream

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
