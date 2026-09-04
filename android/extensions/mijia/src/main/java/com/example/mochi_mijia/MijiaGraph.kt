package com.example.mochi_mijia

import android.content.Context

class MijiaGraph private constructor(context: Context) {
    val sessionStore = MijiaSessionStore(createMijiaDataStore(context))
    val passportQrClient = PassportQrClient(sessionStore)
    val cloudClient = MiotCloudClient()
    val repository = MiotRepository(
        sessionStore,
        cloudClient,
        passportQrClient,
    )
    val specificationClient = MiotSpecClient()
    val cameraEventClient = CameraEventClient(context)
    val toolExecutor = MijiaToolExecutor(
        repository,
        specificationClient,
        cameraEventClient,
        sessionStore,
        passportQrClient,
    )

    companion object {
        @Volatile
        private var instance: MijiaGraph? = null

        fun get(context: Context): MijiaGraph =
            instance ?: synchronized(this) {
                instance ?: MijiaGraph(context.applicationContext).also {
                    instance = it
                }
            }
    }
}
