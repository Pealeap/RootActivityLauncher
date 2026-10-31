package tk.zwander.rootactivitylauncher.data.model

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import tk.zwander.rootactivitylauncher.data.FilterMode
import tk.zwander.rootactivitylauncher.data.SortMode
import tk.zwander.rootactivitylauncher.data.SortOrder
import tk.zwander.rootactivitylauncher.util.AdvancedSearcher
import tk.zwander.rootactivitylauncher.util.distinctByPackageName
import tk.zwander.rootactivitylauncher.util.isSystemAppCompat
import java.util.regex.PatternSyntaxException

class MainModel(
    @Suppress("CanBeParameter", "RedundantSuppression")
    private val scope: CoroutineScope,
) {
    val apps = MutableStateFlow<List<BaseInfoModel>>(listOf())
    private val totalInitialSize = apps.map { apps ->
        combine(apps.map { it.totalInitialSize }) { sizes ->
            sizes.sum()
        }.fold(0) { accumulator, value -> accumulator + value }
    }.stateIn(scope, SharingStarted.Eagerly, 0)

    private val currentProgress = atomic(0)
    private val lastUpdateTime = atomic(0L)

    val enabledFilterMode = MutableStateFlow<FilterMode.EnabledFilterMode>(FilterMode.EnabledFilterMode.ShowAll)
    val exportedFilterMode = MutableStateFlow<FilterMode.ExportedFilterMode>(FilterMode.ExportedFilterMode.ShowAll)
    val permissionFilterMode = MutableStateFlow<FilterMode.PermissionFilterMode>(FilterMode.PermissionFilterMode.ShowAll)
    val componentFilterMode = MutableStateFlow<FilterMode.HasComponentsFilterMode>(FilterMode.HasComponentsFilterMode.ShowHasComponents)
    val systemAppsFilterMode = MutableStateFlow<FilterMode.SystemAppFilterMode>(FilterMode.SystemAppFilterMode.ShowAll)

    val sortAppsBy = MutableStateFlow<SortMode>(SortMode.SortByName)
    val sortOrder = MutableStateFlow<SortOrder>(SortOrder.Ascending)

    val query = MutableStateFlow("")

    /**
     * Debounced version of [query].
     *
     * As the user types, this prevents the full filter + sort of every
     * installed app (and every per-app component pipeline) from running
     * on every keystroke.
     */
    @OptIn(FlowPreview::class)
    private val debouncedQuery = query.debounce(200).stateIn(scope, SharingStarted.Eagerly, "")

    val progress = MutableStateFlow<Float?>(null)

    val useRegex = MutableStateFlow(false)
    val includeComponents = MutableStateFlow(false)

    val isSearching = MutableStateFlow(false)

    val filteredApps = combine(
        isSearching, useRegex, includeComponents,
        apps, enabledFilterMode, exportedFilterMode,
        permissionFilterMode, componentFilterMode, debouncedQuery,
        sortAppsBy, sortOrder, totalInitialSize, systemAppsFilterMode,
    ) { flowValues ->
        val useRegex = flowValues[1] as Boolean
        @Suppress("UNCHECKED_CAST")
        val apps = flowValues[3] as List<BaseInfoModel>
        val enabledFilterMode = flowValues[4] as FilterMode.EnabledFilterMode
        val exportedFilterMode = flowValues[5] as FilterMode.ExportedFilterMode
        val permissionFilterMode = flowValues[6] as FilterMode.PermissionFilterMode
        val componentFilterMode = flowValues[7] as FilterMode.HasComponentsFilterMode
        val query = flowValues[8] as String
        val sortAppsBy = flowValues[9] as SortMode
        val sortOrder = flowValues[10] as SortOrder
        val systemAppsFilterMode = flowValues[12] as FilterMode.SystemAppFilterMode

        val hasFilters = query.isNotBlank() ||
                enabledFilterMode != FilterMode.EnabledFilterMode.ShowAll ||
                exportedFilterMode != FilterMode.ExportedFilterMode.ShowAll ||
                permissionFilterMode != FilterMode.PermissionFilterMode.ShowAll

        // Compile the regex once per emission instead of once per app.
        val queryRegex = if (useRegex && query.isNotBlank()) {
            try {
                Regex(query)
            } catch (_: PatternSyntaxException) {
                null
            }
        } else {
            null
        }

        withContext(Dispatchers.IO) {
            val filtered = if (hasFilters ||
                (componentFilterMode !is FilterMode.HasComponentsFilterMode.ShowAll) ||
                (systemAppsFilterMode !is FilterMode.SystemAppFilterMode.ShowAll)) {
                apps.filter { matches(it, query, queryRegex, componentFilterMode, systemAppsFilterMode) }
            } else {
                apps
            }

            // Normalize sort keys once per emission instead of per comparison.
            val labelKeys = if (sortAppsBy == SortMode.SortByName) {
                HashMap<BaseInfoModel, String>(filtered.size).apply {
                    filtered.forEach {
                        if (it is AppModel) {
                            this[it] = it.label.toString().lowercase()
                        }
                    }
                }
            } else {
                null
            }

            filtered.sortedWith { o1, o2 ->
                when {
                    o1 is FavoriteModel && o2 !is FavoriteModel -> -1
                    o1 !is FavoriteModel && o2 is FavoriteModel -> 1
                    o1 is AppModel && o2 is AppModel -> {
                        when (sortAppsBy) {
                            SortMode.SortByName -> {
                                val keys = labelKeys!!
                                sortOrder.applyOrder(keys.getValue(o1).compareTo(keys.getValue(o2)))
                            }
                            SortMode.SortByInstalledDate -> sortOrder.applyOrder(o1.pInfo.firstInstallTime.compareTo(o2.pInfo.firstInstallTime))
                            SortMode.SortByUpdatedDate -> sortOrder.applyOrder(o1.pInfo.lastUpdateTime.compareTo(o2.pInfo.lastUpdateTime))
                        }
                    }
                    else -> 0
                }
            }.distinctByPackageName().also {
                progress.value = null
            }
        }
    }

    private fun matches(
        data: BaseInfoModel,
        query: String,
        queryRegex: Regex?,
        componentFilterMode: FilterMode.HasComponentsFilterMode,
        systemAppsFilterMode: FilterMode.SystemAppFilterMode,
    ): Boolean {
        val componentSize = data.totalInitialSize.value

        if (data is AppModel) {
            when (componentFilterMode) {
                FilterMode.HasComponentsFilterMode.ShowHasNoComponents -> {
                    if (componentSize > 0) {
                        return false
                    }
                }
                FilterMode.HasComponentsFilterMode.ShowHasComponents -> {
                    if (componentSize == 0) {
                        return false
                    }
                }
                else -> {}
            }

            when (systemAppsFilterMode) {
                FilterMode.SystemAppFilterMode.ShowSystemApps -> {
                    if (data.info?.isSystemAppCompat() == false) {
                        return false
                    }
                }
                FilterMode.SystemAppFilterMode.ShowNonSystemApps -> {
                    if (data.info?.isSystemAppCompat() == true) {
                        return false
                    }
                }
                else -> {}
            }
        }

        if (query.isBlank()) return true

        val activityFilterEmpty = data.filteredActivities.value.isEmpty()
        val serviceFilterEmpty = data.filteredServices.value.isEmpty()
        val receiverFilterEmpty = data.filteredReceivers.value.isEmpty()

        if (includeComponents.value && (!activityFilterEmpty || !serviceFilterEmpty || !receiverFilterEmpty)) return true

        if (data is AppModel) {
            val advancedMatch = AdvancedSearcher.matchesHasPermission(query, data)
                    || AdvancedSearcher.matchesRequiresPermission(query, data)
                    || AdvancedSearcher.matchesDeclaresPermission(query, data)
                    || AdvancedSearcher.matchesRequiresFeature(query, data)

            if (advancedMatch) return true

            if (queryRegex != null) {
                if (queryRegex.run {
                        containsMatchIn(data.pInfo.packageName)
                                || containsMatchIn(data.label)
                    }) {
                    return true
                }
            } else {
                if (data.label.contains(query, true)
                    || data.pInfo.packageName.contains(query, true)) {
                    return true
                }
            }
        }

        return false
    }

    fun updateProgress(checkProgress: Boolean = false) {
        if (checkProgress) {
            if (progress.value.run { this != null && this >= 100 }) {
                resetProgress()
            }
        } else {
            tk.zwander.rootactivitylauncher.util.updateProgress(
                currentProgress,
                lastUpdateTime,
                totalInitialSize.value
            ) { newProgress ->
                progress.value = newProgress
            }
        }
    }

    fun resetProgress() {
        progress.value = null
        currentProgress.value = 0
        lastUpdateTime.value = 0
    }
}