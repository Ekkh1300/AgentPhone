package com.agentphone.app.tools

import android.content.Context
import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.domain.agent.RiskLevel
import com.agentphone.app.domain.agent.ToolCategory
import com.agentphone.app.domain.agent.ToolContext
import com.agentphone.app.domain.agent.ToolResult
import com.agentphone.app.platform.CaptureCoordinator
import com.agentphone.app.platform.PermissionCoordinator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject

class CameraCaptureTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCoordinator,
    private val capture: CaptureCoordinator,
) : AgentTool {
    override val name = "camera_capture"
    override val description =
        "Open the camera to take a photo, then attach the captured image so the model can see it."
    override val riskLevel = RiskLevel.WRITE
    override val category = ToolCategory.MEDIA
    override val parameters = Schemas.obj(
        properties = buildJsonObject {
            put("reason", Schemas.string("Optional short reason shown to the user."))
        },
    )

    override suspend fun execute(context: ToolContext, arguments: JsonObject): ToolResult {
        val outcome = permissions.ensureCamera()
        if (!outcome.granted) return ToolResult.error("Camera permission was denied.")
        val path = capture.capturePhoto()
            ?: return ToolResult.error("No photo was captured (the camera was cancelled or unavailable).")
        return ToolResult(
            output = "Captured a photo at $path. The image is attached for vision analysis.",
            images = listOf(path),
        )
    }
}
