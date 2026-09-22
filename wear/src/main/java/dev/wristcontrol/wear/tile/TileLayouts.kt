package dev.wristcontrol.wear.tile

import android.content.Context
import androidx.annotation.ColorInt
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.material.Button
import androidx.wear.protolayout.material.ButtonColors
import androidx.wear.protolayout.material.ChipColors
import androidx.wear.protolayout.material.CircularProgressIndicator
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.ProgressIndicatorColors
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.EdgeContentLayout
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import dev.wristcontrol.wear.data.WristState
import dev.wristcontrol.wear.data.model.AgentStatus
import dev.wristcontrol.wear.data.model.ConnectionState

/**
 * ProtoLayout rendering for the status tile.
 *
 * The tile has three jobs, in priority order:
 *  1. When Claude is blocked, put Approve/Deny under the thumb immediately —
 *     this is the whole point of wearing the thing.
 *  2. Otherwise, say what the agent is doing in one glance.
 *  3. Always offer a one-tap route into voice input.
 *
 * Everything is built synchronously from a [WristState] snapshot so the tile
 * request never has to block on the network.
 */
object TileLayouts {

    const val ID_ICON_APPROVE = "ic_approve"
    const val ID_ICON_DENY = "ic_deny"
    const val ID_ICON_MIC = "ic_mic"

    @ColorInt private const val COLOR_BACKGROUND = 0xFF000000.toInt()
    @ColorInt private const val COLOR_PRIMARY = 0xFFD97757.toInt()
    @ColorInt private const val COLOR_ON_PRIMARY = 0xFF1A1A18.toInt()
    @ColorInt private const val COLOR_TEXT = 0xFFF5F4EF.toInt()
    @ColorInt private const val COLOR_TEXT_DIM = 0xFF9B9992.toInt()
    @ColorInt private const val COLOR_APPROVE = 0xFF2E9E6B.toInt()
    @ColorInt private const val COLOR_DENY = 0xFFC2463B.toInt()
    @ColorInt private const val COLOR_TRACK = 0xFF3A3A38.toInt()

    fun build(context: Context, device: DeviceParameters, state: WristState): LayoutElement = when {
        !state.isAttached -> notAttachedLayout(context, device)
        state.pendingApproval != null -> approvalLayout(context, device, state)
        else -> statusLayout(context, device, state)
    }

