package com.agentphone.app.ui.host

import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.agentphone.app.R
import com.agentphone.app.platform.CaptureCoordinator
import com.agentphone.app.platform.PermissionCoordinator
import com.agentphone.app.platform.PermissionRequest
import com.agentphone.app.platform.UiActionBus

@Composable
fun PermissionHost(coordinator: PermissionCoordinator) {
    var queue by remember { mutableStateOf<List<PermissionRequest>>(emptyList()) }
    val request = queue.firstOrNull()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        request?.let { coordinator.deliver(it.id, result) }
        queue = queue.drop(1)
    }
    LaunchedEffect(coordinator) {
        coordinator.requests.collect { incoming -> queue = queue + incoming }
    }
    request?.let { pending ->
        AlertDialog(
            onDismissRequest = {
                coordinator.deliver(pending.id, emptyMap())
                queue = queue.drop(1)
            },
            title = { Text(pending.title) },
            text = { Text(pending.rationale) },
            confirmButton = {
                TextButton(
                    onClick = {
                        launcher.launch(pending.permissions.toTypedArray())
                    },
                ) { Text(stringResource(R.string.allow_permission)) }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        coordinator.deliver(pending.id, emptyMap())
                        queue = queue.drop(1)
                    },
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
fun CaptureHost(coordinator: CaptureCoordinator) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success -> coordinator.deliver(success) }
    LaunchedEffect(coordinator) {
        coordinator.requests.collect { request ->
            try {
                launcher.launch(request.outputUri)
            } catch (e: ActivityNotFoundException) {
                coordinator.deliver(false)
                Toast.makeText(context, context.getString(R.string.no_camera_app), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                coordinator.deliver(false)
                Toast.makeText(
                    context,
                    e.message ?: context.getString(R.string.camera_failed),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}

@Composable
fun IntentHost(bus: UiActionBus) {
    val context = LocalContext.current
    LaunchedEffect(bus) {
        bus.actions.collect { intent ->
            try {
                context.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(
                    context,
                    context.getString(R.string.no_app_can_handle),
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    e.message ?: context.getString(R.string.could_not_open_action),
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }
}
