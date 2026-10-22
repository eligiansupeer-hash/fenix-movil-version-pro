// ============================================================================
// InstallerModule.kt — proveedores Hilt del instalador de CLIs (plan B5)
// ============================================================================

package com.fenix.movil.sandbox.installer

import android.content.Context
import com.fenix.movil.sandbox.SandboxPaths
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Grafo Hilt del instalador: una única instancia [CliInstaller] por
 * proceso, con la red del dispositivo ([AndroidNetworkProbe]) y la
 * configuración del sandbox resuelta desde [SandboxPaths] (mismas rutas
 * que el [com.fenix.movil.sandbox.SandboxManager] de B3).
 */
@Module
@InstallIn(SingletonComponent::class)
object InstallerModule {
    @Provides
    @Singleton
    fun provideNetworkProbe(@ApplicationContext context: Context): NetworkProbe =
        AndroidNetworkProbe(context)

    @Provides
    @Singleton
    fun provideCliInstaller(@ApplicationContext context: Context): CliInstaller =
        CliInstaller(
            networkProbe = AndroidNetworkProbe(context),
            runner = ProotGuestCommandRunner(SandboxPaths.from(context).toConfig()),
        )
}
