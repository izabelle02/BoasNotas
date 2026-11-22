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

    /** Segurar a caneta parada no fim do traço transforma o rabisco em forma perfeita. */
    var holdForShape: Boolean
        get() = prefs.getBoolean("holdForShape", true)
        set(v) = prefs.edit().putBoolean("holdForShape", v).apply()

    /** Reconhecer a letra para poder buscar nos cadernos. */
    var handwritingSearch: Boolean
        get() = prefs.getBoolean("handwritingSearch", true)
        set(v) = prefs.edit().putBoolean("handwritingSearch", v).apply()

    /** Salvar automaticamente no Google Drive. */
    var driveEnabled: Boolean
        get() = prefs.getBoolean("driveEnabled", false)
        set(v) = prefs.edit().putBoolean("driveEnabled", v).apply()

    /** Última mensagem do salvamento no Drive (ex.: "Salvo às 14:32" ou o erro). */
    var driveStatus: String
        get() = prefs.getString("driveStatus", "") ?: ""
        set(v) = prefs.edit().putString("driveStatus", v).apply()

    fun getString(key: String): String? = prefs.getString(key, null)
    fun putString(key: String, v: String?) = prefs.edit().putString(key, v).apply()

    // Última ferramenta usada (para abrir o caderno do jeito que você deixou).
    fun getInt(key: String, def: Int) = prefs.getInt(key, def)
    fun putInt(key: String, v: Int) = prefs.edit().putInt(key, v).apply()
}
