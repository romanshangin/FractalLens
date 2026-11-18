#import <Cocoa/Cocoa.h>
#include <jni.h>

// Interesting Places as a nonmodal AppKit panel. Java owns the catalog, preview rendering
// and opening a place; this file only presents entries and reports the user's choice.

static NSUserInterfaceItemIdentifier const FractalGalleryItemIdentifier = @"FractalGalleryItem";
static NSUserInterfaceItemIdentifier const FractalGalleryHeaderIdentifier = @"FractalGalleryHeader";
static const CGFloat PreviewWidth = 240;
static const CGFloat PreviewHeight = 150;
static const CGFloat CardPadding = 8;

@class FractalGallerySession;

/** Card background: selection uses the system accent, resolved for the current appearance. */
@interface FractalGalleryCardView : NSView
@property (nonatomic) BOOL selected;
@end

@implementation FractalGalleryCardView
- (BOOL)wantsUpdateLayer { return YES; }
// The card, not its image view, receives clicks: an image view would consume the mouse
// down, and the collection view selects the item whose view the click hits.
- (NSView *)hitTest:(NSPoint)point {
    return [super hitTest:point] ? self : nil;
}
- (void)setSelected:(BOOL)selected {
    _selected = selected;
    self.needsDisplay = YES;
}
- (void)updateLayer {
    self.layer.cornerRadius = 8;
    self.layer.borderWidth = self.selected ? 3 : 0;
    [self.effectiveAppearance performAsCurrentDrawingAppearance:^{
        self.layer.borderColor = NSColor.controlAccentColor.CGColor;
        self.layer.backgroundColor = self.selected
                ? NSColor.unemphasizedSelectedContentBackgroundColor.CGColor : NSColor.clearColor.CGColor;
    }];
}
@end

@interface FractalGalleryItem : NSCollectionViewItem
@property NSImageView *preview;
@property NSProgressIndicator *spinner;
@property NSTextField *failure;
@property NSTextField *name;
@property NSTextField *details;
@end

static NSTextField *galleryLabel(NSString *text, NSFont *font, NSColor *color) {
    NSTextField *label = [NSTextField wrappingLabelWithString:text];
    label.font = font;
    label.textColor = color;
    label.selectable = NO;
    label.translatesAutoresizingMaskIntoConstraints = NO;
    return label;
}

@implementation FractalGalleryItem
- (void)loadView {
    FractalGalleryCardView *card = [FractalGalleryCardView new];
    card.wantsLayer = YES;
    self.preview = [NSImageView new];
    self.preview.imageScaling = NSImageScaleProportionallyUpOrDown;
    self.preview.wantsLayer = YES;
    self.preview.layer.cornerRadius = 4;
    self.preview.layer.masksToBounds = YES;
    self.preview.translatesAutoresizingMaskIntoConstraints = NO;
    self.spinner = [NSProgressIndicator new];
    self.spinner.style = NSProgressIndicatorStyleSpinning;
    self.spinner.controlSize = NSControlSizeSmall;
    self.spinner.displayedWhenStopped = NO;
    self.spinner.translatesAutoresizingMaskIntoConstraints = NO;
    self.failure = galleryLabel(@"", [NSFont systemFontOfSize:NSFont.smallSystemFontSize],
            NSColor.secondaryLabelColor);
    self.failure.alignment = NSTextAlignmentCenter;
    self.name = galleryLabel(@"", [NSFont boldSystemFontOfSize:NSFont.systemFontSize], NSColor.labelColor);
    self.name.maximumNumberOfLines = 2;
    // Wrapping labels report a one-line intrinsic height unless they know their width.
    self.name.preferredMaxLayoutWidth = PreviewWidth;
    self.details = galleryLabel(@"", [NSFont systemFontOfSize:NSFont.smallSystemFontSize],
            NSColor.secondaryLabelColor);
    self.details.maximumNumberOfLines = 4;
    self.details.lineBreakMode = NSLineBreakByWordWrapping;
    self.details.cell.truncatesLastVisibleLine = YES;
    self.details.preferredMaxLayoutWidth = PreviewWidth;
    for (NSView *view in @[self.preview, self.spinner, self.failure, self.name, self.details])
        [card addSubview:view];
    [NSLayoutConstraint activateConstraints:@[
        [self.preview.topAnchor constraintEqualToAnchor:card.topAnchor constant:CardPadding],
        [self.preview.leadingAnchor constraintEqualToAnchor:card.leadingAnchor constant:CardPadding],
        [self.preview.widthAnchor constraintEqualToConstant:PreviewWidth],
        [self.preview.heightAnchor constraintEqualToConstant:PreviewHeight],
        [self.spinner.centerXAnchor constraintEqualToAnchor:self.preview.centerXAnchor],
        [self.spinner.centerYAnchor constraintEqualToAnchor:self.preview.centerYAnchor],
        [self.failure.centerYAnchor constraintEqualToAnchor:self.preview.centerYAnchor],
        [self.failure.leadingAnchor constraintEqualToAnchor:self.preview.leadingAnchor],
        [self.failure.trailingAnchor constraintEqualToAnchor:self.preview.trailingAnchor],
        [self.name.topAnchor constraintEqualToAnchor:self.preview.bottomAnchor constant:6],
        [self.name.leadingAnchor constraintEqualToAnchor:self.preview.leadingAnchor],
        [self.name.trailingAnchor constraintEqualToAnchor:self.preview.trailingAnchor],
        [self.details.topAnchor constraintEqualToAnchor:self.name.bottomAnchor constant:3],
        [self.details.leadingAnchor constraintEqualToAnchor:self.preview.leadingAnchor],
        [self.details.trailingAnchor constraintEqualToAnchor:self.preview.trailingAnchor],
    ]];
    self.view = card;
}
- (void)setSelected:(BOOL)selected {
    [super setSelected:selected];
    ((FractalGalleryCardView *)self.view).selected = selected;
}
@end

