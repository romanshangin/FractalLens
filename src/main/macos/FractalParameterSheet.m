#import <Cocoa/Cocoa.h>
#include <jni.h>

@interface FractalParameterSession : NSObject <NSWindowDelegate, NSTextFieldDelegate>
- (void)cancel:(id)sender;
- (void)focusFirstChoice;
@property NSWindow *panel;
@property NSAlert *alert;
@property id escapeMonitor;
@property NSImageView *preview;
@property NSTextField *estimate;
@property BOOL modeless;
@property NSMutableArray<NSTextField *> *positions;
@property NSMutableArray<NSColorWell *> *colors;
@property NSStackView *paletteRows;
@property NSMutableArray<NSButton *> *removeButtons;
@property NSMutableArray<NSPopUpButton *> *choices;
@property NSArray<NSNumber *> *choiceDefaults;
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

/** Modeless panels route Escape through the same cancellation lifecycle as buttons. */
@interface FractalPanel : NSPanel
@end
@implementation FractalPanel
- (void)cancelOperation:(id)sender {
    [(FractalParameterSession *)self.delegate cancel:sender];
}
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

static NSString *firstDraft(FractalParameterSession *session) {
    if (session.choices) {
        NSMutableArray *drafts = [NSMutableArray new];
        for (NSPopUpButton *choice in session.choices)
            [drafts addObject:[NSString stringWithFormat:@"%ld", (long)choice.indexOfSelectedItem]];
        return [drafts componentsJoinedByString:@","];
    }
    if (!session.positions) return session.first ? session.first.stringValue : @"";
    NSMutableArray *drafts = [NSMutableArray new];
    for (NSTextField *field in session.positions) [drafts addObject:field.stringValue];
    return [drafts componentsJoinedByString:@"\n"];
}

static NSString *secondDraft(FractalParameterSession *session) {
    if (!session.colors) return session.second ? session.second.stringValue : @"";
    NSMutableArray *drafts = [NSMutableArray new];
    for (NSColorWell *well in session.colors) {
        NSColor *color = [well.color colorUsingColorSpace:NSColorSpace.sRGBColorSpace];
        uint32_t value = ((uint32_t)lround(color.alphaComponent * 255) << 24)
                | ((uint32_t)lround(color.redComponent * 255) << 16)
                | ((uint32_t)lround(color.greenComponent * 255) << 8)
                | (uint32_t)lround(color.blueComponent * 255);
        [drafts addObject:[NSString stringWithFormat:@"%d", (int32_t)value]];
    }
    return [drafts componentsJoinedByString:@"\n"];
}

static void complete(FractalParameterSession *session, BOOL accepted) {
    if (session.escapeMonitor) {
        [NSEvent removeMonitor:session.escapeMonitor];
        session.escapeMonitor = nil;
    }
    session.finished = YES;
    [session.panel orderOut:nil];
    for (NSColorWell *well in session.colors) {
        if (well.isActive) [[NSColorPanel sharedColorPanel] orderOut:nil];
        [well deactivate];
    }
    if (session.cancelled) return;
    BOOL attached;
    JNIEnv *env = sheetEnvironment(session.vm, &attached);
    if (env) {
        jstring first = javaString(env, firstDraft(session));
        jstring second = javaString(env, secondDraft(session));
        if (first && second && !(*env)->ExceptionCheck(env))
            (*env)->CallVoidMethod(env, session.callback, session.completion, (jboolean)accepted, first, second);
        if (first) (*env)->DeleteLocalRef(env, first);
        if (second) (*env)->DeleteLocalRef(env, second);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        if (attached) (*session.vm)->DetachCurrentThread(session.vm);
    }
}

