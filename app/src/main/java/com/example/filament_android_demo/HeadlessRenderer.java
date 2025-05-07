/**
 * 重要：所有 Filament 资源的创建与销毁必须在专用渲染线程（mRenderExecutor）上完成，以确保线程亲和性。
 * 否则会导致 “This thread has not been adopted” 等崩溃。
 */
package com.example.filament_android_demo; // Replace with your package name

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.opengl.Matrix; // Import for matrix math
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.filament.Box; // Use this Box type
import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.Entity;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
// Removed direct import of filament.MaterialProvider if present
import com.google.android.filament.LightManager;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.SwapChainFlags;
import com.google.android.filament.Texture;
import com.google.android.filament.TransformManager;
import com.google.android.filament.View;
import com.google.android.filament.Viewport;

import com.google.android.filament.RenderableManager; // 新增
import com.google.mediapipe.tasks.components.containers.Category; // 新增

// Correct gltfio imports based on provided source
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.MaterialProvider; // Use gltfio version
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;

import com.google.android.filament.utils.Float3;
import com.google.android.filament.utils.Utils;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;


import java.io.BufferedInputStream;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


public class HeadlessRenderer {

  /**
   * 存储单个实体上的 morph target 信息。
   */
  private static class MorphInfo {
    final int entityId;
    final String morphTargetName;
    final int morphTargetIndex;
    final int totalMorphTargetsForEntity;

    MorphInfo(int entityId, String morphTargetName, int morphTargetIndex, int totalMorphTargetsForEntity) {
      this.entityId = entityId;
      this.morphTargetName = morphTargetName;
      this.morphTargetIndex = morphTargetIndex;
      this.totalMorphTargetsForEntity = totalMorphTargetsForEntity;
    }
  }

  // Morph Target 名称 -> 受影响的 MorphInfo 列表
  private volatile Map<String, List<MorphInfo>> mMorphTargetInfoMap = new ConcurrentHashMap<>();


  // 可选：存储 ApplicationContext 以便后续使用
  private volatile Context mApplicationContext;

  private static final String TAG = "HeadlessFilament";
  // Positive value shifts model visually downwards. Tune as needed.
  private static final float VERTICAL_CENTERING_ADJUSTMENT_FACTOR = 0.2f;
  public static final String headMeshName = "Wolf3D_Head";
  public static final String headName = "Head";

  // --- Configuration ---
  private static final int IMAGE_WIDTH = 600;
  private static final int IMAGE_HEIGHT = 800;
  private static final float[] SKY_COLOR = {0.2f, 0.4f, 0.8f, 1.0f};
  private static final long RENDER_TIMEOUT_SECONDS = 15;
  private static final long INIT_TIMEOUT_SECONDS = 20;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 20;
  // private static final long LOAD_MODEL_TIMEOUT_SECONDS = 60; // Can add if needed
  // ----------------

  // --- Filament Core Objects ---
  private volatile Engine mEngine = null;
  private volatile SwapChain mSwapChain = null;
  private volatile Renderer mRenderer = null;
  private volatile Scene mScene = null;
  private volatile View mView = null;
  private volatile Camera mCamera = null;
  private volatile int mCameraEntity = 0;
  private volatile Skybox mSkybox = null;
  private volatile int mLightEntity = 0; // 新增：光源实体ID

  // --- gltfio Objects ---
  private volatile AssetLoader mAssetLoader = null;
  private volatile ResourceLoader mResourceLoader = null; // Added ResourceLoader instance
  private volatile FilamentAsset mCurrentAsset = null;
  private volatile int[] mAssetEntities = null;


  // ***** 新增：存储实体名称和其初始世界变换矩阵的 Map *****
  // 使用 ConcurrentHashMap 以支持可能的并发读取（尽管写入只在渲染线程）
  private volatile java.util.Map<String, float[]> mEntityInitialTransforms = new java.util.concurrent.ConcurrentHashMap<>();
  // *********************************************************

  private ExecutorService mRenderExecutor = null;
  private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

  private final AtomicBoolean mIsInitialized = new AtomicBoolean(false);
  private final AtomicBoolean mIsCleanedUp = new AtomicBoolean(false);


  private boolean initFilamentCore() {
    Filament.init();
    Utils.INSTANCE.init();
    mEngine = Engine.create();
    if (mEngine == null) { // Check and log here
      Log.e(TAG, "Failed to create Filament Engine.");
      return false;
    }
    Log.i(TAG, "Filament engine created (Backend: " + mEngine.getBackend() + ")");

    // Use gltfio's MaterialProvider
    MaterialProvider materialProvider = new UbershaderProvider(mEngine);
    mAssetLoader = new AssetLoader(mEngine, materialProvider, EntityManager.get());
    // Use the two-argument ResourceLoader constructor
    mResourceLoader = new ResourceLoader(mEngine, true /*normalizeSkinningWeights*/);

    mSwapChain = mEngine.createSwapChain(IMAGE_WIDTH, IMAGE_HEIGHT, SwapChainFlags.CONFIG_READABLE);
    if (mSwapChain == null) { /* cleanup and return false */
      Log.e(TAG, "Failed to create headless SwapChain.");
      if (mAssetLoader != null) mAssetLoader.destroy();
      if (mResourceLoader != null) mResourceLoader.destroy();
      // UbershaderProvider doesn't need destroy() itself, MaterialProvider interface might
      // but typically UbershaderProvider manages its materials via AssetLoader.
      // If using a different MaterialProvider, it might need explicit cleanup.
      // materialProvider.destroyMaterials(); // Usually handled by AssetLoader/Engine
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
    mView.setViewport(new Viewport(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT));

    // Default camera setup (adjust as needed)
    mCamera.setProjection(60.0, (double) IMAGE_WIDTH / IMAGE_HEIGHT, 0.1, 1000.0, Camera.Fov.VERTICAL);
    // Place camera slightly further back or adjust model position/scale later
    mCamera.lookAt(0.0, 1.0, 5.0, // Eye position
      0.0, 0.0, 0.0, // Target position
      0.0, 1.0, 0.0); // Up vector

    mSkybox = new Skybox.Builder()
      .color(SKY_COLOR[0], SKY_COLOR[1], SKY_COLOR[2], SKY_COLOR[3])
      .build(mEngine);
    mScene.setSkybox(mSkybox);
    Log.i(TAG, "Skybox created.");

    // ***** 添加方向光源 START *****
    try {
      mLightEntity = EntityManager.get().create();

      // 简单的方向光（类似阳光）
      final float lightIntensity = 100_000.0f; // Lux
      final float[] lightColor = {0.98f, 0.92f, 0.89f}; // 略带暖色
      final float[] lightDirection = {0.5f, -1.0f, -0.8f};

      // 归一化方向向量
      float len = (float) Math.sqrt(lightDirection[0] * lightDirection[0] + lightDirection[1] * lightDirection[1] + lightDirection[2] * lightDirection[2]);
      lightDirection[0] /= len;
      lightDirection[1] /= len;
      lightDirection[2] /= len;

      new com.google.android.filament.LightManager.Builder(com.google.android.filament.LightManager.Type.DIRECTIONAL)
        .color(lightColor[0], lightColor[1], lightColor[2])
        .intensity(lightIntensity)
        .direction(lightDirection[0], lightDirection[1], lightDirection[2])
        .castShadows(true)
        .build(mEngine, mLightEntity);

      mScene.addEntity(mLightEntity);
      Log.i(TAG, "Directional light created and added to scene (Entity ID: " + mLightEntity + ").");

    } catch (Exception e) {
      Log.e(TAG, "Failed to create light source.", e);
    }
    // ***** 添加方向光源 END *****

    Log.i(TAG, "Filament core resources initialized successfully on render thread.");
    return true;
  }

  private void initLoadModel(Context context) {
    // ***** MODIFICATION START *****
    // 初始化成功后异步加载模型
    Log.i(TAG, "Initiating initial model load: man1.glb");
    loadModel(context, "man1.glb")
      .thenAccept(modelLoaded -> {
        if (modelLoaded) {
          Log.i(TAG, "Initial model 'man1.glb' loaded successfully (async).");
        } else {
          Log.e(TAG, "Initial model 'man1.glb' failed to load (async).");
        }
      })
      .exceptionally(ex -> {
        Log.e(TAG, "Exception occurred during initial model 'man1.glb' load (async).", ex);
        return null;
      });
    // ***** MODIFICATION END *****
  }

  /**
   * Initializes the HeadlessRenderer, sets up Filament resources, and loads the initial model.
   *
   * @param context The Android application context, needed for accessing assets.
   * @return true if core Filament initialization was successful, false otherwise.
   * Note: This return value does *not* guarantee the model loaded successfully,
   * as model loading happens asynchronously after initialization.
   */
  public boolean init(@NonNull Context context) {
    if (mIsInitialized.get()) {
      Log.w(TAG, "Already initialized.");
      return true;
    }
    if (mIsCleanedUp.get()) {
      Log.e(TAG, "Cannot initialize after cleanup.");
      return false;
    }

    // 可选：存储 context 以便后续使用
    mApplicationContext = context.getApplicationContext();

    Log.i(TAG, "Initializing HeadlessRenderer...");

    try {
      if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
        mRenderExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "FilamentRenderThread"));
        Log.i(TAG, "Render executor created.");
      }