@interface FractalGalleryHeader : NSView <NSCollectionViewElement>
@property NSTextField *title;
@end

@implementation FractalGalleryHeader
- (instancetype)initWithFrame:(NSRect)frame {
    self = [super initWithFrame:frame];
    if (self) {
        self.title = galleryLabel(@"", [NSFont boldSystemFontOfSize:NSFont.systemFontSize], NSColor.labelColor);
        [self addSubview:self.title];
        [NSLayoutConstraint activateConstraints:@[
            [self.title.leadingAnchor constraintEqualToAnchor:self.leadingAnchor constant:CardPadding],
            [self.title.bottomAnchor constraintEqualToAnchor:self.bottomAnchor constant:-4],
        ]];
    }
    return self;
}
@end

/** Return or Enter opens the selected place; a double click opens the place under the pointer. */
@interface FractalGalleryCollectionView : NSCollectionView
@property (weak) FractalGallerySession *session;
@end

@interface FractalGallerySession : NSObject <NSWindowDelegate, NSCollectionViewDataSource,
        NSCollectionViewDelegate, NSCollectionViewDelegateFlowLayout>
@property NSPanel *panel;
@property NSWindow *owner;
@property NSTextField *hint;
@property NSTextField *notice;
@property NSTextField *error;
@property NSProgressIndicator *loading;
@property NSScrollView *scroll;
@property FractalGalleryCollectionView *collection;
@property NSArray<NSString *> *sections;
@property NSArray<NSArray<NSString *> *> *sectionIds;
@property NSMutableDictionary<NSString *, NSString *> *names;
@property NSMutableDictionary<NSString *, NSString *> *descriptions;
@property NSMutableDictionary<NSString *, NSImage *> *images;
@property NSMutableSet<NSString *> *failed;
@property NSString *failureText;
@property BOOL finished;
@property JavaVM *vm;
@property jobject callback;
@property jmethodID opened;
@property jmethodID closed;
- (void)openItemAtIndexPath:(NSIndexPath *)path;
- (void)openSelected:(id)sender;
- (void)close:(id)sender;
@end

@implementation FractalGalleryCollectionView
- (void)keyDown:(NSEvent *)event {
    if (event.keyCode == 36 || event.keyCode == 76) [self.session openSelected:self];
    else [super keyDown:event];
}
- (void)mouseDown:(NSEvent *)event {
    [super mouseDown:event];
    if (event.clickCount != 2) return;
    NSIndexPath *path = [self indexPathForItemAtPoint:[self convertPoint:event.locationInWindow fromView:nil]];
    if (path) [self.session openItemAtIndexPath:path];
}
@end

