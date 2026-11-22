package com.boasnotas.model

/** Uma alteração que pode ser desfeita e refeita. */
interface Edit {
    fun undo()
    fun redo()
    /** true se a alteração muda a lista de páginas (aí a tela precisa se reorganizar). */
    val changesPages: Boolean get() = false
}

class StrokesEdit(val page: Page, private val before: List<Stroke>, private val after: List<Stroke>) : Edit {
    override fun undo() { page.strokes = before }
    override fun redo() { page.strokes = after }
}

/** Troca o conteúdo inteiro da página (traços, textos e imagens) — usado pelo laço, texto e imagens. */
class ContentEdit(val page: Page, private val before: PageContent, private val after: PageContent) : Edit {
    override fun undo() { page.content = before }
    override fun redo() { page.content = after }
}

class TemplateEdit(val page: Page, private val before: PaperTemplate, private val after: PaperTemplate) : Edit {
    override fun undo() { page.template = before }
    override fun redo() { page.template = after }
}

class PageInsertEdit(private val pages: MutableList<Page>, private val index: Int, private val page: Page) : Edit {
    override val changesPages get() = true
    override fun undo() { pages.remove(page) }
    override fun redo() { pages.add(index.coerceIn(0, pages.size), page) }
}

class PageRemoveEdit(private val pages: MutableList<Page>, private val index: Int, private val page: Page) : Edit {
    override val changesPages get() = true
    override fun undo() { pages.add(index.coerceIn(0, pages.size), page) }
    override fun redo() { pages.remove(page) }
}

class CompositeEdit(private val edits: List<Edit>) : Edit {
    override val changesPages get() = edits.any { it.changesPages }
    override fun undo() { for (i in edits.indices.reversed()) edits[i].undo() }
    override fun redo() { for (e in edits) e.redo() }
}

class History(private val limit: Int = 300) {
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** Registra uma alteração que JÁ foi aplicada. */
    fun push(edit: Edit) {
        undoStack.addLast(edit)
        if (undoStack.size > limit) undoStack.removeFirst()
        redoStack.clear()
    }

    fun undo(): Edit? {
        val e = undoStack.removeLastOrNull() ?: return null
        e.undo()
        redoStack.addLast(e)
        return e
    }

    fun redo(): Edit? {
        val e = redoStack.removeLastOrNull() ?: return null
        e.redo()
        undoStack.addLast(e)
        return e
    }
}
