package com.agentphone.app.di

import com.agentphone.app.domain.agent.AgentTool
import com.agentphone.app.tools.AirplaneModeTool
import com.agentphone.app.tools.AlarmSetTool
import com.agentphone.app.tools.AppInfoTool
import com.agentphone.app.tools.AppInstallTool
import com.agentphone.app.tools.AppUninstallTool
import com.agentphone.app.tools.AppsListTool
import com.agentphone.app.tools.AppsUsageTool
import com.agentphone.app.tools.AudioRecordTool
import com.agentphone.app.tools.BluetoothDevicesTool
import com.agentphone.app.tools.BrightnessTool
import com.agentphone.app.tools.CalendarCreateTool
import com.agentphone.app.tools.CalendarDeleteTool
import com.agentphone.app.tools.CalendarListTool
import com.agentphone.app.tools.CalendarUpdateTool
import com.agentphone.app.tools.CallLogTool
import com.agentphone.app.tools.CameraCaptureTool
import com.agentphone.app.tools.ClipboardReadTool
import com.agentphone.app.tools.ClipboardWriteTool
import com.agentphone.app.tools.ContactCreateTool
import com.agentphone.app.tools.ContactsSearchTool
import com.agentphone.app.tools.DeviceInfoTool
import com.agentphone.app.tools.DialNumberTool
import com.agentphone.app.tools.DisplayControlTool
import com.agentphone.app.tools.DndTool
import com.agentphone.app.tools.EmailComposeTool
import com.agentphone.app.tools.FileOpenTool
import com.agentphone.app.tools.FilesAppendTool
import com.agentphone.app.tools.FilesCopyTool
import com.agentphone.app.tools.FilesDeleteTool
import com.agentphone.app.tools.FilesEditTool
import com.agentphone.app.tools.FilesExportPdfTool
import com.agentphone.app.tools.FilesInfoTool
import com.agentphone.app.tools.FilesListTool
import com.agentphone.app.tools.FilesMkdirTool
import com.agentphone.app.tools.FilesMoveTool
import com.agentphone.app.tools.FilesReadTool
import com.agentphone.app.tools.FilesRootsTool
import com.agentphone.app.tools.FilesSaveDownloadTool
import com.agentphone.app.tools.FilesSearchTool
import com.agentphone.app.tools.FilesWriteTool
import com.agentphone.app.tools.FlashlightTool
import com.agentphone.app.tools.LocationTool
import com.agentphone.app.tools.McpCallTool
import com.agentphone.app.tools.McpListToolsTool
import com.agentphone.app.tools.MediaControlTool
import com.agentphone.app.tools.MemoryReadTool
import com.agentphone.app.tools.MemoryWriteTool
import com.agentphone.app.tools.NetworkInfoTool
import com.agentphone.app.tools.NotificationActionTool
import com.agentphone.app.tools.NotificationPostTool
import com.agentphone.app.tools.NotificationsListTool
import com.agentphone.app.tools.OpenAppTool
import com.agentphone.app.tools.OpenMapsTool
import com.agentphone.app.tools.OpenSettingsTool
import com.agentphone.app.tools.OpenUrlTool
import com.agentphone.app.tools.QuickToggleTool
import com.agentphone.app.tools.ReminderScheduleTool
import com.agentphone.app.tools.RingerModeTool
import com.agentphone.app.tools.ScreenActionTool
import com.agentphone.app.tools.ScreenSnapshotTool
import com.agentphone.app.tools.SensorReadTool
import com.agentphone.app.tools.ShareTextTool
import com.agentphone.app.tools.SimInfoTool
import com.agentphone.app.tools.SmsListTool
import com.agentphone.app.tools.SmsSendTool
import com.agentphone.app.tools.SpeakTool
import com.agentphone.app.tools.SystemSettingsTool
import com.agentphone.app.tools.TimerSetTool
import com.agentphone.app.tools.VibrateTool
import com.agentphone.app.tools.VolumeControlTool
import com.agentphone.app.tools.WallpaperSetTool
import com.agentphone.app.tools.WebFetchTool
import com.agentphone.app.tools.WebSearchTool
import com.agentphone.app.tools.WifiScanTool
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class ToolBindingsModule {

    @Binds @IntoSet abstract fun filesList(tool: FilesListTool): AgentTool
    @Binds @IntoSet abstract fun filesRead(tool: FilesReadTool): AgentTool
    @Binds @IntoSet abstract fun filesWrite(tool: FilesWriteTool): AgentTool
    @Binds @IntoSet abstract fun filesAppend(tool: FilesAppendTool): AgentTool
    @Binds @IntoSet abstract fun filesEdit(tool: FilesEditTool): AgentTool
    @Binds @IntoSet abstract fun filesMkdir(tool: FilesMkdirTool): AgentTool
    @Binds @IntoSet abstract fun filesDelete(tool: FilesDeleteTool): AgentTool
    @Binds @IntoSet abstract fun filesMove(tool: FilesMoveTool): AgentTool
    @Binds @IntoSet abstract fun filesCopy(tool: FilesCopyTool): AgentTool
    @Binds @IntoSet abstract fun filesSearch(tool: FilesSearchTool): AgentTool
    @Binds @IntoSet abstract fun filesInfo(tool: FilesInfoTool): AgentTool
    @Binds @IntoSet abstract fun filesRoots(tool: FilesRootsTool): AgentTool
    @Binds @IntoSet abstract fun filesExportPdf(tool: FilesExportPdfTool): AgentTool
    @Binds @IntoSet abstract fun filesSaveDownload(tool: FilesSaveDownloadTool): AgentTool

    @Binds @IntoSet abstract fun clipboardRead(tool: ClipboardReadTool): AgentTool
    @Binds @IntoSet abstract fun clipboardWrite(tool: ClipboardWriteTool): AgentTool
    @Binds @IntoSet abstract fun shareText(tool: ShareTextTool): AgentTool
    @Binds @IntoSet abstract fun openUrl(tool: OpenUrlTool): AgentTool
    @Binds @IntoSet abstract fun openMaps(tool: OpenMapsTool): AgentTool
    @Binds @IntoSet abstract fun dialNumber(tool: DialNumberTool): AgentTool
    @Binds @IntoSet abstract fun openApp(tool: OpenAppTool): AgentTool
    @Binds @IntoSet abstract fun openSettings(tool: OpenSettingsTool): AgentTool

    @Binds @IntoSet abstract fun deviceInfo(tool: DeviceInfoTool): AgentTool
    @Binds @IntoSet abstract fun location(tool: LocationTool): AgentTool
    @Binds @IntoSet abstract fun calendarList(tool: CalendarListTool): AgentTool
    @Binds @IntoSet abstract fun calendarCreate(tool: CalendarCreateTool): AgentTool
    @Binds @IntoSet abstract fun contacts(tool: ContactsSearchTool): AgentTool
    @Binds @IntoSet abstract fun alarm(tool: AlarmSetTool): AgentTool
    @Binds @IntoSet abstract fun timer(tool: TimerSetTool): AgentTool
    @Binds @IntoSet abstract fun reminder(tool: ReminderScheduleTool): AgentTool
    @Binds @IntoSet abstract fun notification(tool: NotificationPostTool): AgentTool
    @Binds @IntoSet abstract fun camera(tool: CameraCaptureTool): AgentTool

    @Binds @IntoSet abstract fun webFetch(tool: WebFetchTool): AgentTool
    @Binds @IntoSet abstract fun webSearch(tool: WebSearchTool): AgentTool
    @Binds @IntoSet abstract fun memoryRead(tool: MemoryReadTool): AgentTool
    @Binds @IntoSet abstract fun memoryWrite(tool: MemoryWriteTool): AgentTool

    @Binds @IntoSet abstract fun flashlight(tool: FlashlightTool): AgentTool
    @Binds @IntoSet abstract fun volume(tool: VolumeControlTool): AgentTool
    @Binds @IntoSet abstract fun brightness(tool: BrightnessTool): AgentTool
    @Binds @IntoSet abstract fun dnd(tool: DndTool): AgentTool
    @Binds @IntoSet abstract fun airplane(tool: AirplaneModeTool): AgentTool
    @Binds @IntoSet abstract fun displayControl(tool: DisplayControlTool): AgentTool
    @Binds @IntoSet abstract fun ringer(tool: RingerModeTool): AgentTool
    @Binds @IntoSet abstract fun media(tool: MediaControlTool): AgentTool
    @Binds @IntoSet abstract fun quickToggle(tool: QuickToggleTool): AgentTool
    @Binds @IntoSet abstract fun systemSettings(tool: SystemSettingsTool): AgentTool
    @Binds @IntoSet abstract fun notificationsList(tool: NotificationsListTool): AgentTool
    @Binds @IntoSet abstract fun notificationAction(tool: NotificationActionTool): AgentTool
    @Binds @IntoSet abstract fun appsList(tool: AppsListTool): AgentTool
    @Binds @IntoSet abstract fun appInfo(tool: AppInfoTool): AgentTool
    @Binds @IntoSet abstract fun appUninstall(tool: AppUninstallTool): AgentTool
    @Binds @IntoSet abstract fun appsUsage(tool: AppsUsageTool): AgentTool

    @Binds @IntoSet abstract fun screenSnapshot(tool: ScreenSnapshotTool): AgentTool
    @Binds @IntoSet abstract fun screenAction(tool: ScreenActionTool): AgentTool
    @Binds @IntoSet abstract fun mcpList(tool: McpListToolsTool): AgentTool
    @Binds @IntoSet abstract fun mcpCall(tool: McpCallTool): AgentTool

    @Binds @IntoSet abstract fun smsList(tool: SmsListTool): AgentTool
    @Binds @IntoSet abstract fun smsSend(tool: SmsSendTool): AgentTool
    @Binds @IntoSet abstract fun callLog(tool: CallLogTool): AgentTool
    @Binds @IntoSet abstract fun contactCreate(tool: ContactCreateTool): AgentTool
    @Binds @IntoSet abstract fun emailCompose(tool: EmailComposeTool): AgentTool
    @Binds @IntoSet abstract fun calendarUpdate(tool: CalendarUpdateTool): AgentTool
    @Binds @IntoSet abstract fun calendarDelete(tool: CalendarDeleteTool): AgentTool

    @Binds @IntoSet abstract fun speak(tool: SpeakTool): AgentTool
    @Binds @IntoSet abstract fun audioRecord(tool: AudioRecordTool): AgentTool
    @Binds @IntoSet abstract fun wallpaperSet(tool: WallpaperSetTool): AgentTool
    @Binds @IntoSet abstract fun vibrate(tool: VibrateTool): AgentTool
    @Binds @IntoSet abstract fun sensorRead(tool: SensorReadTool): AgentTool

    @Binds @IntoSet abstract fun networkInfo(tool: NetworkInfoTool): AgentTool
    @Binds @IntoSet abstract fun wifiScan(tool: WifiScanTool): AgentTool
    @Binds @IntoSet abstract fun bluetoothDevices(tool: BluetoothDevicesTool): AgentTool
    @Binds @IntoSet abstract fun simInfo(tool: SimInfoTool): AgentTool

    @Binds @IntoSet abstract fun fileOpen(tool: FileOpenTool): AgentTool
    @Binds @IntoSet abstract fun appInstall(tool: AppInstallTool): AgentTool
}
