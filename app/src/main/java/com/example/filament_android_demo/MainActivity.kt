package com.example.filament_android_demo

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.example.filament_android_demo.ui.theme.Filament_android_demoTheme

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    setContent {
      Filament_android_demoTheme {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
          Greeting(
            name = "Android",
            modifier = Modifier.padding(innerPadding)
          )
        }
      }
    }
  }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
  val context = LocalContext.current
  Column(modifier = modifier) {
    Text(
      text = "Hello $name!"
    )
    Button(onClick = {
      Toast.makeText(context, "拍摄", Toast.LENGTH_SHORT).show()
    }) {
      Text("拍摄")
    }
  }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
  Filament_android_demoTheme {
    Greeting("Android")
  }
}