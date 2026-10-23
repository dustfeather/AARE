package net.prototux.aare

import android.app.Application

class AareApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: AareApp
            private set
    }
}
