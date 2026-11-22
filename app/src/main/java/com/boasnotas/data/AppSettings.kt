package com.boasnotas.data

import android.content.Context

/** O que o botão lateral da S Pen faz. */
enum class PenButtonMode(val label: String) {
    HOLD("Segurar o botão = borracha (solta e volta para a caneta)"),
    TOGGLE("Apertar o botão alterna entre caneta e borracha"),
    OFF("Não fazer nada"),
}

class AppSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var penButtonMode: PenButtonMode
        get() = PenButtonMode.entries.getOrElse(prefs.getInt("penButtonMode", 0)) { PenButtonMode.HOLD }
        set(v) = prefs.edit().putInt("penButtonMode", v.ordinal).apply()

    /** Se falso, só a caneta escreve e o dedo serve para rolar/dar zoom (rejeição da palma). */
    var fingerDraws: Boolean
        get() = prefs.getBoolean("fingerDraws", false)
        set(v) = prefs.edit().putBoolean("fingerDraws", v).apply()

    /** Borracha apaga o traço inteiro (true) ou só onde passa (false). */
    var eraseWholeStroke: Boolean
        get() = prefs.getBoolean("eraseWholeStroke", false)
        set(v) = prefs.edit().putBoolean("eraseWholeStroke", v).apply()

    var pressureEnabled: Boolean
        get() = prefs.getBoolean("pressure", true)
        set(v) = prefs.edit().putBoolean("pressure", v).apply()

    /** Tocar com dois dedos desfaz; com três, refaz. */
    var tapGestures: Boolean
        get() = prefs.getBoolean("tapGestures", true)
        set(v) = prefs.edit().putBoolean("tapGestures", v).apply()

    // Última ferramenta usada (para abrir o caderno do jeito que você deixou).
    fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
}
