package com.boasnotas.model

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Formato binário das páginas de um caderno (arquivo pages.bin).
 * Binário em vez de JSON porque um caderno pode ter centenas de milhares de pontos.
 */
object NotebookCodec {
    private const val MAGIC = 0x424E5431 // "BNT1"
    private const val VERSION = 1

    fun write(out: OutputStream, pages: List<Page>) {
        val o = DataOutputStream(out.buffered())
        o.writeInt(MAGIC)
        o.writeInt(VERSION)
        o.writeInt(pages.size)
        for (page in pages) {
            o.writeUTF(page.id)
            o.writeInt(page.template.ordinal)
            o.writeFloat(page.width)
            o.writeFloat(page.height)
            o.writeInt(page.pdfPageIndex)
            val strokes = page.strokes
            o.writeInt(strokes.size)
            for (s in strokes) {
                o.writeByte(s.tool.ordinal)
                o.writeInt(s.color)
                o.writeFloat(s.width)
                o.writeInt(s.points.size)
                for (v in s.points) o.writeFloat(v)
            }
        }
        o.flush()
    }

    fun read(input: InputStream): MutableList<Page> {
        val i = DataInputStream(input.buffered())
        if (i.readInt() != MAGIC) throw IOException("Arquivo de caderno inválido")
        val version = i.readInt()
        if (version > VERSION) throw IOException("Caderno criado por uma versão mais nova do app")
        val pageCount = i.readInt()
        val pages = ArrayList<Page>(pageCount)
        repeat(pageCount) {
            val id = i.readUTF()
            val template = PaperTemplate.fromOrdinal(i.readInt())
            val w = i.readFloat()
            val h = i.readFloat()
            val pdfIndex = i.readInt()
            val strokeCount = i.readInt()
            val strokes = ArrayList<Stroke>(strokeCount)
            repeat(strokeCount) {
                val tool = Tool.entries.getOrElse(i.readByte().toInt()) { Tool.PEN }
                val color = i.readInt()
                val width = i.readFloat()
                val n = i.readInt()
                val pts = FloatArray(n)
                for (k in 0 until n) pts[k] = i.readFloat()
                strokes.add(Stroke(tool, color, width, pts))
            }
            pages.add(Page(id, template, w, h, pdfIndex, strokes))
        }
        return pages
    }
}
