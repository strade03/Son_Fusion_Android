package com.podcastcreateur.app

object Constants {
    // Audio configuration
    const val SAMPLE_RATE = 44100
    const val CHANNELS = 1
    const val BIT_RATE = 128000
    const val MIME_TYPE_AUDIO = "audio/mp4a-latm" // AAC
    const val TIMEOUT_US = 5000L // 5ms timeout pour MediaCodec

    // Zoom limits
    const val MIN_ZOOM = 0.1f
    const val MAX_ZOOM = 25.0f // Réduit de 50 à 10 pour la stabilité
}