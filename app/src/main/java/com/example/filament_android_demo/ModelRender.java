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
import com.google.android.filament.RenderableManager;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.android.filament.gltfio.AssetLoader;
import com.google.android.filament.gltfio.FilamentAsset;
import com.google.android.filament.gltfio.MaterialProvider; // Use gltfio version
import com.google.android.filament.gltfio.ResourceLoader;
import com.google.android.filament.gltfio.UbershaderProvider;

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


public class ModelRender {
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
  private final Map<String, List<MorphInfo>> mMorphTargetInfoMap = new ConcurrentHashMap<>();

  // 可选：存储 ApplicationContext 以便后续使用
  private volatile Context mApplicationContext;

  private static final String TAG = "ModelRender";
  // Positive value shifts model visually downwards. Tune as needed.
  public static final String headMeshName = "Wolf3D_Head";
  public static final String headName = "Head";

  // --- Configuration ---
  public static final String MODEL_PATH = "man1.glb";
  private static final int IMAGE_WIDTH = 600;
  private static final int IMAGE_HEIGHT = 800;

  private static final float VERTICAL_CENTERING_ADJUSTMENT_FACTOR = 0.2f;
  private static final double FOV = 60.0;
  public static final float SCALE_FACTOR = 5.0f;

  private static final long RENDER_TIMEOUT_SECONDS = 15;
  private static final long SHUTDOWN_TIMEOUT_SECONDS = 20;

  // --- 只显示头部相关实体的名称列表 ---
  private static final List<String> ENTITY_NAMES_TO_KEEP_VISIBLE = Arrays.asList(
    "Wolf3D_Head",
    "Wolf3D_Teeth",
    "EyeLeft",
    "EyeRight",
    "Wolf3D_Hair"
//      "Wolf3D_Glasses"
  );
  // ----------------

  // --- Filament Core Objects ---
  private volatile Engine mEngine = null;
  private volatile SwapChain mSwapChain = null;
  private volatile Renderer mRenderer = null;
  private volatile Scene mScene = null;
  private volatile View mView = null;
  private volatile Camera mCamera = null;
  private volatile int mCameraEntity = 0;
  private volatile Skybox mSkybox = null; // Though unused, kept for consistency if re-enabled
  private volatile int mLightEntity = 0;

  // --- gltfio Objects ---
  private volatile AssetLoader mAssetLoader = null;
  private volatile ResourceLoader mResourceLoader = null;
  private volatile FilamentAsset mCurrentAsset = null;
  private volatile int[] mAssetEntities = null;

  private volatile java.util.Map<String, float[]> mEntityInitialTransforms = new java.util.concurrent.ConcurrentHashMap<>();

  private ExecutorService mRenderExecutor = null;
  private final Handler mMainThreadHandler = new Handler(Looper.getMainLooper());

  private final AtomicBoolean mIsInitialized = new AtomicBoolean(false);
  private final AtomicBoolean mIsCleanedUp = new AtomicBoolean(false);

  // 降级为包级私有
  boolean isRenderExecutorAvailable() {
    return mRenderExecutor != null && !mRenderExecutor.isShutdown();
  }

  // --- initFilamentCore refactored parts START ---
  private boolean initializeFilamentAndUtilsInternal() {
    Log.d(TAG, "initFilamentCore: Calling Filament.init()");
    Filament.init();
    Log.d(TAG, "initFilamentCore: Filament.init() DONE.");

    Log.d(TAG, "initFilamentCore: Calling Utils.INSTANCE.init()");
    Utils.INSTANCE.init();
    Log.d(TAG, "initFilamentCore: Utils.INSTANCE.init() DONE.");
    return true;
  }

  private boolean createEngineAndLoadersInternal() {
    Log.d(TAG, "initFilamentCore: Calling Engine.create()");
    mEngine = Engine.create();
    if (mEngine == null) {
      Log.e(TAG, "initFilamentCore: Failed to create Filament Engine.");
      return false;
    }
    Log.i(TAG, "initFilamentCore: Filament engine CREATED (Backend: " + mEngine.getBackend() + ")");

    MaterialProvider materialProvider = new UbershaderProvider(mEngine);
    mAssetLoader = new AssetLoader(mEngine, materialProvider, EntityManager.get());
    mResourceLoader = new ResourceLoader(mEngine, true /*normalizeSkinningWeights*/);
    return true;
  }

  private boolean createSwapChainAndRendererInternal() {
    mSwapChain = mEngine.createSwapChain(IMAGE_WIDTH, IMAGE_HEIGHT, SwapChainFlags.CONFIG_READABLE);
    if (mSwapChain == null) {
      Log.e(TAG, "Failed to create headless SwapChain.");
      // Minimal cleanup here, full cleanup in caller if this fails
      if (mAssetLoader != null) mAssetLoader.destroy();
      if (mResourceLoader != null) mResourceLoader.destroy();
      if (mEngine != null) mEngine.destroy();
      mEngine = null;
      return false;
    }
    Log.i(TAG, "Headless SwapChain created.");
    mRenderer = mEngine.createRenderer();
    return true;
  }

  private void createSceneViewCameraInternal() {
    mScene = mEngine.createScene();
    mView = mEngine.createView();
    mCameraEntity = EntityManager.get().create();
    mCamera = mEngine.createCamera(mCameraEntity);

    mView.setScene(mScene);
    mView.setCamera(mCamera);
    mView.setViewport(new Viewport(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT));
    mView.setBlendMode(View.BlendMode.TRANSLUCENT); // For transparent background
  }

  private void setupDefaultCameraProjectionInternal() {
    mCamera.setProjection(FOV, (double) IMAGE_WIDTH / IMAGE_HEIGHT, 0.1, 1000.0, Camera.Fov.VERTICAL);
    mCamera.lookAt(0.0, 1.0, 5.0, // Eye position
      0.0, 0.0, 0.0, // Target position
      0.0, 1.0, 0.0); // Up vector
  }

  private void setupDirectionalLightInternal() {
    try {
      mLightEntity = EntityManager.get().create();
      final float lightIntensity = 100_000.0f;
      final float[] lightColor = {0.98f, 0.92f, 0.89f};
      final float[] lightDirection = {0.5f, -1.0f, -0.8f};

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
      // Light creation failure is not critical for core init, but log it.
    }
  }
  // --- initFilamentCore refactored parts END ---

  private boolean initFilamentCore() {
    Log.i(TAG, "initFilamentCore: START");
    try {
      if (!initializeFilamentAndUtilsInternal()) return false;
      if (!createEngineAndLoadersInternal()) return false;
      if (!createSwapChainAndRendererInternal()) return false; // Handles its own specific cleanup on failure

      createSceneViewCameraInternal();
      setupDefaultCameraProjectionInternal();
      // Skybox intentionally omitted as per original code's commented out section
      setupDirectionalLightInternal();

    } catch (Throwable t) {
      Log.e(TAG, "initFilamentCore: THROWABLE during core initialization.", t);
      // Perform more general cleanup if an unexpected throwable occurs
      cleanupFilamentResourcesInternal(); // Ensure engine and loaders are cleaned if partially created
      return false;
    }
    Log.i(TAG, "Filament core resources initialized successfully on render thread.");
    return true;
  }

