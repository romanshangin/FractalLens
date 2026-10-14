#import <Cocoa/Cocoa.h>
#include <jni.h>

@interface FractalParameterSession : NSObject <NSWindowDelegate, NSTextFieldDelegate>
@property NSPanel *panel;
@property NSWindow *owner;
@property NSTextField *first;
@property NSTextField *second;
@property NSTextField *error;
@property BOOL cancelled;
@property BOOL finished;
@property NSString *defaultFirst;
@property NSString *defaultSecond;
@property NSString *validationError;
@property JavaVM *vm;
@property jobject callback;
@property jmethodID completion;
@property jmethodID validation;
@end

static JNIEnv *sheetEnvironment(JavaVM *vm, BOOL *attached) {
    JNIEnv *env = NULL;
    *attached = NO;
    jint status = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK) return NULL;
        *attached = YES;
    } else if (status != JNI_OK) return NULL;
    return env;
}

static BOOL sheetMainThread(JNIEnv *env) {
    if ([NSThread isMainThread]) return YES;
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                    "AppKit sheet requires the main thread");
    return NO;
}

static jstring javaString(JNIEnv *env, NSString *text) {
    NSUInteger length = text.length;
    unichar *characters = malloc(length * sizeof(unichar));
    if (length && !characters) return NULL;
    [text getCharacters:characters range:NSMakeRange(0, length)];
    jstring result = (*env)->NewString(env, (const jchar *)characters, (jsize)length);
    free(characters);
    return result;
}

static NSString *nativeString(JNIEnv *env, jstring text) {
    const jchar *characters = (*env)->GetStringChars(env, text, NULL);
    if (!characters) return nil;
    NSString *result = [NSString stringWithCharacters:(const unichar *)characters
                                              length:(NSUInteger)(*env)->GetStringLength(env, text)];
    (*env)->ReleaseStringChars(env, text, characters);
    return result;
}

// Parsing stays in Java so the native and fallback editors accept identical values.
static jint invalidField(FractalParameterSession *session) {
    BOOL attached;
    JNIEnv *env = sheetEnvironment(session.vm, &attached);
    if (!env) return 1;
    jstring first = javaString(env, session.first.stringValue);
    jstring second = javaString(env, session.second.stringValue);
    jint result = 1;
    if (first && second && !(*env)->ExceptionCheck(env))
        result = (*env)->CallIntMethod(env, session.callback, session.validation, first, second);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        result = 1;
    }
    if (first) (*env)->DeleteLocalRef(env, first);
    if (second) (*env)->DeleteLocalRef(env, second);
    if (attached) (*session.vm)->DetachCurrentThread(session.vm);
    return result;
}

@implementation FractalParameterSession
- (void)apply:(id)sender {
    (void)sender;
    jint invalidIndex = invalidField(self);
    if (invalidIndex != 0) {
        self.error.stringValue = self.validationError;
        NSTextField *invalid = invalidIndex == 1 ? self.first : self.second;
        [self.panel makeFirstResponder:invalid];
        [invalid selectText:nil];
        return;
    }
    [self.owner endSheet:self.panel returnCode:NSModalResponseOK];
}
- (void)cancel:(id)sender {
    (void)sender;
    [self.owner endSheet:self.panel returnCode:NSModalResponseCancel];
}
- (void)reset:(id)sender {
    (void)sender;
    self.first.stringValue = self.defaultFirst;
    self.second.stringValue = self.defaultSecond;
    self.error.stringValue = @"";
    [self.panel makeFirstResponder:self.first];
    [self.first selectText:nil];
}
- (void)controlTextDidChange:(NSNotification *)notification {
    (void)notification;
    if (invalidField(self) == 0)
        self.error.stringValue = @"";
}
- (BOOL)windowShouldClose:(NSWindow *)sender {
    (void)sender;
    [self cancel:nil];
    return NO;
}
- (void)dealloc {
    BOOL attached;
    JNIEnv *env = sheetEnvironment(_vm, &attached);
    if (_callback && env) (*env)->DeleteGlobalRef(env, _callback);
    if (attached) (*_vm)->DetachCurrentThread(_vm);
}
@end

