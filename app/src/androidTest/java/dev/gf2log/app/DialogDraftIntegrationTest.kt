package dev.gf2log.app

import android.content.Context
import android.os.Parcel
import android.os.Parcelable
import android.util.SparseArray
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.gf2log.app.ui.DialogDraft
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DialogDraftIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun typedFieldsAndPickedDatesSurviveAParcelledRoundTrip() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val zone = ZoneId.of("Asia/Seoul")
            val original = form(zone)
            original.note.setText("Left for another Platoon")
            val joined = original.joined
            joined.selectDate(LocalDate.of(2026, 11, 6))
            val views = SparseArray<Parcelable>().also(original.root::saveHierarchyState)
            val draft = DialogDraft("withdrawn_member", android.os.Bundle(), views)

            val restored = DialogDraft.fromBundle(parcelled(draft.toBundle()))
            assertNotNull(restored)
            val recreated = form(zone)
            recreated.root.restoreHierarchyState(restored!!.views)

            assertEquals("Left for another Platoon", recreated.note.text.toString())
            assertEquals(LocalDate.of(2026, 11, 6), recreated.joined.boundary?.date)
            assertNull(recreated.left.boundary)
        }
    }

    private class Form(val root: LinearLayout, val note: EditText, val joined: DateTimePickerInput, val left: DateTimePickerInput)

    private fun form(zone: ZoneId): Form {
        val note = EditText(context).apply { id = R.id.dialog_note_input }
        val joined = DateTimePickerInput(context, "Joined", zone = zone, stateId = R.id.dialog_joined_input)
        val left = DateTimePickerInput(context, "Left", zone = zone, stateId = R.id.dialog_left_input)
        val root = LinearLayout(context).apply {
            addView(note)
            addView(joined)
            addView(left)
        }
        return Form(root, note, joined, left)
    }

    private fun parcelled(bundle: android.os.Bundle): android.os.Bundle {
        val parcel = Parcel.obtain()
        try {
            parcel.writeBundle(bundle)
            parcel.setDataPosition(0)
            return requireNotNull(parcel.readBundle(javaClass.classLoader))
        } finally {
            parcel.recycle()
        }
    }
}
