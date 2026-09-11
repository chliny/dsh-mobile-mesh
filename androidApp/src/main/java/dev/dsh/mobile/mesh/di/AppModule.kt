package dev.dsh.mobile.mesh.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import dev.dsh.mobile.mesh.core.wire.WireJson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

private val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "dsh_mobile")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> = context.appDataStore

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        // A shorter heartbeat bounds detection of a carrier Android suspended while backgrounded.
        // Foreground recovery bypasses retry backoff once this reports a dead connection.
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideWireJson(): Json = WireJson

    @Provides
    fun provideIoDispatcher(): kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
}
