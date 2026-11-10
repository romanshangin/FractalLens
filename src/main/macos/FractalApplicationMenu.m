#import <Cocoa/Cocoa.h>
#include <jni.h>

// One command in the system-owned application menu (the menu named after the app), for
// example Settings… with Command-comma. JavaFX's public MenuBar API cannot reach that
// menu, and Glass's own separator insertion ignores the requested position, so the item
// and its separator are plain AppKit objects owned by this session. Glass builds a new
// application menu for every window's menu bar and for its default menu bar, so the
// session follows NSApp.mainMenu and moves both items into the current menu.
@interface FractalApplicationMenuCommand : NSObject
@property NSMenu *menu;
@property NSMenuItem *item;
@property NSMenuItem *separator;
@property JavaVM *vm;
@property jclass owner;
@property jmethodID activated;
@property BOOL observing;
- (void)activate:(id)sender;
- (void)follow;
@end

@implementation FractalApplicationMenuCommand
- (void)activate:(id)sender {
    (void)sender;
    JNIEnv *env = NULL;
    BOOL attached = NO;
    jint status = (*self.vm)->GetEnv(self.vm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*self.vm)->AttachCurrentThreadAsDaemon(self.vm, (void **)&env, NULL) != JNI_OK) return;
        attached = YES;
    } else if (status != JNI_OK) return;
    // Java only queues the command on the FX thread; it never runs inside menu tracking.
    (*env)->CallStaticVoidMethod(env, self.owner, self.activated);
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if (attached) (*self.vm)->DetachCurrentThread(self.vm);
}
- (void)follow {
    NSMenu *current = NSApp.mainMenu.numberOfItems > 0 ? [NSApp.mainMenu itemAtIndex:0].submenu : nil;
    if (!current || (current == self.menu && [current indexOfItem:self.item] >= 0)) return;
    for (NSMenuItem *item in @[self.item, self.separator]) {
        if (item.menu) [item.menu removeItem:item];
    }
    NSInteger index = 0;
    for (NSInteger i = 0; i < current.numberOfItems; i++) {
        if ([current itemAtIndex:i].action == @selector(hide:)) { index = i; break; }
    }
    [current insertItem:self.item atIndex:index];
    [current insertItem:self.separator atIndex:index + 1];
    self.menu = current;
}
- (void)observeValueForKeyPath:(NSString *)keyPath ofObject:(id)object
                        change:(NSDictionary<NSKeyValueChangeKey, id> *)change context:(void *)context {
    (void)keyPath; (void)object; (void)change; (void)context;
    [self follow];
}
- (BOOL)validateMenuItem:(NSMenuItem *)item {
    (void)item;
    return YES;
}
- (void)dealloc {
    JNIEnv *env = NULL;
    if (_owner && (*_vm)->GetEnv(_vm, (void **)&env, JNI_VERSION_1_8) == JNI_OK)
        (*env)->DeleteGlobalRef(env, _owner);
}
@end

// Inserts the command and a following separator before the Hide commands (or first when
// there are none). Returns 0 while the application menu is not installed yet.
JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacApplicationMenuCommand_install
(JNIEnv *env, jclass type, jstring title, jstring keyEquivalent) {
    if (![NSThread isMainThread]) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                        "AppKit menu requires the main thread");
        return 0;
    }
    NSMenu *menu = NSApp.mainMenu.numberOfItems > 0 ? [NSApp.mainMenu itemAtIndex:0].submenu : nil;
    if (!menu) return 0;
    const char *titleChars = (*env)->GetStringUTFChars(env, title, NULL);
    const char *keyChars = (*env)->GetStringUTFChars(env, keyEquivalent, NULL);
    if (!titleChars || !keyChars) {
        if (titleChars) (*env)->ReleaseStringUTFChars(env, title, titleChars);
        if (keyChars) (*env)->ReleaseStringUTFChars(env, keyEquivalent, keyChars);
        return 0;
    }
    NSString *itemTitle = [NSString stringWithUTF8String:titleChars];
    NSString *key = [NSString stringWithUTF8String:keyChars];
    (*env)->ReleaseStringUTFChars(env, title, titleChars);
    (*env)->ReleaseStringUTFChars(env, keyEquivalent, keyChars);
    FractalApplicationMenuCommand *command = [FractalApplicationMenuCommand new];
    JavaVM *vm = NULL;
    if ((*env)->GetJavaVM(env, &vm) != JNI_OK) return 0;
    command.vm = vm;
    command.activated = (*env)->GetStaticMethodID(env, type, "activated", "()V");
    if (!command.activated) return 0;
    command.owner = (*env)->NewGlobalRef(env, type);
    if (!command.owner) return 0;
    command.item = [[NSMenuItem alloc] initWithTitle:itemTitle action:@selector(activate:) keyEquivalent:key];
    command.item.keyEquivalentModifierMask = NSEventModifierFlagCommand;
    command.item.target = command;
    command.separator = [NSMenuItem separatorItem];
    [command follow];
    [NSApp addObserver:command forKeyPath:@"mainMenu" options:0 context:NULL];
    command.observing = YES;
    return (jlong)(intptr_t)(__bridge_retained void *)command;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacApplicationMenuCommand_remove
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (![NSThread isMainThread]) {
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                        "AppKit menu requires the main thread");
        return;
    }
    if (!handle) return;
    FractalApplicationMenuCommand *command = (__bridge_transfer FractalApplicationMenuCommand *)(void *)(intptr_t)handle;
    if (command.observing) [NSApp removeObserver:command forKeyPath:@"mainMenu"];
    command.observing = NO;
    // Remove by identity: other code may have changed the menu's indices since insertion.
    for (NSMenuItem *item in @[command.item, command.separator]) {
        if (item.menu) [item.menu removeItem:item];
    }
    command.item.target = nil;
}