/** Escape and the close button end the session through the same path as the Close button. */
@interface FractalGalleryWindow : NSPanel
@end

@implementation FractalGalleryWindow
- (void)cancelOperation:(id)sender {
    [(FractalGallerySession *)self.delegate close:sender];
}
@end

static JNIEnv *galleryEnvironment(JavaVM *vm, BOOL *attached) {
    JNIEnv *env = NULL;
    *attached = NO;
    jint status = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_8);
    if (status == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThreadAsDaemon(vm, (void **)&env, NULL) != JNI_OK) return NULL;
        *attached = YES;
    } else if (status != JNI_OK) return NULL;
    return env;
}

static BOOL galleryMainThread(JNIEnv *env) {
    if ([NSThread isMainThread]) return YES;
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"),
                    "AppKit gallery requires the main thread");
    return NO;
}

static NSString *galleryString(JNIEnv *env, jstring text) {
    if (!text) return @"";
    const jchar *characters = (*env)->GetStringChars(env, text, NULL);
    if (!characters) return @"";
    NSString *result = [NSString stringWithCharacters:(const unichar *)characters
                                              length:(NSUInteger)(*env)->GetStringLength(env, text)];
    (*env)->ReleaseStringChars(env, text, characters);
    return result;
}

static NSArray<NSString *> *galleryStrings(JNIEnv *env, jobjectArray values) {
    jsize count = values ? (*env)->GetArrayLength(env, values) : 0;
    NSMutableArray<NSString *> *result = [NSMutableArray arrayWithCapacity:(NSUInteger)count];
    for (jsize i = 0; i < count; i++) {
        jstring value = (jstring)(*env)->GetObjectArrayElement(env, values, i);
        [result addObject:galleryString(env, value)];
        if (value) (*env)->DeleteLocalRef(env, value);
    }
    return result;
}

static FractalGallerySession *gallerySession(jlong handle) {
    return (__bridge FractalGallerySession *)(void *)(intptr_t)handle;
}

