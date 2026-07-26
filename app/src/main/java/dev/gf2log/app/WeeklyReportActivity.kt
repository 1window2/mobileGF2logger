package dev.gf2log.app

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.content.Intent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import dev.gf2log.app.management.EvidencePrecision
import dev.gf2log.app.management.PlatoonPeriods
import dev.gf2log.app.management.PlatoonRepository
import dev.gf2log.app.management.WeeklyNote
import dev.gf2log.app.management.WeeklyReportBuilder
import dev.gf2log.app.management.WeeklyReportCsv
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class WeeklyReportActivity : LocalizedActivity() {
    private lateinit var repository: PlatoonRepository
    private lateinit var body: LinearLayout
    private var referenceDay: LocalDate = LocalDate.now()
    private var pendingCsv: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = PlatoonRepository(this)
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        setContentView(ScrollView(this).apply { addView(body, matchWidth()) })
        render()
    }

    private fun render() {
        body.removeAllViews()
        val zone = ZoneId.systemDefault()
        val report = WeeklyReportBuilder.build(referenceDay, zone, repository.listSnapshots(1000))
        val notes = repository.listWeeklyNotes(report.periodStart.toEpochDay())

        body.addView(TextView(this).apply {
            text = getString(
                if (report.isGunsmokeWeek) R.string.gunsmoke_week else R.string.off_week,
            )
            textSize = 27f
            setTypeface(typeface, Typeface.BOLD)
        }, matchWidth())
        body.addView(TextView(this).apply {
            text = getString(
                R.string.week_period,
                report.periodStart.format(DATE),
                report.periodEnd.format(DATE),
                zone.id,
            )
            textSize = 15f
        }, matchWidth())
        body.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(context).apply {
                text = "‹"
                contentDescription = getString(R.string.previous_week)
                setOnClickListener {
                    referenceDay = report.periodStart.minusDays(1)
                    render()
                }
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
            addView(Button(context).apply {
                text = getString(R.string.current_week)
                setOnClickListener {
                    referenceDay = PlatoonPeriods.gameDay(Instant.now(), ZoneId.systemDefault())
                    render()
                }
            }, LinearLayout.LayoutParams(0, wrap(), 2f))
            addView(Button(context).apply {
                text = "›"
                contentDescription = getString(R.string.next_week)
                setOnClickListener {
                    referenceDay = report.periodEnd.plusDays(1)
                    render()
                }
            }, LinearLayout.LayoutParams(0, wrap(), 1f))
        }, matchWidth())
        body.addView(Button(this).apply {
            text = getString(R.string.export_weekly_csv)
            setOnClickListener { exportWeeklyCsv(report) }
        }, matchWidth())

        if (report.members.isEmpty()) {
            body.addView(TextView(this).apply {
                text = getString(R.string.no_weekly_data)
                setPadding(0, dp(16), 0, dp(16))
            }, matchWidth())
        } else {
            body.addView(HorizontalScrollView(this).apply {
                addView(TableLayout(context).apply {
                    addView(
                        row(
                            listOf(getString(R.string.member)) +
                                report.days.map { it.format(DAY) } +
                                getString(R.string.total),
                            header = true,
                        ),
                    )
                    report.members.forEach { member ->
                        addView(
                            row(
                                listOf("${member.name}\n#${member.uid}") +
                                    member.days.map { cell ->
                                        when {
                                            cell.meritDelta == null -> "-"
                                            !report.isGunsmokeWeek -> cell.meritDelta.toString()
                                            else -> buildString {
                                                append(cell.meritDelta)
                                                append("\n")
                                                append(cell.scoreDelta)
                                                append("pt")
                                                val selected = cell.inference?.selected
                                                if (selected != null) {
                                                    append(" / ")
                                                    append(selected.attempts)
                                                    append("x")
                                                } else if (
                                                    cell.inference?.precision ==
                                                    EvidencePrecision.AMBIGUOUS
                                                ) {
                                                    append(" / ?")
                                                }
                                            }
                                        }
                                    } +
                                    member.totalMerit.toString(),
                                header = false,
                            ),
                        )
                    }
                })
            }, matchWidth())
        }

        body.addView(TextView(this).apply {
            text = getString(R.string.notes)
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        }, matchWidth())
        report.days.forEach { day ->
            val dayNotes = notes.filter { it.gameDay == day }
            if (dayNotes.isNotEmpty()) {
                body.addView(TextView(this).apply {
                    text = day.format(DATE)
                    setTypeface(typeface, Typeface.BOLD)
                }, matchWidth())
                dayNotes.forEach { note -> addNote(note) }
            }
        }
        addNoteEditor(report)
    }

    private fun addNote(note: WeeklyNote) {
        body.addView(Button(this).apply {
            isAllCaps = false
            text = if (note.isAutomatic) {
                localizeAutomaticNote(note.text)
            } else {
                note.text
            }
            isEnabled = !note.isAutomatic
            setOnClickListener {
                if (repository.deleteWeeklyNote(note.id)) render()
            }
        }, matchWidth())
    }

    @Deprecated("Uses the platform document picker without an AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_WEEKLY) return
        val content = pendingCsv
        pendingCsv = null
        val destination = data?.data
        if (resultCode != RESULT_OK || destination == null || content == null) return
        val exported = runCatching {
            val output = TrustedExportDestination.openOutputStream(contentResolver, destination)
                ?: error("Document provider did not open an output stream")
            output.writer(Charsets.UTF_8).use { it.write(content) }
        }.isSuccess
        Toast.makeText(
            this,
            getString(if (exported) R.string.weekly_csv_exported else R.string.status_export_failed),
            Toast.LENGTH_SHORT,
        ).show()
    }

    @Suppress("DEPRECATION")
    private fun exportWeeklyCsv(report: WeeklyReportBuilder.Report) {
        pendingCsv = WeeklyReportCsv.format(report)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("text/csv")
            .putExtra(
                Intent.EXTRA_TITLE,
                "GF2logger-week-${report.periodStart.format(FILE_DATE)}.csv",
            )
        startActivityForResult(intent, REQUEST_EXPORT_WEEKLY)
    }

    private fun addNoteEditor(report: WeeklyReportBuilder.Report) {
        val day = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@WeeklyReportActivity,
                android.R.layout.simple_spinner_dropdown_item,
                report.days.map { it.format(DATE) },
            )
        }
        val note = EditText(this).apply {
            hint = getString(R.string.note_hint)
            setSingleLine(true)
        }
        body.addView(day, matchWidth())
        body.addView(note, matchWidth())
        body.addView(Button(this).apply {
            text = getString(R.string.add_note)
            setOnClickListener {
                val text = note.text.toString().trim()
                if (text.isBlank()) return@setOnClickListener
                val gameDay = report.days[day.selectedItemPosition]
                repository.addWeeklyNote(
                    report.periodStart.toEpochDay(),
                    gameDay.toEpochDay(),
                    text,
                )
                Toast.makeText(
                    this@WeeklyReportActivity,
                    getString(R.string.saved),
                    Toast.LENGTH_SHORT,
                ).show()
                render()
            }
        }, matchWidth())
    }

    private fun localizeAutomaticNote(note: String): String {
        val parts = note.split(':', limit = 3)
        if (parts.size != 3) return note
        val action = when (parts[0]) {
            "JOINED" -> getString(R.string.joined)
            "REJOINED" -> getString(R.string.rejoined)
            "LEFT" -> getString(R.string.left)
            else -> parts[0]
        }
        return "$action: ${parts[1]} (#${parts[2]})"
    }

    private fun row(values: List<String>, header: Boolean) = TableRow(this).apply {
        values.forEach { value ->
            addView(TextView(context).apply {
                text = value
                gravity = Gravity.CENTER
                textSize = if (header) 13f else 12f
                if (header) setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(8), dp(7), dp(8), dp(7))
                background = GradientDrawable().apply {
                    setColor(if (header) Color.rgb(210, 222, 241) else Color.TRANSPARENT)
                    setStroke(1, Color.rgb(140, 150, 165))
                }
            })
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun wrap() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun matchWidth() = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    companion object {
        private val DATE = DateTimeFormatter.ofPattern("yy/MM/dd")
        private val DAY = DateTimeFormatter.ofPattern("MM/dd")
        private val FILE_DATE = DateTimeFormatter.BASIC_ISO_DATE
        private const val REQUEST_EXPORT_WEEKLY = 201
    }
}
