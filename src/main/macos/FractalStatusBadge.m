#import <Cocoa/Cocoa.h>
#include <jni.h>

// Glass runs JavaFX on the AppKit main thread on macOS; every entry point runs there.
static BOOL requireBadgeMainThread(JNIEnv *env) {
    if ([NSThread isMainThread]) return YES;
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                    "AppKit status badge requires the main thread");
    return NO;
}

static NSString *string(JNIEnv *env, jstring value) {
    if (!value) return @"";
    const jchar *chars = (*env)->GetStringChars(env, value, NULL);
    if (!chars) return @"";
    NSString *result = [NSString stringWithCharacters:chars length:(NSUInteger)(*env)->GetStringLength(env, value)];
    (*env)->ReleaseStringChars(env, value, chars);
    return result;
}

static const CGFloat kHorizontalPadding = 10;
static const CGFloat kIconSize = 12;
static const CGFloat kIconGap = 7;
static const CGFloat kHeight = 26;

// Informational overlay above the JavaFX content. It swallows pointer input so
// hovering or scrolling over it never pans or zooms the fractal underneath.
@interface FractalStatusBadge : NSView
@property NSView *material;
@property NSView *content;
@property NSView *dot;
@property NSProgressIndicator *spinner;
@property NSTextField *label;
@property CGFloat left;
@property CGFloat bottom;
@end

@implementation FractalStatusBadge
- (instancetype)init {
    self = [super initWithFrame:NSMakeRect(0, 0, 120, kHeight)];
    if (!self) return nil;
    self.content = [[NSView alloc] initWithFrame:self.bounds];
    self.content.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
#if defined(__MAC_26_0) && __MAC_OS_X_VERSION_MAX_ALLOWED >= __MAC_26_0
    // Liquid Glass, as used by system overlays and toolbars since macOS 26. It
    // needs both an SDK that declares it and a macOS 26 runtime.
    if (@available(macOS 26.0, *)) {
        NSGlassEffectView *glass = [[NSGlassEffectView alloc] initWithFrame:self.bounds];
        glass.cornerRadius = kHeight / 2;
        glass.contentView = self.content;
        self.material = glass;
    }
#endif
    if (!self.material) {
        NSVisualEffectView *effect = [[NSVisualEffectView alloc] initWithFrame:self.bounds];
        effect.material = NSVisualEffectMaterialPopover;
        effect.blendingMode = NSVisualEffectBlendingModeWithinWindow;
        effect.state = NSVisualEffectStateActive;
        effect.wantsLayer = YES;
        effect.layer.cornerRadius = kHeight / 2;
        effect.layer.masksToBounds = YES;
        [effect addSubview:self.content];
        self.material = effect;
    }
    self.material.autoresizingMask = NSViewWidthSizable | NSViewHeightSizable;
    [self addSubview:self.material];

    CGFloat iconY = (kHeight - kIconSize) / 2;
    self.dot = [[NSView alloc] initWithFrame:NSMakeRect(kHorizontalPadding + 2.5, iconY + 2.5, 7, 7)];
    self.dot.wantsLayer = YES;
    self.dot.layer.cornerRadius = 3.5;
    [self.content addSubview:self.dot];
    self.spinner = [[NSProgressIndicator alloc]
            initWithFrame:NSMakeRect(kHorizontalPadding, iconY, kIconSize, kIconSize)];
    self.spinner.style = NSProgressIndicatorStyleSpinning;
    self.spinner.controlSize = NSControlSizeMini;
    self.spinner.indeterminate = YES;
    self.spinner.displayedWhenStopped = NO;
    [self.content addSubview:self.spinner];

    self.label = [NSTextField labelWithString:@""];
    // Tabular digits keep the text from shifting while percentages change.
    self.label.font = [NSFont monospacedDigitSystemFontOfSize:
            [NSFont systemFontSizeForControlSize:NSControlSizeSmall] weight:NSFontWeightMedium];
    self.label.textColor = NSColor.labelColor;
    self.label.lineBreakMode = NSLineBreakByClipping;
    self.label.selectable = NO;
    [self.content addSubview:self.label];

    self.left = 12;
    self.bottom = 12;
    self.accessibilityElement = YES;
    self.accessibilityRole = NSAccessibilityStaticTextRole;
    return self;
}
- (NSView *)hitTest:(NSPoint)point {
    return NSPointInRect([self convertPoint:point fromView:self.superview], self.bounds) ? self : nil;
}
- (BOOL)acceptsFirstMouse:(NSEvent *)event { (void)event; return NO; }
- (void)mouseDown:(NSEvent *)event { (void)event; }
- (void)rightMouseDown:(NSEvent *)event { (void)event; }
- (void)otherMouseDown:(NSEvent *)event { (void)event; }
- (void)scrollWheel:(NSEvent *)event { (void)event; }
- (void)magnifyWithEvent:(NSEvent *)event { (void)event; }
- (void)rotateWithEvent:(NSEvent *)event { (void)event; }
- (NSMenu *)menuForEvent:(NSEvent *)event { (void)event; return nil; }

