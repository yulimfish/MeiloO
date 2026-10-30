package com.ljyh.mei.ui.screen.search

import kotlin.math.abs
import org.junit.Assume.assumeTrue
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.activity.BackEventCompat
import android.content.res.Configuration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.datastore.preferences.core.edit
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.ljyh.mei.MainActivity
import com.ljyh.mei.R
import com.ljyh.mei.constants.PredictiveBackAnimationEnabledKey
import com.ljyh.mei.data.model.room.Song
import com.ljyh.mei.data.model.room.SourceType
import com.ljyh.mei.di.AppDatabase
import com.ljyh.mei.utils.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises real activity navigation with restored preferences and local-only search fixtures. */
@RunWith(AndroidJUnit4::class)
class SearchNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var originalFixture: Song? = null
    private var fixtureInstalled = false

    @Before
    fun startsAtHome() {
        // A new activity starts at Home; LastSelectedTab only affects the tab highlight.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText(text(R.string.app_tab_home))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertHome()
    }

    @After
    fun restoreLocalSearchFixture() = runBlocking<Unit>(Dispatchers.IO) {
        if (!fixtureInstalled) return@runBlocking
        val dao = AppDatabase.getDatabase(compose.activity).songDao()
        dao.deleteById(SearchFixture.id)
        originalFixture?.let { dao.insertSong(it) }
        originalFixture = null
        fixtureInstalled = false
    }

    @Test
    fun miniPlayerUsesTheSearchControlGapWithAndWithoutTheKeyboard() {
        // Exercise the retained queue without replacing the user's tracks or playback state.
        val miniTag = "MeiloXMiniPlayerSurface"
        try {
            // Service connection and its retained playback snapshot can follow the Home title.
            compose.waitUntil(5_000) {
                compose.onAllNodesWithTag(miniTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        } catch (_: ComposeTimeoutException) {
            // An empty retained queue has no mini player; the assumption below covers that case.
        }
        assumeTrue(
            "This placement regression needs an existing retained mini player",
            compose.onAllNodesWithTag(miniTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
        )
        openSearch()
        val density = compose.activity.resources.displayMetrics.density
        val tolerance = density
        fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        fun keyboardVisible(): Boolean = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true
        fun assertSpacing(imeVisible: Boolean): Rect {
            compose.waitUntil(10_000) {
                val field = bounds("GlasmioSearchFieldSurface")
                val cancel = bounds("GlasmioSearchCancelSurface")
                val mini = bounds(miniTag)
                keyboardVisible() == imeVisible &&
                    abs((field.top - mini.bottom) - (cancel.left - field.right)) <= tolerance &&
                    mini.height >= 43f * density
            }
            val field = bounds("GlasmioSearchFieldSurface")
            val cancel = bounds("GlasmioSearchCancelSurface")
            val mini = bounds(miniTag)
            assertEquals("Horizontal controls retain their 8dp visible gap", 8f * density,
                cancel.left - field.right, tolerance)
            assertEquals("Mini player and search use the same visible edge gap",
                cancel.left - field.right, field.top - mini.bottom, tolerance)
            assertTrue("Search field retains a 44dp target", field.height >= 44f * density)
            assertTrue("Cancel retains a 44dp target", cancel.height >= 44f * density)
            return field
        }
        try {
            compose.runOnUiThread {
                WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            val restingField = assertSpacing(imeVisible = false)
            val configuration = compose.activity.resources.configuration
            if (configuration.keyboard == Configuration.KEYBOARD_NOKEYS ||
                configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_YES
            ) {
                searchInput().performClick()
                compose.runOnUiThread {
                    WindowCompat.getInsetsController(compose.activity.window, compose.activity.window.decorView)
                        .show(WindowInsetsCompat.Type.ime())
                }
                val raisedField = assertSpacing(imeVisible = true)
                assertTrue("Both controls must move above the keyboard", raisedField.top < restingField.top)
            }
        } finally {
            compose.onNodeWithContentDescription(text(R.string.cancel)).performClick()
            assertHome()
        }
    }

    @Test
    fun bottomToolbarIsTheOnlyInputAndFiltersLocalSongs() {
        installLocalSearchFixture()
        openSearch()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("搜索本地曲库").assertIsDisplayed()
        searchInput().performTextInput("no matching local song")
        compose.onNodeWithText("没有匹配的本地歌曲").assertIsDisplayed()

        searchInput().performTextReplacement(SearchFixture.query)
        compose.onNodeWithText(SearchFixture.title).assertIsDisplayed()
        compose.onNodeWithText("没有匹配的本地歌曲").assertDoesNotExist()

        searchInput().performTextReplacement("")
        compose.onNodeWithText(SearchFixture.title).assertDoesNotExist()
        compose.onNodeWithText("搜索本地曲库").assertIsDisplayed()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
    }

    @Test
    fun emptySearchBackRemovesTheToolbarWithTheLocalPrompt() {
        openSearch()
        compose.onNodeWithText("搜索本地曲库").assertIsDisplayed()

        navigateBack()

        assertHome()
        compose.onNodeWithText("搜索本地曲库").assertDoesNotExist()
    }

    @Test
    fun leavingTypedSearchClearsTheQueryBeforeReopening() {
        openSearch()
        searchInput().performTextInput("Codex navigation regression")

        navigateBack()
        assertHome()
        openSearch()

        assertEmptyQuery()
        compose.onNodeWithContentDescription(text(R.string.cancel)).performClick()
        assertHome()
    }

    @Test
    fun imeSearchKeepsLocalResultsWithoutOpeningOnlineDetails() {
        installLocalSearchFixture()
        openSearch()
        searchInput().performTextInput(SearchFixture.query)
        compose.onNodeWithText(SearchFixture.title).assertIsDisplayed()

        searchInput().performImeAction()

        searchInput().assertIsDisplayed().assertIsNotFocused()
        assertQuery(SearchFixture.query)
        compose.onNodeWithText(SearchFixture.title).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.search_details_title)).assertDoesNotExist()
    }

    @Test
    fun songPageSearchEntryFocusesTheOnlyBottomInput() {
        compose.onNodeWithContentDescription("搜索本地歌曲").performClick()

        searchInput().assertIsDisplayed().assertIsFocused()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithText("搜索本地曲库").assertIsDisplayed()
    }

    @Test
    fun predictiveBackToggleUpdatesTheCurrentPageWithoutChangingTheBackStack() {
        val dataStore = compose.activity.dataStore
        val originalValue = runBlocking(Dispatchers.IO) {
            dataStore.data.first()[PredictiveBackAnimationEnabledKey]
        }

        fun toggleRow() = compose.onNodeWithText(text(R.string.appearance_predictive_back))
        fun toggle() = toggleRow().onChildren().filterToOne(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
        )
        fun bodyLeft() = toggleRow().fetchSemanticsNode().boundsInRoot.left
        fun openAppearance() {
            compose.onNodeWithContentDescription(text(R.string.settings)).performClick()
            compose.onNodeWithText(text(R.string.settings_appearance)).performClick()
            compose.onNode(
                hasScrollToNodeAction() and
                    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
            ).performScrollToNode(hasText(text(R.string.appearance_predictive_back)))
        }
        fun startGesture() {
            compose.runOnUiThread {
                compose.activity.onBackPressedDispatcher.apply {
                    dispatchOnBackStarted(BackEventCompat(0f, 200f, 0f, BackEventCompat.EDGE_LEFT))
                    dispatchOnBackProgressed(BackEventCompat(100f, 200f, 0.25f, BackEventCompat.EDGE_LEFT))
                }
            }
            compose.waitForIdle()
        }
        fun cancelGesture() {
            compose.runOnUiThread {
                compose.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
            }
            compose.waitForIdle()
        }

        try {
            // Apply OFF at Home before the display first observes a previous scene.
            runBlocking(Dispatchers.IO) {
                dataStore.edit { it[PredictiveBackAnimationEnabledKey] = false }
            }
            compose.waitForIdle()
            openAppearance()
            toggle().assertIsOff()

            listOf(false, true, false, true).forEachIndexed { index, enabled ->
                if (index > 0) toggle().performSemanticsAction(SemanticsActions.OnClick) { it() }
                val expectedState = if (enabled) ToggleableState.On else ToggleableState.Off
                compose.waitUntil(timeoutMillis = 5_000) {
                    toggle().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expectedState
                }
                if (enabled) toggle().assertIsOn() else toggle().assertIsOff()
                compose.waitForIdle()
                val restingLeft = bodyLeft()

                try {
                    startGesture()
                    if (enabled) {
                        compose.waitUntil(timeoutMillis = 5_000) { bodyLeft() > restingLeft + 8f }
                        assertTrue("An enabled gesture must preview the previous page", bodyLeft() > restingLeft + 8f)
                    } else {
                        assertEquals("A disabled gesture must leave the page in place", restingLeft, bodyLeft(), 1f)
                    }
                } finally {
                    cancelGesture()
                }
                assertEquals("Cancelling must restore the current page", restingLeft, bodyLeft(), 1f)
            }

            listOf(true, false).forEach { enabled ->
                if (!enabled) {
                    openAppearance()
                    toggle().performSemanticsAction(SemanticsActions.OnClick) { it() }
                    compose.waitUntil(timeoutMillis = 5_000) {
                        toggle().fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == ToggleableState.Off
                    }
                    toggle().assertIsOff()
                }
                startGesture()
                navigateBack()
                compose.onNodeWithText(text(R.string.appearance_predictive_back)).assertDoesNotExist()
                compose.onNodeWithText(text(R.string.general_settings)).assertIsDisplayed()
                navigateBack()
                assertHome()
            }
        } finally {
            cancelGesture()
            runBlocking(Dispatchers.IO) {
                dataStore.edit {
                    if (originalValue == null) it.remove(PredictiveBackAnimationEnabledKey)
                    else it[PredictiveBackAnimationEnabledKey] = originalValue
                }
            }
        }
    }

    private fun text(resource: Int) = compose.activity.getString(resource)

    private fun searchInput() = compose.onNode(hasSetTextAction())

    private fun openSearch() {
        compose.onNodeWithContentDescription(text(R.string.app_tab_search)).performClick()
        searchInput().assertIsDisplayed()
    }

    private fun assertHome() {
        searchInput().assertDoesNotExist()
        compose.onAllNodesWithText(text(R.string.app_tab_home))[0].assertIsDisplayed()
        compose.onNodeWithContentDescription(text(R.string.app_tab_search)).assertIsDisplayed()
    }

    private fun assertQuery(query: String) {
        searchInput().assertIsDisplayed().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(query)),
        )
    }

    private fun assertEmptyQuery() {
        searchInput().assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
        )
    }

    private fun installLocalSearchFixture() {
        runBlocking(Dispatchers.IO) {
            val dao = AppDatabase.getDatabase(compose.activity).songDao()
            originalFixture = dao.getById(SearchFixture.id)
            dao.insertSong(SearchFixture.song)
            fixtureInstalled = true
        }
    }

    private fun navigateBack() {
        // Test destination changes directly; system Back may first dismiss the IME.
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private object SearchFixture {
        const val id = "meiloo-instrumented-search-fixture"
        const val query = "search fixture 7f3a9"
        const val title = "MeiloO $query"
        val song = Song(
            id = id,
            title = title,
            artist = listOf("Local Search Fixture Artist"),
            album = "Local Search Fixture Album",
            cover = "",
            duration = 120_000L,
            path = "content://meiloo.test/$id",
            sourceType = SourceType.LOCAL,
            addedAt = 0L,
            updatedAt = 0L,
        )
    }
}
