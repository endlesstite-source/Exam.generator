package com.example.cbeexamgenerator

import android.app.Dialog
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import android.widget.ArrayAdapter
import android.widget.AdapterView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.text.SimpleDateFormat
import java.util.*

// ==================== DATA CLASSES ====================
data class Subject(val id: Int, val name: String, val grade: Int) {
    fun displayName(): String = name.replace(Regex(" Grade \\d+$"), "")
}
data class Topic(val id: Int, val name: String, val question_count: Int)
data class GenerateRequest(
    val subject_id: Int, val term: Int, val exam_type: String, val cat_number: Int?,
    val template: String, val school_name: String, val total_marks: Int,
    val mcq_marks: Int, val structured_marks_min: Int, val structured_marks_max: Int,
    val duration_hours: Int, val duration_minutes: Int, val include_marking_scheme: Boolean,
    val include_images: Boolean, val cross_grade: Boolean, val question_format: String,
    val topic_ids: List<Int>?, val avoid_question_ids: List<Int>
)
data class GenerateResponse(val exam_paper: String, val marking_scheme: String?, val total_marks: Int, val questions: List<Question>)
data class Question(val id: Int, val text: String)
data class SavedPaper(val subject: String, val date: String, val paper: String, val marking: String?)

// ==================== API ====================
interface ApiService {
    @GET("subjects/") suspend fun getSubjects(): List<Subject>
    @GET("subjects/{id}/topics") suspend fun getTopics(@Path("id") id: Int): List<Topic>
    @POST("generate-exam") suspend fun generateExam(@Body request: GenerateRequest): GenerateResponse
}

object RetrofitClient {
    val api: ApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://exams-gen.onrender.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

// ==================== MAIN ACTIVITY ====================
class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private val tabTitles = listOf("Home", "Topical", "Headers", "Bulk", "Saved")
    private var allSubjects = listOf<Subject>()
    private var selectedTemplate = "classic"
    private val savedPapers = mutableListOf<SavedPaper>()
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("saved_papers", MODE_PRIVATE)

        try {
            val root = createMainLayout()
            setContentView(root)
            loadSavedPapers()
            loadSubjectsSafely()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun createMainLayout(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        root.addView(createHeader())
        val tabLayout = TabLayout(this).apply {
            tabGravity = TabLayout.GRAVITY_FILL
            tabMode = TabLayout.MODE_SCROLLABLE
        }
        root.addView(tabLayout)
        viewPager = ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            adapter = TabPagerAdapter()
        }
        root.addView(viewPager)
        TabLayoutMediator(tabLayout, viewPager) { tab, pos -> tab.text = tabTitles[pos] }.attach()
        return root
    }

