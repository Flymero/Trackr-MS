package com.trackr.ui.screens.me

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.ic_bell
import com.trackr.feature.me.resources.Res as MeRes
import com.trackr.feature.me.resources.me_live_guide_body
import com.trackr.feature.me.resources.me_live_guide_fallback
import com.trackr.feature.me.resources.me_live_guide_go
import com.trackr.feature.me.resources.me_live_guide_later
import com.trackr.feature.me.resources.me_live_guide_title
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/**
 * 「我的」→「状态栏胶囊」点「去开启」时的说明弹窗。
 *
 * 只在用户自己走进设置、主动想开的时候出现——开始追踪时不再拦（issue #29）。
 * 各家系统的名字（小米焦点通知 / 超级岛、OPPO 流体云、Android 实时活动）在文案里统一叫「实时活动通知」，
 * 不跟着 ROM 和版本改，免得每出一个新系统就要补一套说法。
 */
@Composable
internal fun LiveUpdateGuideDialog(
    onDismissRequest: () -> Unit,
    onGoToSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(20.dp),
        icon = {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = vectorResource(Res.drawable.ic_bell),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(10.dp)
                        .size(28.dp)
                )
            }
        },
        title = {
            Text(
                text = stringResource(MeRes.string.me_live_guide_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(MeRes.string.me_live_guide_body),
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(MeRes.string.me_live_guide_fallback),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        },
        confirmButton = {
            Button(onClick = onGoToSettings, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(MeRes.string.me_live_guide_go))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismissRequest, shape = RoundedCornerShape(10.dp)) {
                Text(stringResource(MeRes.string.me_live_guide_later))
            }
        }
    )
}
