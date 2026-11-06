package com.example.libremaps

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [BookmarkEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    // fornece o acesso aos metodos do DAO de bookmarks
    abstract fun bookmarkDao(): BookmarkDao

    companion object {
        // @Volatile garante que as alterações nesta instância são visíveis imediatamente para todas as threads
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Retorna a instancia única da base de dados (Padrão Singleton)
        fun getDatabase(context: Context): AppDatabase {
            // se a instancia já existir retorna-a
            // se nao existir, sincroniza o acesso para evitar instancias duplicadas
            return INSTANCE ?: synchronized(this) {
                val dbinstance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "libremaps_database.db" // Nome do ficheiro SQLite criado no dispositivo
                ).build()
                INSTANCE = dbinstance
                dbinstance
            }
        }
    }
}