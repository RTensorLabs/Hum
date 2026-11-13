package labs.rtensor.music

import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollTo

/** Settings is one scrolling page; tests bring the section that holds the control they exercise into view. */
internal fun ComposeTestRule.settingsTab(label: String) {
    onAllNodes(hasText(label)).onFirst().performScrollTo()
    waitForIdle()
}
