package com.example.omronwear

import android.app.Application
import com.example.omronwear.presentation.OmronViewModel

class OmronWearApp : Application() {

    var viewModel: OmronViewModel? = null
        private set

    override fun onCreate() {
        super.onCreate()
        viewModel = OmronViewModel(this)
    }
}
