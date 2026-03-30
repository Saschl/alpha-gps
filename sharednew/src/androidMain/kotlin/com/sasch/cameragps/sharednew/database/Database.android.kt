package com.sasch.cameragps.sharednew.database

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase

fun getDatabaseBuilder(context: Context): RoomDatabase.Builder<LogDatabase> {
    val appContext = context.applicationContext
    val dbFile = appContext.getDatabasePath("log_database")
    return Room.databaseBuilder<LogDatabase>(
        context = appContext,
        name = dbFile.absolutePath
    )
}