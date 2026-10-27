package com.jarvis.assistant

import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onAllNodesWithText

fun SemanticsNodeInteractionsProvider.onAllNodesWithTextCount(
    text: String,
    substring: Boolean = false,
): Int = onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size
