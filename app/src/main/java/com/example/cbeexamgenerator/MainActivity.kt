package com.example.cbeexamgenerator

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val textView = TextView(this)
        textView.text = "App is working!"
        textView.textSize = 24f
        setContentView(textView)
    }
}