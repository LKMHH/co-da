package com.coda.workbench.ui.theme

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 主按钮：视觉稿 §1.4 固定 52dp 高（action.primary 填充）。 */
@Composable
fun CodaButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(onClick = onClick, modifier = modifier.height(52.dp), enabled = enabled, content = content)
}

/** 次按钮：主色描边，同样 52dp。 */
@Composable
fun CodaOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(52.dp), enabled = enabled, content = content)
}