// Parsing stays in Java so the native and fallback editors accept identical values.
static jint invalidField(FractalParameterSession *session) {
    BOOL attached;
    JNIEnv *env = sheetEnvironment(session.vm, &attached);
    if (!env) return 1;
    jstring first = javaString(env, firstDraft(session));
    jstring second = javaString(env, secondDraft(session));
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
- (void)updatePreview {
    if (!self.preview && !self.estimate) return;
    BOOL attached;
    JNIEnv *env = sheetEnvironment(self.vm, &attached);
    if (!env) return;
    jclass type = (*env)->GetObjectClass(env, self.callback);
    jstring first = javaString(env, firstDraft(self));
    jstring second = javaString(env, secondDraft(self));
    if (self.preview) {
        jmethodID method = (*env)->GetMethodID(env, type, "preview", "(Ljava/lang/String;Ljava/lang/String;)[I");
        jintArray pixels = NULL;
        if (method && first && second && !(*env)->ExceptionCheck(env))
            pixels = (*env)->CallObjectMethod(env, self.callback, method, first, second);
        if (pixels && !(*env)->ExceptionCheck(env)) {
            jsize count = (*env)->GetArrayLength(env, pixels);
            jint *colors = (*env)->GetIntArrayElements(env, pixels, NULL);
            if (count && colors) {
                NSBitmapImageRep *rep = [[NSBitmapImageRep alloc] initWithBitmapDataPlanes:NULL
                        pixelsWide:count pixelsHigh:1 bitsPerSample:8 samplesPerPixel:4 hasAlpha:YES
                        isPlanar:NO colorSpaceName:NSDeviceRGBColorSpace
                        bitmapFormat:NSBitmapFormatAlphaNonpremultiplied bytesPerRow:count * 4 bitsPerPixel:32];
                unsigned char *bytes = rep.bitmapData;
                for (jsize i = 0; i < count; i++) {
                    bytes[i * 4] = (colors[i] >> 16) & 255;
                    bytes[i * 4 + 1] = (colors[i] >> 8) & 255;
                    bytes[i * 4 + 2] = colors[i] & 255;
                    bytes[i * 4 + 3] = ((uint32_t)colors[i] >> 24) & 255;
                }
                NSImage *image = [[NSImage alloc] initWithSize:NSMakeSize(count, 1)];
                [image addRepresentation:rep];
                self.preview.image = image;
            }
            if (colors) (*env)->ReleaseIntArrayElements(env, pixels, colors, JNI_ABORT);
            (*env)->DeleteLocalRef(env, pixels);
        }
    }
    if (self.estimate && !(*env)->ExceptionCheck(env)) {
        jmethodID method = (*env)->GetMethodID(env, type, "estimate", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        jstring result = NULL;
        if (method && first && second && !(*env)->ExceptionCheck(env))
            result = (*env)->CallObjectMethod(env, self.callback, method, first, second);
        if (result && !(*env)->ExceptionCheck(env)) {
            self.estimate.stringValue = nativeString(env, result);
            self.estimate.hidden = self.estimate.stringValue.length == 0;
            (*env)->DeleteLocalRef(env, result);
        }
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if (first) (*env)->DeleteLocalRef(env, first);
    if (second) (*env)->DeleteLocalRef(env, second);
    (*env)->DeleteLocalRef(env, type);
    if (attached) (*self.vm)->DetachCurrentThread(self.vm);
}
- (void)colorChanged:(id)sender {
    (void)sender;
    [self updatePreview];
}
- (void)apply:(id)sender {
    (void)sender;
    jint invalidIndex = invalidField(self);
    if (invalidIndex != 0) {
        self.error.stringValue = self.validationError;
        NSTextField *invalid = self.positions ? self.positions[MIN((NSUInteger)invalidIndex - 1, self.positions.count - 1)]
                : invalidIndex == 1 ? self.first : self.second;
        [self.panel makeFirstResponder:invalid];
        [invalid selectText:nil];
        return;
    }
    if (self.modeless) complete(self, YES);
    else [self.owner endSheet:self.panel returnCode:NSModalResponseOK];
}
- (void)cancel:(id)sender {
    (void)sender;
    if (self.modeless) complete(self, NO);
    else [self.owner endSheet:self.panel returnCode:NSModalResponseCancel];
}
- (void)reset:(id)sender {
    (void)sender;
    self.first.stringValue = self.defaultFirst;
    self.second.stringValue = self.defaultSecond;
    self.error.stringValue = @"";
    [self.panel makeFirstResponder:self.first];
    [self.first selectText:nil];
}
- (void)resetChoices:(id)sender {
    (void)sender;
    // Reset only changes the draft; Apply still decides whether the scene changes.
    for (NSUInteger i = 0; i < self.choices.count; i++)
        [self.choices[i] selectItemAtIndex:self.choiceDefaults[i].integerValue];
    [self focusFirstChoice];
}
- (void)focusFirstChoice {
    // With macOS Keyboard navigation off, pop-up buttons are not key views; forcing focus
    // onto one would leave its focus ring in place while clicks select other controls.
    NSPopUpButton *first = self.choices.firstObject;
    [self.panel makeFirstResponder:first.canBecomeKeyView ? first : nil];
}
- (void)appendPosition:(NSString *)position color:(int32_t)argb {
    NSTextField *field = [NSTextField textFieldWithString:position];
    field.accessibilityLabel = @"Color stop position, from 0 to 1";
    field.delegate = self;
    [(NSTextFieldCell *)field.cell setSendsActionOnEndEditing:NO];
    field.target = self;
    field.action = @selector(apply:);
    NSColorWell *well = [NSColorWell new];
    well.color = [NSColor colorWithSRGBRed:((argb >> 16) & 255) / 255.0
            green:((argb >> 8) & 255) / 255.0 blue:(argb & 255) / 255.0
            alpha:((uint32_t)argb >> 24) / 255.0];
    well.accessibilityLabel = @"Color stop color";
    [NSColorPanel sharedColorPanel].showsAlpha = YES;
    well.target = self;
    well.action = @selector(colorChanged:);
    NSButton *remove = [NSButton buttonWithTitle:@"−" target:self action:@selector(removeStop:)];
    remove.accessibilityLabel = @"Remove color stop";
    NSStackView *row = [NSStackView stackViewWithViews:@[field, well, remove]];
    row.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    row.spacing = 12;
    [field.widthAnchor constraintEqualToConstant:160].active = YES;
    [well.widthAnchor constraintEqualToConstant:100].active = YES;
    [well.heightAnchor constraintEqualToConstant:28].active = YES;
    [self.positions addObject:field];
    [self.colors addObject:well];
    [self.removeButtons addObject:remove];
    [self.paletteRows addArrangedSubview:row];
    for (NSButton *button in self.removeButtons) button.enabled = self.positions.count > 2;
}
- (void)removeStop:(NSButton *)sender {
    NSUInteger index = [self.removeButtons indexOfObject:sender];
    if (index == NSNotFound || self.positions.count <= 2) return;
    [self.colors[index] deactivate];
    NSView *row = self.paletteRows.arrangedSubviews[index];
    [self.paletteRows removeArrangedSubview:row];
    [row removeFromSuperview];
    [self.positions removeObjectAtIndex:index];
    [self.colors removeObjectAtIndex:index];
    [self.removeButtons removeObjectAtIndex:index];
    for (NSButton *button in self.removeButtons) button.enabled = self.positions.count > 2;
    NSTextField *field = self.positions[MIN(index, self.positions.count - 1)];
    [self.panel makeFirstResponder:field];
    [field selectText:nil];
    if (invalidField(self) == 0) self.error.stringValue = @"";
    [self updatePreview];
}
- (void)addStop:(id)sender {
    (void)sender;
    if (invalidField(self) != 0) {
        self.error.stringValue = self.validationError;
        return;
    }
    BOOL attached;
    JNIEnv *env = sheetEnvironment(self.vm, &attached);
    if (!env) return;
    jclass type = (*env)->GetObjectClass(env, self.callback);
    jmethodID method = (*env)->GetMethodID(env, type, "addedStop", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    jstring first = javaString(env, firstDraft(self));
    jstring second = javaString(env, secondDraft(self));
    jstring result = NULL;
    if (method && first && second && !(*env)->ExceptionCheck(env))
        result = (*env)->CallObjectMethod(env, self.callback, method, first, second);
    if (result && !(*env)->ExceptionCheck(env)) {
        NSArray *parts = [nativeString(env, result) componentsSeparatedByString:@"\n"];
        if (parts.count == 2) {
            [self appendPosition:parts[0] color:[parts[1] intValue]];
            [self.panel makeFirstResponder:self.positions.lastObject];
            [self.positions.lastObject selectText:nil];
            [self.positions.lastObject scrollRectToVisible:self.positions.lastObject.bounds];
        }
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if (first) (*env)->DeleteLocalRef(env, first);
    if (second) (*env)->DeleteLocalRef(env, second);
    if (result) (*env)->DeleteLocalRef(env, result);
    (*env)->DeleteLocalRef(env, type);
    if (attached) (*self.vm)->DetachCurrentThread(self.vm);
    [self updatePreview];
}
- (void)controlTextDidChange:(NSNotification *)notification {
    (void)notification;
    if (invalidField(self) == 0)
        self.error.stringValue = @"";
    [self updatePreview];
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
 jint kind, jstring confirmTitle) {
    BOOL singleField = kind == 1;
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
    session.modeless = kind == 3;
    session.defaultFirst = nativeString(env, defaultFirst);
    session.defaultSecond = nativeString(env, defaultSecond);
    session.validationError = nativeString(env, errorText);
    session.panel = [[FractalPanel alloc] initWithContentRect:NSMakeRect(0, 0, 480, 250)
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
    hint.selectable = YES;
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
    NSStackView *actions = [NSStackView stackViewWithViews:kind != 0
            ? @[spacer, cancel, apply] : @[reset, spacer, cancel, apply]];
    actions.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    actions.spacing = 8;
    NSView *editor = grid;
    if (kind == 3) {
        session.positions = [NSMutableArray new];
        session.colors = [NSMutableArray new];
        session.removeButtons = [NSMutableArray new];
        session.paletteRows = [NSStackView stackViewWithViews:@[]];
        session.paletteRows.orientation = NSUserInterfaceLayoutOrientationVertical;
        session.paletteRows.alignment = NSLayoutAttributeLeading;
        session.paletteRows.spacing = 8;
        NSArray *positions = [nativeString(env, first) componentsSeparatedByString:@"\n"];
        NSArray *colors = [nativeString(env, second) componentsSeparatedByString:@"\n"];
        for (NSUInteger i = 0; i < positions.count; i++)
            [session appendPosition:positions[i] color:[colors[i] intValue]];
        NSScrollView *scroll = [NSScrollView new];
        scroll.hasVerticalScroller = YES;
        NSView *document = [NSView new];
        scroll.documentView = document;
        session.paletteRows.translatesAutoresizingMaskIntoConstraints = NO;
        [document addSubview:session.paletteRows];
        [NSLayoutConstraint activateConstraints:@[
            [session.paletteRows.leadingAnchor constraintEqualToAnchor:document.leadingAnchor constant:4],
            [session.paletteRows.topAnchor constraintEqualToAnchor:document.topAnchor constant:4],
            [session.paletteRows.bottomAnchor constraintEqualToAnchor:document.bottomAnchor constant:-4],
            [session.paletteRows.trailingAnchor constraintEqualToAnchor:document.trailingAnchor constant:-4]]];
        document.translatesAutoresizingMaskIntoConstraints = NO;
        [document.widthAnchor constraintEqualToAnchor:scroll.contentView.widthAnchor].active = YES;
        [scroll.heightAnchor constraintEqualToConstant:220].active = YES;
        NSButton *add = button(@"Add Color Stop", session, @selector(addStop:));
        session.preview = [NSImageView new];
        session.preview.imageScaling = NSImageScaleAxesIndependently;
        session.preview.accessibilityLabel = @"Live palette gradient preview";
        [session.preview.heightAnchor constraintEqualToConstant:28].active = YES;
        NSStackView *palette = [NSStackView stackViewWithViews:@[session.preview, scroll, add]];
        palette.orientation = NSUserInterfaceLayoutOrientationVertical;
        palette.alignment = NSLayoutAttributeLeading;
        palette.spacing = 8;
        [scroll.widthAnchor constraintEqualToAnchor:palette.widthAnchor].active = YES;
        [session.preview.widthAnchor constraintEqualToAnchor:palette.widthAnchor].active = YES;
        editor = palette;
    }
    session.estimate = label(@"");
    session.estimate.textColor = NSColor.secondaryLabelColor;
    session.estimate.hidden = YES;
    NSStackView *body = [NSStackView stackViewWithViews:@[heading, hint, editor, session.estimate, session.error, actions]];
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
        [editor.widthAnchor constraintEqualToAnchor:body.widthAnchor],
        [session.estimate.widthAnchor constraintEqualToAnchor:body.widthAnchor],
        [session.error.widthAnchor constraintEqualToAnchor:body.widthAnchor],
        [session.error.heightAnchor constraintGreaterThanOrEqualToConstant:32],
        [actions.widthAnchor constraintEqualToAnchor:body.widthAnchor]]];
    session.first.nextKeyView = singleField ? cancel : session.second;
    session.second.nextKeyView = kind == 0 ? reset : cancel;
    reset.nextKeyView = cancel;
    cancel.nextKeyView = apply;
    apply.nextKeyView = session.first;
    if (kind == 3) [session.panel recalculateKeyViewLoop];
    session.panel.initialFirstResponder = kind == 3 ? session.positions.firstObject : session.first;
    [session updatePreview];
    if (session.modeless) {
        [owner addChildWindow:session.panel ordered:NSWindowAbove];
        [session.panel center];
        [session.panel makeKeyAndOrderFront:nil];
    } else {
        [owner beginSheet:session.panel completionHandler:^(NSModalResponse response) {
            complete(session, response == NSModalResponseOK && invalidField(session) == 0);
        }];
    }
    [(kind == 3 ? session.positions.firstObject : session.first) selectText:nil];
    return (jlong)(intptr_t)(__bridge_retained void *)session;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacParameterSheet_dispose
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!sheetMainThread(env) || !handle) return;
    FractalParameterSession *session = (__bridge_transfer FractalParameterSession *)(void *)(intptr_t)handle;
    session.cancelled = YES;
    if (session.escapeMonitor) {
        [NSEvent removeMonitor:session.escapeMonitor];
        session.escapeMonitor = nil;
    }
    for (NSColorWell *well in session.colors) {
        if (well.isActive) [[NSColorPanel sharedColorPanel] orderOut:nil];
        [well deactivate];
    }
    if (session.modeless) [session.owner removeChildWindow:session.panel];
    if (!session.finished) {
        if (session.modeless) [session.panel orderOut:nil];
        else [session.owner endSheet:session.panel returnCode:NSModalResponseCancel];
    }
}

JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacParameterSheet_openNotice
(JNIEnv *env, jobject callback, jlong windowHandle, jstring title, jstring message,
 jboolean confirmation, jboolean modeless) {
    if (!sheetMainThread(env)) return 0;
    NSWindow *owner = (__bridge NSWindow *)(void *)(intptr_t)windowHandle;
    if (!owner.isVisible || (!modeless && owner.attachedSheet)) return 0;
    FractalParameterSession *session = [FractalParameterSession new];
    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    session.vm = vm;
    session.completion = (*env)->GetMethodID(env, (*env)->GetObjectClass(env, callback),
            "completed", "(ZLjava/lang/String;Ljava/lang/String;)V");
    if (!session.completion) return 0;
    session.callback = (*env)->NewGlobalRef(env, callback);
    if (!session.callback) return 0;
    session.owner = owner;
    session.modeless = modeless;
    if (modeless) {
        session.panel = [[FractalPanel alloc] initWithContentRect:NSMakeRect(0, 0, 520, 420)
                styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
                backing:NSBackingStoreBuffered defer:NO];
        session.panel.title = nativeString(env, title);
        session.panel.releasedWhenClosed = NO;
        session.panel.delegate = session;
        NSScrollView *scroll = [[NSScrollView alloc] initWithFrame:session.panel.contentView.bounds];
        scroll.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
        scroll.hasVerticalScroller = YES;
        NSTextView *text = [[NSTextView alloc] initWithFrame:scroll.bounds];
        text.string = nativeString(env, message);
        text.editable = NO;
        text.font = [NSFont systemFontOfSize:NSFont.systemFontSize];
        text.textColor = NSColor.labelColor;
        text.backgroundColor = NSColor.windowBackgroundColor;
        text.textContainerInset = NSMakeSize(20, 20);
        text.autoresizingMask = NSViewWidthSizable;
        text.textContainer.widthTracksTextView = YES;
        scroll.documentView = text;
        [session.panel.contentView addSubview:scroll];
        [owner addChildWindow:session.panel ordered:NSWindowAbove];
        [session.panel center];
        [session.panel makeKeyAndOrderFront:nil];
    } else {
        session.alert = [NSAlert new];
        session.alert.messageText = nativeString(env, title);
        session.alert.informativeText = nativeString(env, message);
        session.alert.alertStyle = NSAlertStyleWarning;
        [session.alert addButtonWithTitle:confirmation ? @"No, Return" : @"OK"];
        if (confirmation) [session.alert addButtonWithTitle:@"Yes, Close"];
        session.alert.buttons[0].keyEquivalent = @"\r";
        session.panel = session.alert.window;
        [session.panel setDefaultButtonCell:session.alert.buttons[0].cell];
        [session.alert beginSheetModalForWindow:owner completionHandler:^(NSModalResponse response) {
            complete(session, response == (confirmation ? NSAlertSecondButtonReturn : NSAlertFirstButtonReturn));
        }];
        if (confirmation) {
            // One button key equivalent cannot provide both Return and Escape.
            // Route Escape to the safe button while preserving the default Return action.
            __weak FractalParameterSession *weakSession = session;
            session.escapeMonitor = [NSEvent addLocalMonitorForEventsMatchingMask:NSEventMaskKeyDown
                    handler:^NSEvent *(NSEvent *event) {
                FractalParameterSession *active = weakSession;
                NSEventModifierFlags shortcuts = NSEventModifierFlagCommand | NSEventModifierFlagControl
                        | NSEventModifierFlagOption | NSEventModifierFlagShift;
                if (active && !active.finished && !active.cancelled && event.window == active.panel
                        && event.keyCode == 53 && !(event.modifierFlags & shortcuts)) {
                    [active.alert.buttons[0] performClick:nil];
                    return nil;
                }
                return event;
            }];
        }
    }
    return (jlong)(intptr_t)(__bridge_retained void *)session;
}

static NSArray<NSString *> *nativeStrings(JNIEnv *env, jobjectArray values) {
    if (!values) return nil;
    jsize count = (*env)->GetArrayLength(env, values);
    NSMutableArray<NSString *> *result = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
    for (jsize i = 0; i < count; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, values, i);
        NSString *text = value ? nativeString(env, value) : nil;
        if (value) (*env)->DeleteLocalRef(env, value);
        if (!text) return nil;
        [result addObject:text];
    }
    return result;
}

// Settings text is not selectable: a clicked selectable label keeps keyboard focus,
// so Tab would select its text instead of moving between the controls.
static NSTextField *staticLabel(NSString *text) {
    NSTextField *view = label(text);
    view.selectable = NO;
    return view;
}

static NSTextField *sectionLabel(NSString *text) {
    NSTextField *view = staticLabel(text);
    view.font = [NSFont boldSystemFontOfSize:NSFont.systemFontSize];
    return view;
}

// Sections of labelled pop-up buttons; Java parses the comma-separated selected indices.
JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacParameterSheet_openChoices
(JNIEnv *env, jobject callback, jlong windowHandle, jstring title, jstring hintText,
 jobjectArray sectionTitles, jobjectArray fieldLabels, jobjectArray fieldIds, jobjectArray fieldHints,
 jobjectArray fieldChoices, jintArray selectedIndices, jintArray defaultIndices, jstring resetTitle,
 jstring confirmTitle) {
    if (!sheetMainThread(env)) return 0;
    NSWindow *owner = (__bridge NSWindow *)(void *)(intptr_t)windowHandle;
    if (!owner.isVisible || owner.attachedSheet) return 0;
    NSArray<NSString *> *sections = nativeStrings(env, sectionTitles);
    NSArray<NSString *> *labels = nativeStrings(env, fieldLabels);
    NSArray<NSString *> *ids = nativeStrings(env, fieldIds);
    NSArray<NSString *> *hints = nativeStrings(env, fieldHints);
    NSUInteger count = sections.count;
    if (count == 0 || labels.count != count || ids.count != count || hints.count != count
            || (NSUInteger)(*env)->GetArrayLength(env, fieldChoices) != count
            || (NSUInteger)(*env)->GetArrayLength(env, selectedIndices) != count
            || (NSUInteger)(*env)->GetArrayLength(env, defaultIndices) != count) return 0;
    jint selected[count];
    jint defaults[count];
    (*env)->GetIntArrayRegion(env, selectedIndices, 0, (jsize)count, selected);
    (*env)->GetIntArrayRegion(env, defaultIndices, 0, (jsize)count, defaults);
    if ((*env)->ExceptionCheck(env)) return 0;
    NSMutableArray<NSNumber *> *choiceDefaults = [NSMutableArray arrayWithCapacity:count];
    NSMutableArray<NSPopUpButton *> *popups = [NSMutableArray arrayWithCapacity:count];
    for (NSUInteger i = 0; i < count; i++) {
        jobjectArray titles = (jobjectArray)(*env)->GetObjectArrayElement(env, fieldChoices, (jsize)i);
        NSArray<NSString *> *choices = nativeStrings(env, titles);
        if (titles) (*env)->DeleteLocalRef(env, titles);
        if (!choices || selected[i] < 0 || (NSUInteger)selected[i] >= choices.count
                || defaults[i] < 0 || (NSUInteger)defaults[i] >= choices.count) return 0;
        [choiceDefaults addObject:@(defaults[i])];
        NSPopUpButton *popup = [[NSPopUpButton alloc] initWithFrame:NSZeroRect pullsDown:NO];
        [popup addItemsWithTitles:choices];
        [popup selectItemAtIndex:selected[i]];
        popup.identifier = ids[i];
        popup.accessibilityLabel = labels[i];
        [popups addObject:popup];
    }
    FractalParameterSession *session = [FractalParameterSession new];
    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    session.vm = vm;
    jclass type = (*env)->GetObjectClass(env, callback);
    session.completion = (*env)->GetMethodID(env, type, "completed", "(ZLjava/lang/String;Ljava/lang/String;)V");
    session.validation = (*env)->GetMethodID(env, type, "invalidField", "(Ljava/lang/String;Ljava/lang/String;)I");
    (*env)->DeleteLocalRef(env, type);
    if (!session.completion || !session.validation) return 0;
    session.callback = (*env)->NewGlobalRef(env, callback);
    if (!session.callback) return 0;
    session.owner = owner;
    session.validationError = @"";
    session.choices = popups;
    session.choiceDefaults = choiceDefaults;
    session.panel = [[FractalPanel alloc] initWithContentRect:NSMakeRect(0, 0, 520, 300)
            styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable
            backing:NSBackingStoreBuffered defer:NO];
    session.panel.title = nativeString(env, title);
    session.panel.releasedWhenClosed = NO;
    session.panel.delegate = session;
    session.panel.backgroundColor = NSColor.windowBackgroundColor;
    NSTextField *heading = staticLabel(session.panel.title);
    heading.font = [NSFont boldSystemFontOfSize:15];
    NSTextField *hint = staticLabel(nativeString(env, hintText));
    hint.textColor = NSColor.secondaryLabelColor;
    NSMutableArray<NSView *> *rows = [NSMutableArray arrayWithObjects:heading, hint, nil];
    NSMutableArray<NSView *> *wide = [NSMutableArray arrayWithObject:hint];
    NSMutableArray<NSLayoutConstraint *> *constraints = [NSMutableArray new];
    NSLayoutDimension *labelWidth = nil;
    NSString *currentSection = nil;
    for (NSUInteger i = 0; i < count; i++) {
        if (![sections[i] isEqualToString:currentSection]) {
            currentSection = sections[i];
            [rows addObject:sectionLabel(currentSection)];
        }
        NSTextField *fieldLabel = [NSTextField labelWithString:[labels[i] stringByAppendingString:@":"]];
        fieldLabel.font = [NSFont systemFontOfSize:NSFont.systemFontSize];
        fieldLabel.alignment = NSTextAlignmentRight;
        [fieldLabel setContentHuggingPriority:NSLayoutPriorityRequired
                               forOrientation:NSLayoutConstraintOrientationHorizontal];
        // Labels share the widest width so the pop-up buttons line up as in System Settings.
        if (labelWidth) [constraints addObject:[fieldLabel.widthAnchor constraintEqualToAnchor:labelWidth]];
        else labelWidth = fieldLabel.widthAnchor;
        [constraints addObject:[fieldLabel.widthAnchor constraintGreaterThanOrEqualToConstant:
                fieldLabel.intrinsicContentSize.width]];
        NSStackView *row = [NSStackView stackViewWithViews:@[fieldLabel, popups[i]]];
        row.orientation = NSUserInterfaceLayoutOrientationHorizontal;
        row.alignment = NSLayoutAttributeFirstBaseline;
        row.spacing = 8;
        NSTextField *fieldHint = staticLabel(hints[i]);
        fieldHint.textColor = NSColor.secondaryLabelColor;
        fieldHint.font = [NSFont systemFontOfSize:NSFont.smallSystemFontSize];
        NSStackView *field = [NSStackView stackViewWithViews:@[row, fieldHint]];
        field.orientation = NSUserInterfaceLayoutOrientationVertical;
        field.alignment = NSLayoutAttributeLeading;
        field.spacing = 4;
        [constraints addObject:[fieldHint.leadingAnchor constraintEqualToAnchor:popups[i].leadingAnchor]];
        [constraints addObject:[fieldHint.trailingAnchor constraintEqualToAnchor:field.trailingAnchor]];
        [rows addObject:field];
        [wide addObject:field];
    }
    session.error = label(@"");
    NSButton *reset = button(nativeString(env, resetTitle), session, @selector(resetChoices:));
    NSButton *cancel = button(@"Cancel", session, @selector(cancel:));
    NSButton *apply = button(nativeString(env, confirmTitle), session, @selector(apply:));
    cancel.keyEquivalent = @"\033";
    apply.keyEquivalent = @"\r";
    NSView *spacer = [NSView new];
    [spacer setContentHuggingPriority:1 forOrientation:NSLayoutConstraintOrientationHorizontal];
    NSStackView *actions = [NSStackView stackViewWithViews:@[reset, spacer, cancel, apply]];
    actions.orientation = NSUserInterfaceLayoutOrientationHorizontal;
    actions.spacing = 8;
    [rows addObject:actions];
    [wide addObject:actions];
    NSStackView *body = [NSStackView stackViewWithViews:rows];
    body.orientation = NSUserInterfaceLayoutOrientationVertical;
    body.alignment = NSLayoutAttributeLeading;
    body.spacing = 12;
    [body setCustomSpacing:20 afterView:hint];
    [body setCustomSpacing:20 afterView:rows[rows.count - 2]];
    body.translatesAutoresizingMaskIntoConstraints = NO;
    [session.panel.contentView addSubview:body];
    for (NSView *view in wide) [constraints addObject:[view.widthAnchor constraintEqualToAnchor:body.widthAnchor]];
    [constraints addObjectsFromArray:@[
        [body.leadingAnchor constraintEqualToAnchor:session.panel.contentView.leadingAnchor constant:20],
        [body.trailingAnchor constraintEqualToAnchor:session.panel.contentView.trailingAnchor constant:-20],
        [body.topAnchor constraintEqualToAnchor:session.panel.contentView.topAnchor constant:20],
        [body.bottomAnchor constraintEqualToAnchor:session.panel.contentView.bottomAnchor constant:-20]]];
    [NSLayoutConstraint activateConstraints:constraints];
    [session.panel recalculateKeyViewLoop];
    if (popups[0].canBecomeKeyView) session.panel.initialFirstResponder = popups[0];
    [owner beginSheet:session.panel completionHandler:^(NSModalResponse response) {
        complete(session, response == NSModalResponseOK && invalidField(session) == 0);
    }];
    [session focusFirstChoice];
    return (jlong)(intptr_t)(__bridge_retained void *)session;
}
