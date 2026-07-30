package com.example.url_blocker

/**
 * Launcher activity — a minimal entry point that inherits all behavior from MainActivity.
 *
 * This is a SEPARATE component from MainActivity with its own component identity.
 * The old code disabled "MainActivity" via PackageManager.setComponentEnabledSetting(),
 * and that state persisted across app updates. This new component has no disabled state,
 * so Android Studio can still launch the app.
 *
 * Once this activity runs, MainViewModel.initialize() calls ensureLauncherEnabled()
 * which re-enables "MainActivity" (the old component) for any future use.
 */
class LauncherActivity : MainActivity()
