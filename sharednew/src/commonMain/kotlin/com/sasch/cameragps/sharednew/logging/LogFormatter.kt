package com.sasch.cameragps.sharednew.logging

import kotlinx.coroutines.flow.Flow

interface LogFormatter {

    fun format(): Flow<List<String>>
}