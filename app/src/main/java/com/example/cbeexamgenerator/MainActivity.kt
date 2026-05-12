package com.example.cbeexamgenerator

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.text.SimpleDateFormat
import java.util.*

// ==================== DATA ====================
data class Subject(val id: Int, val name: String, val grade: Int) {
    fun displayName() = name.replace(Regex(" Grade \\d+$"), "")
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

    // Spinner references for updating adapters after subjects load
    private val spinnerRefs = mutableListOf<Spinner>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("saved_papers", MODE_PRIVATE)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            setBackgroundColor(0xFFF8FAFC.toInt())
        }

        // Header
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setBackgroundColor(0xFF16A34A.toInt()); setPadding(16, 24, 16, 16)
            addView(TextView(context).apply {
                text = "CBE Exam Generator"; textSize = 22f; setTextColor(0xFFFFFFFF.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = "Competency Based Education Assessments"
                textSize = 12f; setTextColor(0xFFDCFCE7.toInt()); setPadding(0, 4, 0, 0)
            })
        })

        // Tabs
        val tabLayout = TabLayout(this).apply {
            tabGravity = TabLayout.GRAVITY_FILL; tabMode = TabLayout.MODE_SCROLLABLE
            setBackgroundColor(0xFFFFFFFF.toInt())
            setSelectedTabIndicatorColor(0xFF16A34A.toInt())
            setTabTextColors(0xFF64748B.toInt(), 0xFF16A34A.toInt())
        }
        root.addView(tabLayout)

        viewPager = ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
            adapter = TabPagerAdapter()
        }
        root.addView(viewPager)

        TabLayoutMediator(tabLayout, viewPager) { tab, pos -> tab.text = tabTitles[pos] }.attach()
        setContentView(root)

        loadSavedPapers()
        loadSubjects()
    }

    // ---------- LOAD SUBJECTS ----------
    private fun loadSubjects() {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                allSubjects = withContext(Dispatchers.IO) { RetrofitClient.api.getSubjects() }
                    .sortedBy { it.displayName() }
                updateAllSpinners()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Failed to load subjects", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateAllSpinners() {
        val names = allSubjects.map { it.displayName() }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerRefs.forEach { it.adapter = adapter }
    }

    // ---------- UI HELPERS ----------
    private fun card(): CardView = CardView(this).apply {
        radius = 24f; cardElevation = 2f; setContentPadding(20, 20, 20, 20)
        setCardBackgroundColor(0xFFFFFFFF.toInt())
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, 0, 0, 16) }
    }

    private fun title(text: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, 16)
        addView(TextView(context).apply {
            this.text = text; textSize = 18f; setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(0xFF0F172A.toInt())
        })
        addView(TextView(context).apply {
            this.text = sub; textSize = 13f; setTextColor(0xFF64748B.toInt())
        })
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; textSize = 12f; setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFF475569.toInt()); setPadding(0, 12, 0, 4)
    }

    private fun input(hint: String = "", default: String = "", number: Boolean = false): TextInputLayout {
        val layout = TextInputLayout(this).apply {
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            hint = hint; boxCornerRadiusTopStart = 12f; boxCornerRadiusTopEnd = 12f
            boxCornerRadiusBottomStart = 12f; boxCornerRadiusBottomEnd = 12f
            hintTextColor = android.content.res.ColorStateList.valueOf(0xFF94A3B8.toInt())
        }
        val edit = TextInputEditText(this).apply {
            setText(default); setTextColor(0xFF1E293B.toInt())
            if (number) inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        layout.addView(edit)
        return layout
    }

    private fun spinner(items: List<String>): Spinner {
        val s = Spinner(this).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_item, items)
                .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setBackgroundColor(0xFFF1F5F9.toInt())
        }
        spinnerRefs.add(s)
        return s
    }

    private fun dual(left: Pair<String, View>, right: Pair<String, View>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(label(left.first)); addView(left.second)
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setPadding(16, 0, 0, 0); addView(label(right.first)); addView(right.second)
        })
    }

    private fun button(text: String, color: Int) = MaterialButton(this).apply {
        this.text = text; setBackgroundColor(color); setTextColor(0xFFFFFFFF.toInt())
        cornerRadius = 16; textSize = 14f; gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(0, 16, 0, 0) }
    }

    private fun status() = TextView(this).apply {
        textAlignment = View.TEXT_ALIGNMENT_CENTER; textSize = 13f; setPadding(0, 12, 0, 0)
    }

    // ---------- TABS ----------
    private inner class TabPagerAdapter : RecyclerView.Adapter<TabPagerAdapter.VH>() {
        override fun getItemCount() = 5
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
            FrameLayout(parent.context).apply { layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
        )
        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.container.removeAllViews()
            holder.container.addView(when (position) {
                0 -> homeTab()
                1 -> topicalTab()
                2 -> headersTab()
                3 -> bulkTab()
                4 -> savedTab()
                else -> TextView(context)
            })
        }
        inner class VH(val container: FrameLayout) : RecyclerView.ViewHolder(container)
    }

    // ---------- HOME TAB ----------
    private fun homeTab(): View {
        val scroll = ScrollView(this).apply { setPadding(16, 16, 16, 16); setBackgroundColor(0xFFF8FAFC.toInt()) }
        val card = card()
        card.addView(title("Generate Assessment", "Configure and create a professional exam paper."))

        card.addView(label("School Name (optional)"))
        val schoolName = input("Leave blank for standard header")
        card.addView(schoolName)

        card.addView(label("Learning Area"))
        val subjectSpinner = spinner(listOf("Loading..."))
        card.addView(subjectSpinner)

        val gradeInput = input("e.g. 8", "8", true)
        card.addView(dual("Grade" to gradeInput, "Learning Area" to subjectSpinner))

        val termSpinner = spinner(listOf("1", "2", "3"))
        val typeSpinner = spinner(listOf("End Term", "Mid Term", "Opener", "CAT"))
        card.addView(dual("Term" to termSpinner, "Type" to typeSpinner))

        val catLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        catLayout.addView(label("CAT Number"))
        val catNumber = spinner(listOf("1", "2", "3")); catLayout.addView(catNumber)
        catLayout.addView(label("Question Format"))
        val catFormat = spinner(listOf("Mixed", "MCQ Only", "Structured Only")); catLayout.addView(catFormat)
        card.addView(catLayout)

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                catLayout.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        card.addView(label("Total Marks"))
        val totalMarks = input("50", "50", true); card.addView(totalMarks)

        card.addView(dual("Hours" to input("Hours", "1", true), "Minutes" to input("Minutes", "30", true)))

        val checkRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 12, 0, 0) }
        val includeImages = MaterialCheckBox(this).apply { text = "Include Diagrams"; isChecked = true }
        val crossGrade = MaterialCheckBox(this).apply { text = "Cross‑Grade Content"; isChecked = true }
        checkRow.addView(includeImages); checkRow.addView(crossGrade)
        card.addView(checkRow)

        val genBtn = button("Generate Paper", 0xFF16A34A.toInt())
        val status = status()
        card.addView(genBtn); card.addView(status)

        genBtn.setOnClickListener {
            val subjName = subjectSpinner.selectedItem as? String ?: ""
            val subj = allSubjects.find { it.displayName() == subjName }
            if (subj == null) { status.text = "Select a learning area"; return@setOnClickListener }
            val grade = (gradeInput.editText as? TextInputEditText)?.text?.toString()?.toIntOrNull()
            if (grade == null || grade < 4 || grade > 9) { status.text = "Grade must be 4–9"; return@setOnClickListener }

            val examType = (typeSpinner.selectedItem as String).lowercase().replace(" ", "_")
            val catFormatSelected = if (catLayout.visibility == View.VISIBLE) {
                when (catFormat.selectedItem as String) {
                    "Mixed" -> "mixed"
                    "MCQ Only" -> "mcq_only"
                    "Structured Only" -> "structured_only"
                    else -> "mixed"
                }
            } else "mixed"

            val request = GenerateRequest(
                subject_id = subj.id, term = (termSpinner.selectedItem as String).toInt(),
                exam_type = examType,
                cat_number = if (catLayout.visibility == View.VISIBLE) (catNumber.selectedItem as String).toInt() else null,
                template = selectedTemplate,
                school_name = (schoolName.editText as? TextInputEditText)?.text?.toString().orEmpty(),
                total_marks = (totalMarks.editText as? TextInputEditText)?.text?.toString()?.toIntOrNull() ?: 50,
                mcq_marks = 1, structured_marks_min = 2, structured_marks_max = 8,
                duration_hours = 1, duration_minutes = 30,
                include_marking_scheme = true,
                include_images = includeImages.isChecked,
                cross_grade = crossGrade.isChecked,
                question_format = catFormatSelected,
                topic_ids = null, avoid_question_ids = emptyList()
            )
            status.text = "Generating..."
            generate(request, status, subj.displayName())
        }
        scroll.addView(card)
        return scroll
    }

    // ---------- TOPICAL TAB ----------
    private fun topicalTab(): View {
        val scroll = ScrollView(this).apply { setPadding(16, 16, 16, 16); setBackgroundColor(0xFFF8FAFC.toInt()) }
        val card = card()
        card.addView(title("Topical Test", "Select specific topics for a focused assessment."))

        val schoolName = input("School Name (optional)"); card.addView(schoolName)

        card.addView(label("Learning Area"))
        val subjectSpinner = spinner(listOf("Loading...")); card.addView(subjectSpinner)

        val gradeInput = input("e.g. 8", "8", true)
        card.addView(dual("Grade" to gradeInput, "Learning Area" to subjectSpinner))

        val termSpinner = spinner(listOf("1", "2", "3"))
        val totalMarks = input("50", "50", true)
        card.addView(dual("Term" to termSpinner, "Total Marks" to totalMarks))

        card.addView(label("Topics"))
        val topicsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; card.addView(topicsContainer)

        val includeImages = MaterialCheckBox(this).apply { text = "Include Diagrams"; isChecked = true }; card.addView(includeImages)

        val genBtn = button("Generate Topical Test", 0xFF16A34A.toInt())
        val status = status(); card.addView(genBtn); card.addView(status)

        subjectSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val name = parent?.getItemAtPosition(pos) as? String ?: return
                val subj = allSubjects.find { it.displayName() == name } ?: return
                (gradeInput.editText as? TextInputEditText)?.setText(subj.grade.toString())
                loadTopics(subj.id, topicsContainer)
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        genBtn.setOnClickListener {
            val subjName = subjectSpinner.selectedItem as? String ?: ""
            val subj = allSubjects.find { it.displayName() == subjName }
            if (subj == null) { status.text = "Select a learning area"; return@setOnClickListener }
            val topicIds = (0 until topicsContainer.childCount).mapNotNull { i ->
                (topicsContainer.getChildAt(i) as? CheckBox)?.takeIf { it.isChecked }?.tag as? Int
            }
            if (topicIds.isEmpty()) { status.text = "Select at least one topic"; return@setOnClickListener }
            val request = GenerateRequest(
                subject_id = subj.id, term = (termSpinner.selectedItem as String).toInt(),
                exam_type = "topical", cat_number = null, template = selectedTemplate,
                school_name = (schoolName.editText as? TextInputEditText)?.text?.toString().orEmpty(),
                total_marks = (totalMarks.editText as? TextInputEditText)?.text?.toString()?.toIntOrNull() ?: 50,
                mcq_marks = 1, structured_marks_min = 2, structured_marks_max = 8,
                duration_hours = 1, duration_minutes = 30,
                include_marking_scheme = true, include_images = includeImages.isChecked,
                cross_grade = false, question_format = "mixed",
                topic_ids = topicIds, avoid_question_ids = emptyList()
            )
            status.text = "Generating..."
            generate(request, status, "Topical: ${subj.displayName()}")
        }

        scroll.addView(card)
        return scroll
    }

    // ---------- HEADERS TAB ----------
    private fun headersTab(): View {
        val scroll = ScrollView(this).apply { setPadding(16, 16, 16, 16) }
        val card = card(); card.addView(title("Header Styles", "Choose a cover page design."))
        val radio = RadioGroup(this)
        listOf("Classic" to "classic", "Modern" to "modern", "Framed" to "framed",
            "Minimal" to "minimal", "Assessment" to "assessment",
            "Professional" to "professional", "Elegant" to "elegant")
            .forEachIndexed { i, (name, value) ->
                radio.addView(RadioButton(this).apply {
                    text = name; id = i + 1; tag = value; if (i == 0) isChecked = true
                })
            }
        radio.setOnCheckedChangeListener { _, id -> selectedTemplate = radio.findViewById<RadioButton>(id).tag as String }
        card.addView(radio); scroll.addView(card)
        return scroll
    }

    // ---------- BULK TAB ----------
    private fun bulkTab(): View {
        val scroll = ScrollView(this).apply { setPadding(16, 16, 16, 16) }
        val card = card(); card.addView(title("Bulk Generation", "Generate papers for all subjects in a grade."))

        val gradeInput = input("e.g. 8", "", true); card.addView(gradeInput)
        val termSpinner = spinner(listOf("1", "2", "3"))
        val typeSpinner = spinner(listOf("End Term", "Mid Term", "Opener", "CAT"))
        card.addView(dual("Term" to termSpinner, "Type" to typeSpinner))

        val catLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        catLayout.addView(label("CAT Number"))
        val catNumber = spinner(listOf("1", "2", "3")); catLayout.addView(catNumber)
        card.addView(catLayout)

        typeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                catLayout.visibility = if (pos == 3) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }

        card.addView(label("Learning Areas"))
        val subjectsContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; card.addView(subjectsContainer)

        (gradeInput.editText as? TextInputEditText)?.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val g = (gradeInput.editText as? TextInputEditText)?.text?.toString()?.toIntOrNull()
                subjectsContainer.removeAllViews()
                allSubjects.filter { it.grade == g }.forEach { subj ->
                    subjectsContainer.addView(CheckBox(this).apply { text = subj.displayName() })
                }
            }
        }

        val genBtn = button("Generate All Papers", 0xFF7C3AED.toInt())
        val status = status(); card.addView(genBtn); card.addView(status)

        genBtn.setOnClickListener {
            val selected = (0 until subjectsContainer.childCount).mapNotNull { i ->
                (subjectsContainer.getChildAt(i) as? CheckBox)?.takeIf { it.isChecked }?.text?.toString()
            }
            if (selected.isEmpty()) { status.text = "Select at least one subject"; return@setOnClickListener }
            status.text = "Generating..."
            CoroutineScope(Dispatchers.Main).launch {
                var done = 0
                for (name in selected) {
                    val subj = allSubjects.find { it.displayName() == name } ?: continue
                    try {
                        val request = GenerateRequest(
                            subject_id = subj.id, term = (termSpinner.selectedItem as String).toInt(),
                            exam_type = (typeSpinner.selectedItem as String).lowercase().replace(" ", "_"),
                            cat_number = if (catLayout.visibility == View.VISIBLE) (catNumber.selectedItem as String).toInt() else null,
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
                status.text = "Complete: $done papers"
            }
        }
        scroll.addView(card)
        return scroll
    }

    // ---------- SAVED TAB ----------
    private fun savedTab(): View {
        val recyclerView = RecyclerView(this).apply { layoutManager = LinearLayoutManager(context) }
        recyclerView.adapter = SavedAdapter(savedPapers) { paper, action ->
            when (action) {
                "view" -> showExamDialog(paper.paper, paper.marking ?: "")
                "delete" -> {
                    savedPapers.remove(paper)
                    savePapers()
                    recyclerView.adapter?.notifyDataSetChanged()
                }
            }
        }
        return recyclerView
    }

    // ---------- GENERATION ----------
    private fun generate(request: GenerateRequest, status: TextView, label: String) {
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resp = withContext(Dispatchers.IO) { RetrofitClient.api.generateExam(request) }
                showExamDialog(resp.exam_paper, resp.marking_scheme ?: "")
                status.text = "Ready – ${resp.total_marks} marks"
                savePaper(label, resp.exam_paper, resp.marking_scheme)
            } catch (e: Exception) {
                status.text = "Error: ${e.message}"
            }
        }
    }

    private fun loadTopics(subjectId: Int, container: LinearLayout) {
        CoroutineScope(Dispatchers.Main).launch {
            container.removeAllViews()
            try {
                val topics = withContext(Dispatchers.IO) { RetrofitClient.api.getTopics(subjectId) }
                topics.forEach { topic ->
                    container.addView(CheckBox(this@MainActivity).apply {
                        text = "${topic.name} (${topic.question_count} qns)"; tag = topic.id
                    })
                }
            } catch (_: Exception) {
                container.addView(TextView(this@MainActivity).apply { text = "No topics available" })
            }
        }
    }

    // ---------- EXAM DIALOG ----------
    private fun showExamDialog(paper: String, marking: String) {
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val examBtn = Button(this).apply {
            text = "Exam Paper"; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            setBackgroundColor(0xFF16A34A.toInt()); setTextColor(0xFFFFFFFF.toInt())
        }
        val markBtn = Button(this).apply {
            text = "Marking Guide"; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        tabs.addView(examBtn); tabs.addView(markBtn); root.addView(tabs)

        val webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            settings.javaScriptEnabled = true; webViewClient = WebViewClient()
        }
        root.addView(webView)
        dialog.setContentView(root)

        examBtn.setOnClickListener {
            webView.loadDataWithBaseURL(null, paper, "text/html", "UTF-8", null)
            examBtn.setBackgroundColor(0xFF16A34A.toInt()); markBtn.setBackgroundColor(0xFF64748B.toInt())
        }
        markBtn.setOnClickListener {
            webView.loadDataWithBaseURL(null, marking, "text/html", "UTF-8", null)
            markBtn.setBackgroundColor(0xFF2563EB.toInt()); examBtn.setBackgroundColor(0xFF64748B.toInt())
        }
        webView.loadDataWithBaseURL(null, paper, "text/html", "UTF-8", null)
        dialog.show()
    }

    // ---------- PERSISTENCE ----------
    private fun loadSavedPapers() {
        val json = prefs.getString("papers", "[]") ?: "[]"
        savedPapers.clear()
        savedPapers.addAll(Gson().fromJson(json, object : TypeToken<List<SavedPaper>>() {}.type))
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

    // ---------- SAVED ADAPTER ----------
    inner class SavedAdapter(
        private val list: List<SavedPaper>,
        private val callback: (SavedPaper, String) -> Unit
    ) : RecyclerView.Adapter<SavedAdapter.VH>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(16, 12, 16, 12); gravity = Gravity.CENTER_VERTICAL
            }
            val info = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            }
            val title = TextView(parent.context).apply { textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD) }
            val date = TextView(parent.context).apply { textSize = 12f; setTextColor(0xFF666666.toInt()) }
            info.addView(title); info.addView(date); row.addView(info)

            fun makeButton(label: String, color: Int) = Button(parent.context).apply {
                text = label; setBackgroundColor(color); setTextColor(0xFFFFFFFF.toInt()); textSize = 10f
                layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(4, 0, 4, 0) }
            }
            row.addView(makeButton("View", 0xFF16A34A.toInt()))
            row.addView(makeButton("Del", 0xFFEF4444.toInt()))
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