static NSTextField *label(NSString *text) {
    NSTextField *view = [NSTextField wrappingLabelWithString:text];
    view.font = [NSFont systemFontOfSize:NSFont.systemFontSize];
    return view;
}

static NSButton *button(NSString *title, id target, SEL action) {
    NSButton *view = [NSButton buttonWithTitle:title target:target action:action];
    view.bezelStyle = NSBezelStyleRounded;
    return view;
}

JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacParameterSheet_open
(JNIEnv *env, jobject callback, jlong windowHandle, jstring title, jstring hintText,
 jstring firstLabel, jstring secondLabel, jstring firstId, jstring secondId,
 jstring first, jstring second, jstring defaultFirst, jstring defaultSecond, jstring errorText,
 jboolean singleField, jstring confirmTitle) {
    if (!sheetMainThread(env)) return 0;
    NSWindow *owner = (__bridge NSWindow *)(void *)(intptr_t)windowHandle;
    if (!owner.isVisible || owner.attachedSheet) return 0;
    FractalParameterSession *session = [FractalParameterSession new];
    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    session.vm = vm;
    session.completion = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, callback), "completed", "(ZLjava/lang/String;Ljava/lang/String;)V");
    if (!session.completion) return 0;
    session.validation = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, callback),
            "invalidField", "(Ljava/lang/String;Ljava/lang/String;)I");
    if (!session.validation) return 0;
    session.callback = (*env)->NewGlobalRef(env, callback);
    if (!session.callback) return 0;
    session.owner = owner;
    session.defaultFirst = nativeString(env, defaultFirst);
    session.defaultSecond = nativeString(env, defaultSecond);
    session.validationError = nativeString(env, errorText);
    session.panel = [[NSPanel alloc] initWithContentRect:NSMakeRect(0, 0, 480, 250)
            styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
            backing:NSBackingStoreBuffered defer:NO];
    session.panel.title = nativeString(env, title);
    session.panel.releasedWhenClosed = NO;
    session.panel.delegate = session;
    session.panel.backgroundColor = NSColor.windowBackgroundColor;
    NSTextField *heading = label(session.panel.title);
    heading.font = [NSFont boldSystemFontOfSize:15];
    NSTextField *hint = label(nativeString(env, hintText));
    hint.textColor = NSColor.secondaryLabelColor;
    session.first = [NSTextField textFieldWithString:nativeString(env, first)];
    session.second = [NSTextField textFieldWithString:nativeString(env, second)];
    session.first.identifier = nativeString(env, firstId);
    session.second.identifier = nativeString(env, secondId);
    session.first.accessibilityLabel = nativeString(env, firstLabel);
    session.second.accessibilityLabel = nativeString(env, secondLabel);
    session.first.delegate = session;
    session.second.delegate = session;
    [(NSTextFieldCell *)session.first.cell setSendsActionOnEndEditing:NO];
    [(NSTextFieldCell *)session.second.cell setSendsActionOnEndEditing:NO];
    session.first.target = session;
    session.first.action = @selector(apply:);
    session.second.target = session;
    session.second.action = @selector(apply:);
    NSArray *rows = singleField ? @[@[label(nativeString(env, firstLabel)), session.first]] : @[
        @[label(nativeString(env, firstLabel)), session.first],
        @[label(nativeString(env, secondLabel)), session.second]];
    NSGridView *grid = [NSGridView gridViewWithViews:rows];
    grid.rowSpacing = 12;
    grid.columnSpacing = 12;
    [grid columnAtIndex:0].width = singleField ? 60 : 180;
    [grid columnAtIndex:0].xPlacement = NSGridCellPlacementLeading;
    [grid columnAtIndex:1].xPlacement = NSGridCellPlacementFill;
    grid.yPlacement = NSGridCellPlacementCenter;
    session.error = label(@"");
    session.error.identifier = @"dialog-validation-error";
    NSButton *reset = button(@"Reset to Defaults", session, @selector(reset:));
    NSButton *cancel = button(@"Cancel", session, @selector(cancel:));
    NSButton *apply = button(nativeString(env, confirmTitle), session, @selector(apply:));
    cancel.keyEquivalent = @"\033";
    apply.keyEquivalent = @"\r";
    NSView *spacer = [NSView new];
    [spacer setContentHuggingPriority:1 forOrientation:NSLayoutConstraintOrientationHorizontal];
    NSStackView *actions = [NSStackView stackViewWithViews:singleField
            ? @[spacer, cancel, apply] : @[reset, spacer, cancel, apply]];
    actions.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    actions.spacing = 8;
    NSStackView *body = [NSStackView stackViewWithViews:@[heading, hint, grid, session.error, actions]];
    body.orientation = NSUserInterfaceLayoutOrientationVertical;
    body.alignment = NSLayoutAttributeLeading;
    body.spacing = 14;
    body.translatesAutoresizingMaskIntoConstraints = NO;
    [session.panel.contentView addSubview:body];
    [NSLayoutConstraint activateConstraints:@[
        [body.leadingAnchor constraintEqualToAnchor:session.panel.contentView.leadingAnchor constant:20],
        [body.trailingAnchor constraintEqualToAnchor:session.panel.contentView.trailingAnchor constant:-20],
        [body.topAnchor constraintEqualToAnchor:session.panel.contentView.topAnchor constant:20],
        [body.bottomAnchor constraintEqualToAnchor:session.panel.contentView.bottomAnchor constant:-20],
        [hint.widthAnchor constraintEqualToAnchor:body.widthAnchor],
        [grid.widthAnchor constraintEqualToAnchor:body.widthAnchor],
        [session.error.widthAnchor constraintEqualToAnchor:body.widthAnchor],
        [session.error.heightAnchor constraintGreaterThanOrEqualToConstant:32],
        [actions.widthAnchor constraintEqualToAnchor:body.widthAnchor]]];
    session.first.nextKeyView = singleField ? cancel : session.second;
    session.second.nextKeyView = reset;
    reset.nextKeyView = cancel;
    cancel.nextKeyView = apply;
    apply.nextKeyView = session.first;
    session.panel.initialFirstResponder = session.first;
    [owner beginSheet:session.panel completionHandler:^(NSModalResponse response) {
        session.finished = YES;
        [session.panel orderOut:nil];
        if (session.cancelled) return;
        BOOL accepted = response == NSModalResponseOK && invalidField(session) == 0;
        BOOL attached;
        JNIEnv *callbackEnv = sheetEnvironment(session.vm, &attached);
        if (callbackEnv) {
            jstring resultFirst = javaString(callbackEnv, session.first.stringValue);
            jstring resultSecond = javaString(callbackEnv, session.second.stringValue);
            if (resultFirst && resultSecond && !(*callbackEnv)->ExceptionCheck(callbackEnv))
                (*callbackEnv)->CallVoidMethod(callbackEnv, session.callback, session.completion,
                        (jboolean)accepted, resultFirst, resultSecond);
            if (resultFirst) (*callbackEnv)->DeleteLocalRef(callbackEnv, resultFirst);
            if (resultSecond) (*callbackEnv)->DeleteLocalRef(callbackEnv, resultSecond);
            if ((*callbackEnv)->ExceptionCheck(callbackEnv)) {
                (*callbackEnv)->ExceptionDescribe(callbackEnv);
                (*callbackEnv)->ExceptionClear(callbackEnv);
            }
            if (attached) (*session.vm)->DetachCurrentThread(session.vm);
        }
    }];
    [session.first selectText:nil];
    return (jlong)(intptr_t)(__bridge_retained void *)session;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacParameterSheet_dispose
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!sheetMainThread(env) || !handle) return;
    FractalParameterSession *session = (__bridge_transfer FractalParameterSession *)(void *)(intptr_t)handle;
    session.cancelled = YES;
    if (!session.finished) [session.owner endSheet:session.panel returnCode:NSModalResponseCancel];
}