    /** No session chosen yet (or signed out): a single chip into the app. */
    private fun notAttachedLayout(context: Context, device: DeviceParameters): LayoutElement =
        PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                label(context, "Claude Code", COLOR_PRIMARY, Typography.TYPOGRAPHY_CAPTION1)
            )
            .setContent(
                label(
                    context,
                    "No session attached",
                    COLOR_TEXT,
                    Typography.TYPOGRAPHY_BODY1,
                    maxLines = 2,
                )
            )
            .setPrimaryChipContent(
                CompactChip.Builder(context, "Choose session", launchApp(context, null), device)
                    .setChipColors(ChipColors(COLOR_PRIMARY, COLOR_ON_PRIMARY))
                    .build()
            )
            .build()

    /**
     * The Approval Gate, tile edition: the command Claude wants to run, plus
     * high-contrast deny/approve buttons. Sized so the two targets stay apart
     * enough that a glance-and-tap does not deny by accident.
     */
    private fun approvalLayout(
        context: Context,
        device: DeviceParameters,
        state: WristState,
    ): LayoutElement {
        val approval = requireNotNull(state.pendingApproval)
        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                label(context, approval.toolName.uppercase(), COLOR_PRIMARY, Typography.TYPOGRAPHY_CAPTION1)
            )
            .setContent(
                LayoutElementBuilders.Column.Builder()
                    .addContent(
                        label(
                            context,
                            approval.shortCommand.ifBlank { "Permission requested" },
                            COLOR_TEXT,
                            Typography.TYPOGRAPHY_BODY2,
                            maxLines = 2,
                        )
                    )
                    .addContent(LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build())
                    .addContent(
                        LayoutElementBuilders.Row.Builder()
                            .addContent(
                                Button.Builder(context, tileAction(context, TileAction.DENY))
                                    .setIconContent(ID_ICON_DENY)
                                    .setContentDescription("Deny")
                                    .setButtonColors(ButtonColors(COLOR_DENY, COLOR_TEXT))
                                    .setSize(dp(48f))
                                    .build()
                            )
                            .addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(12f)).build())
                            .addContent(
                                Button.Builder(context, tileAction(context, TileAction.APPROVE))
                                    .setIconContent(ID_ICON_APPROVE)
                                    .setContentDescription("Approve")
                                    .setButtonColors(ButtonColors(COLOR_APPROVE, COLOR_TEXT))
                                    .setSize(dp(48f))
                                    .build()
                            )
                            .build()
                    )
                    .build()
            )
            .setPrimaryChipContent(
                CompactChip.Builder(context, "Details", launchApproval(context), device)
                    .setChipColors(ChipColors(COLOR_TRACK, COLOR_TEXT))
                    .build()
            )
            .build()
    }

    /**
     * Idle/working view: the status ring from the dashboard, reduced to a
     * progress arc the tile renderer can draw without animation support.
     */
    private fun statusLayout(
        context: Context,
        device: DeviceParameters,
        state: WristState,
    ): LayoutElement {
        val session = state.session
        return EdgeContentLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setEdgeContent(
                CircularProgressIndicator.Builder()
                    .setProgress(progressFor(state.status))
                    .setStrokeWidth(dp(6f))
                    .setCircularProgressIndicatorColors(
                        ProgressIndicatorColors(argb(colorFor(state.status)), argb(COLOR_TRACK))
                    )
                    .setContentDescription(statusText(state))
                    .build()
            )
            .setPrimaryLabelTextContent(
                label(
                    context,
                    session?.hostName?.takeIf { it.isNotBlank() } ?: "Claude Code",
                    COLOR_TEXT_DIM,
                    Typography.TYPOGRAPHY_CAPTION2,
                )
            )
            .setContent(
                LayoutElementBuilders.Column.Builder()
                    .setModifiers(
                        ModifiersBuilders.Modifiers.Builder()
                            .setClickable(launchApp(context, session?.id))
                            .build()
                    )
                    .addContent(
                        label(
                            context,
                            statusText(state),
                            colorFor(state.status),
                            Typography.TYPOGRAPHY_TITLE3,
                        )
                    )
                    .addContent(
                        label(
                            context,
                            session?.title.orEmpty(),
                            COLOR_TEXT_DIM,
                            Typography.TYPOGRAPHY_CAPTION2,
                        )
                    )
                    .build()
            )
            .setSecondaryLabelTextContent(
                CompactChip.Builder(context, "Speak", tileAction(context, TileAction.VOICE), device)
                    .setChipColors(ChipColors(COLOR_PRIMARY, COLOR_ON_PRIMARY))
                    .build()
            )
            .build()
    }

    private fun label(
        context: Context,
        text: String,
        @ColorInt color: Int,
        typography: Int,
        maxLines: Int = 1,
    ): LayoutElement = Text.Builder(context, text)
        .setTypography(typography)
        .setColor(argb(color))
        .setMaxLines(maxLines)
        .setOverflow(LayoutElementBuilders.TEXT_OVERFLOW_ELLIPSIZE_END)
        .setMultilineAlignment(LayoutElementBuilders.TEXT_ALIGN_CENTER)
        .build()

    fun statusText(state: WristState): String = when {
        state.connection is ConnectionState.Reconnecting -> "Reconnecting"
        state.connection is ConnectionState.Failed -> "Offline"
        else -> when (state.status) {
            AgentStatus.IDLE -> "Idle"
            AgentStatus.THINKING -> "Thinking"
            AgentStatus.EXECUTING -> "Executing"
            AgentStatus.AWAITING_APPROVAL -> "Waiting on you"
            AgentStatus.ERROR -> "Error"
            AgentStatus.UNKNOWN -> "Connecting"
        }
    }

    /**
     * The arc is a mood indicator, not a measurement: Claude does not report
     * percent-complete, so each state gets a fixed, recognisable sweep.
     */
    private fun progressFor(status: AgentStatus): Float = when (status) {
        AgentStatus.IDLE -> 0.08f
        AgentStatus.THINKING -> 0.45f
        AgentStatus.EXECUTING -> 0.75f
        AgentStatus.AWAITING_APPROVAL -> 1f
        AgentStatus.ERROR -> 1f
        AgentStatus.UNKNOWN -> 0.02f
    }

    @ColorInt
    private fun colorFor(status: AgentStatus): Int = when (status) {
        AgentStatus.IDLE -> COLOR_TEXT_DIM
        AgentStatus.THINKING -> COLOR_PRIMARY
        AgentStatus.EXECUTING -> COLOR_APPROVE
        AgentStatus.AWAITING_APPROVAL -> COLOR_PRIMARY
        AgentStatus.ERROR -> COLOR_DENY
        AgentStatus.UNKNOWN -> COLOR_TRACK
    }

    @ColorInt
    fun backgroundColor(): Int = COLOR_BACKGROUND

    private fun launchApp(context: Context, sessionId: String?): ModifiersBuilders.Clickable {
        val activity = ActionBuilders.AndroidActivity.Builder()
            .setPackageName(context.packageName)
            .setClassName(MAIN_ACTIVITY)
            .addKeyToExtraMapping(
                EXTRA_DESTINATION,
                ActionBuilders.AndroidStringExtra.Builder().setValue(DESTINATION_DASHBOARD).build(),
            )
        if (sessionId != null) {
            activity.addKeyToExtraMapping(
                EXTRA_SESSION_ID,
                ActionBuilders.AndroidStringExtra.Builder().setValue(sessionId).build(),
            )
        }
        return ModifiersBuilders.Clickable.Builder()
            .setId("open_app")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(activity.build()).build())
            .build()
    }

    private fun launchApproval(context: Context): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId("open_approval")
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(MAIN_ACTIVITY)
                            .addKeyToExtraMapping(
                                EXTRA_DESTINATION,
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue(DESTINATION_APPROVAL)
                                    .build(),
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    private fun tileAction(context: Context, action: TileAction): ModifiersBuilders.Clickable =
        ModifiersBuilders.Clickable.Builder()
            .setId(action.id)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(context.packageName)
                            .setClassName(TILE_ACTION_ACTIVITY)
                            .addKeyToExtraMapping(
                                EXTRA_TILE_ACTION,
                                ActionBuilders.AndroidStringExtra.Builder()
                                    .setValue(action.id)
                                    .build(),
                            )
                            .build()
                    )
                    .build()
            )
            .build()

    const val EXTRA_DESTINATION = "dev.wristcontrol.wear.extra.DESTINATION"
    const val EXTRA_SESSION_ID = "dev.wristcontrol.wear.extra.SESSION_ID"
    const val EXTRA_TILE_ACTION = "dev.wristcontrol.wear.extra.TILE_ACTION"
    const val DESTINATION_DASHBOARD = "dashboard"
    const val DESTINATION_VOICE = "voice"
    const val DESTINATION_APPROVAL = "approval"

    private const val MAIN_ACTIVITY = "dev.wristcontrol.wear.ui.MainActivity"
    private const val TILE_ACTION_ACTIVITY = "dev.wristcontrol.wear.tile.TileActionActivity"
}

enum class TileAction(val id: String) {
    APPROVE("tile_approve"),
    DENY("tile_deny"),
    VOICE("tile_voice");

    companion object {
        fun fromId(id: String?): TileAction? = entries.firstOrNull { it.id == id }
    }
}
