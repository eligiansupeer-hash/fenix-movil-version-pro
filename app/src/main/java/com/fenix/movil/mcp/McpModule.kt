// ============================================================================
// McpModule.kt — módulo Hilt para MCP Server + ModelCatalog + T0Triage
// ============================================================================

package com.fenix.movil.mcp

import android.content.Context
import android.content.pm.PackageManager
import com.fenix.movil.core.modelcatalog.DefaultModelCatalog
import com.fenix.movil.core.modelcatalog.ModelCatalog
import com.fenix.movil.core.triage.T0TriageClient
import com.fenix.movil.mcp.server.EmbeddedMcpServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object McpModule {

    @Provides
    @Singleton
    fun provideModelCatalog(@ApplicationContext context: Context): ModelCatalog {
        val persistenceDir = context.getDir("modelcatalog", Context.MODE_PRIVATE)
        return DefaultModelCatalog(persistenceDir = persistenceDir)
    }

    @Provides
    @Singleton
    fun provideMcpServer(
        catalog: ModelCatalog,
        @ApplicationContext context: Context,
    ): EmbeddedMcpServer {
        // Puerto configurable vía metadata o default 8080
        val port = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getInt("fenix_mcp_port") ?: 8080
        } catch (_: Exception) {
            8080
        }
        return EmbeddedMcpServer(port = port, catalog = catalog)
    }

    @Provides
    @Singleton
    fun provideT0TriageClient(
        catalog: ModelCatalog,
        @ApplicationContext context: Context,
    ): T0TriageClient? {
        // Credenciales CF Workers AI desde KeystoreVault (C5) o metadata
        // Por ahora retorna null si no hay credenciales; C5 las inyectará
        val accountId = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("cf_account_id")
        } catch (_: Exception) { null }

        val apiToken = try {
            context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                .metaData?.getString("cf_api_token")
        } catch (_: Exception) { null }

        return if (accountId != null && apiToken != null && accountId.isNotBlank() && apiToken.isNotBlank()) {
            T0TriageClient(accountId = accountId, apiToken = apiToken, catalog = catalog)
        } else null
    }
}

