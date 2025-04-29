package com.example.filament_android_demo

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.filament_android_demo.ui.theme.Filament_android_demoTheme
import java.util.concurrent.CompletionException // Import CompletionException

class MainActivity : ComponentActivity() {
    private lateinit var headlessRenderer: HeadlessRenderer
    private var isRendererInitialized = false // Track initialization state

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        headlessRenderer = HeadlessRenderer()

        // Initialize renderer - handle potential exceptions
        try {
            isRendererInitialized = headlessRenderer.init() // Store result
            if (!isRendererInitialized) {
                showToast("HeadlessRenderer 初始化失败")
                Log.e("MainActivity", "HeadlessRenderer initialization failed.")
            } else {
                showToast("HeadlessRenderer 初始化成功")
                Log.i("MainActivity", "HeadlessRenderer initialization successful.")
            }
        } catch (e: Exception) {
            isRendererInitialized = false // Ensure state is false on exception
            showToast("HeadlessRenderer 初始化异常: ${e.message}")
            Log.e("MainActivity", "HeadlessRenderer initialization exception", e)
        }

        enableEdgeToEdge()
        setContent {
            Filament_android_demoTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    // Pass the renderer and its init status to the composable
                    RenderScreen(
                        modifier = Modifier.padding(innerPadding),
                        renderer = headlessRenderer,
                        isRendererReady = isRendererInitialized // Pass init status
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MainActivity", "onDestroy called, cleaning up renderer.")
        // Cleanup renderer resources if it was successfully initialized
        if (::headlessRenderer.isInitialized) { // Check if instance exists
             headlessRenderer.cleanup() // Cleanup always, even if init failed partially
        }
    }

    // Helper function for showing toasts
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun RenderScreen(
    modifier: Modifier = Modifier,
    renderer: HeadlessRenderer,
    isRendererReady: Boolean // Receive initialization status
) {
    val context = LocalContext.current
    // State for holding the rendered bitmap
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // State to control dialog visibility
    var showDialog by remember { mutableStateOf(false) }
    // State to indicate if rendering is in progress
    var isLoading by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Click '拍摄' to render an image using Headless Filament."
        )
        Button(
            onClick = {
                if (!isRendererReady) {
                    Toast.makeText(context, "Renderer not ready!", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (isLoading) return@Button // Prevent multiple clicks while loading

                isLoading = true // Start loading indicator
                renderedBitmap = null // Clear previous bitmap
                Log.d("RenderScreen", "Render button clicked, starting render...")

                renderer.render().handle { bitmap, throwable ->
                    // This handler runs when the CompletableFuture completes
                    // Ensure UI updates run on the main thread (though CompletableFuture often handles this)
                    (context as? ComponentActivity)?.runOnUiThread {
                        isLoading = false // Stop loading indicator
                        if (throwable != null) {
                            // Handle errors
                            val cause = if (throwable is CompletionException) throwable.cause else throwable
                            Log.e("RenderScreen", "Rendering failed", cause)
                            Toast.makeText(
                                context,
                                "Rendering failed: ${cause?.message ?: "Unknown error"}",
                                Toast.LENGTH_LONG
                            ).show()
                        } else if (bitmap != null) {
                            // Handle success
                            Log.d("RenderScreen", "Rendering successful, bitmap received.")
                            renderedBitmap = bitmap
                            showDialog = true // Show the dialog
                        } else {
                             Log.e("RenderScreen", "Rendering completed but bitmap was null.")
                             Toast.makeText(context, "Rendering failed: No bitmap received", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            },
            // Disable button if renderer isn't ready or if loading
            enabled = isRendererReady && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onPrimary // Adapts to theme
                )
            } else {
                Text("拍摄 (Capture)")
            }
        }
    }

    // --- Dialog to display the rendered image ---
    if (showDialog && renderedBitmap != null) {
        AlertDialog(
            onDismissRequest = {
                // Called when the user clicks outside the dialog or presses back button
                showDialog = false
                // Optionally recycle bitmap here if you won't reuse it,
                // but be careful if state restoration happens.
                // renderedBitmap?.recycle() // Use with caution
                renderedBitmap = null // Clear the bitmap state
            },
            title = { Text("Rendered Image") },
            text = {
                // Display the bitmap
                Image(
                    bitmap = renderedBitmap!!.asImageBitmap(), // Use the state variable
                    contentDescription = "Headless Render Result",
                    modifier = Modifier.fillMaxSize(0.8f) // Adjust size as needed
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    // renderedBitmap?.recycle() // Optional: recycle here too
                    renderedBitmap = null // Clear the bitmap state
                }) {
                    Text("Close")
                }
            }
        )
    }
}

// Keep the preview, but it won't interact with the actual renderer
@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    Filament_android_demoTheme {
        // Provide dummy values for the preview
        RenderScreen(renderer = HeadlessRenderer(), isRendererReady = true)
    }
}