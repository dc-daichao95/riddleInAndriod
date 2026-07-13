package dev.riddle.magicpaper

import android.app.Application

class RiddleApplication : Application() {
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }
}