  // --- init() refactored parts START ---
  private void performInitializationOnRenderThread(@NonNull Context context, @NonNull CompletableFuture<Void> initFuture) {
    Log.i(TAG, "Render thread: Task STARTED.");
    try {
      Log.i(TAG, "Render thread: Calling initFilamentCore().");
      if (!initFilamentCore()) {
        Log.e(TAG, "Render thread: initFilamentCore() FAILED.");
        initFuture.completeExceptionally(new RuntimeException("Filament core initialization failed."));
        return;
      }
      Log.i(TAG, "Render thread: initFilamentCore() SUCCEEDED.");

      Log.i(TAG, "Render thread: Calling loadModelAndSetupViewport().");
      loadModelAndSetupViewport(context, MODEL_PATH)
        .thenRun(() -> {
          mIsInitialized.set(true);
          Log.i(TAG, "Render thread: Initialization, initial model load, and viewport setup SUCCESSFUL. Completing future.");
          initFuture.complete(null);
        })
        .exceptionally(ex -> {
          Log.e(TAG, "Render thread: EXCEPTION in loadModel/updateViewPort chain.", ex);
          cleanupFilamentResourcesInternal(); // Ensure cleanup on failure path
          initFuture.completeExceptionally(ex);
          return null;
        });
    } catch (Throwable t) {
      Log.e(TAG, "Render thread: Uncaught THROWABLE during initialization task.", t);
      cleanupFilamentResourcesInternal(); // Ensure cleanup on unexpected failure
      initFuture.completeExceptionally(t);
    }
    Log.i(TAG, "Render thread: Task SUBMITTED part finished (async operations may still be running).");
  }

  private CompletableFuture<Void> loadModelAndSetupViewport(@NonNull Context context, @NonNull String modelPath) {
    return loadModel(context, modelPath)
      .thenCompose(modelLoaded -> {
        if (modelLoaded) {
          Log.i(TAG, "Render thread: loadModel() SUCCEEDED. Calling updateViewPortAsync().");
          return updateViewPortAsync(headMeshName, SCALE_FACTOR);
        } else {
          Log.e(TAG, "Render thread: loadModel() FAILED.");
          CompletableFuture<Void> fail = new CompletableFuture<>();
          fail.completeExceptionally(new RuntimeException("Initial model loading failed."));
          return fail;
        }
      });
  }
  // --- init() refactored parts END ---

