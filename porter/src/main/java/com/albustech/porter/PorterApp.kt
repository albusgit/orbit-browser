package com.albustech.porter

import android.app.Application
import android.content.Context

class PorterApp : Application() {
    val porter by lazy { Porter(this) }
}

val Context.porter: Porter get() = (applicationContext as PorterApp).porter
