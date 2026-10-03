package dev.pi.android

import android.app.Application

class PiApplication : Application() {
    lateinit var runtime: PiRuntime
        private set
    override fun onCreate() {
        super.onCreate()
        runtime = PiRuntime(this)
        runtime.start()
    }
}
