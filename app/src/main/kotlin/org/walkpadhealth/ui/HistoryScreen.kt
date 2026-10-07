package org.walkpadhealth.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import org.walkpadhealth.data.SessionEntity

@Composable fun HistoryScreen(all: List<SessionEntity>) =
    LazyColumn(contentPadding = PaddingValues(16.dp)) { items(all, key = { it.id }) { SessionRow(it) } }