@implementation FractalGallerySession
- (void)notifyId:(NSString *)identifier {
    if (self.finished) return;
    BOOL attached;
    JNIEnv *env = galleryEnvironment(self.vm, &attached);
    if (!env) return;
    if (identifier) {
        NSUInteger length = identifier.length;
        unichar *characters = malloc(MAX(length, 1) * sizeof(unichar));
        if (characters) {
            [identifier getCharacters:characters range:NSMakeRange(0, length)];
            jstring value = (*env)->NewString(env, (const jchar *)characters, (jsize)length);
            free(characters);
            if (value && !(*env)->ExceptionCheck(env))
                (*env)->CallVoidMethod(env, self.callback, self.opened, value);
            if (value) (*env)->DeleteLocalRef(env, value);
        }
    } else {
        (*env)->CallVoidMethod(env, self.callback, self.closed);
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if (attached) (*self.vm)->DetachCurrentThread(self.vm);
}
- (void)finish {
    if (self.finished) return;
    self.finished = YES;
    [self.owner removeChildWindow:self.panel];
    [self.panel orderOut:nil];
}
- (void)close:(id)sender {
    (void)sender;
    if (self.finished) return;
    // Report before finishing: once finished, the session sends nothing more to Java.
    [self notifyId:nil];
    [self finish];
}
- (BOOL)windowShouldClose:(NSWindow *)sender {
    (void)sender;
    [self close:nil];
    return NO;
}
- (void)openItemAtIndexPath:(NSIndexPath *)path {
    if (self.finished || (NSUInteger)path.section >= self.sectionIds.count) return;
    NSArray<NSString *> *ids = self.sectionIds[(NSUInteger)path.section];
    if ((NSUInteger)path.item >= ids.count) return;
    // Java decides: it closes the panel after opening the place, or keeps it open on failure.
    [self notifyId:ids[(NSUInteger)path.item]];
}
- (void)openSelected:(id)sender {
    (void)sender;
    NSIndexPath *path = self.collection.selectionIndexPaths.anyObject;
    if (path) [self openItemAtIndexPath:path];
    else NSBeep();
}
- (NSIndexPath *)indexPathForId:(NSString *)identifier {
    for (NSUInteger section = 0; section < self.sectionIds.count; section++) {
        NSUInteger item = [self.sectionIds[section] indexOfObject:identifier];
        if (item != NSNotFound) return [NSIndexPath indexPathForItem:(NSInteger)item inSection:(NSInteger)section];
    }
    return nil;
}
- (void)configure:(FractalGalleryItem *)item identifier:(NSString *)identifier {
    item.name.stringValue = self.names[identifier] ?: @"";
    item.details.stringValue = self.descriptions[identifier] ?: @"";
    item.view.toolTip = self.descriptions[identifier];
    item.view.accessibilityLabel = [NSString stringWithFormat:@"%@. %@", item.name.stringValue,
            item.details.stringValue];
    NSImage *image = self.images[identifier];
    BOOL failed = [self.failed containsObject:identifier];
    item.preview.image = image;
    item.failure.stringValue = failed ? self.failureText : @"";
    item.failure.hidden = !failed;
    if (image || failed) [item.spinner stopAnimation:nil];
    else [item.spinner startAnimation:nil];
}
- (void)refresh:(NSString *)identifier {
    NSIndexPath *path = [self indexPathForId:identifier];
    if (!path) return;
    FractalGalleryItem *item = (FractalGalleryItem *)[self.collection itemAtIndexPath:path];
    if (item) [self configure:item identifier:identifier];
}

- (NSInteger)numberOfSectionsInCollectionView:(NSCollectionView *)collectionView {
    (void)collectionView;
    return (NSInteger)self.sectionIds.count;
}
- (NSInteger)collectionView:(NSCollectionView *)collectionView numberOfItemsInSection:(NSInteger)section {
    (void)collectionView;
    return (NSInteger)self.sectionIds[(NSUInteger)section].count;
}
- (NSCollectionViewItem *)collectionView:(NSCollectionView *)collectionView
     itemForRepresentedObjectAtIndexPath:(NSIndexPath *)path {
    FractalGalleryItem *item = [collectionView makeItemWithIdentifier:FractalGalleryItemIdentifier forIndexPath:path];
    [self configure:item identifier:self.sectionIds[(NSUInteger)path.section][(NSUInteger)path.item]];
    return item;
}
- (NSView *)collectionView:(NSCollectionView *)collectionView
        viewForSupplementaryElementOfKind:(NSCollectionViewSupplementaryElementKind)kind
                              atIndexPath:(NSIndexPath *)path {
    FractalGalleryHeader *header = [collectionView makeSupplementaryViewOfKind:kind
            withIdentifier:FractalGalleryHeaderIdentifier forIndexPath:path];
    header.title.stringValue = self.sections[(NSUInteger)path.section];
    return header;
}
- (NSSize)collectionView:(NSCollectionView *)collectionView layout:(NSCollectionViewLayout *)layout
        referenceSizeForHeaderInSection:(NSInteger)section {
    (void)layout; (void)section;
    return NSMakeSize(collectionView.bounds.size.width, 30);
}
- (void)dealloc {
    // A session that failed during open may have neither a VM nor a global reference.
    if (!_vm || !_callback) return;
    BOOL attached;
    JNIEnv *env = galleryEnvironment(_vm, &attached);
    if (env) (*env)->DeleteGlobalRef(env, _callback);
    if (env && attached) (*_vm)->DetachCurrentThread(_vm);
}
@end

static NSButton *galleryButton(NSString *title, id target, SEL action) {
    NSButton *button = [NSButton buttonWithTitle:title target:target action:action];
    button.bezelStyle = NSBezelStyleRounded;
    button.translatesAutoresizingMaskIntoConstraints = NO;
    return button;
}

JNIEXPORT jlong JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_open
(JNIEnv *env, jobject callback, jlong windowHandle, jstring title, jstring hint, jstring closeTitle,
 jstring loadingText, jstring failureText) {
    if (!galleryMainThread(env)) return 0;
    NSWindow *owner = (__bridge NSWindow *)(void *)(intptr_t)windowHandle;
    if (!owner.isVisible) return 0;
    jclass type = (*env)->GetObjectClass(env, callback);
    FractalGallerySession *session = [FractalGallerySession new];
    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    session.vm = vm;
    session.opened = (*env)->GetMethodID(env, type, "opened", "(Ljava/lang/String;)V");
    session.closed = (*env)->GetMethodID(env, type, "closed", "()V");
    (*env)->DeleteLocalRef(env, type);
    if (!session.opened || !session.closed) return 0;
    session.callback = (*env)->NewGlobalRef(env, callback);
    if (!session.callback) return 0;
    session.owner = owner;
    session.names = [NSMutableDictionary new];
    session.descriptions = [NSMutableDictionary new];
    session.images = [NSMutableDictionary new];
    session.failed = [NSMutableSet new];
    session.sections = @[];
    session.sectionIds = @[];
    session.failureText = galleryString(env, failureText);

    NSPanel *panel = [[FractalGalleryWindow alloc] initWithContentRect:NSMakeRect(0, 0, 860, 620)
            styleMask:NSWindowStyleMaskTitled | NSWindowStyleMaskClosable | NSWindowStyleMaskResizable
            backing:NSBackingStoreBuffered defer:NO];
    panel.title = galleryString(env, title);
    panel.releasedWhenClosed = NO;
    panel.delegate = session;
    panel.hidesOnDeactivate = NO;
    panel.contentMinSize = NSMakeSize(PreviewWidth + 2 * CardPadding + 40, 360);
    session.panel = panel;

    session.hint = galleryLabel(galleryString(env, hint), [NSFont systemFontOfSize:NSFont.systemFontSize],
            NSColor.secondaryLabelColor);
    session.notice = galleryLabel(@"", [NSFont systemFontOfSize:NSFont.systemFontSize], NSColor.systemRedColor);
    session.notice.hidden = YES;
    session.error = galleryLabel(@"", [NSFont systemFontOfSize:NSFont.systemFontSize], NSColor.systemRedColor);
    session.error.hidden = YES;
    session.loading = [NSProgressIndicator new];
    session.loading.style = NSProgressIndicatorStyleSpinning;
    session.loading.displayedWhenStopped = NO;
    session.loading.accessibilityLabel = galleryString(env, loadingText);
    session.loading.translatesAutoresizingMaskIntoConstraints = NO;
    [session.loading startAnimation:nil];

    NSCollectionViewFlowLayout *layout = [NSCollectionViewFlowLayout new];
    layout.itemSize = NSMakeSize(PreviewWidth + 2 * CardPadding, 268);
    layout.minimumInteritemSpacing = 8;
    layout.minimumLineSpacing = 8;
    layout.sectionInset = NSEdgeInsetsMake(0, 8, 12, 8);
    session.collection = [FractalGalleryCollectionView new];
    session.collection.session = session;
    session.collection.collectionViewLayout = layout;
    session.collection.dataSource = session;
    session.collection.delegate = session;
    session.collection.selectable = YES;
    session.collection.allowsMultipleSelection = NO;
    session.collection.backgroundColors = @[NSColor.clearColor];
    [session.collection registerClass:FractalGalleryItem.class forItemWithIdentifier:FractalGalleryItemIdentifier];
    [session.collection registerClass:FractalGalleryHeader.class
           forSupplementaryViewOfKind:NSCollectionElementKindSectionHeader
                       withIdentifier:FractalGalleryHeaderIdentifier];
    session.scroll = [NSScrollView new];
    session.scroll.documentView = session.collection;
    session.scroll.hasVerticalScroller = YES;
    session.scroll.drawsBackground = NO;
    session.scroll.hidden = YES;
    session.scroll.translatesAutoresizingMaskIntoConstraints = NO;

    NSButton *close = galleryButton(galleryString(env, closeTitle), session, @selector(close:));
    NSView *content = panel.contentView;
    for (NSView *view in @[session.hint, session.notice, session.error, session.loading, session.scroll, close])
        [content addSubview:view];
    [NSLayoutConstraint activateConstraints:@[
        [session.hint.topAnchor constraintEqualToAnchor:content.topAnchor constant:16],
        [session.hint.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:20],
        [session.hint.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-20],
        [session.notice.topAnchor constraintEqualToAnchor:session.hint.bottomAnchor constant:6],
        [session.notice.leadingAnchor constraintEqualToAnchor:session.hint.leadingAnchor],
        [session.notice.trailingAnchor constraintEqualToAnchor:session.hint.trailingAnchor],
        [session.error.topAnchor constraintEqualToAnchor:session.notice.bottomAnchor constant:12],
        [session.error.leadingAnchor constraintEqualToAnchor:session.hint.leadingAnchor],
        [session.error.trailingAnchor constraintEqualToAnchor:session.hint.trailingAnchor],
        [session.loading.centerXAnchor constraintEqualToAnchor:session.scroll.centerXAnchor],
        [session.loading.centerYAnchor constraintEqualToAnchor:session.scroll.centerYAnchor],
        [session.scroll.topAnchor constraintEqualToAnchor:session.notice.bottomAnchor constant:10],
        [session.scroll.leadingAnchor constraintEqualToAnchor:content.leadingAnchor constant:12],
        [session.scroll.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-12],
        [session.scroll.bottomAnchor constraintEqualToAnchor:close.topAnchor constant:-12],
        [close.trailingAnchor constraintEqualToAnchor:content.trailingAnchor constant:-20],
        [close.bottomAnchor constraintEqualToAnchor:content.bottomAnchor constant:-16],
    ]];

    // Center over the owner and keep the panel inside the visible part of its screen.
    NSRect visible = (owner.screen ?: NSScreen.mainScreen).visibleFrame;
    NSRect frame = panel.frame;
    frame.size.width = MIN(frame.size.width, visible.size.width);
    frame.size.height = MIN(frame.size.height, visible.size.height);
    frame.origin.x = NSMidX(owner.frame) - frame.size.width / 2;
    frame.origin.y = NSMidY(owner.frame) - frame.size.height / 2;
    frame.origin.x = MAX(NSMinX(visible), MIN(frame.origin.x, NSMaxX(visible) - frame.size.width));
    frame.origin.y = MAX(NSMinY(visible), MIN(frame.origin.y, NSMaxY(visible) - frame.size.height));
    [panel setFrame:frame display:NO];
    [owner addChildWindow:panel ordered:NSWindowAbove];
    panel.initialFirstResponder = session.collection;
    [panel makeKeyAndOrderFront:nil];
    [panel makeFirstResponder:session.collection];
    return (jlong)(intptr_t)(__bridge_retained void *)session;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_showError
(JNIEnv *env, jclass type, jlong handle, jstring message) {
    (void)type;
    if (!galleryMainThread(env) || !handle) return;
    FractalGallerySession *session = gallerySession(handle);
    [session.loading stopAnimation:nil];
    session.scroll.hidden = YES;
    session.error.stringValue = galleryString(env, message);
    session.error.hidden = NO;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_showEntries
(JNIEnv *env, jclass type, jlong handle, jstring notice, jobjectArray sections, jintArray sectionSizes,
 jobjectArray ids, jobjectArray names, jobjectArray descriptions) {
    (void)type;
    if (!galleryMainThread(env) || !handle) return;
    FractalGallerySession *session = gallerySession(handle);
    NSArray<NSString *> *sectionTitles = galleryStrings(env, sections);
    NSArray<NSString *> *identifiers = galleryStrings(env, ids);
    NSArray<NSString *> *titles = galleryStrings(env, names);
    NSArray<NSString *> *details = galleryStrings(env, descriptions);
    jsize count = sectionSizes ? (*env)->GetArrayLength(env, sectionSizes) : 0;
    jint *sizes = count ? (*env)->GetIntArrayElements(env, sectionSizes, NULL) : NULL;
    NSMutableArray<NSArray<NSString *> *> *grouped = [NSMutableArray new];
    NSUInteger next = 0;
    for (jsize i = 0; i < count && sizes; i++) {
        NSUInteger size = (NSUInteger)MAX(0, sizes[i]);
        if (next + size > identifiers.count) break;
        [grouped addObject:[identifiers subarrayWithRange:NSMakeRange(next, size)]];
        next += size;
    }
    if (sizes) (*env)->ReleaseIntArrayElements(env, sectionSizes, sizes, JNI_ABORT);
    for (NSUInteger i = 0; i < identifiers.count && i < titles.count && i < details.count; i++) {
        session.names[identifiers[i]] = titles[i];
        session.descriptions[identifiers[i]] = details[i];
    }
    session.sections = [sectionTitles subarrayWithRange:NSMakeRange(0, MIN(sectionTitles.count, grouped.count))];
    session.sectionIds = [grouped subarrayWithRange:NSMakeRange(0, session.sections.count)];
    NSString *noticeText = galleryString(env, notice);
    session.notice.stringValue = noticeText;
    session.notice.hidden = noticeText.length == 0;
    [session.loading stopAnimation:nil];
    session.error.hidden = YES;
    session.scroll.hidden = NO;
    [session.collection reloadData];
    // Select the first place so arrows move from it and Return opens it, as with focus elsewhere.
    if (session.sectionIds.firstObject.count) {
        session.collection.selectionIndexPaths = [NSSet setWithObject:[NSIndexPath indexPathForItem:0 inSection:0]];
    }
    [session.panel makeFirstResponder:session.collection];
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_setPreview
(JNIEnv *env, jclass type, jlong handle, jstring identifier, jintArray pixels, jint width, jint height) {
    (void)type;
    if (!galleryMainThread(env) || !handle || width < 1 || height < 1) return;
    if ((*env)->GetArrayLength(env, pixels) != width * height) return;
    FractalGallerySession *session = gallerySession(handle);
    NSBitmapImageRep *rep = [[NSBitmapImageRep alloc] initWithBitmapDataPlanes:NULL
            pixelsWide:width pixelsHigh:height bitsPerSample:8 samplesPerPixel:4 hasAlpha:YES
            isPlanar:NO colorSpaceName:NSDeviceRGBColorSpace
            bitmapFormat:NSBitmapFormatAlphaNonpremultiplied bytesPerRow:width * 4 bitsPerPixel:32];
    jint *colors = (*env)->GetIntArrayElements(env, pixels, NULL);
    if (!rep || !colors) {
        if (colors) (*env)->ReleaseIntArrayElements(env, pixels, colors, JNI_ABORT);
        return;
    }
    unsigned char *bytes = rep.bitmapData;
    for (jint i = 0; i < width * height; i++) {
        bytes[i * 4] = (colors[i] >> 16) & 255;
        bytes[i * 4 + 1] = (colors[i] >> 8) & 255;
        bytes[i * 4 + 2] = colors[i] & 255;
        bytes[i * 4 + 3] = ((uint32_t)colors[i] >> 24) & 255;
    }
    (*env)->ReleaseIntArrayElements(env, pixels, colors, JNI_ABORT);
    // The raster has physical pixels; the image keeps the card's size in points.
    NSImage *image = [[NSImage alloc] initWithSize:NSMakeSize(PreviewWidth, PreviewHeight)];
    [image addRepresentation:rep];
    NSString *key = galleryString(env, identifier);
    session.images[key] = image;
    [session.failed removeObject:key];
    [session refresh:key];
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_setPreviewFailed
(JNIEnv *env, jclass type, jlong handle, jstring identifier) {
    (void)type;
    if (!galleryMainThread(env) || !handle) return;
    FractalGallerySession *session = gallerySession(handle);
    NSString *key = galleryString(env, identifier);
    [session.images removeObjectForKey:key];
    [session.failed addObject:key];
    [session refresh:key];
}

JNIEXPORT jdouble JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_backingScale
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!galleryMainThread(env) || !handle) return 1;
    return gallerySession(handle).panel.backingScaleFactor;
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_toFront
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!galleryMainThread(env) || !handle) return;
    FractalGallerySession *session = gallerySession(handle);
    if (!session.finished) [session.panel makeKeyAndOrderFront:nil];
}

JNIEXPORT void JNICALL Java_com_shangin_fractal_ui_MacGalleryPanel_dispose
(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    if (!galleryMainThread(env) || !handle) return;
    FractalGallerySession *session = (__bridge_transfer FractalGallerySession *)(void *)(intptr_t)handle;
    // Closing from Java reports nothing back: Java already knows the session ended.
    [session finish];
}
