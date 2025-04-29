package com.example.filament_android_demo; // Replace with your package name

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.SwapChainFlags;
import com.google.android.filament.Texture;
import com.google.android.filament.View;
import com.google.android.filament.Viewport;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;


public class HeadlessRenderer {

    private static final String TAG = "HeadlessFilament";

    // --- Configuration ---
    private static final int IMAGE_WIDTH = 1280;
    private static final int IMAGE_HEIGHT = 720;
    // Skybox color (RGBA) - e.g., a soft blue
    private static final float[] SKY_COLOR = {0.2f, 0.4f, 0.8f, 1.0f};
    // ----------------

    private Engine mEngine = null;
    private SwapChain mSwapChain = null;
    private Renderer mRenderer = null;
    private Scene mScene = null;
    private View mView = null;
    private Camera mCamera = null;
    private int mCameraEntity = 0;
    private Skybox mSkybox = null;

    // Synchronization helper for readPixels callback
    private CountDownLatch mFrameLatch = null;
    
    // Initialization state
    private boolean mIsInitialized = false;

    /**
     * Initializes Filament resources needed for headless rendering.
     * This method must be called before render().
     * 
     * @return True if initialization was successful, false otherwise.
     */
    public boolean init() {
        if (mIsInitialized) {
            Log.w(TAG, "HeadlessRenderer already initialized");
            return true;
        }
        
        Log.i(TAG, "Initializing HeadlessRenderer...");
        
        try {
            // 1. Initialize Filament and Engine
            Filament.init(); // Ensure Filament JNI is loaded
            mEngine = Engine.create(); // Use default backend (often OpenGL on Android)
            if (mEngine == null) {
                Log.e(TAG, "Failed to create Filament engine.");
                return false;
            }
            Log.i(TAG, "Filament engine created (Backend: " + mEngine.getBackend() + ")");

            // 2. Create headless SwapChain
            mSwapChain = mEngine.createSwapChain(IMAGE_WIDTH, IMAGE_HEIGHT, SwapChainFlags.CONFIG_READABLE);
            if (mSwapChain == null) {
                Log.e(TAG, "Failed to create headless SwapChain.");
                cleanup();
                return false;
            }
            Log.i(TAG, "Headless SwapChain created (" + IMAGE_WIDTH + "x" + IMAGE_HEIGHT + ")");

            // 3. Create Renderer, Scene, View, Camera
            mRenderer = mEngine.createRenderer();
            mScene = mEngine.createScene();
            mView = mEngine.createView();
            mCameraEntity = EntityManager.get().create();
            mCamera = mEngine.createCamera(mCameraEntity);

            // 4. Configure View and Camera
            mView.setScene(mScene);
            mView.setCamera(mCamera);
            Viewport viewport = new Viewport(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
            mView.setViewport(viewport);

            // Set projection (matching C++ example)
            mCamera.setProjection(60.0,                               // fovInDegrees (vertical)
                (double) IMAGE_WIDTH / IMAGE_HEIGHT, // aspect ratio
                0.1,                                // near plane
                100.0,                              // far plane
                Camera.Fov.VERTICAL);
            // Set camera position and orientation
            mCamera.lookAt(0.0, 0.0, 3.0, // eye position
                0.0, 0.0, 0.0, // target position
                0.0, 1.0, 0.0); // up vector

            // --- Add any renderables to the scene here if needed ---
            // Example: addRenderable(mEngine, mScene);
            // -------------------------------------------------------

            // 5. Create and set Skybox
            mSkybox = new Skybox.Builder()
                .color(SKY_COLOR[0], SKY_COLOR[1], SKY_COLOR[2], SKY_COLOR[3])
                .build(mEngine);
            mScene.setSkybox(mSkybox);
            Log.i(TAG, "Skybox created, color: (" + SKY_COLOR[0] + ", " + SKY_COLOR[1] + ", " + SKY_COLOR[2] + ", " + SKY_COLOR[3] + ")");
            
            mIsInitialized = true;
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Exception during headless renderer initialization: ", e);
            cleanup();
            return false;
        }
    }

    /**
     * Performs headless rendering and returns the rendered image as a Bitmap.
     * Make sure to call init() before calling this method.
     *
     * @return CompletableFuture containing the rendered Bitmap, or completed exceptionally if rendering failed.
     */
    public CompletableFuture<Bitmap> render() {
        Log.i(TAG, "Starting headless render...");
        CompletableFuture<Bitmap> resultFuture = new CompletableFuture<>();
        
        if (!mIsInitialized) {
            resultFuture.completeExceptionally(new IllegalStateException("HeadlessRenderer not initialized. Call init() first."));
            return resultFuture;
        }
        
        // Reset the frame latch for this render operation
        mFrameLatch = new CountDownLatch(1);

        try {
            // Prepare pixel readback resources
            // Allocate a direct ByteBuffer for pixel data transfer (RGBA)
            final int bufferSize = IMAGE_WIDTH * IMAGE_HEIGHT * 4; // RGBA * 8 bits per channel
            final ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(bufferSize);
            pixelBuffer.order(ByteOrder.nativeOrder()); // Important for direct buffers

            // Use a Handler associated with the main looper for the callback
            final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

            // Callback Runnable
            final Runnable readPixelsCallback = () -> {
                try {
                    Log.i(TAG, "readPixelsCallback: Processing received pixels...");
                    pixelBuffer.rewind(); // Ensure buffer position is at the start

                    // Create a Bitmap to hold the pixels (ARGB_8888 matches RGBA UBYTE)
                    Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
                    bitmap.copyPixelsFromBuffer(pixelBuffer); // This should now work
                    
                    // Complete the future with the bitmap
                    resultFuture.complete(bitmap);
                    Log.i(TAG, "Bitmap created and future completed");
                } catch (Exception e) {
                    Log.e(TAG, "Exception in readPixelsCallback: ", e);
                    resultFuture.completeExceptionally(e);
                } finally {
                    mFrameLatch.countDown(); // Signal completion
                    Log.d(TAG, "readPixelsCallback finished.");
                }
            };

            // Create the PixelBufferDescriptor
            final Texture.PixelBufferDescriptor descriptor = new Texture.PixelBufferDescriptor(
                pixelBuffer,
                Texture.Format.RGBA, // Format we want back from GPU
                Texture.Type.UBYTE, // Type we want back from GPU
                1, // Alignment (usually 1 for byte operations)
                0, // Left offset in buffer (usually 0)
                0, // Top offset in buffer (usually 0)
                IMAGE_WIDTH, // Stride (in pixels)
                mainThreadHandler, // Handler to run the callback on
                readPixelsCallback // Runnable to execute when done
            );

            // Perform single frame rendering and pixel readback
            Log.i(TAG, "Beginning frame rendering...");
            // Use a fixed large value for frameTimeNanos, it's less critical for single-frame headless
            long frameTimeNanos = System.nanoTime();
            if (mRenderer.beginFrame(mSwapChain, frameTimeNanos)) {
                mRenderer.render(mView);

                Log.i(TAG, "Requesting pixel readback...");
                mRenderer.readPixels(
                    0, 0,             // xoffset, yoffset in the swap chain
                    IMAGE_WIDTH, IMAGE_HEIGHT, // width, height of region to read
                    descriptor         // the descriptor with buffer and callback
                );
                // Ownership of pixelBuffer transferred to Filament via descriptor

                mRenderer.endFrame();
                Log.i(TAG, "Frame ended.");

                // Wait for backend completion and callback execution
                Log.i(TAG, "Waiting for backend and callback...");
                // flushAndWait ensures all GPU commands *and* the readPixels command
                // (including its callback invocation via the handler) are finished.
                mEngine.flushAndWait();
                Log.i(TAG, "Engine flushed.");

                // Wait for the callback itself to finish its work
                // Add a timeout to prevent indefinite blocking
                boolean callbackFinished = mFrameLatch.await(10, TimeUnit.SECONDS);
                if (!callbackFinished) {
                    Log.e(TAG, "Callback did not complete within timeout!");
                    resultFuture.completeExceptionally(new RuntimeException("Rendering timed out"));
                }

            } else {
                Log.e(TAG, "renderer.beginFrame() failed!");
                resultFuture.completeExceptionally(new RuntimeException("Renderer beginFrame failed"));
            }

        } catch (Exception e) {
            Log.e(TAG, "Exception during headless rendering: ", e);
            resultFuture.completeExceptionally(e);
        }
        
        return resultFuture;
    }

    /**
     * Cleans up all initialized Filament resources.
     * Call this method when you're done with the HeadlessRenderer.
     */
    public void cleanup() {
        Log.i(TAG, "Cleaning up Filament resources...");
        
        mIsInitialized = false;
        
        // Destroy resources in reverse order of creation or based on dependencies
        if (mEngine != null) {
            // Make sure engine isn't destroyed before potential ongoing callbacks finish
            // (though flushAndWait should handle most of this)
            try {
                // If the latch timed out previously, give it one last chance briefly
                if (mFrameLatch != null && mFrameLatch.getCount() > 0) {
                    mFrameLatch.await(500, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Interrupted while waiting for callback in cleanup");
            }

            // Now proceed with destruction
            if (mSkybox != null) {
                if (mEngine.isValidSkybox(mSkybox)) { // Check validity before destroy
                    mEngine.destroySkybox(mSkybox);
                }
                mSkybox = null;
            }
            if (mCamera != null) {
                if (mCamera.getEntity() != 0) { // Check if entity is valid before destroying component
                    mEngine.destroyCameraComponent(mCamera.getEntity());
                }
                mCamera = null;
            }
            if (mCameraEntity != 0) {
                // The entity itself needs to be destroyed via EntityManager
                if (EntityManager.get().isAlive(mCameraEntity)) { // Check if entity exists
                    EntityManager.get().destroy(mCameraEntity);
                }
                mCameraEntity = 0;
            }
            if (mView != null) {
                if (mEngine.isValidView(mView)) {
                    mEngine.destroyView(mView);
                }
                mView = null;
            }
            if (mScene != null) {
                if (mEngine.isValidScene(mScene)) {
                    mEngine.destroyScene(mScene);
                }
                mScene = null;
            }
            if (mRenderer != null) {
                if (mEngine.isValidRenderer(mRenderer)) {
                    mEngine.destroyRenderer(mRenderer);
                }
                mRenderer = null;
            }
            if (mSwapChain != null) {
                if (mEngine.isValidSwapChain(mSwapChain)) {
                    mEngine.destroySwapChain(mSwapChain);
                }
                mSwapChain = null;
            }
            // Destroy the engine itself
            mEngine.destroy(); // This blocks until the backend thread is joined
            mEngine = null;
        }
        Log.i(TAG, "Cleanup complete.");
    }
}
