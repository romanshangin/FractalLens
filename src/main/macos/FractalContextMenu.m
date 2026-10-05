#import <Cocoa/Cocoa.h>
#include <jni.h>

// AppKit may call us outside a Java->native invocation. Glass's attachment
// must not be assumed to survive a return to the native event loop.
static JNIEnv *environment(JavaVM *vm, BOOL *attached) {
    JNIEnv *env = NULL;
    *attached = NO;
    jint status = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK) return NULL;
        *attached = YES;
    } else if (status != JNI_OK) return NULL;
    return env;
}

// Glass runs JavaFX on the AppKit main thread on macOS. Reject other callers
// instead of dispatch_sync, which could deadlock the toolkit.
static BOOL requireMainThread(JNIEnv *env) {
    if ([NSThread isMainThread]) return YES;
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                    "AppKit menu requires the main thread");
    return NO;
}

@interface FractalMenuSession : NSObject
@property NSMenu *menu;
@property NSView *view;
@property NSPoint point;
@property BOOL cancelled;
@property BOOL selected;
@property BOOL tracking;
@property JavaVM *vm;
@property jobject callback;
@property jmethodID completion;
- (void)track;
- (void)choose:(id)sender;
@end

@implementation FractalMenuSession
- (void)choose:(id)sender {
    (void)sender;
    self.selected = YES;
    // Also finish tracking when accessibility invokes the item's action directly.
    [self.menu cancelTracking];
}
- (void)track {
    if (self.cancelled) return;
    // Retain the session through the nested tracking loop, including reentrant close.
    __attribute__((objc_precise_lifetime)) FractalMenuSession *session = self;
    if (session.view.window.isVisible && session.view.window.isKeyWindow) {
        session.tracking = YES;
        [session.menu popUpMenuPositioningItem:nil atLocation:session.point inView:session.view];
        session.tracking = NO;
    }
    if (!session.cancelled) {
        BOOL attached;
        JNIEnv *env = environment(session.vm, &attached);
        if (env) {
            (*env)->CallVoidMethod(env, session.callback, session.completion, (jboolean)session.selected);
            // Exceptions must not escape into the AppKit run loop.
            if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
            if (attached) (*session.vm)->DetachCurrentThread(session.vm);
        }
    }
}
- (void)dealloc {
    BOOL attached;
    JNIEnv *env = environment(_vm, &attached);
    if (_callback && env)
        (*env)->DeleteGlobalRef(env, _callback);
    if (attached) (*_vm)->DetachCurrentThread(_vm);
}
@end

// Resolve the Glass peer within the JNI bridge rather than through cross-module Java reflection.
JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacContextMenu_nativeWindowHandle
(JNIEnv *env, jclass type, jobject window) {
    (void)type;
    if (!requireMainThread(env)) return 0;
    jclass helper = (*env)->FindClass(env, "com/sun/javafx/stage/WindowHelper");
    if (!helper) return 0;
    jmethodID getPeer = (*env)->GetStaticMethodID(env, helper, "getPeer",
            "(Ljavafx/stage/Window;)Lcom/sun/javafx/tk/TKStage;");
    if (!getPeer) return 0;
    jobject peer = (*env)->CallStaticObjectMethod(env, helper, getPeer, window);
    if ((*env)->ExceptionCheck(env)) return 0;
    if (!peer) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                        "Window has no native peer");
        return 0;
    }
    jclass stage = (*env)->GetObjectClass(env, peer);
    if (!stage) return 0;
    jmethodID getHandle = (*env)->GetMethodID(env, stage, "getRawHandle", "()J");
    if (!getHandle) return 0;
    return (*env)->CallLongMethod(env, peer, getHandle);
}

JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacContextMenu_open
(JNIEnv *env, jobject callback, jlong windowHandle, jdouble x, jdouble y) {
    if (!requireMainThread(env)) return 0;
    NSWindow *window = (__bridge NSWindow *)(void *)(intptr_t)windowHandle;
    FractalMenuSession *session = [FractalMenuSession new];
    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    session.vm = vm;
    session.completion = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, callback), "completed", "(Z)V");
    if (!session.completion) return 0;
    session.callback = (*env)->NewGlobalRef(env, callback);
    if (!session.callback) return 0;
    session.view = window.contentView;
    NSRect bounds = session.view.bounds;
    // JavaFX scene coordinates and AppKit view coordinates are logical points.
    // No primary-screen height or backingScaleFactor assumptions (Retina/full screen).
    session.point = NSMakePoint(NSMinX(bounds) + x,
            session.view.isFlipped ? NSMinY(bounds) + y : NSMaxY(bounds) - y);
    session.menu = [[NSMenu alloc] initWithTitle:@""];
    session.menu.autoenablesItems = NO;
    NSMenuItem *item = [[NSMenuItem alloc] initWithTitle:@"Copy Coordinates and Zoom"
            action:@selector(choose:) keyEquivalent:@""];
    item.target = session;
    [session.menu addItem:item];
    // Return to Java before entering AppKit's nested event loop. Schedule only
    // in the default mode: a replacement must wait for the old tracking loop
    // to unwind, otherwise AppKit can cancel the newly opened menu as well.
    // JavaFX's common-mode sources continue running inside menu tracking.
    [session performSelector:@selector(track) withObject:nil afterDelay:0
            inModes:@[NSDefaultRunLoopMode]];
    return (jlong)(intptr_t)(__bridge_retained void *)session;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacContextMenu_dispose
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!requireMainThread(env) || !handle) return;
    FractalMenuSession *session = (__bridge_transfer FractalMenuSession *)(void *)(intptr_t)handle;
    session.cancelled = YES;
    [NSObject cancelPreviousPerformRequestsWithTarget:session];
    if (session.tracking) [session.menu cancelTrackingWithoutAnimation];
}