  @NonNull
  public CompletableFuture<Void> init(@NonNull Context context) {
    CompletableFuture<Void> initFuture = new CompletableFuture<>();
    Log.i(TAG, "init() called. mIsInitialized: " + mIsInitialized.get() + ", mIsCleanedUp: " + mIsCleanedUp.get());

    if (mIsInitialized.get()) {
      Log.w(TAG, "Already initialized.");
      initFuture.complete(null);
      return initFuture;
    }
    if (mIsCleanedUp.get()) {
      Log.e(TAG, "Cannot initialize after cleanup.");
      initFuture.completeExceptionally(new IllegalStateException("Cannot initialize after cleanup."));
      return initFuture;
    }
    mApplicationContext = context.getApplicationContext();
    Log.i(TAG, "Initializing ModelRender...");

    try {
      if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
        mRenderExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "FilamentRenderThread"));
        Log.i(TAG, "Render executor CREATED.");
      } else {
        Log.i(TAG, "Render executor ALREADY EXISTS.");
      }
      mRenderExecutor.submit(() -> performInitializationOnRenderThread(context, initFuture));
    } catch (Exception e) {
      Log.e(TAG, "Exception during init (e.g., thread submission).", e);
      cleanupFilamentResourcesInternal(); // Attempt cleanup if submission fails
      initFuture.completeExceptionally(e);
    }
    Log.i(TAG, "init() method returning future.");
    return initFuture;
  }

  // --- applyLandmarkResult refactored parts START ---
  private Map<String, Float> extractBlendshapesFromResult(@Nullable FaceLandmarkerResult result) {
    Map<String, Float> blendshapeMap = new HashMap<>();
    if (result == null) return blendshapeMap;

    java.util.Optional<java.util.List<java.util.List<Category>>> blendshapesOptional = result.faceBlendshapes();
    if (blendshapesOptional.isPresent() && !blendshapesOptional.get().isEmpty() && !blendshapesOptional.get().get(0).isEmpty()) {
      List<Category> blendshapes = blendshapesOptional.get().get(0);
      for (Category blendshape : blendshapes) {
        blendshapeMap.put(blendshape.categoryName(), blendshape.score());
      }
    } else {
      Log.i(TAG, "extractBlendshapesFromResult: No blendshapes found in the result.");
    }
    return blendshapeMap;
  }

  @Nullable
  private float[] extractFaceTransformMatrixFromResult(@Nullable FaceLandmarkerResult result) {
    if (result == null) return null;

    java.util.Optional<java.util.List<float[]>> matrixesOptional = result.facialTransformationMatrixes();
    if (matrixesOptional.isPresent() && !matrixesOptional.get().isEmpty()) {
      float[] faceTransformMatrix = matrixesOptional.get().get(0);
      if (faceTransformMatrix.length != 16) {
        Log.e(TAG, "extractFaceTransformMatrixFromResult: Facial transformation matrix has incorrect size: " + faceTransformMatrix.length);
        return null;
      }
      return faceTransformMatrix;
    } else {
      Log.i(TAG, "extractFaceTransformMatrixFromResult: No facial transformation matrix found in the result.");
      return null;
    }
  }

  private boolean applyFacialRotationInternal(@NonNull String targetEntityName, @NonNull float[] faceTransformMatrix) {
    int headEntityId = findEntityByNameInternal(targetEntityName);
    if (headEntityId == Entity.NULL) {
      Log.e(TAG, "applyFacialRotationInternal: Entity '" + targetEntityName + "' not found.");
      return false;
    }

    TransformManager tm = mEngine.getTransformManager();
    if (!tm.hasComponent(headEntityId)) {
      Log.e(TAG, "applyFacialRotationInternal: Entity '" + targetEntityName + "' has no Transform component.");
      return false;
    }

    int headInstance = tm.getInstance(headEntityId);
    float[] initialHeadTransform = mEntityInitialTransforms.get(targetEntityName);
    if (initialHeadTransform == null) {
      Log.e(TAG, "applyFacialRotationInternal: Initial transform for '" + targetEntityName + "' not found.");
      return false;
    }

    // Create a 4x4 matrix containing only the rotation from MediaPipe's matrix
    float[] rotationFromMediaPipe = new float[16];
    Matrix.setIdentityM(rotationFromMediaPipe, 0);
    // Copy 3x3 rotation part (faceTransformMatrix is column-major)
    rotationFromMediaPipe[0] = faceTransformMatrix[0];
    rotationFromMediaPipe[1] = faceTransformMatrix[1];
    rotationFromMediaPipe[2] = faceTransformMatrix[2];
    rotationFromMediaPipe[4] = faceTransformMatrix[4];
    rotationFromMediaPipe[5] = faceTransformMatrix[5];
    rotationFromMediaPipe[6] = faceTransformMatrix[6];
    rotationFromMediaPipe[8] = faceTransformMatrix[8];
    rotationFromMediaPipe[9] = faceTransformMatrix[9];
    rotationFromMediaPipe[10] = faceTransformMatrix[10];

    // New local transform: M_newLocal = M_initialLocal * R_mediaPipe
    float[] newHeadLocalTransform = new float[16];
    Matrix.multiplyMM(newHeadLocalTransform, 0, initialHeadTransform, 0, rotationFromMediaPipe, 0);

    tm.setTransform(headInstance, newHeadLocalTransform);
    return true;
  }

  private void performApplyLandmarkResultOnRenderThread(@NonNull Map<String, Float> blendshapeMap, @Nullable float[] faceTransformMatrix, @NonNull CompletableFuture<Void> future) {
    try {
      // 1. Apply Blendshapes
      if (!blendshapeMap.isEmpty()) {
        setMorphWeightsInternal(blendshapeMap);
      }

      // 2. Apply Rotation (if matrix exists)
      boolean rotationApplied = false;
      if (faceTransformMatrix != null) {
        rotationApplied = applyFacialRotationInternal(headName, faceTransformMatrix);
        if (!rotationApplied) {
          Log.w(TAG, "Facial rotation could not be applied.");
        }
      }

      // 3. Update Bone Matrices (if rotation was applied)
      if (rotationApplied) {
        updateBoneMatricesInternal();
      }
      future.complete(null);
    } catch (Exception e) {
      Log.e(TAG, "Exception during applyLandmarkResult execution on render thread.", e);
      future.completeExceptionally(e);
    }
  }
  // --- applyLandmarkResult refactored parts END ---

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
      Log.i(TAG, "applyLandmarkResult: FaceLandmarkerResult is null, applying no changes.");
      future.complete(null); // Complete normally
      return future;
    }

    final Map<String, Float> finalBlendshapeMap = extractBlendshapesFromResult(result);
    final float[] finalFaceTransformMatrix = extractFaceTransformMatrixFromResult(result);

    mRenderExecutor.submit(() -> performApplyLandmarkResultOnRenderThread(finalBlendshapeMap, finalFaceTransformMatrix, future));
    return future;
  }

  @NonNull
  public CompletableFuture<Bitmap> applyLandmarkResultAndRender(@Nullable FaceLandmarkerResult result) {
    if (mIsCleanedUp.get()) {
      CompletableFuture<Bitmap> failedFuture = new CompletableFuture<>();
      failedFuture.completeExceptionally(new IllegalStateException("Renderer is cleaned up."));
      return failedFuture;
    }
    if (!mIsInitialized.get()) {
      CompletableFuture<Bitmap> failedFuture = new CompletableFuture<>();
      failedFuture.completeExceptionally(new IllegalStateException("Renderer not initialized."));
      return failedFuture;
    }
    if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
      CompletableFuture<Bitmap> failedFuture = new CompletableFuture<>();
      failedFuture.completeExceptionally(new IllegalStateException("Render executor not available."));
      return failedFuture;
    }
    return updateViewPortAsync(headMeshName, SCALE_FACTOR)
      .thenComposeAsync(aVoid -> applyLandmarkResult(result), mRenderExecutor)
      .thenComposeAsync(aVoid -> render(), mRenderExecutor)
      .exceptionally(ex -> {
        Log.e(TAG, "Error in applyLandmarkResultAndRender chain", ex);
        // Instead of re-throwing, which might obscure the original exception type if not careful,
        // let the original CompletableFuture propagate its exception.
        // If a specific new exception is needed: throw new RuntimeException("Chain failed", ex);
        CompletableFuture<Bitmap> failed = new CompletableFuture<>();
        failed.completeExceptionally(ex);
        return failed.join(); // This will rethrow the exception
      });
  }

  private void updateBoneMatricesInternal() {
    if (mEngine == null || !mEngine.isValid() || mCurrentAsset == null) {
      Log.w(TAG, "updateBoneMatricesInternal: Invalid state.");
      return;
    }
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

  // rotateInternal remains as is, as it's already fairly focused for an internal helper.
  private boolean rotateInternal(@NonNull String entityName, float x, float y, float z) {
    if (mEngine == null || !mEngine.isValid()) {
      Log.e(TAG, "rotateInternal: Engine is not valid.");
      return false;
    }

    float[] initialTransform = mEntityInitialTransforms.get(entityName);
    if (initialTransform == null) {
      Log.e(TAG, "rotateInternal failed: Initial transform for entity '" + entityName + "' not cached.");
      return false;
    }

    int entityId = findEntityByNameInternal(entityName);
    if (entityId == Entity.NULL) {
      Log.e(TAG, "rotateInternal failed: Entity '" + entityName + "' not found (despite having cached transform).");
      return false;
    }

    TransformManager tm = mEngine.getTransformManager();
    if (!tm.hasComponent(entityId)) {
      Log.e(TAG, "rotateInternal failed: Entity '" + entityName + "' has no Transform component.");
      return false;
    }
    int instance = tm.getInstance(entityId);

    float[] rotationX = new float[16];
    float[] rotationY = new float[16];
    float[] rotationZ = new float[16];
    float[] temp = new float[16];
    float[] desiredRotation = new float[16];

    Matrix.setIdentityM(rotationX, 0);
    Matrix.rotateM(rotationX, 0, (float) Math.toDegrees(x), 1, 0, 0);
    Matrix.setIdentityM(rotationY, 0);
    Matrix.rotateM(rotationY, 0, (float) Math.toDegrees(y), 0, 1, 0);
    Matrix.setIdentityM(rotationZ, 0);
    Matrix.rotateM(rotationZ, 0, (float) Math.toDegrees(z), 0, 0, 1);

    Matrix.multiplyMM(temp, 0, rotationY, 0, rotationX, 0);
    Matrix.multiplyMM(desiredRotation, 0, rotationZ, 0, temp, 0);

    float[] newLocalTransform = new float[16];
    Matrix.multiplyMM(newLocalTransform, 0, initialTransform, 0, desiredRotation, 0);

    tm.setTransform(instance, newLocalTransform);
    Log.d(TAG, "Applied absolute rotation to entity '" + entityName + "' (x=" + x + ", y=" + y + ", z=" + z + ").");
    return true;
  }

  // --- loadModel refactored parts START ---
  private void removePreviousAssetInternal() {
    if (mCurrentAsset != null) {
      if (mAssetEntities != null) {
        Log.d(TAG, "loadModel render thread: Removing previous model entities from scene.");
        mScene.removeEntities(mAssetEntities); // Should be safe even if mScene is null/invalid due to checks in removeEntities
      }
      Log.d(TAG, "loadModel render thread: Destroying previous FilamentAsset.");
      if (mAssetLoader != null) { // Check AssetLoader validity
        mAssetLoader.destroyAsset(mCurrentAsset);
      }
      mCurrentAsset = null;
      mAssetEntities = null;
    }
    mMorphTargetInfoMap.clear();
  }

  @NonNull
  private ByteBuffer readAssetToByteBufferInternal(@NonNull Context context, @NonNull String assetPath) throws IOException {
    AssetManager assetManager = context.getAssets();
    try (InputStream inputStream = assetManager.open(assetPath);
         BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
         ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[1024];
      int len;
      while ((len = bufferedInputStream.read(buffer)) != -1) {
        byteArrayOutputStream.write(buffer, 0, len);
      }
      return ByteBuffer.wrap(byteArrayOutputStream.toByteArray());
    }
  }

  @Nullable
  private FilamentAsset createAndLoadFilamentAssetInternal(@NonNull ByteBuffer byteBuffer, @NonNull String assetPath) {
    if (mAssetLoader == null || mResourceLoader == null) {
      Log.e(TAG, "loadModel render thread: AssetLoader/ResourceLoader is null. Initialization incomplete?");
      return null; // Indicates failure
    }

    FilamentAsset newAsset = mAssetLoader.createAsset(byteBuffer);
    if (newAsset == null) {
      Log.e(TAG, "loadModel render thread: Failed to load asset: " + assetPath + ". createAsset returned null.");
      return null;
    }
    Log.i(TAG, "loadModel render thread: Asset created: " + assetPath);

    mResourceLoader.loadResources(newAsset);
    newAsset.releaseSourceData(); // Important to release after loading resources
    Log.i(TAG, "loadModel render thread: Resources loaded for asset: " + assetPath);
    return newAsset;
  }

  private void addAssetToSceneAndFilterEntitiesInternal(@NonNull FilamentAsset newAsset) {
    mAssetEntities = newAsset.getEntities();
    if (mScene == null || mAssetEntities == null || mAssetEntities.length == 0) {
      Log.w(TAG, "addAssetToSceneAndFilterEntitiesInternal: Scene is null or asset has no entities.");
      return;
    }
    mScene.addEntities(mAssetEntities); // Add all first
    Log.i(TAG, "loadModel render thread: Added " + mAssetEntities.length + " entities to the scene.");

    // Filter entities for visibility
    RenderableManager rm = mEngine.getRenderableManager();
    List<Integer> entitiesToHide = new ArrayList<>();
    Log.i(TAG, "loadModel render thread: --- Filtering entities for visibility ---");
    for (int entityId : mAssetEntities) {
      String entityName = newAsset.getName(entityId);
      if (entityName != null && !entityName.isEmpty() && rm.hasComponent(entityId)) {
        if (!ENTITY_NAMES_TO_KEEP_VISIBLE.contains(entityName)) {
          entitiesToHide.add(entityId);
        }
      }
    }
    if (!entitiesToHide.isEmpty()) {
      mScene.removeEntities(entitiesToHide.stream().mapToInt(i -> i).toArray());
      Log.i(TAG, "loadModel render thread: Hid " + entitiesToHide.size() + " entities by removing them from the scene.");
      // Update mAssetEntities to only contain visible entities
      List<Integer> visibleEntitiesList = new ArrayList<>();
      for (int entityId : mAssetEntities) {
        if (!entitiesToHide.contains(entityId)) {
          visibleEntitiesList.add(entityId);
        }
      }
      mAssetEntities = visibleEntitiesList.stream().mapToInt(i -> i).toArray();

    }
  }


  private void recordInitialTransformsAndPrepareMorphTargetsInternal(@NonNull FilamentAsset asset) {
    mEntityInitialTransforms.clear();
    if (mAssetEntities == null || mAssetEntities.length == 0 || mEngine == null) {
      Log.w(TAG, "recordInitialTransformsInternal: No asset entities or engine not available.");
      return;
    }

    Log.i(TAG, "loadModel render thread: --- Processing Entities in Asset for Transform Map ---");
    TransformManager tm = mEngine.getTransformManager();
    for (int entityId : mAssetEntities) { // Iterate over potentially filtered mAssetEntities
      String entityName = asset.getName(entityId);
      if (entityName != null && !entityName.isEmpty()) {
        if (tm.hasComponent(entityId)) {
          int instance = tm.getInstance(entityId);
          float[] transformMatrix = new float[16];
          tm.getTransform(instance, transformMatrix);
          mEntityInitialTransforms.put(entityName, transformMatrix);
        } else {
          Log.w(TAG, "loadModel render thread: Entity ID: " + entityId + ", Name: '" + entityName + "' has a name but no Transform component. Skipping transform storage.");
        }
      }
    }
    Log.i(TAG, "loadModel render thread: --- Finished Processing Entities for Transform Map ---");

    // Prepare Morph Target Info (uses mCurrentAsset and mAssetEntities which are now set)
    prepareMorphTargetInfoInternal();
  }

  private void applyRootTransformToUnitCubeInternal(@NonNull FilamentAsset asset) {
    if (mEngine == null || !mEngine.isValid()) return;

    TransformManager tm = mEngine.getTransformManager();
    int rootEntity = asset.getRoot();
    if (!tm.hasComponent(rootEntity)) {
      Log.w(TAG, "loadModel render thread: Asset root entity " + rootEntity + " does not have a transform component.");
      return;
    }
    int rootInstance = tm.getInstance(rootEntity);
    Box aabb = asset.getBoundingBox(); // Use the asset's overall bounding box
    float[] center = aabb.getCenter();
    float[] halfExtent = aabb.getHalfExtent();

    float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));
    // Avoid division by zero or tiny numbers
    float scaleFactor = (maxExtent > 1e-6f) ? (1.0f / (maxExtent * 2.0f)) : 1.0f;
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
    Matrix.multiplyMM(finalTransform, 0, translationMatrix, 0, scaleMatrix, 0);

    tm.setTransform(rootInstance, finalTransform);
  }

  private void performLoadModelOnRenderThread(@NonNull Context context, @NonNull String assetPath, @NonNull CompletableFuture<Boolean> loadFuture) {
    Log.i(TAG, "loadModel render thread: Task STARTED for " + assetPath);
    boolean success = false;
    FilamentAsset newAsset = null;

    try {
      removePreviousAssetInternal();
      ByteBuffer byteBuffer = readAssetToByteBufferInternal(context, assetPath);
      newAsset = createAndLoadFilamentAssetInternal(byteBuffer, assetPath);

      if (newAsset == null) {
        loadFuture.complete(false); // createAndLoad... already logged the error
        return;
      }
      mCurrentAsset = newAsset; // Set mCurrentAsset here so other internal methods can use it

      addAssetToSceneAndFilterEntitiesInternal(newAsset); // This updates mAssetEntities
      recordInitialTransformsAndPrepareMorphTargetsInternal(newAsset); // Uses mAssetEntities
      applyRootTransformToUnitCubeInternal(newAsset);

      success = true;
    } catch (IOException e) {
      Log.e(TAG, "loadModel render thread: Failed to read asset: " + assetPath, e);
      loadFuture.completeExceptionally(e);
      return; // Exit before finally if future is already completed exceptionally
    } catch (Throwable t) {
      Log.e(TAG, "loadModel render thread: THROWABLE during model loading for " + assetPath, t);
      if (newAsset != null && mAssetLoader != null) { // Check if mAssetLoader is still valid
        try {
          mAssetLoader.destroyAsset(newAsset); // Clean up the newly created asset if something went wrong
        } catch (Exception cleanupEx) {
          Log.e(TAG, "loadModel render thread: Exception during asset cleanup: ", cleanupEx);
        }
      }
      mCurrentAsset = null; // Ensure mCurrentAsset is null on failure
      mAssetEntities = null;
      loadFuture.completeExceptionally(t);
      return; // Exit before finally
    } finally {
      if (success) {
        Log.i(TAG, "loadModel render thread: Model loading task COMPLETED SUCCESSFULLY for: " + assetPath);
        loadFuture.complete(true);
      } else if (!loadFuture.isDone()) { // Only complete if not already completed by an exception
        Log.e(TAG, "loadModel render thread: Model loading task FAILED for: " + assetPath);
        loadFuture.complete(false);
      }
      Log.i(TAG, "loadModel render thread: Task ENDED for " + assetPath);
    }
  }
  // --- loadModel refactored parts END ---

  @NonNull
  private CompletableFuture<Boolean> loadModel(@NonNull Context context, @NonNull String assetPath) {
    Log.i(TAG, "loadModel: START for asset: " + assetPath);
    CompletableFuture<Boolean> loadFuture = new CompletableFuture<>();

    if (mIsCleanedUp.get()) {
      Log.e(TAG, "loadModel: Renderer is cleaned up, aborting.");
      loadFuture.completeExceptionally(new IllegalStateException("Renderer is cleaned up."));
      return loadFuture;
    }
    if (mRenderExecutor == null || mRenderExecutor.isShutdown()) {
      Log.e(TAG, "loadModel: Render executor not available, aborting.");
      loadFuture.completeExceptionally(new IllegalStateException("Render executor not available."));
      return loadFuture;
    }

    mRenderExecutor.submit(() -> performLoadModelOnRenderThread(context, assetPath, loadFuture));

    Log.i(TAG, "loadModel: END for asset: " + assetPath + " (returning future)");
    return loadFuture;
  }


  // --- render refactored parts START ---
  private void setupFrameForRenderingInternal() {
    if (mRenderer == null) throw new IllegalStateException("Renderer is null in setupFrameForRenderingInternal");

    com.google.android.filament.Renderer.ClearOptions clearOptions = new com.google.android.filament.Renderer.ClearOptions();
    clearOptions.clearColor = new float[]{0.0f, 0.0f, 0.0f, 0.0f}; // Transparent
    clearOptions.clear = true;
    clearOptions.discard = false; // Important for readPixels
    mRenderer.setClearOptions(clearOptions);
  }

  private void performRenderOnRenderThread(@NonNull CompletableFuture<Bitmap> resultFuture) {
    if (!mIsInitialized.get() || mIsCleanedUp.get()) {
      Log.w(TAG, "Render task executing but renderer is no longer initialized or cleaned up.");
      if (!resultFuture.isDone()) {
        resultFuture.completeExceptionally(new IllegalStateException("Renderer not ready or cleaned up for render task."));
      }
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
          if (!callbackSuccess.get() && !resultFuture.isDone()) { // Check if future already completed
            resultFuture.completeExceptionally(new RuntimeException("Rendering timed out"));
          }
          frameLatch.countDown();
        }
      }
    };
    mMainThreadHandler.postDelayed(timeoutRunnable, TimeUnit.SECONDS.toMillis(RENDER_TIMEOUT_SECONDS));

    try {
      if (mRenderer == null || mSwapChain == null || mView == null || mEngine == null || !mEngine.isValid()) {
        throw new IllegalStateException("Filament resources are not valid at the start of render task.");
      }

      final int bufferSize = IMAGE_WIDTH * IMAGE_HEIGHT * 4;
      final ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(bufferSize).order(ByteOrder.nativeOrder());

      final Runnable readPixelsCallback = () -> {
        if (mIsCleanedUp.get()) { // Check if cleaned up before processing
          Log.w(TAG, "readPixelsCallback invoked but renderer cleaned up. Ignoring.");
          frameLatch.countDown();
          return;
        }
        if (timedOut.get()) {
          Log.w(TAG, "readPixelsCallback invoked after timeout. Ignoring bitmap creation.");
          frameLatch.countDown();
          return;
        }
        mMainThreadHandler.removeCallbacks(timeoutRunnable); // Crucial: remove timeout if callback runs
        try {
          Log.i(TAG, "readPixelsCallback: Processing received pixels on main thread...");
          pixelBuffer.rewind();
          Bitmap bitmap = Bitmap.createBitmap(IMAGE_WIDTH, IMAGE_HEIGHT, Bitmap.Config.ARGB_8888);
          bitmap.copyPixelsFromBuffer(pixelBuffer);
          callbackSuccess.set(true);
          if (!resultFuture.isDone()) resultFuture.complete(bitmap); // Complete future if not already done
          Log.i(TAG, "Bitmap created and future completed successfully.");
        } catch (Exception e) {
          Log.e(TAG, "Exception in readPixelsCallback: ", e);
          if (!timedOut.get() && !resultFuture.isDone()) resultFuture.completeExceptionally(e);
        } finally {
          frameLatch.countDown();
        }
      };

      final Texture.PixelBufferDescriptor descriptor = new Texture.PixelBufferDescriptor(
        pixelBuffer, Texture.Format.RGBA, Texture.Type.UBYTE,
        1, 0, 0, IMAGE_WIDTH, mMainThreadHandler, readPixelsCallback);

      Log.i(TAG, "Beginning frame rendering on render thread...");
      long frameTimeNanos = System.nanoTime();
      setupFrameForRenderingInternal();

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
        if (!frameLatch.await(RENDER_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS)) { // Slightly longer for internal latch
          Log.e(TAG, "Render thread timed out waiting for callback latch!");
          if (!timedOut.get() && !resultFuture.isDone()) { // Check conditions before completing
            resultFuture.completeExceptionally(new RuntimeException("Internal latch timeout waiting for callback"));
          }
        } else if (timedOut.get()) {
          Log.w(TAG, "Render thread proceeding after timeout occurred (signaled by latch).");
        } else {
          Log.i(TAG, "Render thread resuming after callback completed normally.");
        }
      } else {
        Log.e(TAG, "renderer.beginFrame() failed on render thread!");
        mMainThreadHandler.removeCallbacks(timeoutRunnable); // Ensure timeout is removed
        if (!resultFuture.isDone())
          resultFuture.completeExceptionally(new RuntimeException("Renderer beginFrame failed"));
        frameLatch.countDown(); // Ensure latch is released
      }
    } catch (IllegalStateException ise) {
      Log.e(TAG, "IllegalStateException during background render task: ", ise);
      mMainThreadHandler.removeCallbacks(timeoutRunnable);
      if (!resultFuture.isDone()) resultFuture.completeExceptionally(ise);
      frameLatch.countDown(); // Ensure latch is released
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      Log.e(TAG, "Background render task interrupted: ", e);
      mMainThreadHandler.removeCallbacks(timeoutRunnable);
      if (!resultFuture.isDone()) resultFuture.completeExceptionally(e);
      frameLatch.countDown(); // Ensure latch is released
    } catch (Exception e) { // Catch-all for other unexpected exceptions
      Log.e(TAG, "Exception during background render task: ", e);
      mMainThreadHandler.removeCallbacks(timeoutRunnable);
      if (!resultFuture.isDone()) resultFuture.completeExceptionally(e);
      frameLatch.countDown(); // Ensure latch is released
    } finally {
      Log.i(TAG, "Background render task finished execution on render thread.");
    }
  }
  // --- render refactored parts END ---

  @NonNull
  public CompletableFuture<Bitmap> render() {
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

    mRenderExecutor.submit(() -> performRenderOnRenderThread(resultFuture));
    return resultFuture;
  }

  private int findEntityByNameInternal(@NonNull String name) {
    if (mCurrentAsset == null || mAssetEntities == null) {
      Log.w(TAG, "findEntityByNameInternal: No asset loaded or entities not set.");
      return Entity.NULL;
    }
    // Ensure mAssetEntities is iterated, which contains only visible entities if filtering occurred
    for (int entityId : mAssetEntities) {
      String currentName = mCurrentAsset.getName(entityId); // mCurrentAsset should be valid here
      if (currentName != null && currentName.equals(name)) {
        Log.d(TAG, "findEntityByNameInternal: Found entity '" + name + "' with ID: " + entityId);
        return entityId;
      }
    }
    Log.w(TAG, "findEntityByNameInternal: Entity with name '" + name + "' not found.");
    return Entity.NULL;
  }

  private float[] fitIntoUnitCubeInternal(@NonNull Box aabb, float zOffset, float scaleFactor) {
    float[] center = aabb.getCenter();
    float[] halfExtent = aabb.getHalfExtent();
    float maxExtent = Math.max(halfExtent[0], Math.max(halfExtent[1], halfExtent[2]));
    float baseScale = (maxExtent > 1e-6f) ? (1.0f / maxExtent) : 1.0f;
    float finalScale = baseScale * scaleFactor;

    // Avoid division by zero if finalScale is extremely small or zero
    float adjustedCenterZ = center[2] + ((Math.abs(finalScale) > 1e-9f) ? (zOffset / finalScale) : 0.0f);


    float aabbHeight = halfExtent[1] * 2.0f;
    float verticalOffsetInAABBSpace = aabbHeight * VERTICAL_CENTERING_ADJUSTMENT_FACTOR;
    float targetYToCenter = center[1] + verticalOffsetInAABBSpace;

    float[] scaleMatrix = new float[16];
    float[] translationMatrix = new float[16];
    float[] finalTransform = new float[16];

    Matrix.setIdentityM(scaleMatrix, 0);
    Matrix.scaleM(scaleMatrix, 0, finalScale, finalScale, finalScale);
    Matrix.setIdentityM(translationMatrix, 0);
    Matrix.translateM(translationMatrix, 0, -center[0], -targetYToCenter, -adjustedCenterZ);
    Matrix.multiplyMM(finalTransform, 0, scaleMatrix, 0, translationMatrix, 0);

    return finalTransform;
  }

  @NonNull
  private CompletableFuture<Void> updateViewPortAsync(@Nullable String entityName, float scaleFactor) {
    CompletableFuture<Void> future = new CompletableFuture<>();
    if (mIsCleanedUp.get()) {
      future.completeExceptionally(new IllegalStateException("Renderer is cleaned up."));
      return future;
    }
    // mIsInitialized check removed here as per original code's intent (can be called during init)
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
    RenderableManager rm = mEngine.getRenderableManager();
    int rootEntity = mCurrentAsset.getRoot();

    if (!tcm.hasComponent(rootEntity)) {
      Log.w(TAG, "updateViewPortInternal: Asset root entity (" + rootEntity + ") has no transform component.");
      return;
    }
    int rootInstance = tcm.getInstance(rootEntity);

    Box targetAabb = new Box(); // Initialize with default (empty) values
    boolean specificAabbFound = false;

    if (entityName != null) {
      int targetEntityId = findEntityByNameInternal(entityName);
      if (targetEntityId != Entity.NULL) {
        if (rm.hasComponent(targetEntityId)) {
          int renderableInstance = rm.getInstance(targetEntityId);
          // It's crucial that getAxisAlignedBoundingBox populates the passed Box object.
          rm.getAxisAlignedBoundingBox(renderableInstance, targetAabb);
          specificAabbFound = true;
          Log.i(TAG, "updateViewPortInternal: Using AABB of specific entity '" + entityName + "' (ID: " + targetEntityId + "). Center: " + Arrays.toString(targetAabb.getCenter()) + ", HalfExtent: " + Arrays.toString(targetAabb.getHalfExtent()));
        } else {
          Log.w(TAG, "updateViewPortInternal: Entity '" + entityName + "' found, but has no renderable component. Falling back to asset AABB.");
        }
      } else {
        Log.w(TAG, "updateViewPortInternal: Entity '" + entityName + "' not found. Falling back to asset AABB.");
      }
    }

    if (!specificAabbFound) {
      Box assetBox = mCurrentAsset.getBoundingBox(); // This should return a valid Box object
      if (assetBox != null) {
        // Manually copy values to targetAabb as Box might be immutable or direct assignment might share reference
        targetAabb.setCenter(assetBox.getCenter()[0], assetBox.getCenter()[1], assetBox.getCenter()[2]);
        targetAabb.setHalfExtent(assetBox.getHalfExtent()[0], assetBox.getHalfExtent()[1], assetBox.getHalfExtent()[2]);
        Log.i(TAG, "updateViewPortInternal: Using AABB of the entire asset. Center: " + Arrays.toString(targetAabb.getCenter()) + ", HalfExtent: " + Arrays.toString(targetAabb.getHalfExtent()));
      } else {
        Log.e(TAG, "updateViewPortInternal: Could not get bounding box from the asset. Cannot proceed.");
        return;
      }
    }

    float[] halfExtent = targetAabb.getHalfExtent();
    final float epsilon = 1e-6f; // A small epsilon value
    if (Math.abs(halfExtent[0]) <= epsilon && Math.abs(halfExtent[1]) <= epsilon && Math.abs(halfExtent[2]) <= epsilon) {
      Log.e(TAG, "updateViewPortInternal: Invalid or zero-sized target AABB obtained (HalfExtent: " + Arrays.toString(halfExtent) + "). Cannot calculate transform.");
      return;
    }

    float[] transformMatrix = fitIntoUnitCubeInternal(targetAabb, DEFAULT_VIEWPORT_Z_OFFSET, scaleFactor);
    tcm.setTransform(rootInstance, transformMatrix);
    Log.i(TAG, "updateViewPortInternal: Applied new transform to asset root (" + rootEntity + ").");
  }

  // --- release() refactored parts START ---
  private void performReleaseOnRenderThread(@NonNull ExecutorService executorToShutdown, @NonNull CompletableFuture<Void> releaseFuture) {
    try {
      cleanupFilamentResourcesInternal();
      Log.i(TAG, "Filament resource cleanup task completed on render thread.");
      executorToShutdown.shutdown();
      if (!executorToShutdown.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        Log.e(TAG, "Render executor did not terminate gracefully. Forcing shutdown...");
        executorToShutdown.shutdownNow();
        if (!executorToShutdown.awaitTermination(5, TimeUnit.SECONDS)) {
          Log.e(TAG, "Render executor did not terminate even after shutdownNow().");
          if (!releaseFuture.isDone())
            releaseFuture.completeExceptionally(new RuntimeException("Executor failed to terminate."));
          return; // Exit early if future already completed exceptionally
        }
      }
      Log.i(TAG, "Render executor terminated.");
      nullifyFilamentMembers(); // Set members to null after successful shutdown and cleanup
      if (!releaseFuture.isDone()) releaseFuture.complete(null);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      Log.e(TAG, "Interrupted during executor shutdown or cleanup.", ie);
      executorToShutdown.shutdownNow(); // Force shutdown on interrupt
      nullifyFilamentMembers();
      if (!releaseFuture.isDone()) releaseFuture.completeExceptionally(ie);
    } catch (Exception e) {
      Log.e(TAG, "Exception during resource cleanup or executor shutdown.", e);
      nullifyFilamentMembers();
      if (!releaseFuture.isDone()) releaseFuture.completeExceptionally(e);
    }
  }

  private void nullifyFilamentMembers() {
    mRenderer = null;
    mSwapChain = null;
    mView = null;
    mScene = null;
    mCamera = null;
    mSkybox = null;
    mAssetLoader = null;
    mResourceLoader = null;
    mCurrentAsset = null;
    mAssetEntities = null;
    mEngine = null; // Engine last
    mLightEntity = 0;
    mCameraEntity = 0;
    if (mEntityInitialTransforms != null) mEntityInitialTransforms.clear();
    if (mMorphTargetInfoMap != null) mMorphTargetInfoMap.clear();
  }
  // --- release() refactored parts END ---

  @NonNull
  public CompletableFuture<Void> release() {
    CompletableFuture<Void> releaseFuture = new CompletableFuture<>();
    if (mIsCleanedUp.compareAndSet(false, true)) {
      Log.i(TAG, "release() called. Initiating shutdown...");
      mIsInitialized.set(false); // Mark as not initialized
      mMainThreadHandler.removeCallbacksAndMessages(null); // Clear main thread tasks

      ExecutorService executor = mRenderExecutor; // Capture current executor
      mRenderExecutor = null; // Nullify immediately to prevent new submissions

      if (executor != null && !executor.isShutdown()) {
        Log.i(TAG, "Submitting final resource cleanup task to render thread...");
        executor.submit(() -> performReleaseOnRenderThread(executor, releaseFuture));
      } else {
        Log.w(TAG, "Render executor was null or already shutdown. Performing direct cleanup if possible...");
        try {
          cleanupFilamentResourcesInternal(); // Attempt direct cleanup
          nullifyFilamentMembers();
          releaseFuture.complete(null);
        } catch (Exception e) {
          Log.e(TAG, "Exception during direct cleanup.", e);
          nullifyFilamentMembers(); // Still nullify
          releaseFuture.completeExceptionally(e);
        }
      }
    } else {
      Log.w(TAG, "release() called, but already cleaned up or cleanup in progress.");
      releaseFuture.complete(null); // Already done or in progress
    }
    return releaseFuture;
  }

  // --- cleanupFilamentResourcesInternal refactored parts START ---
  private void destroyAssetAndLoadersInternal() {
    if (mCurrentAsset != null && mAssetLoader != null) {
      try {
        Log.i(TAG, "Destroying FilamentAsset...");
        mAssetLoader.destroyAsset(mCurrentAsset);
        Log.i(TAG, "FilamentAsset destroyed.");
      } catch (Exception e) {
        Log.e(TAG, "Exception destroying FilamentAsset: ", e);
      }
    }
    mCurrentAsset = null; // Nullify after attempt
    mAssetEntities = null;

    if (mResourceLoader != null) {
      try {
        Log.i(TAG, "Destroying ResourceLoader...");
        mResourceLoader.destroy();
        Log.i(TAG, "ResourceLoader destroyed.");
      } catch (Exception e) {
        Log.e(TAG, "Exception destroying ResourceLoader: ", e);
      }
    }
    mResourceLoader = null;

    if (mAssetLoader != null) {
      try {
        Log.i(TAG, "Destroying AssetLoader...");
        mAssetLoader.destroy();
        Log.i(TAG, "AssetLoader destroyed.");
      } catch (Exception e) {
        Log.e(TAG, "Exception destroying AssetLoader: ", e);
      }
    }
    mAssetLoader = null;
  }

  private void destroySceneContentsInternal() {
    if (mEngine == null || !mEngine.isValid() || mScene == null || !mEngine.isValidScene(mScene)) return;

    // Remove entities that were added to the scene (mAssetEntities might be a subset if filtered)
    // It's safer to remove all entities associated with the asset if it was fully added initially.
    // However, the original code removed mAssetEntities. If mAssetEntities truly reflects all *added*
    // entities from mCurrentAsset that are still in the scene, this is fine.
    // A more robust way for general assets might be to iterate mCurrentAsset.getEntities() if they were all added.
    // Given the current logic, using mAssetEntities (which may have been filtered) is consistent.
    if (mAssetEntities != null && mAssetEntities.length > 0) {
      // Check if scene is still valid before removing entities
      if (mEngine.isValidScene(mScene)) {
        mScene.removeEntities(mAssetEntities);
        Log.d(TAG, "Removed asset entities from scene.");
      }
    }


    if (mLightEntity != 0 && EntityManager.get().isAlive(mLightEntity)) {
      LightManager lm = mEngine.getLightManager();
      if (lm.hasComponent(mLightEntity)) { // Check component before destroying
        lm.destroy(mLightEntity);
      }
      EntityManager.get().destroy(mLightEntity); // Destroy entity itself
      Log.d(TAG, "Destroyed light entity.");
    }
    mLightEntity = 0;

    // Skybox was commented out, but if it were active:
    // if (mSkybox != null && mEngine.isValidSkybox(mSkybox)) mEngine.destroySkybox(mSkybox);
    // mSkybox = null;
  }

  private void destroyCameraAndDependentComponentsInternal() {
    if (mEngine == null || !mEngine.isValid()) return;

    if (mCamera != null && mCamera.getEntity() != 0) { // mCamera.getEntity() is mCameraEntity
      int entity = mCamera.getEntity();
      if (EntityManager.get().isAlive(entity)) { // Check entity validity
        // Camera component is attached to mCameraEntity
        if (mEngine.getCameraComponent(entity) != null) { // Check if component exists
          mEngine.destroyCameraComponent(entity);
          Log.d(TAG, "Destroyed camera component.");
        }
      }
    }
    mCamera = null; // Nullify the reference

    if (mCameraEntity != 0 && EntityManager.get().isAlive(mCameraEntity)) {
      EntityManager.get().destroy(mCameraEntity);
      Log.d(TAG, "Destroyed camera entity.");
    }
    mCameraEntity = 0;
  }

  private void destroyCoreFilamentObjectsInternal() {
    if (mEngine == null || !mEngine.isValid()) return;
    try {
      if (mView != null && mEngine.isValidView(mView)) mEngine.destroyView(mView);
      if (mScene != null && mEngine.isValidScene(mScene)) mEngine.destroyScene(mScene);
      if (mRenderer != null && mEngine.isValidRenderer(mRenderer)) mEngine.destroyRenderer(mRenderer);
      if (mSwapChain != null && mEngine.isValidSwapChain(mSwapChain)) mEngine.destroySwapChain(mSwapChain);
    } catch (Exception e) {
      Log.e(TAG, "Exception destroying core Filament objects: ", e);
    }

    mView = null;
    mScene = null;
    mRenderer = null;
    mSwapChain = null;
  }
  // --- cleanupFilamentResourcesInternal refactored parts END ---

  private void cleanupFilamentResourcesInternal() {
    Log.i(TAG, "Executing cleanupFilamentResourcesInternal on thread: " + Thread.currentThread().getName());

    if (mMorphTargetInfoMap != null) mMorphTargetInfoMap.clear();
    if (mEntityInitialTransforms != null) mEntityInitialTransforms.clear();
    // mEntityInitialTransforms = null; // Don't nullify the map itself here, nullifyFilamentMembers does it.

    // Order of destruction can be important.
    // 1. Destroy things that depend on the engine but are "higher level" (assets, loaders).
    destroyAssetAndLoadersInternal();

    // 2. Destroy scene contents (entities, lights, skybox).
    // Needs engine to be valid.
    if (mEngine != null && mEngine.isValid()) {
      destroySceneContentsInternal(); // Handles entities, light
      destroyCameraAndDependentComponentsInternal(); // Handles camera entity and component
      destroyCoreFilamentObjectsInternal(); // Handles View, Scene, Renderer, SwapChain
    }


    // 3. Finally, destroy the engine itself.
    if (mEngine != null && mEngine.isValid()) {
      try {
        Log.i(TAG, "Destroying Filament Engine...");
        mEngine.destroy();
        Log.i(TAG, "Filament Engine destroyed.");
      } catch (Exception e) {
        Log.e(TAG, "Exception destroying Filament Engine: ", e);
      }
    }
    mEngine = null; // Nullify after attempt, ensures it's nulled even if destroy fails

    // Other members are nullified by nullifyFilamentMembers() called by the release process
    // or directly if this is called from a different path.
    // For safety, can call a subset of nullification here if this method could be entered standalone
    // without going through the full release path.
    // However, given current structure, `release` is the main entry for full cleanup.
  }


  @Override
  protected void finalize() throws Throwable {
    try {
      if (!mIsCleanedUp.get()) {
        Log.w(TAG, "ModelRender finalize() called without explicit release! This is problematic and indicates a potential resource leak.");
        // Attempt a last-ditch cleanup, though it's not guaranteed to work correctly
        // as it might not be on the render thread and executor might be gone.
        // This is mostly for logging and awareness during development.
        // A true fix is ensuring release() is always called.
        // performDirectCleanupForFinalize(); // A hypothetical synchronous cleanup, unsafe.
      }
    } finally {
      super.finalize();
    }
  }

  private void prepareMorphTargetInfoInternal() {
    if (mEngine == null || !mEngine.isValid() || mCurrentAsset == null || mAssetEntities == null) {
      Log.w(TAG, "prepareMorphTargetInfoInternal: Invalid state (engine, asset, or entities null/invalid).");
      return;
    }
    mMorphTargetInfoMap.clear(); // Clear previous info
    Log.i(TAG, "--- Preparing Morph Target Information ---");
    RenderableManager rm = mEngine.getRenderableManager();
    int totalMorphTargetsFound = 0;

    // Iterate over mAssetEntities which should contain only visible/relevant entities
    for (int entityId : mAssetEntities) {
      if (!rm.hasComponent(entityId)) {
        continue;
      }
      var instance = rm.getInstance(entityId);
      int numMorphTargets = rm.getMorphTargetCount(instance);

      if (numMorphTargets > 0) {
        Log.d(TAG, "Entity ID " + entityId + " (Name: " + mCurrentAsset.getName(entityId) + ") has " + numMorphTargets + " morph targets.");
        String[] morphTargetNamesForEntity = mCurrentAsset.getMorphTargetNames(entityId); // Get all names for this entity once

        for (int j = 0; j < numMorphTargets; j++) {
          String morphName = null;
          if (morphTargetNamesForEntity != null && j < morphTargetNamesForEntity.length) {
            morphName = morphTargetNamesForEntity[j];
          } else {
            Log.w(TAG, "  Warning: Morph target index " + j + " for entity " + entityId +
              (morphTargetNamesForEntity != null ? " is out of bounds for names array (size: " + morphTargetNamesForEntity.length + ")" : " but names array is null"));
          }

          if (morphName != null && !morphName.isEmpty()) {
            MorphInfo info = new MorphInfo(entityId, morphName, j, numMorphTargets);
            mMorphTargetInfoMap.computeIfAbsent(morphName, k -> new ArrayList<>()).add(info);
            Log.d(TAG, "  Added morph info: Name='" + morphName + "', Entity=" + entityId + ", Index=" + j);
            totalMorphTargetsFound++;
          } else {
            Log.w(TAG, "  Warning: Unnamed or inaccessible morph target at index " + j + " on entity " + entityId);
          }
        }
      }
    }
    Log.i(TAG, "--- Finished Preparing Morph Targets ---");
    Log.i(TAG, "Found " + totalMorphTargetsFound + " named morph targets across " + mMorphTargetInfoMap.size() + " unique names.");
  }

  private void setMorphWeightsInternal(@NonNull Map<String, Float> weights) {
    if (mEngine == null || !mEngine.isValid() || mCurrentAsset == null) {
      Log.e(TAG, "setMorphWeightsInternal: Invalid state (engine or asset null/invalid).");
      return;
    }
    if (mMorphTargetInfoMap.isEmpty()) {
      // Log.v(TAG, "setMorphWeightsInternal: No morph target info prepared, skipping.");
      return;
    }

    RenderableManager rm = mEngine.getRenderableManager();
    // Use a temporary map to build up weights per entity before applying
    Map<Integer, float[]> entityWeightsMap = new HashMap<>();

    for (Map.Entry<String, List<MorphInfo>> mapEntry : mMorphTargetInfoMap.entrySet()) {
      String morphNameInModel = mapEntry.getKey();
      List<MorphInfo> infos = mapEntry.getValue();
      float desiredWeight = weights.getOrDefault(morphNameInModel, 0.0f); // Default to 0 if not in input

      for (MorphInfo info : infos) {
        // Ensure the entity still exists and has a renderable component
        if (!EntityManager.get().isAlive(info.entityId) || !rm.hasComponent(info.entityId)) {
          Log.w(TAG, "setMorphWeightsInternal: Entity " + info.entityId + " for morph target " + info.morphTargetName + " is no longer alive or has no renderable. Skipping.");
          continue;
        }

        float[] weightsArray = entityWeightsMap.computeIfAbsent(info.entityId, k -> {
          // Initialize with zeros, size based on this entity's total morph targets
          float[] newArr = new float[info.totalMorphTargetsForEntity];
          // Arrays.fill(newArr, 0.0f); // computeIfAbsent with new float[] already initializes to 0.0f
          return newArr;
        });

        if (info.morphTargetIndex < weightsArray.length) {
          weightsArray[info.morphTargetIndex] = desiredWeight;
        } else {
          Log.e(TAG, "  Error: Morph index " + info.morphTargetIndex + " for target '" + info.morphTargetName +
            "' out of bounds for entity " + info.entityId + " (array size " + weightsArray.length + "). This should not happen if prepareMorphTargetInfoInternal was correct.");
        }
      }
    }

    // Log input weights not found in the model (excluding _neutral as it's common from MediaPipe)
    for (String inputName : weights.keySet()) {
      if (!inputName.equals("_neutral") && !mMorphTargetInfoMap.containsKey(inputName)) {
        Log.w(TAG, "Input weight name '" + inputName + "' not found in prepared morph targets for the current model.");
      }
    }

    // Apply the collected weights
    if (!entityWeightsMap.isEmpty()) {
      for (Map.Entry<Integer, float[]> entityEntry : entityWeightsMap.entrySet()) {
        int entityId = entityEntry.getKey();
        float[] finalWeights = entityEntry.getValue();
        // Double check component before setting, though it should exist if it was in mMorphTargetInfoMap
        if (rm.hasComponent(entityId)) {
          var instance = rm.getInstance(entityId);
          rm.setMorphWeights(instance, finalWeights, 0);
        } else {
          Log.e(TAG, "setMorphWeightsInternal: Entity " + entityId + " unexpectedly lost its Renderable component before weights could be applied.");
        }
      }
    }
  }
}