- (void)place {
    NSView *parent = self.superview;
    if (!parent) return;
    NSRect bounds = parent.bounds;
    NSRect frame = self.frame;
    frame.origin.x = NSMinX(bounds) + self.left;
    // JavaFX gives logical scene points; only the flipped axis needs mirroring.
    frame.origin.y = parent.isFlipped ? NSMaxY(bounds) - self.bottom - kHeight : NSMinY(bounds) + self.bottom;
    self.frame = frame;
    self.autoresizingMask = NSViewMaxXMargin | (parent.isFlipped ? NSViewMinYMargin : NSViewMaxYMargin);
}

- (void)showText:(NSString *)text reserving:(NSString *)widest {
    self.label.stringValue = widest;
    CGFloat reserved = ceil(self.label.fittingSize.width);
    self.label.stringValue = text;
    CGFloat textWidth = MAX(reserved, ceil(self.label.fittingSize.width));
    CGFloat labelX = kHorizontalPadding + kIconSize + kIconGap;
    CGFloat labelHeight = ceil(self.label.fittingSize.height);
    self.label.frame = NSMakeRect(labelX, floor((kHeight - labelHeight) / 2), textWidth, labelHeight);
    NSRect frame = self.frame;
    frame.size = NSMakeSize(labelX + textWidth + kHorizontalPadding, kHeight);
    self.frame = frame;
    [self place];
}
@end

JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacStatusBadge_create
(JNIEnv *env, jclass type, jlong windowHandle) {
    (void)type;
    if (!requireBadgeMainThread(env) || !windowHandle) return 0;
    NSWindow *window = (__bridge NSWindow *)(void *)(intptr_t)windowHandle;
    NSView *parent = window.contentView;
    if (!parent) return 0;
    FractalStatusBadge *badge = [FractalStatusBadge new];
    [parent addSubview:badge positioned:NSWindowAbove relativeTo:nil];
    [badge place];
    return (jlong)(intptr_t)(__bridge_retained void *)badge;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacStatusBadge_update
(JNIEnv *env, jclass type, jlong handle, jstring text, jstring widest, jstring tooltip,
 jstring accessibility, jint rgb, jboolean rendering) {
    (void)type;
    if (!requireBadgeMainThread(env) || !handle) return;
    FractalStatusBadge *badge = (__bridge FractalStatusBadge *)(void *)(intptr_t)handle;
    [badge showText:string(env, text) reserving:string(env, widest)];
    badge.toolTip = string(env, tooltip);
    badge.accessibilityLabel = string(env, accessibility);
    badge.dot.layer.backgroundColor = [NSColor colorWithSRGBRed:((rgb >> 16) & 0xff) / 255.0
            green:((rgb >> 8) & 0xff) / 255.0 blue:(rgb & 0xff) / 255.0 alpha:1].CGColor;
    badge.dot.hidden = rendering;
    if (rendering) [badge.spinner startAnimation:nil];
    else [badge.spinner stopAnimation:nil];
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacStatusBadge_place
(JNIEnv *env, jclass type, jlong handle, jdouble left, jdouble bottom) {
    (void)type;
    if (!requireBadgeMainThread(env) || !handle) return;
    FractalStatusBadge *badge = (__bridge FractalStatusBadge *)(void *)(intptr_t)handle;
    badge.left = left;
    badge.bottom = bottom;
    [badge place];
}

// Test contract: {topmost (1/0), x, distance from bottom, width, height} in
// logical points relative to the window's content view.
JNIEXPORT jdoubleArray JNICALL Java_com_shangin_fractal_ui_MacStatusBadge_state
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!requireBadgeMainThread(env) || !handle) return NULL;
    FractalStatusBadge *badge = (__bridge FractalStatusBadge *)(void *)(intptr_t)handle;
    NSView *parent = badge.superview;
    if (!parent) return NULL;
    NSRect frame = badge.frame;
    NSRect bounds = parent.bounds;
    jdouble values[] = {
        parent.subviews.lastObject == badge ? 1 : 0,
        NSMinX(frame) - NSMinX(bounds),
        parent.isFlipped ? NSMaxY(bounds) - NSMaxY(frame) : NSMinY(frame) - NSMinY(bounds),
        NSWidth(frame), NSHeight(frame)
    };
    jdoubleArray result = (*env)->NewDoubleArray(env, 5);
    if (result) (*env)->SetDoubleArrayRegion(env, result, 0, 5, values);
    return result;
}

// Keeps the badge above AppKit overlays that are attached later, such as the loading material.
JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacStatusBadge_raise
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!requireBadgeMainThread(env) || !handle) return;
    FractalStatusBadge *badge = (__bridge FractalStatusBadge *)(void *)(intptr_t)handle;
    NSView *parent = badge.superview;
    if (parent) [parent addSubview:badge positioned:NSWindowAbove relativeTo:nil];
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacStatusBadge_dispose
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!requireBadgeMainThread(env) || !handle) return;
    FractalStatusBadge *badge = (__bridge_transfer FractalStatusBadge *)(void *)(intptr_t)handle;
    [badge.spinner stopAnimation:nil];
    [badge removeFromSuperview];
}
