package com.trackr.ui.components.elder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.trackr.core.designsystem.resources.Res
import com.trackr.core.designsystem.resources.action_back
import com.trackr.core.designsystem.resources.ic_back
import com.trackr.core.designsystem.resources.ic_qr_code
import com.trackr.core.designsystem.theme.TrackrTheme
import com.trackr.core.designsystem.theme.eta
import com.trackr.core.ui.resources.Res as UiRes
import com.trackr.core.ui.resources.ui_elder_got_it
import com.trackr.core.ui.resources.ui_elder_show_transit_code
import com.trackr.ui.components.arrival.ArrivalUi
import com.trackr.ui.components.arrival.arrivalToneColor
import org.jetbrains.compose.resources.stringResource
import org.jetbrains.compose.resources.vectorResource

/*
 * 长辈版尺寸的共用组件（重构计划 §8.5，设计稿 redesign_v3_elder.html）。
 * 字号、颜色只读主题（长辈主题下正文 20sp、最小 18sp）；这里只管「结构」上的东西：
 * 按钮都带字、至少 64dp 高（主按钮 72dp），每页左上角「← 返回」，底部固定「出示乘车码」。
 */

/** 普通按钮的高度（设计稿：能点的地方都 ≥ 64dp） */
val ElderButtonHeight: Dp = 64.dp

/** 主按钮、「出示乘车码」的高度 */
val ElderPrimaryButtonHeight: Dp = 72.dp

/** 测试找「出示乘车码」用 */
const val SHOW_TRANSIT_CODE_TAG = "elder_show_transit_code"

/** 测试找「返回」用 */
const val ELDER_BACK_TAG = "elder_back"

/**
 * 长辈版顶栏：左边「← 返回」大按钮（[onBack] 为 null 时不显示，首页用），中间 [title]，右边 [actions]。
 * 不用 M3 TopAppBar：它固定 64dp 高，字放大后会被裁。
 */
@Composable
fun ElderTopBar(
    title: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val dimens = TrackrTheme.dimens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .heightIn(min = ElderButtonHeight)
            .padding(horizontal = dimens.spaceS, vertical = dimens.spaceXs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TextButton(
                onClick = onBack,
                modifier = Modifier
                    .heightIn(min = ElderButtonHeight)
                    .widthIn(min = ElderButtonHeight)
                    .testTag(ELDER_BACK_TAG)
            ) {
                Icon(imageVector = vectorResource(Res.drawable.ic_back), contentDescription = null, modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(dimens.spaceXs))
                Text(text = stringResource(Res.string.action_back), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = dimens.spaceS)
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }
        actions()
    }
}

/**
 * 长辈版按钮：整行宽、带字。[primary] 是实心主按钮（72dp），否则是描边按钮（64dp）。
 * @param color 实心按钮的底色；默认品牌蓝
 */
@Composable
fun ElderButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
    color: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified
) {
    val textStyle = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    if (primary) {
        val colors = if (color == Color.Unspecified) {
            ButtonDefaults.buttonColors()
        } else {
            ButtonDefaults.buttonColors(containerColor = color, contentColor = contentColor)
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            colors = colors,
            modifier = modifier.heightIn(min = ElderPrimaryButtonHeight).widthIn(min = ElderButtonHeight)
        ) {
            Text(text = text, style = textStyle)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = MaterialTheme.shapes.medium,
            modifier = modifier.heightIn(min = ElderButtonHeight).widthIn(min = ElderButtonHeight)
        ) {
            Text(text = text, style = textStyle)
        }
    }
}

/**
 * 每页底部固定的「出示乘车码」长条（设计稿：绿色，和支付宝乘车码颜色接近）。一点就开，不藏、不长按。
 * 自己让开系统导航栏，放在 Scaffold 的 bottomBar 里。打开哪个平台由调用方决定（`TransitCodeLauncher`）。
 */
@Composable
fun ShowTransitCodeButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val dimens = TrackrTheme.dimens
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier) {
        Button(
            onClick = onClick,
            shape = MaterialTheme.shapes.medium,
            colors = ButtonDefaults.buttonColors(
                containerColor = TrackrTheme.colors.go,
                // 浅色主题 go 是深绿配白字，深色主题 go 是亮绿配深色字；surface 两边都对得上
                contentColor = MaterialTheme.colorScheme.surface
            ),
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = dimens.screenPadding, vertical = dimens.spaceS)
                .heightIn(min = ElderPrimaryButtonHeight)
                .testTag(SHOW_TRANSIT_CODE_TAG)
        ) {
            Icon(imageVector = vectorResource(Res.drawable.ic_qr_code), contentDescription = null, modifier = Modifier.size(32.dp))
            Spacer(modifier = Modifier.width(dimens.spaceS))
            Text(
                text = stringResource(UiRes.string.ui_elder_show_transit_code),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** 长辈版线路号：深蓝底白字，比标准版的浅蓝底醒目（设计稿「和标准版的差别」）；名字为空时不画空壳（issue #20） */
@Composable
fun ElderLineBadge(name: String, modifier: Modifier = Modifier) {
    if (name.isBlank()) return
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        modifier = modifier
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp)
        )
    }
}

/**
 * 到站大字（主题 eta：长辈版 56sp）+ 下面的小字。大字一行放不下时自动缩小（最小 24sp），
 * 系统字体放到 2 倍也不会被裁（R13）。
 */
@Composable
fun ElderArrival(arrival: ArrivalUi, modifier: Modifier = Modifier, detail: String = arrival.small) {
    val color = arrivalToneColor(arrival.tone)
    val style = MaterialTheme.typography.eta.copy(color = color)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            text = arrival.big,
            style = style,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 24.sp, maxFontSize = style.fontSize),
            modifier = Modifier.fillMaxWidth()
        )
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 长辈版提示：要点「知道了」才关，不会自己消失，点框外也不关（设计稿「不用会自己消失的提示条」）。
 * 系统返回键照样能关，免得卡住。页面一般不直接用它，用 `UserMessageHost`（长辈版下就是它）。
 */
@Composable
fun ElderNoticeDialog(text: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        text = { Text(text = text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ElderButtonHeight).widthIn(min = ElderButtonHeight)) {
                Text(text = stringResource(UiRes.string.ui_elder_got_it), style = MaterialTheme.typography.titleMedium)
            }
        }
    )
}

/**
 * 长辈版确认框（如「不用叫了」）：两个带字按钮。
 */
@Composable
fun ElderConfirmDialog(
    text: String,
    confirmText: String,
    dismissText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(text = text, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = ElderButtonHeight).widthIn(min = ElderButtonHeight)) {
                Text(text = confirmText, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = ElderButtonHeight).widthIn(min = ElderButtonHeight)) {
                Text(text = dismissText, style = MaterialTheme.typography.titleMedium)
            }
        }
    )
}
