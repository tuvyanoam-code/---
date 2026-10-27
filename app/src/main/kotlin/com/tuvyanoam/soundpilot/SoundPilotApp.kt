package com.tuvyanoam.soundpilot

import android.app.Application
import com.tuvyanoam.soundpilot.platform.NotificationChannels
import com.tuvyanoam.soundpilot.service.LearningWorker

class SoundPilotApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NotificationChannels.create(this)
        LearningWorker.schedule(this)
    }
}
