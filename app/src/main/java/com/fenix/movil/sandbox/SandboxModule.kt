// ============================================================================
// SandboxModule.kt — proveedores Hilt del subsistema sandbox
// ============================================================================

package com.fenix.movil.sandbox

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Grafo Hilt del sandbox: una única instancia [SandboxManager] por proceso,
 * con la configuración resuelta desde [SandboxPaths] (rutas + DNS reales
 * del dispositivo). Los consumidores (terminal, tools MCP, instalador de
 * CLIs) la inyectan como `SandboxManager`.
 */
@Module
@InstallIn(SingletonComponent::class)
object SandboxModule {
    @Provides
    @Singleton
    fun provideSandboxManager(@ApplicationContext context: Context): SandboxManager =
        SandboxManager(config = SandboxPaths.from(context).toConfig())
}
