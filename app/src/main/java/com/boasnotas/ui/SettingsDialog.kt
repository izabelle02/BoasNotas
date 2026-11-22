package com.boasnotas.ui

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import com.boasnotas.data.AppSettings
import com.boasnotas.data.PenButtonMode
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

object SettingsDialog {

    fun show(activity: Activity, settings: AppSettings, onClose: () -> Unit = {}) {
        val pad = activity.dpi(22)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, activity.dpi(8), pad, 0)
        }

        fun header(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, activity.dpi(14), 0, activity.dpi(4))
        }

        fun note(text: String) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            alpha = 0.7f
            setPadding(0, 0, 0, activity.dpi(6))
        }

        box.addView(header("Botão da S Pen"))
        val group = RadioGroup(activity)
        PenButtonMode.entries.forEach { mode ->
            group.addView(RadioButton(activity).apply {
                id = View.generateViewId()
                text = mode.label
                isChecked = settings.penButtonMode == mode
                setOnCheckedChangeListener { _, checked -> if (checked) settings.penButtonMode = mode }
            })
        }
        box.addView(group)
        box.addView(
            note(
                "Dica: aperte o botão com a ponta da caneta encostada na tela. Se o botão abrir o " +
                    "menu de \"Comandos aéreos\" da Samsung quando a caneta está no ar, desative em " +
                    "Configurações › Recursos avançados › S Pen › Comandos aéreos."
            )
        )

        box.addView(header("Escrita"))
        fun switch(text: String, value: Boolean, onChange: (Boolean) -> Unit) =
            MaterialSwitch(activity).apply {
                this.text = text
                isChecked = value
                setOnCheckedChangeListener { _, c -> onChange(c) }
                setPadding(0, activity.dpi(4), 0, activity.dpi(4))
            }

        box.addView(switch("Sensível à pressão da caneta", settings.pressureEnabled) { settings.pressureEnabled = it })
        box.addView(switch("Borracha apaga o traço inteiro", settings.eraseWholeStroke) { settings.eraseWholeStroke = it })
        box.addView(switch("Desenhar com o dedo", settings.fingerDraws) { settings.fingerDraws = it })
        box.addView(note("Desligado: só a caneta escreve; o dedo rola e dá zoom (a palma da mão é ignorada)."))
        box.addView(switch("Toque com 2 dedos desfaz (3 dedos refaz)", settings.tapGestures) { settings.tapGestures = it })

        MaterialAlertDialogBuilder(activity)
            .setTitle("Configurações")
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton("OK", null)
            .setOnDismissListener { onClose() }
            .show()
    }
}
