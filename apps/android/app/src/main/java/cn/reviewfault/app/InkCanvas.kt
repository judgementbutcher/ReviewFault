package cn.reviewfault.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream
import org.json.JSONArray
import org.json.JSONObject

data class InkPoint(val x: Float, val y: Float, val pressure: Float, val time: Long)
data class InkStroke(
    val tool: String, val color: String, val width: Float, val points: List<InkPoint>,
)
data class InkPage(val id: String = UUID.randomUUID().toString(), val strokes: List<InkStroke> = emptyList())

class InkState {
    var pages by mutableStateOf(listOf(InkPage()))
        private set
    var currentPage by mutableIntStateOf(0)
        private set
    var tool by mutableStateOf("pen")
    var color by mutableStateOf("#ff172033")
    var width by mutableStateOf(0.006f)
    var working by mutableStateOf<InkStroke?>(null)
        private set
    var version by mutableIntStateOf(0)
        private set
    private val undo = ArrayDeque<List<InkPage>>()
    private val redo = ArrayDeque<List<InkPage>>()

    fun begin(point: InkPoint) { working = InkStroke(tool, color, width, listOf(point)) }
    fun append(point: InkPoint) { working = working?.copy(points = working!!.points + point) }
    fun finish() {
        val stroke = working ?: return
        checkpoint(); pages = pages.toMutableList().also { it[currentPage] = it[currentPage].copy(strokes = it[currentPage].strokes + stroke) }
        working = null; redo.clear(); version++
    }
    fun undo() { if (undo.isNotEmpty()) { redo.addLast(pages); pages = undo.removeLast(); currentPage = currentPage.coerceAtMost(pages.lastIndex); version++ } }
    fun redo() { if (redo.isNotEmpty()) { undo.addLast(pages); pages = redo.removeLast(); currentPage = currentPage.coerceAtMost(pages.lastIndex); version++ } }
    fun addPage() { checkpoint(); pages = pages + InkPage(); currentPage = pages.lastIndex; redo.clear(); version++ }
    fun selectPage(index: Int) { currentPage = index.coerceIn(0, pages.lastIndex) }
    fun deletePage() {
        checkpoint(); pages = if (pages.size == 1) listOf(InkPage()) else pages.toMutableList().also { it.removeAt(currentPage) }
        currentPage = currentPage.coerceAtMost(pages.lastIndex); redo.clear(); version++
    }
    private fun checkpoint() { undo.addLast(pages); if (undo.size > 100) undo.removeFirst() }

    fun gzipJson(): ByteArray {
        val json = JSONObject().apply {
            put("format", "reviewfault-ink"); put("version", 1)
            put("pages", JSONArray(pages.map { page -> JSONObject().apply {
                put("id", page.id); put("backgroundMediaSha256", JSONObject.NULL)
                put("strokes", JSONArray(page.strokes.map { stroke -> JSONObject().apply {
                    put("tool", stroke.tool); put("color", stroke.color); put("width", stroke.width)
                    put("points", JSONArray(stroke.points.map { point -> JSONObject().apply {
                        put("x", point.x); put("y", point.y); put("pressure", point.pressure)
                        put("tiltX", 0); put("tiltY", 0); put("time", point.time)
                    } }))
                } }))
            } }))
        }.toString().toByteArray()
        return ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(json) }; output.toByteArray()
        }
    }
}

@Composable
fun InkPad(state: InkState = remember { InkState() }, onDocumentChanged: (ByteArray) -> Unit) {
    var widthPx by remember { mutableIntStateOf(1) }
    var heightPx by remember { mutableIntStateOf(1) }
    LaunchedEffect(state.version) { if (state.version > 0) onDocumentChanged(state.gzipJson()) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row {
                listOf("pen" to Icons.Default.Edit, "highlighter" to Icons.Default.Brush,
                    "eraser" to Icons.Default.DeleteOutline).forEach { (tool, icon) ->
                    IconButton({ state.tool = tool }, Modifier.size(48.dp)) {
                        Icon(icon, contentDescription = tool, tint = if (state.tool == tool) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(state::undo, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Undo, "撤销") }
                IconButton(state::redo, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.Redo, "重做") }
            }
            Row {
                IconButton(state::addPage, Modifier.size(48.dp)) { Icon(Icons.Default.Add, "添加演算页") }
                IconButton(state::deletePage, Modifier.size(48.dp)) { Icon(Icons.Default.DeleteOutline, "删除当前页") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("#ff172033" to Color(0xFF172033), "#ffb3261e" to Color(0xFFB3261E),
                "#ff315c49" to Color(0xFF315C49), "#ffd99b52" to Color(0xFFD99B52)).forEach { (value, shown) ->
                Surface(onClick = { state.color = value }, modifier = Modifier.size(48.dp),
                    shape = RoundedCornerShape(8.dp), color = shown,
                    border = if (state.color == value) androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.primary) else null) {}
            }
            listOf(0.004f to "细", 0.008f to "中", 0.014f to "粗").forEach { (value, label) ->
                FilterChip(selected = state.width == value, onClick = { state.width = value }, label = { Text(label) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            state.pages.indices.forEach { index -> FilterChip(selected = state.currentPage == index,
                onClick = { state.selectPage(index) }, label = { Text("${index + 1}") }) }
        }
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.White, RoundedCornerShape(8.dp))
                .onSizeChanged { widthPx = it.width.coerceAtLeast(1); heightPx = it.height.coerceAtLeast(1) }
                .pointerInput(state.currentPage, state.tool, state.color, state.width) {
                    detectDragGestures(
                        onDragStart = { offset -> state.begin(InkPoint(offset.x / widthPx, offset.y / heightPx, 1f, System.currentTimeMillis())) },
                        onDragEnd = state::finish,
                        onDragCancel = state::finish,
                    ) { change, _ -> change.consume(); state.append(InkPoint(change.position.x / widthPx, change.position.y / heightPx, 1f, System.currentTimeMillis())) }
                },
        ) {
            val all = state.pages[state.currentPage].strokes + listOfNotNull(state.working)
            all.forEach { stroke ->
                if (stroke.points.isEmpty()) return@forEach
                val path = Path().apply { moveTo(stroke.points.first().x * size.width, stroke.points.first().y * size.height); stroke.points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) } }
                val shown = if (stroke.tool == "eraser") Color.White else Color(android.graphics.Color.parseColor(stroke.color))
                drawPath(path, shown.copy(alpha = if (stroke.tool == "highlighter") .35f else 1f), style = Stroke(width = stroke.width * size.minDimension, cap = StrokeCap.Round))
            }
        }
    }
}
