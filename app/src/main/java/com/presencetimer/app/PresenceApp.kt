package com.presencetimer.app

import android.app.Application

class PresenceApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        Engine.init()
    }
}