      Callable<Boolean> initTask = () -> {
        Log.i(TAG, "Executing initialization task on render thread...");
        try {
          if (mIsInitialized.get()) return true; // Re-check inside task
          return initFilamentCore();// Core initialization successful
        } catch (Exception e) {
          Log.e(TAG, "Exception during initialization task on render thread: ", e);
          cleanupFilamentResourcesInternal(); // Attempt cleanup on the same thread
          return false;
        }
      };

      Log.i(TAG, "Submitting initialization task to render thread...");
      Future<Boolean> initFuture = mRenderExecutor.submit(initTask);
      boolean success = initFuture.get(INIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);

      if (success) {
        mIsInitialized.set(true);
        Log.i(TAG, "HeadlessRenderer core initialization successful (waited).");
        initLoadModel(context); // Load model after core init
        return true; // Return true for successful core init
      } else {
        Log.e(TAG, "HeadlessRenderer initialization failed on render thread (waited).");
        shutdownExecutorService(); // Clean up executor if task failed
        return false;
      }
    } catch (ExecutionException e) {
      Log.e(TAG, "ExecutionException during initialization: ", e.getCause());
      shutdownExecutorService();
      return false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.e(TAG, "Initialization interrupted: ", e);
      shutdownExecutorService();
      return false;
    } catch (Exception e) { // Catches TimeoutException and others
      Log.e(TAG, "Exception during initialization (e.g., timeout): ", e);
      shutdownExecutorService();
      return false;
    }
    // 移除 finally 块（原本为空）
  }

  /**
   * 应用 MediaPipe FaceLandmarkerResult 的 blendshapes 到模型的 morph target。
   * 异步在渲染线程执行。
   *
   * @param result FaceLandmarkerResult
   */
  @NonNull
  public CompletableFuture<Void> applyLandmarkResult(@Nullable FaceLandmarkerResult result) {
    CompletableFuture<Void> future = new CompletableFuture<>();

    if (mIsCleanedUp.get()) {
      future.completeExceptionally(new IllegalStateException("Renderer is cleaned up."));
      return future;
    }
    if (!mIsInitialized.get()) {
      future.completeExceptionally(new IllegalStateException("Renderer not initialized."));
      return future;
    }
    if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
      future.completeExceptionally(new IllegalStateException("Render executor not available."));
      return future;
    }
    if (result == null) {
      // Changed to complete normally as per discussion, maybe no face was detected.
      Log.i(TAG, "applyLandmarkResult: FaceLandmarkerResult is null, applying no changes.");
      future.complete(null);
      return future;
    }

    // --- Extract Blendshapes ---
    Map<String, Float> blendshapeMap = new HashMap<>();
    java.util.Optional<java.util.List<java.util.List<Category>>> blendshapesOptional = result.faceBlendshapes();
    if (blendshapesOptional.isPresent() && !blendshapesOptional.get().isEmpty() && !blendshapesOptional.get().get(0).isEmpty()) {
      List<Category> blendshapes = blendshapesOptional.get().get(0);
      for (Category blendshape : blendshapes) {
        blendshapeMap.put(blendshape.categoryName(), blendshape.score());
      }
      Log.d(TAG, "Extracted " + blendshapeMap.size() + " blendshapes.");
    } else {
      Log.i(TAG, "applyLandmarkResult: No blendshapes found in the result.");
      // Continue without blendshapes, still apply rotation if available
    }

    // --- Extract Transformation Matrix ---
    float[] faceTransformMatrix = null;
    java.util.Optional<java.util.List<float[]>> matrixesOptional = result.facialTransformationMatrixes();
    if (matrixesOptional.isPresent() && !matrixesOptional.get().isEmpty()) {
        faceTransformMatrix = matrixesOptional.get().get(0); // Get the first face's matrix
        if(faceTransformMatrix.length != 16) {
            Log.e(TAG, "applyLandmarkResult: Facial transformation matrix has incorrect size: " + faceTransformMatrix.length);
            faceTransformMatrix = null; // Invalidate if incorrect size
        } else {
             Log.d(TAG, "Extracted facial transformation matrix.");
        }
    } else {
        Log.i(TAG, "applyLandmarkResult: No facial transformation matrix found in the result.");
        // Continue without rotation
    }

    // --- Submit Task to Render Thread ---
    // Need final references for the lambda
    final Map<String, Float> finalBlendshapeMap = blendshapeMap;
    final float[] finalFaceTransformMatrix = faceTransformMatrix; // Can be null

    Log.d(TAG, "Submitting applyLandmarkResult task to render thread.");
    mRenderExecutor.submit(() -> {
      try {
        long startTime = System.nanoTime();

        // 1. Apply Blendshapes
        if (!finalBlendshapeMap.isEmpty()) {
           Log.d(TAG, "Render task: Applying blendshapes...");
           setMorphWeightsInternal(finalBlendshapeMap);
        } else {
           Log.d(TAG, "Render task: No blendshapes to apply.");
        }

        // 2. Apply Rotation (if matrix exists)
        boolean rotationApplied = false;
        if (finalFaceTransformMatrix != null) {
          Log.d(TAG, "Render task: Calculating and applying rotation...");
          // Convert matrix to Euler angles
          float[] eulerAngles = matrixToEulerAnglesZYX(finalFaceTransformMatrix);
          Log.d(TAG, "Render task: Calculated Euler Angles (X,Y,Z radians): " + Arrays.toString(eulerAngles));

          // Apply rotation to the Head entity
          rotationApplied = rotateInternal(headName, -eulerAngles[0], -eulerAngles[1], eulerAngles[2]);
          if(!rotationApplied) {
              Log.e(TAG, "Render task: Failed to apply rotation.");
          }
        } else {
             Log.d(TAG, "Render task: No rotation matrix to apply.");
        }

        // 3. Update Bone Matrices (if rotation was applied)
        if (rotationApplied) {
            Log.d(TAG, "Render task: Updating bone matrices due to rotation...");
            updateBoneMatricesInternal();
        } else {
            Log.d(TAG, "Render task: Skipping bone update as no rotation was applied.");
        }

        long endTime = System.nanoTime();
        Log.d(TAG, "Render task: applyLandmarkResult execution took " + (endTime - startTime) / 1e6 + " ms.");

        future.complete(null); // Signal successful completion

      } catch (Exception e) {
        Log.e(TAG, "Exception during applyLandmarkResult execution on render thread.", e);
        future.completeExceptionally(e); // Signal failure
      }
    });

    return future;
  }

  /**
   * Converts a 4x4 rotation matrix (in OpenGL column-major format) to Euler angles
   * using the ZYX convention (intrinsic rotation).
   * Note: This implementation assumes the input matrix is purely rotation or affine
   * transformation where the upper-left 3x3 is a valid rotation matrix.
   *
   * @param matrix The 4x4 rotation matrix (float[16]).
   * @return A float[3] array containing {angleX, angleY, angleZ} in radians.
   */
  private float[] matrixToEulerAnglesZYX(float[] matrix) {
    // Based on http://www.euclideanspace.com/maths/geometry/rotations/conversions/matrixToEuler/index.htm
    // and checking common ZYX implementations. Assumes matrix is column-major.
    // matrix[col*4 + row]

    float m00 = matrix[0]; // R11
    float m01 = matrix[1]; // R21
    float m02 = matrix[2]; // R31

    float m10 = matrix[4]; // R12
    float m11 = matrix[5]; // R22
    float m12 = matrix[6]; // R32

    float m20 = matrix[8];  // R13
    float m21 = matrix[9];  // R23
    float m22 = matrix[10]; // R33

    float angleY, angleZ, angleX;

    // Calculate Y-axis rotation (Pitch)
    angleY = (float) -Math.asin(Math.max(-1.0f, Math.min(1.0f, m20))); // -asin(R13)

    // Check for gimbal lock (when cos(angleY) is close to 0)
    if (Math.abs(Math.cos(angleY)) > 1e-6) {
      // Not in gimbal lock
      // Calculate X-axis rotation (Roll)
      angleX = (float) Math.atan2(m21, m22); // atan2(R23, R33)
      // Calculate Z-axis rotation (Yaw)
      angleZ = (float) Math.atan2(m10, m00); // atan2(R12, R11)
    } else {
      // Gimbal lock
      Log.w(TAG, "Gimbal lock detected during Euler angle conversion.");
      // Assume Z = 0 (standard convention)
      angleZ = 0.0f;
      // Calculate X-axis rotation (Roll) based on the remaining elements
      angleX = (float) Math.atan2(-m12, m11); // atan2(-R32, R22)
    }

    // Return in {X, Y, Z} order for the rotate function
    return new float[]{angleX, angleY, angleZ};
  }

  /**
   * Internal method to update bone matrices for the current asset.
   * Must be called on the render thread.
   */
  private void updateBoneMatricesInternal() {
    if (mEngine == null || !mEngine.isValid() || mCurrentAsset == null) {
        Log.w(TAG, "updateBoneMatricesInternal: Invalid state.");
        return;
    }
    // NOTE: FilamentAsset might have multiple instances in the future,
    // but typically there's one for a single loaded glTF.
    // Get the default instance (index 0).
    com.google.android.filament.gltfio.FilamentInstance filamentInstance = mCurrentAsset.getInstance();
    if (filamentInstance != null) {
        com.google.android.filament.gltfio.Animator animator = filamentInstance.getAnimator();
        if (animator != null) {
            Log.d(TAG, "Updating bone matrices...");
            animator.updateBoneMatrices();
        } else {
            Log.d(TAG, "updateBoneMatricesInternal: No animator found on the instance.");
        }
    } else {
       Log.w(TAG, "updateBoneMatricesInternal: No FilamentInstance found on the asset.");
    }
  }

  /**
   * Internal core logic for rotating an entity.
   * Must be called on the render thread.
   *
   * @param entityName Entity name
   * @param x          X rotation (radians)
   * @param y          Y rotation (radians)
   * @param z          Z rotation (radians)
   * @return true if successful, false otherwise
   */
  private boolean rotateInternal(@NonNull String entityName, float x, float y, float z) {
      if (mEngine == null || !mEngine.isValid()) {
          Log.e(TAG, "rotateInternal: Engine is not valid.");
          return false;
      }

      // 1. 查找初始变换
      float[] initialTransform = mEntityInitialTransforms.get(entityName);
      if (initialTransform == null) {
          Log.e(TAG, "rotateInternal failed: Initial transform for entity '" + entityName + "' not cached.");
          return false;
      }

      // 2. 查找实体ID
      int entityId = findEntityByNameInternal(entityName);
      if (entityId == Entity.NULL) { // Check against Entity.NULL which is 0
          Log.e(TAG, "rotateInternal failed: Entity '" + entityName + "' not found (despite having cached transform).");
          return false;
      }

      TransformManager tm = mEngine.getTransformManager();
      if (!tm.hasComponent(entityId)) {
          Log.e(TAG, "rotateInternal failed: Entity '" + entityName + "' has no Transform component.");
          return false;
      }
      int instance = tm.getInstance(entityId);

      // 3. 构造旋转矩阵 (Z * Y * X order)
      float[] rotationX = new float[16];
      float[] rotationY = new float[16];
      float[] rotationZ = new float[16];
      float[] temp = new float[16];
      float[] desiredRotation = new float[16];

      Matrix.setIdentityM(rotationX, 0);
      Matrix.rotateM(rotationX, 0, (float) Math.toDegrees(x), 1, 0, 0); // rotateM takes degrees
      Matrix.setIdentityM(rotationY, 0);
      Matrix.rotateM(rotationY, 0, (float) Math.toDegrees(y), 0, 1, 0);
      Matrix.setIdentityM(rotationZ, 0);
      Matrix.rotateM(rotationZ, 0, (float) Math.toDegrees(z), 0, 0, 1);

      // desiredRotation = rotationZ * rotationY * rotationX
      Matrix.multiplyMM(temp, 0, rotationY, 0, rotationX, 0);
      Matrix.multiplyMM(desiredRotation, 0, rotationZ, 0, temp, 0);

      // 4. newLocalTransform = initialTransform * desiredRotation
      float[] newLocalTransform = new float[16];
      Matrix.multiplyMM(newLocalTransform, 0, initialTransform, 0, desiredRotation, 0);

      // 5. 应用变换
      tm.setTransform(instance, newLocalTransform);

      Log.d(TAG, "Applied absolute rotation to entity '" + entityName + "' (x=" + x + ", y=" + y + ", z=" + z + ").");
      return true;
  }

  /**
   * Loads a glTF/GLB model from the app's assets folder into the scene.
   * Replaces any previously loaded model.
   * Executes asynchronously on the render thread.
   * <p>
   * NOTE: This version assumes a self-contained GLB or glTF with embedded resources.
   * Loading external resources requires pre-fetching them and using addResourceData
   * before calling this method.
   *
   * @param context   Android context to access assets.
   * @param assetPath Path to the model file within the assets folder (e.g., "models/my_model.glb").
   * @return A CompletableFuture that completes with true if loading was successful, false otherwise.
   */
  @NonNull
  public CompletableFuture<Boolean> loadModel(@NonNull Context context, @NonNull String assetPath) {
    Log.i(TAG, "loadModel called for asset: " + assetPath);
    CompletableFuture<Boolean> loadFuture = new CompletableFuture<>();

    if (mIsCleanedUp.get()) { /* complete exceptionally */
      return loadFuture;
    }
    if (!mIsInitialized.get()) { /* complete exceptionally */
      return loadFuture;
    }
    if (mRenderExecutor == null || mRenderExecutor.isShutdown()) { /* complete exceptionally */
      return loadFuture;
    }

    mRenderExecutor.submit(() -> {
      if (!mIsInitialized.get() || mIsCleanedUp.get()) {
        Log.w(TAG, "Model loading task executing but renderer is no longer valid.");
        loadFuture.complete(false);
        return;
      }

      Log.i(TAG, "Executing model loading task on render thread for: " + assetPath);
      boolean success = false;

      try {
        // --- Remove previous asset ---
        if (mCurrentAsset != null) {
          // Ensure removal and destruction happen on the render thread
          if (mAssetEntities != null) {
            Log.d(TAG, "Removing previous model entities from scene.");
            mScene.removeEntities(mAssetEntities);
          }
          Log.d(TAG, "Destroying previous FilamentAsset.");
          mAssetLoader.destroyAsset(mCurrentAsset);
          mCurrentAsset = null;
          mAssetEntities = null;
        }
        // 清空 morph target 信息
        mMorphTargetInfoMap.clear();
        Log.d(TAG, "Cleared morph target info map for new model.");

        // --- Read asset into ByteBuffer ---
        AssetManager assetManager = context.getAssets();
        ByteBuffer byteBuffer;
        try (InputStream inputStream = assetManager.open(assetPath);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
             ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
          byte[] buffer = new byte[1024];
          int len;
          while ((len = bufferedInputStream.read(buffer)) != -1) {
            byteArrayOutputStream.write(buffer, 0, len);
          }
          byte[] bytes = byteArrayOutputStream.toByteArray();
          byteBuffer = ByteBuffer.wrap(bytes); // Use wrap for direct memory access if possible
          Log.d(TAG, "Read " + bytes.length + " bytes from asset: " + assetPath);
        } catch (IOException e) {
          Log.e(TAG, "Failed to read asset: " + assetPath, e);
          loadFuture.completeExceptionally(e);
          return;
        }

        // --- Load GLTF Asset ---
        if (mAssetLoader == null || mResourceLoader == null) {
          throw new IllegalStateException("AssetLoader/ResourceLoader is null. Initialization incomplete?");
        }

        // Use createAsset (not createAssetFromBinary)
        FilamentAsset newAsset = mAssetLoader.createAsset(byteBuffer);
        if (newAsset == null) {
          Log.e(TAG, "Failed to load asset: " + assetPath + ". createAsset returned null.");
          loadFuture.complete(false);
          return;
        }
        Log.i(TAG, "Asset created: " + assetPath);
        mCurrentAsset = newAsset;

        // --- Load Resources (Textures, etc.) ---
        // For self-contained GLB or glTF with embedded data, ResourceLoader handles it.
        // For external resources, they *must* have been added via addResourceData before this point.
        Log.d(TAG, "Loading resources for asset...");
        mResourceLoader.loadResources(newAsset); // Synchronous loading on this thread
        newAsset.releaseSourceData(); // Release original glTF buffer data after loading
        Log.i(TAG, "Resources loaded for asset: " + assetPath);

        // --- Add to Scene ---
        mAssetEntities = newAsset.getEntities();
        mScene.addEntities(mAssetEntities);
        Log.i(TAG, "Added " + mAssetEntities.length + " entities to the scene.");

        // ***** 新增：准备 Morph Target 信息 *****
        prepareMorphTargetInfoInternal();
        // *************************************


        // ***** MODIFICATION START: Record Entity Names and Transforms *****
        // 在加载新模型前，清除旧模型的变换信息
        mEntityInitialTransforms.clear();
        Log.d(TAG, "Cleared previous entity transform map.");

        if (mAssetEntities != null && mAssetEntities.length > 0) {
          Log.i(TAG, "--- Processing Entities in Asset '" + assetPath + "' for Transform Map ---");
          TransformManager tm = mEngine.getTransformManager(); // 获取 TransformManager

          for (int entityId : mAssetEntities) {
            String entityName = newAsset.getName(entityId);

            // 只处理有名称的实体
            if (entityName != null && !entityName.isEmpty()) {
              // 检查实体是否有 Transform 组件
              if (tm.hasComponent(entityId)) {
                int instance = tm.getInstance(entityId);
                float[] transformMatrix = new float[16]; // 分配空间存储 4x4 矩阵
                tm.getTransform(instance, transformMatrix); // 获取世界变换矩阵

                // 存储名称和变换矩阵到 Map 中
                mEntityInitialTransforms.put(entityName, transformMatrix);
                Log.i(TAG, "Stored transform for Entity ID: " + entityId + ", Name: '" + entityName + "'");
                // 可以选择性打印矩阵内容进行调试:
                // Log.d(TAG, "  Transform: " + Arrays.toString(transformMatrix));
              } else {
                Log.w(TAG, "Entity ID: " + entityId + ", Name: '" + entityName + "' has a name but no Transform component. Skipping transform storage.");
              }
            } else {
              // 可选：记录没有名称的实体ID
              Log.d(TAG, "Entity ID: " + entityId + " has no specific name in the glTF asset. Skipping transform storage.");
            }
          }
          Log.i(TAG, "--- Finished Processing Entities for Transform Map ---");
        } else {
          Log.w(TAG, "Asset '" + assetPath + "' loaded but reported 0 entities.");
        }
        // ***** MODIFICATION END: Record Entity Names and Transforms *****

        // --- Manual Transform to Unit Cube ---
        // The helper newAsset.transformToUnitCube(...) doesn't exist in 1.57.1
        TransformManager tm = mEngine.getTransformManager();
        int rootEntity = newAsset.getRoot();
        if (tm.hasComponent(rootEntity)) {
          int rootInstance = tm.getInstance(rootEntity);
          Box aabb = newAsset.getBoundingBox();
          float[] center = aabb.getCenter(); // float[3]
          float[] halfExtent = aabb.getHalfExtent(); // float[3]

          float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));
          // Avoid division by zero for empty or invalid boxes
          float scaleFactor = (maxExtent > 1e-6f) ? (1.0f / (maxExtent * 2.0f)) : 1.0f;
          // Center the model at the origin after scaling
          float tx = -center[0] * scaleFactor;
          float ty = -center[1] * scaleFactor;
          float tz = -center[2] * scaleFactor;

          float[] scaleMatrix = new float[16];
          float[] translationMatrix = new float[16];
          float[] finalTransform = new float[16];

          Matrix.setIdentityM(scaleMatrix, 0);
          Matrix.scaleM(scaleMatrix, 0, scaleFactor, scaleFactor, scaleFactor);

          Matrix.setIdentityM(translationMatrix, 0);
          Matrix.translateM(translationMatrix, 0, tx, ty, tz);

          // The transformation order should be: scale first, then translate.
          // Matrix multiplication order is result = translation * scale
          Matrix.multiplyMM(finalTransform, 0, translationMatrix, 0, scaleMatrix, 0);

          tm.setTransform(rootInstance, finalTransform);
          Log.d(TAG, "Manually transformed asset to unit cube at origin. Scale: " + scaleFactor);
        } else {
          Log.w(TAG, "Asset root entity " + rootEntity + " does not have a transform component.");
        }


        success = true;

      } catch (Exception e) {
        Log.e(TAG, "Exception during model loading task: ", e);
        // Cleanup partially loaded asset if necessary
        if (mCurrentAsset != null && mAssetLoader != null) {
          try {
            mAssetLoader.destroyAsset(mCurrentAsset);
          } catch (Exception cleanupEx) {
            Log.e(TAG, "Exception during asset cleanup: ", cleanupEx);
          }
          mCurrentAsset = null;
          mAssetEntities = null;
        }
        loadFuture.completeExceptionally(e);
      } finally {
        // mResourceLoader itself is not destroyed here, only per-load resources if needed
        if (success) {
          Log.i(TAG, "Model loading task completed successfully for: " + assetPath);
          loadFuture.complete(true);
        } else if (!loadFuture.isDone()) {
          Log.e(TAG, "Model loading task failed for: " + assetPath);
          loadFuture.complete(false);
        }
        Log.i(TAG, "Model loading task finished execution on render thread.");
      }
    });

    Log.d(TAG, "loadModel returning future immediately.");
    return loadFuture;
  }

  @NonNull
  public CompletableFuture<Bitmap> render() {
    // ... (render method remains largely the same as the fixed version from the previous turn) ...
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
      final AtomicBoolean callbackSuccess = new AtomicBoolean(false);

      Runnable timeoutRunnable = () -> {
        if (frameLatch.getCount() > 0) {
          if (timedOut.compareAndSet(false, true)) {
            Log.e(TAG, "Rendering task timed out after " + RENDER_TIMEOUT_SECONDS + " seconds (Main Thread Timeout)!");
            if (!callbackSuccess.get()) {
              resultFuture.completeExceptionally(new RuntimeException("Rendering timed out"));
            }
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
          if (mIsCleanedUp.get()) {
            Log.w(TAG, "readPixelsCallback invoked but renderer cleaned up. Ignoring.");
            frameLatch.countDown();
            return;
          }
          if (timedOut.get()) {
            Log.w(TAG, "readPixelsCallback invoked after timeout. Ignoring bitmap creation.");
            frameLatch.countDown();
            return;
          }

          mMainThreadHandler.removeCallbacks(timeoutRunnable);

          try {
            Log.i(TAG, "readPixelsCallback: Processing received pixels on main thread...");
            pixelBuffer.rewind();
            Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
            bitmap.copyPixelsFromBuffer(pixelBuffer);
            callbackSuccess.set(true);
            resultFuture.complete(bitmap);
            Log.i(TAG, "Bitmap created and future completed successfully.");
          } catch (Exception e) {
            Log.e(TAG, "Exception in readPixelsCallback: ", e);
            if (!timedOut.get()) {
              resultFuture.completeExceptionally(e);
            }
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
          if (!frameLatch.await(RENDER_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)) {
            Log.e(TAG, "Render thread timed out waiting for callback latch!");
            if (!timedOut.get() && !resultFuture.isDone()) {
              resultFuture.completeExceptionally(new RuntimeException("Internal latch timeout waiting for callback"));
            }
          } else if (timedOut.get()) {
            Log.w(TAG, "Render thread proceeding after timeout occurred (signaled by latch).");
          } else {
            Log.i(TAG, "Render thread resuming after callback completed normally.");
          }

        } else {
          Log.e(TAG, "renderer.beginFrame() failed on render thread!");
          mMainThreadHandler.removeCallbacks(timeoutRunnable);
          resultFuture.completeExceptionally(new RuntimeException("Renderer beginFrame failed"));
          frameLatch.countDown();
        }

      } catch (IllegalStateException ise) {
        Log.e(TAG, "IllegalStateException during background render task: ", ise);
        mMainThreadHandler.removeCallbacks(timeoutRunnable);
        resultFuture.completeExceptionally(ise);
        frameLatch.countDown();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        Log.e(TAG, "Background render task interrupted: ", e);
        mMainThreadHandler.removeCallbacks(timeoutRunnable);
        resultFuture.completeExceptionally(e);
        frameLatch.countDown();
      } catch (Exception e) {
        Log.e(TAG, "Exception during background render task: ", e);
        mMainThreadHandler.removeCallbacks(timeoutRunnable);
        resultFuture.completeExceptionally(e);
        frameLatch.countDown();
      } finally {
        Log.i(TAG, "Background render task finished execution on render thread.");
      }
    });

    Log.d(TAG, "render() returning future immediately.");
    return resultFuture;
  }

  /**
   * 查找当前已加载资产中与给定名称匹配的第一个实体。
   * 必须在 Filament 渲染线程上调用。
   *
   * @param name 实体名称
   * @return 找到则返回实体ID，否则返回 Entity.NULL (0)
   */
  private int findEntityByNameInternal(@NonNull String name) {
    if (mCurrentAsset == null || mAssetEntities == null) {
      Log.w(TAG, "findEntityByNameInternal: No asset loaded.");
      return Entity.NULL;
    }
    for (int entityId : mAssetEntities) {
      String currentName = mCurrentAsset.getName(entityId);
      if (currentName != null && currentName.equals(name)) {
        Log.d(TAG, "findEntityByNameInternal: Found entity '" + name + "' with ID: " + entityId);
        return entityId;
      }
    }
    Log.w(TAG, "findEntityByNameInternal: Entity with name '" + name + "' not found.");
    return Entity.NULL;
  }

  /**
   * 计算将给定包围盒缩放/平移到单位立方体的变换矩阵。
   * 必须在 Filament 渲染线程上调用。
   *
   * @param aabb        包围盒
   * @param zOffset     沿Z轴偏移
   * @param scaleFactor 附加缩放因子
   * @return 4x4 OpenGL 格式变换矩阵
   */
  private float[] fitIntoUnitCubeInternal(@NonNull Box aabb, float zOffset, float scaleFactor) {
    float[] center = aabb.getCenter();
    float[] halfExtent = aabb.getHalfExtent();
    float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));
    float baseScale = (maxExtent > 1e-6f) ? (1.0f / maxExtent) : 1.0f;
    float finalScale = baseScale * scaleFactor;
    float adjustedCenterZ = center[2] + (zOffset / finalScale);

    // Calculate the vertical offset based on the AABB height and the adjustment factor
    float aabbHeight = halfExtent[1] * 2.0f;
    float verticalOffsetInAABBSpace = aabbHeight * VERTICAL_CENTERING_ADJUSTMENT_FACTOR;

    // The new target Y-coordinate in the AABB's local space to be centered.
    float targetYToCenter = center[1] + verticalOffsetInAABBSpace;

    float[] scaleMatrix = new float[16];
    float[] translationMatrix = new float[16];
    float[] finalTransform = new float[16];

    Matrix.setIdentityM(scaleMatrix, 0);
    Matrix.scaleM(scaleMatrix, 0, finalScale, finalScale, finalScale);

    Matrix.setIdentityM(translationMatrix, 0);
    // Use the new targetYToCenter for the translation
    Matrix.translateM(translationMatrix, 0, -center[0], -targetYToCenter, -adjustedCenterZ);

    // 先平移后缩放: final = scale * translation
    Matrix.multiplyMM(finalTransform, 0, scaleMatrix, 0, translationMatrix, 0);

    Log.d(TAG, "fitIntoUnitCubeInternal: Center=" + Arrays.toString(center) +
            ", HalfExtent=" + Arrays.toString(halfExtent) +
            ", MaxExtent=" + maxExtent +
            ", FinalScale=" + finalScale +
            ", AdjustedCenterZ=" + adjustedCenterZ +
            ", TargetYToCenter=" + targetYToCenter +
            ", VerticalOffsetInAABBSpace=" + verticalOffsetInAABBSpace);
    return finalTransform;
  }

  /**
   * 异步调整模型视口，使指定实体或整体适配视图。
   *
   * @param entityName  目标实体名，null 表示整体
   * @param scaleFactor 附加缩放因子
   * @return 操作完成时完成的 CompletableFuture
   */
  @NonNull
  public CompletableFuture<Void> updateViewPortAsync(@Nullable String entityName, float scaleFactor) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    if (mIsCleanedUp.get()) {
      future.completeExceptionally(new IllegalStateException("Renderer is cleaned up."));
      return future;
    }
    if (!mIsInitialized.get()) {
      future.completeExceptionally(new IllegalStateException("Renderer not initialized."));
      return future;
    }
    if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
      future.completeExceptionally(new IllegalStateException("Render executor not available."));
      return future;
    }
    mRenderExecutor.submit(() -> {
      try {
        updateViewPortInternal(entityName, scaleFactor);
        future.complete(null);
      } catch (Exception e) {
        Log.e(TAG, "Exception during updateViewPortInternal execution.", e);
        future.completeExceptionally(e);
      }
    });
    return future;
  }

  /**
   * 内部实现：调整当前资产根节点变换，使目标实体或整体适配视图。
   * 必须在渲染线程调用。
   *
   * @param entityName  目标实体名，null 表示整体
   * @param scaleFactor 附加缩放因子
   */
  private void updateViewPortInternal(@Nullable String entityName, float scaleFactor) {
    final float DEFAULT_VIEWPORT_Z_OFFSET = 4.0f;
    if (mEngine == null || !mEngine.isValid()) {
      Log.e(TAG, "updateViewPortInternal: Engine is not valid.");
      return;
    }
    if (mCurrentAsset == null) {
      Log.w(TAG, "updateViewPortInternal: No asset loaded.");
      return;
    }

    TransformManager tcm = mEngine.getTransformManager();
    com.google.android.filament.RenderableManager rm = mEngine.getRenderableManager();
    int rootEntity = mCurrentAsset.getRoot();

    if (!tcm.hasComponent(rootEntity)) {
      Log.w(TAG, "updateViewPortInternal: Asset root entity (" + rootEntity + ") has no transform component.");
      return;
    }
    int rootInstance = tcm.getInstance(rootEntity);

    // --- 修正版实现 ---
    Box targetAabb = new Box();
    boolean specificAabbFound = false;

    if (entityName != null) {
      int targetEntityId = findEntityByNameInternal(entityName);
      if (targetEntityId != Entity.NULL) {
        if (rm.hasComponent(targetEntityId)) {
          int renderableInstance = rm.getInstance(targetEntityId);
          rm.getAxisAlignedBoundingBox(renderableInstance, targetAabb);
          specificAabbFound = true;
          Log.i(TAG, "updateViewPortInternal: Using AABB of specific entity '" + entityName + "' (ID: " + targetEntityId + ").");
        } else {
          Log.w(TAG, "updateViewPortInternal: Entity '" + entityName + "' found, but has no renderable component. Falling back to asset AABB.");
        }
      } else {
        Log.w(TAG, "updateViewPortInternal: Entity '" + entityName + "' not found. Falling back to asset AABB.");
      }
    }

    if (!specificAabbFound) {
      Box assetBox = mCurrentAsset.getBoundingBox();
      if (assetBox != null) {
        targetAabb.setCenter(assetBox.getCenter()[0], assetBox.getCenter()[1], assetBox.getCenter()[2]);
        targetAabb.setHalfExtent(assetBox.getHalfExtent()[0], assetBox.getHalfExtent()[1], assetBox.getHalfExtent()[2]);
      } else {
        Log.e(TAG, "updateViewPortInternal: Could not get bounding box from the asset.");
        return;
      }
      Log.i(TAG, "updateViewPortInternal: Using AABB of the entire asset.");
    }

    float[] halfExtent = targetAabb.getHalfExtent();
    final float epsilon = 1e-6f;
    if (halfExtent[0] <= epsilon && halfExtent[1] <= epsilon && halfExtent[2] <= epsilon) {
      Log.e(TAG, "updateViewPortInternal: Invalid or zero-sized target AABB obtained (HalfExtent: " + Arrays.toString(halfExtent) + "). Cannot calculate transform.");
      return;
    }

    float[] transformMatrix = fitIntoUnitCubeInternal(targetAabb, DEFAULT_VIEWPORT_Z_OFFSET, scaleFactor);
    tcm.setTransform(rootInstance, transformMatrix);
    Log.i(TAG, "updateViewPortInternal: Applied new transform to asset root (" + rootEntity + ").");
  }


  public void cleanup() {
    // ... (Cleanup logic remains the same, including calling shutdownExecutorService) ...
    if (mIsCleanedUp.compareAndSet(false, true)) {
      Log.i(TAG, "cleanup() called. Initiating shutdown...");
      cleanupInternal();
      Log.i(TAG, "Cleanup process finished.");
    } else {
      Log.w(TAG, "cleanup() called, but already cleaned up or cleanup in progress.");
    }
  }

  /**
   * 对指定名称的实体进行绝对旋转（基于初始变换）。
   *
   * @param entityName 实体名称（glTF中定义的节点名）
   * @param x          X轴旋转角度（弧度）
   * @param y          Y轴旋转角度（弧度）
   * @param z          Z轴旋转角度（弧度）
   * @return CompletableFuture<Boolean>，表示操作是否成功
   */
  @NonNull
  public CompletableFuture<Boolean> rotate(@NonNull String entityName, float x, float y, float z) {
    CompletableFuture<Boolean> resultFuture = new CompletableFuture<>();

    if (mIsCleanedUp.get()) {
      resultFuture.completeExceptionally(new IllegalStateException("Renderer已清理，无法旋转实体。"));
      return resultFuture;
    }
    if (!mIsInitialized.get()) {
      resultFuture.completeExceptionally(new IllegalStateException("Renderer未初始化，无法旋转实体。"));
      return resultFuture;
    }
    if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
      resultFuture.completeExceptionally(new IllegalStateException("渲染线程不可用。"));
      return resultFuture;
    }

    mRenderExecutor.submit(() -> {
      try {
        // 1. 查找初始变换
        float[] initialTransform = mEntityInitialTransforms.get(entityName);
        if (initialTransform == null) {
          Log.e(TAG, "旋转失败：未缓存实体 '" + entityName + "' 的初始变换。");
          resultFuture.complete(false);
          return;
        }

        // 2. 查找实体ID
        int entityId = findEntityByNameInternal(entityName);
        if (entityId == 0) {
          Log.e(TAG, "旋转失败：缓存中存在但未找到实体 '" + entityName + "' 的ID。");
          resultFuture.complete(false);
          return;
        }

        TransformManager tm = mEngine.getTransformManager();
        if (!tm.hasComponent(entityId)) {
          Log.e(TAG, "旋转失败：实体 '" + entityName + "' 没有变换组件。");
          resultFuture.complete(false);
          return;
        }
        int instance = tm.getInstance(entityId);

        // 3. 构造旋转矩阵
        float[] rotationX = new float[16];
        float[] rotationY = new float[16];
        float[] rotationZ = new float[16];
        float[] temp = new float[16];
        float[] desiredRotation = new float[16];

        Matrix.setIdentityM(rotationX, 0);
        Matrix.setRotateM(rotationX, 0, (float) Math.toDegrees(x), 1, 0, 0);
        Matrix.setIdentityM(rotationY, 0);
        Matrix.setRotateM(rotationY, 0, (float) Math.toDegrees(y), 0, 1, 0);
        Matrix.setIdentityM(rotationZ, 0);
        Matrix.setRotateM(rotationZ, 0, (float) Math.toDegrees(z), 0, 0, 1);

        // desiredRotation = rotationZ * rotationY * rotationX
        Matrix.multiplyMM(temp, 0, rotationY, 0, rotationX, 0);
        Matrix.multiplyMM(desiredRotation, 0, rotationZ, 0, temp, 0);

        // 4. newLocalTransform = initialTransform * desiredRotation
        float[] newLocalTransform = new float[16];
        Matrix.multiplyMM(newLocalTransform, 0, initialTransform, 0, desiredRotation, 0);

        // 5. 应用变换
        tm.setTransform(instance, newLocalTransform);

        Log.i(TAG, "已为实体 '" + entityName + "' 设置绝对旋转 (x=" + x + ", y=" + y + ", z=" + z + ") 弧度。");
        resultFuture.complete(true);
      } catch (Exception e) {
        Log.e(TAG, "旋转实体 '" + entityName + "' 时发生异常：", e);
        resultFuture.completeExceptionally(e);
      }
    });

    return resultFuture;
  }

  private void cleanupInternal() {
    // ... (Cleanup logic remains the same, including calling shutdownExecutorService) ...
    Log.i(TAG, "Starting internal cleanup...");
    mIsInitialized.set(false);

    mMainThreadHandler.removeCallbacksAndMessages(null);

    shutdownExecutorService(); // This now submits cleanupFilamentResourcesInternal

    // Nullify references after cleanup attempt
    mRenderer = null;
    mSwapChain = null;
    mView = null;
    mScene = null;
    mCamera = null;
    mSkybox = null;
    mAssetLoader = null;
    mResourceLoader = null; // Nullify resource loader too
    mCurrentAsset = null;
    mAssetEntities = null;
    mEngine = null;

    Log.i(TAG, "Internal cleanup finished.");
  }

  private void shutdownExecutorService() {
    // ... (Executor shutdown logic remains the same, submitting cleanupFilamentResourcesInternal before shutdown) ...
    Log.i(TAG, "Initiating executor shutdown and resource cleanup...");
    ExecutorService executor = mRenderExecutor;
    mRenderExecutor = null;

    if (executor != null && !executor.isShutdown()) {
      Log.i(TAG, "Submitting final resource cleanup task to render thread...");
      Future<?> cleanupFuture = executor.submit(this::cleanupFilamentResourcesInternal);

      try {
        cleanupFuture.get(SHUTDOWN_TIMEOUT_SECONDS / 2, TimeUnit.SECONDS);
        Log.i(TAG, "Filament resource cleanup task completed on render thread.");
      } catch (Exception e) {
        Log.e(TAG, "Exception/Timeout waiting for Filament resource cleanup task: ", e);
      }

      Log.i(TAG, "Shutting down render executor...");
      executor.shutdown();
      try {
        if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          Log.e(TAG, "Render executor did not terminate gracefully after shutdown(). Forcing shutdown...");
          executor.shutdownNow();
          if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
            Log.e(TAG, "Render executor did not terminate even after shutdownNow().");
          } else {
            Log.i(TAG, "Render executor terminated after shutdownNow().");
          }
        } else {
          Log.i(TAG, "Render executor terminated gracefully.");
        }
      } catch (InterruptedException ie) {
        Log.e(TAG, "Interrupted during executor shutdown.", ie);
        executor.shutdownNow();
        Thread.currentThread().interrupt();
      }
    } else {
      Log.w(TAG, "Render executor was null or already shutdown. Performing direct cleanup...");
      cleanupFilamentResourcesInternal();
    }
  }

  private void cleanupFilamentResourcesInternal() {
    Log.i(TAG, "Executing cleanupFilamentResourcesInternal on thread: " + Thread.currentThread().getName());

    // ***** 新增：清空 Morph Target Map *****
    if (mMorphTargetInfoMap != null) {
      mMorphTargetInfoMap.clear();
      Log.d(TAG, "Cleared morph target info map during cleanup.");
    }
    // ***************************************

    // ***** 新增：清空实体变换 Map *****
    if (mEntityInitialTransforms != null) {
      mEntityInitialTransforms.clear();
      Log.d(TAG, "Cleared entity transform map during cleanup.");
      mEntityInitialTransforms = null; // 显式设为 null
    }
    // ***********************************

    // Destroy asset first
    if (mCurrentAsset != null && mAssetLoader != null) {
      try {
        Log.i(TAG, "Destroying FilamentAsset...");
        mAssetLoader.destroyAsset(mCurrentAsset); // Use the loader to destroy the asset
        Log.i(TAG, "FilamentAsset destroyed.");
      } catch (Exception e) {
        Log.e(TAG, "Exception destroying FilamentAsset: ", e);
      }
      mCurrentAsset = null;
      mAssetEntities = null;
    }

    // Destroy ResourceLoader
    if (mResourceLoader != null) {
      try {
        Log.i(TAG, "Destroying ResourceLoader...");
        mResourceLoader.destroy();
        Log.i(TAG, "ResourceLoader destroyed.");
      } catch (Exception e) {
        Log.e(TAG, "Exception destroying ResourceLoader: ", e);
      }
      mResourceLoader = null;
    }

    // Destroy AssetLoader
    if (mAssetLoader != null) {
      try {
        Log.i(TAG, "Destroying AssetLoader...");
        // UbershaderProvider doesn't have a destroy method itself,
        // but AssetLoader's destroy should handle internal cleanup.
        mAssetLoader.destroy();
        Log.i(TAG, "AssetLoader destroyed.");
      } catch (Exception e) {
        Log.e(TAG, "Exception destroying AssetLoader: ", e);
      }
      mAssetLoader = null;
    }

    if (mEngine != null && mEngine.isValid()) {
      Log.i(TAG, "Destroying core Filament resources...");
      try {
        // Remove entities first
        if (mScene != null && mEngine.isValidScene(mScene)) {
          if (mAssetEntities != null && mAssetEntities.length > 0) {
            mScene.removeEntities(mAssetEntities);
            Log.d(TAG, "Removed asset entities from scene during cleanup.");
          }
        }

        if (mLightEntity != 0 && EntityManager.get().isAlive(mLightEntity)) {
          LightManager lm = mEngine.getLightManager();
          if (lm.hasComponent(mLightEntity)) {
            lm.destroy(mLightEntity);
            Log.d(TAG, "Destroyed light component for entity: " + mLightEntity);
          }
          EntityManager.get().destroy(mLightEntity);
          Log.d(TAG, "Destroyed light entity: " + mLightEntity);
          mLightEntity = 0;
        }

        if (mSkybox != null && mEngine.isValidSkybox(mSkybox)) mEngine.destroySkybox(mSkybox);
        if (mCamera != null && mCamera.getEntity() != 0) {
          int entity = mCamera.getEntity();
          // Check if entity is still valid before destroying component
          if (EntityManager.get().isAlive(entity)) {
            mEngine.destroyCameraComponent(entity);
          }
        }
        if (mCameraEntity != 0 && EntityManager.get().isAlive(mCameraEntity)) {
          EntityManager.get().destroy(mCameraEntity);
        }
        if (mView != null && mEngine.isValidView(mView)) mEngine.destroyView(mView);
        if (mScene != null && mEngine.isValidScene(mScene)) mEngine.destroyScene(mScene);
        if (mRenderer != null && mEngine.isValidRenderer(mRenderer))
          mEngine.destroyRenderer(mRenderer);
        if (mSwapChain != null && mEngine.isValidSwapChain(mSwapChain))
          mEngine.destroySwapChain(mSwapChain);

        Log.i(TAG, "Destroying Filament Engine...");
        mEngine.destroy();
        Log.i(TAG, "Filament Engine destroyed.");

      } catch (Exception e) {
        Log.e(TAG, "Exception during Filament resource destruction: ", e);
      }
    } else {
      Log.w(TAG, "Filament Engine was null or invalid during resource cleanup.");
    }

    // Nullify members after attempted destruction
    mSkybox = null;
    mCamera = null;
    mCameraEntity = 0;
    mView = null;
    mScene = null;
    mRenderer = null;
    mSwapChain = null;
    mLightEntity = 0; // 确保即使引擎无效也重置
    mEngine = null; // Nullify engine last
  }


  @Override
  protected void finalize() throws Throwable {
    // ... (Finalize method remains the same, emphasizing explicit cleanup) ...
    try {
      if (!mIsCleanedUp.get()) {
        Log.w(TAG, "HeadlessRenderer finalize() called without explicit cleanup! This is problematic.");
      }
    } finally {
      super.finalize();
    }
  }

  /**
   * 扫描当前 asset 的 morph target 并填充 mMorphTargetInfoMap。必须在渲染线程调用。
   */
  private void prepareMorphTargetInfoInternal() {
    if (mEngine == null || !mEngine.isValid() || mCurrentAsset == null || mAssetEntities == null) {
      Log.w(TAG, "prepareMorphTargetInfoInternal: Invalid state (engine, asset, or entities null/invalid).");
      return;
    }

    Log.i(TAG, "--- Preparing Morph Target Information ---");
    RenderableManager rm = mEngine.getRenderableManager();
    int totalMorphTargetsFound = 0;

    for (int entityId : mAssetEntities) {
      if (!rm.hasComponent(entityId)) {
        continue;
      }
      var instance = rm.getInstance(entityId);
      int numMorphTargets = rm.getMorphTargetCount(instance);

      if (numMorphTargets > 0) {
        Log.d(TAG, "Entity ID " + entityId + " has " + numMorphTargets + " morph targets.");
        for (int j = 0; j < numMorphTargets; j++) {
          String[] morphNames = mCurrentAsset.getMorphTargetNames(entityId);
          String morphName = null;
          if (morphNames != null && j < morphNames.length) {
            morphName = morphNames[j];
          } else if (morphNames != null) {
            Log.w(TAG, "  Error: Morph target index " + j + " is out of bounds for entity " + entityId + " (name array size: " + morphNames.length + ")");
          }
          if (morphName != null && !morphName.isEmpty()) {
            MorphInfo info = new MorphInfo(entityId, morphName, j, numMorphTargets);
            mMorphTargetInfoMap.computeIfAbsent(morphName, k -> new ArrayList<>()).add(info);
            Log.d(TAG, "  Added morph info: Name='" + morphName + "', Entity=" + entityId + ", Index=" + j);
            totalMorphTargetsFound++;
          } else if (morphName == null) {
            // 已在上方打印越界错误日志
          } else {
            Log.w(TAG, "  Warning: Unnamed morph target at index " + j + " on entity " + entityId);
          }
        }
      }
    }

    Log.i(TAG, "--- Finished Preparing Morph Targets ---");
    Log.i(TAG, "Found " + totalMorphTargetsFound + " named morph targets across " + mMorphTargetInfoMap.size() + " unique names.");
  }

  /**
   * 设置 morph target 权重。必须在渲染线程调用。
   *
   * @param weights morph target 名称到权重的映射
   */
  private void setMorphWeightsInternal(@NonNull Map<String, Float> weights) {
    if (mEngine == null || !mEngine.isValid() || mCurrentAsset == null) {
      Log.e(TAG, "setMorphWeightsInternal: Invalid state (engine or asset null/invalid).");
      return;
    }
    if (mMorphTargetInfoMap.isEmpty()) {
      return;
    }

    RenderableManager rm = mEngine.getRenderableManager();
    Map<Integer, float[]> entityWeightsMap = new HashMap<>();

    for (Map.Entry<String, List<MorphInfo>> entry : mMorphTargetInfoMap.entrySet()) {
      String morphName = entry.getKey();
      List<MorphInfo> infos = entry.getValue();
      float desiredWeight = weights.getOrDefault(morphName, 0.0f);

      for (MorphInfo info : infos) {
        float[] weightsArray = entityWeightsMap.computeIfAbsent(info.entityId, k -> new float[info.totalMorphTargetsForEntity]);
        if (info.morphTargetIndex < weightsArray.length) {
          weightsArray[info.morphTargetIndex] = desiredWeight;
        } else {
          Log.e(TAG, "  Error: Morph index " + info.morphTargetIndex + " out of bounds for entity " + info.entityId + " (size " + weightsArray.length + ")");
        }
      }
    }

    for (String inputName : weights.keySet()) {
      if (!mMorphTargetInfoMap.containsKey(inputName)) {
        if (!inputName.equals("_neutral")) {
          Log.w(TAG, "Input weight name '" + inputName + "' not found in prepared morph targets.");
        }
      }
    }

    if (!entityWeightsMap.isEmpty()) {
      Log.d(TAG, "Applying morph weights to " + entityWeightsMap.size() + " entities.");
      for (Map.Entry<Integer, float[]> entityEntry : entityWeightsMap.entrySet()) {
        int entityId = entityEntry.getKey();
        float[] finalWeights = entityEntry.getValue();

        if (rm.hasComponent(entityId)) {
          var instance = rm.getInstance(entityId);
          rm.setMorphWeights(instance, finalWeights, 0);
        } else {
          Log.w(TAG, "Entity " + entityId + " no longer has renderable component when applying weights?");
        }
      }
    } else {
      Log.d(TAG, "No entities needed morph weight updates.");
    }
  }

}