    private fun createHeader(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF16a34a.toInt())
            setPadding(16, 12, 16, 12)
            addView(TextView(context).apply {
                text = "CBE Exam Generator"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
            })
            addView(TextView(context).apply {
                text = "Competency Based Education Assessments"
                textSize = 11f
                setTextColor(0xFFbbf7d0.toInt())
            })
        }
    }

    // ==================== TAB VIEWS ====================
    private fun createHomeView(): ScrollView {
        val ctx = this
        val scroll = ScrollView(ctx).apply { setPadding(16, 16, 16, 16) }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(20, 20, 20, 20)
        }

        card.addView(createLabel("School Name (optional)"))
        val schoolName = createEditText("e.g., Sunshine Academy")
        card.addView(schoolName)

        card.addView(createLabel("Learning Area"))
        val subjectSpinner = createSpinner(listOf())
        card.addView(subjectSpinner)

        val gradeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        gradeRow.addView(createLabel("Grade").apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        })
        val gradeInput = createEditText("8", num = true)
        gradeRow.addView(gradeInput)
        card.addView(gradeRow)

        val termSpinner = createSpinner(listOf("1","2","3"))
        val typeSpinner = createSpinner(listOf("End Term","Mid Term","Opener","CAT"))
        card.addView(createDualRow("Term" to termSpinner, "Type" to typeSpinner))

        val catLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        catLayout.addView(createLabel("CAT Number"))
        val catNumber = createSpinner(listOf("1","2","3"))
        catLayout.addView(catNumber)
        catLayout.addView(createLabel("Question Format"))
        val catFormat = createSpinner(listOf("Mixed","MCQ Only","Structured Only"))
        catLayout.addView(catFormat)
        card.addView(catLayout)

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                catLayout.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        card.addView(createLabel("Total Marks"))
        val totalMarks = createEditText("50", num = true)
        card.addView(totalMarks)

        val durRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        durRow.addView(createLabel("Hours").apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
        val durH = createEditText("1", num = true)
        durRow.addView(durH)
        durRow.addView(createLabel("Minutes").apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
        val durM = createEditText("30", num = true)
        durRow.addView(durM)
        card.addView(durRow)

        val checkRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val includeImages = CheckBox(ctx).apply { text = "Include Diagrams"; isChecked = true }
        val crossGrade = CheckBox(ctx).apply { text = "Cross‑Grade Content"; isChecked = true }
        checkRow.addView(includeImages)
        checkRow.addView(crossGrade)
        card.addView(checkRow)

        val genBtn = Button(ctx).apply {
            text = "Generate Paper"
            setBackgroundColor(0xFF16a34a.toInt())
        }
        val status = TextView(ctx).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 13f; setPadding(0,8,0,0) }
        card.addView(genBtn)
        card.addView(status)

        genBtn.setOnClickListener {
            val subjName = subjectSpinner.selectedItem as? String ?: ""
            val subj = allSubjects.find { it.displayName() == subjName }
            if (subj == null) { status.text = "Select a learning area"; return@setOnClickListener }
            val grade = gradeInput.text.toString().toIntOrNull()
            if (grade == null || grade < 4 || grade > 9) { status.text = "Grade must be 4–9"; return@setOnClickListener }

            val examType = typeSpinner.selectedItem.toString().lowercase().replace(" ", "_")
            val catFormatSelected = if (catLayout.visibility == View.VISIBLE) {
                when (catFormat.selectedItem.toString()) {
                    "Mixed" -> "mixed"
                    "MCQ Only" -> "mcq_only"
                    "Structured Only" -> "structured_only"
                    else -> "mixed"
                }
            } else "mixed"

            val request = GenerateRequest(
                subject_id = subj.id, term = termSpinner.selectedItem.toString().toInt(),
                exam_type = examType,
                cat_number = if (catLayout.visibility == View.VISIBLE) catNumber.selectedItem.toString().toInt() else null,
                template = selectedTemplate, school_name = schoolName.text.toString(),
                total_marks = totalMarks.text.toString().toIntOrNull() ?: 50,
                mcq_marks = 1, structured_marks_min = 2, structured_marks_max = 8,
                duration_hours = durH.text.toString().toIntOrNull() ?: 1,
                duration_minutes = durM.text.toString().toIntOrNull() ?: 30,
                include_marking_scheme = true, include_images = includeImages.isChecked,
                cross_grade = crossGrade.isChecked, question_format = catFormatSelected,
                topic_ids = null, avoid_question_ids = emptyList()
            )
            status.text = "Generating..."
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val resp = withContext(Dispatchers.IO) { RetrofitClient.api.generateExam(request) }
                    showExamDialog(resp.exam_paper, resp.marking_scheme ?: "")
                    status.text = "Ready – ${resp.total_marks} marks"
                    savePaper(subj.displayName(), resp.exam_paper, resp.marking_scheme)
                } catch (e: Exception) {
                    status.text = "Error: ${e.message}"
                }
            }
        }

        scroll.addView(card)
        return scroll
    }

    private fun createTopicalView(): ScrollView {
        val ctx = this
        val scroll = ScrollView(ctx).apply { setPadding(16, 16, 16, 16) }
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(20, 20, 20, 20) }

        card.addView(createLabel("School Name (optional)"))
        val schoolName = createEditText()
        card.addView(schoolName)

        card.addView(createLabel("Learning Area"))
        val subjectSpinner = createSpinner(listOf())
        card.addView(subjectSpinner)

        val gradeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        gradeRow.addView(createLabel("Grade").apply { layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) })
        val gradeInput = createEditText("8", num = true)
        gradeRow.addView(gradeInput)
        card.addView(gradeRow)

        val termSpinner = createSpinner(listOf("1","2","3"))
        val totalMarks = createEditText("50", num = true)
        card.addView(createDualRow("Term" to termSpinner, "Total Marks" to totalMarks))

        card.addView(createLabel("Topics"))
        val topicsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(topicsContainer)

        val includeImages = CheckBox(ctx).apply { text = "Include Diagrams"; isChecked = true }
        card.addView(includeImages)

        val genBtn = Button(ctx).apply { text = "Generate Topical Test"; setBackgroundColor(0xFF16a34a.toInt()) }
        val status = TextView(ctx).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 13f }
        card.addView(genBtn)
        card.addView(status)

        genBtn.setOnClickListener {
            val subjName = subjectSpinner.selectedItem as? String ?: ""
            val subj = allSubjects.find { it.displayName() == subjName }
            if (subj == null) { status.text = "Select a learning area"; return@setOnClickListener }
            val topicIds = mutableListOf<Int>()
            for (i in 0 until topicsContainer.childCount) {
                val cb = topicsContainer.getChildAt(i) as CheckBox
                if (cb.isChecked) topicIds.add(cb.tag as Int)
            }
            if (topicIds.isEmpty()) { status.text = "Select at least one topic"; return@setOnClickListener }
            val request = GenerateRequest(
                subject_id = subj.id, term = termSpinner.selectedItem.toString().toInt(),
                exam_type = "topical", cat_number = null, template = selectedTemplate,
                school_name = schoolName.text.toString(),
                total_marks = totalMarks.text.toString().toIntOrNull() ?: 50,
                mcq_marks = 1, structured_marks_min = 2, structured_marks_max = 8,
                duration_hours = 1, duration_minutes = 30, include_marking_scheme = true,
                include_images = includeImages.isChecked, cross_grade = false,
                question_format = "mixed", topic_ids = topicIds, avoid_question_ids = emptyList()
            )
            status.text = "Generating..."
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val resp = withContext(Dispatchers.IO) { RetrofitClient.api.generateExam(request) }
                    showExamDialog(resp.exam_paper, resp.marking_scheme ?: "")
                    status.text = "Ready – ${resp.total_marks} marks"
                    savePaper(subj.displayName(), resp.exam_paper, resp.marking_scheme)
                } catch (e: Exception) { status.text = "Error: ${e.message}" }
            }
        }

        scroll.addView(card)
        return scroll
    }

    private fun createHeadersView(): RadioGroup {
        val radio = RadioGroup(this).apply { setPadding(32,32,32,32) }
        val templates = listOf(
            "Classic" to "classic", "Modern" to "modern", "Framed" to "framed",
            "Minimal" to "minimal", "Assessment" to "assessment",
            "Professional" to "professional", "Elegant" to "elegant"
        )
        templates.forEachIndexed { i, (name, value) ->
            radio.addView(RadioButton(this).apply {
                text = name; id = i+1; tag = value
                if (i == 0) isChecked = true
            })
        }
        radio.setOnCheckedChangeListener { _, id ->
            selectedTemplate = radio.findViewById<RadioButton>(id).tag as String
        }
        return radio
    }

    private fun createBulkView(): ScrollView {
        val ctx = this
        val scroll = ScrollView(ctx).apply { setPadding(16, 16, 16, 16) }
        val card = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFFFFFFFF.toInt()); setPadding(20, 20, 20, 20) }

        card.addView(createLabel("Grade"))
        val gradeInput = createEditText("", num = true)
        card.addView(gradeInput)

        val termSpinner = createSpinner(listOf("1","2","3"))
        val typeSpinner = createSpinner(listOf("End Term","Mid Term","Opener","CAT"))
        card.addView(createDualRow("Term" to termSpinner, "Type" to typeSpinner))

        val catLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        catLayout.addView(createLabel("CAT Number"))
        val catNumber = createSpinner(listOf("1","2","3"))
        catLayout.addView(catNumber)
        card.addView(catLayout)

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                catLayout.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        card.addView(createLabel("Learning Areas"))
        val subjectsContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        card.addView(subjectsContainer)

        val genBtn = Button(ctx).apply { text = "Generate All Papers"; setBackgroundColor(0xFF7c3aed.toInt()) }
        val status = TextView(ctx).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 13f }
        card.addView(genBtn)
        card.addView(status)

        gradeInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val g = gradeInput.text.toString().toIntOrNull() ?: return@setOnFocusChangeListener
                loadSubjectsForBulk(g, subjectsContainer)
            }
        }

        genBtn.setOnClickListener {
            val g = gradeInput.text.toString().toIntOrNull() ?: return@setOnClickListener
            status.text = "Generating..."
            CoroutineScope(Dispatchers.Main).launch {
                var done = 0
                for (i in 0 until subjectsContainer.childCount) {
                    val cb = subjectsContainer.getChildAt(i) as CheckBox
                    if (cb.isChecked) {
                        val displayName = cb.text.toString()
                        val subj = allSubjects.find { it.displayName() == displayName } ?: continue
                        try {
                            val request = GenerateRequest(
                                subject_id = subj.id, term = termSpinner.selectedItem.toString().toInt(),
                                exam_type = typeSpinner.selectedItem.toString().lowercase().replace(" ", "_"),
                                cat_number = if (catLayout.visibility == View.VISIBLE) catNumber.selectedItem.toString().toInt() else null,
                                template = selectedTemplate, school_name = "",
                                total_marks = 50, mcq_marks = 1, structured_marks_min = 2, structured_marks_max = 8,
                                duration_hours = 1, duration_minutes = 30, include_marking_scheme = true,
                                include_images = true, cross_grade = true, question_format = "mixed",
                                topic_ids = null, avoid_question_ids = emptyList()
                            )
                            val resp = withContext(Dispatchers.IO) { RetrofitClient.api.generateExam(request) }
                            savePaper(subj.displayName(), resp.exam_paper, resp.marking_scheme)
                            done++
                        } catch (_: Exception) {}
                    }
                }
                status.text = "Complete: $done papers"
            }
        }

        scroll.addView(card)
        return scroll
    }

    private fun createSavedView(): RecyclerView {
        val recyclerView = RecyclerView(this).apply { layoutManager = LinearLayoutManager(this@MainActivity) }
        recyclerView.adapter = SavedAdapter(savedPapers) { paper, action ->
            when(action) {
                "view" -> showExamDialog(paper.paper, paper.marking ?: "")
                "delete" -> { savedPapers.remove(paper); savePapers(); recyclerView.adapter?.notifyDataSetChanged() }
            }
        }
        return recyclerView
    }

    // ==================== HELPERS ====================
    private fun createLabel(text: String) = TextView(this).apply {
        this.text = text; textSize = 13f; setTextColor(0xFF475569.toInt())
        setPadding(0, 8, 0, 4)
    }

    private fun createEditText(default: String = "", num: Boolean = false) = EditText(this).apply {
        setText(default); setTextColor(0xFF334155.toInt()); background = getDrawable(android.R.drawable.edit_text)
        if (num) inputType = android.text.InputType.TYPE_CLASS_NUMBER
    }

    private fun createSpinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun createDualRow(pair1: Pair<String, View>, pair2: Pair<String, View>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val col1 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        col1.addView(createLabel(pair1.first)); col1.addView(pair1.second)
        val col2 = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(16,0,0,0)
        }
        col2.addView(createLabel(pair2.first)); col2.addView(pair2.second)
        row.addView(col1); row.addView(col2)
        return row
    }

    private fun loadSubjectsSafely() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                allSubjects = withContext(Dispatchers.IO) {
                    RetrofitClient.api.getSubjects().sortedBy { it.displayName() }
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Could not load subjects: ${e.message}", Toast.LENGTH_SHORT).show()
                allSubjects = emptyList()
            }
        }
    }

    private fun loadSubjectsForBulk(grade: Int, container: LinearLayout) {
        container.removeAllViews()
        allSubjects.filter { it.grade == grade }.forEach { subj ->
            container.addView(CheckBox(this).apply { text = subj.displayName() })
        }
    }

    private fun showExamDialog(paper: String, marking: String) {
        try {
            val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
            val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val examBtn = Button(this).apply {
                text = "Exam Paper"; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                setBackgroundColor(0xFF16a34a.toInt()); setTextColor(0xFFFFFFFF.toInt())
            }
            val markBtn = Button(this).apply {
                text = "Marking Guide"; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            tabRow.addView(examBtn); tabRow.addView(markBtn); root.addView(tabRow)

            val webView = WebView(this).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                settings.javaScriptEnabled = true; webViewClient = WebViewClient()
            }
            root.addView(webView)
            dialog.setContentView(root)

            examBtn.setOnClickListener {
                webView.loadDataWithBaseURL(null, paper, "text/html", "UTF-8", null)
                examBtn.setBackgroundColor(0xFF16a34a.toInt())
                markBtn.setBackgroundColor(0xFF64748b.toInt())
            }
            markBtn.setOnClickListener {
                webView.loadDataWithBaseURL(null, marking, "text/html", "UTF-8", null)
                markBtn.setBackgroundColor(0xFF2563eb.toInt())
                examBtn.setBackgroundColor(0xFF64748b.toInt())
            }
            webView.loadDataWithBaseURL(null, paper, "text/html", "UTF-8", null)
            dialog.show()
        } catch (_: Exception) {}
    }

    private fun loadSavedPapers() {
        try {
            val json = prefs.getString("papers", "[]") ?: "[]"
            val type = object : TypeToken<List<SavedPaper>>() {}.type
            savedPapers.clear()
            savedPapers.addAll(Gson().fromJson(json, type))
        } catch (_: Exception) {}
    }

    private fun savePapers() {
        prefs.edit().putString("papers", Gson().toJson(savedPapers)).apply()
    }

    private fun savePaper(subject: String, paper: String, marking: String?) {
        val date = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        savedPapers.add(0, SavedPaper(subject, date, paper, marking))
        if (savedPapers.size > 30) savedPapers.removeAt(savedPapers.size - 1)
        savePapers()
        viewPager.adapter?.notifyDataSetChanged()
    }

    // ==================== ADAPTERS ====================
    inner class TabPagerAdapter : RecyclerView.Adapter<TabPagerAdapter.TabViewHolder>() {
        override fun getItemCount() = 5

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TabViewHolder {
            val frame = object : android.widget.FrameLayout(parent.context) {
                init { layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
            }
            return TabViewHolder(frame)
        }

        override fun onBindViewHolder(holder: TabViewHolder, position: Int) {
            holder.frame.removeAllViews()
            holder.frame.addView(
                when (position) {
                    0 -> createHomeView()
                    1 -> createTopicalView()
                    2 -> createHeadersView()
                    3 -> createBulkView()
                    4 -> createSavedView()
                    else -> TextView(this@MainActivity).apply { text = "Unknown tab" }
                }
            )
        }

        inner class TabViewHolder(val frame: android.widget.FrameLayout) : RecyclerView.ViewHolder(frame)
    }

    inner class SavedAdapter(
        private val list: List<SavedPaper>,
        private val callback: (SavedPaper, String) -> Unit
    ) : RecyclerView.Adapter<SavedAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(16, 12, 16, 12)
                gravity = Gravity.CENTER_VERTICAL
            }
            val info = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val title = TextView(parent.context).apply { textSize = 14f }
            val date = TextView(parent.context).apply { textSize = 12f; setTextColor(0xFF666666.toInt()) }
            info.addView(title); info.addView(date)
            row.addView(info)

            val viewBtn = Button(parent.context).apply { text = "View"; setBackgroundColor(0xFF16a34a.toInt()); setTextColor(0xFFFFFFFF.toInt()) }
            val delBtn = Button(parent.context).apply { text = "Del"; setBackgroundColor(0xFFef4444.toInt()); setTextColor(0xFFFFFFFF.toInt()) }
            row.addView(viewBtn); row.addView(delBtn)
            return VH(row)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val paper = list[position]
            val row = holder.itemView as LinearLayout
            val info = row.getChildAt(0) as LinearLayout
            (info.getChildAt(0) as TextView).text = paper.subject
            (info.getChildAt(1) as TextView).text = paper.date
            (row.getChildAt(1) as Button).setOnClickListener { callback(paper, "view") }
            (row.getChildAt(2) as Button).setOnClickListener { callback(paper, "delete") }
        }

        override fun getItemCount() = list.size

        inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView)
    }
}
