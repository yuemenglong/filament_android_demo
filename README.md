# Filament Android Demo

## 线程亲和性重要说明

所有 Filament 资源（Engine、Renderer、SwapChain、View、Scene 等）的创建与销毁必须在专用渲染线程（mRenderExecutor）上完成，以确保线程亲和性。如果在非专用线程上操作这些对象，会导致 “This thread has not been adopted” 等崩溃。

请严格遵循此原则进行开发和维护。