#import <Cocoa/Cocoa.h>
#include <jni.h>
#include <limits.h>

@interface FractalIterationSession : NSObject <NSWindowDelegate, NSTextFieldDelegate>
@property NSPanel *panel;
@property NSWindow *owner;
@property NSTextField *base;
@property NSTextField *zoom;
@property NSTextField *error;
@property BOOL cancelled;
@property BOOL finished;
@property int defaultBase;
@property int defaultZoom;
@property JavaVM *vm;
@property jobject callback;
@property jmethodID completion;
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

static BOOL parseCount(NSString *draft, BOOL positive, int *value) {
    NSString *text = [draft stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceAndNewlineCharacterSet];
    const char *bytes = text.UTF8String;
    if (!bytes || !*bytes) return NO;
    if (*bytes == '+') bytes++;
    if (!*bytes) return NO;
    long long result = 0;
    for (; *bytes; bytes++) {
        if (*bytes < '0' || *bytes > '9') return NO;
        result = result * 10 + (*bytes - '0');
        if (result > INT_MAX) return NO;
    }
    if (positive && result == 0) return NO;
    *value = (int)result;
    return YES;
}

@implementation FractalIterationSession
- (void)apply:(id)sender {
    (void)sender;
    int base = 0, zoom = 0;
    BOOL validBase = parseCount(self.base.stringValue, YES, &base);
    BOOL validZoom = parseCount(self.zoom.stringValue, NO, &zoom);
    if (!validBase || !validZoom) {
        self.error.stringValue = @"Enter a positive base count and a non-negative zoom increment.";
        NSTextField *invalid = validBase ? self.zoom : self.base;
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
    self.base.stringValue = [NSString stringWithFormat:@"%d", self.defaultBase];
    self.zoom.stringValue = [NSString stringWithFormat:@"%d", self.defaultZoom];
    self.error.stringValue = @"";
    [self.panel makeFirstResponder:self.base];
    [self.base selectText:nil];
}
- (void)controlTextDidChange:(NSNotification *)notification {
    (void)notification;
    int base, zoom;
    if (parseCount(self.base.stringValue, YES, &base) && parseCount(self.zoom.stringValue, NO, &zoom))
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

JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacIterationSheet_open
(JNIEnv *env, jobject callback, jlong windowHandle, jint base, jint zoom, jint defaultBase, jint defaultZoom) {
    if (!sheetMainThread(env)) return 0;
    NSWindow *owner = (__bridge NSWindow *)(void *)(intptr_t)windowHandle;
    if (!owner.isVisible || owner.attachedSheet) return 0;
    FractalIterationSession *session = [FractalIterationSession new];
    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    session.vm = vm;
    session.completion = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, callback), "completed", "(ZII)V");
    if (!session.completion) return 0;
    session.callback = (*env)->NewGlobalRef(env, callback);
    if (!session.callback) return 0;
    session.owner = owner;
    session.defaultBase = defaultBase;
    session.defaultZoom = defaultZoom;
    session.panel = [[NSPanel alloc] initWithContentRect:NSMakeRect(0, 0, 480, 250)
            styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
            backing:NSBackingStoreBuffered defer:NO];
    session.panel.title = @"Iteration Settings";
    session.panel.releasedWhenClosed = NO;
    session.panel.delegate = session;
    session.panel.backgroundColor = NSColor.windowBackgroundColor;
    NSTextField *heading = label(@"Iteration Settings");
    heading.font = [NSFont boldSystemFontOfSize:15];
    NSTextField *hint = label(@"Set the base render budget and how much it grows as you zoom in.");
    hint.textColor = NSColor.secondaryLabelColor;
    session.base = [NSTextField textFieldWithString:[NSString stringWithFormat:@"%d", base]];
    session.zoom = [NSTextField textFieldWithString:[NSString stringWithFormat:@"%d", zoom]];
    session.base.identifier = @"iteration-base";
    session.zoom.identifier = @"iteration-zoom";
    session.base.accessibilityLabel = @"Base iterations";
    session.zoom.accessibilityLabel = @"Additional per zoom level";
    session.base.delegate = session;
    session.zoom.delegate = session;
    [(NSTextFieldCell *)session.base.cell setSendsActionOnEndEditing:NO];
    [(NSTextFieldCell *)session.zoom.cell setSendsActionOnEndEditing:NO];
    session.base.target = session;
    session.base.action = @selector(apply:);
    session.zoom.target = session;
    session.zoom.action = @selector(apply:);
    NSGridView *grid = [NSGridView gridViewWithViews:@[
        @[label(@"Base iterations"), session.base],
        @[label(@"Additional per zoom level"), session.zoom]]];
    grid.rowSpacing = 12;
    grid.columnSpacing = 12;
    [grid columnAtIndex:0].width = 180;
    [grid columnAtIndex:0].xPlacement = NSGridCellPlacementLeading;
    [grid columnAtIndex:1].xPlacement = NSGridCellPlacementFill;
    grid.yPlacement = NSGridCellPlacementCenter;
    session.error = label(@"");
    session.error.identifier = @"dialog-validation-error";
    NSButton *reset = button(@"Reset to Defaults", session, @selector(reset:));
    NSButton *cancel = button(@"Cancel", session, @selector(cancel:));
    NSButton *apply = button(@"Apply", session, @selector(apply:));
    cancel.keyEquivalent = @"\033";
    apply.keyEquivalent = @"\r";
    NSView *spacer = [NSView new];
    [spacer setContentHuggingPriority:1 forOrientation:NSLayoutConstraintOrientationHorizontal];
    NSStackView *actions = [NSStackView stackViewWithViews:@[reset, spacer, cancel, apply]];
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
    session.base.nextKeyView = session.zoom;
    session.zoom.nextKeyView = reset;
    reset.nextKeyView = cancel;
    cancel.nextKeyView = apply;
    apply.nextKeyView = session.base;
    session.panel.initialFirstResponder = session.base;
    [owner beginSheet:session.panel completionHandler:^(NSModalResponse response) {
        session.finished = YES;
        [session.panel orderOut:nil];
        if (session.cancelled) return;
        int resultBase = 0, resultZoom = 0;
        BOOL accepted = response == NSModalResponseOK
                && parseCount(session.base.stringValue, YES, &resultBase)
                && parseCount(session.zoom.stringValue, NO, &resultZoom);
        BOOL attached;
        JNIEnv *callbackEnv = sheetEnvironment(session.vm, &attached);
        if (callbackEnv) {
            (*callbackEnv)->CallVoidMethod(callbackEnv, session.callback, session.completion,
                    (jboolean)accepted, (jint)resultBase, (jint)resultZoom);
            if ((*callbackEnv)->ExceptionCheck(callbackEnv)) {
                (*callbackEnv)->ExceptionDescribe(callbackEnv);
                (*callbackEnv)->ExceptionClear(callbackEnv);
            }
            if (attached) (*session.vm)->DetachCurrentThread(session.vm);
        }
    }];
    [session.base selectText:nil];
    return (jlong)(intptr_t)(__bridge_retained void *)session;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacIterationSheet_dispose
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!sheetMainThread(env) || !handle) return;
    FractalIterationSession *session = (__bridge_transfer FractalIterationSession *)(void *)(intptr_t)handle;
    session.cancelled = YES;
    if (!session.finished) [session.owner endSheet:session.panel returnCode:NSModalResponseCancel];
}
