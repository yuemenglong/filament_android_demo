/**
 * 重要：所有 Filament 资源的创建与销毁必须在专用渲染线程（mRenderExecutor）上完成，以确保线程亲和性。
 * 否则会导致 “This thread has not been adopted” 等崩溃。
 */
package com.example.filament_android_demo; // Replace with your package name

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

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
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class HeadlessRenderer {

    private static final String TAG = "HeadlessFilament";

    // --- Configuration ---
    private static final int IMAGE_WIDTH = 1280;
    private static final int IMAGE_HEIGHT = 720;
    private static final float[] SKY_COLOR = {0.2f, 0.4f, 0.8f, 1.0f};
    private static final long RENDER_TIMEOUT_SECONDS = 15;
    private static final long INIT_TIMEOUT_SECONDS = 20; // Timeout for initialization
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 20;
    // ----------------

    // Make these volatile as they are accessed/assigned across threads,
    // although the single executor thread simplifies things.
    private volatile Engine mEngine = null;
    private volatile SwapChain mSwapChain = null;
    private volatile Renderer mRenderer = null;
    private volatile Scene mScene = null;
    private volatile View mView = null;
    private volatile Camera mCamera = null;
    private volatile int mCameraEntity = 0;
    private volatile Skybox mSkybox = null;

    private ExecutorService mRenderExecutor = null;
    private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

    private final AtomicBoolean mIsInitialized = new AtomicBoolean(false);
    private final AtomicBoolean mIsInitializing = new AtomicBoolean(false);
    private final AtomicBoolean mIsCleanedUp = new AtomicBoolean(false);


    /**
     * Initializes Filament resources and the background rendering thread.
     * This method BLOCKS until initialization is complete or fails.
     * It ensures all Filament objects are created on the dedicated render thread.
     * Safe to call multiple times.
     *
     * @return True if initialization was successful or already initialized, false otherwise.
     */
    public boolean init() {
        if (mIsInitialized.get()) {
             Log.w(TAG, "Already initialized.");
            return true;
        }
        if (mIsCleanedUp.get()) {
            Log.w(TAG, "HeadlessRenderer has been cleaned up. Cannot initialize.");
            return false;
        }
        if (!mIsInitializing.compareAndSet(false, true)) {
            Log.w(TAG, "Initialization already in progress by another thread.");
            return false;
        }

        Log.i(TAG, "Initializing HeadlessRenderer...");

        try {
            // 1. Create the executor first (outside the submitted task)
            if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
                 mRenderExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "FilamentRenderThread"));
                 Log.i(TAG, "Render executor created.");
            } else {
                 Log.w(TAG, "Render executor already exists. Reusing.");
            }

            // 2. Define the initialization task to run on the render thread
            Callable<Boolean> initTask = () -> {
                Log.i(TAG, "Executing initialization task on render thread...");
                try {
                    if (mIsInitialized.get()) return true;

                    Filament.init();
                    mEngine = Engine.create();
                    if (mEngine == null) {
                        Log.e(TAG, "Failed to create Filament engine on render thread.");
                        return false;
                    }
                    Log.i(TAG, "Filament engine created (Backend: " + mEngine.getBackend() + ")");

                    mSwapChain = mEngine.createSwapChain(IMAGE_WIDTH, IMAGE_HEIGHT, SwapChainFlags.CONFIG_READABLE);
                    if (mSwapChain == null) {
                        Log.e(TAG, "Failed to create headless SwapChain on render thread.");
                        if (mEngine != null) mEngine.destroy();
                        mEngine = null;
                        return false;
                    }
                    Log.i(TAG, "Headless SwapChain created.");

                    mRenderer = mEngine.createRenderer();
                    mScene = mEngine.createScene();
                    mView = mEngine.createView();
                    mCameraEntity = EntityManager.get().create();
                    mCamera = mEngine.createCamera(mCameraEntity);

                    mView.setScene(mScene);
                    mView.setCamera(mCamera);
                    Viewport viewport = new Viewport(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
                    mView.setViewport(viewport);

                    mCamera.setProjection(60.0, (double) IMAGE_WIDTH / IMAGE_HEIGHT, 0.1, 100.0, Camera.Fov.VERTICAL);
                    mCamera.lookAt(0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);

                    mSkybox = new Skybox.Builder()
                            .color(SKY_COLOR[0], SKY_COLOR[1], SKY_COLOR[2], SKY_COLOR[3])
                            .build(mEngine);
                    mScene.setSkybox(mSkybox);
                    Log.i(TAG, "Skybox created.");

                    mIsInitialized.set(true);
                    Log.i(TAG, "Filament resources initialized successfully on render thread.");
                    return true;
                } catch (Exception e) {
                    Log.e(TAG, "Exception during initialization task on render thread: ", e);
                    cleanupFilamentResourcesInternal();
                    return false;
                }
            };

            // 3. Submit the task and wait for its completion
            Log.i(TAG, "Submitting initialization task to render thread...");
            Future<Boolean> initFuture = mRenderExecutor.submit(initTask);

            boolean success = initFuture.get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            if (success) {
                Log.i(TAG, "HeadlessRenderer initialization successful (waited).");
                return true;
            } else {
                Log.e(TAG, "HeadlessRenderer initialization failed on render thread (waited).");
                cleanupInternal();
                return false;
            }

        } catch (ExecutionException e) {
             Log.e(TAG, "ExecutionException during initialization: ", e.getCause());
             cleanupInternal();
             return false;
         } catch (InterruptedException e) {
             Thread.currentThread().interrupt();
             Log.e(TAG, "Initialization interrupted: ", e);
             cleanupInternal();
             return false;
         } catch (Exception e) {
            Log.e(TAG, "Exception during initialization (e.g., timeout): ", e);
             cleanupInternal();
            return false;
        } finally {
            mIsInitializing.set(false);
        }
    }

    /**
     * Performs headless rendering asynchronously on the internal render thread.
     * Returns a CompletableFuture immediately.
     * Ensures init() was called successfully first.
     */
    @NonNull
    public CompletableFuture<Bitmap> render() {
        Log.d(TAG, "render() called.");
        CompletableFuture<Bitmap> resultFuture = new CompletableFuture<>();

        if (mIsCleanedUp.get()) {
             resultFuture.completeExceptionally(new IllegalStateException("HeadlessRenderer has been cleaned up. Cannot render."));
             return resultFuture;
        }

        if (!mIsInitialized.get()) {
            resultFuture.completeExceptionally(new IllegalStateException("HeadlessRenderer not initialized. Call init() first."));
            return resultFuture;
        }
        if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
             resultFuture.completeExceptionally(new IllegalStateException("Render executor is not available. Renderer likely not initialized correctly or cleaned up."));
             return resultFuture;
        }

        mRenderExecutor.submit(() -> {
            if (!mIsInitialized.get() || mIsCleanedUp.get()) {
                 Log.w(TAG, "Render task executing but renderer is no longer initialized or cleaned up.");
                 return;
            }

            Log.i(TAG, "Starting background render task on render thread...");
            final CountDownLatch frameLatch = new CountDownLatch(1);
            final AtomicBoolean timedOut = new AtomicBoolean(false);

            Runnable timeoutRunnable = () -> {
                if (frameLatch.getCount() > 0) {
                    if(timedOut.compareAndSet(false, true)) {
                        Log.e(TAG, "Rendering task timed out after " + RENDER_TIMEOUT_SECONDS + " seconds!");
                        resultFuture.completeExceptionally(new RuntimeException("Rendering timed out"));
                        frameLatch.countDown();
                    }
                }
            };
            mMainThreadHandler.postDelayed(timeoutRunnable, TimeUnit.SECONDS.toMillis(RENDER_TIMEOUT_SECONDS));

            try {
                final int bufferSize = IMAGE_WIDTH * IMAGE_HEIGHT * 4;
                final ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(bufferSize);
                pixelBuffer.order(ByteOrder.nativeOrder());

                final Runnable readPixelsCallback = () -> {
                     if (mIsCleanedUp.get()){
                         Log.w(TAG, "readPixelsCallback invoked but renderer cleaned up. Ignoring.");
                         frameLatch.countDown();
                         return;
                     }
                    if (timedOut.get()) {
                        Log.w(TAG, "readPixelsCallback invoked after timeout. Ignoring.");
                        frameLatch.countDown();
                        return;
                    }
                    mMainThreadHandler.removeCallbacks(timeoutRunnable);

                    try {
                        Log.i(TAG, "readPixelsCallback: Processing received pixels on main thread...");
                        pixelBuffer.rewind();
                        Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
                        bitmap.copyPixelsFromBuffer(pixelBuffer);
                        resultFuture.complete(bitmap);
                        Log.i(TAG, "Bitmap created and future completed successfully.");
                    } catch (Exception e) {
                        Log.e(TAG, "Exception in readPixelsCallback: ", e);
                        resultFuture.completeExceptionally(e);
                    } finally {
                        frameLatch.countDown();
                        Log.d(TAG, "readPixelsCallback finished processing.");
                    }
                };

                final Texture.PixelBufferDescriptor descriptor = new Texture.PixelBufferDescriptor(
                        pixelBuffer, Texture.Format.RGBA, Texture.Type.UBYTE,
                        1, 0, 0, IMAGE_WIDTH, mMainThreadHandler, readPixelsCallback);

                Log.i(TAG, "Beginning frame rendering on render thread...");
                long frameTimeNanos = System.nanoTime();

                 if (mRenderer == null || mSwapChain == null || mView == null || mEngine == null || !mEngine.isValid()) {
                     throw new IllegalStateException("Filament resources are not valid at the start of render task.");
                 }

                if (mRenderer.beginFrame(mSwapChain, frameTimeNanos)) {
                    mRenderer.render(mView);
                    Log.i(TAG, "Requesting pixel readback on render thread...");
                    mRenderer.readPixels(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, descriptor);
                    mRenderer.endFrame();
                    Log.i(TAG, "Frame ended on render thread.");

                    Log.i(TAG, "Calling flushAndWait on render thread...");
                    mEngine.flushAndWait();
                    Log.i(TAG, "flushAndWait completed on render thread.");

                    Log.i(TAG, "Render thread waiting for readPixelsCallback to complete...");
                    if (!frameLatch.await(RENDER_TIMEOUT_SECONDS + 2, TimeUnit.SECONDS)) {
                         Log.e(TAG, "Render thread timed out waiting for callback latch! Timeout mechanism might have failed.");
                          if (!timedOut.get() && !resultFuture.isDone()) {
                             resultFuture.completeExceptionally(new RuntimeException("Internal latch timeout waiting for callback"));
                          }
                    } else if (timedOut.get()) {
                         Log.w(TAG, "Render thread proceeding after timeout occurred (signaled by latch).");
                    } else {
                         Log.i(TAG, "Render thread resuming after callback completed.");
                    }

                } else {
                    Log.e(TAG, "renderer.beginFrame() failed on render thread!");
                    mMainThreadHandler.removeCallbacks(timeoutRunnable);
                    resultFuture.completeExceptionally(new RuntimeException("Renderer beginFrame failed"));
                }

            } catch (IllegalStateException ise) {
                Log.e(TAG, "IllegalStateException during background render task: ", ise);
                mMainThreadHandler.removeCallbacks(timeoutRunnable);
                resultFuture.completeExceptionally(ise);
            } catch (InterruptedException e) {
                 Thread.currentThread().interrupt();
                 Log.e(TAG, "Background render task interrupted: ", e);
                 mMainThreadHandler.removeCallbacks(timeoutRunnable);
                 resultFuture.completeExceptionally(e);
            } catch (Exception e) {
                Log.e(TAG, "Exception during background render task: ", e);
                mMainThreadHandler.removeCallbacks(timeoutRunnable);
                resultFuture.completeExceptionally(e);
            } finally {
                 Log.i(TAG, "Background render task finished execution.");
            }
        });

        Log.d(TAG, "render() returning future immediately.");
        return resultFuture;
    }

     /**
      * Cleans up all initialized Filament resources and shuts down the background thread.
      * Submits cleanup tasks to the render thread if necessary.
      */
     public void cleanup() {
         if (mIsCleanedUp.compareAndSet(false, true)) {
             Log.i(TAG, "cleanup() called. Initiating shutdown...");
             cleanupInternal();
             Log.i(TAG, "Cleanup process finished.");
         } else {
             Log.w(TAG, "cleanup() called, but already cleaned up or cleanup in progress.");
         }
     }


     /** Internal cleanup logic, potentially submitting tasks to render thread */
    private void cleanupInternal() {
        Log.i(TAG, "Starting internal cleanup...");
        mIsInitialized.set(false);

        mMainThreadHandler.removeCallbacksAndMessages(null);

        ExecutorService executor = mRenderExecutor;

        if (executor != null && !executor.isShutdown()) {
             Runnable cleanupTask = this::cleanupFilamentResourcesInternal;
             Future<?> cleanupFuture = executor.submit(cleanupTask);
             try {
                 cleanupFuture.get(SHUTDOWN_TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);
                 Log.i(TAG, "Filament resource cleanup task completed on render thread.");
             } catch (Exception e) {
                 Log.e(TAG, "Exception waiting for Filament resource cleanup task: ", e);
             }

            Log.i(TAG, "Shutting down render executor...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.e(TAG, "Render executor did not terminate gracefully.");
                    executor.shutdownNow();
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        Log.e(TAG, "Render executor did not terminate after shutdownNow().");
                    }
                } else {
                    Log.i(TAG, "Render executor terminated.");
                }
            } catch (InterruptedException ie) {
                Log.e(TAG, "Interrupted during executor shutdown.", ie);
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        } else {
             Log.w(TAG, "Render executor was null or already shutdown during cleanup. Skipping resource cleanup task submission.");
             cleanupFilamentResourcesInternal();
        }

        mRenderExecutor = null;

        Log.i(TAG, "Internal cleanup finished.");
    }

     /** Destroys Filament resources. Should be called on the render thread. */
     private void cleanupFilamentResourcesInternal() {
          Log.i(TAG, "Executing cleanupFilamentResourcesInternal on thread: " + Thread.currentThread().getName());
         if (mEngine != null) {
             Log.i(TAG, "Destroying Filament resources...");
             try {
                 if (mSkybox != null && mEngine.isValidSkybox(mSkybox)) mEngine.destroySkybox(mSkybox);
                 if (mCamera != null && mCamera.getEntity() != 0) mEngine.destroyCameraComponent(mCamera.getEntity());
                 if (mCameraEntity != 0 && EntityManager.get().isAlive(mCameraEntity)) EntityManager.get().destroy(mCameraEntity);
                 if (mView != null && mEngine.isValidView(mView)) mEngine.destroyView(mView);
                 if (mScene != null && mEngine.isValidScene(mScene)) mEngine.destroyScene(mScene);
                 if (mRenderer != null && mEngine.isValidRenderer(mRenderer)) mEngine.destroyRenderer(mRenderer);
                 if (mSwapChain != null && mEngine.isValidSwapChain(mSwapChain)) mEngine.destroySwapChain(mSwapChain);

                 Log.i(TAG, "Destroying Filament Engine...");
                 mEngine.destroy();
                 Log.i(TAG, "Filament Engine destroyed.");
             } catch(Exception e) {
                 Log.e(TAG, "Exception during Filament resource destruction: ", e);
             } finally {
                 mSkybox = null;
                 mCamera = null;
                 mCameraEntity = 0;
                 mView = null;
                 mScene = null;
                 mRenderer = null;
                 mSwapChain = null;
                 mEngine = null;
             }
         } else {
             Log.w(TAG, "Filament Engine was null during resource cleanup.");
         }
     }


    @Override
    protected void finalize() throws Throwable {
        try {
            if (!mIsCleanedUp.get()) {
                Log.w(TAG, "HeadlessRenderer finalize() called without explicit cleanup! Performing cleanup now.");
                cleanupInternal();
            }
        } finally {
            super.finalize();
        }
    }
}
