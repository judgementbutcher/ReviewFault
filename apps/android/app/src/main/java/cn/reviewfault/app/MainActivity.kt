package cn.reviewfault.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.provider.MediaStore
import cn.reviewfault.app.data.AppDatabase
import cn.reviewfault.app.data.StudyRow
import cn.reviewfault.app.core.NativeScheduler
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONArray

class MainActivity : Activity() {
    private lateinit var database: AppDatabase
    private var pendingMathSource = ""
    private var pendingCaptureUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        NativeScheduler.nativeAbiVersion()
        database = AppDatabase.get(this)
        showHome()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        showHome()
    }

    private fun showHome() {
        val now = Instant.now().epochSecond
        val dayStart = ZonedDateTime.now().toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val summary = database.dashboard(now, dayStart)

        val content = column().apply {
            setPadding(dp(22), dp(26), dp(22), dp(32))
            addView(text("ReviewFault", 30, Color.rgb(30, 54, 43)).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(text("把今天该复习的交给算法", 15, Color.DKGRAY).withTop(4))

            addView(sectionCard().apply {
                addView(text("今日学习", 20, Color.rgb(30, 54, 43)).bold())
                addView(text(
                    "逾期 ${summary.overdue}  ·  到期 ${summary.dueToday}  ·  新内容 ${summary.newItems}",
                    16, Color.DKGRAY,
                ).withTop(10))
                addView(text("预计 ${summary.estimatedMinutes} 分钟", 14, Color.GRAY).withTop(4))
                addView(primaryButton("开始复习") { showReview() }.withTop(16))
            }.withTop(24))

            addView(text("快速记录", 18, Color.rgb(30, 54, 43)).bold().withTop(26))
            addView(outlineButton("从相册 / 截图导入数学错题") { askMathSourceAndPickImage() }.withTop(12))
            addView(outlineButton("新建 408 记忆卡") { showMemoryEditor() }.withTop(10))
            addView(outlineButton("浏览 / 搜索题库") { showLibrary("") }.withTop(10))
            addView(text("数据与备份", 18, Color.rgb(30, 54, 43)).bold().withTop(26))
            addView(outlineButton("导出完整备份") { createBackupDocument() }.withTop(10))
            addView(outlineButton("从备份恢复") { chooseBackupToRestore() }.withTop(8))

            addView(sectionCard().apply {
                addView(text("学习原则", 17, Color.rgb(30, 54, 43)).bold())
                addView(text(
                    "数学先只看题面并重做；408 先主动回忆，再展开答案。跳过不会算作失败。",
                    14, Color.DKGRAY,
                ).withTop(8))
            }.withTop(26))
        }
        setContentView(scroll(content))
    }

    private fun showReview() {
        val row = database.nextForReview(Instant.now().epochSecond)
        if (row == null) {
            Toast.makeText(this, "当前没有到期内容，可以先新建一张卡片", Toast.LENGTH_LONG).show()
            return
        }
        val startedAt = Instant.now().epochSecond
        val answerPanel = column().apply { visibility = View.GONE }
        val hintPanel = column()
        var shownHints = 0
        val hints = structuredItems(row)
        val content = column().apply {
            setPadding(dp(22), dp(24), dp(22), dp(34))
            addView(text(if (row.kind == "math_problem") "数学 · 重做" else "408 · 主动回忆",
                14, Color.rgb(70, 100, 85)))
            addView(text(if (row.kind == "math_problem") "先独立完成，再看答案" else "先在脑中或纸上作答",
                25, Color.rgb(30, 54, 43)).bold().withTop(8))

            if (row.prompt.isNotBlank()) {
                addView(sectionCard().apply {
                    addView(text(reviewPrompt(row), 18, Color.rgb(28, 28, 28)))
                }.withTop(22))
            }
            database.mediaPaths(row.id).forEach { relativePath ->
                val file = File(filesDir, relativePath)
                BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                    addView(ImageView(this@MainActivity).apply {
                        setImageBitmap(bitmap)
                        adjustViewBounds = true
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        setBackgroundColor(Color.WHITE)
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(18) })
                }
            }
            if (row.kind == "math_problem") {
                addView(outlineButton("补充解答、错因与关键提示") {
                    showMathDetailsEditor(row)
                }.withTop(12))
            }
            if (row.templateType == "layered_hint" && hints.isNotEmpty()) {
                addView(hintPanel.withTop(10))
                addView(outlineButton("显示一层提示") { button ->
                    if (shownHints < hints.size) {
                        hintPanel.addView(sectionCard().apply {
                            addView(text("提示 ${shownHints + 1}：${hints[shownHints]}", 15, Color.DKGRAY))
                        }.withTop(6))
                        shownHints++
                    }
                    if (shownHints >= hints.size) button.visibility = View.GONE
                }.withTop(10))
            }

            addView(primaryButton("显示答案 / 提交作答") {
                answerPanel.visibility = View.VISIBLE
                it.visibility = View.GONE
            }.withTop(20))

            answerPanel.addView(sectionCard().apply {
                addView(text("参考答案", 15, Color.GRAY))
                val shownAnswer = reviewAnswer(row)
                addView(text(shownAnswer.ifBlank { "尚未填写解答；本次仍可按实际作答结果评分。" },
                    17, Color.rgb(28, 28, 28)).withTop(8))
            })
            answerPanel.addView(text("这次完成得怎样？", 17, Color.rgb(30, 54, 43)).bold().withTop(20))
            if (row.kind == "math_problem") {
                answerPanel.addView(ratingRow(listOf(
                    RatingAction("不会", 1, "again"),
                    RatingAction("做错", 1, "wrong"),
                    RatingAction("勉强做对", 2, "effortful"),
                    RatingAction("熟练", 4, "fluent"),
                ), row, startedAt))
            } else {
                answerPanel.addView(ratingRow(listOf(
                    RatingAction("忘记", 1, null),
                    RatingAction("困难", 2, null),
                    RatingAction("正确", 3, null),
                    RatingAction("轻松", 4, null),
                ), row, startedAt))
            }
            addView(answerPanel.withTop(16))
            addView(outlineButton("退出本次复习") { showHome() }.withTop(24))
        }
        setContentView(scroll(content))
    }

    private fun ratingRow(actions: List<RatingAction>, row: StudyRow, startedAt: Long): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            actions.forEach { action ->
                addView(Button(this@MainActivity).apply {
                    text = action.label
                    textSize = 12f
                    isAllCaps = false
                    setOnClickListener {
                        val reviewedAt = Instant.now().epochSecond
                        try {
                            val result = database.review(
                                row, action.rating, reviewedAt,
                                (reviewedAt - startedAt).toInt().coerceAtLeast(0),
                                action.mathResult,
                            )
                            val next = formatInterval(result.scheduledDays)
                            Toast.makeText(this@MainActivity, "已保存，下次约 $next 后", Toast.LENGTH_SHORT).show()
                            showReview()
                        } catch (error: Exception) {
                            Toast.makeText(this@MainActivity, error.message ?: "保存失败", Toast.LENGTH_LONG).show()
                        }
                    }
                }, LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginStart = dp(2)
                    marginEnd = dp(2)
                })
            }
        }
    }

    private fun showMemoryEditor() {
        val templateLabels = arrayOf("问答", "填空", "分层提示", "枚举", "图示遮挡", "对比")
        val templateValues = arrayOf("qa", "cloze", "layered_hint", "enumeration", "image_occlusion", "comparison")
        val subjectLabels = arrayOf("数据结构", "计算机组成原理", "操作系统", "计算机网络")
        val subjectValues = arrayOf("data_structures", "computer_organization", "operating_systems", "computer_networks")
        val template = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, templateLabels)
        }
        val subject = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, subjectLabels)
        }
        val prompt = editor("问题 / 带 {{c1::答案}} 的填空")
        val answer = editor("答案")
        val structured = editor("提示或枚举要点（每行一条）").apply {
            minLines = 3
        }
        val structuredLabel = text("提示（每行一条，可选）", 13, Color.GRAY)
        template.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                structuredLabel.text = when (templateValues[position]) {
                    "layered_hint" -> "分层提示（每行一条）"
                    "enumeration" -> "答案要点（每行一条）"
                    "image_occlusion" -> "图示遮挡将在图片编辑器阶段补充"
                    else -> "补充结构（可选）"
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        val body = column().apply {
            setPadding(dp(18), 0, dp(18), 0)
            addView(text("科目", 13, Color.GRAY))
            addView(subject)
            addView(text("模板", 13, Color.GRAY).withTop(10))
            addView(template)
            addView(prompt.withTop(10))
            addView(answer.withTop(8))
            addView(structuredLabel.withTop(10))
            addView(structured)
        }
        AlertDialog.Builder(this)
            .setTitle("新建 408 记忆卡")
            .setView(scroll(body))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存", null)
            .create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val type = templateValues[template.selectedItemPosition]
                        val lines = structured.text.toString().lineSequence()
                            .map(String::trim).filter(String::isNotEmpty).toList()
                        try {
                            validateMemoryDraft(type, prompt.text.toString(), answer.text.toString(), lines)
                            database.createMemoryCard(
                                type, prompt.text.toString(), answer.text.toString(),
                                hints = if (type == "layered_hint") lines else emptyList(),
                                answerPoints = if (type == "enumeration") lines else emptyList(),
                                subject = subjectValues[subject.selectedItemPosition],
                            )
                            dialog.dismiss()
                            Toast.makeText(this, "408 卡片已保存", Toast.LENGTH_SHORT).show()
                            showHome()
                        } catch (error: IllegalArgumentException) {
                            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                dialog.show()
            }
    }

    private fun showLibrary(initialQuery: String) {
        val query = EditText(this).apply {
            hint = "搜索题干、答案或来源"
            setText(initialQuery)
            setSingleLine(true)
        }
        val content = column().apply {
            setPadding(dp(22), dp(24), dp(22), dp(34))
            addView(text("我的题库", 28, Color.rgb(30, 54, 43)).bold())
            addView(query.withTop(14))
            addView(primaryButton("搜索") { showLibrary(query.text.toString()) }.withTop(8))
            val rows = database.search(initialQuery)
            addView(text("${rows.size} 条内容", 14, Color.GRAY).withTop(18))
            rows.forEach { row ->
                addView(sectionCard().apply {
                    addView(text(if (row.kind == "math_problem") "数学错题" else subjectLabel(row.subject),
                        13, Color.rgb(70, 100, 85)))
                    addView(text(row.prompt.ifBlank { "图片题面" }, 17, Color.rgb(28, 28, 28)).withTop(6))
                    addView(text(
                        if (row.state == 0) "新内容" else "已复习 ${row.repetitions} 次",
                        13, Color.GRAY,
                    ).withTop(6))
                    addView(outlineButton("查看") { showLibraryDetail(row) }.withTop(8))
                }.withTop(12))
            }
            addView(outlineButton("返回首页") { showHome() }.withTop(22))
        }
        setContentView(scroll(content))
    }

    private fun showLibraryDetail(row: StudyRow) {
        val content = column().apply {
            setPadding(dp(22), dp(24), dp(22), dp(34))
            addView(text(if (row.kind == "math_problem") "数学错题" else subjectLabel(row.subject),
                14, Color.rgb(70, 100, 85)))
            addView(text(row.prompt.ifBlank { "图片题面" }, 22, Color.rgb(30, 54, 43)).bold().withTop(8))
            database.mediaPaths(row.id).forEach { relative ->
                BitmapFactory.decodeFile(File(filesDir, relative).absolutePath)?.let { bitmap ->
                    addView(ImageView(this@MainActivity).apply {
                        setImageBitmap(bitmap); adjustViewBounds = true
                    }, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(14) })
                }
            }
            addView(sectionCard().apply {
                addView(text("答案 / 解答", 14, Color.GRAY))
                addView(text(reviewAnswer(row).ifBlank { "尚未填写" }, 17, Color.DKGRAY).withTop(6))
            }.withTop(16))
            if (row.kind == "math_problem") {
                addView(primaryButton("编辑错题复盘") { showMathDetailsEditor(row) }.withTop(14))
            } else {
                addView(primaryButton("编辑记忆卡") { showMemoryCardEditor(row) }.withTop(14))
            }
            addView(outlineButton("返回题库") { showLibrary("") }.withTop(10))
        }
        setContentView(scroll(content))
    }

    private fun subjectLabel(subject: String) = when (subject) {
        "data_structures" -> "408 · 数据结构"
        "computer_organization" -> "408 · 计算机组成原理"
        "operating_systems" -> "408 · 操作系统"
        "computer_networks" -> "408 · 计算机网络"
        else -> subject
    }

    private fun structuredItems(row: StudyRow): List<String> = try {
        val array = JSONArray(row.structuredJson)
        buildList { for (index in 0 until array.length()) add(array.getString(index)) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun reviewPrompt(row: StudyRow): String {
        if (row.templateType != "cloze") return row.prompt
        return Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?}}")
            .replace(row.prompt, "[…]")
    }

    private fun reviewAnswer(row: StudyRow): String = when (row.templateType) {
        "cloze" -> Regex("\\{\\{c\\d+::(.*?)(?:::[^}]*)?}}")
            .findAll(row.prompt).map { it.groupValues[1] }.joinToString("\n")
        "enumeration" -> structuredItems(row).joinToString("\n") { "• $it" }
        else -> row.answer
    }

    private fun showMathDetailsEditor(row: StudyRow) {
        val reasons = arrayOf("未选择", "概念不清", "思路中断", "计算错误", "审题错误", "遗忘结论", "超时", "其他")
        val reasonValues = arrayOf<String?>(null, "concept", "approach", "calculation", "misread", "forgotten_fact", "timeout", "other")
        val reason = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, reasons)
        }
        val solution = editor("完整解答").apply { setText(row.answer); minLines = 4 }
        val wrongStep = editor("自己的关键错误步骤").apply { minLines = 3 }
        val hint = editor("下次看到题时必须想起的一句提示")
        val body = column().apply {
            setPadding(dp(18), 0, dp(18), 0)
            addView(text("主要错因", 13, Color.GRAY))
            addView(reason)
            addView(solution.withTop(8))
            addView(wrongStep.withTop(8))
            addView(hint.withTop(8))
        }
        AlertDialog.Builder(this)
            .setTitle("完善数学错题")
            .setView(scroll(body))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                try {
                    database.updateMathDetails(
                        row.id, solution.text.toString(), wrongStep.text.toString(),
                        hint.text.toString(), reasonValues[reason.selectedItemPosition],
                    )
                    Toast.makeText(this, "错题复盘已保存", Toast.LENGTH_SHORT).show()
                    showReview()
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "保存失败", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun showMemoryCardEditor(row: StudyRow) {
        val prompt = editor("题干").apply { setText(row.prompt); minLines = 3 }
        val answer = editor("答案").apply { setText(row.answer); minLines = 4 }
        val body = column().apply {
            setPadding(dp(18), 0, dp(18), 0)
            addView(prompt)
            addView(answer.withTop(8))
        }
        AlertDialog.Builder(this)
            .setTitle("编辑 408 记忆卡")
            .setView(scroll(body))
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                try {
                    database.updateMemoryCard(row.id, prompt.text.toString(), answer.text.toString())
                    Toast.makeText(this, "记忆卡已更新", Toast.LENGTH_SHORT).show()
                    showLibrary("")
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "保存失败", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun validateMemoryDraft(type: String, prompt: String, answer: String, lines: List<String>) {
        require(prompt.isNotBlank()) { "题干不能为空" }
        when (type) {
            "qa", "comparison" -> require(answer.isNotBlank()) { "答案不能为空" }
            "cloze" -> require(Regex("\\{\\{c\\d+::.+?}}").containsMatchIn(prompt)) {
                "填空题干需要包含 {{c1::答案}} 标记"
            }
            "layered_hint" -> {
                require(answer.isNotBlank()) { "答案不能为空" }
                require(lines.isNotEmpty()) { "至少填写一层提示" }
            }
            "enumeration" -> require(lines.size >= 2) { "枚举卡至少需要两个答案要点" }
            "image_occlusion" -> require(false) {
                "图示遮挡需要先选择图片；当前版本请使用分层提示卡"
            }
        }
    }

    private fun askMathSourceAndPickImage() {
        val source = editor("来源（可选，例如：张宇 1000 题 P32）")
        AlertDialog.Builder(this)
            .setTitle("数学错题快速录入")
            .setMessage("先保存题面，解答和错因可以复习后再补。")
            .setView(source)
            .setNegativeButton("取消", null)
            .setPositiveButton("从相册选择") { _, _ ->
                pendingMathSource = source.text.toString()
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_MATH_IMAGE)
            }
            .setNeutralButton("拍照") { _, _ ->
                pendingMathSource = source.text.toString()
                val name = "${UUID.randomUUID()}.jpg"
                val uri = Uri.Builder().scheme("content")
                    .authority("$packageName.capture").appendPath(name).build()
                pendingCaptureUri = uri
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                    putExtra(MediaStore.EXTRA_OUTPUT, uri)
                    addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = android.content.ClipData.newRawUri("ReviewFault 题面", uri)
                }
                if (intent.resolveActivity(packageManager) == null) {
                    pendingCaptureUri = null
                    contentResolver.delete(uri, null, null)
                    Toast.makeText(this, "设备上没有可用的相机应用", Toast.LENGTH_LONG).show()
                } else {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, REQUEST_MATH_CAMERA)
                }
            }
            .show()
    }

    @Deprecated("Kept for API 26 compatibility without an AndroidX activity dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MATH_IMAGE && resultCode == RESULT_OK) {
            val uris = buildList {
                data?.clipData?.let { clips ->
                    for (index in 0 until minOf(clips.itemCount, 5)) add(clips.getItemAt(index).uri)
                }
                if (isEmpty()) data?.data?.let(::add)
            }
            if (uris.isEmpty()) return
            try {
                database.createMathProblemFromImages(contentResolver, uris, pendingMathSource)
                Toast.makeText(this, "${uris.size} 张题面已保存，稍后可直接重做", Toast.LENGTH_SHORT).show()
                showHome()
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "图片保存失败", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_MATH_CAMERA) {
            val uri = pendingCaptureUri
            pendingCaptureUri = null
            if (resultCode == RESULT_OK && uri != null) {
                try {
                    database.createMathProblemFromImage(contentResolver, uri, pendingMathSource)
                    Toast.makeText(this, "题面照片已保存，稍后可直接重做", Toast.LENGTH_SHORT).show()
                    showHome()
                } catch (error: Exception) {
                    Toast.makeText(this, error.message ?: "照片保存失败", Toast.LENGTH_LONG).show()
                } finally {
                    contentResolver.delete(uri, null, null)
                }
            } else if (uri != null) {
                contentResolver.delete(uri, null, null)
            }
        } else if (requestCode == REQUEST_EXPORT_BACKUP && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri, "w")?.use(database::exportBackup)
                    ?: error("无法创建备份文件")
                Toast.makeText(this, "完整备份已导出", Toast.LENGTH_LONG).show()
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "导出失败", Toast.LENGTH_LONG).show()
            }
        } else if (requestCode == REQUEST_RESTORE_BACKUP && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.openInputStream(uri)?.use(database::restoreBackup)
                    ?: error("无法读取备份文件")
                Toast.makeText(this, "数据已恢复", Toast.LENGTH_LONG).show()
                showHome()
            } catch (error: Exception) {
                Toast.makeText(this, error.message ?: "恢复失败", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun createBackupDocument() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(Intent.EXTRA_TITLE, "ReviewFault-${java.time.LocalDate.now()}.reviewfault")
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_EXPORT_BACKUP)
    }

    private fun chooseBackupToRestore() {
        AlertDialog.Builder(this)
            .setTitle("从备份恢复？")
            .setMessage("恢复会替换当前设备上的数据库和媒体。建议先导出当前备份。")
            .setNegativeButton("取消", null)
            .setPositiveButton("选择备份") { _, _ ->
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "application/zip"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(intent, REQUEST_RESTORE_BACKUP)
            }
            .show()
    }

    private fun primaryButton(label: String, action: (View) -> Unit) = Button(this).apply {
        text = label
        textSize = 16f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.rgb(49, 92, 73))
        setOnClickListener { view -> action(view) }
        minHeight = dp(54)
    }

    private fun outlineButton(label: String, action: (View) -> Unit) = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setOnClickListener { view -> action(view) }
        minHeight = dp(52)
    }

    private fun sectionCard() = column().apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        setBackgroundColor(Color.WHITE)
        elevation = dp(2).toFloat()
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun scroll(child: View) = ScrollView(this).apply {
        setBackgroundColor(Color.rgb(247, 245, 239))
        isFillViewport = true
        addView(child)
    }

    private fun text(value: String, size: Int, color: Int) = TextView(this).apply {
        text = value
        textSize = size.toFloat()
        setTextColor(color)
        setLineSpacing(0f, 1.2f)
    }

    private fun editor(hintText: String) = EditText(this).apply {
        hint = hintText
        textSize = 15f
        setSingleLine(false)
        minLines = 2
    }

    private fun <T : View> T.withTop(value: Int): T = apply {
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(value) }
    }

    private fun TextView.bold(): TextView = apply {
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun formatInterval(days: Double): String = when {
        days < 1.0 / 24.0 -> "${(days * 24 * 60).roundToInt()} 分钟"
        days < 1.0 -> "${(days * 24).roundToInt()} 小时"
        else -> "${days.roundToInt()} 天"
    }

    private data class RatingAction(val label: String, val rating: Int, val mathResult: String?)

    companion object {
        private const val REQUEST_MATH_IMAGE = 4108
        private const val REQUEST_EXPORT_BACKUP = 4109
        private const val REQUEST_RESTORE_BACKUP = 4110
        private const val REQUEST_MATH_CAMERA = 4111
    }
}
