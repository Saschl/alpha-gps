package com.sasch.cameragps.sharednew.ui.remote

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity

// Keep downward overscroll inside the content; the sheet's handle can still dismiss it.
internal object PhotoSheetScrollConnection : NestedScrollConnection {
    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset = Offset(0f, available.y.coerceAtLeast(0f))

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        Velocity(0f, available.y.coerceAtLeast(0f))
}
