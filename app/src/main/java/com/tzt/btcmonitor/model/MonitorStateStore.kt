package com.tzt.btcmonitor.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class MonitorStateStore {
    private val mutableState = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = mutableState.asStateFlow()

    fun update(block: (MonitorState) -> MonitorState) = mutableState.update(block)
}
