package dev.pi.android

import android.app.Application

class PiApplication : Application() {
    lateinit var automation: DeviceAutomation
        private set
    lateinit var runtime: PiRuntime
        private set
    override fun onCreate() {
        super.onCreate()
        automation = DeviceAutomation(this)
        runtime = PiRuntime(this)
        runtime.start()
    }
}
