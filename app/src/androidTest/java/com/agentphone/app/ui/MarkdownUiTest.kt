package com.agentphone.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.agentphone.app.ui.components.MarkdownText
import com.agentphone.app.ui.theme.AgentPhoneTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rendersHeadingCodeAndTable() {
        rule.setContent {
            AgentPhoneTheme(dynamicColor = false) {
                MarkdownText(
                    """
                    ## Hello agent

                    | Tool | Risk |
                    | --- | --- |
                    | files | write |

                    ```kotlin
                    val answer = 42
                    ```
                    """.trimIndent(),
                )
            }
        }
        rule.onNodeWithText("Hello agent").assertIsDisplayed()
        rule.onNodeWithText("files").assertIsDisplayed()
        rule.onNodeWithText("val answer = 42", substring = true).assertIsDisplayed()
    }
}
