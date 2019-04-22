package com.topjohnwu.magisk.ui.module

import android.content.res.Resources
import android.database.Cursor
import androidx.annotation.StringRes
import com.skoumal.teanity.databinding.ComparableRvItem
import com.skoumal.teanity.extensions.addOnPropertyChangedCallback
import com.skoumal.teanity.extensions.subscribeK
import com.skoumal.teanity.util.DiffObservableList
import com.skoumal.teanity.util.KObservableField
import com.topjohnwu.magisk.BR
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.data.database.RepoDatabaseHelper
import com.topjohnwu.magisk.model.entity.Module
import com.topjohnwu.magisk.model.entity.Repo
import com.topjohnwu.magisk.model.entity.recycler.ModuleRvItem
import com.topjohnwu.magisk.model.entity.recycler.RepoRvItem
import com.topjohnwu.magisk.model.entity.recycler.SectionRvItem
import com.topjohnwu.magisk.model.events.InstallModuleEvent
import com.topjohnwu.magisk.model.events.OpenChangelogEvent
import com.topjohnwu.magisk.model.events.OpenFilePickerEvent
import com.topjohnwu.magisk.tasks.UpdateRepos
import com.topjohnwu.magisk.ui.base.MagiskViewModel
import com.topjohnwu.magisk.utils.Event
import com.topjohnwu.magisk.utils.Utils
import com.topjohnwu.magisk.utils.toSingle
import com.topjohnwu.magisk.utils.update
import io.reactivex.Single
import me.tatarka.bindingcollectionadapter2.OnItemBind

class ModuleViewModel(
    private val repoDatabase: RepoDatabaseHelper,
    private val resources: Resources
) : MagiskViewModel() {

    val query = KObservableField("")

    private val allItems = mutableListOf<ComparableRvItem<*>>()

    val itemsInstalled = DiffObservableList(ComparableRvItem.callback)
    val itemsRemote = DiffObservableList(ComparableRvItem.callback)
    val itemBinding = OnItemBind<ComparableRvItem<*>> { itemBinding, _, item ->
        item.bind(itemBinding)
        itemBinding.bindExtra(BR.viewModel, this@ModuleViewModel)
    }

    init {
        query.addOnPropertyChangedCallback { query() }
        Event.register(this)
        refresh()
    }

    override fun getListeningEvents(): IntArray {
        return intArrayOf(Event.MODULE_LOAD_DONE, Event.REPO_LOAD_DONE)
    }

    override fun onEvent(event: Int) = when (event) {
        Event.MODULE_LOAD_DONE -> updateModules(Event.getResult(event))
        Event.REPO_LOAD_DONE -> updateRepos()
        else -> Unit
    }

    fun fabPressed() = OpenFilePickerEvent().publish()
    fun repoPressed(item: RepoRvItem) = OpenChangelogEvent(item.item).publish()
    fun downloadPressed(item: RepoRvItem) = InstallModuleEvent(item.item).publish()

    fun refresh() {
        state = State.LOADING
        Utils.loadModules(true)
        UpdateRepos().exec(true)
    }

    private fun updateModules(result: Map<String, Module>) = result.values
        .map { ModuleRvItem(it) }
        .let { itemsInstalled.update(it) }

    internal fun updateRepos() {
        Single.fromCallable { repoDatabase.repoCursor.toList { Repo(it) } }
            .flattenAsFlowable { it }
            .map { RepoRvItem(it) }
            .toList()
            .doOnSuccess { allItems.update(it) }
            .flatMap { queryRaw() }
            .applyViewModel(this)
            .subscribeK { itemsRemote.update(it.first, it.second) }
            .add()
    }

    private fun query() = queryRaw()
        .subscribeK { itemsRemote.update(it.first, it.second) }
        .add()

    private fun queryRaw(query: String = this.query.value) = allItems.toSingle()
        .map { it.filterIsInstance<RepoRvItem>() }
        .flattenAsFlowable { it }
        .filter {
            it.item.name.contains(query, ignoreCase = true) ||
                    it.item.author.contains(query, ignoreCase = true) ||
                    it.item.description.contains(query, ignoreCase = true)
        }
        .toList()
        .map { if (query.isEmpty()) it.divide() else it }
        .map { it to itemsRemote.calculateDiff(it) }

    private fun List<RepoRvItem>.divide(): List<ComparableRvItem<*>> {
        val installed = itemsInstalled.filterIsInstance<ModuleRvItem>()
        val installedModules = filter { installed.any { item -> it.item.id == item.item.id } }

        fun installedByID(id: String) = installed.firstOrNull { it.item.id == id }

        fun List<RepoRvItem>.filterObsolete() = filter {
            val module = installedByID(it.item.id) ?: return@filter false
            module.item.versionCode != it.item.versionCode
        }

        val resultObsolete = installedModules.filterObsolete()
        val resultInstalled = installedModules - resultObsolete
        val resultRemote = toList() - installedModules

        fun buildList(@StringRes text: Int, list: List<RepoRvItem>): List<ComparableRvItem<*>> {
            return if (list.isEmpty()) list
            else listOf(SectionRvItem(resources.getString(text))) + list
        }

        return buildList(R.string.update_available, resultObsolete) +
                buildList(R.string.installed, resultInstalled) +
                buildList(R.string.not_installed, resultRemote)
    }

    private fun <Result> Cursor.toList(transformer: (Cursor) -> Result): List<Result> {
        val out = mutableListOf<Result>()
        while (moveToNext()) out.add(transformer(this))
        return out
    }

}