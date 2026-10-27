package com.luma.remote

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.luma.remote.ui.LumaApp

class MainActivity:AppCompatActivity() {
    private lateinit var model:RemoteViewModel
    override fun onCreate(savedInstanceState:Bundle?) { super.onCreate(savedInstanceState); model=ViewModelProvider(this)[RemoteViewModel::class.java]; setContent { LumaApp(model) } }
    override fun onStart() { super.onStart(); if(::model.isInitialized)model.resume() }
    override fun onStop() { if(::model.isInitialized)model.pause(); super.onStop() }
}
