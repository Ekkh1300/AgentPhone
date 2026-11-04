package com.agentphone.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.StaticLayout
import android.text.TextPaint
import com.agentphone.app.domain.agent.ToolArtifact
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PdfGenerator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workspace: WorkspaceFiles,
) {
    fun generate(title: String, body: String): ToolArtifact {
        val directory = workspace.resolve("Exports").apply { mkdirs() }
        val safeTitle = title.take(60).replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "document" }
        val file = File(directory, "$safeTitle.pdf")
        val document = PdfDocument()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 11.5f
            typeface = Typeface.SANS_SERIF
        }
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 19f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val width = PAGE_WIDTH - 2 * MARGIN
        val bodyLayout = StaticLayout.Builder.obtain(body, 0, body.length, paint, width)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(true)
            .build()
        var pageNumber = 1
        var yOffset = 0f
        val firstPageContentHeight = PAGE_HEIGHT - 2 * MARGIN - 48f

        while (yOffset < bodyLayout.height || pageNumber == 1) {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create(),
            )
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            if (pageNumber == 1) {
                canvas.drawText(title.take(100), MARGIN.toFloat(), (MARGIN + 16f), titlePaint)
            }
            canvas.save()
            canvas.translate(MARGIN.toFloat(), MARGIN + 48f - yOffset)
            bodyLayout.draw(canvas)
            canvas.restore()
            val footer = "AgentPhone · page $pageNumber"
            val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GRAY
                textSize = 9f
            }
            canvas.drawText(footer, MARGIN.toFloat(), PAGE_HEIGHT - 22f, footerPaint)
            document.finishPage(page)
            if (yOffset + firstPageContentHeight >= bodyLayout.height) break
            yOffset += firstPageContentHeight
            pageNumber++
            if (pageNumber > 100) break
        }
        file.outputStream().use { document.writeTo(it) }
        document.close()
        return workspace.artifact(file, "application/pdf")
    }

    private companion object {
        const val PAGE_WIDTH = 595
        const val PAGE_HEIGHT = 842
        const val MARGIN = 48
    }
}
