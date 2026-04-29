package com.oli.projectsai.ui.repo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oli.projectsai.data.github.GitHubClient
import com.oli.projectsai.data.github.RepoSelectionStore
import com.oli.projectsai.data.preferences.GitHubSettings
import com.oli.projectsai.inference.InferenceManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RepoBrowserViewModel @Inject constructor(
    private val client: GitHubClient,
    private val settings: GitHubSettings,
    private val selectionStore: RepoSelectionStore,
    private val inferenceManager: InferenceManager
) : ViewModel() {

    sealed class Phase {
        data object PickRepo : Phase()
        data class Loading(val label: String) : Phase()
        data class Tree(
            val owner: String,
            val name: String,
            val ref: String,
            val rootNode: TreeNode,
            val truncated: Boolean
        ) : Phase()
        data class Error(val message: String) : Phase()
        /** No GitHub PAT configured yet — direct the user to Settings. */
        data object NeedsSetup : Phase()
    }

    /**
     * In-memory directory node. Children are populated up-front from the recursive
     * tree response, so expanding folders is purely a UI affair (no extra fetches).
     */
    data class TreeNode(
        val name: String,
        val path: String,
        val isDir: Boolean,
        val sizeBytes: Int,
        val children: MutableList<TreeNode> = mutableListOf()
    )

    private val _phase = MutableStateFlow<Phase>(Phase.PickRepo)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _repos = MutableStateFlow<List<GitHubClient.Repo>>(emptyList())
    val repos: StateFlow<List<GitHubClient.Repo>> = _repos.asStateFlow()

    /** Set of file paths currently checked. */
    private val _selected = MutableStateFlow<Set<String>>(emptySet())
    val selected: StateFlow<Set<String>> = _selected.asStateFlow()

    /** Folder paths the user has expanded, so we can render a flat list. */
    private val _expanded = MutableStateFlow<Set<String>>(emptySet())
    val expanded: StateFlow<Set<String>> = _expanded.asStateFlow()

    private val _injecting = MutableStateFlow(false)
    val injecting: StateFlow<Boolean> = _injecting.asStateFlow()

    /** Token-budget summary for the current selection. */
    private val _selectionStats = MutableStateFlow(SelectionStats())
    val selectionStats: StateFlow<SelectionStats> = _selectionStats.asStateFlow()

    val contextLimitFlow = inferenceManager.contextLimitFlow

    data class SelectionStats(
        val files: Int = 0,
        val totalBytes: Int = 0,
        val approxTokens: Int = 0
    )

    init {
        loadRepoList()
    }

    fun loadRepoList() {
        viewModelScope.launch {
            if (settings.pat.first().isBlank()) {
                _phase.value = Phase.NeedsSetup
                return@launch
            }
            _phase.value = Phase.Loading("Loading repositories…")
            try {
                val list = client.listRepos()
                _repos.value = list
                _phase.value = Phase.PickRepo
            } catch (t: Throwable) {
                _phase.value = Phase.Error(t.message ?: "Failed to list repos.")
            }
        }
    }

    fun pickRepo(repo: GitHubClient.Repo) {
        viewModelScope.launch {
            _phase.value = Phase.Loading("Loading ${repo.owner}/${repo.name}…")
            try {
                val tree = client.tree(repo.owner, repo.name, repo.defaultBranch)
                val root = buildNodeTree(tree.entries)
                _selected.value = emptySet()
                _expanded.value = setOf("")  // root expanded by default
                recomputeStats()
                runCatching { settings.setDefaultRepo("${repo.owner}/${repo.name}") }
                _phase.value = Phase.Tree(repo.owner, repo.name, tree.ref, root, tree.truncated)
            } catch (t: Throwable) {
                _phase.value = Phase.Error(t.message ?: "Failed to load repo tree.")
            }
        }
    }

    fun toggleExpanded(path: String) {
        _expanded.value = _expanded.value.toMutableSet().also {
            if (!it.add(path)) it.remove(path)
        }
    }

    fun toggleSelected(path: String) {
        _selected.value = _selected.value.toMutableSet().also {
            if (!it.add(path)) it.remove(path)
        }
        recomputeStats()
    }

    fun clearSelection() {
        _selected.value = emptySet()
        recomputeStats()
    }

    fun injectIntoChat(onDone: () -> Unit) {
        val tree = _phase.value as? Phase.Tree ?: return
        val sel = _selected.value
        if (sel.isEmpty()) return
        viewModelScope.launch {
            _injecting.value = true
            try {
                val files = sel.toList().sorted().mapNotNull { path ->
                    runCatching { client.file(tree.owner, tree.name, path, tree.ref) }.getOrNull()?.let {
                        RepoSelectionStore.StagedFile(it.path, it.sizeBytes, it.text)
                    }
                }
                if (files.isEmpty()) {
                    _phase.value = Phase.Error("Couldn't fetch any of the selected files.")
                    return@launch
                }
                selectionStore.stage(
                    RepoSelectionStore.Selection(
                        owner = tree.owner,
                        repo = tree.name,
                        ref = tree.ref,
                        files = files
                    )
                )
                onDone()
            } finally {
                _injecting.value = false
            }
        }
    }

    private fun recomputeStats() {
        val tree = _phase.value as? Phase.Tree
        val selectedNodes = if (tree == null) emptyList() else collectFiles(tree.rootNode)
            .filter { it.path in _selected.value }
        val totalBytes = selectedNodes.sumOf { it.sizeBytes }
        viewModelScope.launch {
            // ~3.5 chars/token for code; close enough for a budget hint.
            val tokens = (totalBytes / 3.5f).toInt()
            _selectionStats.value = SelectionStats(
                files = selectedNodes.size,
                totalBytes = totalBytes,
                approxTokens = tokens
            )
        }
    }

    private fun collectFiles(node: TreeNode): List<TreeNode> = buildList {
        if (!node.isDir) add(node)
        node.children.forEach { addAll(collectFiles(it)) }
    }

    /**
     * Build a parent → children map from GitHub's flat recursive tree listing.
     * Sort directories first, then alphabetically.
     */
    private fun buildNodeTree(entries: List<GitHubClient.TreeEntry>): TreeNode {
        val root = TreeNode(name = "", path = "", isDir = true, sizeBytes = 0)
        val byPath = mutableMapOf("" to root)
        // Ensure parents exist before we attach children.
        entries.sortedBy { it.path }.forEach { entry ->
            val isDir = entry.type == "tree"
            val parts = entry.path.split('/')
            val name = parts.last()
            val parentPath = if (parts.size == 1) "" else parts.dropLast(1).joinToString("/")
            val node = TreeNode(
                name = name,
                path = entry.path,
                isDir = isDir,
                sizeBytes = entry.size
            )
            byPath[entry.path] = node
            byPath[parentPath]?.children?.add(node)
        }
        fun sort(n: TreeNode) {
            n.children.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            n.children.forEach { if (it.isDir) sort(it) }
        }
        sort(root)
        return root
    }
}
